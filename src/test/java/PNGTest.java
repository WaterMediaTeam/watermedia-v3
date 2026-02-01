import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.formats.png.PNG;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Comprehensive PNG decoder tests comparing Java implementation against reference data.
 * Tests cover static and animated PNG formats with pixel-accurate verification.
 * Reference data is pre-generated and stored as .pam files.
 * For animated PNGs, only the first frame is compared since PAM doesn't support animation.
 */
public class PNGTest {

    // PNG IS LOSSLESS, SO WE EXPECT PIXEL-PERFECT MATCH
    private static final int LOSSLESS_TOLERANCE = 0;

    /**
     * Test factory for PNG decoding.
     * Verifies that each PNG file decodes successfully and
     * the first frame output matches the reference PAM file exactly (pixel-perfect).
     *
     * @return collection of dynamic tests for all PNG files
     */
    @TestFactory
    Iterable<DynamicTest> testPNG() {
        return this.createTestsForDirectory("src/test/resources/png");
    }

    /**
     * Creates dynamic tests for all PNG files in a directory.
     *
     * @param folderPath path to the test resources folder
     * @return collection of dynamic tests
     */
    private Iterable<DynamicTest> createTestsForDirectory(final String folderPath) {
        final File testFolder = new File(folderPath);

        // VERIFY TEST FOLDER EXISTS
        assertTrue(testFolder.exists(), "Test folder does not exist: " + folderPath);

        final File[] imageFiles = testFolder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png"));

        // VERIFY PNG FILES ARE PRESENT
        assertNotNull(imageFiles, "No PNG files found in: " + folderPath);
        assertTrue(imageFiles.length > 0, "Test folder is empty: " + folderPath);

        final List<DynamicTest> tests = new ArrayList<>();

        for (final File imageFile : imageFiles) {
            final String baseName = imageFile.getName();

            // TEST 1: VERIFY DECODING SUCCEEDS
            tests.add(dynamicTest(
                    "PNG decode [" + baseName + "]",
                    () -> testDecodeSucceeds(imageFile)
            ));

            // TEST 2: VERIFY FIRST FRAME MATCHES REFERENCE
            tests.add(dynamicTest(
                    "PNG data match [" + baseName + "]",
                    () -> testDataMatchesReference(imageFile, folderPath)
            ));
        }

        return tests;
    }

    /**
     * Tests that a PNG file decodes without throwing exceptions.
     * Verifies basic properties of the decoded image.
     */
    private void testDecodeSucceeds(final File imageFile) throws Exception {
        final PNG decoder = new PNG();
        final byte[] fileBytes = new FileInputStream(imageFile).readAllBytes();
        final ByteBuffer imageData = ByteBuffer.wrap(fileBytes);

        // VERIFY PNG SIGNATURE IS RECOGNIZED
        assertTrue(decoder.supported(imageData),
                "PNG signature not recognized for: " + imageFile.getName());

        // NOTE: supported() leaves position after signature, which is where decode expects it
        // DECODE THE IMAGE
        final Image result = assertDoesNotThrow(
                () -> decoder.decode(imageData),
                "Decoding failed for: " + imageFile.getName()
        );

        // VERIFY BASIC PROPERTIES
        assertNotNull(result, "Decoded image is null");
        assertTrue(result.width() > 0, "Width must be positive");
        assertTrue(result.height() > 0, "Height must be positive");
        assertNotNull(result.frames(), "Frames array is null");
        assertTrue(result.frames().length > 0, "No frames decoded");

        // VERIFY FRAME BUFFER SIZE MATCHES DIMENSIONS
        final int expectedBufferSize = result.width() * result.height() * 4; // BGRA = 4 BYTES PER PIXEL
        for (int i = 0; i < result.frames().length; i++) {
            final ByteBuffer frame = result.frames()[i];
            assertNotNull(frame, "Frame " + i + " is null");
            assertEquals(
                    expectedBufferSize,
                    frame.capacity(),
                    "Frame " + i + " buffer size mismatch"
            );
        }

        // LOG ANIMATION INFO IF MULTI-FRAME
        if (result.frames().length > 1) {
            System.out.println("Animated PNG detected: " + imageFile.getName() +
                    " - " + result.frames().length + " frames, duration: " + result.duration() + "ms");
        }
    }

