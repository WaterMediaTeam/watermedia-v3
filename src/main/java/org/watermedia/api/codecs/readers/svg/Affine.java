package org.watermedia.api.codecs.readers.svg;

/**
 * Minimal 2x3 affine transform used by the SVG rasterizer, kept fully independent of
 * {@code java.awt.geom.AffineTransform} so the codec path never loads AWT.
 *
 * <p>The matrix maps a point {@code (x, y)} to
 * {@code (a*x + c*y + e, b*x + d*y + f)}, matching the column-vector convention of the SVG
 * {@code matrix(a b c d e f)} primitive. Modelled as a record: immutable, allocation-friendly, and
 * every operation returns a new transform.
 *
 * @param a scale-x / m00
 * @param b shear-y / m10
 * @param c shear-x / m01
 * @param d scale-y / m11
 * @param e translate-x / m02
 * @param f translate-y / m12
 */
record Affine(double a, double b, double c, double d, double e, double f) {

    static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);

    static Affine translate(final double tx, final double ty) {
        return new Affine(1, 0, 0, 1, tx, ty);
    }

    static Affine scale(final double sx, final double sy) {
        return new Affine(sx, 0, 0, sy, 0, 0);
    }

    static Affine rotate(final double degrees) {
        final double r = Math.toRadians(degrees);
        final double cos = Math.cos(r), sin = Math.sin(r);
        return new Affine(cos, sin, -sin, cos, 0, 0);
    }

    static Affine rotate(final double degrees, final double cx, final double cy) {
        // TRANSLATE TO PIVOT, ROTATE, TRANSLATE BACK
        return translate(cx, cy).concat(rotate(degrees)).concat(translate(-cx, -cy));
    }

    static Affine skewX(final double degrees) {
        return new Affine(1, 0, Math.tan(Math.toRadians(degrees)), 1, 0, 0);
    }

    static Affine skewY(final double degrees) {
        return new Affine(1, Math.tan(Math.toRadians(degrees)), 0, 1, 0, 0);
    }

    // RETURNS this * other: THE RESULT APPLIES `other` FIRST, THEN `this` (SVG LEFT-TO-RIGHT NESTING)
    Affine concat(final Affine o) {
        return new Affine(
                this.a * o.a + this.c * o.b,
                this.b * o.a + this.d * o.b,
                this.a * o.c + this.c * o.d,
                this.b * o.c + this.d * o.d,
                this.a * o.e + this.c * o.f + this.e,
                this.b * o.e + this.d * o.f + this.f);
    }

    double x(final double px, final double py) { return this.a * px + this.c * py + this.e; }
    double y(final double px, final double py) { return this.b * px + this.d * py + this.f; }

    double det() { return this.a * this.d - this.b * this.c; }

    // UNIFORM-ish DEVICE SCALE ESTIMATE — USED TO PICK A USER-SPACE FLATTENING TOLERANCE
    double scaleFactor() {
        final double det = Math.abs(this.det());
        return det > 0 ? Math.sqrt(det) : 1.0;
    }

    // RETURNS THE INVERSE, OR null WHEN THE MATRIX IS SINGULAR (DEGENERATE TRANSFORM)
    Affine invert() {
        final double det = this.det();
        if (det == 0 || !Double.isFinite(det)) return null;
        final double inv = 1.0 / det;
        return new Affine(
                this.d * inv,
                -this.b * inv,
                -this.c * inv,
                this.a * inv,
                (this.c * this.f - this.d * this.e) * inv,
                (this.b * this.e - this.a * this.f) * inv);
    }
}
