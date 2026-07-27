package org.watermedia.api.media.players.sync;

import org.watermedia.api.media.players.MediaPlayer;

import java.nio.ByteBuffer;

/**
 * Byte carrier between a playback session and its counterpart across the network.
 * <p>
 * WaterMedia owns the protocol and the correction math; the bridge owns the routing. Nothing
 * about who the peers are reaches this API: an implementation knows which session it serves
 * (a block position, a resource location, an entity id) and delivers the payload to it —
 * downstream to every viewer when it backs an authority, upstream to the server when it backs
 * a follower. The natural shape is a small class holding that key:
 * <pre>{@code
 * public final class MediaBridge implements Bridge {
 *     private final ResourceLocation session;
 *     public MediaBridge(ResourceLocation session) { this.session = session; }
 *     public void send(ByteBuffer payload) { Network.send(this.session, payload); }
 * }
 * }</pre>
 * Incoming traffic goes the other way, straight into {@link MediaPlayer#sync(ByteBuffer)} from
 * the handler that received it.
 * <p>
 * Implementations must be thread-safe and must not block: sends happen on WaterMedia's sync
 * thread and on whichever thread issued a control call.
 */
@FunctionalInterface
public interface Bridge {

    /**
     * Delivers an encoded packet to the other side of this session.
     * @param payload the bytes to transmit, ready to read
     */
    void send(ByteBuffer payload);
}
