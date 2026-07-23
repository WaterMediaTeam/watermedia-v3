package org.watermedia.tools;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class DataTool {
    public static boolean startsWith(final ByteBuffer buffer, final int offset, final byte[] header) {
        if (buffer.limit() - offset < header.length) return false;
        for (int i = 0; i < header.length; i++) {
            if (buffer.get(offset + i) != header[i]) return false;
        }
        return true;
    }

    public static boolean startsWith(final String str, final String... prefixes) {
        Objects.requireNonNull(str, "String cannot be null");
        Objects.requireNonNull(prefixes, "Prefix array cannot be null");
        for (final var prefix: prefixes) {
            if (str.startsWith(prefix)) return true;
        }
        return false;
    }

    public static Map<String, String> parseQuery(final String query) {
        if (query == null || query.isEmpty()) return Map.of();

        final var result = new HashMap<String, String>();
        for (final var param: query.split("&")) {
            final var pair = param.split("=", 2);
            final var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            final var value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    public static <T> Map<T, T> arrayMapper(final T[] keyValue) {
        if (keyValue.length % 2 != 0)
            throw new IllegalArgumentException("Not all keys have values");

        final var map = new HashMap<T, T>(keyValue.length / 2);
        for (int i = 0; i < keyValue.length; i+=2) {
            map.put(keyValue[i], keyValue[i + 1]);
        }
        return map;
    }

    public static boolean endsWith(final String o, final String... arr) {
        for (final String element: arr)
            if (element != null && o.endsWith(element)) return true;
        return false;
    }

    public static boolean equalsAnyIgnoreCase(final String o, final String... arr) {
        for (final String element: arr)
            if (element != null && element.equalsIgnoreCase(o)) return true; // CASE-INSENSITIVE EQUALITY CHECK
        return false;
    }

    @SafeVarargs
    public static <T> boolean contains(final T o, final T... arr) {
        for (final T element: arr)
            if (Objects.equals(element, o)) return true; // OBJECT EQUALITY CHECK
        return false;
    }

    public static boolean contains(final String o, final String in) {
        return o != null && o.contains(in);
    }

    public static byte bytesAt(final long packet, final int position) {
        return (byte) ((packet >> (position * 8)) & 0xFF);
    }

    public static int readBytesAsInt(final ByteBuffer buffer, final int length, final ByteOrder order) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value |= (buffer.get() & 0xFF) << shiftFor((length * 8) - 8, i * 8, order);
        }
        return value;
    }

    public static long readBytesAsLong(final ByteBuffer buffer, final int length, final ByteOrder order) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value |= (long) (buffer.get() & 0xFF) << shiftFor((length * 8) - 8, i * 8, order);
        }
        return value;
    }

    public static long sumArray(final long... array) {
        int i = 0;
        long result = 0;
        while (i < array.length) {
            result += array[i++];
        }
        return result;
    }

    public static int sumArray(final int... array) {
        int i = 0;
        int result = 0;
        while (i < array.length) {
            result += array[i++];
        }
        return result;
    }

    public static short toShort(final byte one, final byte two, final ByteOrder order) {
        return (short) ((one & 0xFF) << shiftFor(8, 0, order) | (two & 0xFF) << shiftFor(8, 8, order));
    }

    public static int toInt(final byte one, final byte two, final byte three, final byte four, final ByteOrder order) {
        return (one & 0xFF) << shiftFor(24, 0, order) | (two & 0xFF) << shiftFor(24, 8, order) | (three & 0xFF) << shiftFor(24, 16, order) | (four & 0xFF) << shiftFor(24, 24, order);
    }

    public static long toLong(final byte b1, final byte b2, final byte b3, final byte b4, final byte b5, final byte b6, final byte b7, final byte b8, final ByteOrder order) {
        return ((long)(b1 & 0xFF) << shiftFor(56, 0, order)) |
                ((long)(b2 & 0xFF) << shiftFor(56, 8, order)) |
                ((long)(b3 & 0xFF) << shiftFor(56, 16, order)) |
                ((long)(b4 & 0xFF) << shiftFor(56, 24, order)) |
                ((long)(b5 & 0xFF) << shiftFor(56, 32, order)) |
                ((long)(b6 & 0xFF) << shiftFor(56, 40, order)) |
                ((long)(b7 & 0xFF) << shiftFor(56, 48, order))  |
                ((long)(b8 & 0xFF) << shiftFor(56, 56, order));
    }

    private static int shiftFor(final int top, final int pos, final ByteOrder order) {
        return order == ByteOrder.BIG_ENDIAN ? top - pos : pos;
    }

    public static void rgbaToBgra(final ByteBuffer buffer, final int pixel, final byte a) {
        final int r = (pixel >> 16) & 0xFF;
        final int g = (pixel >> 8) & 0xFF;
        final int b = pixel & 0xFF;
        buffer.put((byte) b);
        buffer.put((byte) g);
        buffer.put((byte) r);
        buffer.put(a);
    }

    // CONVERTS YUV TO BGRA INT ARRAY (USED FOR ANIMATION COMPOSITING)
    // INT LAYOUT: (A<<24 | R<<16 | G<<8 | B) - WHEN WRITTEN AS LITTLE-ENDIAN GIVES [B,G,R,A] BYTES
    public static int[] yuvToBgra(final byte[] yP, final byte[] uP, final byte[] vP, final int w, final int h, final int yS, final int uvS) {
        final int[] bgra = new int[w * h];
        for (int py = 0; py < h; py++)
            for (int px = 0; px < w; px++) {
                final int y = yP[py * yS + px] & 0xFF;
                final int i = (py >> 1) * uvS + (px >> 1);
                final int u = uP[i] & 0xFF;
                final int v = vP[i] & 0xFF;
                final int c = y - 16, d = u - 128, e = v - 128;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;

                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                bgra[py * w + px] = (255 << 24) | (r << 16) | (g << 8) | b;
            }
        return bgra;
    }

    // CONVERTS YUV DIRECTLY TO BGRA BYTEBUFFER
    public static ByteBuffer yuvToBgraBuf(final byte[] yP, final byte[] uP, final byte[] vP, final int w, final int h, final int yS, final int uvS) {
        // CONVERT INTO AN ON-HEAP int[] FIRST (TIGHT ALLOCATION-FREE HOT LOOP WITH ARRAY
        // BOUNDS-CHECK ELIMINATION), THEN DO A SINGLE BULK COPY INTO THE DIRECT BUFFER.
        // THIS AVOIDS ~w*h DIRECT-BUFFER put/putInt CALLS, EACH OF WHICH WOULD BE A SEPARATE
        // ACCESS WITH ITS OWN BOUNDS CHECK.
        final int[] packed = new int[w * h];
        for (int py = 0; py < h; py++) {
            final int yRow = py * yS;
            final int uvRow = (py >> 1) * uvS;
            final int dstRow = py * w;
            for (int px = 0; px < w; px++) {
                final int y = yP[yRow + px] & 0xFF;
                final int i = uvRow + (px >> 1);
                final int u = uP[i] & 0xFF;
                final int v = vP[i] & 0xFF;
                final int c = y - 16, d = u - 128, e = v - 128;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;

                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;

                // PACK AS LITTLE-ENDIAN INT: WHEN COPIED INTO LE DIRECT BUFFER THIS LANDS AS
                // B, G, R, A IN MEMORY, MATCHING THE WEBP READER'S EXPECTED LAYOUT.
                packed[dstRow + px] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        final ByteBuffer bgra = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        bgra.asIntBuffer().put(packed);
        return bgra;
    }

    // THIS IS THE MOST EFFICIENT WAY TO CONVERT INT[] CANVAS TO BYTEBUFFER
    public static ByteBuffer bgraToBuffer(final int[] bgra) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bgra.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(bgra);
        return buffer;
    }

    public static int toInt(final String s, final int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    public static long toLong(final String s, final long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    public static double toDouble(final String s, final double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    // LOWERCASE HEX ENCODING OF bytes
    public static String hex(final byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (final byte b: bytes) {
            out.append(Character.forDigit((b >>> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    // ONE-SHOT SHA-256 OF data; SHA-256 IS MANDATED BY THE JVM SPEC, SO ABSENCE MEANS A BROKEN RUNTIME
    public static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
