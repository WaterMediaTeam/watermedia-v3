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
 * Engines are created through {@link MediaAPI} factory methods and are
 * client-side only — construction throws on a server-side environment, headless capture
 * included. Backends are OpenGL, Vulkan, software surfaces and headless capture; thread-safety
 * contracts depend on the backend — see each implementation's javadoc. The owning
 * {@link MediaPlayer} drives formats and uploads; the public surface only queries the engine.
 */
public abstract sealed class GFXEngine permits VKEngine, GLEngine, HeadlessGFXEngine, SWEngine {
    protected PixelFormat format;
    protected int width;
    protected int height;
    protected int bits = 8;

    // SINGLE ENFORCEMENT POINT: EVERY ENGINE CONSTRUCTION PASSES THROUGH HERE, HEADLESS CAPTURE INCLUDED.
    protected GFXEngine() {
        WaterMedia.checkIsClientSideOrThrow(this.getClass());
    }

    // RECONFIGURES FOR AN 8-BIT FORMAT; SEE THE FOUR-ARGUMENT OVERLOAD
    void format(final PixelFormat format, final int width, final int height) {
        this.format(format, width, height, 8);
    }

    // RESETS PLANE TEXTURES, UPLOAD BUFFERS AND BUFFERED FRAMES FOR A NEW FORMAT; CALLED BEFORE THE FIRST UPLOAD AND ON
    // EVERY RESOLUTION OR LAYOUT CHANGE. bits IS THE SAMPLE PRECISION (8, 10, 12, 16 OR 32); COMPILED PROGRAMS MAY BE CACHED
    void format(final PixelFormat format, final int width, final int height, final int bits) {
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
    // SINCE DESTROYING AN IMPORT AFTER ITS HOST MEMORY IS GONE CRASHES THE DRIVER. CALLED FROM THE upload THREAD
    void release(final ByteBuffer buffer) {}

    // UPLOADS ONE FRAME AS ITS ORDERED PLANES (ONE PER PixelFormat.planes(); STRIDES IN BYTES, 0 = TIGHT). THE ENGINE MAY
    // READ THE BUFFERS UNTIL THE SECOND LATER upload RETURNS AND MAY RETAIN THE ARRAYS, SO CALLERS PASS FRESH ONES
    abstract void upload(final ByteBuffer[] planes, final int[] strides);

    /**
     * Releases all GPU resources. The engine is unusable after this call.
     */
    public abstract void release();
}
