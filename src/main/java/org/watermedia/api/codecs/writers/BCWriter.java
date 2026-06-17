package org.watermedia.api.codecs.writers;

import org.watermedia.api.codecs.ImageWriter;
import org.watermedia.api.codecs.common.bc.BCCodec;
import org.watermedia.api.codecs.common.dds.DDSHeader;
import org.watermedia.api.util.PixelFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * {@link ImageWriter} for the BC (BC7/BC3/BC1) texture-compression codec, stored in a
 * {@link DDSHeader DDS} container. Pixel frames go in; one block-compressed array slice per frame
 * comes out, ready for the GPU to sample without any software decode and at a quarter (BC3/BC7) or
 * eighth (BC1) of the RGBA8 footprint.
 *
 * <p>Availability is validated up front: the constructor throws when the requested version (or, by
 * default, any version) is not natively available, so a {@code BCWriter} that exists is always
 * usable. The {@code arraySize} of the DDS header is written as a placeholder and must be patched
 * with {@link DDSHeader#patchArraySize} once every frame has been streamed (the writer targets a
 * forward-only {@link OutputStream}).
 */
public final class BCWriter extends ImageWriter {

    private final String version;
    private final int frameBytes;
    private long[] delays = new long[16];
    private int frames;

    /** Opens a writer that uses the highest-quality BC version available. */
    public BCWriter(final OutputStream out, final int width, final int height, final PixelFormat pixelFormat) throws IOException {
        this(out, width, height, pixelFormat, BCCodec.best());
    }

    /**
     * Opens a writer for a specific BC version.
     *
     * @throws IOException when {@code version} is {@code null} or not natively available
     */
    public BCWriter(final OutputStream out, final int width, final int height,
                    final PixelFormat pixelFormat, final String version) throws IOException {
        super(out, width, height, pixelFormat);
        if (version == null) throw new IOException("No BC codec available");
        if (!BCCodec.available(version)) throw new IOException("BC codec unavailable: " + version);
        this.version = version;
        this.frameBytes = DDSHeader.frameBytes(width, height, version);
        this.out.write(DDSHeader.write(width, height, version, 0));
    }

    @Override
    public void writeFrame(final ByteBuffer frame) throws IOException {
        this.writeFrame(frame, 0L);
    }

    @Override
    public void writeFrame(final ByteBuffer frame, final long delayMs) throws IOException {
        final ByteBuffer blocks = BCCodec.encode(this.version, frame, this.width, this.height, this.pixelFormat);
        if (blocks.remaining() != this.frameBytes) {
            throw new IOException("BC encoder produced " + blocks.remaining()
                    + " bytes, expected " + this.frameBytes + " for " + this.width + "x" + this.height);
        }
        if (blocks.hasArray()) {
            this.out.write(blocks.array(), blocks.arrayOffset() + blocks.position(), blocks.remaining());
        } else {
            final byte[] tmp = new byte[blocks.remaining()];
            blocks.get(tmp);
            this.out.write(tmp);
        }
        if (this.frames == this.delays.length) {
            final long[] grown = new long[this.delays.length * 2];
            System.arraycopy(this.delays, 0, grown, 0, this.frames);
            this.delays = grown;
        }
        this.delays[this.frames++] = Math.max(0L, delayMs);
    }

    /** The BC version this writer encodes with ({@code BC7}/{@code BC3}/{@code BC1}). */
    public String version() { return this.version; }

    /** Number of frames written so far. */
    public int frameCount() { return this.frames; }

    @Override
    public void close() throws IOException {
        try {
            this.out.write(DDSHeader.writeFooter(this.delays, this.frames));
        } finally {
            super.close();
        }
    }
}
