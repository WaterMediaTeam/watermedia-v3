package org.watermedia.api.media.engines;

import org.watermedia.api.util.PixelFormat;

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
    protected PixelFormat pixelFormat;
    protected int width;
    protected int height;
    protected int bitsPerComponent = 8;

    /**
     * Reconfigures the engine for a new video format (8-bit).
     * @param pixelFormat pixel layout of incoming frames
     * @param width  frame width in pixels
     * @param height frame height in pixels
     */
    public void setVideoFormat(final PixelFormat pixelFormat, final int width, final int height) {
        this.setVideoFormat(pixelFormat, width, height, 8);
    }

    /**
     * Reconfigures the engine for a new video format with explicit bit depth.
     * <b>This resets all internal rendering state</b> — plane textures, PBOs, shaders,
     * and any buffered frame data are released and re-initialized on the next upload.
     * Must be called before the first upload and whenever resolution or pixel format changes.
     * @param pixelFormat pixel layout of incoming frames
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @param bitsPerComponent sample precision: 8, 10, 12, 16, or 32
     */
    public void setVideoFormat(final PixelFormat pixelFormat, final int width, final int height, final int bitsPerComponent) {
        this.pixelFormat = pixelFormat;
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
     * Whether this engine can keep a small animated image as one texture per frame.
     * Engines that return false keep using {@link #upload(ByteBuffer, int)} each frame.
     */
    public boolean supportsFrameTextures() { return false; }

    /**
     * Uploads a complete frame set as dedicated textures.
     * <p>
     * This is an optional fast path for animated images that fit a VRAM budget. The default
     * implementation reports unsupported so custom engines remain source-compatible.
     * Engines may upload the set progressively across render frames; in that case
     * {@link #useFrameTexture(int)} must clamp to the already-uploaded prefix.
     * @param frames decoded direct frame buffers in the current {@link #pixelFormat}
     * @param stride row stride in bytes, or 0 for tightly-packed rows
     * @return true when the engine accepted the frame set
     */
    public boolean uploadFrameTextures(final ByteBuffer[] frames, final int stride) { return false; }

    /**
     * Selects which preloaded frame texture is exposed by {@link #texture()}.
     */
    public void useFrameTexture(final int frameIndex) {}

    /**
     * Whether this engine can sample block-compressed (BCn) textures of the given codec.
     * Engines that return false keep receiving decoded pixels through the {@code upload} overloads.
     * @param codec a codec id such as {@code "BC7"} (see {@code CodecsAPI.CODEC_BC7})
     */
    public boolean supportsCompressedTextures(final String codec) { return false; }

    /**
     * Uploads a set of already block-compressed frames as dedicated textures — the GPU samples the
     * BCn data directly, with no software decode and a quarter (BC3/BC7) or eighth (BC1) of the
     * VRAM of an RGBA8 frame set. This is the codec-cache counterpart to
     * {@link #uploadFrameTextures(ByteBuffer[], int)}; {@link #useFrameTexture(int)} and
     * {@link #texture()} select and expose frames the same way. Dimensions come from the active
     * {@link #setVideoFormat}. The default implementation reports unsupported so custom engines
     * remain source-compatible.
     * @param frameBlocks per-frame compressed block data, each {@code ceil(w/4)*ceil(h/4)*blockBytes} bytes
     * @param codec       the BCn codec id (e.g. {@code "BC7"})
     * @param blockBytes  bytes per 4x4 block (8 for BC1, 16 for BC3/BC7)
     * @return true when the engine accepted the compressed frame set
     */
    public boolean uploadCompressedFrames(final ByteBuffer[] frameBlocks, final String codec, final int blockBytes) { return false; }

    /**
     * Uploads a single-plane frame (BGRA, RGBA, RGB, GRAY, YUYV).
     * <p>
     * <b>Buffer retention contract (applies to all {@code upload} overloads):</b> engines may
     * consume the submitted buffers asynchronously, but must finish reading (or drop) them
     * before the second subsequent {@code upload} call on the same engine returns. Callers in
     * turn must not modify a submitted buffer until they have submitted two newer frames.
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
}
