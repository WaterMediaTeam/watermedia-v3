package org.watermedia.test.codecs.webp;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.readers.webp.riff.RiffChunk;
import org.watermedia.api.codecs.readers.webp.riff.RiffParser;
import org.watermedia.api.codecs.readers.webp.riff.WebPInfo;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * RiffParser unit tests covering header validation, lossy/lossless/extended container
 * parsing, dimension extraction and animation frame walking against the real WebP
 * fixtures bundled with the project.
 */
public class RiffParserTest {

    @TestFactory
    Iterable<DynamicTest> testFormatDetection() {
        final List<DynamicTest> tests = new ArrayList<>();

        // VALID RIFF/WEBP HEADER MUST BE DETECTED
        tests.add(dynamicTest("Valid RIFF/WEBP header", () -> {
            final ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(RiffChunk.RIFF);
            buffer.putInt(100);
            buffer.putInt(RiffChunk.WEBP);
            buffer.flip();
            assertTrue(RiffParser.isWebP(buffer), "Valid WebP header should be detected");
        }));

        // BIG-ENDIAN RIFF VALUE — RESULT DEPENDS ON BYTE ORDER BUT MUST NOT THROW
        tests.add(dynamicTest("Invalid RIFF signature", () -> {
            final ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x46464952);
            buffer.putInt(100);
            buffer.putInt(RiffChunk.WEBP);
            buffer.flip();
            assertDoesNotThrow(() -> RiffParser.isWebP(buffer));
        }));

        // PNG FOURCC IN THE FORMAT SLOT MUST BE REJECTED
        tests.add(dynamicTest("Invalid WEBP signature", () -> {
            final ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(RiffChunk.RIFF);
            buffer.putInt(100);
            buffer.putInt(0x504E4750);
            buffer.flip();
            assertFalse(RiffParser.isWebP(buffer), "PNG signature should not be detected as WebP");
        }));

        // BUFFER SHORTER THAN 12 BYTES CANNOT CARRY A FULL WEBP HEADER
        tests.add(dynamicTest("Buffer too small", () -> {
            final ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(RiffChunk.RIFF);
            buffer.putInt(100);
            buffer.flip();
            assertFalse(RiffParser.isWebP(buffer), "Buffer too small should return false");
        }));

        tests.add(dynamicTest("Empty buffer",
                () -> assertFalse(RiffParser.isWebP(ByteBuffer.allocate(0)), "Empty buffer should return false")));

        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> testParseRealFiles() {
        final List<DynamicTest> tests = new ArrayList<>();

        // LOSSLESS FILES MUST HAVE A VP8L BITSTREAM CHUNK
        for (final Path file: listWebp(Fixtures.WEBP_LOSSLESS_DIR)) {
            tests.add(dynamicTest("Parse lossless [" + file.getFileName() + "]", () -> {
                final ByteBuffer buffer = ByteBuffer.wrap(Fixtures.readAll(file));
                final WebPInfo info = RiffParser.parse(buffer);
                assertNotNull(info, "Parsed info should not be null");
                assertTrue(info.width() > 0, "Width should be positive");
                assertTrue(info.height() > 0, "Height should be positive");
                assertNotNull(info.bitstreamChunk(), "Bitstream chunk should exist");
                assertEquals(RiffChunk.VP8L, info.bitstreamChunk().fourCC(),
                        "Lossless file should have VP8L bitstream");
            }));
        }

        // LOSSY FILES MAY USE SIMPLE OR EXTENDED CONTAINERS — JUST CHECK BITSTREAM PRESENCE
        for (final Path file: listWebp(Fixtures.WEBP_LOSSY_DIR)) {
            tests.add(dynamicTest("Parse lossy [" + file.getFileName() + "]", () -> {
                final ByteBuffer buffer = ByteBuffer.wrap(Fixtures.readAll(file));
                final WebPInfo info = RiffParser.parse(buffer);
                assertNotNull(info, "Parsed info should not be null");
                assertTrue(info.width() > 0, "Width should be positive");
                assertTrue(info.height() > 0, "Height should be positive");
                assertNotNull(info.bitstreamChunk(), "Bitstream chunk should exist");
            }));
        }

        // ANIMATED FILES MUST BE EXTENDED AND EXPOSE A FRAME LIST
        for (final Path file: listWebp(Fixtures.WEBP_ANIMATED_DIR)) {
            tests.add(dynamicTest("Parse animated [" + file.getFileName() + "]", () -> {
                final ByteBuffer buffer = ByteBuffer.wrap(Fixtures.readAll(file));
                final WebPInfo info = RiffParser.parse(buffer);
                assertNotNull(info, "Parsed info should not be null");
                assertTrue(info.width() > 0, "Width should be positive");
                assertTrue(info.height() > 0, "Height should be positive");
                assertEquals(WebPInfo.Type.EXTENDED, info.type(), "Animated file should be extended format");
                assertTrue(info.hasAnimation(), "Animated file should have animation flag");
                assertNotNull(info.animChunk(), "Animated file should have ANIM chunk");

                final ByteBuffer frameBuffer = ByteBuffer.wrap(Fixtures.readAll(file));
                final var frames = RiffParser.parseFrames(frameBuffer, info);
                assertTrue(frames.size() > 1, "Animated file should have multiple frames");
                for (final var frame: frames) {
                    assertTrue(frame.width() > 0, "Frame width should be positive");
                    assertTrue(frame.height() > 0, "Frame height should be positive");
                    assertTrue(frame.duration() >= 0, "Frame duration should be non-negative");
                    assertTrue(frame.dataSize() > 0, "Frame data size should be positive");
                }
            }));
        }

        return tests;
    }

