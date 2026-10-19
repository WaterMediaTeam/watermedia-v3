package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.common.dds.DdsHeader;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.DdsFixture;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies {@link BcReader} block access over DDS texture arrays without a native BC encoder. */
@DisplayName("BcReader")
public class BcReaderTest {
    @Test
    @DisplayName("Compressed slices, the BC identity and footer delays read back without a native encoder")
    void readsCompressedSlicesWithoutNativeEncoder() throws Exception {
        for (final String codec: List.of(CodecsAPI.CODEC_BC1, CodecsAPI.CODEC_BC3, CodecsAPI.CODEC_BC7)) {
            final int size = (int) DataTool.frameBytes(PixelFormat.valueOf(codec), 5, 7);
            final ByteBuffer file = ByteBuffer.allocate(3 + DdsHeader.BYTES + size * 2 + 28);
            file.position(3);
            file.put(DdsFixture.write(5, 7, codec, 2));
            for (int i = 0; i < size * 2; i++) file.put((byte) i);
            file.put(DdsFixture.writeFooter(25, 75));
            file.flip().position(3);
            try (final BcReader reader = new BcReader(file)) {
                assertEquals(3, file.position());
                assertEquals(codec, reader.version());
                assertEquals(PixelFormat.valueOf(codec).blockBytes(), reader.blockBytes());
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

    @Test
    @DisplayName("A DDS without the animation footer has zero delays")
    void readsOrdinaryDdsWithoutAnimationFooter() throws Exception {
        final ByteBuffer file = ByteBuffer.allocate(DdsHeader.BYTES + 8);
        file.put(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).putLong(0).flip();
        try (final BcReader reader = new BcReader(file)) {
            assertEquals(0, reader.duration());
            assertArrayEquals(new long[] { 0 }, reader.delays());
        }
    }

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
        final ByteBuffer large = ByteBuffer.wrap(DdsFixture.write(8192, 8192, CodecsAPI.CODEC_BC7, 9));
        assertTrue(assertThrowsExactly(CodecException.class, () -> new BcReader(large)).getMessage().contains("too large"));
        final ByteBuffer negativeDelay = ByteBuffer.allocate(DdsHeader.BYTES + 8 + 20);
        negativeDelay.put(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).putLong(0)
                .put(DdsFixture.writeFooter(-1)).flip();
        assertThrowsExactly(CodecException.class, () -> new BcReader(negativeDelay));
    }

    @Test
    @DisplayName("Pixel codec availability does not depend on the bootstrap")
    void pixelCodecAvailabilityDoesNotDependOnBootstrap() {
        for (final String codec: List.of("PNG", "JPG", "GIF", "WEBP", "NETPBM", "SVG")) assertTrue(CodecsAPI.available(codec));
        assertFalse(CodecsAPI.available(CodecsAPI.CODEC_BC7));
        assertFalse(CodecsAPI.available(null));
        assertFalse(CodecsAPI.available("unknown"));
    }

    // A COMPLETE 4x4 BC1 FILE (HEADER PLUS ITS 8 BLOCK BYTES) WITH ONE LITTLE-ENDIAN HEADER FIELD OVERWRITTEN
    private static ByteBuffer file(final int offset, final int value) {
        final ByteBuffer file = ByteBuffer.allocate(DdsHeader.BYTES + 8).order(ByteOrder.LITTLE_ENDIAN);
        file.put(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).putLong(0).flip();
        return file.putInt(offset, value);
    }
}
