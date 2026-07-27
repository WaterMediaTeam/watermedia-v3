package org.watermedia.api.media.players.sync;

import java.nio.ByteBuffer;

/**
 * Upstream goodbye: a follower deregisters from the authority on release, leaving the
 * watcher pool cleanly instead of waiting for its keepalive to expire.
 *
 * @param watcherId follower instance id given in the original {@link Watch}
 */
public record Unwatch(long watcherId) implements Packet {

    /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
    public static final int BYTES = 10;

    static final byte ID = 6;
    private static final byte VERSION = 1;

    @Override
    public ByteBuffer toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
        buf.put(ID).put(VERSION);
        buf.putLong(this.watcherId);
        return buf.flip();
    }

    // DECODES THE POST-HEADER FIELDS — CALLED ONLY FROM Packet.of() (TRUST BOUNDARY)
    static Unwatch read(final ByteBuffer buf, final byte version) {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported Unwatch version: " + version);
        if (buf.remaining() < BYTES - 2) throw new IllegalArgumentException("Truncated Unwatch payload, needs " + BYTES + " bytes");
        return new Unwatch(buf.getLong());
    }
}
