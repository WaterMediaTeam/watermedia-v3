package org.watermedia.api.media.players.sync;

import java.nio.ByteBuffer;

/**
 * Upstream control request: a follower asks the authority to apply a playback mutation.
 * Honored only when {@link Config.Capability#CONTROLS} is granted; the resulting state
 * comes back to every follower as a {@link Sync} broadcast, so the requester never
 * applies anything locally.
 *
 * @param watcherId follower instance id given in the original {@link Watch}
 * @param op        the requested mutation
 * @param value     operand: milliseconds for seeks, float bits for speed, 0/1 for toggles
 */
public record Control(long watcherId, Op op, long value) implements Packet {

    /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
    public static final int BYTES = 19;

    static final byte ID = 5;
    private static final byte VERSION = 1;

    public Control {
        if (op == null) throw new IllegalArgumentException("Op cannot be null");
    }

    /**
     * Builds a float-valued request ({@link Op#SPEED}).
     * @param watcherId follower instance id
     * @param op the requested mutation
     * @param value the float operand, packed into the value bits
     * @return the request
     */
    public static Control of(final long watcherId, final Op op, final float value) {
        return new Control(watcherId, op, Float.floatToIntBits(value) & 0xFFFFFFFFL);
    }

    /**
     * The operand decoded as the float it carries for {@link Op#SPEED}.
     * @return the float operand
     */
    public float floatValue() {
        return Float.intBitsToFloat((int) this.value);
    }

    @Override
    public ByteBuffer toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
        buf.put(ID).put(VERSION);
        buf.putLong(this.watcherId);
        buf.put((byte) this.op.ordinal());
        buf.putLong(this.value);
        return buf.flip();
    }

    // DECODES THE POST-HEADER FIELDS — CALLED ONLY FROM Packet.of() (TRUST BOUNDARY)
    static Control read(final ByteBuffer buf, final byte version) {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported Control version: " + version);
        if (buf.remaining() < BYTES - 2) throw new IllegalArgumentException("Truncated Control payload, needs " + BYTES + " bytes");
        final long watcherId = buf.getLong();
        final int ordinal = buf.get() & 0xFF;
        if (ordinal >= Op.VALUES.length) throw new IllegalArgumentException("Invalid op ordinal: " + ordinal);
        return new Control(watcherId, Op.VALUES[ordinal], buf.getLong());
    }

    /**
     * Playback mutations a follower can request.
     */
    public enum Op {
        /** Start playback from the beginning. */
        START,
        /** Start playback paused at the beginning. */
        START_PAUSED,
        /** Pause (value 1) or resume (value 0) playback. */
        PAUSE,
        /** Stop playback and reset the position. */
        STOP,
        /** Toggle between play and pause. */
        TOGGLE,
        /** Seek to the value in milliseconds. */
        SEEK,
        /** Fast, imprecise seek to the value in milliseconds. */
        SEEK_QUICK,
        /** Change the playback speed to the float packed in the value. */
        SPEED,
        /** Enable (value 1) or disable (value 0) repeat. */
        REPEAT;

        static final Op[] VALUES = values();
    }
}
