package org.watermedia.api.codecs.common.dds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.test.support.DdsFixture;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link DdsHeader} header and footer parsing and block math independently of the
 * native BC codec.
 */
@DisplayName("DDS container")
public class DdsHeaderTest {

    @Test
    @DisplayName("Header parses dimensions, codec and frame count")
    void testHeaderParsing() throws CodecException {
        final byte[] header = DdsFixture.write(64, 32, CodecsAPI.CODEC_BC7, 5);
        assertEquals(DdsHeader.BYTES, header.length);

        final DdsHeader.Info info = DdsHeader.read(ByteBuffer.wrap(header));
        assertEquals(64, info.width());
        assertEquals(32, info.height());
        assertEquals(CodecsAPI.CODEC_BC7, info.codec());
        assertEquals(16, info.blockBytes());
        assertEquals(5, info.arraySize());
    }

    @Test
    @DisplayName("Footer round-trips per-frame delays")
    void testFooterRoundTrip() throws CodecException {
        final long[] delays = { 10L, 0L, 250L, 33L };
        final byte[] footer = DdsFixture.writeFooter(delays);
        final long[] read = DdsHeader.readFooter(ByteBuffer.wrap(footer), delays.length);
        assertArrayEquals(delays, read);
    }

    @Test
    @DisplayName("Block math matches the BC layout")
    void testBlockMath() {
        // 64X32 FORMS 16X8 BLOCKS.
        assertEquals(128, DdsHeader.blocksPerFrame(64, 32));
        // EDGES PAD UP TO THE 4x4 GRID
        assertEquals(DdsHeader.blocksPerFrame(64, 32), DdsHeader.blocksPerFrame(61, 30));
        assertEquals(128 * 16, DdsHeader.frameBytes(64, 32, CodecsAPI.CODEC_BC7));
        assertEquals(128 * 8, DdsHeader.frameBytes(64, 32, CodecsAPI.CODEC_BC1));
        assertEquals(8, DdsHeader.blockBytesOf(CodecsAPI.CODEC_BC1));
        assertEquals(16, DdsHeader.blockBytesOf(CodecsAPI.CODEC_BC3));
        assertEquals(16, DdsHeader.blockBytesOf(CodecsAPI.CODEC_BC7));
    }

    @Test
    @DisplayName("Rejects non-DDS and invalid headers")
    void testRejectsInvalid() {
        assertThrows(CodecException.class, () -> DdsHeader.read(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 })));
        // A WELL-FORMED HEADER WITH arraySize=0 IS NOT A USABLE TEXTURE
        assertThrows(CodecException.class, () -> DdsHeader.read(ByteBuffer.wrap(DdsFixture.write(8, 8, CodecsAPI.CODEC_BC7, 0))));
    }
}
