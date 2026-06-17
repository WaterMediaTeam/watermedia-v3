package org.watermedia.api.codecs.readers;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.bc.BCCodec;
import org.watermedia.api.codecs.common.dds.DDSHeader;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reader for the BC (BC7/BC3/BC1) texture-compression codec stored in a {@link DDSHeader DDS}
 * container. Unlike the pixel-decoding {@link org.watermedia.api.codecs.ImageReader} readers, BC is
 * sampled by the GPU, so this reader yields the <em>compressed</em> blocks of each frame (in the
 * file's own version) for direct upload — there is no software decode.
 *
 * <p>The file's BC version is read from the container and validated against native availability in
 * the constructor, which throws when that version is not available (codecs are not pluggable, so a
 * reader that exists is always usable). Block buffers are direct and ready for the graphics engine;
 * {@link #version()} is exposed because the GPU upload needs the exact format.
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
     * @throws XCodecException when the container is malformed/truncated or its BC version is not
     *                         natively available
     */
    public BCReader(final ByteBuffer file) throws XCodecException {
        final DDSHeader.Info info = DDSHeader.read(file);
        if (!BCCodec.available(info.codec())) {
            throw new XCodecException("BC codec unavailable for cached texture: " + info.codec());
        }
        this.width = info.width();
        this.height = info.height();
        this.version = info.codec();
        this.blockBytes = info.blockBytes();

        final int frameBytes = DDSHeader.frameBytes(this.width, this.height, this.version);
        final long texLen = (long) info.arraySize() * frameBytes;
        final long need = (long) DDSHeader.BYTES + texLen + DDSHeader.FOOTER_HEAD_BYTES + (long) info.arraySize() * Long.BYTES;
        if (file.remaining() < need) throw new XCodecException("Truncated BC texture: need " + need + " bytes");
        if (texLen > Integer.MAX_VALUE) throw new XCodecException("BC texture too large: " + texLen + " bytes");

        final int base = file.position();
        final int texStart = base + DDSHeader.BYTES;

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
            view.position(i * frameBytes).limit((i + 1) * frameBytes);
            this.frames[i] = view.slice().order(tex.order());
        }

        final ByteBuffer footer = file.duplicate();
        footer.position(texStart + (int) texLen);
        this.delays = DDSHeader.readFooter(footer, info.arraySize());
        long total = 0L;
        for (final long d: this.delays) total += d;
        this.duration = total;
    }

    public int width() { return this.width; }
    public int height() { return this.height; }
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
     * The buffers are direct views over shared storage; callers must not free them individually.
     */
    public ByteBuffer[] blocks() { return this.frames.clone(); }

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
