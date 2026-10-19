package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.util.MathUtil;

import java.util.Locale;
import java.util.Map;

/**
 * Immutable inherited render state: the current device transform plus the cascaded presentation
 * values (fill/stroke paint, opacities, fill-rule, stroke width, {@code currentColor}). A child
 * state is produced with {@link #derive(SvgNode, Map, double)}, which reads an element's attributes
 * and falls back to this state for anything the element does not override — the SVG inheritance model.
 *
 * <p>Element {@code opacity} is folded multiplicatively into the subtree opacity (parent × child),
 * which is exact except for an isolated group whose children overlap — an intentional limitation.
 * Modelled as a record so each derived state is an allocation-friendly immutable value.
 *
 * @param ctm           current device transform
 * @param fill          fill paint, or {@code null} for no fill
 * @param fillOpacity   inherited fill opacity
 * @param evenOdd       fill-rule (true = even-odd)
 * @param stroke        stroke paint, or {@code null} for no stroke
 * @param strokeWidth   stroke width in user units
 * @param strokeOpacity inherited stroke opacity
 * @param opacity       folded subtree opacity
 * @param color         {@code currentColor} as packed ARGB
 */
record RenderState(Affine ctm, SvgPaint fill, float fillOpacity, boolean evenOdd, SvgPaint stroke,
                   double strokeWidth, float strokeOpacity, float opacity, int color, boolean visible) {

    // SVG INITIAL VALUES: BLACK FILL, NO STROKE, FULL OPACITY, NON-ZERO FILL RULE, VISIBLE
    static RenderState root(final Affine ctm) {
        return new RenderState(ctm, SvgPaint.solid(0xFF000000), 1f, false, null, 1.0, 1f, 1f, 0xFF000000, true);
    }

    float fillEff() { return this.fillOpacity * this.opacity; }
    float strokeEff() { return this.strokeOpacity * this.opacity; }

    RenderState derive(final SvgNode node, final Map<String, SvgPaint> gradients, final double diag) throws CodecException {
        final Map<String, String> a = node.attrs();

        Affine ctm = this.ctm;
        final String tf = a.get("transform");
        if (tf != null) ctm = ctm.concat(SvgParser.parseTransform(tf));

        int color = this.color;
        final String colorV = a.get("color");
        if (colorV != null && !colorV.equalsIgnoreCase("inherit")) {
            final long c = SvgColor.parse(colorV, this.color);
            if (c != SvgColor.INVALID) color = (int) c;
        }

        final SvgPaint fill = resolvePaint(a.get("fill"), this.fill, gradients, color);
        final SvgPaint stroke = resolvePaint(a.get("stroke"), this.stroke, gradients, color);

        final float fillOpacity = opacityValue(a.get("fill-opacity"), this.fillOpacity);
        final float strokeOpacity = opacityValue(a.get("stroke-opacity"), this.strokeOpacity);

        float opacity = this.opacity;
        final String op = a.get("opacity");
        if (op != null) opacity = this.opacity * (float) MathUtil.clamp(SvgParser.ratioOrNumber(op, 1.0), 0.0, 1.0);

        boolean evenOdd = this.evenOdd;
        final String fr = a.get("fill-rule");
        if (fr != null) {
            if (fr.equalsIgnoreCase("evenodd")) evenOdd = true;
            else if (fr.equalsIgnoreCase("nonzero")) evenOdd = false;
        }

        // visibility IS INHERITED BUT A CHILD CAN RE-SHOW ITSELF WITH visibility:visible
        boolean visible = this.visible;
        final String vis = a.get("visibility");
        if (vis != null) {
            final String vv = vis.trim().toLowerCase(Locale.ROOT);
            if (vv.equals("hidden") || vv.equals("collapse")) visible = false;
            else if (vv.equals("visible")) visible = true;
        }

        double strokeWidth = this.strokeWidth;
        final String sw = a.get("stroke-width");
        // PERCENTAGE STROKE WIDTH RESOLVES AGAINST THE NORMALIZED VIEWPORT DIAGONAL PER SVG SPEC.
        // A NON-FINITE WIDTH ("1e999") IS REJECTED BY length(): AN INFINITE HALF-WIDTH MAKES EVERY
        // JOIN LOOK SHARP AND APPENDS AN INFINITE-RADIUS DISC PER VERTEX, WHICH IS AN INSTANT OOM
        if (sw != null) strokeWidth = SvgParser.length(sw, this.strokeWidth, diag);

        return new RenderState(ctm, fill, fillOpacity, evenOdd, stroke, strokeWidth, strokeOpacity, opacity, color, visible);
    }

    // RESOLVES A fill/stroke VALUE: none → null, url(#id) → gradient, colour → solid, else inherit
    private static SvgPaint resolvePaint(final String value, final SvgPaint inherited,
                                         final Map<String, SvgPaint> gradients, final int color) {
        if (value == null) return inherited;
        final String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("inherit")) return inherited;
        if (v.equalsIgnoreCase("none")) return null;

        if (v.regionMatches(true, 0, "url(", 0, 4)) {
            final int close = v.indexOf(')');
            final int hash = v.indexOf('#');
            if (hash >= 0 && close > hash) {
                String id = v.substring(hash + 1, close).trim();
                id = stripQuotes(id);
                final SvgPaint g = gradients.get(id);
                if (g != null) return g;
            }
            // OPTIONAL FALLBACK PAINT AFTER url(...) e.g. "url(#x) red"
            if (close >= 0 && close + 1 < v.length()) {
                final String fb = v.substring(close + 1).trim();
                if (fb.equalsIgnoreCase("none")) return null;
                final long c = SvgColor.parse(fb, color);
                if (c != SvgColor.INVALID) return SvgPaint.solid((int) c);
            }
            return null;
        }

        final long c = SvgColor.parse(v, color);
        return c == SvgColor.INVALID ? inherited : SvgPaint.solid((int) c);
    }

    private static String stripQuotes(final String s) {
        if (s.length() >= 2) {
            final char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static float opacityValue(final String v, final float inherited) throws CodecException {
        if (v == null) return inherited;
        final String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("inherit")) return inherited;
        return (float) MathUtil.clamp(SvgParser.ratioOrNumber(v, inherited), 0.0, 1.0);
    }
}
