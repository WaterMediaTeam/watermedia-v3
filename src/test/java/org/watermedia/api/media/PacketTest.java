package org.watermedia.api.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.Config.Capability;
import org.watermedia.api.media.Control.Op;
import org.watermedia.api.media.MediaPlayer.Status;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bridge sync wire protocol codec. Decoding is the trust boundary between the
 * network and playback code, so every packet must round-trip byte for byte, decode from any
 * buffer whatever its byte order, and every malformed payload must be rejected instead of
 * reaching a player.
 */
@DisplayName("Sync protocol packets")
public class PacketTest {

    @Nested
    @DisplayName("Round trips")
    class RoundTrips {

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
            final Control speed = (Control) roundTrip(new Control(9L, Op.SPEED, Float.floatToIntBits(2.5f) & 0xFFFFFFFFL), Control.BYTES);
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
        @DisplayName("Every packet type decodes from little-endian heap and direct buffers and leaves their order alone")
        void testLittleEndianSources() {
            // BYTE-ASYMMETRIC VALUES: A DECODE THAT HONOURED THE CALLER'S ORDER WOULD READ EVERY FIELD SWAPPED
            final long id = 0x0102_0304_0506_0708L;
            final List<Packet> packets = List.of(
                    new Sync(0x0A0B_0C0D, Status.PAUSED, 0x0102_0304L, 0x0506_0708L, 1.5f, 42, true, false, true),
                    new Config(id, Capability.mask(Capability.LOCKSTEP, Capability.VOLUME)),
                    new Watch(id),
                    new Report(id, Status.BUFFERING, 0x0102_0304L, true),
                    new Control(id, Op.SEEK, 0x0102_0304L),
                    new Unwatch(id));
            for (final Packet packet: packets) {
                final byte[] wire = packet.toBytes().array();
                final ByteBuffer heap = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
                final ByteBuffer direct = ByteBuffer.allocateDirect(wire.length).order(ByteOrder.LITTLE_ENDIAN).put(wire).flip();
                for (final ByteBuffer source: List.of(heap, direct)) {
                    assertEquals(packet, Packet.read(source), (source.isDirect() ? "direct " : "heap ") + packet);
                    assertEquals(0, source.remaining(), "the decode consumes exactly the packet");
                    assertEquals(ByteOrder.LITTLE_ENDIAN, source.order(), "the caller's byte order survives the decode");
                }
            }
        }

        @Test
        @DisplayName("A payload embedded in a larger frame decodes and leaves the rest")
        void testEmbeddedInFrame() {
            // DEVS WRAP THE PAYLOAD IN THEIR OWN FRAME (BLOCK POS, CHANNEL ID); DECODING TAKES ONLY ITS BYTES
            final Watch watch = new Watch(7L);
            final ByteBuffer frame = ByteBuffer.allocate(Watch.BYTES + 8).put(watch.toBytes()).putLong(0xCAFEBABEL).flip();
            assertEquals(watch, Packet.read(frame));
            assertEquals(8, frame.remaining(), "the dev's trailing fields must survive the decode");
            assertEquals(0xCAFEBABEL, frame.getLong());
        }
    }

    @Nested
    @DisplayName("Rejections")
    class Rejections {

        @Test
        @DisplayName("Malformed payloads are rejected at decode without consuming bytes")
        void testRejections() {
            assertThrows(IllegalArgumentException.class, () -> Packet.read(null));
            rejected(ByteBuffer.allocate(0));
            rejected(ByteBuffer.allocate(1));
            // UNKNOWN PACKET ID
            rejected(ByteBuffer.wrap(new byte[] { 99, 1, 0, 0 }));

            final Sync sync = new Sync(1, Status.PLAYING, 0L, 1_000L, 1f, 100, false, false, false);
            // WRONG VERSION
            final byte[] badVersion = sync.toBytes().array().clone();
            badVersion[1] = 99;
            rejected(ByteBuffer.wrap(badVersion));
            // TRUNCATED
            rejected(ByteBuffer.wrap(sync.toBytes().array(), 0, Sync.BYTES - 1));
            // OUT-OF-RANGE STATUS ORDINAL
            final byte[] badStatus = sync.toBytes().array().clone();
            badStatus[6] = (byte) 200;
            rejected(ByteBuffer.wrap(badStatus));
            // OUT-OF-RANGE SPEED SMUGGLED THROUGH THE WIRE
            final byte[] badSpeed = sync.toBytes().array().clone();
            ByteBuffer.wrap(badSpeed).putFloat(23, Float.NaN);
            rejected(ByteBuffer.wrap(badSpeed));

            // OUT-OF-RANGE CONTROL OP ORDINAL
            final byte[] badOp = new Control(1L, Op.SEEK, 0L).toBytes().array().clone();
            badOp[10] = (byte) 200;
            rejected(ByteBuffer.wrap(badOp));
            // OUT-OF-RANGE REPORT STATUS ORDINAL
            final byte[] badReport = new Report(1L, Status.PLAYING, 0L, false).toBytes().array().clone();
            badReport[10] = (byte) 200;
            rejected(ByteBuffer.wrap(badReport));
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

    // UTILITY

    private static Packet roundTrip(final Packet packet, final int expectedBytes) {
        final ByteBuffer buf = packet.toBytes();
        assertEquals(expectedBytes, buf.remaining(), "unexpected payload size for " + packet);
        final Packet decoded = Packet.read(buf);
        assertEquals(packet, decoded);
        return decoded;
    }

    private static void rejected(final ByteBuffer payload) {
        assertThrows(IllegalArgumentException.class, () -> Packet.read(payload));
        assertEquals(0, payload.position(), "a rejected payload must not consume bytes");
    }
}
