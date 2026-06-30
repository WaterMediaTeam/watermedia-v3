package org.watermedia.api.codecs.readers.svg;

import java.util.Arrays;
import java.util.List;

/**
 * Anti-aliased software rasterizer writing straight {@code 0xAARRGGBB} pixels into an {@code int[]}
 * canvas — fully independent of {@code java.awt}. Filling uses a scanline algorithm with 4x vertical
 * supersampling and analytic horizontal coverage, honouring both non-zero and even-odd winding.
 *
 * <p>Stroking reuses the fill path: the stroke outline is built as the union of one quad per segment
 * plus a disc at every vertex/endpoint, which exactly reproduces {@code stroke-linecap:round} and
 * {@code stroke-linejoin:round} (the only stroke styles the supported SVGs use). Overlapping union
 * pieces are filled in a single non-zero pass, so internal seams never anti-alias against each other.
 */
final class SVGRasterizer {
    // VERTICAL SUPERSAMPLES PER PIXEL ROW
    private static final int SS = 4;
    private static final float SS_WEIGHT = 1.0f / SS;
    // DEVICE-SPACE FLATTENING TOLERANCE FOR THE GENERATED STROKE GEOMETRY
    private static final double STROKE_TOL = 0.2;

    final int width, height;
    final int[] canvas;

    private final float[] cov;

    // EDGE TABLE SCRATCH (DEVICE SPACE, HORIZONTAL EDGES DROPPED)
    private double[] eyTop = new double[64], eyBot = new double[64], exTop = new double[64], edxdy = new double[64];
    private int[] edir = new int[64];
    private int edgeN;

    // PER-SCANLINE CROSSINGS
    private double[] cxs = new double[32];
    private int[] cdir = new int[32];

    SVGRasterizer(final int width, final int height) {
        this.width = width;
        this.height = height;
        this.canvas = new int[width * height];
        this.cov = new float[width];
    }

    void fill(final Path.Polys polys, final boolean evenOdd, final Paint paint, final float opacity) {
        if (opacity <= 0 || polys.lines.isEmpty()) return;
        this.buildEdges(polys.lines);
        if (this.edgeN == 0) return;
        this.scan(evenOdd, paint, opacity);
    }

