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
public abstract sealed class GFXEngine permits VKEngine, GLEngine, HeadlessGFXEngine, SWEngine {
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

    /** Pixel layout the engine is configured for, or null before the first {@link #setVideoFormat}. */
    public PixelFormat format() { return this.pixelFormat; }

    /** Sample precision in bits per component (8, 10, 12, 16, or 32). */
    public int bitsPerComponent() { return this.bitsPerComponent; }

    /**
     * Returns the GPU-side handle for the final RGBA texture.
     * <p>
     * For OpenGL this is a {@code GLuint} texture name (fits in the lower 32 bits).
     * For Vulkan this would be a {@code VkImageView} handle ({@code uint64_t}).
     * @return texture handle, or 0 if no frame has been uploaded yet
     */
    public abstract long texture();

    /**
     * Whether the engine can upload frame planes in the given pixel format directly.
     * <p>
     * The frame producer calls this before choosing an upload path: formats the engine declines are
     * pre-converted (via the decoder's scaler) to a format it does accept — typically {@code BGRA} —
     * before {@code upload}. The default accepts every format, so existing engines are unaffected;
     * a backend that has not yet implemented (for example) GPU YUV conversion can decline the planar
     * formats and still receive correct frames.
     * @param format the candidate pixel layout
     * @return true when the engine handles this format without external conversion
     */
    public boolean supportsFormat(final PixelFormat format) { return true; }

    /**
     * Whether this engine can keep a small animated image as one texture per frame.
     * Engines that return false keep using {@link #upload(ByteBuffer[], int[])} each frame.
     */
    public boolean supportsFrameTextures() { return false; }

    /**
     * Uploads a complete frame set as dedicated textures.
     * <p>
     * This is an optional fast path for animated images that fit a VRAM budget. The default
     * implementation reports unsupported so custom engines remain source-compatible.
     * Engines may upload the set progressively across render frames; in that case
     * {@link #useFrameTexture(int)} must clamp to the already-uploaded prefix. The submitted buffers
     * must stay valid until the next {@code uploadFrameTextures}, {@link #setVideoFormat} or
     * {@link #release()} — a progressive engine reads them from native memory across later frames.
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
     * Engines that return false keep receiving decoded pixels through {@link #upload(ByteBuffer[], int[])}.
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
     * Byte alignment the engine needs the {@code upload} plane buffers to satisfy, or 0 when the
     * engine imposes no constraint.
     * <p>
     * Backends that import the decoder's host memory directly into the GPU (zero-copy) can only do
     * so when the buffer's base address is aligned to a device-specific boundary
     * (e.g. Vulkan's {@code minImportedHostPointerAlignment}). Returning a non-zero value asks the
     * frame producer to allocate its plane buffers aligned to (and sized up to a multiple of) this
     * boundary so the zero-copy path can engage; engines that copy through staging return 0.
     * @return required base-address alignment in bytes, or 0 for no constraint
     */
    public int requiredBufferAlignment() { return 0; }

    /**
     * Notifies the engine that a previously uploaded plane buffer is about to be freed by its owner.
     * <p>
     * Zero-copy backends may hold GPU-side imports of the buffer's host memory (see
     * {@link #requiredBufferAlignment()}); they must drop those imports here — draining any pending
     * GPU reads first — because destroying an import after its host memory was freed crashes the
     * driver. Must be called from the same thread that calls {@code upload}. The default
     * implementation does nothing, so copy-through engines are unaffected.
     * @param buffer the direct buffer the producer is about to free
     */
    public void releaseBuffer(final ByteBuffer buffer) {}

    /**
     * Uploads one decoded frame as its ordered plane set.
     * <p>
     * The plane count must match the active {@link #setVideoFormat video format}: one entry for
     * packed layouts (BGRA, RGBA, RGB, GRAY, YUYV), two for semi-planar (NV12/NV21), three for
     * planar YUV (Y, U, V) and four for planar YUV plus alpha.
     * <p>
     * <b>Buffer retention contract:</b> engines may consume the submitted buffers asynchronously,
     * but must finish reading (or drop) them before the second subsequent {@code upload} call on
     * the same engine returns. Callers in turn must not modify a submitted buffer until they have
     * submitted two newer frames, and must pass freshly allocated arrays on every call — the
     * engine may retain them until the frame is consumed.
     * @param planes  direct ByteBuffers pointing to native pixel data, one per plane
     * @param strides per-plane row strides in <b>bytes</b>, or 0 for tightly-packed rows
     */
    public abstract void upload(final ByteBuffer[] planes, final int[] strides);

    /**
     * Releases all GPU resources. The engine is unusable after this call.
     */
    public abstract void release();
}
