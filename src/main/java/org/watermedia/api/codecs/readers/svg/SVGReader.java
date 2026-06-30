package org.watermedia.api.codecs.readers.svg;

import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Pure-Java SVG reader: parses the document, rasterizes it once into a BGRA frame and exposes it
 * through the {@link ImageReader} contract as a single static frame. Unlike the binary readers, the
 * whole buffer is the body (SVG has no fixed header), so {@code CodecsAPI} hands this reader the
 * document from its very first byte.
 *
 * <p>SVG is resolution-independent, so the output size is the intrinsic size uniformly downscaled so
 * the larger side never exceeds {@link WaterMediaConfig.Decoders#svgMaxSize} (default 512 px); it is
 * never upscaled.
 */
public class SVGReader extends ImageReader {
    private final int w, h;
    private final SVGDocument document;
    private boolean consumed;
    private ByteBuffer rendered; // CACHED BGRA FRAME, BUILT LAZILY ON FIRST next()

    public SVGReader(final ByteBuffer data, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);

        final ByteBuffer buf = this.data.duplicate();
        final byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);

        final SvgNode root = SVGParser.parse(new ByteArrayInputStream(bytes));
        if (root == null || !root.tag().toLowerCase(Locale.ROOT).equals("svg")) {
            throw new XCodecException("Document root is not <svg>");
        }
        this.document = SVGDocument.build(root);

        final double iw = this.document.intrinsicWidth(), ih = this.document.intrinsicHeight();
        if (iw <= 0 || ih <= 0) throw new XCodecException("SVG has no resolvable size");
        final int cap = Math.max(1, WaterMediaConfig.decoders.svgMaxSize);
        final double s = Math.min(1.0, cap / Math.max(iw, ih));
        this.w = Math.max(1, (int) Math.round(iw * s));
        this.h = Math.max(1, (int) Math.round(ih * s));
    }

    @Override public int width() { return this.w; }
    @Override public int height() { return this.h; }
    @Override public PixelFormat pixelFormat() { return PixelFormat.BGRA; }
    @Override public ImageData.Scan scan() { return ImageData.Scan.EMPTY; }

    @Override
    public boolean hasNext() {
        return !this.consumed;
    }

    @Override
    public ByteBuffer next() throws IOException {
        if (this.consumed) throw new EOFException("SVG has a single frame");
        if (this.rendered == null) this.rendered = this.rasterize();
        this.rendered.rewind(); // RE-ARM POSITION 0 SO A SECOND PASS (AFTER reset()) READS THE FULL FRAME
        this.consumed = true;
        this.currentDelay = 0L;
        this.currentFrame = this.rendered;
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
        // ARGB int CANVAS → DIRECT LITTLE-ENDIAN BGRA BUFFER (SHARED HELPER; canvas() IS EXACTLY w*h INTS)
        return DataTool.bgraToBuffer(out.canvas());
    }
}
