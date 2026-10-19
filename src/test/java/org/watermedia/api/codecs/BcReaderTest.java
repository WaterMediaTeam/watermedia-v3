package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.common.dds.DdsHeader;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.DdsFixture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies {@link BcReader} block access over DDS texture arrays without a native BC encoder. */
@DisplayName("BcReader")
public class BcReaderTest {

    @Nested
    @DisplayName("Blocks")
    class Blocks {

        @Test
        @DisplayName("Compressed slices, the BC layout and footer delays read back without a native encoder")
        void readsCompressedSlicesWithoutNativeEncoder() throws Exception {
            for (final PixelFormat format: List.of(PixelFormat.BC1, PixelFormat.BC3, PixelFormat.BC7)) {
                final int size = (int) format.frameBytes(5, 7, 8);
                final ByteBuffer file = ByteBuffer.allocate(3 + DdsHeader.BYTES + size * 2 + 28);
                file.position(3);
                file.put(DdsFixture.write(5, 7, format, 2));
                for (int i = 0; i < size * 2; i++) file.put((byte) i);
                file.put(DdsFixture.writeFooter(25, 75));
                file.flip().position(3);
                try (final BcReader reader = new BcReader(file)) {
                    assertEquals(3, file.position());
                    assertSame(format, reader.pixelFormat());
                    assertEquals(2, reader.frameCount());
                    assertEquals(100, reader.duration());
                    assertTrue(reader.blocks()[0].isDirect());
                    assertEquals(size, reader.blocks()[0].remaining());
                    assertEquals((byte) size, reader.blocks()[1].get(0));
                    assertEquals(25, reader.next().delayMs());
                    assertEquals(75, reader.next().delayMs());
                    assertNull(reader.next());
                    reader.reset();
                    assertTrue(reader.hasNext());
                }
            }
        }
    }

    @Nested
    @DisplayName("Animation footer")
    class Footer {

        @Test
        @DisplayName("A DDS without the animation footer has zero delays")
        void readsOrdinaryDdsWithoutAnimationFooter() throws Exception {
            try (final BcReader reader = new BcReader(texture(1, new byte[0]))) {
                assertEquals(0, reader.duration());
                assertArrayEquals(new long[] { 0 }, reader.delays());
            }
        }

        @Test
        @DisplayName("Trailing bytes without the footer magic are ignored")
        void ignoresTrailingBytesWithoutFooterMagic() throws Exception {
            for (final int length: new int[] { 1, 3, 16 }) {
                final byte[] trailer = new byte[length];
                for (int i = 0; i < length; i++) trailer[i] = (byte) (i + 1);
                try (final BcReader reader = new BcReader(texture(2, trailer))) {
                    assertEquals(0, reader.duration(), length + " trailing bytes");
                    assertArrayEquals(new long[2], reader.delays(), length + " trailing bytes");
                }
            }
        }

        @Test
        @DisplayName("A footer that starts with the magic but is truncated is malformed")
        void rejectsTruncatedFooterWithMagic() {
            // THE MAGIC ALONE, THE HEAD WITHOUT ANY DELAY LONG AND THE HEAD WITH ONE OF ITS TWO DELAYS
            final byte[] footer = DdsFixture.writeFooter(25, 75);
            for (final int length: new int[] { 4, 12, 20 })
                assertThrowsExactly(CodecException.class, () -> new BcReader(texture(2, Arrays.copyOf(footer, length))), length + " footer bytes");
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("Broken headers, oversized textures and negative delays are malformed and valid shapes it lacks unsupported, before blocks are allocated")
        void classifiesRefusalsBeforeAllocatingBlocks() throws Exception {
            try (final BcReader reader = new BcReader(file(0, 0x20534444))) {
                assertEquals(1, reader.frameCount(), "the unpatched file must read, so each refusal below comes from its patched field");
            }
            // HEADER SIZE, PIXEL FORMAT SIZE, MISSING DDSCAPS_TEXTURE AND AN UNKNOWN RESOURCE DIMENSION
            for (final int offset: new int[] { 4, 76, 108, 132 })
                assertThrowsExactly(CodecException.class, () -> new BcReader(file(offset, 0)), "offset " + offset);
            // A LEGACY PIXEL FORMAT, A CUBE MAP AND A MIP CHAIN
            for (final int[] field: new int[][] { { 80, 0 }, { 136, 4 }, { 28, 2 } })
                assertThrowsExactly(UnsupportedFormatException.class, () -> new BcReader(file(field[0], field[1])), "offset " + field[0]);
            final ByteBuffer large = ByteBuffer.wrap(DdsFixture.write(8_192, 8_192, PixelFormat.BC7, 9));
            assertTrue(assertThrowsExactly(CodecException.class, () -> new BcReader(large)).getMessage().contains("too large"));
            assertThrowsExactly(CodecException.class, () -> new BcReader(texture(1, DdsFixture.writeFooter(-1))));
        }
    }

    @Nested
    @DisplayName("Availability")
    class Availability {

        @Test
        @DisplayName("Pixel codec availability does not depend on the bootstrap")
        void pixelCodecAvailabilityDoesNotDependOnBootstrap() {
            for (final String codec: List.of("PNG", "JPG", "GIF", "WEBP", "NETPBM", "SVG")) assertTrue(CodecsAPI.available(codec));
            assertFalse(CodecsAPI.available("BC7"));
            assertFalse(CodecsAPI.available(null));
            assertFalse(CodecsAPI.available("unknown"));
        }
    }

    // A 4x4 BC1 TEXTURE ARRAY OF ZEROED BLOCKS FOLLOWED BY THE GIVEN TRAILING BYTES
    private static ByteBuffer texture(final int slices, final byte[] trailer) {
        final int blocks = slices * PixelFormat.BC1.blockBytes();
        return ByteBuffer.allocate(DdsHeader.BYTES + blocks + trailer.length)
                .put(DdsFixture.write(4, 4, PixelFormat.BC1, slices)).put(new byte[blocks]).put(trailer).flip();
    }

    // THE ONE-SLICE TEXTURE WITH ONE LITTLE-ENDIAN HEADER FIELD OVERWRITTEN
    private static ByteBuffer file(final int offset, final int value) {
        return texture(1, new byte[0]).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }
}
