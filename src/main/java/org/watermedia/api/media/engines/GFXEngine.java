package org.watermedia.api.media.engines;

import java.nio.ByteBuffer;

/**
 * Graphics engine abstraction for uploading decoded video frames to GPU textures.
 * <p>
 * WATERMeDIA creates the texture, uploads pixel data, and exposes a handle.
 * The developer binds that handle in their rendering pipeline.
 * <p>
 * Implementations are backend-specific (OpenGL, Vulkan, etc.).
 * Thread-safety contracts depend on the backend — see implementation javadoc.
 */
public abstract class GFXEngine {
    protected ColorSpace colorSpace;
    protected int width;
    protected int height;
    protected int bitsPerComponent = 8;

    /**
     * Reconfigures the engine for a new video format (8-bit).
     * @param colorSpace pixel layout of incoming frames
     * @param width  frame width in pixels
     * @param height frame height in pixels
     */
    public void setVideoFormat(final ColorSpace colorSpace, final int width, final int height) {
        this.setVideoFormat(colorSpace, width, height, 8);
    }

    /**
     * Reconfigures the engine for a new video format with explicit bit depth.
     * <b>This resets all internal rendering state</b> — plane textures, PBOs, shaders,
     * and any buffered frame data are released and re-initialized on the next upload.
     * Must be called before the first upload and whenever resolution or color space changes.
     * @param colorSpace pixel layout of incoming frames
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @param bitsPerComponent sample precision: 8, 10, 12, 16, or 32
     */
    public void setVideoFormat(final ColorSpace colorSpace, final int width, final int height, final int bitsPerComponent) {
        this.colorSpace = colorSpace;
        this.width = width;
        this.height = height;
        this.bitsPerComponent = bitsPerComponent;
    }

    /** Frame width in pixels, or 0 if no format has been set. */
    public int width() { return this.width; }

    /** Frame height in pixels, or 0 if no format has been set. */
    public int height() { return this.height; }

    /**
     * Returns the GPU-side handle for the final RGBA texture.
     * <p>
     * For OpenGL this is a {@code GLuint} texture name (fits in the lower 32 bits).
     * For Vulkan this would be a {@code VkImageView} handle ({@code uint64_t}).
     * @return texture handle, or 0 if no frame has been uploaded yet
     */
    public abstract long texture();

    /**
     * Uploads a single-plane frame (BGRA, RGBA, RGB, GRAY, YUYV).
     * @param buffer  direct ByteBuffer pointing to native pixel data
     * @param stride  row stride in <b>bytes</b>, or 0 for tightly-packed rows
     */
    public abstract void upload(final ByteBuffer buffer, final int stride);

    /**
     * Uploads a two-plane frame (NV12, NV21, P010, P016).
     * @param yBuffer   Y plane
     * @param yStride   Y row stride in bytes, or 0 for tightly-packed
     * @param uvBuffer  interleaved UV (NV12) or VU (NV21) plane
     * @param uvStride  UV row stride in bytes, or 0 for tightly-packed
     */
    public abstract void upload(final ByteBuffer yBuffer, final int yStride,
                                final ByteBuffer uvBuffer, final int uvStride);

    /**
     * Uploads a three-plane frame (YUV420P, YUV422P, YUV444P).
     * @param yBuffer  Y plane — full resolution
     * @param yStride  Y row stride in bytes
     * @param uBuffer  U (Cb) plane — chroma-subsampled
     * @param uStride  U row stride in bytes
     * @param vBuffer  V (Cr) plane — chroma-subsampled
     * @param vStride  V row stride in bytes
     */
    public abstract void upload(final ByteBuffer yBuffer, final int yStride,
                                final ByteBuffer uBuffer, final int uStride,
                                final ByteBuffer vBuffer, final int vStride);

    /**
     * Uploads a four-plane frame (YUVA420P, YUVA422P, YUVA444P).
     * @param yBuffer  Y plane — full resolution
     * @param yStride  Y row stride in bytes
     * @param uBuffer  U (Cb) plane — chroma-subsampled
     * @param uStride  U row stride in bytes
     * @param vBuffer  V (Cr) plane — chroma-subsampled
     * @param vStride  V row stride in bytes
     * @param aBuffer  Alpha plane — full resolution
     * @param aStride  Alpha row stride in bytes
     */
    public abstract void upload(final ByteBuffer yBuffer, final int yStride,
                                final ByteBuffer uBuffer, final int uStride,
                                final ByteBuffer vBuffer, final int vStride,
                                final ByteBuffer aBuffer, final int aStride);

    /**
     * Releases all GPU resources. The engine is unusable after this call.
     */
    public abstract void release();

    /**
     * Pixel layouts supported by the engine.
     * <p>
     * Single-plane formats go through {@link #upload(ByteBuffer, int)}.
     * Two-plane formats go through the 2-buffer overload.
     * Three-plane formats go through the 3-buffer overload.
     * Four-plane formats go through the 4-buffer overload.
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
}
