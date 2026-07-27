package org.watermedia.test.media.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.sync.Config;
import org.watermedia.api.media.players.sync.Config.Capability;
import org.watermedia.api.media.players.sync.Control;
import org.watermedia.api.media.players.sync.Control.Op;
import org.watermedia.api.media.players.sync.Packet;
import org.watermedia.api.media.players.sync.Report;
import org.watermedia.api.media.players.sync.Sync;
import org.watermedia.api.media.players.sync.Unwatch;
import org.watermedia.api.media.players.sync.Watch;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec tests for the bridge sync wire protocol. Decoding is the trust boundary between the
 * network and playback code, so every packet must round-trip byte for byte and every
 * malformed payload must be rejected instead of reaching a player.
 */
@DisplayName("Sync protocol packets")
public class PacketTest {

    private static Packet roundTrip(final Packet packet, final int expectedBytes) {
        final ByteBuffer buf = packet.toBytes();
        assertEquals(expectedBytes, buf.remaining(), "unexpected payload size for " + packet);
        final Packet decoded = Packet.of(buf);
        assertEquals(packet, decoded);
        return decoded;
    }

    @Test
    @DisplayName("Sync round-trips every status and flag combination")
    void testSyncRoundTrip() {
        for (final Status status: Status.values()) {
            for (int flags = 0; flags < 8; flags++) {
                final Sync sync = new Sync(1234, status, 987_654L, 3_600_000L, 1.75f, 42,
                        (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
                roundTrip(sync, Sync.BYTES);
            }
        }
        // BOUNDARY VALUES SURVIVE THE CODEC
        roundTrip(new Sync(Integer.MAX_VALUE, Status.PLAYING, 0L, 0L, 4f, 100, true, true, true), Sync.BYTES);
        roundTrip(new Sync(Integer.MIN_VALUE, Status.WAITING, Long.MAX_VALUE, Long.MAX_VALUE, 0.01f, 0, false, false, false), Sync.BYTES);
    }

    @Test
    @DisplayName("Every other packet type round-trips")
    void testPacketRoundTrip() {
        roundTrip(new Watch(-9_000_000_000_000L), Watch.BYTES);
        roundTrip(new Unwatch(Long.MAX_VALUE), Unwatch.BYTES);
        roundTrip(new Config(77L, Capability.mask(Capability.LOCKSTEP, Capability.CONTROLS)), Config.BYTES);
        roundTrip(new Config(0L, 0), Config.BYTES);
        for (final Status status: Status.values()) {
            roundTrip(new Report(5L, status, 60_000L, true), Report.BYTES);
            roundTrip(new Report(5L, status, 0L, false), Report.BYTES);
        }
        for (final Op op: Op.values()) {
            roundTrip(new Control(9L, op, 30_000L), Control.BYTES);
        }
        // THE SPEED OPERAND TRAVELS AS PACKED FLOAT BITS
        final Control speed = (Control) roundTrip(Control.of(9L, Op.SPEED, 2.5f), Control.BYTES);
        assertEquals(2.5f, speed.floatValue());
    }

    @Test
    @DisplayName("Config reports its granted capabilities and drops unknown bits")
    void testCapabilities() {
        final Config config = new Config(1L, Capability.mask(Capability.LOCKSTEP, Capability.VOLUME));
        assertTrue(config.has(Capability.LOCKSTEP));
        assertTrue(config.has(Capability.VOLUME));
        assertFalse(config.has(Capability.CONTROLS));
        // FUTURE BITS FROM A NEWER AUTHORITY ARE IGNORED, NOT REJECTED
        assertEquals(Capability.LOCKSTEP.bit, new Config(1L, Capability.LOCKSTEP.bit | 0x80).capabilities());
    }

    @Test
    @DisplayName("Malformed payloads are rejected at decode")
    void testRejections() {
        assertThrows(IllegalArgumentException.class, () -> Packet.of(null));
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.allocate(1)));
        // UNKNOWN PACKET ID
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.wrap(new byte[]{99, 1, 0, 0})));

        final Sync sync = new Sync(1, Status.PLAYING, 0L, 1000L, 1f, 100, false, false, false);
        // WRONG VERSION
        final byte[] badVersion = sync.toBytes().array().clone();
        badVersion[1] = 99;
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.wrap(badVersion)));
        // TRUNCATED
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.wrap(sync.toBytes().array(), 0, Sync.BYTES - 1)));
        // OUT-OF-RANGE STATUS ORDINAL
        final byte[] badStatus = sync.toBytes().array().clone();
        badStatus[6] = (byte) 200;
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.wrap(badStatus)));
        // OUT-OF-RANGE SPEED SMUGGLED THROUGH THE WIRE
        final byte[] badSpeed = sync.toBytes().array().clone();
        ByteBuffer.wrap(badSpeed).putFloat(23, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.wrap(badSpeed)));

        // OUT-OF-RANGE CONTROL OP ORDINAL
        final byte[] badOp = new Control(1L, Op.SEEK, 0L).toBytes().array().clone();
        badOp[10] = (byte) 200;
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.wrap(badOp)));
        // OUT-OF-RANGE REPORT STATUS ORDINAL
        final byte[] badReport = new Report(1L, Status.PLAYING, 0L, false).toBytes().array().clone();
        badReport[10] = (byte) 200;
        assertThrows(IllegalArgumentException.class, () -> Packet.of(ByteBuffer.wrap(badReport)));
    }

    @Test
    @DisplayName("A payload embedded in a larger frame decodes and leaves the rest")
    void testEmbeddedInFrame() {
        // DEVS WRAP THE PAYLOAD IN THEIR OWN FRAME (BLOCK POS, CHANNEL ID); DECODING TAKES ONLY ITS BYTES
        final Watch watch = new Watch(7L);
        final ByteBuffer frame = ByteBuffer.allocate(Watch.BYTES + 8).put(watch.toBytes()).putLong(0xCAFEBABEL).flip();
        assertEquals(watch, Packet.of(frame));
        assertEquals(8, frame.remaining(), "the dev's trailing fields must survive the decode");
        assertEquals(0xCAFEBABEL, frame.getLong());
    }

    @Test
    @DisplayName("Constructors reject impossible values")
    void testValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, null, 0, 0, 1f, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, Status.PLAYING, -1, 0, 1f, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, Status.PLAYING, 0, -1, 1f, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, Status.PLAYING, 0, 0, 0f, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, Status.PLAYING, 0, 0, 4.1f, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, Status.PLAYING, 0, 0, Float.NaN, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, Status.PLAYING, 0, 0, 1f, 101, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Sync(1, Status.PLAYING, 0, 0, 1f, -1, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Report(1L, null, 0L, false));
        assertThrows(IllegalArgumentException.class, () -> new Report(1L, Status.PLAYING, -1L, false));
        assertThrows(IllegalArgumentException.class, () -> new Control(1L, null, 0L));
    }
}
