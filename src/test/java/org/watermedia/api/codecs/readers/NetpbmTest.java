package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.readers.netpbm.NetpbmHeader;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the Netpbm reader: a header parser smoke test over every fixture, a decode test that runs
 * {@link ImageReader#next()} for each binary variant and asserts the first BGRA pixel and that
 * {@link ImageReader#reset()} replays the raster, hand-written plain (ASCII), 16-bit and PAM alpha
 * rasters checked pixel by pixel, and the split between unsupported and malformed headers.
 */
@DisplayName("Netpbm reader")
public class NetpbmTest {

    // FIRST BGRA PIXEL (0xAARRGGBB) EXPECTED PER FIXTURE — DERIVED BY HAND FROM EACH RASTER'S LEADING SAMPLES
    private static final Map<String, Integer> FIRST_PIXEL = Map.of(
            "test.pbm", 0xFF000000,       // P4: LEADING BIT 1 -> BLACK
            "test.pgm", 0xFF2A2A2A,       // P5 MAXVAL 255: GRAY 0x2A
            "test.ppm", 0xFF372531,       // P6 MAXVAL 255: R=0x37 G=0x25 B=0x31
            "bw.pam", 0xFF000000,         // P7 BLACKANDWHITE: SAMPLE 0 -> BLACK
            "grayscale.pam", 0xFF2A2A2A,  // P7 GRAYSCALE: GRAY 0x2A
            "rgb.pam", 0xFF372531,        // P7 RGB DEPTH 3 MAXVAL 65535: RESCALES TO 0x372531
            "rgba.pam", 0xFF372531        // P7 RGB_ALPHA DEPTH 4 MAXVAL 65535: OPAQUE 0x372531
    );

    @TestFactory
    @DisplayName("Parse header")
    Iterable<DynamicTest> testNetpbmHeaders() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path file: netpbmFixtures()) {
            tests.add(DynamicTest.dynamicTest("Parse " + file.getFileName(), () -> {
                try {
                    final ByteBuffer buffer = ByteBuffer.wrap(Fixtures.readAll(file));
                    final NetpbmHeader header = NetpbmHeader.parse(buffer);
                    assertTrue(header.version() >= 4 && header.version() <= 7,
                            "Invalid version for file: " + file.getFileName());
                    assertTrue(header.width() > 0, "Width should be greater than 0 for file: " + file.getFileName());
                    assertTrue(header.height() > 0, "Height should be greater than 0 for file: " + file.getFileName());
                } catch (final Exception e) {
                    fail("Failed to parse file: " + file.getFileName() + " due to exception: " + e.getMessage());
                }
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Decode first frame and reset")
    Iterable<DynamicTest> testNetpbmDecode() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path file: netpbmFixtures()) {
            final String name = file.getFileName().toString();
            final Integer expected = FIRST_PIXEL.get(name);
            if (expected == null) continue;
            tests.add(DynamicTest.dynamicTest("Decode " + name, () -> {
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(file)))) {
                    assertEquals(600, reader.width(), name);
                    assertEquals(600, reader.height(), name);

                    final ByteBuffer frame = reader.next();
                    assertEquals(600 * 600 * 4, frame.remaining(), name + " frame byte count");
                    assertEquals(expected.intValue(), frame.getInt(0), () ->
                            String.format("%s first pixel: expected 0x%08X but was 0x%08X", name, expected, frame.getInt(0)));

                    // reset() MUST REPLAY THE RASTER FROM THE SNAPSHOTTED START, PRODUCING THE SAME OUTPUT
                    assertTrue(reader.reset(), name + " should support reset");
                    assertTrue(reader.hasNext(), name + " should expose a frame after reset");
                    assertEquals(expected.intValue(), reader.next().getInt(0), name + " first pixel after reset");
                }
            }));
        }
        return tests;
    }

    @Nested
    @DisplayName("Plain and alpha variants")
    class PlainAndAlpha {

        @Test
        @DisplayName("P1 bits need no separator and accept comments")
        void plainBitmap() throws IOException {
            final ImageData image = decode("P1\n# two rows\n3 2\n101\n0 1 0\n");
            assertEquals(3, image.width());
            assertEquals(2, image.height());
            assertArrayEquals(new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF }, pixels(image));
        }

        @Test
        @DisplayName("P2 samples scale by maxval")
        void plainGraymap() throws IOException {
            assertArrayEquals(new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF777777, 0xFF333333 }, pixels(decode("P2 2 2 15\n0 15\n7 3\n")));
        }

        @Test
        @DisplayName("P3 triplets become opaque BGRA, even without a trailing newline")
        void plainPixmap() throws IOException {
            assertArrayEquals(new int[] { 0xFFFF0000, 0xFF0000FF }, pixels(decode("P3 2 1 255 255 0 0 0 0 255")));
        }

        @Test
        @DisplayName("PAM GRAYSCALE_ALPHA keeps its alpha")
        void grayscaleAlpha() throws IOException {
            final ImageData image = decode("P7\nWIDTH 2\nHEIGHT 1\nDEPTH 2\nMAXVAL 255\nTUPLTYPE GRAYSCALE_ALPHA\nENDHDR\n",
                    new byte[] { 0x2A, (byte) 0x80, (byte) 0xFF, 0x00 });
            assertArrayEquals(new int[] { 0x802A2A2A, 0x00FFFFFF }, pixels(image));
        }

        @Test
        @DisplayName("PAM BLACKANDWHITE_ALPHA keeps its alpha")
        void blackAndWhiteAlpha() throws IOException {
            final ImageData image = decode("P7\nWIDTH 2\nHEIGHT 1\nDEPTH 2\nMAXVAL 1\nTUPLTYPE BLACKANDWHITE_ALPHA\nENDHDR\n",
                    new byte[] { 1, 1, 0, 0 });
            assertArrayEquals(new int[] { 0xFFFFFFFF, 0x00000000 }, pixels(image));
        }

        @Test
        @DisplayName("16-bit samples are read big-endian and rescaled")
        void sixteenBit() throws IOException {
            final ImageData image = decode("P5 2 1 65535\n", new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0x80, 0x00 });
            assertArrayEquals(new int[] { 0xFFFFFFFF, 0xFF7F7F7F }, pixels(image));
        }

        private static int[] pixels(final ImageData image) {
            final int[] pixels = new int[image.width() * image.height()];
            image.frames()[0].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
            return pixels;
        }
    }

    @Nested
    @DisplayName("Refusals and contract")
    class Refusals {

        @Test
        @DisplayName("A PAM tuple type outside the six known ones is unsupported at header time")
        void unknownTupleType() {
            for (final String tuple: List.of("TUPLTYPE CMYK\n", "")) {
                final byte[] pam = bytes("P7\nWIDTH 1\nHEIGHT 1\nDEPTH 4\nMAXVAL 255\n" + tuple + "ENDHDR\n", new byte[4]);
                final UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(pam)));
                assertTrue(e.getMessage().contains(tuple.isEmpty() ? "(none)" : "CMYK"), e.getMessage());
            }
        }

        @Test
        @DisplayName("A black-and-white PAM with a MAXVAL other than 1 is malformed")
        void blackAndWhiteMaxVal() {
            final byte[] pam = bytes("P7\nWIDTH 1\nHEIGHT 1\nDEPTH 1\nMAXVAL 255\nTUPLTYPE BLACKANDWHITE\nENDHDR\n", new byte[1]);
            final CodecException e = assertThrows(CodecException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(pam)));
            assertFalse(e instanceof UnsupportedFormatException, e.getMessage());
        }

        @Test
        @DisplayName("A version outside P1..P7 is an argument error")
        void invalidVersion() {
            assertThrows(IllegalArgumentException.class, () -> new NetpbmReader(ByteBuffer.wrap(bytes(" 1 1 255\n", new byte[1])), 8));
        }

        @Test
        @DisplayName("next() past the frame throws NoSuchElementException and a closed reader IllegalStateException")
        void endAndClose() throws IOException {
            final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes("P5 1 1 255\n", new byte[] { 7 })));
            reader.next();
            assertFalse(reader.hasNext());
            assertThrows(NoSuchElementException.class, reader::next);
            reader.close();
            assertThrows(IllegalStateException.class, reader::hasNext);
            assertThrows(IllegalStateException.class, reader::reset);
        }
    }

    private static ImageData decode(final String text) throws IOException {
        return decode(text, new byte[0]);
    }

    private static ImageData decode(final String header, final byte[] raster) throws IOException {
        return CodecsAPI.decode(bytes(header, raster));
    }

    private static byte[] bytes(final String header, final byte[] raster) {
        final byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(head.length + raster.length).put(head).put(raster).array();
    }

    private static List<Path> netpbmFixtures() {
        try (final Stream<Path> entries = Files.list(Fixtures.NETPBM_DIR)) {
            return entries
                    .filter(p -> {
                        final String n = p.getFileName().toString();
                        return n.endsWith(".pbm") || n.endsWith(".pgm") || n.endsWith(".ppm") || n.endsWith(".pam");
                    })
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate netpbm fixtures", e);
        }
    }
}
