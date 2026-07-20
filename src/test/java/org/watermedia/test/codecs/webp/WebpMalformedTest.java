package org.watermedia.test.codecs.webp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.readers.WEBPReader;
import org.watermedia.api.codecs.readers.webp.riff.RiffChunk;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression tests for crafted/corrupted WebP files. Every malformed input must surface as a
 * clean {@link XCodecException} (or complete harmlessly) — never as an unchecked exception,
 * an {@link OutOfMemoryError} or a hang.
 */
@DisplayName("WebP malformed inputs")
public class WebpMalformedTest {

    // VP8X CANVAS LARGER THAN THE EMBEDDED VP8 BITSTREAM PREVIOUSLY OVERRAN THE YUV PLANE COPY
    // (IndexOutOfBoundsException) OR THE BGRA LIMIT (IllegalArgumentException)
    @Test
    @DisplayName("VP8X canvas mismatching VP8 bitstream dims is rejected")
    void vp8CanvasMismatch() {
        final byte[] stream = fixtureChunks(Fixtures.WEBP_LOSSY_DIR.resolve("1.webp"));
        // REAL DIMS LIVE IN THE VP8 KEY FRAME HEADER: BODY BYTES 6..9 (14-BIT EACH)
        final int w = ((stream[8 + 6] & 0xFF) | ((stream[8 + 7] & 0xFF) << 8)) & 0x3FFF;
        final int h = ((stream[8 + 8] & 0xFF) | ((stream[8 + 9] & 0xFF) << 8)) & 0x3FFF;
        final byte[] forged = concat(chunk(RiffChunk.VP8X, vp8xBody(0x00, w + 1, h + 1)), stream);

        final WEBPReader reader = assertDoesNotThrow(() -> new WEBPReader(ByteBuffer.wrap(forged)));
        assertThrows(XCodecException.class, reader::next, "Mismatched canvas must fail cleanly");
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
        final byte[] forged = concat(chunk(RiffChunk.VP8X, vp8xBody(0x00, w + 1, h + 1)), stream);

        final WEBPReader reader = assertDoesNotThrow(() -> new WEBPReader(ByteBuffer.wrap(forged)));
        assertThrows(XCodecException.class, reader::next, "Mismatched canvas must fail cleanly");
    }

    // A NEGATIVE SUB-CHUNK SIZE INSIDE ANMF PREVIOUSLY LOOPED FOREVER (off NEVER ADVANCED)
    @Test
    @Timeout(10)
    @DisplayName("ANMF with negative sub-chunk size neither hangs nor crashes")
    void anmfNegativeSubChunkSize() {
        final ByteBuffer anmf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        anmf.put(anmfHeader(0, 0, 16, 16));
        anmf.putInt(RiffChunk.VP8);
        anmf.putInt(-8); // NEGATIVE SIZE: body + paddedSize(-8) == off, THE OLD INFINITE LOOP
        final byte[] forged = concat(
                chunk(RiffChunk.VP8X, vp8xBody(0x02, 16, 16)),
                chunk(RiffChunk.ANIM, new byte[6]),
                chunk(RiffChunk.ANMF, anmf.array()));

        final WEBPReader reader = assertDoesNotThrow(() -> new WEBPReader(ByteBuffer.wrap(forged)));
        assertThrows(XCodecException.class, reader::next, "Negative sub-chunk size must fail cleanly");
    }

    // ANMF FRAMES EXTENDING PAST THE CANVAS PREVIOUSLY DECODED A FULL OFF-CANVAS FRAME BUFFER
    @Test
    @DisplayName("ANMF frame outside the canvas is rejected")
    void anmfFrameOutsideCanvas() {
        final byte[] forged = concat(
                chunk(RiffChunk.VP8X, vp8xBody(0x02, 16, 16)),
                chunk(RiffChunk.ANIM, new byte[6]),
                chunk(RiffChunk.ANMF, anmfHeader(0, 0, 32, 32)));

        final WEBPReader reader = assertDoesNotThrow(() -> new WEBPReader(ByteBuffer.wrap(forged)));
        assertThrows(XCodecException.class, reader::next, "Out-of-canvas frame must fail cleanly");
    }

    // 16Kx16K PASSES THE PER-AXIS CAP BUT WOULD FORCE ~2 GiB OF CANVAS + OUTPUT ALLOCATIONS
    @Test
    @DisplayName("Canvas above the total-pixel cap is rejected at construction")
    void canvasPixelBomb() {
        final byte[] forged = chunk(RiffChunk.VP8X, vp8xBody(0x02, 16384, 16384));
        assertThrows(XCodecException.class, () -> new WEBPReader(ByteBuffer.wrap(forged)),
                "Pixel bomb must fail before allocating");
    }

    // AN ODD-SIZED VP8X TRUNCATED EXACTLY AT ITS END PREVIOUSLY BLEW UP scan() WITH AN
    // IllegalArgumentException WHILE SEEKING ONE BYTE PAST THE BUFFER LIMIT
    @Test
    @DisplayName("Truncated odd-sized VP8X does not crash the constructor")
    void truncatedOddVp8x() throws IOException {
        final ByteBuffer body = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        body.put(vp8xBody(0x02, 1, 1)).put((byte) 0); // 11 BYTES: ODD SIZE, NO PADDING FOLLOWS
        final ByteBuffer forged = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        forged.putInt(RiffChunk.VP8X).putInt(11).put(body.array());
        forged.flip();

        final WEBPReader reader = assertDoesNotThrow(() -> new WEBPReader(forged));
        assertFalse(reader.hasNext(), "Nothing to decode in a header-only file");
    }

    // ----- FORGING HELPERS -----

    // CHUNK STREAM OF A REAL FIXTURE (EVERYTHING PAST THE 12-BYTE RIFF????WEBP PREAMBLE)
    private static byte[] fixtureChunks(final java.nio.file.Path path) {
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
