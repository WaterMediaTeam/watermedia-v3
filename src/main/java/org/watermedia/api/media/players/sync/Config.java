package org.watermedia.api.media.players.sync;

import java.nio.ByteBuffer;

/**
 * Downstream session configuration: the authority answers a {@link Watch} with the
 * capabilities granted to its followers. Broadcast — followers match {@code watcherId}
 * against their own to measure the round trip, and adopt the capabilities regardless.
 *
 * @param watcherId    id of the {@link Watch} this config answers, for RTT measurement
 * @param capabilities bit mask of granted {@link Capability} flags
 */
public record Config(long watcherId, int capabilities) implements Packet {

    /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
    public static final int BYTES = 11;

    static final byte ID = 2;
    private static final byte VERSION = 1;

    public Config {
        // UNKNOWN FUTURE BITS ARE DROPPED, NOT REJECTED — OLD CLIENTS STAY COMPATIBLE
        capabilities &= Capability.MASK_ALL;
    }

    /**
     * Whether the given capability is granted by this config.
     * @param cap the capability to check
     * @return true when granted
     */
    public boolean has(final Capability cap) {
        return (this.capabilities & cap.bit) != 0;
    }

    @Override
    public ByteBuffer toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
        buf.put(ID).put(VERSION);
        buf.putLong(this.watcherId);
        buf.put((byte) this.capabilities);
        return buf.flip();
    }

    // DECODES THE POST-HEADER FIELDS — CALLED ONLY FROM Packet.of() (TRUST BOUNDARY)
    static Config read(final ByteBuffer buf, final byte version) {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported Config version: " + version);
        if (buf.remaining() < BYTES - 2) throw new IllegalArgumentException("Truncated Config payload, needs " + BYTES + " bytes");
        return new Config(buf.getLong(), buf.get() & 0xFF);
    }

    /**
     * Feature switches the authority grants to its followers.
     * The clock and discrete state mirror (play/pause/stop/seek/speed/repeat) is always
     * on for a bridged player; capabilities extend or restrict what surrounds it.
     */
    public enum Capability {
        /**
         * Gates playback on the whole audience: while any pooled follower is still
         * loading or buffering, the authority presents {@code BUFFERING} with a frozen
         * clock and every follower holds. Errored or vanished followers never hold the
         * gate, and a spectator joining mid-playback never interrupts the others.
         */
        LOCKSTEP,
        /**
         * Lets followers drive the session: control calls on a bridged player are
         * forwarded to the authority as {@link Control} requests instead of being
         * dropped. Permissions belong to the transport — filter before feeding
         * {@code sync()} or gate your own UI.
         */
        CONTROLS,
        /**
         * The authority also dictates volume and mute. Without it both stay
         * client-local, like LOD and scaling always do.
         */
        VOLUME;

        /** Bit flag of this capability inside a {@link Config#capabilities()} mask. */
        public final int bit = 1 << this.ordinal();

        static final int MASK_ALL;

        static {
            int m = 0;
            for (final Capability c: values()) m |= c.bit;
            MASK_ALL = m;
        }

        /**
         * Combines capabilities into a bit mask.
         * @param caps the capabilities to combine
         * @return the resulting mask
         */
        public static int mask(final Capability... caps) {
            int m = 0;
            for (final Capability c: caps) m |= c.bit;
            return m;
        }
    }
}
