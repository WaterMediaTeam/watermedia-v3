package org.watermedia.api.codecs;

import org.watermedia.api.util.ColorSpace;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Streaming, frame-by-frame image encoder.
 *
 * <p>A concrete {@code ImageWriter} takes ownership of an {@link OutputStream} and emits the
 * format's container header on construction (or lazily on the first {@link #writeFrame}), then
 * appends one encoded frame per call. {@link #close()} writes any trailers/footers and closes
 * the wrapped {@code OutputStream}.
 *
 * <p>The {@link ByteBuffer} passed to {@link #writeFrame} must contain exactly
 * {@code width * height} pixels laid out in {@link #pixelFormat()} order.
 */
public abstract class ImageWriter implements Closeable {
    protected final OutputStream out;
    protected final int width;
    protected final int height;
    protected final ColorSpace pixelFormat;

    protected ImageWriter(final OutputStream out, final int width, final int height, final ColorSpace pixelFormat) {
        if (out == null) throw new IllegalArgumentException("OutputStream is null");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        if (pixelFormat == null) throw new IllegalArgumentException("pixelFormat is null");
        this.out = out;
        this.width = width;
        this.height = height;
        this.pixelFormat = pixelFormat;
    }

    /** Image width in pixels. */
    public final int width() { return this.width; }

    /** Image height in pixels. */
    public final int height() { return this.height; }

    /** Pixel layout the writer expects for {@link #writeFrame} inputs. */
    public final ColorSpace pixelFormat() { return this.pixelFormat; }

    /**
     * Encodes one frame and appends it to the wrapped {@link OutputStream}.
     * The buffer must hold {@code width * height} pixels in {@link #pixelFormat()}.
     *
     * @throws IOException on I/O error or unsupported frame configuration
     */
    public abstract void writeFrame(ByteBuffer frame) throws IOException;

    /**
     * Encodes one frame with an associated delay (for animated formats). Static formats may
     * ignore {@code delayMs}. Defaults to {@link #writeFrame(ByteBuffer)}.
     */
    public void writeFrame(final ByteBuffer frame, final long delayMs) throws IOException {
        this.writeFrame(frame);
    }

    /** Writes any trailers/footers and closes the wrapped {@link OutputStream}. */
    @Override
    public void close() throws IOException {
        this.out.close();
    }
}
