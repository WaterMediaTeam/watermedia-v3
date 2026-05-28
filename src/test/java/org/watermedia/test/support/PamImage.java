package org.watermedia.test.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses a PAM (Portable Arbitrary Map, P7) file used as the canonical
 * reference for codec pixel-perfect tests.
 *
 * <p>The decoder under test produces BGRA buffers; PAM stores RGB(A) tightly
 * packed. {@link #bgraToRgba(ByteBuffer, int, int)} converts a single frame
 * once so test bodies stay flat.
 */
public record PamImage(int width, int height, int depth, byte[] pixels) {

    /** Reads {@code path} and parses the PAM payload. */
    public static PamImage read(final Path path) {
        try {
            return parse(Files.readAllBytes(path));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read PAM " + path, e);
        }
    }

    /** Parses an in-memory PAM blob. */
    public static PamImage parse(final byte[] data) {
        final int headerEnd = findHeaderEnd(data);
        if (headerEnd <= 0) {
            throw new IllegalArgumentException("Invalid PAM payload: ENDHDR marker missing");
        }
        final String header = new String(data, 0, headerEnd, StandardCharsets.US_ASCII);

        int width = 0;
        int height = 0;
        int depth = 0;
        for (final String line: header.split("\\R")) {
            if (line.startsWith("WIDTH ")) width = Integer.parseInt(line.substring(6).trim());
            else if (line.startsWith("HEIGHT ")) height = Integer.parseInt(line.substring(7).trim());
            else if (line.startsWith("DEPTH ")) depth = Integer.parseInt(line.substring(6).trim());
        }
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Invalid PAM header: w=" + width + " h=" + height + " d=" + depth);
        }
        final byte[] pixels = new byte[data.length - headerEnd];
        System.arraycopy(data, headerEnd, pixels, 0, pixels.length);
        return new PamImage(width, height, depth, pixels);
    }

    /**
     * Converts a BGRA byte buffer to an RGBA byte array. The buffer's position
     * is consumed; callers that need to reuse it should {@link ByteBuffer#duplicate()}
     * beforehand.
     */
    public static byte[] bgraToRgba(final ByteBuffer bgra, final int width, final int height) {
        final int pixelCount = width * height;
        final byte[] rgba = new byte[pixelCount * 4];
        bgra.rewind();
        for (int i = 0; i < pixelCount; i++) {
            final int offset = i * 4;
            final byte b = bgra.get(offset);
            final byte g = bgra.get(offset + 1);
            final byte r = bgra.get(offset + 2);
            final byte a = bgra.get(offset + 3);
            rgba[offset] = r;
            rgba[offset + 1] = g;
            rgba[offset + 2] = b;
            rgba[offset + 3] = a;
        }
        return rgba;
    }

    private static int findHeaderEnd(final byte[] data) {
        for (int i = 0; i <= data.length - 7; i++) {
            if (data[i] == 'E' && data[i + 1] == 'N' && data[i + 2] == 'D'
                    && data[i + 3] == 'H' && data[i + 4] == 'D' && data[i + 5] == 'R'
                    && data[i + 6] == '\n') {
                return i + 7;
            }
        }
        return -1;
    }
}
