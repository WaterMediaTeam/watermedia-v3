package org.watermedia.test.codecs.dds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.dds.DDSHeader;
import org.watermedia.test.support.DDSFixture;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Container-level tests for {@link DDSHeader}. These exercise header and footer parsing and block
 * math independently of the native BC codec.
 */
@DisplayName("DDS container")
public class DDSHeaderTest {

    @Test
    @DisplayName("Header parses dimensions, codec and frame count")
    void testHeaderParsing() throws XCodecException {
        final byte[] header = DDSFixture.write(64, 32, CodecsAPI.CODEC_BC7, 5);
        assertEquals(DDSHeader.BYTES, header.length);

        final DDSHeader.Info info = DDSHeader.read(ByteBuffer.wrap(header));
        assertEquals(64, info.width());
        assertEquals(32, info.height());
        assertEquals(CodecsAPI.CODEC_BC7, info.codec());
        assertEquals(16, info.blockBytes());
        assertEquals(5, info.arraySize());
    }

    @Test
    @DisplayName("Footer round-trips per-frame delays")
    void testFooterRoundTrip() throws XCodecException {
        final long[] delays = { 10L, 0L, 250L, 33L };
        final byte[] footer = DDSFixture.writeFooter(delays);
        final long[] read = DDSHeader.readFooter(ByteBuffer.wrap(footer), delays.length);
        assertArrayEquals(delays, read);
    }

    @Test
    @DisplayName("Block math matches the BC layout")
    void testBlockMath() {
        // 64X32 FORMS 16X8 BLOCKS.
        assertEquals(128, DDSHeader.blocksPerFrame(64, 32));
        // EDGES PAD UP TO THE 4x4 GRID
        assertEquals(DDSHeader.blocksPerFrame(64, 32), DDSHeader.blocksPerFrame(61, 30));
        assertEquals(128 * 16, DDSHeader.frameBytes(64, 32, CodecsAPI.CODEC_BC7));
        assertEquals(128 * 8, DDSHeader.frameBytes(64, 32, CodecsAPI.CODEC_BC1));
        assertEquals(8, DDSHeader.blockBytesOf(CodecsAPI.CODEC_BC1));
        assertEquals(16, DDSHeader.blockBytesOf(CodecsAPI.CODEC_BC3));
        assertEquals(16, DDSHeader.blockBytesOf(CodecsAPI.CODEC_BC7));
    }

    @Test
    @DisplayName("Rejects non-DDS and invalid headers")
    void testRejectsInvalid() {
        assertThrows(XCodecException.class, () -> DDSHeader.read(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 })));
        // A WELL-FORMED HEADER WITH arraySize=0 IS NOT A USABLE TEXTURE
        assertThrows(XCodecException.class, () -> DDSHeader.read(ByteBuffer.wrap(DDSFixture.write(8, 8, CodecsAPI.CODEC_BC7, 0))));
    }
}
