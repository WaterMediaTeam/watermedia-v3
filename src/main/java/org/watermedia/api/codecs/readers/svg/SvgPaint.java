package org.watermedia.api.codecs.readers.svg;

/**
 * Resolved fill/stroke paint. A paint is turned into a per-pixel {@link Paint} sampler at draw time
 * via {@link #sampler(Affine, double, double, double, double)}, which receives the current device
 * transform and the shape's user-space bounds (needed for {@code objectBoundingBox} gradients).
 *
 * <p>Sealed over two record carriers — a solid colour and a linear gradient. "No paint"
 * ({@code fill="none"}) is represented by a {@code null} {@code SvgPaint} at the call site.
 */
sealed interface SvgPaint permits SvgPaint.Solid, SvgPaint.Linear {

    Paint sampler(Affine ctm, double minX, double minY, double maxX, double maxY);

    static SvgPaint solid(final int argb) {
        return new Solid(argb);
    }

    record Solid(int argb) implements SvgPaint {
        @Override
        public Paint sampler(final Affine ctm, final double minX, final double minY, final double maxX, final double maxY) {
            final int c = this.argb;
            return (px, py) -> c;
        }
    }

    /** Linear gradient. {@code offsets} are ascending in [0,1]; {@code colors} are straight ARGB. */
    record Linear(double x1, double y1, double x2, double y2, boolean userSpace,
                  Affine gradientTransform, float[] offsets, int[] colors) implements SvgPaint {

        @Override
        public Paint sampler(final Affine ctm, final double minX, final double minY, final double maxX, final double maxY) {
            if (this.offsets.length == 0) { final int c = 0; return (px, py) -> c; }
            if (this.offsets.length == 1) { final int c = this.colors[0]; return (px, py) -> c; }

            // BUILD gradient-space → user-space, THEN COMPOSE WITH THE DEVICE TRANSFORM AND INVERT
            Affine gradToUser;
            if (this.userSpace) {
                gradToUser = this.gradientTransform == null ? Affine.IDENTITY : this.gradientTransform;
            } else {
                final double w = maxX - minX, h = maxY - minY;
                if (w == 0 || h == 0) { final int c = this.colors[0]; return (px, py) -> c; }
                final Affine bbox = Affine.translate(minX, minY).concat(Affine.scale(w, h));
                gradToUser = this.gradientTransform == null ? bbox : bbox.concat(this.gradientTransform);
            }

            final Affine inv = ctm.concat(gradToUser).invert();
            if (inv == null) { final int c = this.colors[0]; return (px, py) -> c; }

            final double dx = this.x2 - this.x1, dy = this.y2 - this.y1;
            final double lenSq = dx * dx + dy * dy;
            if (lenSq == 0) { final int c = this.colors[this.colors.length - 1]; return (px, py) -> c; } // DEGENERATE → LAST STOP

            final double gx1 = this.x1, gy1 = this.y1;
            final float[] off = this.offsets;
            final int[] col = this.colors;
            return (px, py) -> {
                final double gx = inv.x(px, py), gy = inv.y(px, py);
                final double t = ((gx - gx1) * dx + (gy - gy1) * dy) / lenSq;
                if (t <= 0) return col[0];
                if (t >= 1) return col[col.length - 1];
                return lookup(off, col, (float) t);
            };
        }

        // LINEAR INTERPOLATION BETWEEN THE TWO BRACKETING STOPS, STRAIGHT (NON-PREMULTIPLIED) sRGB
        private static int lookup(final float[] off, final int[] col, final float t) {
            // PAD BEYOND THE STOP RANGE (STOPS NEED NOT SPAN [0,1])
            if (t <= off[0]) return col[0];
            if (t >= off[off.length - 1]) return col[col.length - 1];
            int hi = 1;
            while (hi < off.length && off[hi] < t) hi++;
            if (hi >= off.length) return col[col.length - 1];
            final int lo = hi - 1;
            final float span = off[hi] - off[lo];
            final float f = span <= 0 ? 0 : (t - off[lo]) / span;
            final int c0 = col[lo], c1 = col[hi];
            final int a = lerp((c0 >>> 24) & 0xFF, (c1 >>> 24) & 0xFF, f);
            final int r = lerp((c0 >>> 16) & 0xFF, (c1 >>> 16) & 0xFF, f);
            final int g = lerp((c0 >>> 8) & 0xFF, (c1 >>> 8) & 0xFF, f);
            final int b = lerp(c0 & 0xFF, c1 & 0xFF, f);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static int lerp(final int a, final int b, final float f) {
            return a + Math.round((b - a) * f);
        }
    }
}
