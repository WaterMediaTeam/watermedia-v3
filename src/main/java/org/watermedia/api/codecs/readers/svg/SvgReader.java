package org.watermedia.api.codecs.readers.svg;

import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Pure-Java SVG reader: parses the document, rasterizes it once into a single static frame and
 * exposes it through the {@link ImageReader} contract. Unlike the binary readers, the whole buffer
 * is the body (SVG has no fixed header), so {@code CodecsAPI} hands this reader the document from its
 * very first byte.
 *
 * <p>SVG is resolution-independent. The output size is the intrinsic size uniformly downscaled so
 * the larger side never exceeds {@link WaterMediaConfig.Decoders.Svg#maxSize} (default 512 px), and
 * it is never upscaled.
 *
 * <p>The frame is {@link PixelFormat#BGRA} natively; a {@link PixelFormat#RGBA} request is honored by
 * swizzling the two colour channels during the canvas-to-buffer copy. Any other request falls back to
 * BGRA — callers must consult {@link #pixelFormat()}.
 */
public final class SvgReader extends ImageReader {
    private final int w, h;
    private final PixelFormat format; // HONORED OUTPUT LAYOUT (BGRA NATIVE, RGBA ON REQUEST)
    private final SvgDocument document;
    private boolean consumed;
    private ByteBuffer rendered; // CACHED FRAME, BUILT LAZILY ON FIRST next()

    public SvgReader(final ByteBuffer data, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);
        // ONLY RGBA IS CHEAP TO HONOR (A CHANNEL SWIZZLE); EVERYTHING ELSE STAYS ON THE NATIVE BGRA LAYOUT
        this.format = requestedFormat == PixelFormat.RGBA ? PixelFormat.RGBA : PixelFormat.BGRA;

        // STREAM StAX STRAIGHT OFF A ByteBuffer VIEW — NO FULL-DOCUMENT byte[] COPY
        final SvgNode root = SvgParser.parse(new ByteBufferInputStream(this.data.duplicate()));
        if (root == null || !root.tag().equals("svg")) {
            throw new CodecException("Document root is not <svg>");
        }
        this.document = new SvgDocument(root);

        final double iw = this.document.intrinsicWidth(), ih = this.document.intrinsicHeight();
        if (iw <= 0 || ih <= 0) throw new CodecException("SVG has no resolvable size");
        // INTRINSIC SIZE CAPPED BY THE CONFIG, NEVER UPSCALED
        final double cap = Math.max(1, WaterMediaConfig.decoders.svg.maxSize);
        final double s = Math.min(Math.max(iw, ih), cap) / Math.max(iw, ih);
        this.w = Math.max(1, (int) Math.round(iw * s));
        this.h = Math.max(1, (int) Math.round(ih * s));
    }

    @Override public int width() { return this.w; }
    @Override public int height() { return this.h; }
    @Override public PixelFormat pixelFormat() { return this.format; }
    @Override public ImageData.Scan scan() { return ImageData.Scan.EMPTY; }

    @Override
    public boolean hasNext() {
        return !this.consumed;
    }

    @Override
    protected ByteBuffer decode() throws IOException {
        if (this.consumed) throw new CodecException("SVG has a single frame");
        if (this.rendered == null) this.rendered = this.rasterize();
        this.rendered.rewind(); // RE-ARM POSITION 0 SO A SECOND PASS (AFTER reset()) READS THE FULL FRAME
        this.consumed = true;
        this.delayMs = 0L;
        return this.rendered;
    }

    @Override
    public boolean reset() {
        this.consumed = false;
        return true;
    }

    private ByteBuffer rasterize() throws IOException {
        final RasterOutput out = new RasterOutput(this.w, this.h);
        this.document.render(out);
        final int[] canvas = out.canvas(); // 0xAARRGGBB, EXACTLY w*h INTS
        if (this.format == PixelFormat.RGBA) {
            // 0xAARRGGBB → RGBA MEMORY ORDER: KEEP A/G, SWAP R AND B (LITTLE-ENDIAN INT = A<<24|B<<16|G<<8|R)
            for (int i = 0; i < canvas.length; i++) {
                final int c = canvas[i];
                canvas[i] = (c & 0xFF00FF00) | ((c >>> 16) & 0xFF) | ((c & 0xFF) << 16);
            }
        }
        // INT CANVAS → DIRECT LITTLE-ENDIAN BUFFER (SHARED HELPER; THE INTS ARE ALREADY IN THE TARGET LAYOUT)
        return DataTool.bgraToBuffer(canvas);
    }

    // MINIMAL InputStream VIEW OVER A ByteBuffer SO StAX READS THE DOCUMENT WITHOUT COPYING IT TO A byte[]
    private static final class ByteBufferInputStream extends InputStream {
        private final ByteBuffer buf;

        ByteBufferInputStream(final ByteBuffer buf) {
            this.buf = buf;
        }

        @Override
        public int read() {
            return this.buf.hasRemaining() ? this.buf.get() & 0xFF : -1;
        }

        @Override
        public int read(final byte[] dst, final int off, final int len) {
            if (!this.buf.hasRemaining()) return -1;
            final int n = Math.min(len, this.buf.remaining());
            this.buf.get(dst, off, n);
            return n;
        }
    }
}
