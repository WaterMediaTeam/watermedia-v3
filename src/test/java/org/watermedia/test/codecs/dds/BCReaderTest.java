package org.watermedia.test.codecs.dds;

import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.dds.DDSHeader;
import org.watermedia.api.codecs.readers.BCReader;
import org.watermedia.test.support.DDSFixture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BCReaderTest {
    @Test
    void readsCompressedSlicesWithoutNativeEncoder() throws Exception {
        for (final String codec: List.of(CodecsAPI.CODEC_BC1, CodecsAPI.CODEC_BC3, CodecsAPI.CODEC_BC7)) {
            final int size = (int) DDSHeader.frameBytes(5, 7, codec);
            final ByteBuffer file = ByteBuffer.allocate(3 + DDSHeader.BYTES + size * 2 + 28);
            file.position(3);
            file.put(DDSFixture.write(5, 7, codec, 2));
            for (int i = 0; i < size * 2; i++) file.put((byte) i);
            file.put(DDSFixture.writeFooter(25, 75));
            file.flip().position(3);
            try (final BCReader reader = new BCReader(file)) {
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
        final ByteBuffer file = ByteBuffer.allocate(DDSHeader.BYTES + 8);
        file.put(DDSFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).putLong(0).flip();
        try (final BCReader reader = new BCReader(file)) {
            assertEquals(0, reader.duration());
            assertArrayEquals(new long[] { 0 }, reader.delays());
        }
    }

    @Test
    void rejectsShapesAndDelaysBeforeAllocatingBlocks() {
        for (final int offset: new int[] { 4, 76, 80, 108, 132 }) {
            final ByteBuffer header = ByteBuffer.wrap(DDSFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(offset, 0);
            assertThrows(XCodecException.class, () -> new BCReader(header));
        }
        final ByteBuffer cube = ByteBuffer.wrap(DDSFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).order(ByteOrder.LITTLE_ENDIAN);
        cube.putInt(136, 4);
        assertThrows(XCodecException.class, () -> new BCReader(cube));
        final ByteBuffer mips = ByteBuffer.wrap(DDSFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).order(ByteOrder.LITTLE_ENDIAN);
        mips.putInt(28, 2);
        assertThrows(XCodecException.class, () -> new BCReader(mips));
        final ByteBuffer large = ByteBuffer.wrap(DDSFixture.write(8192, 8192, CodecsAPI.CODEC_BC7, 9));
        assertTrue(assertThrows(XCodecException.class, () -> new BCReader(large)).getMessage().contains("too large"));
        final ByteBuffer negativeDelay = ByteBuffer.allocate(DDSHeader.BYTES + 8 + 20);
        negativeDelay.put(DDSFixture.write(4, 4, CodecsAPI.CODEC_BC1, 1)).putLong(0)
                .put(DDSFixture.writeFooter(-1)).flip();
        assertThrows(XCodecException.class, () -> new BCReader(negativeDelay));
    }

    @Test
    void pixelCodecAvailabilityDoesNotDependOnBootstrap() {
        for (final String codec: List.of("PNG", "JPG", "GIF", "WEBP", "NETPBM", "SVG")) assertTrue(CodecsAPI.available(codec));
        assertFalse(CodecsAPI.available(CodecsAPI.CODEC_BC7));
        assertFalse(CodecsAPI.available(null));
        assertFalse(CodecsAPI.available("unknown"));
    }
}
