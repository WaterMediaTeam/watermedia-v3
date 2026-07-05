package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.XCodecException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed SVG document ready to rasterize: it resolves the intrinsic size and {@code viewBox},
 * pre-builds the gradient paints declared under {@code <defs>}, and walks the element tree once per
 * render, deriving {@link RenderState} down the hierarchy and emitting fills/strokes to a
 * {@link RasterOutput}.
 *
 * <p>Non-rendered containers ({@code defs}, gradients, filters, masks, ...) are skipped during the
 * render walk; gradients are collected up front so {@code fill="url(#id)"} resolves regardless of
 * document order.
 */
final class SVGDocument {
    // SVG FALLBACK INTRINSIC SIZE WHEN NEITHER width/height NOR viewBox GIVE ONE
    private static final double DEFAULT_W = 300, DEFAULT_H = 150;
    // GUARD AGAINST ADVERSARIAL DEEP NESTING — REAL DOCUMENTS NEST A FEW LEVELS, NEVER HUNDREDS
    private static final int MAX_DEPTH = 512;

    private final SvgNode root;
    private final Map<String, SvgPaint> gradients;

    private final boolean hasViewBox;
    private final double vbX, vbY, vbW, vbH;
    private final double intrinsicW, intrinsicH;
    private final double refW, refH, refD; // PERCENTAGE REFERENCE LENGTHS: VIEWPORT WIDTH/HEIGHT/DIAGONAL
    private final boolean aspectNone; // preserveAspectRatio="none"

    private SVGDocument(final SvgNode root) throws IOException {
        this.root = root;

        final double[] vb = SVGParser.numbers(root.attr("viewBox"));
        if (vb.length == 4 && vb[2] > 0 && vb[3] > 0) {
            this.hasViewBox = true;
            this.vbX = vb[0]; this.vbY = vb[1]; this.vbW = vb[2]; this.vbH = vb[3];
        } else {
            this.hasViewBox = false;
            this.vbX = this.vbY = 0; this.vbW = this.vbH = 0;
        }

        final String w = root.attr("width"), h = root.attr("height");
        final boolean wAbs = w != null && !SVGParser.isPercent(w);
        final boolean hAbs = h != null && !SVGParser.isPercent(h);
        if (wAbs && hAbs) {
            this.intrinsicW = SVGParser.length(w, DEFAULT_W);
            this.intrinsicH = SVGParser.length(h, DEFAULT_H);
        } else if (this.hasViewBox) {
            this.intrinsicW = this.vbW;
            this.intrinsicH = this.vbH;
        } else {
            this.intrinsicW = wAbs ? SVGParser.length(w, DEFAULT_W) : DEFAULT_W;
            this.intrinsicH = hAbs ? SVGParser.length(h, DEFAULT_H) : DEFAULT_H;
        }

        // PERCENTAGE REFERENCES PER SVG SPEC: HORIZONTAL LENGTHS RESOLVE AGAINST THE VIEWPORT WIDTH,
        // VERTICAL AGAINST THE HEIGHT, OTHERS (r, stroke-width) AGAINST sqrt(w^2+h^2)/sqrt(2)
        this.refW = this.hasViewBox ? this.vbW : this.intrinsicW;
        this.refH = this.hasViewBox ? this.vbH : this.intrinsicH;
        this.refD = Math.sqrt((this.refW * this.refW + this.refH * this.refH) / 2);

        final String par = root.attr("preserveAspectRatio");
        this.aspectNone = par != null && par.trim().toLowerCase(Locale.ROOT).startsWith("none");

        this.gradients = new HashMap<>();
        this.collectGradients(root);
    }

    static SVGDocument build(final SvgNode root) throws IOException {
        return new SVGDocument(root);
    }

    double intrinsicWidth() { return this.intrinsicW; }
    double intrinsicHeight() { return this.intrinsicH; }

