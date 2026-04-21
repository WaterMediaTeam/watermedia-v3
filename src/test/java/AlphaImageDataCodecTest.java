import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.webp.common.AlphaDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Unit tests for the AlphaDecoder class used in WebP lossy+alpha decoding.
 * Tests cover uncompressed alpha, filtering methods, and alpha application to ARGB pixels.
 */
public class AlphaImageDataCodecTest {

    // HEADER BYTE FORMAT: [FILTER:2][PREPROC:2][COMPRESSION:2][RESERVED:2]
    // COMPRESSION: 0 = NONE, 1 = LOSSLESS
    // FILTER: 0 = NONE, 1 = HORIZONTAL, 2 = VERTICAL, 3 = GRADIENT

    /**
     * Test factory for uncompressed alpha decoding with different filter methods.
     *
     * @return collection of dynamic tests for each filter type
     */
    @TestFactory
    Iterable<DynamicTest> testUncompressedAlpha() {
        final List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("No filter", () -> testUncompressedNoFilter()));
        tests.add(dynamicTest("Horizontal filter", () -> testUncompressedHorizontalFilter()));
        tests.add(dynamicTest("Vertical filter", () -> testUncompressedVerticalFilter()));
        tests.add(dynamicTest("Gradient filter", () -> testUncompressedGradientFilter()));

