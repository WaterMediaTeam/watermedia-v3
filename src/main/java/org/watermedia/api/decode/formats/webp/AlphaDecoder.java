package org.watermedia.api.decode.formats.webp;

import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.formats.webp.common.BitReader;
import org.watermedia.api.decode.formats.webp.lossless.VP8LDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// ALPHA BYTE OFFSET IN BGRA LAYOUT: [B=0, G=1, R=2, A=3]
// PIXEL STRIDE IS 4 BYTES, ALPHA IS AT i*4+3

import static org.watermedia.WaterMedia.LOGGER;

// WEBP ALPHA CHANNEL DECODER
// SUPPORTS UNCOMPRESSED AND VP8L-COMPRESSED ALPHA DATA
// WITH OPTIONAL PREDICTIVE FILTERING (HORIZONTAL, VERTICAL, GRADIENT)
public final class AlphaDecoder {

    // COMPRESSION METHODS
    private static final int COMPRESS_NONE = 0;
    private static final int COMPRESS_LOSSLESS = 1;

    // FILTERING METHODS
    private static final int FILTER_NONE = 0;
    private static final int FILTER_HORIZ = 1;
    private static final int FILTER_VERT = 2;
    private static final int FILTER_GRAD = 3;

    private AlphaDecoder() {
    }

    // DECODE ALPHA CHUNK AND RETURN ALPHA PLANE (W*H BYTES)
    public static byte[] decode(ByteBuffer buffer, final int w, final int h) throws DecoderException {
        buffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);

        if (buffer.remaining() < 1) {
            throw new DecoderException("Alpha chunk too small");
        }

        // READ HEADER BYTE: [FILTER:2][PREPROC:2][COMPRESSION:2][RESERVED:2]
        final int hdr = buffer.get() & 0xFF;
        final int filterMeth = (hdr >> 4) & 0x03;
        final int compMeth = hdr & 0x03;
        // int preProc = (hdr >> 2) & 0x03; // UNUSED

        LOGGER.debug("Alpha: filter={}, compression={}", filterMeth, compMeth);

        final byte[] alpha;

        if (compMeth == COMPRESS_NONE) {
            // UNCOMPRESSED: RAW BYTES
            alpha = new byte[w * h];
            if (buffer.remaining() < alpha.length) {
                throw new DecoderException("Alpha data truncated");
            }
            buffer.get(alpha);
        } else if (compMeth == COMPRESS_LOSSLESS) {
            // VP8L COMPRESSED - EXTRACT GREEN CHANNEL AS ALPHA
            final BitReader rd = new BitReader(buffer);
            final int[] argb = VP8LDecoder.decode(rd, w, h);

            alpha = new byte[w * h];
            for (int i = 0; i < argb.length; i++) {
                alpha[i] = (byte) ((argb[i] >> 8) & 0xFF);
            }
        } else {
            throw new DecoderException("Unknown alpha compression: " + compMeth);
        }

        // APPLY INVERSE FILTER TO RECONSTRUCT ORIGINAL VALUES
        if (filterMeth != FILTER_NONE) {
            applyInverseFilter(alpha, w, h, filterMeth);
        }

        return alpha;
    }

    private static void applyInverseFilter(final byte[] alpha, final int w, final int h, final int filterMeth) {
        switch (filterMeth) {
            case FILTER_HORIZ -> inverseHorizFilter(alpha, w, h);
            case FILTER_VERT -> inverseVertFilter(alpha, w, h);
            case FILTER_GRAD -> inverseGradFilter(alpha, w, h);
        }
    }

    // HORIZONTAL FILTER: PIXEL[X] += PIXEL[X-1]
    private static void inverseHorizFilter(final byte[] alpha, final int w, final int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w; x++) {
                final int pos = y * w + x;
                final int left = alpha[pos - 1] & 0xFF;
                alpha[pos] = (byte) ((alpha[pos] & 0xFF) + left);
            }
        }
    }

    // VERTICAL FILTER: PIXEL[Y] += PIXEL[Y-1]
    private static void inverseVertFilter(final byte[] alpha, final int w, final int h) {
        for (int y = 1; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int pos = y * w + x;
                final int top = alpha[pos - w] & 0xFF;
                alpha[pos] = (byte) ((alpha[pos] & 0xFF) + top);
            }
        }
    }

    // GRADIENT FILTER: PIXEL += CLAMP(LEFT + TOP - TOP_LEFT)
    private static void inverseGradFilter(final byte[] alpha, final int w, final int h) {
        // FIRST ROW: HORIZONTAL ONLY
        for (int x = 1; x < w; x++) {
            final int left = alpha[x - 1] & 0xFF;
            alpha[x] = (byte) ((alpha[x] & 0xFF) + left);
        }

        // FIRST COLUMN: VERTICAL ONLY
        for (int y = 1; y < h; y++) {
            final int top = alpha[(y - 1) * w] & 0xFF;
            alpha[y * w] = (byte) ((alpha[y * w] & 0xFF) + top);
        }

        // REST: GRADIENT PREDICTION
        for (int y = 1; y < h; y++) {
            for (int x = 1; x < w; x++) {
                final int pos = y * w + x;
                final int left = alpha[pos - 1] & 0xFF;
                final int top = alpha[pos - w] & 0xFF;
                final int topLeft = alpha[pos - w - 1] & 0xFF;
                final int pred = gradPredict(left, top, topLeft);
                alpha[pos] = (byte) ((alpha[pos] & 0xFF) + pred);
            }
        }
    }

    // GRADIENT PREDICTOR: CLAMP(LEFT + TOP - TOP_LEFT) TO 0-255
    private static int gradPredict(final int left, final int top, final int topLeft) {
        final int pred = left + top - topLeft;
        return Math.max(0, Math.min(255, pred));
    }

    // APPLY ALPHA VALUES TO ARGB PIXEL ARRAY (USED FOR ANIMATION COMPOSITING)
    public static void applyAlpha(final int[] argb, final byte[] alpha) {
        final int len = Math.min(argb.length, alpha.length);
        for (int i = 0; i < len; i++) {
            final int a = alpha[i] & 0xFF;
            argb[i] = (a << 24) | (argb[i] & 0x00FFFFFF);
        }
    }

    // APPLY ALPHA VALUES IN-PLACE ON BGRA BYTEBUFFER
    // WRITES ALPHA BYTE DIRECTLY AT OFFSET i*4+3 (THE A POSITION IN BGRA)
    // NO ALLOCATION, NO CONVERSION - JUST OVERWRITES ALPHA BYTES
    public static void applyAlpha(final ByteBuffer bgra, final byte[] alpha) {
        final int pixelCount = Math.min(bgra.capacity() / 4, alpha.length);
        for (int i = 0; i < pixelCount; i++) {
            bgra.put(i * 4 + 3, alpha[i]);
        }
    }
}