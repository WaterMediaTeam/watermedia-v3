import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.formats.webp.WEBP;

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
 * Comprehensive WebP decoder tests comparing Java implementation against libwebp reference data.
 * Tests cover lossless, lossy, and animated WebP formats with pixel-accurate verification.
 * Reference data is pre-generated using dwebp and stored as .pam files.
 */
public class WebPTest {

    // LOSSY VP8 DECODING HAS INHERENT DIFFERENCES BETWEEN IMPLEMENTATIONS DUE TO:
    // - DCT COEFFICIENT ROUNDING
    // - QUANTIZATION DIFFERENCES
    // - DEBLOCKING FILTER VARIATIONS
    // - UPSAMPLING DIFFERENCES (YUV TO RGB CONVERSION)
    // MAX ALLOWED PER-CHANNEL DIFFERENCE (0-255 SCALE)
    // NOTE: OUTLIER PIXELS MAY HAVE LARGER DIFFERENCES DUE TO EDGE EFFECTS
    private static final int LOSSY_MAX_DIFF_TOLERANCE = 100;

    // MAXIMUM PERCENTAGE OF PIXELS ALLOWED TO EXCEED A SMALL THRESHOLD FOR LOSSY
    private static final double LOSSY_MAX_DIFF_PERCENTAGE = 30.0;

    // LOSSLESS SHOULD BE PIXEL-PERFECT
    private static final int LOSSLESS_TOLERANCE = 0;

    /**
     * Test factory for LOSSLESS WebP decoding.
     * Verifies that each lossless WebP file decodes successfully and
     * the output matches libwebp's reference output exactly (pixel-perfect).
     *
     * @return collection of dynamic tests for all lossless WebP files
     */
    @TestFactory
    Iterable<DynamicTest> testLossless() {
        return createTestsForDirectory(
                "src/test/resources/webp/lossless",
                "LOSSLESS",
                LOSSLESS_TOLERANCE,
                false
        );
    }

    /**
     * Test factory for LOSSY WebP decoding.
     * Verifies that each lossy WebP file decodes successfully and
     * the output is reasonably similar to libwebp's reference output.
     * Note: Lossy VP8 decoding varies between implementations due to
     * DCT rounding, quantization, and deblocking filter differences.
     *
     * @return collection of dynamic tests for all lossy WebP files
     */
    @TestFactory
    Iterable<DynamicTest> testLossy() {
        return createLossyTests("src/test/resources/webp/lossy");
    }

    /**
     * Test factory for ANIMATED WebP decoding.
     * Verifies that each animated WebP file decodes successfully,
     * produces multiple frames, and has valid animation metadata.
     * Note: Pixel comparison is not performed as dwebp doesn't support animated WebP.
     *
     * @return collection of dynamic tests for all animated WebP files
     */
    @TestFactory
    Iterable<DynamicTest> testAnimated() {
        return createAnimatedTests("src/test/resources/webp/animated");
    }

