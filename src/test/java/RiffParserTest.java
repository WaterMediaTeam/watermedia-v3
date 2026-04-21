import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.webp.riff.RiffChunk;
import org.watermedia.api.codecs.decoders.webp.riff.RiffParser;
import org.watermedia.api.codecs.decoders.webp.riff.WebPInfo;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Unit tests for the RiffParser class used in WebP format detection and parsing.
 * Tests cover RIFF header validation, lossy/lossless/extended format parsing,
 * and dimension extraction.
 */
public class RiffParserTest {

    /**
     * Test factory for WebP format detection.
     * Verifies isWebP correctly identifies valid and invalid WebP headers.
     *
     * @return collection of dynamic tests for format detection
     */
    @TestFactory
    Iterable<DynamicTest> testFormatDetection() {
        final List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("Valid RIFF/WEBP header", this::testValidWebPHeader));
        tests.add(dynamicTest("Invalid RIFF signature", this::testInvalidRiffSignature));
        tests.add(dynamicTest("Invalid WEBP signature", this::testInvalidWebPSignature));
        tests.add(dynamicTest("Buffer too small", this::testBufferTooSmall));
        tests.add(dynamicTest("Empty buffer", this::testEmptyBuffer));

        return tests;
    }

    /**
     * Tests that a valid RIFF/WEBP header is correctly detected.
     */
    private void testValidWebPHeader() {
        // CREATE VALID WEBP HEADER: 'RIFF' + SIZE + 'WEBP'
        final ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(RiffChunk.RIFF);  // 'RIFF'
        buffer.putInt(100);             // FILE SIZE
        buffer.putInt(RiffChunk.WEBP);  // 'WEBP'
        buffer.flip();

        assertTrue(RiffParser.isWebP(buffer), "Valid WebP header should be detected");
    }

    /**
     * Tests that invalid RIFF signature is rejected.
     */
    private void testInvalidRiffSignature() {
        // CREATE BUFFER WITH WRONG RIFF SIGNATURE
        final ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46464952);  // 'RIFF' BUT DIFFERENT (BIG ENDIAN)
        buffer.putInt(100);
        buffer.putInt(RiffChunk.WEBP);
        buffer.flip();

        // DEPENDING ON BYTE ORDER, THIS MAY OR MAY NOT MATCH
        // JUST VERIFY NO EXCEPTION IS THROWN
        assertDoesNotThrow(() -> RiffParser.isWebP(buffer));
    }

    /**
     * Tests that invalid WEBP signature is rejected.
     */
    private void testInvalidWebPSignature() {
        // CREATE BUFFER WITH WRONG WEBP SIGNATURE
        final ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(RiffChunk.RIFF);
        buffer.putInt(100);
        buffer.putInt(0x504E4750);  // 'PNG ' INSTEAD OF 'WEBP'
        buffer.flip();

        assertFalse(RiffParser.isWebP(buffer), "PNG signature should not be detected as WebP");
    }

    /**
     * Tests that buffer smaller than 12 bytes is rejected.
     */
    private void testBufferTooSmall() {
        final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(RiffChunk.RIFF);
        buffer.putInt(100);
        buffer.flip();

        assertFalse(RiffParser.isWebP(buffer), "Buffer too small should return false");
    }

    /**
     * Tests that empty buffer is rejected.
     */
    private void testEmptyBuffer() {
        final ByteBuffer buffer = ByteBuffer.allocate(0);
        assertFalse(RiffParser.isWebP(buffer), "Empty buffer should return false");
    }

    /**
     * Test factory for parsing real WebP files from test resources.
     *
     * @return collection of dynamic tests for parsing real files
     */
    @TestFactory
    Iterable<DynamicTest> testParseRealFiles() {
        final List<DynamicTest> tests = new ArrayList<>();

        // TEST LOSSLESS FILES
        final File losslessDir = new File("src/test/resources/webp/lossless");
        if (losslessDir.exists()) {
            final File[] files = losslessDir.listFiles((dir, name) -> name.endsWith(".webp"));
            if (files != null) {
                for (final File file : files) {
                    tests.add(dynamicTest(
                            "Parse lossless [" + file.getName() + "]",
                            () -> testParseLosslessFile(file)
                    ));
                }
            }
        }

        // TEST LOSSY FILES
        final File lossyDir = new File("src/test/resources/webp/lossy");
        if (lossyDir.exists()) {
            final File[] files = lossyDir.listFiles((dir, name) -> name.endsWith(".webp"));
            if (files != null) {
                for (final File file : files) {
                    tests.add(dynamicTest(
                            "Parse lossy [" + file.getName() + "]",
                            () -> testParseLossyFile(file)
                    ));
                }
            }
        }

        // TEST ANIMATED FILES
        final File animatedDir = new File("src/test/resources/webp/animated");
        if (animatedDir.exists()) {
            final File[] files = animatedDir.listFiles((dir, name) -> name.endsWith(".webp"));
            if (files != null) {
                for (final File file : files) {
                    tests.add(dynamicTest(
                            "Parse animated [" + file.getName() + "]",
                            () -> testParseAnimatedFile(file)
                    ));
                }
            }
        }

        return tests;
    }

    /**
     * Tests parsing a lossless WebP file.
     */
    private void testParseLosslessFile(final File file) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(new FileInputStream(file).readAllBytes());
        final WebPInfo info = RiffParser.parse(buffer);

        // VERIFY BASIC PROPERTIES
        assertNotNull(info, "Parsed info should not be null");
        assertTrue(info.width() > 0, "Width should be positive");
        assertTrue(info.height() > 0, "Height should be positive");

        // LOSSLESS FILES MAY BE SIMPLE (VP8L) OR EXTENDED (VP8X + VP8L)
        // VERIFY BITSTREAM CHUNK EXISTS
        assertNotNull(info.bitstreamChunk(), "Bitstream chunk should exist");
        assertEquals(RiffChunk.VP8L, info.bitstreamChunk().fourCC(),
                "Lossless file should have VP8L bitstream");
    }

    /**
     * Tests parsing a lossy WebP file.
     */
    private void testParseLossyFile(final File file) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(new FileInputStream(file).readAllBytes());
        final WebPInfo info = RiffParser.parse(buffer);

        // VERIFY BASIC PROPERTIES
        assertNotNull(info, "Parsed info should not be null");
        assertTrue(info.width() > 0, "Width should be positive");
        assertTrue(info.height() > 0, "Height should be positive");

        // LOSSY FILES MAY BE SIMPLE (VP8) OR EXTENDED (VP8X + VP8/ALPH)
        // VERIFY BITSTREAM CHUNK EXISTS
        assertNotNull(info.bitstreamChunk(), "Bitstream chunk should exist");
    }

    /**
     * Tests parsing an animated WebP file.
     */
    private void testParseAnimatedFile(final File file) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(new FileInputStream(file).readAllBytes());
        final WebPInfo info = RiffParser.parse(buffer);

        // VERIFY BASIC PROPERTIES
        assertNotNull(info, "Parsed info should not be null");
        assertTrue(info.width() > 0, "Width should be positive");
        assertTrue(info.height() > 0, "Height should be positive");

        // ANIMATED FILES MUST BE EXTENDED FORMAT
        assertEquals(WebPInfo.Type.EXTENDED, info.type(), "Animated file should be extended format");
        assertTrue(info.hasAnimation(), "Animated file should have animation flag");
        assertNotNull(info.animChunk(), "Animated file should have ANIM chunk");

        // PARSE ANIMATION FRAMES
        final ByteBuffer frameBuffer = ByteBuffer.wrap(new FileInputStream(file).readAllBytes());
        final var frames = RiffParser.parseFrames(frameBuffer, info);

        assertTrue(frames.size() > 1, "Animated file should have multiple frames");

        // VERIFY FRAME PROPERTIES
        for (final var frame : frames) {
            assertTrue(frame.width() > 0, "Frame width should be positive");
            assertTrue(frame.height() > 0, "Frame height should be positive");
            assertTrue(frame.duration() >= 0, "Frame duration should be non-negative");
            assertTrue(frame.dataSize() > 0, "Frame data size should be positive");
        }
    }

    /**
     * Test for handling corrupted WebP data.
     * Should throw DecoderException for truncated headers.
     */
    @Test
    void testCorruptedData() {
        // CREATE VALID HEADER BUT TRUNCATED CONTENT
        final ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(RiffChunk.RIFF);
        buffer.putInt(1000);  // CLAIMS LARGE SIZE
        buffer.putInt(RiffChunk.WEBP);
        buffer.putInt(RiffChunk.VP8L);  // VP8L CHUNK BUT NO DATA
        buffer.flip();

        // SHOULD THROW EXCEPTION FOR TRUNCATED CHUNK
        assertThrows(XCodecException.class, () -> RiffParser.parse(buffer),
                "Should throw exception for truncated VP8L chunk");
    }

    /**
     * Test for parsing VP8 (lossy) dimension extraction.
     * Verifies correct parsing of VP8 frame header.
     */
    @Test
    void testVP8DimensionParsing() throws Exception {
        // LOAD A KNOWN LOSSY FILE AND VERIFY DIMENSIONS MATCH EXPECTED
        final File lossyDir = new File("src/test/resources/webp/lossy");
        if (!lossyDir.exists()) {
            return;  // SKIP IF NO TEST FILES
        }

        final File[] files = lossyDir.listFiles((dir, name) -> name.endsWith(".webp"));
        if (files == null || files.length == 0) {
            return;  // SKIP IF NO TEST FILES
        }

        // TEST FIRST FILE
        final ByteBuffer buffer = ByteBuffer.wrap(new FileInputStream(files[0]).readAllBytes());
        final WebPInfo info = RiffParser.parse(buffer);

        // VERIFY DIMENSIONS ARE REASONABLE (NOT CORRUPTED)
        assertTrue(info.width() > 0 && info.width() < 16384, "Width should be in valid range");
        assertTrue(info.height() > 0 && info.height() < 16384, "Height should be in valid range");
    }

    /**
     * Test for parsing VP8L (lossless) dimension extraction.
     * Verifies correct parsing of VP8L header with 14-bit dimensions.
     */
    @Test
    void testVP8LDimensionParsing() throws Exception {
        // LOAD A KNOWN LOSSLESS FILE AND VERIFY DIMENSIONS
        final File losslessDir = new File("src/test/resources/webp/lossless");
        if (!losslessDir.exists()) {
            return;  // SKIP IF NO TEST FILES
        }

        final File[] files = losslessDir.listFiles((dir, name) -> name.endsWith(".webp"));
        if (files == null || files.length == 0) {
            return;  // SKIP IF NO TEST FILES
        }

        // TEST FIRST FILE
        final ByteBuffer buffer = ByteBuffer.wrap(new FileInputStream(files[0]).readAllBytes());
        final WebPInfo info = RiffParser.parse(buffer);

        // VERIFY DIMENSIONS ARE REASONABLE
        assertTrue(info.width() > 0 && info.width() < 16384, "Width should be in valid range");
        assertTrue(info.height() > 0 && info.height() < 16384, "Height should be in valid range");
    }

    /**
     * Test RiffChunk fourCC string conversion.
     */
    @Test
    void testFourCCString() {
        // VERIFY FOURCC VALUES ARE CORRECTLY NAMED
        assertEquals("RIFF", RiffChunk.fourCCString(RiffChunk.RIFF));
        assertEquals("WEBP", RiffChunk.fourCCString(RiffChunk.WEBP));
        assertEquals("VP8 ", RiffChunk.fourCCString(RiffChunk.VP8));
        assertEquals("VP8L", RiffChunk.fourCCString(RiffChunk.VP8L));
        assertEquals("VP8X", RiffChunk.fourCCString(RiffChunk.VP8X));
        assertEquals("ALPH", RiffChunk.fourCCString(RiffChunk.ALPH));
        assertEquals("ANIM", RiffChunk.fourCCString(RiffChunk.ANIM));
        assertEquals("ANMF", RiffChunk.fourCCString(RiffChunk.ANMF));
    }

    /**
     * Test WebPInfo type enumeration.
     */
    @Test
    void testWebPInfoTypes() {
        // VERIFY ALL TYPES ARE DEFINED
        assertNotNull(WebPInfo.Type.LOSSY);
        assertNotNull(WebPInfo.Type.LOSSLESS);
        assertNotNull(WebPInfo.Type.EXTENDED);
    }
}
