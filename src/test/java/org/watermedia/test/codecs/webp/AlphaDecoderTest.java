package org.watermedia.test.codecs.webp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.webp.AlphaDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * AlphaDecoder unit tests covering uncompressed alpha streams with each of the four
 * filter modes (none, horizontal, vertical, gradient), application of alpha onto ARGB
 * pixels and BGRA byte buffers, and a few error paths (truncated buffer, empty buffer,
 * unknown compression method, mismatched alpha/pixel array sizes).
 *
 * <p>Header byte layout: {@code [FILTER:2][PREPROC:2][COMPRESSION:2][RESERVED:2]} where
 * compression {@code 0} is none and {@code 1} is lossless; filter {@code 0..3} maps to
 * none / horizontal / vertical / gradient.
 */
@DisplayName("AlphaDecoder")
public class AlphaDecoderTest {

    @TestFactory
    @DisplayName("Uncompressed alpha filter modes")
    Iterable<DynamicTest> testUncompressedAlpha() {
        final List<DynamicTest> tests = new ArrayList<>();

        // NO FILTER — RAW ALPHA VALUES PASS THROUGH UNCHANGED
        tests.add(dynamicTest("No filter", () -> {
            final int width = 4;
            final int height = 4;
            final byte[] alphaData = new byte[width * height];
            for (int i = 0; i < alphaData.length; i++) alphaData[i] = (byte) 128;

            final ByteBuffer buffer = ByteBuffer.allocate(1 + alphaData.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 0x00).put(alphaData).flip();

            final byte[] result = AlphaDecoder.decode(buffer, width, height);
            assertEquals(width * height, result.length, "Result length should match dimensions");
            for (int i = 0; i < result.length; i++) {
                assertEquals((byte) 128, result[i], "Alpha value at " + i + " should be 128");
            }
        }));

        // HORIZONTAL FILTER — EACH PIXEL ADDS THE PIXEL TO ITS LEFT
        tests.add(dynamicTest("Horizontal filter", () -> {
            final int width = 4;
            final int height = 2;
            // ROW 0: [10, 5, 5, 5] -> [10, 15, 20, 25]; ROW 1 ANALOGOUS
            final byte[] filteredData = {10, 5, 5, 5, 20, 10, 10, 10};
            final ByteBuffer buffer = ByteBuffer.allocate(1 + filteredData.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 0x10).put(filteredData).flip();

            final byte[] result = AlphaDecoder.decode(buffer, width, height);
            final int[] expected = {10, 15, 20, 25, 20, 30, 40, 50};
            for (int i = 0; i < result.length; i++) {
                assertEquals((byte) expected[i], result[i], "Alpha value at " + i + " should be " + expected[i]);
            }
        }));

        // VERTICAL FILTER — EACH PIXEL ADDS THE PIXEL ABOVE IT (ROW-MAJOR DATA)
        tests.add(dynamicTest("Vertical filter", () -> {
            final int width = 2;
            final int height = 4;
            final byte[] filteredData = {10, 20, 5, 10, 5, 10, 5, 10};
            final ByteBuffer buffer = ByteBuffer.allocate(1 + filteredData.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 0x20).put(filteredData).flip();

            final byte[] result = AlphaDecoder.decode(buffer, width, height);
            // EXPECTED PER COLUMN: COL0 [10,15,20,25] COL1 [20,30,40,50]
            final int[] expected = {10, 20, 15, 30, 20, 40, 25, 50};
            for (int i = 0; i < result.length; i++) {
                assertEquals((byte) expected[i], result[i], "Alpha value at " + i + " should be " + expected[i]);
            }
        }));

        // GRADIENT FILTER — PREDICTION clamp(left + top - topLeft)
        tests.add(dynamicTest("Gradient filter", () -> {
            // POSITION [0,0] = 100  (NO PREDICTION)
            // POSITION [1,0] = 10   (HORIZONTAL ONLY: 100 + 10 = 110)
            // POSITION [0,1] = 20   (VERTICAL ONLY: 100 + 20 = 120)
            // POSITION [1,1] = 0    (GRADIENT 110 + 120 - 100 = 130, RESULT = 130)
            final byte[] filteredData = {100, 10, 20, 0};
            final ByteBuffer buffer = ByteBuffer.allocate(1 + filteredData.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 0x30).put(filteredData).flip();

            final byte[] result = AlphaDecoder.decode(buffer, 2, 2);
            assertEquals((byte) 100, result[0], "Position [0,0] should be 100");
            assertEquals((byte) 110, result[1], "Position [1,0] should be 110");
            assertEquals((byte) 120, result[2], "Position [0,1] should be 120");
            assertEquals((byte) 130, result[3], "Position [1,1] should be 130");
        }));

        return tests;
    }

