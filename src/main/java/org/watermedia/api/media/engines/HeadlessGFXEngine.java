package org.watermedia.api.media.engines;

import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * Headless {@link GFXEngine} that records frames into memory instead of a GPU.
 * <p>
 * There is no OpenGL or Vulkan context: uploads are captured, not rendered. It lets a
 * {@link org.watermedia.api.media.players.MediaPlayer} run its full decode/upload pipeline where no
 * display is available — server-side probing, CI, or headless validation — and lets callers
 * introspect what the pipeline pushed ({@link #uploadCount()}, {@link #lastUpload()},
 * {@link #lastFormat()}, {@link #activeFrameTexture()}).
 * <p>
 * {@link #texture()} returns a fixed non-zero sentinel; there is no real texture to bind. Every
 * mutable field is {@code volatile} because the player's lifecycle thread writes them while another
 * thread may read them.
 */
public final class HeadlessGFXEngine extends GFXEngine {
    // FIXED NON-ZERO HANDLE — NO REAL GPU TEXTURE EXISTS, BUT texture() MUST READ AS "A FRAME IS PRESENT"
    private static final long SENTINEL_TEXTURE = 1L;

    private volatile long uploadCount;
    private volatile int activeFrameTexture;
    private volatile ByteBuffer[] preloaded;
    private volatile ByteBuffer lastUpload;
    private volatile boolean supportFrameTextures;
    private volatile boolean released;

    public HeadlessGFXEngine() {
        this(false);
    }

    public HeadlessGFXEngine(final boolean supportFrameTextures) {
        this.supportFrameTextures = supportFrameTextures;
    }

    @Override
    public long texture() { return SENTINEL_TEXTURE; }

    @Override
    public boolean supportsFrameTextures() { return this.supportFrameTextures; }

    @Override
    public boolean uploadFrameTextures(final ByteBuffer[] frames, final int stride) {
        this.preloaded = frames;
        return true;
    }

    @Override
    public void useFrameTexture(final int frameIndex) {
        this.activeFrameTexture = frameIndex;
    }

    @Override
    public void upload(final ByteBuffer buffer, final int stride) {
        this.lastUpload = buffer;
        this.uploadCount++;
    }

    @Override
    public void upload(final ByteBuffer y, final int ys, final ByteBuffer uv, final int us) {
        this.lastUpload = y;
        this.uploadCount++;
    }

    @Override
    public void upload(final ByteBuffer y, final int ys, final ByteBuffer u, final int us, final ByteBuffer v, final int vs) {
        this.lastUpload = y;
        this.uploadCount++;
    }

    @Override
    public void upload(final ByteBuffer y, final int ys, final ByteBuffer u, final int us, final ByteBuffer v, final int vs, final ByteBuffer a, final int as) {
        this.lastUpload = y;
        this.uploadCount++;
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

    /** Pixel format set by the last {@link #setVideoFormat}, or null. */
    public PixelFormat lastFormat() { return this.pixelFormat; }

    /** Whether {@link #release()} has been called. */
    public boolean released() { return this.released; }
}