    void stroke(final Path.Polys polys, final double halfWidth, final Paint paint, final float opacity) {
        if (opacity <= 0 || halfWidth <= 0 || polys.lines.isEmpty()) return;
        // BUILD THE STROKE OUTLINE IN DEVICE SPACE: QUAD PER SEGMENT + DISC PER VERTEX (ROUND CAP/JOIN)
        final Path g = new Path();
        final List<double[]> lines = polys.lines;
        for (int li = 0; li < lines.size(); li++) {
            final double[] p = lines.get(li);
            final int n = p.length / 2;
            final boolean closed = polys.closed.get(li);
            for (int i = 0; i + 1 < n; i++) {
                segmentQuad(g, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3], halfWidth);
            }
            if (closed && n > 1) {
                segmentQuad(g, p[2 * (n - 1)], p[2 * (n - 1) + 1], p[0], p[1], halfWidth);
            }
            for (int i = 0; i < n; i++) {
                g.ellipse(p[2 * i], p[2 * i + 1], halfWidth, halfWidth);
            }
        }
        // FILL THE UNION WITH NON-ZERO WINDING SO OVERLAPS DO NOT DOUBLE-BLEND
        this.buildEdges(g.flatten(Affine.IDENTITY, STROKE_TOL).lines);
        if (this.edgeN == 0) return;
        this.scan(false, paint, opacity);
    }

    private static void segmentQuad(final Path g, final double x0, final double y0, final double x1, final double y1, final double hw) {
        final double dx = x1 - x0, dy = y1 - y0;
        final double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-9) return; // ZERO-LENGTH SEGMENT — THE VERTEX DISC COVERS IT
        final double nx = -dy / len * hw, ny = dx / len * hw;
        // VERTEX ORDER MATCHES THE ELLIPSE (DISC) WINDING SO THE NON-ZERO UNION OF QUADS + CAP/JOIN
        // DISCS NEVER CANCELS IN THEIR OVERLAP (REVERSED ORDER WOULD NOTCH EVERY CAP AND JOINT)
        g.moveTo(x0 + nx, y0 + ny);
        g.lineTo(x0 - nx, y0 - ny);
        g.lineTo(x1 - nx, y1 - ny);
        g.lineTo(x1 + nx, y1 + ny);
        g.close();
    }

    private void buildEdges(final List<double[]> lines) {
        this.edgeN = 0;
        for (final double[] p: lines) {
            final int n = p.length / 2;
            if (n < 2) continue;
            // EVERY SUBPATH IS CLOSED FOR FILLING — INCLUDE THE CLOSING EDGE
            for (int i = 0; i < n; i++) {
                final int j = (i + 1) % n;
                this.addEdge(p[2 * i], p[2 * i + 1], p[2 * j], p[2 * j + 1]);
            }
        }
    }

    private void addEdge(final double xa, final double ya, final double xb, final double yb) {
        if (ya == yb) return; // HORIZONTAL EDGES DO NOT CROSS SCANLINES
        if (this.edgeN == this.edir.length) this.growEdges();
        final int i = this.edgeN++;
        final double yt, yb2, xt;
        final int dir;
        if (ya < yb) { yt = ya; yb2 = yb; xt = xa; dir = 1; }
        else { yt = yb; yb2 = ya; xt = xb; dir = -1; }
        this.eyTop[i] = yt;
        this.eyBot[i] = yb2;
        this.exTop[i] = xt;
        this.edxdy[i] = (xb - xa) / (yb - ya);
        this.edir[i] = dir;
    }

    private void scan(final boolean evenOdd, final Paint paint, final float opacity) {
        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < this.edgeN; i++) {
            if (this.eyTop[i] < yMin) yMin = this.eyTop[i];
            if (this.eyBot[i] > yMax) yMax = this.eyBot[i];
            final double xa = this.exTop[i];
            final double xb = this.exTop[i] + (this.eyBot[i] - this.eyTop[i]) * this.edxdy[i];
            xMin = Math.min(xMin, Math.min(xa, xb));
            xMax = Math.max(xMax, Math.max(xa, xb));
        }

        final int y0 = Math.max(0, (int) Math.floor(yMin));
        final int y1 = Math.min(this.height, (int) Math.ceil(yMax));
        final int xlo = Math.max(0, (int) Math.floor(xMin));
        final int xhi = Math.min(this.width, (int) Math.ceil(xMax));
        if (y0 >= y1 || xlo >= xhi) return;

        for (int y = y0; y < y1; y++) {
            Arrays.fill(this.cov, xlo, xhi, 0f);
            for (int s = 0; s < SS; s++) {
                final double ys = y + (s + 0.5) / SS;
                final int cnt = this.crossings(ys);
                if (cnt < 2) continue;
                int wind = 0;
                for (int i = 0; i < cnt - 1; i++) {
                    wind += this.cdir[i];
                    final boolean inside = evenOdd ? ((i & 1) == 0) : (wind != 0);
                    if (inside) this.addSpan(this.cxs[i], this.cxs[i + 1], xlo, xhi);
                }
            }
            final int base = y * this.width;
            for (int x = xlo; x < xhi; x++) {
                float c = this.cov[x];
                if (c <= 0) continue;
                if (c > 1) c = 1;
                final int src = paint.argb(x + 0.5, y + 0.5);
                final float aeff = ((src >>> 24) & 0xFF) / 255f * c * opacity;
                if (aeff <= 0) continue;
                this.blend(base + x, src, aeff);
            }
        }
    }

    // GATHERS SORTED X-CROSSINGS OF THE EDGE TABLE WITH SUB-SCANLINE ys
    private int crossings(final double ys) {
        int cnt = 0;
        for (int i = 0; i < this.edgeN; i++) {
            if (ys < this.eyTop[i] || ys >= this.eyBot[i]) continue;
            final double x = this.exTop[i] + (ys - this.eyTop[i]) * this.edxdy[i];
            final int dir = this.edir[i];
            if (cnt == this.cxs.length) this.growCrossings();
            // INSERTION INTO THE SORTED CROSSING LIST
            int k = cnt - 1;
            while (k >= 0 && this.cxs[k] > x) {
                this.cxs[k + 1] = this.cxs[k];
                this.cdir[k + 1] = this.cdir[k];
                k--;
            }
            this.cxs[k + 1] = x;
            this.cdir[k + 1] = dir;
            cnt++;
        }
        return cnt;
    }

    private void addSpan(double xa, double xb, final int xlo, final int xhi) {
        if (xb <= xa) return;
        if (xa < xlo) xa = xlo;
        if (xb > xhi) xb = xhi;
        if (xa >= xb) return;
        final int ia = (int) Math.floor(xa), ib = (int) Math.floor(xb);
        if (ia == ib) {
            this.cov[ia] += SS_WEIGHT * (float) (xb - xa);
            return;
        }
        this.cov[ia] += SS_WEIGHT * (float) ((ia + 1) - xa);
        for (int i = ia + 1; i < ib; i++) this.cov[i] += SS_WEIGHT;
        if (ib < xhi) this.cov[ib] += SS_WEIGHT * (float) (xb - ib);
    }

    // STRAIGHT (NON-PREMULTIPLIED) SOURCE-OVER ONTO A POSSIBLY-TRANSPARENT CANVAS
    private void blend(final int idx, final int src, final float aeff) {
        final int sr = (src >>> 16) & 0xFF, sg = (src >>> 8) & 0xFF, sb = src & 0xFF;
        final int dst = this.canvas[idx];
        final int da = (dst >>> 24) & 0xFF;
        if (da == 0) {
            this.canvas[idx] = (clamp8(Math.round(aeff * 255f)) << 24) | (sr << 16) | (sg << 8) | sb;
            return;
        }
        final int dr = (dst >>> 16) & 0xFF, dg = (dst >>> 8) & 0xFF, db = dst & 0xFF;
        final float dAf = da / 255f;
        final float outA = aeff + dAf * (1 - aeff);
        if (outA <= 0) { this.canvas[idx] = 0; return; }
        final float inv = dAf * (1 - aeff);
        final int or = Math.round((sr * aeff + dr * inv) / outA);
        final int og = Math.round((sg * aeff + dg * inv) / outA);
        final int ob = Math.round((sb * aeff + db * inv) / outA);
        this.canvas[idx] = (clamp8(Math.round(outA * 255f)) << 24) | (clamp8(or) << 16) | (clamp8(og) << 8) | clamp8(ob);
    }

    private static int clamp8(final int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private void growEdges() {
        final int cap = this.edir.length * 2;
        this.eyTop = Arrays.copyOf(this.eyTop, cap);
        this.eyBot = Arrays.copyOf(this.eyBot, cap);
        this.exTop = Arrays.copyOf(this.exTop, cap);
        this.edxdy = Arrays.copyOf(this.edxdy, cap);
        this.edir = Arrays.copyOf(this.edir, cap);
    }

    private void growCrossings() {
        final int cap = this.cxs.length * 2;
        this.cxs = Arrays.copyOf(this.cxs, cap);
        this.cdir = Arrays.copyOf(this.cdir, cap);
    }
}