    void render(final RasterOutput out) throws IOException {
        final Affine view = this.viewTransform(out.width(), out.height());
        // THE ROOT <svg> ITSELF CAN CARRY PRESENTATION ATTRS (e.g. fill="#000000") THAT CASCADE DOWN
        final RenderState base = RenderState.root(view).derive(this.root, this.gradients, this.refD);
        for (final SvgNode child: this.root.children()) {
            this.renderNode(child, base, out, 1);
        }
    }

    private Affine viewTransform(final int outW, final int outH) {
        if (this.hasViewBox) {
            final double sx = outW / this.vbW, sy = outH / this.vbH;
            if (this.aspectNone) {
                return new Affine(sx, 0, 0, sy, -this.vbX * sx, -this.vbY * sy);
            }
            final double s = Math.min(sx, sy); // xMidYMid meet
            final double tx = (outW - this.vbW * s) / 2 - this.vbX * s;
            final double ty = (outH - this.vbH * s) / 2 - this.vbY * s;
            return new Affine(s, 0, 0, s, tx, ty);
        }
        return Affine.scale(outW / this.intrinsicW, outH / this.intrinsicH);
    }

    private void renderNode(final SvgNode node, final RenderState state, final RasterOutput out, final int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new XCodecException("SVG nesting exceeds " + MAX_DEPTH + " levels");
        switch (node.tag().toLowerCase(Locale.ROOT)) {
            case "defs", "lineargradient", "radialgradient", "stop", "filter", "clippath",
                 "mask", "pattern", "symbol", "marker", "title", "desc", "metadata", "style", "script", "use" -> {
                // NON-RENDERED OR UNSUPPORTED — SKIP THE SUBTREE
            }
            case "g", "a", "svg" -> {
                final RenderState child = state.derive(node, this.gradients, this.refD);
                for (final SvgNode c: node.children()) this.renderNode(c, child, out, depth + 1);
            }
            default -> {
                final RenderState s = state.derive(node, this.gradients, this.refD);
                final Path geo = this.geometry(node);
                if (geo == null || geo.isEmpty()) return;
                if (s.fill() != null) out.fill(geo, s.ctm(), s.evenOdd(), s.fill(), s.fillEff());
                if (s.stroke() != null && s.strokeWidth() > 0) out.stroke(geo, s.ctm(), s.strokeWidth(), s.stroke(), s.strokeEff());
            }
        }
    }

    private Path geometry(final SvgNode node) {
        final Path p = new Path();
        switch (node.tag().toLowerCase(Locale.ROOT)) {
            case "rect" -> {
                final double x = SVGParser.length(node.attr("x"), 0, this.refW), y = SVGParser.length(node.attr("y"), 0, this.refH);
                final double w = SVGParser.length(node.attr("width"), 0, this.refW), h = SVGParser.length(node.attr("height"), 0, this.refH);
                if (w <= 0 || h <= 0) return null;
                final String rxs = node.attr("rx"), rys = node.attr("ry");
                double rx = rxs != null ? SVGParser.length(rxs, 0, this.refW) : Double.NaN;
                double ry = rys != null ? SVGParser.length(rys, 0, this.refH) : Double.NaN;
                if (Double.isNaN(rx)) rx = Double.isNaN(ry) ? 0 : ry;
                if (Double.isNaN(ry)) ry = rx;
                p.rect(x, y, w, h, rx, ry);
            }
            case "circle" -> {
                final double cx = SVGParser.length(node.attr("cx"), 0, this.refW), cy = SVGParser.length(node.attr("cy"), 0, this.refH);
                final double r = SVGParser.length(node.attr("r"), 0, this.refD);
                if (r <= 0) return null;
                p.ellipse(cx, cy, r, r);
            }
            case "ellipse" -> {
                final double cx = SVGParser.length(node.attr("cx"), 0, this.refW), cy = SVGParser.length(node.attr("cy"), 0, this.refH);
                final double rx = SVGParser.length(node.attr("rx"), 0, this.refW), ry = SVGParser.length(node.attr("ry"), 0, this.refH);
                if (rx <= 0 || ry <= 0) return null;
                p.ellipse(cx, cy, rx, ry);
            }
            case "line" -> {
                final double x1 = SVGParser.length(node.attr("x1"), 0, this.refW), y1 = SVGParser.length(node.attr("y1"), 0, this.refH);
                final double x2 = SVGParser.length(node.attr("x2"), 0, this.refW), y2 = SVGParser.length(node.attr("y2"), 0, this.refH);
                p.moveTo(x1, y1);
                p.lineTo(x2, y2);
            }
            case "polygon" -> {
                final double[] pts = SVGParser.numbers(node.attr("points"));
                p.poly(pts, pts.length & ~1, true);
            }
            case "polyline" -> {
                final double[] pts = SVGParser.numbers(node.attr("points"));
                p.poly(pts, pts.length & ~1, false);
            }
            case "path" -> PathParser.parse(node.attr("d"), p);
            default -> { return null; }
        }
        return p;
    }

