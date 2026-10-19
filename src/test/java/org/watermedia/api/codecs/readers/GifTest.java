package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
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
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the GIF decoder: the three bundled animated fixtures (frame count, dimensions, BGRA frame size and
 * byte-identical reset() replays), plus hand-built streams for clipping, stray bytes, delays, loop counts,
 * metadata collection and the reader contract.
 */
@DisplayName("GIF decoder")
public class GifTest {

    private record Fixture(String name, Path path) {}

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("1.gif", Fixtures.GIF_DIR.resolve("1.gif")),
            new Fixture("2.gif", Fixtures.GIF_DIR.resolve("2.gif")),
            new Fixture("3.gif", Fixtures.GIF_DIR.resolve("3.gif")));

    @TestFactory
    @DisplayName("Decode shape")
    Iterable<DynamicTest> testGIFFixtures() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture: FIXTURES) {
            tests.add(dynamicTest("GIF decode [" + fixture.name + "]", () -> {
                final ImageData result = CodecsAPI.decode(Fixtures.readAll(fixture.path));
                assertNotNull(result, "GIF signature not recognized for: " + fixture.name);
                assertTrue(result.width() > 0, "Width must be positive for " + fixture.name);
                assertTrue(result.height() > 0, "Height must be positive for " + fixture.name);

                // ANIMATED GIF — FRAME COUNT > 1 AND DELAY ARRAY MATCHES
                assertTrue(result.frames().length > 1, fixture.name + " should be animated, got " + result.frames().length + " frame(s)");
                assertEquals(result.frames().length, result.delays().length, "Delay array length must match frame count for " + fixture.name);
                assertTrue(result.duration() > 0, "Animation duration must be positive for " + fixture.name);

                // EACH FRAME IS BGRA — w*h*4 BYTES
                final int expectedBufferSize = result.width() * result.height() * 4;
                for (int i = 0; i < result.frames().length; i++) {
                    assertEquals(expectedBufferSize, result.frames()[i].capacity(), "Frame " + i + " buffer size mismatch for " + fixture.name);
                }
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("reset() replay")
    Iterable<DynamicTest> testGIFReset() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture: FIXTURES) {
            tests.add(dynamicTest("GIF reset replay [" + fixture.name + "]", () -> {
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(fixture.path)))) {
                    final long[] delays = reader.delays();
                    final ImageMetadata metadata = reader.metadata();
                    final List<byte[]> first = decodeFrameHashes(reader);
                    assertTrue(first.size() > 1, "Fixture should be animated: " + fixture.name);
                    assertEquals(reader.frameCount(), first.size(), "scan() and the decoder disagree on " + fixture.name);

                    // FIRST RESET — REPLAY MUST BE BYTE-IDENTICAL AND METADATA MUST SURVIVE UNCHANGED
                    assertTrue(reader.reset(), "reset() must be supported for " + fixture.name);
                    assertArrayEquals(delays, reader.delays(), "Delays changed after reset for " + fixture.name);
                    assertReplayMatches(first, decodeFrameHashes(reader), fixture.name);

                    // SECOND RESET — reset() MUST BE REPEATABLE
                    assertTrue(reader.reset(), "Second reset() must be supported for " + fixture.name);
                    assertReplayMatches(first, decodeFrameHashes(reader), fixture.name);
                    assertEquals(metadata.comments(), reader.metadata().comments(), "Comments changed after replays for " + fixture.name);
                    assertEquals(metadata.values().keySet(), reader.metadata().values().keySet(), "Metadata changed after replays for " + fixture.name);
                }
            }));
        }
        return tests;
    }

    @Nested
    @DisplayName("Composition")
    class Composition {

        @Test
        @DisplayName("A frame hanging past the logical screen is clipped, not sheared")
        void frameOutsideCanvasIsClipped() throws IOException {
            // 4x4 SCREEN AND ONE 4x4 RED FRAME AT (2,2): ONLY ITS TOP-LEFT QUARTER LIES INSIDE THE SCREEN
            final ImageData image = CodecsAPI.decode(new Gif(4, 4).frame(2, 2, 4, 4).bytes());
            assertEquals(1, image.frames().length);
            final int[] expected = new int[16];
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++) expected[y * 4 + x] = x >= 2 && y >= 2 ? 0xFFFF0000: 0xFF000000;
            assertArrayEquals(expected, pixels(image, 0));
        }

        @Test
        @DisplayName("Disposing a frame that lies entirely outside the canvas leaves the canvas untouched")
        void offCanvasDisposal() throws IOException {
            for (final int disposal: new int[] { 2, 3 }) {
                final ImageData image = CodecsAPI.decode(new Gif(2, 2).frame(0, 0, 2, 2)
                        .gce(0, disposal).frame(5, 0, 1, 1).frame(0, 0, 1, 1).bytes());
                assertEquals(3, image.frames().length, "disposal " + disposal);
                assertArrayEquals(pixels(image, 0), pixels(image, 2), "disposal " + disposal);
            }
        }
    }

    @Nested
    @DisplayName("Stream structure")
    class Structure {

        @Test
        @DisplayName("scan() skips stray bytes exactly like the decoder and counts the same frames")
        void strayBytesAreSkipped() throws IOException {
            final byte[] gif = new Gif(2, 2).frame(0, 0, 2, 2).raw(0x00, 0x99).frame(0, 0, 2, 2).raw(0x42).frame(0, 0, 2, 2).bytes();
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                assertEquals(3, reader.frameCount());
                assertEquals(3, reader.readAll().frames().length);
            }
        }

        @Test
        @DisplayName("A single frame is a still image whatever delay or loop count it declares")
        void singleFrameIsStill() throws IOException {
            final byte[] gif = new Gif(2, 2).netscape(3).gce(50, 0).frame(0, 0, 2, 2).bytes();
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                assertArrayEquals(new long[] { 0L }, reader.delays());
                assertEquals(0L, reader.duration());
                assertEquals(1, reader.repeat());
                assertFalse(reader.variableFrameRate());
                reader.next();
                assertEquals(0L, reader.delay());
            }
            final ImageData image = CodecsAPI.decode(gif);
            assertArrayEquals(new long[] { 0L }, image.delays());
            assertEquals(1, image.repeat());
        }

        @Test
        @DisplayName("Delays come only from graphic control extensions, apply to the next frame alone and follow the browser floor")
        void delaysOnlyFromGce() throws IOException {
            // NO GCE, A ZERO DELAY AND A 1 CS DELAY ALL PLAY AT 100 MS LIKE EVERY BROWSER RENDERS THEM; 2 CS CLEARS THE FLOOR
            final byte[] gif = new Gif(1, 1).gce(30, 0).frame(0, 0, 1, 1).frame(0, 0, 1, 1).gce(0, 0).frame(0, 0, 1, 1)
                    .gce(1, 0).frame(0, 0, 1, 1).gce(2, 0).frame(0, 0, 1, 1).bytes();
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                assertArrayEquals(new long[] { 300L, 100L, 100L, 100L, 20L }, reader.delays());
                assertTrue(reader.variableFrameRate());
                assertArrayEquals(reader.delays(), reader.readAll().delays(), "scan() and the decoder disagree on delays");
            }
        }

        @Test
        @DisplayName("NETSCAPE loop count N plays N + 1 times, 0 loops forever, and the raw value reaches the metadata")
        void loopCount() throws IOException {
            for (final int[] loop: new int[][] { { 0, ImageData.REPEAT_FOREVER }, { 1, 2 }, { 65_535, 65_536 } }) {
                final byte[] gif = new Gif(1, 1).netscape(loop[0]).frame(0, 0, 1, 1).frame(0, 0, 1, 1).bytes();
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                    assertEquals(loop[1], reader.repeat(), "loop count " + loop[0]);
                    assertEquals(loop[0], reader.metadata().value(CodecsAPI.METAKEY_LOOP_COUNT));
                    assertEquals(loop[1], reader.readAll().repeat(), "loop count " + loop[0]);
                }
            }
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(new Gif(1, 1).frame(0, 0, 1, 1).frame(0, 0, 1, 1).bytes()))) {
                assertEquals(1, reader.repeat(), "without a looping extension the animation plays once");
                assertNull(reader.metadata().value(CodecsAPI.METAKEY_LOOP_COUNT));
            }
        }

        @Test
        @DisplayName("next() past the last frame throws NoSuchElementException and a closed reader IllegalStateException")
        void endAndClose() throws IOException {
            final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(new Gif(1, 1).frame(0, 0, 1, 1).bytes()));
            reader.next();
            assertFalse(reader.hasNext());
            assertThrows(NoSuchElementException.class, reader::next);
            reader.close();
            assertThrows(IllegalStateException.class, reader::hasNext);
            assertThrows(IllegalStateException.class, reader::next);
            assertThrows(IllegalStateException.class, reader::reset);
        }
    }

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("Every comment and application extension is kept once, wherever it sits and across replays")
        void keptOnce() throws IOException {
            final byte[] early = { 1, 2, 3 };
            final byte[] late = new byte[300];
            late[299] = 9;
            final byte[] gif = new Gif(1, 1).comment("first").frame(0, 0, 1, 1).comment("second")
                    .application("WATERMDA1.0", early).frame(0, 0, 1, 1).application("WATERMDA1.0", late).bytes();
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                for (int pass = 0; pass < 3; pass++) {
                    while (reader.hasNext()) reader.next();
                    assertTrue(reader.reset());
                }
                final ImageMetadata metadata = reader.metadata();
                assertEquals(List.of("first", "second"), metadata.comments());
                final Map<?, ?> extensions = assertInstanceOf(Map.class, metadata.value(CodecsAPI.GIF_METAKEY_APPLICATION_EXTENSION));
                final List<?> payloads = assertInstanceOf(List.class, extensions.get("WATERMDA1.0"));
                assertEquals(2, payloads.size());
                assertArrayEquals(early, (byte[]) payloads.get(0));
                assertArrayEquals(late, (byte[]) payloads.get(1), "a payload split over sub-blocks is joined");
            }
        }

        @Test
        @DisplayName("Records past the cap are skipped instead of failing the decode")
        void recordCap() throws IOException {
            final Gif gif = new Gif(1, 1);
            for (int i = 0; i < 1_100; i++) gif.comment("c" + i);
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif.frame(0, 0, 1, 1).bytes()))) {
                assertEquals(1_024, reader.metadata().comments().size());
                assertEquals("c0", reader.metadata().comments().get(0));
                assertEquals(1, reader.readAll().frames().length);
            }
        }

        @Test
        @DisplayName("An application extension without its 11-byte header names nothing and is skipped")
        void headerlessApplicationExtension() throws IOException {
            final byte[] gif = new Gif(1, 1).raw(0x21, 0xFF, 0).frame(0, 0, 1, 1).bytes();
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                assertNull(reader.metadata().value(CodecsAPI.GIF_METAKEY_APPLICATION_EXTENSION));
                assertEquals(1, reader.readAll().frames().length);
            }
        }
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
            assertArrayEquals(expected.get(i), replay.get(i), "Frame " + i + " not byte-identical after reset for " + name);
        }
    }

    private static int[] pixels(final ImageData image, final int frame) {
        final int[] pixels = new int[image.width() * image.height()];
        image.frames()[frame].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        return pixels;
    }

    // BUILDS GIF89a STREAMS WITH A TWO-COLOUR GLOBAL PALETTE (BLACK, RED) WHOSE FRAMES ARE ALL INDEX 1
    private static final class Gif {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Gif(final int width, final int height) {
            this.out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
            this.raw(width & 0xFF, width >> 8, height & 0xFF, height >> 8, 0x80, 0, 0);
            this.raw(0, 0, 0, 255, 0, 0);
        }

        Gif raw(final int... bytes) {
            for (final int b: bytes) this.out.write(b);
            return this;
        }

        Gif gce(final int delayCs, final int disposal) {
            return this.raw(0x21, 0xF9, 4, disposal << 2, delayCs & 0xFF, delayCs >> 8, 0, 0);
        }

        Gif netscape(final int loops) {
            return this.application("NETSCAPE2.0", new byte[] { 1, (byte) loops, (byte) (loops >> 8) });
        }

        Gif comment(final String text) {
            this.raw(0x21, 0xFE);
            this.blocks(text.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        Gif application(final String identifier, final byte[] payload) {
            this.raw(0x21, 0xFF, identifier.length());
            this.out.writeBytes(identifier.getBytes(StandardCharsets.ISO_8859_1));
            this.blocks(payload);
            return this;
        }

        // A CLEAR CODE BEFORE EVERY 3-BIT LITERAL KEEPS THE LZW CODE SIZE FIXED; END OF INFORMATION CLOSES THE DATA
        Gif frame(final int left, final int top, final int width, final int height) {
            this.raw(0x2C, left & 0xFF, left >> 8, top & 0xFF, top >> 8, width & 0xFF, width >> 8, height & 0xFF, height >> 8, 0, 2);
            final ByteArrayOutputStream lzw = new ByteArrayOutputStream();
            final int codes = width * height * 2 + 1;
            long acc = 0L;
            int bits = 0;
            for (int i = 0; i < codes; i++) {
                acc |= (long) (i == codes - 1 ? 5 : (i & 1) == 0 ? 4 : 1) << bits;
                bits += 3;
                while (bits >= 8) {
                    lzw.write((int) acc & 0xFF);
                    acc >>>= 8;
                    bits -= 8;
                }
            }
            if (bits > 0) lzw.write((int) acc & 0xFF);
            this.blocks(lzw.toByteArray());
            return this;
        }

        byte[] bytes() {
            this.out.write(0x3B);
            return this.out.toByteArray();
        }

        // SPLITS A PAYLOAD INTO SUB-BLOCKS OF AT MOST 255 BYTES, THEN THE TERMINATOR
        private void blocks(final byte[] payload) {
            for (int off = 0; off < payload.length; off += 255) {
                final int size = Math.min(255, payload.length - off);
                this.out.write(size);
                this.out.write(payload, off, size);
            }
            this.out.write(0);
        }
    }
}
