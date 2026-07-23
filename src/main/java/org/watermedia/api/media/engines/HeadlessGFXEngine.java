package org.watermedia.api.media.engines;

import java.nio.ByteBuffer;

/**
 * Headless {@link GFXEngine} that records frames into memory instead of a GPU.
 * <p>
 * There is no OpenGL or Vulkan context: uploads are captured, not rendered. It lets a
 * {@link org.watermedia.api.media.players.MediaPlayer} run its full decode/upload pipeline where no
 * display is available — server-side probing, CI, or headless validation — and lets callers
 * introspect what the pipeline pushed ({@link #uploadCount()}, {@link #lastUpload()},
 * {@link #format()}, {@link #activeFrameTexture()}).
 * <p>
 * {@link #texture()} returns a non-zero sentinel once the first frame is captured (0 before),
 * matching the base contract; there is no real texture to bind. Every mutable field is
 * {@code volatile} because the player's lifecycle thread writes them while another thread may read them.
 */
public final class HeadlessGFXEngine extends GFXEngine {
    // FIXED NON-ZERO HANDLE — NO REAL GPU TEXTURE EXISTS, BUT texture() MUST READ AS "A FRAME IS PRESENT"
    private static final long SENTINEL_TEXTURE = 1L;

    private final boolean supportsFrameTextures;
    private volatile long uploadCount;
    private volatile int activeFrameTexture;
    private volatile ByteBuffer[] preloaded;
    private volatile ByteBuffer lastUpload;
    private volatile boolean framed;
    private volatile boolean released;

    public HeadlessGFXEngine() {
        this(false);
    }

    public HeadlessGFXEngine(final boolean supportsFrameTextures) {
        this.supportsFrameTextures = supportsFrameTextures;
    }

    @Override
    public long texture() { return this.released || !this.framed ? 0L : SENTINEL_TEXTURE; }

    @Override
    public boolean supportsFrameTextures() { return this.supportsFrameTextures; }

    @Override
    public boolean uploadFrameTextures(final ByteBuffer[] frames, final int stride) {
        // CLONE ON STORE — THE ARRAY IS PUBLIC API, DON'T ALIAS THE PRODUCER'S ARRAY
        this.preloaded = frames != null ? frames.clone() : null;
        this.framed = true;
        return true;
    }

    @Override
    public void useFrameTexture(final int frameIndex) {
        this.activeFrameTexture = frameIndex;
    }

    @Override
    public void upload(final ByteBuffer[] planes, final int[] strides) {
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

    /** Index last selected through {@link #useFrameTexture(int)}. */
    public int activeFrameTexture() { return this.activeFrameTexture; }

    /** Frame set last handed to {@link #uploadFrameTextures(ByteBuffer[], int)}, or null. */
    public ByteBuffer[] preloadedFrames() { return this.preloaded; }

    /** Y (or sole) plane of the most recent {@code upload}, or null. */
    public ByteBuffer lastUpload() { return this.lastUpload; }

    /** Whether {@link #release()} has been called. */
    public boolean released() { return this.released; }
}