    // ----- GRADIENTS -----

    private void collectGradients(final SvgNode node) throws IOException {
        // FIRST INDEX ALL GRADIENT NODES BY id (FOR href TEMPLATE RESOLUTION), THEN BUILD PAINTS
        final Map<String, SvgNode> nodes = new HashMap<>();
        indexGradients(node, nodes, 1);
        for (final Map.Entry<String, SvgNode> e: nodes.entrySet()) {
            final String tag = e.getValue().tag().toLowerCase(Locale.ROOT);
            if (tag.equals("lineargradient")) {
                this.gradients.put(e.getKey(), this.buildLinear(e.getValue(), nodes));
            } else if (tag.equals("radialgradient")) {
                // RADIAL RENDERING IS OUT OF SCOPE — APPROXIMATE WITH A SOLID (AVERAGE OF STOPS) SO A
                // SHAPE PAINTED WITH url(#radial) STILL SHOWS A PLAUSIBLE COLOUR INSTEAD OF VANISHING
                final SvgPaint fallback = buildRadialFallback(e.getValue(), nodes);
                if (fallback != null) this.gradients.put(e.getKey(), fallback);
            }
        }
    }

    private static void indexGradients(final SvgNode node, final Map<String, SvgNode> out, final int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new XCodecException("SVG nesting exceeds " + MAX_DEPTH + " levels");
        final String tag = node.tag().toLowerCase(Locale.ROOT);
        if (tag.equals("lineargradient") || tag.equals("radialgradient")) {
            final String id = node.attr("id");
            if (id != null) out.put(id, node);
        }
        for (final SvgNode c: node.children()) indexGradients(c, out, depth + 1);
    }

    private SvgPaint buildLinear(final SvgNode node, final Map<String, SvgNode> nodes) {
        final SvgNode tmpl = templateOf(node, nodes);
        final boolean userSpace = "userSpaceOnUse".equalsIgnoreCase(attr(node, tmpl, "gradientUnits"));
        final Affine gt = SVGParser.parseTransform(attr(node, tmpl, "gradientTransform"));

        final double x1 = coord(attr(node, tmpl, "x1"), 0.0, userSpace, this.refW);
        final double y1 = coord(attr(node, tmpl, "y1"), 0.0, userSpace, this.refH);
        final double x2 = coord(attr(node, tmpl, "x2"), 1.0, userSpace, this.refW);
        final double y2 = coord(attr(node, tmpl, "y2"), 0.0, userSpace, this.refH);

        final List<SvgNode> stopNodes = stopsOf(node, tmpl);
        final List<float[]> offs = new ArrayList<>();
        final List<int[]> cols = new ArrayList<>();
        double prev = -1;
        for (final SvgNode stop: stopNodes) {
            double off = SVGParser.ratioOrNumber(stop.attr("offset"), 0);
            if (off < 0) off = 0; else if (off > 1) off = 1;
            if (off <= prev) off = prev + 1e-6; // KEEP STRICTLY INCREASING (HARD-EDGE STOPS)
            prev = off;
            offs.add(new float[] { (float) off });
            cols.add(new int[] { stopColor(stop) });
        }

        final float[] offArr = new float[offs.size()];
        final int[] colArr = new int[cols.size()];
        for (int i = 0; i < offArr.length; i++) { offArr[i] = offs.get(i)[0]; colArr[i] = cols.get(i)[0]; }
        return new SvgPaint.Linear(x1, y1, x2, y2, userSpace, gt, offArr, colArr);
    }

