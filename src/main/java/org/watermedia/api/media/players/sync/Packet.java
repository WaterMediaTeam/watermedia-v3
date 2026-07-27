package org.watermedia.api.media.players.sync;

import java.nio.ByteBuffer;

/**
 * Wire unit of the bridge sync protocol.
 * <p>
 * Every packet encodes to a fixed-size big-endian payload headed by a packet id and a
 * per-type version byte, small enough for any byte carrier — a Minecraft custom payload,
 * a plain socket frame. {@link #of(ByteBuffer)} is the single decode entry and the trust
 * boundary: malformed, unknown or out-of-range payloads never reach playback code.
 * <p>
 * Downstream (authority to followers): {@link Sync} and {@link Config}. Upstream
 * (followers to authority): {@link Watch}, {@link Report}, {@link Control} and
 * {@link Unwatch}.
 */
public sealed interface Packet permits Sync, Config, Watch, Report, Control, Unwatch {

    /**
     * Encodes this packet into its fixed binary form.
     * @return a new heap buffer positioned at zero, ready to transmit
     */
    ByteBuffer toBytes();

    /**
     * Decodes a packet from its binary form. Exactly the packet's own bytes are consumed and
     * anything after them is left in the buffer, so a payload may travel inside a larger frame
     * carrying the dev's own routing fields.
     * @param buf a payload produced by {@link #toBytes()}
     * @return the decoded packet
     * @throws IllegalArgumentException if the payload is null, truncated, has an unknown
     *         packet id, an unsupported version or out-of-range values
     */
    static Packet of(final ByteBuffer buf) {
        if (buf == null || buf.remaining() < 2)
            throw new IllegalArgumentException("Packet payload is null or truncated");
        final byte id = buf.get();
        final byte version = buf.get();
        return switch (id) {
            case Sync.ID -> Sync.read(buf, version);
            case Config.ID -> Config.read(buf, version);
            case Watch.ID -> Watch.read(buf, version);
            case Report.ID -> Report.read(buf, version);
            case Control.ID -> Control.read(buf, version);
            case Unwatch.ID -> Unwatch.read(buf, version);
            default -> throw new IllegalArgumentException("Unknown packet id: " + id);
        };
    }
}
