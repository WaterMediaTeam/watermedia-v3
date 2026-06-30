package org.watermedia.api.codecs.readers.svg;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds an {@link SvgNode} tree from SVG bytes using the JDK StAX reader, and hosts the small value
 * parsers shared by the renderer (transform lists, lengths, inline styles, point lists).
 *
 * <p>The reader is hardened against XXE: external entities and external DTD loading are disabled.
 * Internal DTDs are tolerated (many real SVGs carry a {@code <!DOCTYPE>}); the JDK's entity-expansion
 * limit guards against expansion-bomb inputs.
 */
final class SVGParser {
    private SVGParser() {}

    static SvgNode parse(final InputStream in) throws IOException {
        final XMLInputFactory factory = XMLInputFactory.newFactory();
        // BLOCK EXTERNAL ENTITY / EXTERNAL DTD RESOLUTION (XXE) WHILE STILL TOLERATING AN INTERNAL DOCTYPE
        setProperty(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", Boolean.FALSE);
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);
        // NAMESPACE-UNAWARE: STAY LENIENT LIKE THE REST OF THE DECODER. NAMESPACE-AWARE MODE MAKES AN
        // UNDECLARED PREFIX (e.g. xlink:href WITH NO xmlns:xlink) A FATAL ERROR; ATTRIBUTES ARE READ
        // BY LOCAL NAME (href / xlink:href BOTH HANDLED), SO PREFIX RESOLUTION IS NOT NEEDED
        setProperty(factory, XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);

        XMLStreamReader r = null;
        try {
            r = factory.createXMLStreamReader(in);
            final Deque<SvgNode> stack = new ArrayDeque<>();
            SvgNode root = null;
            while (r.hasNext()) {
                final int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    final Map<String, String> attrs = new HashMap<>();
                    final int count = r.getAttributeCount();
                    for (int i = 0; i < count; i++) {
                        attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                    }
                    final String style = attrs.get("style");
                    if (style != null) parseStyleInto(attrs, style);

                    final SvgNode node = new SvgNode(r.getLocalName(), attrs);
                    if (stack.isEmpty()) {
                        if (root == null) root = node;
                    } else {
                        stack.peek().children().add(node);
                    }
                    stack.push(node);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (!stack.isEmpty()) stack.pop();
                }
            }
            return root;
        } catch (final XMLStreamException e) {
            throw new IOException("Malformed SVG document: " + e.getMessage(), e);
        } finally {
            if (r != null) {
                try { r.close(); } catch (final XMLStreamException ignored) { /* BEST EFFORT */ }
            }
        }
    }

    private static void setProperty(final XMLInputFactory factory, final String name, final Object value) {
        // SOME IMPLEMENTATIONS REJECT UNKNOWN PROPERTIES — TREAT AS BEST EFFORT
        try {
            factory.setProperty(name, value);
        } catch (final IllegalArgumentException ignored) {
            // PROPERTY NOT SUPPORTED BY THIS PARSER
        }
    }

    // ----- INLINE STYLE: "prop: value; prop2: value2" OVERLAYS THE ATTRIBUTE MAP -----

    static void parseStyleInto(final Map<String, String> attrs, final String style) {
        for (final String decl: style.split(";")) {
            final int colon = decl.indexOf(':');
            if (colon <= 0) continue;
            final String prop = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            final String value = decl.substring(colon + 1).trim();
            if (!prop.isEmpty() && !value.isEmpty()) attrs.put(prop, value);
        }
    }

    // ----- TRANSFORM LIST → AFFINE (functions composed left to right) -----

    static Affine parseTransform(final String s) {
        if (s == null || s.isBlank()) return Affine.IDENTITY;
        Affine m = Affine.IDENTITY;
        int i = 0;
        final int n = s.length();
        while (i < n) {
            while (i < n && !Character.isLetter(s.charAt(i))) i++;
            final int nameStart = i;
            while (i < n && Character.isLetter(s.charAt(i))) i++;
            if (i >= n || nameStart == i) break;
            final String name = s.substring(nameStart, i);
            final int open = s.indexOf('(', i);
            final int close = open < 0 ? -1 : s.indexOf(')', open);
            if (open < 0 || close < 0) break;
            final double[] a = numbers(s.substring(open + 1, close));
            i = close + 1;
            m = m.concat(transformOf(name, a));
        }
        return m;
    }

    private static Affine transformOf(final String name, final double[] a) {
        return switch (name) {
            case "translate" -> Affine.translate(a.length > 0 ? a[0] : 0, a.length > 1 ? a[1] : 0);
            case "scale" -> Affine.scale(a.length > 0 ? a[0] : 1, a.length > 1 ? a[1] : (a.length > 0 ? a[0] : 1));
            case "rotate" -> a.length >= 3 ? Affine.rotate(a[0], a[1], a[2]) : Affine.rotate(a.length > 0 ? a[0] : 0);
            case "matrix" -> a.length >= 6 ? new Affine(a[0], a[1], a[2], a[3], a[4], a[5]) : Affine.IDENTITY;
            case "skewX" -> Affine.skewX(a.length > 0 ? a[0] : 0);
            case "skewY" -> Affine.skewY(a.length > 0 ? a[0] : 0);
            default -> Affine.IDENTITY;
        };
    }

    // ----- LENGTHS AND NUMBERS -----

    // PARSES A LENGTH INTO USER UNITS (px). UNKNOWN UNITS AND "%" FALL BACK TO THE RAW NUMBER.
    static double length(final String v, final double def) {
        if (v == null || v.isBlank()) return def;
        final String s = v.trim();
        int end = 0;
        final int n = s.length();
        if (end < n && (s.charAt(end) == '+' || s.charAt(end) == '-')) end++;
        boolean dot = false;
        while (end < n) {
            final char c = s.charAt(end);
            if (c >= '0' && c <= '9') end++;
            else if (c == '.' && !dot) { dot = true; end++; }
            else if (c == 'e' || c == 'E') {
                // CONSUME 'e'/'E' AS AN EXPONENT MARKER ONLY WHEN A (SIGNED) DIGIT FOLLOWS; OTHERWISE
                // IT IS A UNIT LETTER (em/ex) AND THE NUMBER ENDS HERE
                int k = end + 1;
                if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                if (k < n && s.charAt(k) >= '0' && s.charAt(k) <= '9') end = k;
                else break;
            } else break;
        }
        if (end == 0) return def;
        final double num;
        try {
            num = Double.parseDouble(s.substring(0, end));
        } catch (final NumberFormatException e) {
            return def;
        }
        final String unit = s.substring(end).trim().toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "", "px" -> num;
            case "pt" -> num * 96.0 / 72.0;
            case "pc" -> num * 16.0;
            case "in" -> num * 96.0;
            case "cm" -> num * 96.0 / 2.54;
            case "mm" -> num * 96.0 / 25.4;
            case "em" -> num * 16.0;
            case "ex" -> num * 8.0;
            default -> num; // INCLUDING "%": HANDLED BY CALLERS THAT HAVE A REFERENCE LENGTH
        };
    }

    static boolean isPercent(final String v) {
        return v != null && v.trim().endsWith("%");
    }

    // PARSES A NUMBER POSSIBLY EXPRESSED AS A PERCENT (RETURNS THE FRACTION FOR "%", ELSE THE NUMBER)
    static double ratioOrNumber(final String v, final double def) {
        if (v == null || v.isBlank()) return def;
        final String s = v.trim();
        try {
            if (s.endsWith("%")) return Double.parseDouble(s.substring(0, s.length() - 1)) / 100.0;
            return Double.parseDouble(s);
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    // SCANS A NUMBER LIST, TOLERATING SVG'S COMPACT FORMS: NUMBERS SEPARATED BY WHITESPACE, COMMAS,
    // OR ONLY A SIGN (e.g. points="100-50") OR A DECIMAL POINT, PLUS EXPONENTS
    static double[] numbers(final String s) {
        if (s == null || s.isBlank()) return new double[0];
        final int n = s.length();
        double[] out = new double[8];
        int k = 0, i = 0;
        while (i < n) {
            final char sep = s.charAt(i);
            if (sep == ' ' || sep == ',' || sep == '\t' || sep == '\n' || sep == '\r' || sep == '\f') { i++; continue; }
            final int start = i;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
            boolean dot = false, digits = false;
            while (i < n) {
                final char c = s.charAt(i);
                if (c >= '0' && c <= '9') { digits = true; i++; }
                else if (c == '.' && !dot) { dot = true; i++; }
                else break;
            }
            if (digits && i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int j = i + 1;
                if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
                if (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                    i = j + 1;
                    while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                }
            }
            if (!digits) { i = start + 1; continue; } // SKIP A STRAY NON-NUMERIC CHARACTER
            if (k == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            try {
                // parseDouble IS EVALUATED BEFORE THE STORE, SO ON FAILURE k IS LEFT UNCHANGED
                out[k++] = Double.parseDouble(s.substring(start, i));
            } catch (final NumberFormatException e) {
                // DROP MALFORMED TOKEN
            }
        }
        return k == out.length ? out : java.util.Arrays.copyOf(out, k);
    }
}