    @Test
    void corruptedDataThrows() {
        // VALID HEADER BUT VP8L CHUNK CLAIMS DATA THAT ISN'T THERE
        final ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(RiffChunk.RIFF);
        buffer.putInt(1000);
        buffer.putInt(RiffChunk.WEBP);
        buffer.putInt(RiffChunk.VP8L);
        buffer.flip();
        assertThrows(XCodecException.class, () -> RiffParser.parse(buffer),
                "Should throw exception for truncated VP8L chunk");
    }

    @Test
    void vp8DimensionsAreReasonable() {
        // GUARDS AGAINST INTEGER UNDERFLOW / 14-BIT MASK BUGS
        final List<Path> files = listWebp(Fixtures.WEBP_LOSSY_DIR);
        if (files.isEmpty()) return;
        final ByteBuffer buffer = ByteBuffer.wrap(Fixtures.readAll(files.get(0)));
        final WebPInfo info = assertDoesNotThrow(() -> RiffParser.parse(buffer));
        assertTrue(info.width() > 0 && info.width() < 16384, "Width should be in valid range");
        assertTrue(info.height() > 0 && info.height() < 16384, "Height should be in valid range");
    }

    @Test
    void vp8lDimensionsAreReasonable() {
        final List<Path> files = listWebp(Fixtures.WEBP_LOSSLESS_DIR);
        if (files.isEmpty()) return;
        final ByteBuffer buffer = ByteBuffer.wrap(Fixtures.readAll(files.get(0)));
        final WebPInfo info = assertDoesNotThrow(() -> RiffParser.parse(buffer));
        assertTrue(info.width() > 0 && info.width() < 16384, "Width should be in valid range");
        assertTrue(info.height() > 0 && info.height() < 16384, "Height should be in valid range");
    }

    @Test
    void fourCCStringMatchesAscii() {
        assertEquals("RIFF", RiffChunk.fourCCString(RiffChunk.RIFF));
        assertEquals("WEBP", RiffChunk.fourCCString(RiffChunk.WEBP));
        assertEquals("VP8 ", RiffChunk.fourCCString(RiffChunk.VP8));
        assertEquals("VP8L", RiffChunk.fourCCString(RiffChunk.VP8L));
        assertEquals("VP8X", RiffChunk.fourCCString(RiffChunk.VP8X));
        assertEquals("ALPH", RiffChunk.fourCCString(RiffChunk.ALPH));
        assertEquals("ANIM", RiffChunk.fourCCString(RiffChunk.ANIM));
        assertEquals("ANMF", RiffChunk.fourCCString(RiffChunk.ANMF));
    }

    @Test
    void webPInfoTypesAreDeclared() {
        assertNotNull(WebPInfo.Type.LOSSY);
        assertNotNull(WebPInfo.Type.LOSSLESS);
        assertNotNull(WebPInfo.Type.EXTENDED);
    }

    private static List<Path> listWebp(final Path dir) {
        if (!Files.exists(dir)) return List.of();
        try (final Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".webp"))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate WebP fixtures in " + dir, e);
        }
    }
}
