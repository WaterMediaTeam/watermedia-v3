package org.watermedia.api.media.players.util;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;

/**
 * Thread-safe ring buffer of decoded AVFrames.
 * Follows the ffplay pattern: pre-allocated slots with explicit ownership.
 * <p>
 * Threading:
 * <ul>
 *   <li>Exactly one writer thread (decode)</li>
 *   <li>Exactly one reader thread (render/caller)</li>
 *   <li>Internal synchronization via {@code synchronized} plus wait/notify</li>
 * </ul>
 * Memory:
 * <ul>
 *   <li>AVFrame slots are pre-allocated in the constructor with {@code av_frame_alloc()}</li>
 *   <li>Pixel buffers inside each slot are owned by the slot between {@link #push()} and {@link #next()}</li>
 *   <li>Each slot is reused indefinitely (no GC of AVFrame structs)</li>
 *   <li>{@link #free()} releases the AVFrame structs — call only during final cleanup</li>
 * </ul>
 * Recommended capacity:
 * <ul>
 *   <li>Video: 3 slots (~24MB for 1080p BGRA, absorbs decode jitter)</li>
 *   <li>Audio: 9 slots (~144KB for 48kHz stereo S16, low latency)</li>
 * </ul>
 */
public final class FrameQueue {

    /** Wrapper for a slot with its AVFrame and presentation metadata. */
    public static final class Slot {
        /** Pre-allocated AVFrame. Pixel data is filled via {@code av_frame_move_ref}. */
        public final AVFrame frame;

        /** PTS in milliseconds. Set by the writer before {@link FrameQueue#push()}. */
        public long ptsMs;

        /** Estimated frame duration in milliseconds. */
        public long durationMs;

        /**
         * Serial of the {@link PacketQueue} at decode time.
         * The reader compares this with the clock serial to discard frames from a previous seek.
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
    public FrameQueue(final int capacity) {
        this.capacity = capacity;
        this.queue = new Slot[capacity];
        for (int i = 0; i < capacity; i++) {
            this.queue[i] = new Slot();
        }
    }

    // WRITER SIDE (CALL FROM DECODE THREAD)
    /**
     * Gets the next available slot for writing.
     * Blocks if the queue is full, this is intentional backpressure.
     * <p>
     * Caller contract:
     * <ol>
     *   <li>Call {@link #peekWritable()} to get a slot</li>
     *   <li>{@code av_frame_move_ref(slot.frame, decodedFrame)}</li>
     *   <li>Fill {@code slot.ptsMs}, {@code slot.serial}, etc.</li>
     *   <li>Call {@link #push()}</li>
     * </ol>
     *
     * @return writable slot, or null if the queue was aborted.
     */
    public Slot peekWritable() {
        synchronized (this.lock) {
            while (this.size >= this.capacity && !this.aborted) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted) return null;
            return this.queue[this.writeIndex];
        }
    }

    /**
     * Confirms that the slot at {@code writeIndex} has been filled.
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
     * Non-blocking — returns null immediately if empty.
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
     * Blocking version of {@link #peek()}.
     * Blocks until at least one frame is available, or until the timeout elapses.
     *
     * @param timeoutMs maximum wait time in milliseconds. 0 = indefinite.
     * @return readable slot, or null if aborted or timed out.
     */
    public Slot peekBlocking(final long timeoutMs) {
        synchronized (this.lock) {
            final long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
            while (this.size <= 0 && !this.aborted) {
                final long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    this.lock.wait(Math.min(remaining, 10));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted || this.size <= 0) return null;
            return this.queue[this.readIndex];
        }
    }

    /**
     * Peeks at the next frame after the current one (for computing inter-frame duration).
     * Requires {@code size >= 2}.
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
     * Consumes the current frame: advances {@code readIndex} and releases pixel buffers.
     * Calls {@code av_frame_unref} internally — pixel buffers are returned to the FFmpeg pool.
     * <p>
     * After {@link #next()}, the previous slot is no longer valid.
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
    /** Frames available for reading. */
    public int remaining() {
        synchronized (this.lock) { return this.size; }
    }

    /** Returns true if the queue is empty. */
    public boolean isEmpty() {
        synchronized (this.lock) { return this.size == 0; }
    }

    /**
     * Discards all pending frames. Calls {@code av_frame_unref} on each.
     * Use after a seek to clear frames from the previous timestamp.
     */
    public void flush() {
        synchronized (this.lock) {
            for (int i = 0; i < this.size; i++) {
                final int idx = (this.readIndex + i) % this.capacity;
                avutil.av_frame_unref(this.queue[idx].frame);
            }
            this.readIndex = 0;
            this.writeIndex = 0;
            this.size = 0;
            this.lock.notifyAll();
        }
    }

    /** Abort signal — unblocks {@link #peekWritable()} and {@link #peekBlocking(long)} immediately. */
    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** Resets the abort flag to reuse the queue (e.g. on a quality switch). */
    public void reset() {
        synchronized (this.lock) {
            this.flush();
            this.aborted = false;
        }
    }

    /**
     * Releases the AVFrame structs. Call only during final player cleanup.
     * After {@link #free()}, the queue is not reusable.
     */
    public void free() {
        this.flush();
        for (int i = 0; i < this.capacity; i++) {
            this.queue[i].destroy();
        }
    }
}
