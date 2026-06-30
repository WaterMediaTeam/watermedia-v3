package org.watermedia.api.codecs.readers.svg;

import java.util.Locale;
import java.util.Map;

/**
 * Parses SVG/CSS colour syntax into a packed {@code 0xAARRGGBB} integer: {@code #rgb}, {@code #rgba},
 * {@code #rrggbb}, {@code #rrggbbaa}, {@code rgb()}/{@code rgba()} (numeric or percentage),
 * {@code hsl()}/{@code hsla()}, the SVG named colours, {@code transparent} and {@code currentColor}.
 */
final class SvgColor {
    // SENTINEL RETURNED FOR AN UNPARSEABLE COLOUR TOKEN
    static final int INVALID = 0x00000001;

    private SvgColor() {}

    static int argb(final int a, final int r, final int g, final int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // PARSES A COLOUR; RETURNS currentColor FOR "currentColor", INVALID WHEN UNRECOGNISED
    static int parse(final String raw, final int currentColor) {
        if (raw == null) return INVALID;
        final String s = raw.trim();
        if (s.isEmpty()) return INVALID;

        if (s.charAt(0) == '#') return parseHex(s);

        final String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("transparent")) return 0;
        if (lower.equals("currentcolor")) return currentColor;
        if (lower.startsWith("rgb")) return parseRgb(lower);
        if (lower.startsWith("hsl")) return parseHsl(lower);

        final Integer named = NAMED.get(lower);
        return named != null ? named : INVALID;
    }