    @Test
    @DisplayName("applyAlpha replaces the high byte of each ARGB pixel")
    void testApplyAlphaToArgb() {
        // ALPHA BYTE MUST REPLACE THE HIGH BYTE OF EACH ARGB PIXEL
        final int[] argb = {0xFF_FF_00_00, 0xFF_00_FF_00, 0xFF_00_00_FF};
        final byte[] alpha = {(byte) 128, (byte) 64, (byte) 255};

        AlphaDecoder.applyAlpha(argb, alpha);

        assertEquals(0x80_FF_00_00, argb[0], "Red pixel should have alpha 128");
        assertEquals(0x40_00_FF_00, argb[1], "Green pixel should have alpha 64");
        assertEquals(0xFF_00_00_FF, argb[2], "Blue pixel should have alpha 255");
    }

    @Test
    @DisplayName("applyAlpha writes the alpha byte in-place into a BGRA buffer")
    void testApplyAlphaToBgraBuffer() {
        // 3 OPAQUE BGRA PIXELS — APPLY ALPHA AT OFFSETS i*4+3 IN-PLACE
        final ByteBuffer bgra = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.LITTLE_ENDIAN);
        bgra.put((byte) 0).put((byte) 0).put((byte) 255).put((byte) 255);   // RED
        bgra.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 255);   // GREEN
        bgra.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);   // BLUE
        bgra.flip();

        final byte[] alpha = {(byte) 128, (byte) 64, (byte) 0};
        AlphaDecoder.applyAlpha(bgra, alpha);

        assertEquals((byte) 128, bgra.get(3), "Pixel 0 alpha should be 128");
        assertEquals((byte) 64, bgra.get(7), "Pixel 1 alpha should be 64");
        assertEquals((byte) 0, bgra.get(11), "Pixel 2 alpha should be 0");

        // BGR CHANNELS MUST BE LEFT UNTOUCHED
        assertEquals((byte) 0, bgra.get(0), "Pixel 0 B should be unchanged");
        assertEquals((byte) 0, bgra.get(1), "Pixel 0 G should be unchanged");
        assertEquals((byte) 255, bgra.get(2), "Pixel 0 R should be unchanged");
        assertEquals((byte) 0, bgra.get(4), "Pixel 1 B should be unchanged");
        assertEquals((byte) 255, bgra.get(5), "Pixel 1 G should be unchanged");
        assertEquals((byte) 0, bgra.get(6), "Pixel 1 R should be unchanged");
    }

    @Test
    @DisplayName("Truncated data throws")
    void testTruncatedDataThrows() {
        // HEADER ONLY — DECODER MUST REJECT MISSING PAYLOAD
        final ByteBuffer buffer = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x00).flip();
        assertThrows(XCodecException.class, () -> AlphaDecoder.decode(buffer, 4, 4),
                "Should throw exception for truncated data");
    }

    @Test
    @DisplayName("Empty buffer throws")
    void testEmptyBufferThrows() {
        assertThrows(XCodecException.class,
                () -> AlphaDecoder.decode(ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN), 4, 4),
                "Should throw exception for empty buffer");
    }

    @Test
    @DisplayName("Unknown compression method throws")
    void testUnknownCompressionThrows() {
        // HEADER 0x02 — COMPRESSION FIELD IS NEITHER 0 (NONE) NOR 1 (LOSSLESS)
        final ByteBuffer buffer = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x02);
        for (int i = 0; i < 99; i++) buffer.put((byte) 0);
        buffer.flip();
        assertThrows(XCodecException.class, () -> AlphaDecoder.decode(buffer, 4, 4),
                "Should throw exception for unknown compression method");
    }

    @Test
    @DisplayName("applyAlpha with a shorter alpha array touches only the leading pixels")
    void testApplyAlphaMismatchedSizes() {
        // ALPHA ARRAY SHORTER THAN ARGB — ONLY THE LEADING ELEMENTS ARE TOUCHED
        final int[] argb = {
                0xFF_FF_00_00,
                0xFF_00_FF_00,
                0xFF_00_00_FF,
                0xFF_FF_FF_00,
                0xFF_00_FF_FF
        };
        final byte[] alpha = {(byte) 100, (byte) 150, (byte) 200};

        AlphaDecoder.applyAlpha(argb, alpha);

        assertEquals(0x64_FF_00_00, argb[0], "Pixel 0 should have alpha 100");
        assertEquals(0x96_00_FF_00, argb[1], "Pixel 1 should have alpha 150");
        assertEquals(0xC8_00_00_FF, argb[2], "Pixel 2 should have alpha 200");
        assertEquals(0xFF_FF_FF_00, argb[3], "Pixel 3 should be unchanged");
        assertEquals(0xFF_00_FF_FF, argb[4], "Pixel 4 should be unchanged");
    }
}
