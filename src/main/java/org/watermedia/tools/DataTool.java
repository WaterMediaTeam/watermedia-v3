package org.watermedia.tools;

import org.watermedia.api.util.PixelFormat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
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

    // EXPIRY CARRIED BY A SIGNED CDN URL: THE FIRST expire/expires/validto/x-expires QUERY VALUE, IN EPOCH SECONDS
    // OR MILLISECONDS, OR NULL WHEN THE URL CARRIES NONE
    public static Instant expiry(final URI uri) {
        final String query = uri == null ? null : uri.getRawQuery();
        if (query == null) return null;
        for (final String param: query.split("&")) {
            final int eq = param.indexOf('=');
            if (eq < 0) continue;
            final String key = param.substring(0, eq).toLowerCase(Locale.ROOT);
            if (!key.equals("expire") && !key.equals("expires") && !key.equals("validto") && !key.equals("x-expires")) continue;
            // BELOW 2001 IN SECONDS IT IS A RELATIVE LIFETIME OR GARBAGE, NOT AN EPOCH; ABOVE 100 BILLION IT IS MILLISECONDS
            final long value = parseLong(param.substring(eq + 1), -1L);
            if (value < 1_000_000_000L) continue;
            return value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        return null;
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

    public static boolean equalsAnyIgnoreCase(final String o, final String... arr) {
        for (final String element: arr)
            if (element != null && element.equalsIgnoreCase(o)) return true; // CASE-INSENSITIVE EQUALITY CHECK
        return false;
    }

    public static boolean contains(final String o, final String in) {
        return o != null && o.contains(in);
    }

    public static long sumArray(final long... array) {
        int i = 0;
        long result = 0;
        while (i < array.length) {
            result += array[i++];
        }
        return result;
    }

    // CONVERTS BT.601 LIMITED-RANGE YUV 4:2:0 PLANES (VP8) TO PACKED OPAQUE ARGB INTS, WHICH LAND AS [B,G,R,A]
    // BYTES ONCE WRITTEN LITTLE-ENDIAN. STRIDES MAY EXCEED THE WIDTH (MACROBLOCK PADDING)
    public static int[] yuvToBgra(final byte[] yP, final byte[] uP, final byte[] vP, final int w, final int h, final int yS, final int uvS) {
        final int[] bgra = new int[w * h];
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

                bgra[dstRow + px] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return bgra;
    }

    // THIS IS THE MOST EFFICIENT WAY TO CONVERT INT[] CANVAS TO BYTEBUFFER
    public static ByteBuffer bgraToBuffer(final int[] bgra) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bgra.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(bgra);
        return buffer;
    }

    // NON-PREMULTIPLIED SOURCE-OVER OF TWO PACKED ARGB PIXELS (APNG AND WEBP FRAME BLENDING). THE DESTINATION
    // WEIGHT IS COMPUTED ONCE AND SHARED BY THE ALPHA SUM AND EVERY CHANNEL, SO NO CHANNEL CAN EXCEED 255
    public static int blendOver(final int src, final int dst) {
        final int srcA = src >>> 24;
        if (srcA == 255) return src;
        if (srcA == 0) return dst;
        final int dstW = (dst >>> 24) * (255 - srcA) / 255;
        final int outA = srcA + dstW;
        final int r = (((src >> 16) & 0xFF) * srcA + ((dst >> 16) & 0xFF) * dstW) / outA;
        final int g = (((src >> 8) & 0xFF) * srcA + ((dst >> 8) & 0xFF) * dstW) / outA;
        final int b = ((src & 0xFF) * srcA + (dst & 0xFF) * dstW) / outA;
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }

    // PREMULTIPLIES 8-BIT FOUR-CHANNEL PIXELS WITH ALPHA LAST (BGRA OR RGBA) IN PLACE, FROM position TO limit.
    // EACH CHANNEL BECOMES round(c * a / 255); OPAQUE PIXELS ARE SKIPPED, SO OPAQUE VIDEO COSTS ONE READ PER PIXEL.
    public static void premultiply(final ByteBuffer pixels) {
        final ByteBuffer view = pixels.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = view.position(), end = view.limit() - 3; i < end; i += 4) {
            final int p = view.getInt(i);
            final int a = p >>> 24;
            if (a == 0xFF) continue;
            // TWO CHANNELS PER MULTIPLY; (y + (y >> 8)) >> 8 WITH y = c * a + 128 IS THE EXACT ROUNDED DIVISION BY 255
            final int rb = (p & 0x00FF00FF) * a + 0x00800080;
            final int g = (p & 0x0000FF00) * a + 0x00008000;
            view.putInt(i, (p & 0xFF000000) | (((rb + ((rb >>> 8) & 0x00FF00FF)) >>> 8) & 0x00FF00FF)
                    | (((g + ((g >>> 8) & 0x0000FF00)) >>> 8) & 0x0000FF00));
        }
    }

    // REMAPS A TIGHTLY PACKED RASTER OF 1 OR 4 BYTES PER PIXEL INTO ITS EXIF ORIENTATION (1-8), FROM THE START OF
    // src INTO THE START OF dst; 5-8 SWAP THE AXES, SO dst ENDS UP height x width. OUTPUT PIXEL (x, y) COMES FROM
    // SOURCE INDEX a + y * b + x * step
    public static void orient(final ByteBuffer src, final ByteBuffer dst, final int w, final int h,
                              final int bytesPerPixel, final int orientation) {
        final boolean swap = orientation >= 5;
        final int ow = swap ? h : w, oh = swap ? w : h;
        final int a, b, step;
        switch (orientation) {
            case 1 -> { a = 0; b = w; step = 1; }
            case 2 -> { a = w - 1; b = w; step = -1; }
            case 3 -> { a = (h - 1) * w + w - 1; b = -w; step = -1; }
            case 4 -> { a = (h - 1) * w; b = -w; step = 1; }
            case 5 -> { a = 0; b = 1; step = w; }
            case 6 -> { a = (h - 1) * w; b = 1; step = -w; }
            case 7 -> { a = (h - 1) * w + w - 1; b = -1; step = -w; }
            case 8 -> { a = w - 1; b = -1; step = w; }
            default -> throw new IllegalArgumentException("Invalid EXIF orientation: " + orientation);
        }
        final ByteBuffer in = src.duplicate().clear();
        final ByteBuffer out = dst.duplicate().clear();
        if (bytesPerPixel == 4) {
            final IntBuffer pixels = in.asIntBuffer();
            final IntBuffer target = out.asIntBuffer();
            for (int y = 0, di = 0; y < oh; y++) {
                for (int x = 0, si = a + y * b; x < ow; x++, si += step) target.put(di++, pixels.get(si));
            }
        } else if (bytesPerPixel == 1) {
            for (int y = 0, di = 0; y < oh; y++) {
                for (int x = 0, si = a + y * b; x < ow; x++, si += step) out.put(di++, in.get(si));
            }
        } else throw new IllegalArgumentException("Unsupported bytes per pixel: " + bytesPerPixel);
        dst.position(0).limit(ow * oh * bytesPerPixel);
    }

    public static int parseInt(final String s, final int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    public static long parseLong(final String s, final long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    public static double parseDouble(final String s, final double def) {
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

    // ============================================================
    // PIXEL-PLANE AREA SCALING — SHARED BY TxMediaPlayer AND DownscaleFilter
    // ============================================================

    // TIGHTLY-PACKED BYTE SIZE OF ONE FRAME IN THE GIVEN FORMAT AT w x h.
    public static long frameBytes(final PixelFormat format, final int w, final int h) {
        final long pixels = (long) w * h;
        final long chromaW = (w + 1L) >> 1;
        final long chromaH = (h + 1L) >> 1;
        return switch (format) {
            case GRAY -> pixels;
            case YUYV, UYVY -> pixels * 2L;
            case RGB, YUV444P -> pixels * 3L;
            case BGRA, RGBA, GBRA, YUVA444P -> pixels * 4L;
            case NV12, NV21, YUV420P -> pixels + 2L * chromaW * chromaH;
            case YUV422P -> pixels + 2L * chromaW * h;
            case YUVA420P -> pixels * 2L + 2L * chromaW * chromaH;
            case YUVA422P -> pixels * 2L + 2L * chromaW * h;
            // BCn: ONE PLANE OF 4x4 BLOCKS
            case BC1, BC2, BC3, BC5, BC7 -> ((w + 3L) >> 2) * ((h + 3L) >> 2) * format.blockBytes();
        };
    }

    // FORMATS THE AREA SCALER UNDERSTANDS — PACKED YUYV (PIXEL PAIRS SHARE CHROMA) AND
    // COMPRESSED BCn (GPU-SIDE BLOCKS) CANNOT BE AVERAGED BYTE-WISE.
    public static boolean scalable(final PixelFormat format) {
        return switch (format) {
            case YUYV, UYVY, BC1, BC2, BC3, BC5, BC7 -> false;
            default -> true;
        };
    }

    // AREA-AVERAGE SCALE OF A FULL FRAME (TIGHTLY-PACKED PLANES) FROM sw x sh INTO dst AT dw x dh.
    // MIRRORS THE PLANE LAYOUT OF frameBytes; EACH OUTPUT PIXEL AVERAGES ITS WHOLE SOURCE BLOCK.
    public static void scaleArea(final ByteBuffer src, final int sw, final int sh,
                                 final ByteBuffer dst, final int dw, final int dh, final PixelFormat format) {
        final int base = src.position();
        switch (format) {
            case GRAY -> scalePlane(src, base, sw, sh, dst, dw, dh, 1);
            case RGB -> scalePlane(src, base, sw, sh, dst, dw, dh, 3);
            case BGRA, RGBA, GBRA -> scalePlane(src, base, sw, sh, dst, dw, dh, 4);
            case NV12, NV21 -> {
                scalePlane(src, base, sw, sh, dst, dw, dh, 1);
                scalePlane(src, base + sw * sh, (sw + 1) >> 1, (sh + 1) >> 1, dst, (dw + 1) >> 1, (dh + 1) >> 1, 2);
            }
            case YUV420P, YUVA420P -> {
                final int scw = (sw + 1) >> 1;
                final int sch = (sh + 1) >> 1;
                final int dcw = (dw + 1) >> 1;
                final int dch = (dh + 1) >> 1;
                scalePlane(src, base, sw, sh, dst, dw, dh, 1);
                scalePlane(src, base + sw * sh, scw, sch, dst, dcw, dch, 1);
                scalePlane(src, base + sw * sh + scw * sch, scw, sch, dst, dcw, dch, 1);
                if (format == PixelFormat.YUVA420P) {
                    scalePlane(src, base + sw * sh + 2 * scw * sch, sw, sh, dst, dw, dh, 1);
                }
            }
            case YUV422P, YUVA422P -> {
                final int scw = (sw + 1) >> 1;
                final int dcw = (dw + 1) >> 1;
                scalePlane(src, base, sw, sh, dst, dw, dh, 1);
                scalePlane(src, base + sw * sh, scw, sh, dst, dcw, dh, 1);
                scalePlane(src, base + sw * sh + scw * sh, scw, sh, dst, dcw, dh, 1);
                if (format == PixelFormat.YUVA422P) {
                    scalePlane(src, base + sw * sh + 2 * scw * sh, sw, sh, dst, dw, dh, 1);
                }
            }
            case YUV444P, YUVA444P -> {
                final int planes = format == PixelFormat.YUVA444P ? 4 : 3;
                for (int p = 0; p < planes; p++) {
                    scalePlane(src, base + p * sw * sh, sw, sh, dst, dw, dh, 1);
                }
            }
            default -> dst.put(src.duplicate()); // YUYV/BCn ARE UNSCALABLE — COPY VERBATIM SO dst IS ALWAYS FILLED
        }
    }

    // AREA-AVERAGE DOWNSCALE OF AN 8-BIT PLANE WITH ch INTERLEAVED COMPONENTS.
    // STRONG REDUCTIONS STAY ALIAS-FREE. READS src ABSOLUTE, WRITES dst RELATIVE.
    private static void scalePlane(final ByteBuffer src, final int srcOff, final int sw, final int sh,
                                   final ByteBuffer dst, final int dw, final int dh, final int ch) {
        final int[] acc = new int[ch];
        for (int dy = 0; dy < dh; dy++) {
            final int sy0 = (int) ((long) dy * sh / dh);
            final int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * sh / dh));
            for (int dx = 0; dx < dw; dx++) {
                final int sx0 = (int) ((long) dx * sw / dw);
                final int sx1 = Math.max(sx0 + 1, (int) ((long) (dx + 1) * sw / dw));
                Arrays.fill(acc, 0);
                for (int sy = sy0; sy < sy1; sy++) {
                    int p = srcOff + (sy * sw + sx0) * ch;
                    for (int sx = sx0; sx < sx1; sx++) {
                        for (int c = 0; c < ch; c++) acc[c] += src.get(p++) & 0xFF;
                    }
                }
                final int count = (sy1 - sy0) * (sx1 - sx0);
                for (int c = 0; c < ch; c++) dst.put((byte) (acc[c] / count));
            }
        }
    }
}