    private static int parseHex(final String s) {
        final String h = s.substring(1);
        try {
            switch (h.length()) {
                case 3 -> {
                    final int r = hex(h.charAt(0)) * 17, g = hex(h.charAt(1)) * 17, b = hex(h.charAt(2)) * 17;
                    return argb(255, r, g, b);
                }
                case 4 -> {
                    final int r = hex(h.charAt(0)) * 17, g = hex(h.charAt(1)) * 17, b = hex(h.charAt(2)) * 17, a = hex(h.charAt(3)) * 17;
                    return argb(a, r, g, b);
                }
                case 6 -> {
                    final int v = Integer.parseInt(h, 16);
                    return argb(255, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
                }
                case 8 -> {
                    final long v = Long.parseLong(h, 16);
                    return argb((int) (v & 0xFF), (int) ((v >> 24) & 0xFF), (int) ((v >> 16) & 0xFF), (int) ((v >> 8) & 0xFF));
                }
                default -> { return INVALID; }
            }
        } catch (final NumberFormatException e) {
            return INVALID;
        }
    }

    private static int parseRgb(final String s) {
        final int open = s.indexOf('('), close = s.indexOf(')');
        if (open < 0 || close < 0) return INVALID;
        final String[] parts = s.substring(open + 1, close).trim().split("[,\\s/]+");
        if (parts.length < 3) return INVALID;
        try {
            final int r = channel(parts[0]);
            final int g = channel(parts[1]);
            final int b = channel(parts[2]);
            final int a = parts.length >= 4 ? alpha(parts[3]) : 255;
            return argb(a, r, g, b);
        } catch (final NumberFormatException e) {
            return INVALID;
        }
    }

    private static int parseHsl(final String s) {
        final int open = s.indexOf('('), close = s.indexOf(')');
        if (open < 0 || close < 0) return INVALID;
        final String[] parts = s.substring(open + 1, close).trim().split("[,\\s/]+");
        if (parts.length < 3) return INVALID;
        try {
            double h = Double.parseDouble(parts[0].replace("deg", "").trim()) % 360.0;
            if (h < 0) h += 360.0;
            final double sat = pct(parts[1]);
            final double lig = pct(parts[2]);
            final int a = parts.length >= 4 ? alpha(parts[3]) : 255;
            return hslToArgb(h, sat, lig, a);
        } catch (final NumberFormatException e) {
            return INVALID;
        }
    }

    private static int hslToArgb(final double h, final double s, final double l, final int a) {
        final double c = (1 - Math.abs(2 * l - 1)) * s;
        final double hp = h / 60.0;
        final double x = c * (1 - Math.abs(hp % 2 - 1));
        double r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        final double m = l - c / 2;
        return argb(a, clamp255((r + m) * 255), clamp255((g + m) * 255), clamp255((b + m) * 255));
    }

    private static int channel(final String v) {
        final String t = v.trim();
        if (t.endsWith("%")) return clamp255(Double.parseDouble(t.substring(0, t.length() - 1)) * 255.0 / 100.0);
        return clamp255(Double.parseDouble(t));
    }

    private static int alpha(final String v) {
        final String t = v.trim();
        if (t.endsWith("%")) return clamp255(Double.parseDouble(t.substring(0, t.length() - 1)) * 255.0 / 100.0);
        return clamp255(Double.parseDouble(t) * 255.0);
    }

    private static double pct(final String v) {
        final String t = v.trim();
        final double d = t.endsWith("%") ? Double.parseDouble(t.substring(0, t.length() - 1)) : Double.parseDouble(t);
        return Math.max(0, Math.min(1, d / 100.0));
    }

    private static int clamp255(final double v) {
        final int i = (int) Math.round(v);
        return i < 0 ? 0 : Math.min(255, i);
    }

    private static int hex(final char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
        throw new NumberFormatException("bad hex: " + ch);
    }

    // COMMON SVG NAMED COLOURS (the references use hex/rgb/hsl; this covers the frequent names)
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("black", argb(255, 0, 0, 0)),
            Map.entry("white", argb(255, 255, 255, 255)),
            Map.entry("red", argb(255, 255, 0, 0)),
            Map.entry("green", argb(255, 0, 128, 0)),
            Map.entry("lime", argb(255, 0, 255, 0)),
            Map.entry("blue", argb(255, 0, 0, 255)),
            Map.entry("yellow", argb(255, 255, 255, 0)),
            Map.entry("cyan", argb(255, 0, 255, 255)),
            Map.entry("aqua", argb(255, 0, 255, 255)),
            Map.entry("magenta", argb(255, 255, 0, 255)),
            Map.entry("fuchsia", argb(255, 255, 0, 255)),
            Map.entry("gray", argb(255, 128, 128, 128)),
            Map.entry("grey", argb(255, 128, 128, 128)),
            Map.entry("silver", argb(255, 192, 192, 192)),
            Map.entry("maroon", argb(255, 128, 0, 0)),
            Map.entry("olive", argb(255, 128, 128, 0)),
            Map.entry("navy", argb(255, 0, 0, 128)),
            Map.entry("teal", argb(255, 0, 128, 128)),
            Map.entry("purple", argb(255, 128, 0, 128)),
            Map.entry("orange", argb(255, 255, 165, 0)),
            Map.entry("pink", argb(255, 255, 192, 203)),
            Map.entry("brown", argb(255, 165, 42, 42)),
            Map.entry("gold", argb(255, 255, 215, 0)),
            Map.entry("indigo", argb(255, 75, 0, 130)),
            Map.entry("violet", argb(255, 238, 130, 238)),
            Map.entry("darkgray", argb(255, 169, 169, 169)),
            Map.entry("darkgrey", argb(255, 169, 169, 169)),
            Map.entry("lightgray", argb(255, 211, 211, 211)),
            Map.entry("lightgrey", argb(255, 211, 211, 211)),
            Map.entry("dimgray", argb(255, 105, 105, 105)),
            Map.entry("crimson", argb(255, 220, 20, 60)),
            Map.entry("coral", argb(255, 255, 127, 80)),
            Map.entry("salmon", argb(255, 250, 128, 114)),
            Map.entry("tomato", argb(255, 255, 99, 71)),
            Map.entry("skyblue", argb(255, 135, 206, 235)),
            Map.entry("steelblue", argb(255, 70, 130, 180)),
            Map.entry("royalblue", argb(255, 65, 105, 225)),
            Map.entry("forestgreen", argb(255, 34, 139, 34)),
            Map.entry("seagreen", argb(255, 46, 139, 87)),
            Map.entry("khaki", argb(255, 240, 230, 140)),
            Map.entry("beige", argb(255, 245, 245, 220)),
            Map.entry("ivory", argb(255, 255, 255, 240)),
            Map.entry("lavender", argb(255, 230, 230, 250)),
            Map.entry("turquoise", argb(255, 64, 224, 208))
    );
}
