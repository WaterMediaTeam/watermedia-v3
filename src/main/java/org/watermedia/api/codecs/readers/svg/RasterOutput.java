package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.CodecException;

/**
 * AWT-free drawing surface that paints user-space {@link Path} geometry into the raw ARGB canvas of its
 * {@link SvgRasterizer}; the device transform comes with every call (it lives on the per-node {@code RenderState}).
 */
record RasterOutput(SvgRasterizer ras) {
    // DEVICE-SPACE FLATTENING TOLERANCE IN PIXELS — SMALL ENOUGH FOR CRISP CURVES AT ANY SCALE
    private static final double TOL = 0.25;

    RasterOutput(final int width, final int height) {
        this(new SvgRasterizer(width, height));
    }

    int width() { return this.ras.width; }
    int height() { return this.ras.height; }
    int[] canvas() { return this.ras.canvas; }

    void fill(final Path geo, final Affine ctm, final boolean evenOdd, final SvgPaint paint, final float opacity) throws CodecException {
        if (paint == null || opacity <= 0 || geo.isEmpty()) return;
        final double[] bb = geo.userBounds();
        if (bb == null) return;
        final Paint sampler = paint.sampler(ctm, bb[0], bb[1], bb[2], bb[3]);
        this.ras.fill(geo.flatten(ctm, TOL), evenOdd, sampler, opacity);
    }

    void stroke(final Path geo, final Affine ctm, final double strokeWidthUser, final SvgPaint paint, final float opacity) throws CodecException {
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
