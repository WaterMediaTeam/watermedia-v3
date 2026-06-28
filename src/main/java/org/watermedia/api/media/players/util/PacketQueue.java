package org.watermedia.api.media.players.util;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;

import java.util.ArrayDeque;

/**
 * Thread-safe queue of AVPackets between the demux thread and the decode threads.
 * <p>
 * Unlike {@link FrameQueue} (a fixed ring buffer), PacketQueue uses an {@link ArrayDeque} because:
 * <ul>
 *   <li>Packets are small (metadata plus a pointer to compressed data)</li>
 *   <li>Packet count varies widely (keyframes are large, P-frames are small)</li>
 *   <li>No need to pre-allocate slots — {@code av_packet_clone} is cheap</li>
 * </ul>
 * Serial system: each {@link #flush()} increments the serial. The decode thread compares the
 * packet serial with the current serial to discard stale packets after a seek.
 * <p>
 * Capacity: no packet count limit, but a byte total limit ({@code maxBytes}). If the demux thread
 * exceeds the limit, {@link #put(AVPacket)} blocks until the decode thread consumes enough packets.
 * This prevents OOM on network streams with large buffers.
 */
public final class PacketQueue {
    private final ArrayDeque<Entry> packets = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile boolean aborted;
    private volatile boolean finished; // EOF: DRAIN REMAINING, THEN RETURN NULL
    private volatile int serial;
    private long totalBytes;
    private final long maxBytes;

    private static final class Entry {
        final AVPacket packet; // OWNED — CLONED IN put()
        final int serial;

        Entry(final AVPacket packet, final int serial) {
            this.packet = packet;
            this.serial = serial;
        }
    }

    /**
     * @param maxBytes byte limit for the queue. 16MB is reasonable for video.
     *                 Use Long.MAX_VALUE for no limit.
     */
    public PacketQueue(final long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Enqueues a packet. Does {@code av_packet_clone} internally.
     * The caller retains ownership of its original packet.
     * <p>
     * Blocks if {@code totalBytes >= maxBytes} (backpressure to the demux thread).
     *
     * @return false if the queue was aborted.
     */
    public boolean put(final AVPacket packet) {
        final AVPacket clone = avcodec.av_packet_clone(packet);
        if (clone == null) return false;

        synchronized (this.lock) {
            while (this.totalBytes >= this.maxBytes && !this.aborted) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    avcodec.av_packet_free(clone);
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (this.aborted) {
                avcodec.av_packet_free(clone);
                return false;
            }

            this.packets.addLast(new Entry(clone, this.serial));
            this.totalBytes += clone.size();
            this.lock.notifyAll();
        }
        return true;
    }

    /**
     * Non-blocking put. Returns false immediately if the queue is full.
     * The caller's original packet is not consumed on failure.
     */
    public boolean tryPut(final AVPacket packet) {
        final AVPacket clone = avcodec.av_packet_clone(packet);
        if (clone == null) return false;

        synchronized (this.lock) {
            if (this.totalBytes >= this.maxBytes || this.aborted) {
                avcodec.av_packet_free(clone);
                return false;
            }
            this.packets.addLast(new Entry(clone, this.serial));
            this.totalBytes += clone.size();
            this.lock.notifyAll();
        }
        return true;
    }

    /**
     * Dequeues a packet. Blocks if empty.
     * The caller receives ownership of the AVPacket — must call {@code av_packet_free}.
     *
     * @param serialOut array of at least 1 element. {@code serialOut[0]} receives
     *                  the packet serial for post-seek comparison.
     * @return packet owned by the caller, or null if aborted.
     */
    public AVPacket get(final int[] serialOut) {
        synchronized (this.lock) {
            while (this.packets.isEmpty() && !this.aborted && !this.finished) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted) return null;
            // FINISHED + EMPTY: EOF, NO MORE PACKETS COMING
            if (this.packets.isEmpty()) return null;

            final Entry entry = this.packets.pollFirst();
            this.totalBytes -= entry.packet.size();
            serialOut[0] = entry.serial;
            this.lock.notifyAll();
            return entry.packet;
        }
    }

    /**
     * Flush: discards all packets and increments the serial.
     * Decode threads that receive packets with the old serial will discard them without decoding.
     */
    public void flush() {
        synchronized (this.lock) {
            while (!this.packets.isEmpty()) {
                avcodec.av_packet_free(this.packets.pollFirst().packet);
            }
            this.totalBytes = 0;
            this.serial++;
            this.lock.notifyAll();
        }
    }

    /**
     * Clear: removes all packets without changing the serial.
     * Used during a precise seek when the demux thread drains the codec synchronously — the
     * serial must stay the same so the decode thread doesn't re-flush the codec on the next packet.
     */
    public void clear() {
        synchronized (this.lock) {
            while (!this.packets.isEmpty()) {
                avcodec.av_packet_free(this.packets.pollFirst().packet);
            }
            this.totalBytes = 0;
            this.lock.notifyAll();
        }
    }

    /** Current serial. Changes with each {@link #flush()}. */
    public int serial() { return this.serial; }

    /**
     * True when a {@code null} from {@link #get(int[])} means a clean end-of-stream
     * ({@link #finish()} was signalled) rather than a {@link #abort()} teardown.
     * The decode threads use this to decide whether to flush the decoder's buffered
     * frames at EOF — on an abort those frames are stale and must be dropped.
     */
    public boolean endOfFile() { return this.finished && !this.aborted; }

    /** Total bytes enqueued. */
    public long byteSize() {
        synchronized (this.lock) { return this.totalBytes; }
    }

    /** Number of packets enqueued. */
    public int count() {
        synchronized (this.lock) { return this.packets.size(); }
    }

    /**
     * Signals EOF: no more packets will be added.
     * {@link #get(int[])} will drain the remaining packets, then return null.
     * Unlike {@link #abort()}, this does not discard pending packets.
     */
    public void finish() {
        synchronized (this.lock) {
            this.finished = true;
            this.lock.notifyAll();
        }
    }

    /** Abort — unblocks {@link #get(int[])} and {@link #put(AVPacket)} immediately, discards pending packets. */
    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** Resets the flags to reuse the queue (e.g. on a quality switch). */
    public void reset() {
        synchronized (this.lock) {
            this.flush();
            this.aborted = false;
            this.finished = false;
        }
    }

    /** Releases all pending packets. */
    public void free() {
        this.flush();
    }
}
