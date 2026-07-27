package org.watermedia.api.media.players.sync;

import java.nio.ByteBuffer;

/**
 * Upstream hello: a follower announces itself to the authority as a new spectator and
 * asks for its {@link Config} and a fresh {@link Sync}. Sent once when the bridged
 * player is created; the follower registers as loading and never interrupts an
 * already-running session.
 *
 * @param watcherId follower instance id, echoed back in {@link Config} for RTT measurement
 */
public record Watch(long watcherId) implements Packet {

    /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
    public static final int BYTES = 10;

    static final byte ID = 3;
    private static final byte VERSION = 1;

    @Override
    public ByteBuffer toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
        buf.put(ID).put(VERSION);
        buf.putLong(this.watcherId);
        return buf.flip();
    }

    // DECODES THE POST-HEADER FIELDS — CALLED ONLY FROM Packet.of() (TRUST BOUNDARY)
    static Watch read(final ByteBuffer buf, final byte version) {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported Watch version: " + version);
        if (buf.remaining() < BYTES - 2) throw new IllegalArgumentException("Truncated Watch payload, needs " + BYTES + " bytes");
        return new Watch(buf.getLong());
    }
}
