package org.watermedia.api.media;

import org.watermedia.api.media.MediaPlayer.Status;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Snapshot of a {@link ServerMediaPlayer}: everything a following player needs, taken with
 * {@link ServerMediaPlayer#snapshot()} on one side and fed to {@link MediaPlayer#sync(Sync)} on the other.
 * {@link #toBytes()} and {@link #read(ByteBuffer)} give it a fixed {@value #BYTES}-byte big-endian form for
 * hosts whose transport carries raw bytes.
 *
 * @param revision clock mutation counter at capture time, for the host to tell snapshots apart
 * @param status   playback status at capture time
 * @param time     playback position in milliseconds at capture time
 * @param duration total media duration in milliseconds, or 0 when unknown
 * @param speed    playback speed factor, in (0, 4]
 * @param repeat   whether playback loops at the end of the media
 * @param live     whether the media source is a live stream
 */
public record Sync(int revision, Status status, long time, long duration, float speed, boolean repeat, boolean live) {

    /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
    public static final int BYTES = 27;

    private static final byte VERSION = 3;
    private static final int REPEAT_BIT = 0b01;
    private static final int LIVE_BIT = 0b10;

    public Sync {
        if (status == null) throw new IllegalArgumentException("Status cannot be null");
        if (time < 0 || duration < 0) throw new IllegalArgumentException("Time and duration cannot be negative");
        // NaN FAILS BOTH COMPARISONS — THIS ALSO SHIELDS AGAINST CORRUPTED FLOAT PAYLOADS
        if (!(speed > 0f && speed <= 4f)) throw new IllegalArgumentException("Speed must be in (0, 4], got " + speed);
    }

    /** Encodes this snapshot into its fixed binary form, a new heap buffer positioned at zero and ready to transmit. */
    public ByteBuffer toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
        buf.put(VERSION);
        buf.putInt(this.revision);
        buf.put((byte) this.status.ordinal());
        buf.putLong(this.time);
        buf.putLong(this.duration);
        buf.putFloat(this.speed);
        buf.put((byte) ((this.repeat ? REPEAT_BIT : 0) | (this.live ? LIVE_BIT : 0)));
        return buf.flip();
    }

    /**
     * Decodes a snapshot produced by {@link #toBytes()}. Exactly {@value #BYTES} bytes are consumed and anything after
     * them is left in the buffer, so a snapshot may travel inside a larger frame carrying the host's own routing fields;
     * the buffer's byte order is irrelevant (the wire is big-endian) and a rejected payload leaves the position untouched.
     * @param buf a payload produced by {@link #toBytes()}
     * @return the decoded snapshot
     * @throws IllegalArgumentException if the payload is null, truncated, of another version or holds out-of-range values
     */
    public static Sync read(final ByteBuffer buf) {
        if (buf == null || buf.remaining() < BYTES) throw new IllegalArgumentException("Snapshot payload is null or truncated, needs " + BYTES + " bytes");
        // DECODE FROM A BIG-ENDIAN VIEW SO THE CALLER'S BYTE ORDER NEVER MATTERS, AND ADVANCE THE CALLER'S POSITION ONLY
        // ONCE THE WHOLE SNAPSHOT PARSED
        final ByteBuffer view = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
        final byte version = view.get();
        if (version != VERSION) throw new IllegalArgumentException("Unsupported snapshot version: " + version);
        final int revision = view.getInt();
        final Status status = Status.of(view.get() & 0xFF);
        final long time = view.getLong();
        final long duration = view.getLong();
        final float speed = view.getFloat();
        final byte flags = view.get();
        final Sync sync = new Sync(revision, status, time, duration, speed, (flags & REPEAT_BIT) != 0, (flags & LIVE_BIT) != 0);
        buf.position(view.position());
        return sync;
    }
}
