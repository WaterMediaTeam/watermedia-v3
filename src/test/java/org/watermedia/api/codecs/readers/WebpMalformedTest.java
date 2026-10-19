package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.readers.webp.FourCC;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies crafted and corrupted WebP files: every malformed input surfaces as a clean
 * {@link CodecException} (or completes harmlessly) — never as an unchecked exception, an
 * {@link OutOfMemoryError} or a hang — while valid features the decoder lacks surface as
 * {@link UnsupportedFormatException}.
 */
@DisplayName("WebP malformed inputs")
public class WebpMalformedTest {

    // A VP8X CANVAS LARGER THAN THE EMBEDDED VP8 BITSTREAM FAILS AS A CODEC ERROR, NEVER AS AN INDEX OR ARGUMENT FAULT
    @Test
    @DisplayName("VP8X canvas mismatching VP8 bitstream dims is rejected")
    void vp8CanvasMismatch() {
        final byte[] stream = fixtureChunks(Fixtures.WEBP_LOSSY_DIR.resolve("1.webp"));
        // REAL DIMS LIVE IN THE VP8 KEY FRAME HEADER: BODY BYTES 6..9 (14-BIT EACH)
        final int w = ((stream[8 + 6] & 0xFF) | ((stream[8 + 7] & 0xFF) << 8)) & 0x3FFF;
        final int h = ((stream[8 + 8] & 0xFF) | ((stream[8 + 9] & 0xFF) << 8)) & 0x3FFF;
        final byte[] forged = concat(chunk(FourCC.VP8X, vp8xBody(0x00, w + 1, h + 1)), stream);

        final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(forged)));
        assertThrows(CodecException.class, reader::next, "Mismatched canvas must fail cleanly");
    }

    // SAME MISMATCH ON THE LOSSLESS PATH: THE VP8L HEADER DIMS MUST MATCH THE CONTAINER
    @Test
    @DisplayName("VP8X canvas mismatching VP8L bitstream dims is rejected")
    void vp8lCanvasMismatch() {
        final byte[] stream = fixtureChunks(Fixtures.WEBP_LOSSLESS);
        // REAL DIMS LIVE IN THE VP8L HEADER: BODY BYTES 1..4 (14-BIT EACH, MINUS-ONE CODED)
        final int bits = (stream[8 + 1] & 0xFF) | ((stream[8 + 2] & 0xFF) << 8)
                | ((stream[8 + 3] & 0xFF) << 16) | ((stream[8 + 4] & 0xFF) << 24);
        final int w = (bits & 0x3FFF) + 1;
        final int h = ((bits >> 14) & 0x3FFF) + 1;
        final byte[] forged = concat(chunk(FourCC.VP8X, vp8xBody(0x00, w + 1, h + 1)), stream);

        final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(forged)));
        assertThrows(CodecException.class, reader::next, "Mismatched canvas must fail cleanly");
    }

    // A NEGATIVE SUB-CHUNK SIZE INSIDE ANMF CAN STOP FORWARD PROGRESS.
    @Test
    @Timeout(10)
    @DisplayName("ANMF with negative sub-chunk size neither hangs nor crashes")
    void anmfNegativeSubChunkSize() {
        final ByteBuffer anmf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        anmf.put(anmfHeader(0, 0, 16, 16));
        anmf.putInt(FourCC.VP8);
        anmf.putInt(-8); // NEGATIVE SIZE: BODY + PADDEDSIZE(-8) == OFF.
        final byte[] forged = concat(
                chunk(FourCC.VP8X, vp8xBody(0x02, 16, 16)),
                chunk(FourCC.ANIM, new byte[6]),
                chunk(FourCC.ANMF, anmf.array()));

        final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(forged)));
        assertThrows(CodecException.class, reader::next, "Negative sub-chunk size must fail cleanly");
    }

    // AN ANMF FRAME EXTENDING PAST THE CANVAS IS REJECTED, NOT DECODED INTO AN OFF-CANVAS BUFFER
    @Test
    @DisplayName("ANMF frame outside the canvas is rejected")
    void anmfFrameOutsideCanvas() {
        final byte[] forged = concat(
                chunk(FourCC.VP8X, vp8xBody(0x02, 16, 16)),
                chunk(FourCC.ANIM, new byte[6]),
                chunk(FourCC.ANMF, anmfHeader(0, 0, 32, 32)));

        final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(forged)));
        assertThrows(CodecException.class, reader::next, "Out-of-canvas frame must fail cleanly");
    }

    // 16Kx16K PASSES THE PER-AXIS CAP BUT WOULD FORCE ~2 GiB OF CANVAS + OUTPUT ALLOCATIONS
    @Test
    @DisplayName("Canvas above the total-pixel cap is rejected at construction")
    void canvasPixelBomb() {
        final byte[] forged = chunk(FourCC.VP8X, vp8xBody(0x02, 16_384, 16_384));
        assertThrows(CodecException.class, () -> new WebPReader(ByteBuffer.wrap(forged)),
                "Pixel bomb must fail before allocating");
    }

    // AN ODD-SIZED VP8X TRUNCATED AT ITS END MUST NOT SEEK PAST THE BUFFER LIMIT.
    @Test
    @DisplayName("Truncated odd-sized VP8X does not crash the constructor")
    void truncatedOddVp8x() throws IOException {
        final ByteBuffer body = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        body.put(vp8xBody(0x02, 1, 1)).put((byte) 0); // 11 BYTES: ODD SIZE, NO PADDING FOLLOWS
        final ByteBuffer forged = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        forged.putInt(FourCC.VP8X).putInt(11).put(body.array());
        forged.flip();

        final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(forged));
        assertFalse(reader.hasNext(), "Nothing to decode in a header-only file");
    }

    @Test
    @DisplayName("A VP8 interframe is unsupported, not malformed")
    void vp8Interframe() {
        // FRAME TAG BIT 0 SET MARKS AN INTERFRAME; THE REST IS A WELL-FORMED 1x1 KEY FRAME HEADER
        final byte[] body = { 0x01, 0, 0, (byte) 0x9D, 0x01, 0x2A, 1, 0, 1, 0 };
        assertThrows(UnsupportedFormatException.class, () -> new WebPReader(ByteBuffer.wrap(chunk(FourCC.VP8, body))));
    }

    @Test
    @DisplayName("A VP8L bitstream of an unknown version is unsupported, not malformed")
    void vp8lVersion() {
        // THE TOP THREE BITS OF THE 32-BIT SIZE FIELD CARRY THE VERSION: 0x20 IN ITS LAST BYTE IS VERSION 1
        final byte[] body = { 0x2F, 0, 0, 0, 0x20 };
        assertThrows(UnsupportedFormatException.class, () -> new WebPReader(ByteBuffer.wrap(chunk(FourCC.VP8L, body))));
        assertThrows(CodecException.class, () -> new WebPReader(ByteBuffer.wrap(chunk(FourCC.VP8L, new byte[] { 0x2E, 0, 0, 0, 0 }))));
    }

    @Test
    @DisplayName("Frames are capped per pass and reset() starts a new pass")
    void frameCap() throws IOException {
        // A 1x1 VP8L FRAME: NO TRANSFORM, NO CACHE, ONE GROUP OF FIVE ONE-SYMBOL CODES, SO THE PIXEL COSTS NO BITS
        final byte[] vp8l = { 0x2F, 0, 0, 0, 0, (byte) 0x88, (byte) 0x88, 0x08 };
        final ByteBuffer anmf = ByteBuffer.allocate(16 + 8 + vp8l.length).order(ByteOrder.LITTLE_ENDIAN);
        anmf.put(anmfHeader(0, 0, 1, 1)).putInt(FourCC.VP8L).putInt(vp8l.length).put(vp8l);
        final byte[] frame = chunk(FourCC.ANMF, anmf.array());
        try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(animation(frame, 4_096)))) {
            assertEquals(4_096, reader.frameCount());
            assertEquals(4_096, reader.readAll().frames().length);
            assertTrue(reader.reset());
            assertEquals(4_096, reader.readAll().frames().length);
        }
        try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(animation(frame, 4_097)))) {
            assertTrue(assertThrows(CodecException.class, reader::readAll).getMessage().contains("frame count"));
        }
    }

    // ----- FORGING HELPERS -----

    // A 1x1 ANIMATION REPEATING ONE ANMF CHUNK
    private static byte[] animation(final byte[] frame, final int count) {
        final byte[][] parts = new byte[count + 2][];
        parts[0] = chunk(FourCC.VP8X, vp8xBody(0x02, 1, 1));
        parts[1] = chunk(FourCC.ANIM, new byte[6]);
        Arrays.fill(parts, 2, parts.length, frame);
        return concat(parts);
    }

    // CHUNK STREAM OF A REAL FIXTURE (EVERYTHING PAST THE 12-BYTE RIFF????WEBP PREAMBLE)
    private static byte[] fixtureChunks(final Path path) {
        final byte[] file = Fixtures.readAll(path);
        return Arrays.copyOfRange(file, 12, file.length);
    }

    private static byte[] chunk(final int fourCC, final byte[] body) {
        final int padded = (body.length + 1) & ~1;
        final ByteBuffer out = ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(fourCC).putInt(body.length).put(body);
        return out.array();
    }

    private static byte[] vp8xBody(final int flags, final int width, final int height) {
        final ByteBuffer body = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        body.put((byte) flags).put((byte) 0).put((byte) 0).put((byte) 0);
        putLE24(body, width - 1);
        putLE24(body, height - 1);
        return body.array();
    }

    private static byte[] anmfHeader(final int x, final int y, final int width, final int height) {
        final ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        putLE24(hdr, x);
        putLE24(hdr, y);
        putLE24(hdr, width - 1);
        putLE24(hdr, height - 1);
        putLE24(hdr, 10); // DURATION MS
        hdr.put((byte) 0); // FLAGS: BLEND, NO DISPOSE
        return hdr.array();
    }

    private static void putLE24(final ByteBuffer out, final int value) {
        out.put((byte) value).put((byte) (value >> 8)).put((byte) (value >> 16));
    }

    private static byte[] concat(final byte[]... parts) {
        int total = 0;
        for (final byte[] part: parts) total += part.length;
        final byte[] out = new byte[total];
        int off = 0;
        for (final byte[] part: parts) {
            System.arraycopy(part, 0, out, off, part.length);
            off += part.length;
        }
        return out;
    }
}
