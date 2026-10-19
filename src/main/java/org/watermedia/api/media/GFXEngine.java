package org.watermedia.api.media;

import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * Graphics engine abstraction for uploading decoded video frames to GPU textures.
 * <p>
 * WATERMeDIA creates the texture, uploads pixel data, and exposes a handle.
 * The developer binds that handle in their rendering pipeline.
 * <p>
 * Engines are created through {@link MediaAPI} factory methods and are client-side only —
 * construction throws on a server-side environment, headless capture included. Backends are
 * OpenGL, Vulkan, software surfaces and headless capture. The owning {@link MediaPlayer} drives
 * formats and uploads from one producer thread; the public surface only queries the engine.
 * <p>
 * Every backend shares one lifecycle contract: {@link #release()} is idempotent, may run on any
 * thread but never concurrently with a format or upload, and leaves the engine inert
 * ({@link #texture()} returns 0, formats are refused and uploads are dropped). Uploads never
 * throw: a backend that hits a driver failure logs it, drops the frame and keeps the last texture
 * it published. Software and headless backends expose a non-zero sentinel texture because they
 * have no GPU handle.
 */
public abstract sealed class GFXEngine permits VKEngine, GLEngine, HeadlessGFXEngine, SWEngine {
    // NON-ZERO HANDLE FOR BACKENDS WITHOUT A GPU TEXTURE (SOFTWARE SURFACES, HEADLESS CAPTURE): NEVER A VALID GL NAME
    static final long SENTINEL_TEXTURE = -1L;

    // CONFIGURED LAYOUT, WRITTEN BY THE PRODUCER (THE RENDER THREAD IN GL) AND READ FROM ANY HOST THREAD
    volatile PixelFormat format;
    volatile int width;
    volatile int height;
    volatile int bits = 8;
    volatile boolean released;

    // SINGLE ENFORCEMENT POINT: EVERY ENGINE CONSTRUCTION PASSES THROUGH HERE, HEADLESS CAPTURE INCLUDED.
    GFXEngine() {
        WaterMedia.checkIsClientSideOrThrow(this.getClass());
    }

    // RECONFIGURES FOR AN 8-BIT FORMAT; SEE THE FOUR-ARGUMENT OVERLOAD
    final boolean format(final PixelFormat format, final int width, final int height) {
        return this.format(format, width, height, 8);
    }

    // RESETS PLANE TEXTURES, UPLOAD BUFFERS AND BUFFERED FRAMES FOR A NEW LAYOUT; CALLED BEFORE THE FIRST UPLOAD AND ON
    // EVERY RESOLUTION OR LAYOUT CHANGE. FALSE (RELEASED, EMPTY SIZE, UNSUPPORTED FORMAT/BITS) LEAVES THE ENGINE AS IT WAS
    final boolean format(final PixelFormat format, final int width, final int height, final int bits) {
        if (this.released || width <= 0 || height <= 0 || !this.supports(format, bits)) return false;
        return this.apply(format, width, height, bits);
    }

    // APPLIES A LAYOUT THE BASE ALREADY ACCEPTED; FALSE WHEN THE BACKEND CANNOT BACK IT (E.G. A SURFACE TOO LARGE).
    // THE DEFAULT ONLY PUBLISHES THE FIELDS
    boolean apply(final PixelFormat format, final int width, final int height, final int bits) {
        this.publish(format, width, height, bits);
        return true;
    }

    // PUBLISHES THE LAYOUT ONCE THE BACKEND APPLIED IT (GL DOES SO ON ITS RENDER THREAD, THE REST SYNCHRONOUSLY)
    final void publish(final PixelFormat format, final int width, final int height, final int bits) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.bits = bits;
    }

    /** Frame width in pixels, or 0 if no format has been set. */
    public int width() { return this.width; }

    /** Frame height in pixels, or 0 if no format has been set. */
    public int height() { return this.height; }

    /** Pixel layout the engine is configured for, or null before the player configures the first format. */
    public PixelFormat format() { return this.format; }

    /** Sample precision in bits per component (8, 10, 12, 16, or 32 for float); 8 before the first format. */
    public int bits() { return this.bits; }

    /** Whether {@link #release()} already ran; a released engine refuses formats and drops uploads. */
    public final boolean released() { return this.released; }

    /**
     * Returns the GPU-side handle for the final RGBA texture.
     * <p>
     * For OpenGL this is a {@code GLuint} texture name (fits in the lower 32 bits), for Vulkan a
     * {@code VkImageView} handle ({@code uint64_t}); software and headless backends return a fixed
     * non-zero sentinel once a frame is present, so hosts only test the handle against 0.
     * @return texture handle, or 0 if no frame has been uploaded yet or the engine was released
     */
    public abstract long texture();

    /** Whether the engine consumes 8-bit frames in {@code format} directly; see {@link #supports(PixelFormat, int)}. */
    public final boolean supports(final PixelFormat format) { return this.supports(format, 8); }

    /**
     * Whether the engine can consume frames in the given pixel layout and sample precision directly.
     * <p>
     * The frame producer calls this before choosing an upload path: layouts the engine declines are
     * pre-converted (via the decoder's scaler) to one it does accept — typically 8-bit {@code BGRA} —
     * before {@code upload}; declined {@link PixelFormat#compressed() compressed} formats fall back
     * to the decode path. The default accepts every raw layout at 8, 10, 12, 16 or 32 (float) bits
     * and declines compressed ones, so a backend opts into BCn sampling explicitly.
     * @param format the candidate pixel layout
     * @param bits   bits per component the frames carry
     * @return true when the engine handles this layout without external conversion
     */
    public boolean supports(final PixelFormat format, final int bits) {
        return format != null && !format.compressed() && (bits == 8 || bits == 10 || bits == 12 || bits == 16 || bits == 32);
    }

    /**
     * Whether this engine can keep a small animated image as one texture per frame.
     * Engines that report false keep receiving one upload per frame.
     */
    public boolean preload() { return false; }

    // OPTIONAL FAST PATH: ONE TEXTURE PER FRAME OF THE SET, IN format() (RAW PIXELS OR BCn BLOCKS); THE BUFFERS MUST OUTLIVE
    // THE NEXT preload/format/release AND A PROGRESSIVE ENGINE CLAMPS frame() TO THE UPLOADED PREFIX. FALSE = UNSUPPORTED
    boolean preload(final ByteBuffer[] frames, final int stride) { return false; }

    // SELECTS THE PRELOADED FRAME TEXTURE EXPOSED BY texture()
    void frame(final int index) {}

    // BASE-ADDRESS ALIGNMENT ZERO-COPY BACKENDS NEED FOR THE upload PLANES (E.G. minImportedHostPointerAlignment); THE
    // PRODUCER THEN ALLOCATES ALIGNED, PADDED BUFFERS. 0 = NO CONSTRAINT (STAGING COPY)
    int alignment() { return 0; }

    // A PLANE BUFFER IS ABOUT TO BE FREED: ZERO-COPY BACKENDS DROP ITS GPU IMPORT HERE, DRAINING PENDING READS FIRST,
    // SINCE DESTROYING AN IMPORT AFTER ITS HOST MEMORY IS GONE CRASHES THE DRIVER. NEVER CONCURRENT WITH upload
    void release(final ByteBuffer buffer) {}

    // UPLOADS ONE FRAME AS ITS ORDERED PLANES (ONE PER PixelFormat.planes(); STRIDES IN BYTES, 0 = TIGHT): READS [position,
    // limit) WITHOUT MOVING position AND MAY KEEP READING UNTIL THE SECOND LATER upload RETURNS, SO CALLERS PASS FRESH ARRAYS
    final void upload(final ByteBuffer[] planes, final int[] strides) {
        if (planes == null || strides == null || strides.length < planes.length)
            throw new IllegalArgumentException("Every plane needs a buffer and a stride");
        for (final ByteBuffer plane: planes) if (plane == null) throw new IllegalArgumentException("A plane buffer cannot be null");
        if (this.released) return;
        this.push(planes, strides);
    }

    // BACKEND UPLOAD (SEE upload); IMPLEMENTATIONS LOG AND DROP ON FAILURE INSTEAD OF THROWING
    abstract void push(final ByteBuffer[] planes, final int[] strides);

    /**
     * Releases all GPU resources. The engine is unusable after this call; later calls are no-ops.
     * Never call it concurrently with an upload or a format change.
     */
    public final void release() {
        // THE TEST-AND-SET IS ATOMIC SO TWO CONCURRENT RELEASES NEVER TEAR DOWN TWICE
        synchronized (this) {
            if (this.released) return;
            this.released = true;
        }
        this.destroy();
    }

    // BACKEND TEARDOWN, RUN EXACTLY ONCE AFTER released IS SET; GL DEFERS ITS GPU WORK TO THE RENDER THREAD
    abstract void destroy();
}