    /**
     * Tests that the first frame of decoded pixel data matches reference output exactly.
     * Uses pre-generated .pam reference files for comparison.
     * For animated PNGs, only the first frame is compared since PAM doesn't support animation.
     */
    private void testDataMatchesReference(final File imageFile, final String folderPath) throws Exception {
        // DECODE WITH JAVA IMPLEMENTATION
        final PNG decoder = new PNG();
        final byte[] fileBytes = new FileInputStream(imageFile).readAllBytes();
        final ByteBuffer imageData = ByteBuffer.wrap(fileBytes);

        // VERIFY SIGNATURE (this moves position past signature where decode expects it)
        assertTrue(decoder.supported(imageData), "PNG signature not recognized");

        final Image javaResult = decoder.decode(imageData);

        // LOAD REFERENCE DATA FROM PRE-GENERATED PAM FILE
        final byte[] referenceRgba = loadReferenceData(imageFile, folderPath);

        // CONVERT JAVA BGRA (FIRST FRAME) TO RGBA FOR COMPARISON
        final ByteBuffer javaBgra = javaResult.frames()[0];
        javaBgra.rewind();
        final byte[] javaRgba = convertBgraToRgba(javaBgra, javaResult.width(), javaResult.height());

        // VERIFY SIZES MATCH
        assertEquals(
                referenceRgba.length,
                javaRgba.length,
                "Pixel data size mismatch for " + imageFile.getName() +
                        " - reference: " + referenceRgba.length + ", java: " + javaRgba.length
        );

        // COMPARE PIXEL DATA
        int maxDiff = 0;
        int diffCount = 0;
        int firstDiffIndex = -1;
        final int pixelCount = javaResult.width() * javaResult.height();

        for (int i = 0; i < referenceRgba.length; i++) {
            final int ref = referenceRgba[i] & 0xFF;
            final int java = javaRgba[i] & 0xFF;
            final int diff = Math.abs(ref - java);

            if (diff > maxDiff) {
                maxDiff = diff;
            }
            if (diff > LOSSLESS_TOLERANCE) {
                if (firstDiffIndex < 0) {
                    firstDiffIndex = i;
                }
                diffCount++;
            }
        }

        // CALCULATE PERCENTAGE OF PIXELS THAT DIFFER
        final double diffPercentage = (diffCount * 100.0) / (pixelCount * 4); // 4 CHANNELS PER PIXEL

        // ASSERT DATA MATCHES EXACTLY (PNG IS LOSSLESS)
        assertEquals(
                0,
                maxDiff,
                String.format(
                        "Pixel data mismatch for %s - max diff: %d, %d values differ (%.2f%%), first diff at index %d",
                        imageFile.getName(), maxDiff, diffCount, diffPercentage, firstDiffIndex
                )
        );
    }

    /**
     * Loads reference RGBA data from pre-generated .pam file.
     * PAM files are stored in the 'raw' subdirectory with same base name.
     */
    private byte[] loadReferenceData(final File pngFile, final String folderPath) throws Exception {
        // CONSTRUCT PATH TO REFERENCE PAM FILE
        final String baseName = pngFile.getName().replace(".png", ".pam");
        final File pamFile = new File(folderPath + "/raw/" + baseName);

        assertTrue(pamFile.exists(),
                "Reference PAM file not found: " + pamFile.getAbsolutePath() +
                        " - Generate reference data using: convert input.png -depth 8 output.pam");

        // PARSE PAM FILE TO EXTRACT RAW RGBA DATA
        return parsePamFile(pamFile);
    }

    /**
     * Parses a PAM (Portable Arbitrary Map) file and extracts raw RGBA pixel data.
     * PAM format: P7 header followed by raw pixel data.
     */
    private byte[] parsePamFile(final File pamFile) throws Exception {
        final byte[] fileData = Files.readAllBytes(pamFile.toPath());

        // FIND END OF HEADER (ENDHDR\n)
        int headerEnd = -1;
        for (int i = 0; i < fileData.length - 7; i++) {
            if (fileData[i] == 'E' && fileData[i + 1] == 'N' &&
                    fileData[i + 2] == 'D' && fileData[i + 3] == 'H' &&
                    fileData[i + 4] == 'D' && fileData[i + 5] == 'R' &&
                    fileData[i + 6] == '\n') {
                headerEnd = i + 7;
                break;
            }
        }

        assertTrue(headerEnd > 0, "Invalid PAM file - ENDHDR not found in: " + pamFile.getName());

        // EXTRACT RAW PIXEL DATA AFTER HEADER
        final int dataLength = fileData.length - headerEnd;
        final byte[] pixelData = new byte[dataLength];
        System.arraycopy(fileData, headerEnd, pixelData, 0, dataLength);

        return pixelData;
    }

    /**
     * Converts BGRA byte buffer to RGBA byte array for comparison with reference data.
     */
    private byte[] convertBgraToRgba(final ByteBuffer bgra, final int width, final int height) {
        final int pixelCount = width * height;
        final byte[] rgba = new byte[pixelCount * 4];

        bgra.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < pixelCount; i++) {
            final int srcOffset = i * 4;
            final int dstOffset = i * 4;

            // READ BGRA
            final byte b = bgra.get(srcOffset);
            final byte g = bgra.get(srcOffset + 1);
            final byte r = bgra.get(srcOffset + 2);
            final byte a = bgra.get(srcOffset + 3);

            // WRITE RGBA
            rgba[dstOffset] = r;
            rgba[dstOffset + 1] = g;
            rgba[dstOffset + 2] = b;
            rgba[dstOffset + 3] = a;
        }

        return rgba;
    }
}
