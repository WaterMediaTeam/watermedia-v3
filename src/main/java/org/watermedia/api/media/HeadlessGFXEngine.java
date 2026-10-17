package org.watermedia.api.media;

import java.nio.ByteBuffer;

/**
 * Headless {@link GFXEngine} that records frames into memory instead of a GPU.
 * <p>
 * There is no OpenGL or Vulkan context: uploads are captured, not rendered. It lets a
 * {@link MediaPlayer} run its full decode/upload pipeline where no display is available — CI,
 * headless validation, image processing — and lets callers introspect what the pipeline pushed
 * ({@link #uploadCount()}, {@link #lastUpload()}, {@link #format()}, {@link #activeFrame()}). It
 * needs no GPU but, like every engine and media player, it only exists on a client session.
 * <p>
 * {@link #texture()} returns a non-zero sentinel once the first frame is captured (0 before),
 * matching the base contract; there is no real texture to bind. Every mutable field is
 * {@code volatile} because the player's lifecycle thread writes them while another thread may read them.
 */
public final class HeadlessGFXEngine extends GFXEngine {
    // FIXED NON-ZERO HANDLE — NO REAL GPU TEXTURE EXISTS, BUT texture() MUST READ AS "A FRAME IS PRESENT"
    private static final long SENTINEL_TEXTURE = 1L;

    private final boolean preload;
    private volatile long uploadCount;
    private volatile int activeFrame;
    private volatile ByteBuffer[] preloaded;
    private volatile ByteBuffer lastUpload;
    private volatile boolean framed;
    private volatile boolean released;

    HeadlessGFXEngine() {
        this(false);
    }

    HeadlessGFXEngine(final boolean preload) {
        this.preload = preload;
    }

    @Override
    public long texture() { return this.released || !this.framed ? 0L : SENTINEL_TEXTURE; }

    @Override
    public boolean preload() { return this.preload; }

    @Override
    boolean preload(final ByteBuffer[] frames, final int stride) {
        // CLONE ON STORE — THE ARRAY IS PUBLIC API, DON'T ALIAS THE PRODUCER'S ARRAY
        this.preloaded = frames != null ? frames.clone() : null;
        this.framed = true;
        return true;
    }

    @Override
    void frame(final int index) {
        this.activeFrame = index;
    }

    @Override
    void upload(final ByteBuffer[] planes, final int[] strides) {
        this.lastUpload = planes.length > 0 ? planes[0] : null;
        this.uploadCount++;
        this.framed = true;
    }

    @Override
    public void release() {
        this.released = true;
    }

    /** Number of {@code upload} calls received so far. */
    public long uploadCount() { return this.uploadCount; }

    /** Index of the preloaded frame the player last selected. */
    public int activeFrame() { return this.activeFrame; }

    /** Frame set the player last preloaded, or null. */
    public ByteBuffer[] preloadedFrames() { return this.preloaded; }

    /** Y (or sole) plane of the most recent {@code upload}, or null. */
    public ByteBuffer lastUpload() { return this.lastUpload; }

    /** Whether {@link #release()} has been called. */
    public boolean released() { return this.released; }
}
