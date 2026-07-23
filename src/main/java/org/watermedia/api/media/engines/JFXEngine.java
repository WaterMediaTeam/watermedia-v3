package org.watermedia.api.media.engines;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.ByteBuffer;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * {@link SWEngine} that renders frames into a JavaFX {@link Image}.
 * <p>
 * The engine's direct BGRA buffer backs a {@link PixelBuffer} with no copy, so a decoded frame is
 * visible to JavaFX the moment {@link PixelBuffer#updateBuffer} runs. Bind {@link #image()} to an
 * {@code ImageView}; re-read it after {@code onFrame} because a resolution change replaces the image.
 * <p>
 * Requires a running JavaFX toolkit: {@code updateBuffer} is marshalled to the Application Thread via
 * {@link Platform#runLater}. The BGRA data is treated as premultiplied
 * ({@link PixelFormat#getByteBgraPreInstance()}); video frames are opaque, so this is exact.
 */
public final class JFXEngine extends SWEngine {
    private volatile WritableImage image;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private volatile boolean loggedPresent;

    public JFXEngine(final Runnable onFrame) {
        super(onFrame);
    }

    /** The current frame image; changes on a resolution change, so re-read it after each frame. */
    public Image image() {
        return this.image;
    }

    @Override
    protected void allocSurface(final int width, final int height, final ByteBuffer bgra) {
        try {
            this.pixelBuffer = new PixelBuffer<>(width, height, bgra, PixelFormat.getByteBgraPreInstance());
            this.image = new WritableImage(this.pixelBuffer);
        } catch (final RuntimeException t) {
            LOGGER.error("JFXEngine: failed to allocate the {}x{} surface", width, height, t);
        }
    }

    /**
     * Re-marks the current frame dirty. The software Prism pulse only repaints on a fresh
     * {@code updateBuffer}, so a static single-frame image needs this pumped to stay on screen.
     */
    public void repaint() {
        final PixelBuffer<ByteBuffer> pb = this.pixelBuffer;
        if (pb != null) Platform.runLater(() -> pb.updateBuffer(b -> null));
    }

    @Override
    protected void present() {
        final PixelBuffer<ByteBuffer> pb = this.pixelBuffer;
        if (pb == null) return;
        if (!this.loggedPresent) {
            this.loggedPresent = true;
            LOGGER.info("JFXEngine: first frame presented ({}x{})", this.width, this.height);
        }
        // updateBuffer MUST RUN ON THE FX THREAD; null MARKS THE WHOLE BUFFER DIRTY. THE NEXT DECODE-THREAD
        // WRITE INTO bgra MAY RACE PRISM'S READ — ACCEPTED: OPAQUE VIDEO FRAMES TEAR AT WORST ONE PULSE.
        Platform.runLater(() -> pb.updateBuffer(b -> null));
    }

    @Override
    protected void disposeSurface() {
        this.image = null;
        this.pixelBuffer = null;
    }
}
