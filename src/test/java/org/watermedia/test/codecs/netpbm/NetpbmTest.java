package org.watermedia.test.codecs.netpbm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.readers.netpbm.NetpbmHeader;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Netpbm reader tests: a header parser smoke test over every fixture, plus a decode test that
 * runs {@link ImageReader#next()} for each binary variant and asserts the first BGRA pixel and
 * that {@link ImageReader#reset()} replays the raster.
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
                try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(Fixtures.readAll(file)))) {
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
