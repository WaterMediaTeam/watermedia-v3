package org.watermedia.test.codecs.dds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.dds.DDSHeader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Container-level tests for {@link DDSHeader}. These exercise the DDS structure independently of the
 * native BC codec: header write/read, the {@code arraySize} patch, the delay footer, and the
 * codec ⇄ block-size math.
 */
@DisplayName("DDS container")
public class DDSHeaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Header round-trips dimensions, codec and frame count")
    void testHeaderRoundTrip() throws IOException {
        final byte[] header = DDSHeader.write(64, 32, CodecsAPI.CODEC_BC7, 5);
        assertEquals(DDSHeader.BYTES, header.length);

        final DDSHeader.Info info = DDSHeader.read(ByteBuffer.wrap(header));
        assertEquals(64, info.width());
        assertEquals(32, info.height());
        assertEquals(CodecsAPI.CODEC_BC7, info.codec());
        assertEquals(16, info.blockBytes());
        assertEquals(5, info.arraySize());
    }

    @Test
    @DisplayName("arraySize is patched in place on disk")
    void testPatchArraySize() throws IOException {
        final Path file = this.tempDir.resolve("texture.dds");
        Files.write(file, DDSHeader.write(16, 16, CodecsAPI.CODEC_BC1, 0));

        DDSHeader.patchArraySize(file, 7);

        final DDSHeader.Info info = DDSHeader.read(ByteBuffer.wrap(Files.readAllBytes(file)));
        assertEquals(7, info.arraySize());
        assertEquals(CodecsAPI.CODEC_BC1, info.codec());
        assertEquals(8, info.blockBytes());
    }

    @Test
    @DisplayName("Footer round-trips per-frame delays")
    void testFooterRoundTrip() throws IOException {
        final long[] delays = { 10L, 0L, 250L, 33L };
        final byte[] footer = DDSHeader.writeFooter(delays, delays.length);
        final long[] read = DDSHeader.readFooter(ByteBuffer.wrap(footer), delays.length);
        assertArrayEquals(delays, read);
    }

    @Test
    @DisplayName("Block math matches the BC layout")
    void testBlockMath() {
        // 64x32 -> 16x8 blocks = 128 blocks
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
        assertThrows(XCodecException.class, () -> DDSHeader.read(ByteBuffer.wrap(DDSHeader.write(8, 8, CodecsAPI.CODEC_BC7, 0))));
    }
}