        return tests;
    }

    /**
     * Tests uncompressed alpha with no filtering.
     * Raw alpha values should pass through unchanged.
     */
    private void testUncompressedNoFilter() throws XCodecException {
        final int width = 4;
        final int height = 4;

        // CREATE HEADER: FILTER=0, COMPRESSION=0
        final byte header = 0x00;

        // CREATE ALPHA DATA (ALL 128 = 50% TRANSPARENT)
        final byte[] alphaData = new byte[width * height];
        for (int i = 0; i < alphaData.length; i++) {
            alphaData[i] = (byte) 128;
        }

        // COMBINE HEADER + DATA
        final ByteBuffer buffer = ByteBuffer.allocate(1 + alphaData.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(header);
        buffer.put(alphaData);
        buffer.flip();

        // DECODE
        final byte[] result = AlphaDecoder.decode(buffer, width, height);

        // VERIFY ALL VALUES ARE 128
        assertEquals(width * height, result.length, "Result length should match dimensions");
        for (int i = 0; i < result.length; i++) {
            assertEquals((byte) 128, result[i], "Alpha value at " + i + " should be 128");
        }
    }

    /**
     * Tests uncompressed alpha with horizontal filter.
     * Each pixel adds the value of the pixel to its left.
     */
    private void testUncompressedHorizontalFilter() throws XCodecException {
        final int width = 4;
        final int height = 2;

        // CREATE HEADER: FILTER=1 (HORIZONTAL), COMPRESSION=0
        // HEADER = (FILTER << 4) | COMPRESSION = (1 << 4) | 0 = 0x10
        final byte header = 0x10;

        // CREATE FILTERED ALPHA DATA
        // ROW 0: [10, 5, 5, 5] -> AFTER INVERSE FILTER: [10, 15, 20, 25]
        // ROW 1: [20, 10, 10, 10] -> AFTER INVERSE FILTER: [20, 30, 40, 50]
        final byte[] filteredData = {10, 5, 5, 5, 20, 10, 10, 10};

        // COMBINE HEADER + DATA
        final ByteBuffer buffer = ByteBuffer.allocate(1 + filteredData.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(header);
        buffer.put(filteredData);
        buffer.flip();

        // DECODE
        final byte[] result = AlphaDecoder.decode(buffer, width, height);

        // VERIFY EXPECTED VALUES AFTER INVERSE HORIZONTAL FILTER
        final int[] expected = {10, 15, 20, 25, 20, 30, 40, 50};
        for (int i = 0; i < result.length; i++) {
            assertEquals((byte) expected[i], result[i], "Alpha value at " + i + " should be " + expected[i]);
        }
    }

    /**
     * Tests uncompressed alpha with vertical filter.
     * Each pixel adds the value of the pixel above it.
     */
    private void testUncompressedVerticalFilter() throws XCodecException {
        final int width = 2;
        final int height = 4;

        // CREATE HEADER: FILTER=2 (VERTICAL), COMPRESSION=0
        // HEADER = (FILTER << 4) | COMPRESSION = (2 << 4) | 0 = 0x20
        final byte header = 0x20;

        // CREATE FILTERED ALPHA DATA
        // COL 0: [10, 5, 5, 5] -> AFTER INVERSE FILTER: [10, 15, 20, 25]
        // COL 1: [20, 10, 10, 10] -> AFTER INVERSE FILTER: [20, 30, 40, 50]
        // DATA IS ROW-MAJOR: [10, 20, 5, 10, 5, 10, 5, 10]
        final byte[] filteredData = {10, 20, 5, 10, 5, 10, 5, 10};

        // COMBINE HEADER + DATA
        final ByteBuffer buffer = ByteBuffer.allocate(1 + filteredData.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(header);
        buffer.put(filteredData);
        buffer.flip();

        // DECODE
        final byte[] result = AlphaDecoder.decode(buffer, width, height);

        // VERIFY EXPECTED VALUES AFTER INVERSE VERTICAL FILTER
        // ROW 0: [10, 20] (UNCHANGED - FIRST ROW)
        // ROW 1: [10+5=15, 20+10=30]
        // ROW 2: [15+5=20, 30+10=40]
        // ROW 3: [20+5=25, 40+10=50]
        final int[] expected = {10, 20, 15, 30, 20, 40, 25, 50};
        for (int i = 0; i < result.length; i++) {
            assertEquals((byte) expected[i], result[i], "Alpha value at " + i + " should be " + expected[i]);
        }
    }

    /**
     * Tests uncompressed alpha with gradient filter.
     * Uses gradient prediction: clamp(left + top - topLeft).
     */
    private void testUncompressedGradientFilter() throws XCodecException {
        final int width = 2;
        final int height = 2;

        // CREATE HEADER: FILTER=3 (GRADIENT), COMPRESSION=0
        // HEADER = (FILTER << 4) | COMPRESSION = (3 << 4) | 0 = 0x30
        final byte header = 0x30;

        // CREATE SIMPLE GRADIENT FILTERED DATA
        // POSITION [0,0] = 100 (NO PREDICTION - FIRST PIXEL)
        // POSITION [1,0] = 10 (HORIZONTAL ONLY: RESULT = 100 + 10 = 110)
        // POSITION [0,1] = 20 (VERTICAL ONLY: RESULT = 100 + 20 = 120)
        // POSITION [1,1] = 0 (GRADIENT: PRED = 110 + 120 - 100 = 130, RESULT = 130 + 0 = 130)
        final byte[] filteredData = {100, 10, 20, 0};

        // COMBINE HEADER + DATA
        final ByteBuffer buffer = ByteBuffer.allocate(1 + filteredData.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(header);
        buffer.put(filteredData);
        buffer.flip();

        // DECODE
        final byte[] result = AlphaDecoder.decode(buffer, width, height);

        // VERIFY EXPECTED VALUES AFTER INVERSE GRADIENT FILTER
        final int[] expected = {100, 110, 120, (byte) 130};
        assertEquals((byte) 100, result[0], "Position [0,0] should be 100");
        assertEquals((byte) 110, result[1], "Position [1,0] should be 110");
        assertEquals((byte) 120, result[2], "Position [0,1] should be 120");
        assertEquals((byte) 130, result[3], "Position [1,1] should be 130");
    }

    /**
     * Test for applying alpha to ARGB pixel array.
     * Verifies alpha values are correctly placed in the high byte of each pixel.
     */
    @Test
    void testApplyAlpha() {
        // CREATE ARGB PIXELS (OPAQUE RED, GREEN, BLUE)
        final int[] argb = {
                0xFF_FF_00_00,  // RED (WILL HAVE ALPHA REPLACED)
                0xFF_00_FF_00,  // GREEN
                0xFF_00_00_FF   // BLUE
        };

        // CREATE ALPHA VALUES
        final byte[] alpha = {(byte) 128, (byte) 64, (byte) 255};

        // APPLY ALPHA
        AlphaDecoder.applyAlpha(argb, alpha);

        // VERIFY ALPHA WAS APPLIED (HIGH BYTE REPLACED)
        assertEquals(0x80_FF_00_00, argb[0], "Red pixel should have alpha 128");
        assertEquals(0x40_00_FF_00, argb[1], "Green pixel should have alpha 64");
        assertEquals(0xFF_00_00_FF, argb[2], "Blue pixel should have alpha 255");
    }

    /**
     * Test for applying alpha directly to a BGRA ByteBuffer in-place.
     * Verifies alpha bytes are written at the correct offsets (i*4+3).
     */
    @Test
    void testApplyAlphaToBgraBuffer() {
        // CREATE BGRA BYTEBUFFER WITH 3 PIXELS (OPAQUE RED, GREEN, BLUE)
        // BGRA LAYOUT: [B, G, R, A] PER PIXEL
        final ByteBuffer bgra = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.LITTLE_ENDIAN);

        // PIXEL 0: RED (B=0, G=0, R=255, A=255)
        bgra.put((byte) 0).put((byte) 0).put((byte) 255).put((byte) 255);
        // PIXEL 1: GREEN (B=0, G=255, R=0, A=255)
        bgra.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 255);
        // PIXEL 2: BLUE (B=255, G=0, R=0, A=255)
        bgra.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
        bgra.flip();

        // CREATE ALPHA VALUES
        final byte[] alpha = {(byte) 128, (byte) 64, (byte) 0};

        // APPLY ALPHA IN-PLACE
        AlphaDecoder.applyAlpha(bgra, alpha);

        // VERIFY ALPHA BYTES WERE WRITTEN AT CORRECT OFFSETS
        assertEquals((byte) 128, bgra.get(3), "Pixel 0 alpha should be 128");
        assertEquals((byte) 64, bgra.get(7), "Pixel 1 alpha should be 64");
        assertEquals((byte) 0, bgra.get(11), "Pixel 2 alpha should be 0");

        // VERIFY BGR VALUES WERE NOT MODIFIED
        assertEquals((byte) 0, bgra.get(0), "Pixel 0 B should be unchanged");
        assertEquals((byte) 0, bgra.get(1), "Pixel 0 G should be unchanged");
        assertEquals((byte) 255, bgra.get(2), "Pixel 0 R should be unchanged");
        assertEquals((byte) 0, bgra.get(4), "Pixel 1 B should be unchanged");
        assertEquals((byte) 255, bgra.get(5), "Pixel 1 G should be unchanged");
        assertEquals((byte) 0, bgra.get(6), "Pixel 1 R should be unchanged");
    }

    /**
     * Test for handling truncated alpha data.
     * Should throw DecoderException when data is too short.
     */
    @Test
    void testTruncatedData() {
        final int width = 4;
        final int height = 4;

        // CREATE HEADER ONLY (NO ALPHA DATA)
        final ByteBuffer buffer = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x00);
        buffer.flip();

        // SHOULD THROW EXCEPTION
        assertThrows(XCodecException.class, () -> AlphaDecoder.decode(buffer, width, height),
                "Should throw exception for truncated data");
    }

    /**
     * Test for handling empty buffer.
     * Should throw DecoderException when buffer is empty.
     */
    @Test
    void testEmptyBuffer() {
        final ByteBuffer buffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);

        // SHOULD THROW EXCEPTION
        assertThrows(XCodecException.class, () -> AlphaDecoder.decode(buffer, 4, 4),
                "Should throw exception for empty buffer");
    }

    /**
     * Test for unknown compression method.
     * Compression values other than 0 (none) and 1 (lossless) should throw.
     */
    @Test
    void testUnknownCompression() {
        // CREATE HEADER WITH UNKNOWN COMPRESSION (2)
        final byte header = 0x02;

        final ByteBuffer buffer = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(header);
        // ADD SOME DUMMY DATA
        for (int i = 0; i < 99; i++) {
            buffer.put((byte) 0);
        }
        buffer.flip();

        // SHOULD THROW EXCEPTION FOR UNKNOWN COMPRESSION
        assertThrows(XCodecException.class, () -> AlphaDecoder.decode(buffer, 4, 4),
                "Should throw exception for unknown compression method");
    }

    /**
     * Test alpha application with mismatched array sizes.
     * Should handle gracefully when alpha array is shorter than argb array.
     */
    @Test
    void testApplyAlphaMismatchedSizes() {
        // CREATE ARGB ARRAY WITH 5 PIXELS
        final int[] argb = {
                0xFF_FF_00_00,
                0xFF_00_FF_00,
                0xFF_00_00_FF,
                0xFF_FF_FF_00,
                0xFF_00_FF_FF
        };

        // CREATE ALPHA ARRAY WITH ONLY 3 VALUES
        final byte[] alpha = {(byte) 100, (byte) 150, (byte) 200};

        // APPLY ALPHA - SHOULD ONLY MODIFY FIRST 3 PIXELS
        AlphaDecoder.applyAlpha(argb, alpha);

        // VERIFY FIRST 3 PIXELS MODIFIED, LAST 2 UNCHANGED
        assertEquals(0x64_FF_00_00, argb[0], "Pixel 0 should have alpha 100");
        assertEquals(0x96_00_FF_00, argb[1], "Pixel 1 should have alpha 150");
        assertEquals(0xC8_00_00_FF, argb[2], "Pixel 2 should have alpha 200");
        assertEquals(0xFF_FF_FF_00, argb[3], "Pixel 3 should be unchanged");
        assertEquals(0xFF_00_FF_FF, argb[4], "Pixel 4 should be unchanged");
    }
}
