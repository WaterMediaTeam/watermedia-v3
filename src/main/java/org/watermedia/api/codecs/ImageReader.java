package org.watermedia.api.codecs;

import org.watermedia.api.util.PixelFormat;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ByteBuffer-backed, frame-by-frame image decoder.
 *
 * <p>An {@code ImageReader} is constructed by {@link CodecsAPI#reader(ByteBuffer)} once the
 * format has been identified from the leading magic bytes. The {@code ByteBuffer} passed to a
 * concrete subclass is positioned <strong>past the format header</strong> — the reader assumes
 * the format is correct and starts parsing the body directly. Headers are never re-validated.
 *
 * <p>The reader pulls from the underlying buffer only the bytes it needs to produce the next
 * frame. After {@link #next()} returns, the decoded frame is available as a direct
 * {@link ByteBuffer} laid out in {@link #pixelFormat()}; {@link ReaderFilter}s added through
 * {@link #addFilter(ReaderFilter)} transform it first, so {@link #frameWidth()} and
 * {@link #frameHeight()} carry the geometry of what {@code next()} actually returned.
 * The buffer is owned by the reader and reused across calls; if the caller needs to retain a
 * previous frame, it must copy the bytes out before the next {@code next()} call.
 */
public abstract class ImageReader implements Closeable {
    /**
     * Aggregate ceiling on the pixel bytes {@link #readAll()} will retain across every frame of one
     * image, and the last line of defence against a container that declares far more animation than
     * it carries. Sized at roughly twice the largest legitimate sample measured (an ordinary
     * 32-frame animated GIF already decodes to 261 MiB), because a budget tight enough to be
     * interesting is also tight enough to reject real content.
     */
    public static final long MAX_DECODED_BYTES = 512L << 20;

    protected final ByteBuffer data;
    protected final PixelFormat requestedFormat;
    // ORDERED SO A POST-PROCESSOR CHAIN RUNS IN INSERTION ORDER (E.G. DOWNSCALE THEN RECOLOR)
    protected final List<ReaderFilter> filters = new ArrayList<>(2);
    protected long delayMs;
    // OUTPUT OF THE LATEST next() AFTER THE FILTER CHAIN; fresh MARKS A FILTER-ALLOCATED BUFFER THE CALLER MAY KEEP
    private ByteBuffer frame;
    private int frameWidth, frameHeight;
    private boolean fresh;

    protected ImageReader(final ByteBuffer data) { this(data, null); }

    protected ImageReader(final ByteBuffer data, final PixelFormat requestedFormat) {
        this.data = data.slice();
        this.requestedFormat = requestedFormat;
    }

    /** Format short name (e.g. {@code "PNG"}, {@code "GIF"}). */
    public String name() {
        // FORMAT NAMES ARE UPPERCASE ("PNG", "WEBP") WHATEVER THE CLASS CASING IS
        final String simpleName = this.getClass().getSimpleName();
        final String format = simpleName.endsWith("Reader")
                ? simpleName.substring(0, simpleName.length() - "Reader".length())
                : simpleName;
        return format.toUpperCase(Locale.ROOT);
    }

    /** Image width in pixels. Available immediately after construction. */
    public abstract int width();

    /** Image height in pixels. Available immediately after construction. */
    public abstract int height();

    /** Width of the frame returned by the latest {@link #next()}, after filters; {@link #width()} before the first. */
    public int frameWidth() { return this.frame == null ? this.width() : this.frameWidth; }

    /** Height of the frame returned by the latest {@link #next()}, after filters; {@link #height()} before the first. */
    public int frameHeight() { return this.frame == null ? this.height() : this.frameHeight; }

    /**
     * Pixel layout of the single, tightly packed buffer returned by {@link #next()}. The reader
     * chooses this layout based on the {@code requestedFormat} passed at construction time:
     * <ul>
     *   <li>If {@code requestedFormat} is non-null, the reader tries to honor it (converting
     *       internally as needed).</li>
     *   <li>If {@code requestedFormat} is {@code null}, the reader returns whatever layout is
     *       cheapest for it to produce (its native format) without doing a color conversion.</li>
     * </ul>
     * Every built-in reader delivers {@link PixelFormat#BGRA}, except grayscale JPEG
     * ({@link PixelFormat#GRAY} unless BGRA is requested) and SVG, which honors an
     * {@link PixelFormat#RGBA} request. Callers must always read this method to know how to
     * interpret the decoded bytes.
     */
    public abstract PixelFormat pixelFormat();

    /**
     * Pre-generated animation summary computed during construction. Animated formats walk the
     * container chunk structure (without decoding pixel data) to populate this; static formats
     * return {@link ImageData.Scan#EMPTY}.
     */
    public abstract ImageData.Scan scan();

    /**
     * Repeat count for animated images.
     * {@link ImageData#NO_REPEAT} (-1) for single-shot, {@link ImageData#REPEAT_FOREVER} (0) for
     * infinite loop, or a positive integer for explicit repeat count.
     */
    public int repeat() { return this.scan().repeat(); }

    /** Total frame count if known, or {@code -1} if streaming/unknown. */
    public int frameCount() { return this.scan().frameCount(); }

    /**
     * Total playback duration in milliseconds. Static images return {@code 0}.
     */
    public long duration() { return this.scan().duration(); }

    /**
     * Per-frame delays in milliseconds. Static images return a single {@code 0}. The array is owned
     * by the reader and must be treated as read-only; callers that need to mutate it must copy first.
     */
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

    /** Appends a filter to the chain run on every frame produced by {@link #next()} and {@link #readAll()}. */
    public void addFilter(final ReaderFilter filter) {
        this.filters.add(filter);
    }

    /**
     * Decodes every available frame and returns a detached {@link ImageData} instance.
     * Frames the reader would reuse are copied, frames a filter allocated are retained as they are,
     * and the resulting geometry is the last filter's output.
     */
    public ImageData readAll() throws IOException {
        final List<ByteBuffer> frames = new ArrayList<>();
        final List<Long> frameDelays = new ArrayList<>();
        long retained = 0L;
        while (this.hasNext()) {
            final ByteBuffer decoded = this.next();
            final ByteBuffer kept = this.fresh ? decoded : detach(decoded);
            // BACKSTOP AGAINST THE ANIMATION BOMB: FRAME COST IS FIXED AT CANVAS SIZE WHILE FRAME
            // COUNT COMES FROM THE CONTAINER, SO A FEW HUNDRED HEADER BYTES CAN DESCRIBE GIGABYTES.
            // READERS CAP THEIR OWN FRAME COUNTS; THIS BOUNDS WHAT SURVIVES ALL OF THEM COMBINED.
            retained += kept.remaining();
            if (retained > MAX_DECODED_BYTES) {
                throw new CodecException("Decoded animation exceeds budget: " + retained
                        + " bytes over " + frames.size() + " frames (max " + MAX_DECODED_BYTES + ")");
            }
            frames.add(kept);
            frameDelays.add(this.delayMs);
        }
        if (frames.isEmpty()) {
            throw new CodecException("No frames decoded by " + this.name());
        }
        final long[] delayArray = new long[frameDelays.size()];
        long total = 0L;
        for (int i = 0; i < delayArray.length; i++) {
            delayArray[i] = frameDelays.get(i);
            total += delayArray[i];
        }
        return new ImageData(frames.toArray(ByteBuffer[]::new), this.frameWidth(), this.frameHeight(),
                delayArray, total, this.repeat());
    }

    // COPIES A REUSED READER FRAME INTO AN INDEPENDENTLY-OWNED DIRECT BUFFER, READY TO READ.
    private static ByteBuffer detach(final ByteBuffer src) {
        final ByteBuffer copy = ByteBuffer.allocateDirect(src.remaining()).order(src.order());
        copy.put(src.duplicate());
        copy.flip();
        return copy;
    }

    /**
     * Rewinds the reader back to the first frame without re-parsing or re-buffering the source.
     * <p>
     * Animated readers that support rewinding reposition their internal cursor at the first
     * frame and clear any inter-frame state (canvas, disposal, counters) so the next
     * {@link #next()} call decodes frame 0 again. Metadata accessors ({@link #scan()},
     * {@link #width()}, {@link #delays()}, ...) are unaffected.
     * <p>
     * The default implementation reports no support; callers must then dispose this reader and
     * decode the source from scratch.
     *
     * @return {@code true} when the reader was rewound, {@code false} when rewinding is not
     *         supported by this implementation
     */
    public boolean reset() {
        return false;
    }

    /**
     * Returns whether another decoded frame can be produced. Implementations may validate and
     * skip non-frame container records while answering this.
     */
    public abstract boolean hasNext() throws IOException;

    /**
     * Decodes the next frame and runs it through the filter chain.
     *
     * @return the frame buffer, positioned at zero and ready to read
     * @throws IOException on I/O error or malformed bitstream
     */
    public final ByteBuffer next() throws IOException {
        final ByteBuffer decoded = this.decode();
        ReaderFilter.Frame f = new ReaderFilter.Frame(decoded, this.width(), this.height(), this.pixelFormat());
        // EACH FILTER CONSUMES THE PREVIOUS OUTPUT'S GEOMETRY; A NO-OP CHAIN HANDS BACK THE READER'S REUSED BUFFER
        for (final ReaderFilter filter: this.filters) f = filter.compute(f);
        this.fresh = f.buffer() != decoded;
        this.frameWidth = f.width();
        this.frameHeight = f.height();
        return this.frame = f.buffer();
    }

    /**
     * Decodes the next frame from the underlying buffer into the internal direct buffer.
     *
     * @return the decoded frame buffer, positioned at zero
     * @throws IOException on I/O error or malformed bitstream
     */
    protected abstract ByteBuffer decode() throws IOException;

    @Override
    public void close() {
        // BYTEBUFFER-BACKED READERS OWN NO EXTERNAL RESOURCES; DROPPING THE LAST FRAME FREES ITS DIRECT MEMORY EARLY
        this.frame = null;
    }
}
