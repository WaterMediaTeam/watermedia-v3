package org.watermedia.api.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Wire unit of the bridge sync protocol.
 * <p>
 * Every packet encodes to a fixed-size big-endian payload headed by a packet id and a
 * per-type version byte, small enough for any byte carrier — a Minecraft custom payload,
 * a plain socket frame. {@link #read(ByteBuffer)} is the single decode entry and the trust
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
     * carrying the dev's own routing fields. The buffer's byte order is irrelevant (the wire is
     * big-endian) and a rejected payload leaves the position untouched.
     * @param buf a payload produced by {@link #toBytes()}
     * @return the decoded packet
     * @throws IllegalArgumentException if the payload is null, truncated, has an unknown
     *         packet id, an unsupported version or out-of-range values
     */
    static Packet read(final ByteBuffer buf) {
        if (buf == null || buf.remaining() < 2)
            throw new IllegalArgumentException("Packet payload is null or truncated");
        // DECODE FROM A BIG-ENDIAN VIEW SO THE CALLER'S BYTE ORDER NEVER MATTERS, AND ADVANCE THE
        // CALLER'S POSITION ONLY ONCE THE WHOLE PACKET PARSED — A REJECTED PAYLOAD LEAVES IT UNTOUCHED
        final ByteBuffer view = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
        final byte id = view.get();
        final byte version = view.get();
        final Packet packet = switch (id) {
            case Sync.ID -> Sync.read(view, version);
            case Config.ID -> Config.read(view, version);
            case Watch.ID -> Watch.read(view, version);
            case Report.ID -> Report.read(view, version);
            case Control.ID -> Control.read(view, version);
            case Unwatch.ID -> Unwatch.read(view, version);
            default -> throw new IllegalArgumentException("Unknown packet id: " + id);
        };
        buf.position(view.position());
        return packet;
    }
}
