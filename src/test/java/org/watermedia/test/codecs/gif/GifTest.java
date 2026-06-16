package org.watermedia.test.codecs.gif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
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
 * GIF decoder verification driven through {@link CodecsAPI#decodeImage(byte[])}.
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
                final ImageData result = CodecsAPI.decodeImage(Fixtures.readAll(fixture.path));
                assertNotNull(result, "GIF signature not recognized for: " + fixture.name);

                // DIMENSIONS MUST BE POSITIVE
                assertTrue(result.width() > 0, "Width must be positive for " + fixture.name);
                assertTrue(result.height() > 0, "Height must be positive for " + fixture.name);

                // ANIMATED GIF — FRAME COUNT > 1 AND DELAY ARRAY MATCHES
                assertNotNull(result.frames(), "Frames array is null for " + fixture.name);
                assertTrue(result.frames().length > 1,
                        fixture.name + " should be animated, got " + result.frames().length + " frame(s)");
                assertEquals(result.frames().length, result.delay().length,
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
                try (final ImageReader reader = CodecsAPI.decodeImage(ByteBuffer.wrap(Fixtures.readAll(fixture.path)))) {
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
