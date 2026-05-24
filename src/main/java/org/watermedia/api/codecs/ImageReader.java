package org.watermedia.api.codecs;

import org.watermedia.api.util.ColorSpace;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * ByteBuffer-backed, frame-by-frame image decoder.
 *
 * <p>An {@code ImageReader} is constructed by {@link CodecsAPI#decodeImage(ByteBuffer)} once the
 * format has been identified from the leading magic bytes. The {@code ByteBuffer} passed to a
 * concrete subclass is positioned <strong>past the format header</strong> — the reader assumes
 * the format is correct and starts parsing the body directly. Headers are never re-validated.
 *
 * <p>The reader pulls from the underlying buffer only the bytes it needs to produce the next
 * frame. After {@link #next()} returns, the decoded frame is available as a direct
 * {@link ByteBuffer} laid out in {@link #pixelFormat()}.
 * The buffer is owned by the reader and reused across calls; if the caller needs to retain a
 * previous frame, it must copy the bytes out before the next {@code next()} call.
 */
public abstract class ImageReader implements Closeable {
    protected final ByteBuffer data;
    protected ByteBuffer currentFrame;
    protected long currentDelay;

    protected ImageReader(final ByteBuffer data) {
        this.data = data.slice();
    }

    /** Format short name (e.g. {@code "PNG"}, {@code "GIF"}). */
    public String name() {
        final String simpleName = this.getClass().getSimpleName();
        return simpleName.endsWith("Reader")
                ? simpleName.substring(0, simpleName.length() - "Reader".length())
                : simpleName;
    }

    /** Image width in pixels. Available immediately after construction. */
    public abstract int width();

    /** Image height in pixels. Available immediately after construction. */
    public abstract int height();

    /** Pixel layout of the buffer returned by {@link #next()}. */
    public abstract ColorSpace pixelFormat();

    /**
     * Pre-generated animation summary computed during construction. Animated formats walk the
     * container chunk structure (without decoding pixel data) to populate this; static formats
     * return {@link ImageData.Scan#STATIC}.
     */
    public abstract ImageData.Scan scan();

    /**
     * Loop count for animated images.
     * {@link ImageData#NO_REPEAT} (-1) for single-shot, {@link ImageData#REPEAT_FOREVER} (0) for
     * infinite loop, or a positive integer for explicit repeat count.
     */
    public int loopCount() { return this.scan().loopCount(); }

    /** Total frame count if known, or {@code -1} if streaming/unknown. */
    public int frameCount() { return this.scan().frameCount(); }

    /**
     * Total playback duration in milliseconds. Static images return {@code 0}.
     */
    public long duration() { return this.scan().duration(); }

    /** Per-frame delays in milliseconds. Static images return a single {@code 0}. */
    public long[] delays() { return this.scan().delays(); }

    /**
     * Average FPS when both duration and frame count are known. Animated image formats may be
     * variable-frame-rate, so callers that need exact timing must use {@link #delays()}.
     */
    public float averageFps() {
        final long duration = this.duration();
        final int frames = this.frameCount();
        return duration > 0L && frames > 1 ? (frames * 1000f) / duration : 0f;
    }

    /** True when per-frame delays may differ. */
    public boolean variableFrameRate() {
        return true;
    }

    /** Normalized image metadata. Empty readers return {@link ImageMetadata#EMPTY}. */
    public ImageMetadata metadata() {
        return ImageMetadata.EMPTY;
    }

    /**
     * Decodes every available frame and returns a detached {@link ImageData} instance.
     * Each returned frame is copied because concrete readers may reuse their internal buffer.
     */
    public ImageData readAll() throws IOException {
        final List<ByteBuffer> frames = new ArrayList<>();
        final List<Long> frameDelays = new ArrayList<>();
        while (this.hasNext()) {
            final ByteBuffer decoded = this.next();
            final int savedPos = decoded.position();
            final ByteBuffer copy = ByteBuffer.allocateDirect(decoded.remaining()).order(decoded.order());
            copy.put(decoded);
            copy.flip();
            decoded.position(savedPos);
            frames.add(copy);
            frameDelays.add(this.currentDelay);
        }
        if (frames.isEmpty()) {
            throw new EOFException("No frames decoded by " + this.name());
        }
        final long[] delayArray = new long[frameDelays.size()];
        long total = 0L;
        for (int i = 0; i < delayArray.length; i++) {
            delayArray[i] = frameDelays.get(i);
            total += delayArray[i];
        }
        return new ImageData(frames.toArray(ByteBuffer[]::new), this.width(), this.height(),
                delayArray, total, this.loopCount());
    }

    /**
     * Returns whether another decoded frame can be produced. Implementations may validate and
     * skip non-frame container records while answering this.
     */
    public abstract boolean hasNext() throws IOException;

    /**
     * Decodes the next frame from the underlying buffer into the internal direct buffer.
     *
     * @return the decoded frame buffer
     * @throws IOException on I/O error or malformed bitstream
     */
    public abstract ByteBuffer next() throws IOException;

    /**
     * Legacy alias for callers still using the old pull contract.
     */
    @Deprecated
    public boolean nextFrame() throws IOException {
        if (!this.hasNext()) return false;
        this.next();
        return true;
    }

    /** Legacy alias for the last decoded frame. */
    @Deprecated
    public ByteBuffer frame() {
        return this.currentFrame;
    }

    /** Legacy alias for the delay (ms) of the most recently decoded frame. */
    @Deprecated
    public long frameDelay() {
        return this.currentDelay;
    }

    @Override
    public void close() throws IOException {
        // ByteBuffer-backed readers do not own external resources.
    }
}