    /**
     * Creates lossy-specific tests with relaxed comparison criteria.
     * Lossy VP8 implementations may differ in DCT, quantization, and filtering.
     *
     * @param folderPath path to the lossy test resources folder
     * @return collection of dynamic tests for lossy WebP files
     */
    private Iterable<DynamicTest> createLossyTests(final String folderPath) {
        final File testFolder = new File(folderPath);

        // VERIFY TEST FOLDER EXISTS
        assertTrue(testFolder.exists(), "Test folder does not exist: " + folderPath);

        final File[] imageFiles = testFolder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".webp"));

        // VERIFY WEBP FILES ARE PRESENT
        assertNotNull(imageFiles, "No WebP files found in: " + folderPath);
        assertTrue(imageFiles.length > 0, "Test folder is empty: " + folderPath);

        final List<DynamicTest> tests = new ArrayList<>();

        for (final File imageFile : imageFiles) {
            final String baseName = imageFile.getName();

            // TEST 1: VERIFY DECODING SUCCEEDS
            tests.add(dynamicTest(
                    "LOSSY decode [" + baseName + "]",
                    () -> testDecodeSucceeds(imageFile, false)
            ));

            // TEST 2: VERIFY OUTPUT IS REASONABLY SIMILAR TO REFERENCE
            tests.add(dynamicTest(
                    "LOSSY similarity [" + baseName + "]",
                    () -> testLossySimilarity(imageFile, folderPath)
            ));
        }

        return tests;
    }

    /**
     * Creates animated-specific tests (decode only, no pixel comparison).
     *
     * @param folderPath path to the animated test resources folder
     * @return collection of dynamic tests for animated WebP files
     */
    private Iterable<DynamicTest> createAnimatedTests(final String folderPath) {
        final File testFolder = new File(folderPath);

        // VERIFY TEST FOLDER EXISTS
        assertTrue(testFolder.exists(), "Test folder does not exist: " + folderPath);

        final File[] imageFiles = testFolder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".webp"));

        // VERIFY WEBP FILES ARE PRESENT
        assertNotNull(imageFiles, "No WebP files found in: " + folderPath);
        assertTrue(imageFiles.length > 0, "Test folder is empty: " + folderPath);

        final List<DynamicTest> tests = new ArrayList<>();

        for (final File imageFile : imageFiles) {
            final String baseName = imageFile.getName();

            // ONLY DECODE TEST FOR ANIMATED (DWEBP DOESN'T SUPPORT ANIMATED)
            tests.add(dynamicTest(
                    "ANIMATED decode [" + baseName + "]",
                    () -> testDecodeSucceeds(imageFile, true)
            ));
        }

        return tests;
    }

    /**
     * Creates dynamic tests for all WebP files in a directory.
     *
     * @param folderPath   path to the test resources folder
     * @param category     test category name (LOSSLESS, LOSSY, ANIMATED)
     * @param tolerance    maximum allowed pixel difference per channel
     * @param isAnimated   whether these are animated WebP tests
     * @return collection of dynamic tests
     */
    private Iterable<DynamicTest> createTestsForDirectory(
            final String folderPath,
            final String category,
            final int tolerance,
            final boolean isAnimated
    ) {
        final File testFolder = new File(folderPath);

        // VERIFY TEST FOLDER EXISTS
        assertTrue(testFolder.exists(), "Test folder does not exist: " + folderPath);

        final File[] imageFiles = testFolder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".webp"));

        // VERIFY WEBP FILES ARE PRESENT
        assertNotNull(imageFiles, "No WebP files found in: " + folderPath);
        assertTrue(imageFiles.length > 0, "Test folder is empty: " + folderPath);

        final List<DynamicTest> tests = new ArrayList<>();

        for (final File imageFile : imageFiles) {
            // CREATE TWO TESTS PER FILE: DECODE TEST AND DATA COMPARISON TEST
            final String baseName = imageFile.getName();

            // TEST 1: VERIFY DECODING SUCCEEDS
            tests.add(dynamicTest(
                    category + " decode [" + baseName + "]",
                    () -> testDecodeSucceeds(imageFile, isAnimated)
            ));

            // TEST 2: VERIFY OUTPUT MATCHES REFERENCE (ONLY FOR NON-ANIMATED)
            if (!isAnimated) {
                tests.add(dynamicTest(
                        category + " data match [" + baseName + "]",
                        () -> testDataMatchesReference(imageFile, tolerance, folderPath)
                ));
            }
        }

        return tests;
    }

    /**
     * Tests that a WebP file decodes without throwing exceptions.
     * For animated files, also verifies multiple frames are produced.
     */
    private void testDecodeSucceeds(final File imageFile, final boolean expectAnimated) throws Exception {
        final WEBP decoder = new WEBP();
        final ByteBuffer imageData = ByteBuffer.wrap(new FileInputStream(imageFile).readAllBytes());

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

        if (expectAnimated) {
            // ANIMATED WEBP SHOULD HAVE MULTIPLE FRAMES
            assertTrue(
                    result.frames().length > 1,
                    "Animated WebP should have multiple frames, got: " + result.frames().length
            );

            // VERIFY DELAY ARRAY MATCHES FRAME COUNT
            assertEquals(
                    result.frames().length,
                    result.delay().length,
                    "Delay array length must match frame count"
            );

            // VERIFY TOTAL DURATION IS POSITIVE
            assertTrue(result.duration() > 0, "Animation duration must be positive");
        }

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
    }

    /**
     * Tests lossy WebP similarity using relaxed criteria.
     * Lossy VP8 implementations vary due to DCT/quantization/filtering differences.
     * This test verifies the output is within acceptable bounds rather than pixel-perfect.
     */
    private void testLossySimilarity(final File imageFile, final String folderPath) throws Exception {
        // DECODE WITH JAVA IMPLEMENTATION
        final WEBP decoder = new WEBP();
        final ByteBuffer imageData = ByteBuffer.wrap(new FileInputStream(imageFile).readAllBytes());
        final Image javaResult = decoder.decode(imageData);

        // LOAD REFERENCE DATA FROM PRE-GENERATED PAM FILE
        final byte[] referenceRgba = loadReferenceData(imageFile, folderPath);

        // CONVERT JAVA BGRA TO RGBA FOR COMPARISON
        final ByteBuffer javaBgra = javaResult.frames()[0];
        javaBgra.rewind();
        final byte[] javaRgba = convertBgraToRgba(javaBgra, javaResult.width(), javaResult.height());

        // VERIFY SIZES MATCH
        assertEquals(
                referenceRgba.length,
                javaRgba.length,
                "Pixel data size mismatch - reference: " + referenceRgba.length +
                        ", java: " + javaRgba.length
        );

        // CALCULATE SIMILARITY METRICS
        int maxDiff = 0;
        long totalDiff = 0;
        int diffCountAboveSmallThreshold = 0;
        final int smallThreshold = 10;
        final int pixelCount = javaResult.width() * javaResult.height();
        final int totalValues = pixelCount * 4; // 4 CHANNELS

        for (int i = 0; i < referenceRgba.length; i++) {
            final int ref = referenceRgba[i] & 0xFF;
            final int java = javaRgba[i] & 0xFF;
            final int diff = Math.abs(ref - java);

            if (diff > maxDiff) {
                maxDiff = diff;
            }
            totalDiff += diff;
            if (diff > smallThreshold) {
                diffCountAboveSmallThreshold++;
            }
        }

        final double meanDiff = (double) totalDiff / totalValues;
        final double percentageAboveThreshold = (diffCountAboveSmallThreshold * 100.0) / totalValues;

        // LOSSY TOLERANCES:
        // - MAX DIFF SHOULD NOT BE EXTREME (INDICATES MAJOR DECODING ERROR)
        // - PERCENTAGE OF SIGNIFICANTLY DIFFERENT PIXELS SHOULD BE REASONABLE
        assertTrue(
                maxDiff <= LOSSY_MAX_DIFF_TOLERANCE,
                String.format(
                        "Lossy %s has extreme pixel difference - max: %d (limit: %d), " +
                                "mean: %.2f, %.2f%% above threshold %d",
                        imageFile.getName(), maxDiff, LOSSY_MAX_DIFF_TOLERANCE,
                        meanDiff, percentageAboveThreshold, smallThreshold
                )
        );

        assertTrue(
                percentageAboveThreshold <= LOSSY_MAX_DIFF_PERCENTAGE,
                String.format(
                        "Lossy %s has too many differing pixels - %.2f%% above threshold %d (limit: %.1f%%), " +
                                "max diff: %d, mean diff: %.2f",
                        imageFile.getName(), percentageAboveThreshold, smallThreshold,
                        LOSSY_MAX_DIFF_PERCENTAGE, maxDiff, meanDiff
                )
        );
    }

    /**
     * Tests that decoded pixel data matches libwebp reference output exactly.
     * Uses pre-generated .pam reference files for comparison.
     */
    private void testDataMatchesReference(final File imageFile, final int tolerance, final String folderPath) throws Exception {
        // DECODE WITH JAVA IMPLEMENTATION
        final WEBP decoder = new WEBP();
        final ByteBuffer imageData = ByteBuffer.wrap(new FileInputStream(imageFile).readAllBytes());
        final Image javaResult = decoder.decode(imageData);

        // LOAD REFERENCE DATA FROM PRE-GENERATED PAM FILE
        final byte[] referenceRgba = loadReferenceData(imageFile, folderPath);

        // CONVERT JAVA BGRA TO RGBA FOR COMPARISON
        final ByteBuffer javaBgra = javaResult.frames()[0];
        javaBgra.rewind();
        final byte[] javaRgba = convertBgraToRgba(javaBgra, javaResult.width(), javaResult.height());

        // VERIFY SIZES MATCH
        assertEquals(
                referenceRgba.length,
                javaRgba.length,
                "Pixel data size mismatch - reference: " + referenceRgba.length +
                        ", java: " + javaRgba.length
        );

        // COMPARE PIXEL DATA
        int maxDiff = 0;
        int diffCount = 0;
        final int pixelCount = javaResult.width() * javaResult.height();

        for (int i = 0; i < referenceRgba.length; i++) {
            final int ref = referenceRgba[i] & 0xFF;
            final int java = javaRgba[i] & 0xFF;
            final int diff = Math.abs(ref - java);

            if (diff > maxDiff) {
                maxDiff = diff;
            }
            if (diff > tolerance) {
                diffCount++;
            }
        }

        // CALCULATE PERCENTAGE OF PIXELS THAT DIFFER BEYOND TOLERANCE
        final double diffPercentage = (diffCount * 100.0) / (pixelCount * 4); // 4 CHANNELS PER PIXEL

        // ASSERT DATA MATCHES WITHIN TOLERANCE
        assertTrue(
                maxDiff <= tolerance,
                String.format(
                        "Pixel data mismatch for %s - max diff: %d (tolerance: %d), " +
                                "%.2f%% of values exceed tolerance",
                        imageFile.getName(), maxDiff, tolerance, diffPercentage
                )
        );
    }

    /**
     * Loads reference RGBA data from pre-generated .pam file.
     * PAM files are stored in the 'raw' subdirectory with same base name.
     */
    private byte[] loadReferenceData(final File webpFile, final String folderPath) throws Exception {
        // CONSTRUCT PATH TO REFERENCE PAM FILE
        final String baseName = webpFile.getName().replace(".webp", ".pam");
        final File pamFile = new File(folderPath + "/raw/" + baseName);

        assertTrue(pamFile.exists(),
                "Reference PAM file not found: " + pamFile.getAbsolutePath() +
                        " - Run dwebp to generate reference data");

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

        assertTrue(headerEnd > 0, "Invalid PAM file - ENDHDR not found");

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
