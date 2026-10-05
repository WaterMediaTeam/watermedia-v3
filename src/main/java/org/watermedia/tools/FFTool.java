package org.watermedia.tools;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.watermedia.api.util.PixelFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.bytedeco.ffmpeg.global.avutil.*;

/** FFmpeg helpers shared by the media API, the FFmpeg player and the WebM writer. */
public final class FFTool {
    /** 8-bit packed BGRA, the layout every scaler conversion produces. */
    public static final Pixels BGRA = new Pixels(PixelFormat.BGRA, 8);

    private FFTool() {}

    /** Engine pixel layout and sample precision of an FFmpeg pixel format. */
    public record Pixels(PixelFormat format, int bits) {}

    /** Maps an FFmpeg pixel format to its engine layout, or null when no engine layout matches. */
    public static Pixels pixels(final int avPixFmt) {
        return switch (avPixFmt) {
            // 8-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P -> new Pixels(PixelFormat.YUV420P, 8);
            case AV_PIX_FMT_YUV422P, AV_PIX_FMT_YUVJ422P -> new Pixels(PixelFormat.YUV422P, 8);
            case AV_PIX_FMT_YUV444P, AV_PIX_FMT_YUVJ444P -> new Pixels(PixelFormat.YUV444P, 8);
            // 8-BIT SEMI-PLANAR
            case AV_PIX_FMT_NV12 -> new Pixels(PixelFormat.NV12, 8);
            case AV_PIX_FMT_NV21 -> new Pixels(PixelFormat.NV21, 8);
            // 8-BIT PACKED RGB
            case AV_PIX_FMT_BGRA  -> BGRA;
            case AV_PIX_FMT_RGBA  -> new Pixels(PixelFormat.RGBA, 8);
            case AV_PIX_FMT_RGB24 -> new Pixels(PixelFormat.RGB, 8);
            // 8-BIT GRAYSCALE
            case AV_PIX_FMT_GRAY8 -> new Pixels(PixelFormat.GRAY, 8);
            // 8-BIT PACKED YUV
            case AV_PIX_FMT_YUYV422 -> new Pixels(PixelFormat.YUYV, 8);
            case AV_PIX_FMT_UYVY422 -> new Pixels(PixelFormat.YUYV2, 8);
            // 8-BIT YUVA (4-PLANE)
            case AV_PIX_FMT_YUVA420P -> new Pixels(PixelFormat.YUVA420P, 8);
            case AV_PIX_FMT_YUVA422P -> new Pixels(PixelFormat.YUVA422P, 8);
            case AV_PIX_FMT_YUVA444P -> new Pixels(PixelFormat.YUVA444P, 8);
            // 10-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P10LE -> new Pixels(PixelFormat.YUV420P, 10);
            case AV_PIX_FMT_YUV422P10LE -> new Pixels(PixelFormat.YUV422P, 10);
            case AV_PIX_FMT_YUV444P10LE -> new Pixels(PixelFormat.YUV444P, 10);
            // 10-BIT YUVA
            case AV_PIX_FMT_YUVA420P10LE -> new Pixels(PixelFormat.YUVA420P, 10);
            case AV_PIX_FMT_YUVA422P10LE -> new Pixels(PixelFormat.YUVA422P, 10);
            case AV_PIX_FMT_YUVA444P10LE -> new Pixels(PixelFormat.YUVA444P, 10);
            // 12-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P12LE -> new Pixels(PixelFormat.YUV420P, 12);
            case AV_PIX_FMT_YUV422P12LE -> new Pixels(PixelFormat.YUV422P, 12);
            case AV_PIX_FMT_YUV444P12LE -> new Pixels(PixelFormat.YUV444P, 12);
            // 12-BIT YUVA
            case AV_PIX_FMT_YUVA422P12LE -> new Pixels(PixelFormat.YUVA422P, 12);
            case AV_PIX_FMT_YUVA444P12LE -> new Pixels(PixelFormat.YUVA444P, 12);
            // 16-BIT PLANAR YUV
            case AV_PIX_FMT_YUV420P16LE -> new Pixels(PixelFormat.YUV420P, 16);
            case AV_PIX_FMT_YUV422P16LE -> new Pixels(PixelFormat.YUV422P, 16);
            case AV_PIX_FMT_YUV444P16LE -> new Pixels(PixelFormat.YUV444P, 16);
            // 16-BIT YUVA
            case AV_PIX_FMT_YUVA420P16LE -> new Pixels(PixelFormat.YUVA420P, 16);
            case AV_PIX_FMT_YUVA422P16LE -> new Pixels(PixelFormat.YUVA422P, 16);
            case AV_PIX_FMT_YUVA444P16LE -> new Pixels(PixelFormat.YUVA444P, 16);
            // P010/P016 — LEFT-SHIFTED 10/16-BIT NV12, MAP AS 16-BIT (bitScale=1.0 IS CORRECT)
            case AV_PIX_FMT_P010LE, AV_PIX_FMT_P016LE -> new Pixels(PixelFormat.NV12, 16);
            // HIGH-BIT GRAYSCALE
            case AV_PIX_FMT_GRAY10LE  -> new Pixels(PixelFormat.GRAY, 10);
            case AV_PIX_FMT_GRAY12LE  -> new Pixels(PixelFormat.GRAY, 12);
            case AV_PIX_FMT_GRAY16LE  -> new Pixels(PixelFormat.GRAY, 16);
            case AV_PIX_FMT_GRAYF32LE -> new Pixels(PixelFormat.GRAY, 32);
            // 16-BIT PACKED RGB
            case AV_PIX_FMT_RGB48LE  -> new Pixels(PixelFormat.RGB, 16);
            case AV_PIX_FMT_RGBA64LE -> new Pixels(PixelFormat.RGBA, 16);
            default -> null;
        };
    }

