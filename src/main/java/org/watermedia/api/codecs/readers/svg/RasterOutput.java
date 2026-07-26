package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.XCodecException;

/**
 * Drawing surface that paints user-space {@link Path} geometry into a raw ARGB canvas through the
 * {@link SVGRasterizer}. This is the AWT-free analogue of JSVG's {@code renderer.output.Output} +
 * {@code Graphics2DOutput} seam: the renderer talks only to this surface, so the backend stays a raw
 * pixel buffer instead of a {@code Graphics2D}. Modelled as a record wrapping the rasterizer — a
 * single-implementation interface would add indirection without payoff.
 *
 * <p>The current device transform is supplied per call (it lives on the per-node {@code RenderState}),
 * so the surface holds no mutable transform state.
 *
 * @param ras the backing rasterizer that owns the pixel canvas
 */
record RasterOutput(SVGRasterizer ras) {
    // DEVICE-SPACE FLATTENING TOLERANCE IN PIXELS — SMALL ENOUGH FOR CRISP CURVES AT ANY SCALE
    private static final double TOL = 0.25;

    RasterOutput(final int width, final int height) {
        this(new SVGRasterizer(width, height));
    }

    int width() { return this.ras.width; }
    int height() { return this.ras.height; }
    int[] canvas() { return this.ras.canvas; }

    void fill(final Path geo, final Affine ctm, final boolean evenOdd, final SVGPaint paint, final float opacity) throws XCodecException {
        if (paint == null || opacity <= 0 || geo.isEmpty()) return;
        final double[] bb = geo.userBounds();
        if (bb == null) return;
        final Paint sampler = paint.sampler(ctm, bb[0], bb[1], bb[2], bb[3]);
        this.ras.fill(geo.flatten(ctm, TOL), evenOdd, sampler, opacity);
    }

    void stroke(final Path geo, final Affine ctm, final double strokeWidthUser, final SVGPaint paint, final float opacity) throws XCodecException {
        if (paint == null || opacity <= 0 || strokeWidthUser <= 0 || geo.isEmpty()) return;
        final double[] bb = geo.userBounds();
        if (bb == null) return;
        final Paint sampler = paint.sampler(ctm, bb[0], bb[1], bb[2], bb[3]);
        // CLAMP TO THE CANVAS DIAGONAL: EVERY CANVAS PIXEL LIES WITHIN ONE DIAGONAL OF ANY OTHER, SO A
        // WIDER STROKE PAINTS THE SAME PIXELS WHILE ITS ROUND JOIN DISCS WOULD FLATTEN TO MILLIONS OF
        // POINTS (THE DISC RADIUS DRIVES THE SUBDIVISION DEPTH). ONLY VERTICES OFF-CANVAS COULD DIFFER
        final double w = this.ras.width, h = this.ras.height;
        final double halfWidth = Math.min(strokeWidthUser * ctm.scaleFactor() / 2.0, Math.sqrt(w * w + h * h));
        this.ras.stroke(geo.flatten(ctm, TOL), halfWidth, sampler, opacity);
    }
}
