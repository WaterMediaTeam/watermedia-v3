package org.watermedia.api.media.players.util;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;

import java.util.ArrayDeque;

/**
 * Thread-safe queue of AVPackets between the demux thread and decode threads.
 *
 * Unlike FrameQueue (fixed ring buffer), PacketQueue uses an
 * ArrayDeque because:
 * - Packets are small (metadata + pointer to compressed data)
 * - Packet count varies widely (keyframes are large, P-frames are small)
 * - No need to pre-allocate slots — av_packet_clone is cheap
 *
 * SERIAL SYSTEM:
 * Each flush() increments the serial. The decode thread compares the packet
 * serial with the current serial to discard stale packets after a seek.
 *
 * CAPACITY:
 * No packet count limit, but a byte total limit (maxBytes).
 * If the demux thread exceeds the limit, put() blocks until the
 * decode thread consumes enough packets. This prevents OOM on
 * network streams with large buffers.
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

        Entry(AVPacket pkt, int serial) {
            this.packet = pkt;
            this.serial = serial;
        }
    }

    /**
     * @param maxBytes byte limit for the queue. 16MB is reasonable for video.
     *                 Use Long.MAX_VALUE for no limit.
     */
    public PacketQueue(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Enqueues a packet. Does av_packet_clone internally.
     * The caller retains ownership of its original packet.
     *
     * BLOCKS if totalBytes >= maxBytes (backpressure to the demux thread).
     *
     * @return false if the queue was aborted.
     */
    public boolean put(AVPacket packet) {
        AVPacket clone = avcodec.av_packet_clone(packet);
        if (clone == null) return false;

        synchronized (this.lock) {
            while (this.totalBytes >= this.maxBytes && !this.aborted) {
                try {
                    this.lock.wait();
                } catch (InterruptedException e) {
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
     * The packet is NOT cloned or consumed on failure.
     */
    public boolean tryPut(AVPacket packet) {
        AVPacket clone = avcodec.av_packet_clone(packet);
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
     * Dequeues a packet. BLOCKS if empty.
     * The caller receives ownership of the AVPacket — must call av_packet_free.
     *
     * @param serialOut array of at least 1 element. serialOut[0] receives
     *                  the packet serial for post-seek comparison.
     * @return packet owned by the caller, or null if aborted.
     */
    public AVPacket get(int[] serialOut) {
        synchronized (this.lock) {
            while (this.packets.isEmpty() && !this.aborted && !this.finished) {
                try {
                    this.lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted) return null;
            // FINISHED + EMPTY: EOF, NO MORE PACKETS COMING
            if (this.packets.isEmpty()) return null;

            Entry entry = this.packets.pollFirst();
            this.totalBytes -= entry.packet.size();
            serialOut[0] = entry.serial;
            this.lock.notifyAll();
            return entry.packet;
        }
    }

    /**
     * Flush: discards all packets and increments the serial.
     * Decode threads that receive packets with the old serial
     * will discard them without decoding.
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
     * Clear: remove all packets WITHOUT changing the serial.
     * Used during precise seek when the demux thread drains the codec
     * synchronously — the serial must stay the same so the decode thread
     * doesn't re-flush the codec on the next packet.
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

    /** CURRENT SERIAL. CHANGES WITH EACH flush(). */
    public int serial() { return this.serial; }

    /** TOTAL BYTES ENQUEUED. */
    public long byteSize() {
        synchronized (this.lock) { return this.totalBytes; }
    }

    /** NUMBER OF PACKETS ENQUEUED. */
    public int count() {
        synchronized (this.lock) { return this.packets.size(); }
    }

    /**
     * Signal EOF: no more packets will be added.
     * get() will drain remaining packets, then return null.
     * Unlike abort(), this does NOT discard pending packets.
     */
    public void finish() {
        synchronized (this.lock) {
            this.finished = true;
            this.lock.notifyAll();
        }
    }

    /** ABORT — UNBLOCKS get() AND put() IMMEDIATELY, DISCARDS PENDING PACKETS. */
    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** RESET FLAGS TO REUSE THE QUEUE (E.G., QUALITY SWITCH). */
    public void reset() {
        synchronized (this.lock) {
            this.flush();
            this.aborted = false;
            this.finished = false;
        }
    }

    /** RELEASES ALL PENDING PACKETS. */
    public void free() {
        this.flush();
    }
}
