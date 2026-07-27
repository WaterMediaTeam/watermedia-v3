package org.watermedia.api.media.players.sync;

import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.ServerMediaPlayer;

import java.nio.ByteBuffer;

/**
 * Authoritative state snapshot broadcast from a {@link ServerMediaPlayer} to its
 * followers. One packet type carries the whole downstream truth: the go signal, the
 * periodic heartbeat, lockstep gates (status {@code BUFFERING} with a frozen clock) and
 * every discrete state change.
 *
 * @param revision monotonic mutation counter used to discard stale snapshots
 * @param status   playback status at capture time
 * @param time     playback position in milliseconds at capture time
 * @param duration total media duration in milliseconds, or 0 when unknown
 * @param speed    playback speed factor, in (0, 4]
 * @param volume   volume level in [0, 100], applied only under {@link Config.Capability#VOLUME}
 * @param mute     mute flag, applied only under {@link Config.Capability#VOLUME}
 * @param repeat   whether playback loops at the end of the media
 * @param live     whether the media source is a live stream
 * @see ServerMediaPlayer#snapshot()
 * @see ServerMediaPlayer#applySnapshot(Sync)
 */
public record Sync(int revision, Status status, long time, long duration, float speed,
                   int volume, boolean mute, boolean repeat, boolean live) implements Packet {

    /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
    public static final int BYTES = 29;

    static final byte ID = 1;
    private static final byte VERSION = 2;
    private static final int STATUS_COUNT = Status.values().length;
    private static final int REPEAT_BIT = 0b001;
    private static final int LIVE_BIT = 0b010;
    private static final int MUTE_BIT = 0b100;

    public Sync {
        if (status == null) throw new IllegalArgumentException("Status cannot be null");
        if (time < 0 || duration < 0) throw new IllegalArgumentException("Time and duration cannot be negative");
        // NaN FAILS BOTH COMPARISONS — THIS ALSO SHIELDS AGAINST CORRUPTED FLOAT PAYLOADS
        if (!(speed > 0f && speed <= 4f)) throw new IllegalArgumentException("Speed must be in (0, 4], got " + speed);
        if (volume < 0 || volume > 100) throw new IllegalArgumentException("Volume must be in [0, 100], got " + volume);
    }

    @Override
    public ByteBuffer toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
        buf.put(ID).put(VERSION);
        buf.putInt(this.revision);
        buf.put((byte) this.status.ordinal());
        buf.putLong(this.time);
        buf.putLong(this.duration);
        buf.putFloat(this.speed);
        buf.put((byte) this.volume);
        buf.put((byte) ((this.repeat ? REPEAT_BIT : 0) | (this.live ? LIVE_BIT : 0) | (this.mute ? MUTE_BIT : 0)));
        return buf.flip();
    }

    // DECODES THE POST-HEADER FIELDS — CALLED ONLY FROM Packet.of() (TRUST BOUNDARY)
    static Sync read(final ByteBuffer buf, final byte version) {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported Sync version: " + version);
        if (buf.remaining() < BYTES - 2) throw new IllegalArgumentException("Truncated Sync payload, needs " + BYTES + " bytes");
        final int revision = buf.getInt();
        final int ordinal = buf.get() & 0xFF;
        if (ordinal >= STATUS_COUNT) throw new IllegalArgumentException("Invalid status ordinal: " + ordinal);
        final long time = buf.getLong();
        final long duration = buf.getLong();
        final float speed = buf.getFloat();
        final int volume = buf.get() & 0xFF;
        final byte flags = buf.get();
        return new Sync(revision, Status.of(ordinal), time, duration, speed, volume,
                (flags & MUTE_BIT) != 0, (flags & REPEAT_BIT) != 0, (flags & LIVE_BIT) != 0);
    }
}
