package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.common.dds.DdsHeader;
import org.watermedia.test.support.DdsFixture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies {@link BcReader} block access over DDS texture arrays without a native BC encoder. */
@DisplayName("BcReader")
public class BcReaderTest {
    @Test
    void readsCompressedSlicesWithoutNativeEncoder() throws Exception {
        for (final String codec: List.of(CodecsAPI.CODEC_BC1, CodecsAPI.CODEC_BC3, CodecsAPI.CODEC_BC7)) {
            final int size = (int) DdsHeader.frameBytes(5, 7, codec);
            final ByteBuffer file = ByteBuffer.allocate(3 + DdsHeader.BYTES + size * 2 + 28);
            file.position(3);
            file.put(DdsFixture.write(5, 7, codec, 2));
            for (int i = 0; i < size * 2; i++) file.put((byte) i);
            file.put(DdsFixture.writeFooter(25, 75));
            file.flip().position(3);
            try (final BcReader reader = new BcReader(file)) {
                assertEquals(3, file.position());
                assertEquals(codec, reader.version());
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
    void readsOrdinaryDdsWithoutAnimationFooter() throws Exception {
        final ByteBuffer file = ByteBuffer.allocate(DdsHeader.BYTES + 8);
        file.put(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).putLong(0).flip();
        try (final BcReader reader = new BcReader(file)) {
            assertEquals(0, reader.duration());
            assertArrayEquals(new long[] { 0 }, reader.delays());
        }
    }

    @Test
    void rejectsShapesAndDelaysBeforeAllocatingBlocks() {
        for (final int offset: new int[] { 4, 76, 80, 108, 132 }) {
            final ByteBuffer header = ByteBuffer.wrap(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(offset, 0);
            assertThrows(CodecException.class, () -> new BcReader(header));
        }
        final ByteBuffer cube = ByteBuffer.wrap(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).order(ByteOrder.LITTLE_ENDIAN);
        cube.putInt(136, 4);
        assertThrows(CodecException.class, () -> new BcReader(cube));
        final ByteBuffer mips = ByteBuffer.wrap(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).order(ByteOrder.LITTLE_ENDIAN);
        mips.putInt(28, 2);
        assertThrows(CodecException.class, () -> new BcReader(mips));
        final ByteBuffer large = ByteBuffer.wrap(DdsFixture.write(8192, 8192, CodecsAPI.CODEC_BC7, 9));
        assertTrue(assertThrows(CodecException.class, () -> new BcReader(large)).getMessage().contains("too large"));
        final ByteBuffer negativeDelay = ByteBuffer.allocate(DdsHeader.BYTES + 8 + 20);
        negativeDelay.put(DdsFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).putLong(0)
                .put(DdsFixture.writeFooter(-1)).flip();
        assertThrows(CodecException.class, () -> new BcReader(negativeDelay));
    }

    @Test
    void pixelCodecAvailabilityDoesNotDependOnBootstrap() {
        for (final String codec: List.of("PNG", "JPG", "GIF", "WEBP", "NETPBM", "SVG")) assertTrue(CodecsAPI.available(codec));
        assertFalse(CodecsAPI.available(CodecsAPI.CODEC_BC7));
        assertFalse(CodecsAPI.available(null));
        assertFalse(CodecsAPI.available("unknown"));
    }
}
