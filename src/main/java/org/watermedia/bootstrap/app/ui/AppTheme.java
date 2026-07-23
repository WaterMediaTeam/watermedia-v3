package org.watermedia.bootstrap.app.ui;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hi-fi handoff theme tokens.
 */
public final class AppTheme {

    private static final float FONT_BASE_PX = 20f;
    // CACHE OF alpha() RESULTS — alpha() IS CALLED MANY TIMES PER FRAME FROM DRAW CODE, SO REUSING THE
    // IMMUTABLE Color AVOIDS ALLOCATING GARBAGE EVERY FRAME. ACCESSED FROM BOTH THE RENDER THREAD (UI
    // DRAW) AND THE SWING EDT (SKIN STATIC INIT), SO ConcurrentHashMap PROVIDES THE NEEDED SAFETY.
    private static final Map<Integer, Color> ALPHA_CACHE = new ConcurrentHashMap<>();

    private AppTheme() {
    }

    public static final Color BG_0 = hex(0x04061A);
    public static final Color BG_1 = hex(0x0A0E1F);
    public static final Color BG_2 = hex(0x11172E);
    public static final Color BG_3 = hex(0x1B2342);
    public static final Color BG_2_ALPHA = rgba(17, 23, 46, 218);
    public static final Color BG_1_ALPHA = rgba(10, 14, 31, 218);

    public static final Color STROKE = rgba(110, 168, 255, 46);
    public static final Color STROKE_BRIGHT = rgba(110, 168, 255, 115);

    public static final Color TEXT = hex(0xD8E2FF);
    public static final Color TEXT_SOFT = hex(0x8AA0D0);
    public static final Color TEXT_FAINT = hex(0x5B6B94);

    public static final Color NEON = hex(0x6EA8FF);
    public static final Color NEON_LIGHT = hex(0x9BC2FF);
    public static final Color NEON_DARK = hex(0x4D7FD6);
    public static final Color CYAN = hex(0x5DD9FF);
    public static final Color GREEN = hex(0x6DFFB0);
    public static final Color AMBER = hex(0xFFC266);
    public static final Color RED = hex(0xFF6C8E);

    // SEMANTIC TYPE TOKENS. TINY/SUBTITLE/BODY DELIBERATELY RESOLVE TO THE SAME SCALE FOR NOW SO A LATER
    // RETUNE CAN DIFFERENTIATE THEM WITHOUT TOUCHING ANY CALL SITE — CALLERS STILL ENCODE THEIR INTENT.
    public static final float TEXT_TINY = textScale(12);
    public static final float TEXT_SUBTITLE = textScale(12);
    public static final float TEXT_BODY = textScale(12);
    public static final float TEXT_BUTTON = textScale(14);
    public static final float TEXT_SECTION = textScale(16);
    public static final float TEXT_DISPLAY = textScale(18);

    public static Color hex(final int rgb) {
        return new Color(rgb);
    }

    public static Color rgba(final int r, final int g, final int b, final int a) {
        return new Color(r, g, b, a);
    }

    public static Color alpha(final Color color, final int alpha) {
        final int a = alpha < 0 ? 0 : Math.min(alpha, 255);
        final int key = (color.getRGB() & 0xFFFFFF) << 8 | a;
        Color cached = ALPHA_CACHE.get(key);
        if (cached == null) {
            cached = new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
            ALPHA_CACHE.put(key, cached);
        }
        return cached;
    }

    private static float textScale(final int px) {
        return px / FONT_BASE_PX;
    }
}
