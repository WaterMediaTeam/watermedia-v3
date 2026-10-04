package org.watermedia.api.codecs.readers;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.common.dds.DDSHeader;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reader for the BC (BC7/BC3/BC1) texture-compression codec stored in a {@link DDSHeader DDS}
 * container. Unlike the pixel-decoding {@link ImageReader} readers, BC is
 * sampled by the GPU, so this reader yields the <em>compressed</em> blocks of each frame (in the
 * file's own version) for a consumer's direct upload; there is no software decode.
 *
 * <p>Reading blocks requires no native encoder. WaterMedia's built-in graphics engines currently
 * decline BC textures; an external GPU consumer must support the layout named by {@link #version()}.
 * A WaterMedia animation footer is optional; without one, texture array slices have zero display delay.
 */
public final class BCReader implements Closeable {

    private final int width;
    private final int height;
    private final String version;
    private final int blockBytes;
    private final ByteBuffer[] frames;
    private final long[] delays;
    private final long duration;
    private int cursor;

    /**
     * Parses a complete BC-in-DDS file from {@code file.position()}.
     *
     * @throws XCodecException when the container is malformed, truncated or exceeds the memory budget
     */
    public BCReader(final ByteBuffer file) throws XCodecException {
        final DDSHeader.Info info = DDSHeader.read(file);
        this.width = info.width();
        this.height = info.height();
        this.version = info.codec();
        this.blockBytes = info.blockBytes();

        // SANITY, RANGE, THEN TRUNCATION — IN THAT ORDER. THE RANGE CHECK IS WHAT MAKES THE int CASTS
        // BELOW SOUND, SO RUNNING THE TRUNCATION CHECK FIRST LEFT IT GUARDING NOTHING
        final long frameBytes = DDSHeader.frameBytes(this.width, this.height, this.version);
        if (frameBytes <= 0) throw new XCodecException("Invalid BC frame size: " + frameBytes + " bytes");
        final long texLen = (long) info.arraySize() * frameBytes;
        if (texLen > ImageReader.MAX_DECODED_BYTES) throw new XCodecException("BC texture too large: " + texLen + " bytes");
        final long need = (long) DDSHeader.BYTES + texLen;
        if (file.remaining() < need) throw new XCodecException("Truncated BC texture: need " + need + " bytes");

        final int frameLen = (int) frameBytes;
        final int base = file.position();
        final int texStart = base + DDSHeader.BYTES;

        final ByteBuffer footer = file.duplicate();
        footer.position(texStart + (int) texLen);
        this.delays = footer.hasRemaining() ? DDSHeader.readFooter(footer, info.arraySize()) : new long[info.arraySize()];
        long total = 0L;
        for (final long delay: this.delays) {
            if (delay < 0 || delay > Long.MAX_VALUE - total) throw new XCodecException("Invalid BC frame delays");
            total += delay;
        }
        this.duration = total;

        // COPY THE TEXTURE DATA INTO A SINGLE DIRECT BUFFER AND SLICE PER-FRAME VIEWS FROM IT — THE
        // SLICES STAY DIRECT, SO THE GRAPHICS ENGINE CAN UPLOAD THEM WITHOUT A FURTHER COPY.
        final ByteBuffer tex = ByteBuffer.allocateDirect((int) texLen).order(ByteOrder.nativeOrder());
        final ByteBuffer region = file.duplicate();
        region.position(texStart).limit(texStart + (int) texLen);
        tex.put(region);
        tex.flip();

        this.frames = new ByteBuffer[info.arraySize()];
        for (int i = 0; i < this.frames.length; i++) {
            final ByteBuffer view = tex.duplicate().order(tex.order());
            view.position(i * frameLen).limit((i + 1) * frameLen);
            this.frames[i] = view.slice().order(tex.order());
        }
    }

    /** Returns the encoded frame width in pixels. */
    public int width() { return this.width; }
    /** Returns the encoded frame height in pixels. */
    public int height() { return this.height; }
    /** Returns the number of texture-array slices, including already consumed frames. */
    public int frameCount() { return this.frames.length; }

    /** The BC version of the stored texture ({@code BC7}/{@code BC3}/{@code BC1}) — needed for GPU upload. */
    public String version() { return this.version; }

    /** Bytes per 4x4 block: {@code 8} for BC1, {@code 16} for BC3/BC7. */
    public int blockBytes() { return this.blockBytes; }

    /** Per-frame delays in milliseconds (defensive copy). */
    public long[] delays() { return this.delays.clone(); }

    /** Total playback duration in milliseconds. */
    public long duration() { return this.duration; }

    /**
     * The compressed block buffers for every frame, in order, for a one-shot array upload.
     * Only the array is copied: buffer contents, positions and limits are shared with {@link #next()}.
     * Duplicate a buffer to change its position independently; do not free the shared storage.
     */
    public ByteBuffer[] blocks() { return this.frames.clone(); }

    /** Returns whether next() can advance to another compressed frame; close() exhausts the cursor. */
    public boolean hasNext() { return this.cursor < this.frames.length; }

    /** The next frame's compressed blocks paired with its delay, or {@code null} at the end. */
    public Frame next() {
        if (this.cursor >= this.frames.length) return null;
        final int i = this.cursor++;
        return new Frame(this.frames[i], this.delays[i]);
    }

    /** Rewinds to the first frame. */
    public void reset() { this.cursor = 0; }

    @Override
    public void close() {
        // BACKED BY AN IN-MEMORY DIRECT BUFFER — NO EXTERNAL RESOURCES TO RELEASE.
        this.cursor = this.frames.length;
    }

    /** One frame's compressed blocks and its display delay in milliseconds. */
    public record Frame(ByteBuffer blocks, long delayMs) {}
}
