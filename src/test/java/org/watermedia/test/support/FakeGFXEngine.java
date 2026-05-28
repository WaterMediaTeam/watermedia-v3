package org.watermedia.test.support;

import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * In-memory {@link GFXEngine} stand-in for tests that need to drive a
 * {@code TxMediaPlayer} without a real OpenGL context. Captures the most
 * recently uploaded frame, the active texture index, and the number of
 * uploads so test bodies can assert on the playback pipeline directly.
 *
 * <p>Thread-safe: every mutable field is volatile because the player's
 * lifecycle thread writes them while the test thread reads them.
 */
public final class FakeGFXEngine extends GFXEngine {
    private volatile long uploadCount;
    private volatile int activeFrameTexture;
    private volatile ByteBuffer[] preloaded;
    private volatile ByteBuffer lastUpload;
    private volatile boolean supportFrameTextures;
    private volatile boolean released;

    public FakeGFXEngine() {
        this(false);
    }

    public FakeGFXEngine(final boolean supportFrameTextures) {
        this.supportFrameTextures = supportFrameTextures;
    }

    @Override
    public long texture() { return 1L; }

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

    public long uploadCount() { return this.uploadCount; }
    public int activeFrameTexture() { return this.activeFrameTexture; }
    public ByteBuffer[] preloadedFrames() { return this.preloaded; }
    public ByteBuffer lastUpload() { return this.lastUpload; }
    public PixelFormat lastFormat() { return this.pixelFormat; }
    public boolean released() { return this.released; }
}