    // PARSES A <stop>: stop-color (default black) WITH stop-opacity FOLDED INTO THE ALPHA
    private static int stopColor(final SvgNode stop) {
        final String sc = stop.attr("stop-color");
        final long parsed = sc == null ? SvgColor.INVALID : SvgColor.parse(sc, 0xFF000000);
        int c = parsed == SvgColor.INVALID ? 0xFF000000 : (int) parsed;
        final String so = stop.attr("stop-opacity");
        if (so != null) {
            final double a = Math.max(0, Math.min(1, SVGParser.ratioOrNumber(so, 1)));
            final int alpha = (int) Math.round(((c >>> 24) & 0xFF) * a);
            c = (alpha << 24) | (c & 0x00FFFFFF);
        }
        return c;
    }

    // SOLID APPROXIMATION FOR AN UNSUPPORTED (RADIAL) GRADIENT: THE AVERAGE OF ITS STOP COLOURS
    private static SvgPaint buildRadialFallback(final SvgNode node, final Map<String, SvgNode> nodes) {
        final List<SvgNode> stops = stopsOf(node, templateOf(node, nodes));
        if (stops.isEmpty()) return null;
        long a = 0, r = 0, g = 0, b = 0;
        for (final SvgNode stop: stops) {
            final int c = stopColor(stop);
            a += (c >>> 24) & 0xFF; r += (c >>> 16) & 0xFF; g += (c >>> 8) & 0xFF; b += c & 0xFF;
        }
        final int n = stops.size();
        return SvgPaint.solid((int) ((a / n) << 24 | (r / n) << 16 | (g / n) << 8 | (b / n)));
    }

    private static SvgNode templateOf(final SvgNode node, final Map<String, SvgNode> nodes) {
        String href = node.attr("href");
        if (href == null) href = node.attr("xlink:href");
        if (href == null || !href.startsWith("#")) return null;
        return nodes.get(href.substring(1));
    }

    private static List<SvgNode> stopsOf(final SvgNode node, final SvgNode tmpl) {
        final List<SvgNode> stops = new ArrayList<>();
        for (final SvgNode c: node.children()) {
            if (c.tag().toLowerCase(Locale.ROOT).equals("stop")) stops.add(c);
        }
        if (stops.isEmpty() && tmpl != null) {
            for (final SvgNode c: tmpl.children()) {
                if (c.tag().toLowerCase(Locale.ROOT).equals("stop")) stops.add(c);
            }
        }
        return stops;
    }

    private static String attr(final SvgNode node, final SvgNode tmpl, final String name) {
        final String v = node.attr(name);
        if (v != null) return v;
        return tmpl != null ? tmpl.attr(name) : null;
    }

    // GRADIENT COORDINATE: OBB → FRACTION; userSpaceOnUse → USER UNITS (PERCENT SCALES BY VIEWPORT)
    private static double coord(final String value, final double defFraction, final boolean userSpace, final double ref) {
        if (value == null) return userSpace ? defFraction * ref : defFraction;
        if (userSpace) {
            return SVGParser.isPercent(value) ? SVGParser.ratioOrNumber(value, defFraction) * ref : SVGParser.length(value, defFraction);
        }
        return SVGParser.ratioOrNumber(value, defFraction);
    }
}