    /** Maps an 8-bit engine layout to its FFmpeg pixel format; block-compressed and GBRA layouts have none. */
    public static int avPixelFormat(final PixelFormat format) {
        return switch (format) {
            case GRAY -> AV_PIX_FMT_GRAY8;
            case RGB -> AV_PIX_FMT_RGB24;
            case RGBA -> AV_PIX_FMT_RGBA;
            case BGRA -> AV_PIX_FMT_BGRA;
            case YUYV -> AV_PIX_FMT_YUYV422;
            case YUYV2 -> AV_PIX_FMT_UYVY422;
            case NV12 -> AV_PIX_FMT_NV12;
            case NV21 -> AV_PIX_FMT_NV21;
            case YUV420P -> AV_PIX_FMT_YUV420P;
            case YUV422P -> AV_PIX_FMT_YUV422P;
            case YUV444P -> AV_PIX_FMT_YUV444P;
            case YUVA420P -> AV_PIX_FMT_YUVA420P;
            case YUVA422P -> AV_PIX_FMT_YUVA422P;
            case YUVA444P -> AV_PIX_FMT_YUVA444P;
            default -> throw new IllegalArgumentException("No FFmpeg pixel format for " + format);
        };
    }

    /** Decodes a negative FFmpeg return code into its native message. */
    public static String error(final int code) {
        final byte[] detail = new byte[256];
        return av_strerror(code, detail, detail.length) >= 0 ? new String(detail, StandardCharsets.UTF_8).trim() : "unknown native error";
    }

    /** Creates an exception naming the failed FFmpeg operation, its native message and its code. */
    public static IOException failure(final String operation, final int code) {
        return new IOException(operation + " failed: " + error(code) + " (" + code + ")");
    }

    /** Returns whether a native pointer is missing or null. */
    public static boolean isNull(final Pointer pointer) { return pointer == null || pointer.isNull(); }

    /** Reads a native string, or returns {@code orElse} as text when the pointer is null. */
    public static String string(final BytePointer pointer, final Object orElse) {
        return !isNull(pointer) ? pointer.getString() : orElse != null ? String.valueOf(orElse) : null;
    }
}
