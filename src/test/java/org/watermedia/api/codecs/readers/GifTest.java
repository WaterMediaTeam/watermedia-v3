package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.test.support.Fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * GIF decoder verification driven through {@link CodecsAPI#decode(byte[])}.
 *
 * <p>For each of the three bundled GIF fixtures (the project ships {@code 1.gif},
 * {@code 2.gif} and {@code 3.gif} all of which are animated) the factory emits one
 * test set covering frame count, dimensions, the animated flag and BGRA frame buffer
 * size. dwebp-style PAM references are not generated for GIF so no pixel comparison
 * is attempted here.
 */
@DisplayName("GIF decoder")
public class GifTest {

    private record Fixture(String name, Path path) {}

    @TestFactory
    @DisplayName("Decode shape")
    Iterable<DynamicTest> testGIFFixtures() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture: List.of(
                new Fixture("1.gif", Fixtures.GIF_DIR.resolve("1.gif")),
                new Fixture("2.gif", Fixtures.GIF_DIR.resolve("2.gif")),
                new Fixture("3.gif", Fixtures.GIF_DIR.resolve("3.gif"))
        )) {
            tests.add(dynamicTest("GIF decode [" + fixture.name + "]", () -> {
                final ImageData result = CodecsAPI.decode(Fixtures.readAll(fixture.path));
                assertNotNull(result, "GIF signature not recognized for: " + fixture.name);

                // DIMENSIONS MUST BE POSITIVE
                assertTrue(result.width() > 0, "Width must be positive for " + fixture.name);
                assertTrue(result.height() > 0, "Height must be positive for " + fixture.name);

                // ANIMATED GIF — FRAME COUNT > 1 AND DELAY ARRAY MATCHES
                assertNotNull(result.frames(), "Frames array is null for " + fixture.name);
                assertTrue(result.frames().length > 1,
                        fixture.name + " should be animated, got " + result.frames().length + " frame(s)");
                assertEquals(result.frames().length, result.delays().length,
                        "Delay array length must match frame count for " + fixture.name);
                assertTrue(result.duration() > 0,
                        "Animation duration must be positive for " + fixture.name);

                // EACH FRAME IS BGRA — w*h*4 BYTES
                final int expectedBufferSize = result.width() * result.height() * 4;
                for (int i = 0; i < result.frames().length; i++) {
                    final ByteBuffer frame = result.frames()[i];
                    assertNotNull(frame, "Frame " + i + " is null for " + fixture.name);
                    assertEquals(expectedBufferSize, frame.capacity(),
                            "Frame " + i + " buffer size mismatch for " + fixture.name);
                }
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("reset() replay")
    Iterable<DynamicTest> testGIFReset() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture: List.of(
                new Fixture("1.gif", Fixtures.GIF_DIR.resolve("1.gif")),
                new Fixture("2.gif", Fixtures.GIF_DIR.resolve("2.gif")),
                new Fixture("3.gif", Fixtures.GIF_DIR.resolve("3.gif"))
        )) {
            tests.add(dynamicTest("GIF reset replay [" + fixture.name + "]", () -> {
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(fixture.path)))) {
                    final long[] delays = reader.delays().clone();
                    final List<byte[]> first = decodeFrameHashes(reader);
                    assertTrue(first.size() > 1, "Fixture should be animated: " + fixture.name);

                    // FIRST RESET — REPLAY MUST BE BYTE-IDENTICAL AND METADATA MUST SURVIVE
                    assertTrue(reader.reset(), "reset() must be supported for " + fixture.name);
                    assertArrayEquals(delays, reader.delays(), "Delays changed after reset for " + fixture.name);
                    assertReplayMatches(first, decodeFrameHashes(reader), fixture.name);

                    // SECOND RESET — reset() MUST BE REPEATABLE
                    assertTrue(reader.reset(), "Second reset() must be supported for " + fixture.name);
                    assertReplayMatches(first, decodeFrameHashes(reader), fixture.name);
                }
            }));
        }
        return tests;
    }

    @Test
    @DisplayName("A frame hanging past the logical screen is clipped, not sheared")
    void frameOutsideCanvasIsClipped() throws IOException {
        // 4x4 SCREEN WITH A TWO-COLOUR PALETTE (BLACK, RED) AND ONE 4x4 RED FRAME PLACED AT (2,2), SO ONLY ITS
        // TOP-LEFT QUARTER LIES INSIDE THE SCREEN. THE LZW STREAM EMITS A CLEAR CODE BEFORE EVERY LITERAL SO
        // THE CODE SIZE NEVER GROWS: 16 PIXELS OF INDEX 1 AS 3-BIT CODES, THEN END OF INFORMATION
        final ByteArrayOutputStream gif = new ByteArrayOutputStream();
        gif.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        gif.writeBytes(new byte[] { 4, 0, 4, 0, (byte) 0x80, 0, 0 });
        gif.writeBytes(new byte[] { 0, 0, 0, (byte) 255, 0, 0 });
        gif.writeBytes(new byte[] { 0x2C, 2, 0, 2, 0, 4, 0, 4, 0, 0 });
        gif.write(2);
        final ByteArrayOutputStream lzw = new ByteArrayOutputStream();
        long acc = 0L;
        int bits = 0;
        for (int i = 0; i <= 32; i++) {
            acc |= (long) (i == 32 ? 5 : (i & 1) == 0 ? 4 : 1) << bits;
            bits += 3;
            while (bits >= 8) { lzw.write((int) acc & 0xFF); acc >>>= 8; bits -= 8; }
        }
        if (bits > 0) lzw.write((int) acc & 0xFF);
        gif.write(lzw.size());
        gif.writeBytes(lzw.toByteArray());
        gif.write(0);
        gif.write(0x3B);

        final ImageData image = CodecsAPI.decode(gif.toByteArray());
        assertEquals(1, image.frames().length);
        final int[] pixels = new int[16];
        image.frames()[0].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        final int[] expected = new int[16];
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) expected[y * 4 + x] = x >= 2 && y >= 2 ? 0xFFFF0000: 0xFF000000;
        assertArrayEquals(expected, pixels);
    }

    // HASH FRAMES INSTEAD OF COPYING THEM — 1080P FIXTURES TIMES THREE DECODE PASSES WOULD BLOW
    // THE 512MB DIRECT-MEMORY BUDGET OF THE TEST JVM, AND SHA-256 EQUALITY IS BYTE-IDENTITY
    private static List<byte[]> decodeFrameHashes(final ImageReader reader) throws IOException, NoSuchAlgorithmException {
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        final List<byte[]> hashes = new ArrayList<>();
        while (reader.hasNext()) {
            sha.update(reader.next().duplicate());
            hashes.add(sha.digest());
        }
        return hashes;
    }

    private static void assertReplayMatches(final List<byte[]> expected, final List<byte[]> replay, final String name) {
        assertEquals(expected.size(), replay.size(), "Frame count changed after reset for " + name);
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), replay.get(i),
                    "Frame " + i + " not byte-identical after reset for " + name);
        }
    }
}
