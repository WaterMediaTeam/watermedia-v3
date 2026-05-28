package org.watermedia.test.codecs.gif;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.test.support.Fixtures;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
public class GifTest {

    private record Fixture(String name, Path path) {}

    @TestFactory
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
}
