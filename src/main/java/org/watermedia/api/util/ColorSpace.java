package org.watermedia.api.util;

import java.nio.ByteBuffer;

/**
 * Pixel layouts.
 */
public enum ColorSpace {
    // SINGLE PLANE
    GRAY,
    RGB,
    RGBA,
    BGRA,
    GBRA,
    YUYV,
    YUYV2,
    // TWO PLANES (Y + interleaved chroma)
    NV12,
    NV21,
    // THREE PLANES (Y + U + V)
    YUV420P,
    YUV422P,
    YUV444P,
    // FOUR PLANES (Y + U + V + A)
    YUVA420P,
    YUVA422P,
    YUVA444P,
}
