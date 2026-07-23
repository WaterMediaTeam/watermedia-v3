package org.watermedia.api.media.engines;

import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * Graphics engine abstraction for uploading decoded video frames to GPU textures.
 * <p>
 * WATERMeDIA creates the texture, uploads pixel data, and exposes a handle.
 * The developer binds that handle in their rendering pipeline.
 * <p>
 * Engines are created through {@link org.watermedia.api.media.MediaAPI} factory methods and are
 * client-side only — construction throws on a server-side environment
 * ({@link HeadlessGFXEngine} is the sanctioned exception). Backends are OpenGL, Vulkan, software
 * surfaces and headless capture; thread-safety contracts depend on the backend — see each
 * implementation's javadoc.
 */
public abstract sealed class GFXEngine permits VKEngine, GLEngine, HeadlessGFXEngine, SWEngine {
    protected PixelFormat format;
    protected int width;
    protected int height;
    protected int bits = 8;

    protected GFXEngine() {
        this(true);
    }

    // SINGLE ENFORCEMENT POINT: EVERY ENGINE CONSTRUCTION PASSES THROUGH HERE. ONLY
    // HeadlessGFXEngine (SAME PACKAGE) MAY OPT OUT — IT EXISTS FOR SERVER-SIDE/CI USE.
    GFXEngine(final boolean clientOnly) {
        if (clientOnly) WaterMedia.checkIsClientSideOrThrow(this.getClass());
    }

    /**
     * Reconfigures the engine for a new video format (8-bit).
     * @param format pixel layout of incoming frames
     * @param width  frame width in pixels
     * @param height frame height in pixels
     */
    public void format(final PixelFormat format, final int width, final int height) {
        this.format(format, width, height, 8);
    }

    /**
     * Reconfigures the engine for a new video format with explicit bit depth.
     * <b>This resets all internal rendering state</b> — plane textures, PBOs, shaders,
     * and any buffered frame data are released and re-initialized on the next upload.
     * Must be called before the first upload and whenever resolution or pixel format changes.
     * @param format pixel layout of incoming frames
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @param bits sample precision: 8, 10, 12, 16, or 32 bits per component
     */
    public void format(final PixelFormat format, final int width, final int height, final int bits) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.bits = bits;
    }

    /** Frame width in pixels, or 0 if no format has been set. */
    public int width() { return this.width; }

    /** Frame height in pixels, or 0 if no format has been set. */
    public int height() { return this.height; }

    /** Pixel layout the engine is configured for, or null before the first {@link #format(PixelFormat, int, int, int)}. */
    public PixelFormat format() { return this.format; }

    /** Sample precision in bits per component (8, 10, 12, 16, or 32). */
    public int bits() { return this.bits; }

    /**
     * Returns the GPU-side handle for the final RGBA texture.
     * <p>
     * For OpenGL this is a {@code GLuint} texture name (fits in the lower 32 bits).
     * For Vulkan this would be a {@code VkImageView} handle ({@code uint64_t}).
     * @return texture handle, or 0 if no frame has been uploaded yet
     */
    public abstract long texture();

    /**
     * Whether the engine can consume frames in the given pixel format directly.
     * <p>
     * The frame producer calls this before choosing an upload path: raw formats the engine
     * declines are pre-converted (via the decoder's scaler) to a format it does accept —
     * typically {@code BGRA} — before {@code upload}; declined {@link PixelFormat#compressed()
     * compressed} formats fall back to the decode path. The default accepts every raw format and
     * declines compressed ones, so a backend opts into BCn sampling explicitly.
     * @param format the candidate pixel layout
     * @return true when the engine handles this format without external conversion
     */
    public boolean supports(final PixelFormat format) { return !format.compressed(); }

    /**
     * Whether this engine can keep a small animated image as one texture per frame.
     * Engines that report false keep receiving {@link #upload(ByteBuffer[], int[])} each frame.
     */
    public boolean preload() { return false; }

    /**
     * Uploads a complete frame set as dedicated textures.
     * <p>
     * This is an optional fast path for animated images that fit a VRAM budget. Each buffer holds
     * one frame in the active {@link #format()}: decoded pixels for raw layouts, or BCn block data
     * ({@code ceil(w/4) * ceil(h/4) * blockBytes} bytes, see {@link PixelFormat#blockBytes()}) for
     * compressed ones — the GPU then samples the blocks directly with no software decode.
     * <p>
     * Engines may upload the set progressively across render frames; in that case
     * {@link #frame(int)} must clamp to the already-uploaded prefix. The submitted buffers must
     * stay valid until the next {@code preload}, {@link #format(PixelFormat, int, int, int)} or
     * {@link #release()} — a progressive engine reads them from native memory across later frames.
     * The default implementation reports unsupported.
     * @param frames direct frame buffers in the current {@link #format()}
     * @param stride row stride in bytes, or 0 for tightly-packed rows (ignored for compressed formats)
     * @return true when the engine accepted the frame set
     */
    public boolean preload(final ByteBuffer[] frames, final int stride) { return false; }

    /**
     * Selects which preloaded frame texture is exposed by {@link #texture()}.
     */
    public void frame(final int index) {}

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
    public int alignment() { return 0; }

    /**
     * Notifies the engine that a previously uploaded plane buffer is about to be freed by its owner.
     * <p>
     * Zero-copy backends may hold GPU-side imports of the buffer's host memory (see
     * {@link #alignment()}); they must drop those imports here — draining any pending GPU reads
     * first — because destroying an import after its host memory was freed crashes the driver.
     * Must be called from the same thread that calls {@code upload}. The default implementation
     * does nothing, so copy-through engines are unaffected.
     * @param buffer the direct buffer the producer is about to free
     */
    public void release(final ByteBuffer buffer) {}

    /**
     * Uploads one decoded frame as its ordered plane set.
     * <p>
     * The plane count must match the active {@link #format() video format}: one entry for
     * packed layouts (BGRA, RGBA, RGB, GRAY, YUYV), two for semi-planar (NV12/NV21), three for
     * planar YUV (Y, U, V) and four for planar YUV plus alpha (see {@link PixelFormat#planes()}).
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
