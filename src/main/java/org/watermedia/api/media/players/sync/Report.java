package org.watermedia.api.media.players.sync;

import org.watermedia.api.media.players.MediaPlayer.Status;

import java.nio.ByteBuffer;

/**
 * Upstream status report: a follower notifies its playback state plus the media facts
 * only clients can know — total duration and the live flag. Sent on every status
 * transition and as a periodic keepalive; a report reaching {@code PAUSED} or
 * {@code PLAYING} marks the follower as ready for lockstep gating.
 *
 * @param watcherId follower instance id given in the original {@link Watch}
 * @param status    current playback status of the follower
 * @param duration  total media duration in milliseconds, or 0 when unknown
 * @param live      whether the media source is a live stream
 */
public record Report(long watcherId, Status status, long duration, boolean live) implements Packet {

    /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
    public static final int BYTES = 20;

    static final byte ID = 4;
    private static final byte VERSION = 1;
    private static final int STATUS_COUNT = Status.values().length;
    private static final int LIVE_BIT = 0b1;

    public Report {
        if (status == null) throw new IllegalArgumentException("Status cannot be null");
        if (duration < 0) throw new IllegalArgumentException("Duration cannot be negative");
    }

    @Override
    public ByteBuffer toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
        buf.put(ID).put(VERSION);
        buf.putLong(this.watcherId);
        buf.put((byte) this.status.ordinal());
        buf.putLong(this.duration);
        buf.put((byte) (this.live ? LIVE_BIT : 0));
        return buf.flip();
    }

    // DECODES THE POST-HEADER FIELDS — CALLED ONLY FROM Packet.of() (TRUST BOUNDARY)
    static Report read(final ByteBuffer buf, final byte version) {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported Report version: " + version);
        if (buf.remaining() < BYTES - 2) throw new IllegalArgumentException("Truncated Report payload, needs " + BYTES + " bytes");
        final long watcherId = buf.getLong();
        final int ordinal = buf.get() & 0xFF;
        if (ordinal >= STATUS_COUNT) throw new IllegalArgumentException("Invalid status ordinal: " + ordinal);
        final long duration = buf.getLong();
        final byte flags = buf.get();
        return new Report(watcherId, Status.of(ordinal), duration, (flags & LIVE_BIT) != 0);
    }
}
