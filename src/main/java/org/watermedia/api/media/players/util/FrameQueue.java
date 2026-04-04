package org.watermedia.api.media.players.util;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;

/**
 * Thread-safe ring buffer of decoded AVFrames.
 * Follows the ffplay pattern: pre-allocated slots with explicit ownership.
 *
 * THREADING:
 * - Exactly ONE writer thread (decode)
 * - Exactly ONE reader thread (render/caller)
 * - Internal synchronization via synchronized + wait/notify
 *
 * MEMORY:
 * - AVFrame slots are pre-allocated in the constructor with av_frame_alloc()
 * - Pixel buffers inside each slot are owned by the slot between push() and next()
 * - Each slot is reused indefinitely (no GC of AVFrame structs)
 * - free() releases the AVFrame structs — call ONLY during final cleanup
 *
 * RECOMMENDED CAPACITY:
 * - Video: 3 slots (~24MB for 1080p BGRA, absorbs decode jitter)
 * - Audio: 9 slots (~144KB for 48kHz stereo S16, low latency)
 */
public final class FrameQueue {

    /** WRAPPER FOR A SLOT WITH ITS AVFrame AND PRESENTATION METADATA */
    public static final class Slot {
        /** PRE-ALLOCATED AVFrame. PIXEL DATA IS FILLED VIA av_frame_move_ref. */
        public final AVFrame frame;

        /** PTS IN MILLISECONDS. SET BY THE WRITER BEFORE push(). */
        public long ptsMs;

        /** ESTIMATED FRAME DURATION IN MILLISECONDS. */
        public long durationMs;

        /**
         * SERIAL OF THE PacketQueue AT DECODE TIME.
         * THE READER COMPARES THIS WITH THE CLOCK SERIAL TO
         * DISCARD FRAMES FROM A PREVIOUS SEEK.
         */
        public int serial;

        // CACHED TO DETECT MID-STREAM FORMAT CHANGES WITHOUT ACCESSING THE AVFrame
        public int width;
        public int height;
        public int format;

        Slot() {
            this.frame = avutil.av_frame_alloc();
            if (this.frame == null) {
                throw new OutOfMemoryError("av_frame_alloc failed");
            }
        }

        void destroy() {
            avutil.av_frame_free(this.frame);
        }
    }

    private final Slot[] queue;
    private final int capacity;
    private int readIndex;
    private int writeIndex;
    private int size;
    private volatile boolean aborted;
    private final Object lock = new Object();

    /**
     * @param capacity number of slots. Typically 3 (video) or 9 (audio).
     */
    public FrameQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new Slot[capacity];
        for (int i = 0; i < capacity; i++) {
            this.queue[i] = new Slot();
        }
    }

    // WRITER SIDE (CALL FROM DECODE THREAD)
    /**
     * Gets the next available slot for writing.
     * BLOCKS if the queue is full — this is intentional backpressure.
     *
     * CALLER CONTRACT:
     * 1. Call peekWritable() to get slot
     * 2. av_frame_move_ref(slot.frame, decodedFrame)
     * 3. Fill slot.ptsMs, slot.serial, etc.
     * 4. Call push()
     *
     * @return writable slot, or null if the queue was aborted.
     */
    public Slot peekWritable() {
        synchronized (this.lock) {
            while (this.size >= this.capacity && !this.aborted) {
                try {
                    this.lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted) return null;
            return this.queue[this.writeIndex];
        }
    }

    /**
     * Confirms that the slot at writeIndex has been filled.
     * After this, the frame is visible to the reader.
     */
    public void push() {
        synchronized (this.lock) {
            this.writeIndex = (this.writeIndex + 1) % this.capacity;
            this.size++;
            this.lock.notifyAll();
        }
    }

    // READER SIDE (CALL FROM RENDER/CALLER THREAD)
    /**
     * Gets the oldest frame without consuming it.
     * NON-BLOCKING — returns null immediately if empty.
     *
     * @return readable slot, or null if empty or aborted.
     */
    public Slot peek() {
        synchronized (this.lock) {
            if (this.size <= 0 || this.aborted) return null;
            return this.queue[this.readIndex];
        }
    }

    /**
     * Blocking version of peek().
     * BLOCKS until at least one frame is available, or timeout.
     *
     * @param timeoutMs maximum wait time in milliseconds. 0 = indefinite.
     * @return readable slot, or null if aborted or timed out.
     */
    public Slot peekBlocking(long timeoutMs) {
        synchronized (this.lock) {
            long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
            while (this.size <= 0 && !this.aborted) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    this.lock.wait(Math.min(remaining, 10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted || this.size <= 0) return null;
            return this.queue[this.readIndex];
        }
    }

    /**
     * Peek at the NEXT frame after the current one (for computing inter-frame duration).
     * Requires size >= 2.
     *
     * @return second slot in the queue, or null if not available.
     */
    public Slot peekNext() {
        synchronized (this.lock) {
            if (this.size < 2 || this.aborted) return null;
            return this.queue[(this.readIndex + 1) % this.capacity];
        }
    }

    /**
     * Consumes the current frame: advances readIndex and releases pixel buffers.
     * Calls av_frame_unref internally — pixel buffers are returned to the FFmpeg pool.
     *
     * After next(), the previous slot is no longer valid.
     */
    public void next() {
        synchronized (this.lock) {
            avutil.av_frame_unref(this.queue[this.readIndex].frame);
            this.readIndex = (this.readIndex + 1) % this.capacity;
            this.size--;
            this.lock.notifyAll();
        }
    }

    // CONTROL
    /** FRAMES AVAILABLE FOR READING. */
    public int remaining() {
        synchronized (this.lock) { return this.size; }
    }

    /** RETURNS TRUE IF THE QUEUE IS EMPTY. */
    public boolean isEmpty() {
        synchronized (this.lock) { return this.size == 0; }
    }

    /**
     * Discards ALL pending frames. Calls av_frame_unref on each.
     * Use after seek to clear frames from the previous timestamp.
     */
    public void flush() {
        synchronized (this.lock) {
            for (int i = 0; i < this.size; i++) {
                int idx = (this.readIndex + i) % this.capacity;
                avutil.av_frame_unref(this.queue[idx].frame);
            }
            this.readIndex = 0;
            this.writeIndex = 0;
            this.size = 0;
            this.lock.notifyAll();
        }
    }

    /** ABORT SIGNAL — UNBLOCKS peekWritable AND peekBlocking IMMEDIATELY. */
    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** RESET ABORT FLAG TO REUSE THE QUEUE (E.G., QUALITY SWITCH). */
    public void reset() {
        synchronized (this.lock) {
            this.flush();
            this.aborted = false;
        }
    }

    /**
     * Releases the AVFrame structs. Call ONLY during final player cleanup.
     * After free(), the queue is not reusable.
     */
    public void free() {
        this.flush();
        for (int i = 0; i < this.capacity; i++) {
            this.queue[i].destroy();
        }
    }
}
