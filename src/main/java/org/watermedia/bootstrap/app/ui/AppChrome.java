package org.watermedia.bootstrap.app.ui;

import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.util.MediaType;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;

/**
 * Shared app chrome and hi-fi handoff primitives.
 */
public final class AppChrome {

    public static final int TITLEBAR_H = 28;
    public static final int HEADER_H = 124;
    public static final int FOOTER_H = 44;
    public static final int CONTENT_PAD = 18;
    private static final int WIN_BUTTON_W = 38;
    private static boolean crtEnabled = true;

    private AppChrome() {
    }

    public static int contentTop() {
        return TITLEBAR_H + HEADER_H + 10;
    }

    public static int contentBottom(final int windowH) {
        return windowH - FOOTER_H - 10;
    }

    public static void screen(final TextRenderer text, final AppContext ctx,
                              final int windowW, final int windowH,
                              final String name, final String sub, final String right) {
        background(windowW, windowH);
        titlebar(text, ctx, windowW, AppContext.APP_NAME);
        header(text, ctx, windowW, name, sub, right);
    }

    public static void background(final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fillGradientV(0, 0, windowW, windowH,
                12f / 255f, 18f / 255f, 44f / 255f, 1f,
                4f / 255f, 6f / 255f, 22f / 255f, 1f);
        RenderSystem.fillGradientV(0, 0, windowW, Math.min(150, windowH / 4),
                31f / 255f, 46f / 255f, 92f / 255f, 0.42f,
                4f / 255f, 6f / 255f, 22f / 255f, 0f);
        for (int y = 0; y < windowH; y += 3) {
            RenderSystem.fill(0, y + 2, windowW, 1, 0f, 0f, 0f, 0.15f);
        }
        RenderSystem.restoreProjection();
    }

    public static void titlebar(final TextRenderer text, final AppContext ctx,
                                final int windowW, final String title) {
        RenderSystem.setupOrtho(windowW, ctx.windowHeight);
        RenderSystem.fillGradientV(0, 0, windowW, TITLEBAR_H,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 1f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 1f);
        RenderSystem.lineH(0, TITLEBAR_H - 1, windowW, AppTheme.STROKE_BRIGHT, 1f);
        if (ctx.iconTextureId > 0) {
            RenderSystem.bindTexture(ctx.iconTextureId);
            RenderSystem.color(1f, 1f, 1f, 1f);
            RenderSystem.blit(8, 6, 16, 16);
        }

        text.render(title, 32, textY(text, 0, TITLEBAR_H, AppTheme.TEXT_SUBTITLE), AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE);
        for (int i = 0; i < 3; i++) {
            final int bx = windowW - WIN_BUTTON_W * (3 - i);
            final boolean hover = ctx.mouseY >= 0 && ctx.mouseY <= TITLEBAR_H && ctx.mouseX >= bx && ctx.mouseX <= bx + WIN_BUTTON_W;
            final Color color = i == 2
                    ? (hover ? AppTheme.RED : AppTheme.alpha(AppTheme.RED, 190))
                    : (hover ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT);
            final int cx = bx + WIN_BUTTON_W / 2;
            if (i == 0) {
                RenderSystem.lineH(cx - 5, 17, 10, color, 1f);
            } else if (i == 1) {
                if (ctx.windowMaximized) {
                    RenderSystem.rect(cx - 7, 11, 9, 8, color, 1f);
                    RenderSystem.rect(cx - 3, 8, 9, 8, color, 1f);
                } else {
                    RenderSystem.rect(cx - 5, 9, 10, 10, color, 1f);
                }
            } else {
                PixelIcon.draw("x", cx - 6, 8, 12, color);
            }
        }
        RenderSystem.restoreProjection();
    }

    public static void header(final TextRenderer text, final AppContext ctx,
                              final int windowW, final String name, final String sub,
                              final String right) {
        final int y = TITLEBAR_H;
        RenderSystem.setupOrtho(windowW, ctx.windowHeight);
        RenderSystem.fillGradientV(0, y, windowW, HEADER_H,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 0.86f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.55f);
        RenderSystem.lineH(0, y + HEADER_H - 7, windowW, AppTheme.NEON, 2f);
        RenderSystem.lineH(0, y + HEADER_H - 2, windowW, AppTheme.NEON_DARK, 1f);
        RenderSystem.glowRect(0, y + HEADER_H - 8, windowW, 4, 0f, AppTheme.NEON, 0.16f);
        final int bannerX = 18;
        final int bannerW = Math.min(665, Math.max(430, (int) (windowW * 0.40f)));
        final int actualBannerW = bannerRenderedWidth(ctx, bannerW, 100);
        final int sepX = bannerX + actualBannerW + 14;
        RenderSystem.fill(sepX, y + 38, 4, 42, AppTheme.AMBER);
        RenderSystem.glowRect(sepX - 1, y + 38, 6, 42, 0f, AppTheme.AMBER, 0.24f);

        renderBanner(ctx, bannerX, y + 10, bannerW, 100);
        final int titleX = sepX + 24;
        final int rightReserve = right != null && !right.isEmpty()
                ? tagWidth(text, right) + 52
                : 24;
        final float titleScale = AppTheme.TEXT_SECTION;
        final float subScale = AppTheme.TEXT_BODY;
        final int titleMaxW = Math.max(120, windowW - titleX - rightReserve);
        final String title = text.truncateToWidth(name.toUpperCase(), titleMaxW, titleScale, java.awt.Font.BOLD);
        final int titleBoxY = y + 42;
        final int titleBoxH = 36;
        text.renderBold(title, titleX, boldTextY(text, titleBoxY, titleBoxH, titleScale), AppTheme.NEON_LIGHT, titleScale);
        if (sub != null && !sub.isEmpty()) {
            final int subX = titleX + text.widthBold(title, titleScale) + 18;
            final int subMaxW = Math.max(80, windowW - subX - rightReserve);
            final String subtitle = text.truncateToWidth(sub.toUpperCase(), subMaxW, subScale);
            text.render(subtitle, subX, textY(text, titleBoxY, titleBoxH, subScale), AppTheme.TEXT_FAINT, subScale);
        }
        if (right != null && !right.isEmpty()) {
            tag(text, windowW - tagWidth(text, right) - 32, y + 25, right, AppTheme.TEXT_SOFT);
        }
        RenderSystem.restoreProjection();
    }

    public static void footer(final TextRenderer text, final AppContext ctx,
                              final int windowW, final int windowH,
                              final String instructions, final float mediaProgress) {
        final int y = windowH - FOOTER_H;
        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fillGradientV(0, y, windowW, FOOTER_H,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 1f,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 1f);
        RenderSystem.lineH(0, y, windowW, AppTheme.STROKE_BRIGHT, 1f);
        if (mediaProgress >= 0f) {
            RenderSystem.fill(0, y - 6, windowW, 4, AppTheme.BG_3);
            RenderSystem.fillGradientH(0, y - 6, windowW * mediaProgress, 4,
                    AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                    AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
        }

        renderBindings(text, instructions == null ? "" : instructions, 14, y + 10);
        backendStrip(text, ctx, windowW - backendStripWidth(text, ctx) - 18, y + 10);
        RenderSystem.restoreProjection();
    }

    private static void renderBindings(final TextRenderer text, final String instructions, final int x, final int y) {
        int cx = x;
        for (final String raw : instructions.split("\\|")) {
            final String part = raw.trim();
            if (part.isEmpty()) continue;
            final int sep = part.indexOf(':');
            final String key = sep >= 0 ? part.substring(0, sep).trim() : "";
            final String label = sep >= 0 ? part.substring(sep + 1).trim() : part;
            if (!key.isEmpty()) {
                final float keyScale = AppTheme.TEXT_BODY;
                final int keyW = Math.max(36, scaledWidth(text, key, keyScale) + 36);
                final int keyH = 22;
                final int keyY = y - 1;
                RenderSystem.fill(cx, keyY, keyW, keyH, AppTheme.alpha(AppTheme.BG_2, 220));
                RenderSystem.rect(cx, keyY, keyW, keyH, AppTheme.STROKE_BRIGHT, 1f);
                PixelIcon.draw(iconForKey(key), cx + 7, keyY + 5, 12, AppTheme.TEXT_FAINT);
                text.render(key.toUpperCase(), cx + 24, compactTextY(text, keyY, keyH, keyScale) + 1, AppTheme.TEXT_SOFT, keyScale);
                cx += keyW + 6;
            }
            text.render(label, cx, textY(text, y - 1, 22, AppTheme.TEXT_BODY) + 1, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
            cx += text.width(label, AppTheme.TEXT_BODY) + 18;
        }
    }

    private static String iconForKey(final String key) {
        final String k = key.toUpperCase();
        if (k.contains("ARROW") || k.contains("UP") || k.contains("DOWN") || k.contains("LEFT") || k.contains("RIGHT")) return "arrows";
        if (k.contains("ENTER")) return "play";
        if (k.contains("ESC")) return "x";
        if (k.contains("SPACE")) return "pause";
        return "info";
    }

    public static void sectionHead(final TextRenderer text, final String label, final String count,
                                   final int x, final int y) {
        final int headY = y + 3;
        final int headH = 21;
        RenderSystem.fill(x, headY, 4, headH, AppTheme.NEON);
        RenderSystem.glowRect(x, headY, 4, headH, 0f, AppTheme.NEON, 0.16f);
        text.renderBold(label.toUpperCase(), x + 18, boldTextY(text, headY, headH, AppTheme.TEXT_SECTION), AppTheme.NEON, AppTheme.TEXT_SECTION);
        if (count != null) {
            text.render(count.toUpperCase(), x + 30 + text.widthBold(label.toUpperCase(), AppTheme.TEXT_SECTION),
                    textY(text, headY, headH, AppTheme.TEXT_BODY), AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        }
    }

    public static void panel(final int x, final int y, final int w, final int h, final boolean anchored) {
        RenderSystem.fillGradientV(x, y, w, h,
                17f / 255f, 23f / 255f, 46f / 255f, 0.85f,
                10f / 255f, 14f / 255f, 31f / 255f, 0.85f);
        RenderSystem.rect(x, y, w, h, AppTheme.STROKE_BRIGHT, 1f);
        if (anchored) {
            amberCube(x - 1, y - 1, 10);
            amberCube(x + w - 9, y + h - 9, 10);
        }
    }

    public static void tvFrame(final int x, final int y, final int w, final int h, final boolean focused) {
        RenderSystem.fill(x, y, w, h, AppTheme.BG_2);
        RenderSystem.rect(x, y, w, h, focused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 2f);
        if (focused) RenderSystem.glowRect(x, y, w, h, 0f, AppTheme.NEON, 0.48f);
        RenderSystem.fill(x + 6, y + 6, w - 12, h - 12, AppTheme.BG_0);
        RenderSystem.rect(x + 6, y + 6, w - 12, h - 12, AppTheme.STROKE, 1f);
        amberCube(x + 2, y + h - 12, 10);
        amberCube(x + w - 12, y + h - 12, 10);
    }

    public static void crtOverlay(final int x, final int y, final int w, final int h) {
        crtOverlay(x, y, w, h, -1);
    }

    public static void crtOverlay(final int x, final int y, final int w, final int h, final int canvasH) {
        if (!crtEnabled) return;
        if (canvasH > 0) RenderSystem.clip(x, y, w, h, canvasH);
        for (int sy = y; sy < y + h; sy += 3) {
            RenderSystem.fill(x, sy + 2, w, 1, 0f, 0f, 0f, 0.22f);
        }
        final int bandH = Math.max(38, h / 9);
        final int travel = Math.max(1, h + bandH * 2);
        final int bandY = y - bandH * 2 + (int) ((System.currentTimeMillis() / 24L) % travel);
        for (int i = 0; i < 2; i++) {
            final int by = bandY + i * travel;
            RenderSystem.fillGradientV(x, by, w, bandH,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0f,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0.14f);
            RenderSystem.fillGradientV(x, by + bandH, w, bandH,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0.14f,
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0f);
        }
        if (canvasH > 0) RenderSystem.clearClip();
    }

    public static void toggleCrt() {
        crtEnabled = !crtEnabled;
    }

    public static boolean crtEnabled() {
        return crtEnabled;
    }

    public static void amberTriangle(final int x, final int y, final int size, final boolean left) {
        RenderSystem.glowRect(x, y, size, size, 0f, AppTheme.AMBER, 0.32f);
        if (left) {
            RenderSystem.fillTriangle(x, y, x + size, y, x, y + size,
                    AppTheme.AMBER.getRed() / 255f, AppTheme.AMBER.getGreen() / 255f, AppTheme.AMBER.getBlue() / 255f, 1f);
        } else {
            RenderSystem.fillTriangle(x + size, y, x + size, y + size, x, y + size,
                    AppTheme.AMBER.getRed() / 255f, AppTheme.AMBER.getGreen() / 255f, AppTheme.AMBER.getBlue() / 255f, 1f);
        }
    }

    public static void amberCube(final int x, final int y, final int size) {
        RenderSystem.glowRect(x, y, size, size, 0f, AppTheme.AMBER, 0.32f);
        RenderSystem.fill(x, y, size, size, AppTheme.AMBER);
    }

    public static void tag(final TextRenderer text, final int x, final int y,
                           final String label, final Color color) {
        final float scale = AppTheme.TEXT_BODY;
        final int w = tagWidth(text, label);
        final int h = 24;
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, 170));
        RenderSystem.rect(x, y, w, h, color, 1f);
        text.render(label.toUpperCase(), x + 14, compactTextY(text, y, h, scale) + 1, color, scale);
    }

    public static int tagWidth(final TextRenderer text, final String label) {
        return scaledWidth(text, label, AppTheme.TEXT_BODY) + 28;
    }

    public static int mediaTypeTag(final TextRenderer text, final int x, final int y, final MediaType type) {
        final String label = type == null ? "MEDIA" : type.name();
        final Color color = mediaTypeColor(type);
        final int w = text.width(label, AppTheme.TEXT_BODY) + 22;
        final int h = 22;
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, 188));
        RenderSystem.rect(x, y, w, h, color, 1f);
        RenderSystem.glowRect(x, y, w, h, 0f, color, 0.16f);
        text.render(label, x + 11, textY(text, y, h, AppTheme.TEXT_BODY), color, AppTheme.TEXT_BODY);
        return w;
    }

    public static Color mediaTypeColor(final MediaType type) {
        if (type == MediaType.IMAGE) return AppTheme.GREEN;
        if (type == MediaType.VIDEO) return AppTheme.AMBER;
        if (type == MediaType.AUDIO) return AppTheme.CYAN;
        return AppTheme.TEXT_FAINT;
    }

    public static void dialogCloseButton(final Dimension bounds, final boolean hover) {
        final Color color = hover ? new Color(255, 132, 160) : AppTheme.RED;
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover ? AppTheme.alpha(AppTheme.RED, 76) : AppTheme.alpha(AppTheme.BG_1, 200));
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color, 1.4f);
        if (hover) RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, color, 0.28f);
        PixelIcon.draw("x", bounds.x() + (bounds.width() - 14) / 2, bounds.y() + (bounds.height() - 14) / 2, 14, color);
    }

    private static int scaledWidth(final TextRenderer text, final String value, final float scale) {
        return text.width(value == null ? "" : value.toUpperCase(), scale);
    }

    private static int textY(final TextRenderer text, final int y, final int h, final float scale) {
        return y + Math.max(0, (h - text.glyphHeight(scale)) / 2);
    }

    private static int boldTextY(final TextRenderer text, final int y, final int h, final float scale) {
        return y + Math.max(0, (h - text.glyphHeightBold(scale)) / 2);
    }

    private static int compactTextY(final TextRenderer text, final int y, final int h, final float scale) {
        return textY(text, y, h, scale);
    }

    public static void pip(final int x, final int y, final Color color) {
        RenderSystem.fill(x, y, 8, 8, color);
        RenderSystem.glowRect(x, y, 8, 8, 0f, color, 0.42f);
    }

    public static void statusPip(final int x, final int y, final int size, final Color color, final boolean circle) {
        if (circle) {
            RenderSystem.fillCircle(x + size / 2f, y + size / 2f, size / 2f, color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, color.getAlpha() / 255f);
        } else {
            RenderSystem.fill(x, y, size, size, color);
        }
        RenderSystem.glowRect(x, y, size, size, 0f, color, 0.5f);
    }

    public static void backendStrip(final TextRenderer text, final int x, final int y,
                                    final boolean ffmpeg, final boolean opengl, final boolean vulkan) {
        backendStrip(text, null, x, y);
    }

    public static void backendStrip(final TextRenderer text, final AppContext ctx, final int x, final int y) {
        final boolean pending = ctx != null && ctx.backendsLoading && !FFMediaPlayer.loaded() && !FFMediaPlayer.loadError();
        int cx = x;
        if (ctx != null && ctx.configStatusVisible) {
            cx += backendTag(text, cx, y, "CONFIG", true, ctx.configStatusPulse,
                    ctx.configStatusError, ctx.configStatusWarn, ctx.configStatusStrike);
        }
        cx += backendTag(text, cx, y, "FFMPEG", FFMediaPlayer.loaded(), pending, FFMediaPlayer.loadError(), false, false);
        cx += backendTag(text, cx, y, "OpenGL", true, false, false, false, false);
        backendTag(text, cx, y, "Vulkan", false, false, true, false, true);
    }

    public static int backendStripWidth(final TextRenderer text) {
        return backendStripWidth(text, null);
    }

    public static int backendStripWidth(final TextRenderer text, final AppContext ctx) {
        final int config = ctx != null && ctx.configStatusVisible
                ? backendTagWidth(text, "CONFIG")
                : 0;
        return config + backendTagWidth(text, "FFMPEG") + backendTagWidth(text, "OpenGL") + backendTagWidth(text, "Vulkan");
    }

    private static int backendTagWidth(final TextRenderer text, final String label) {
        return scaledWidth(text, label, AppTheme.TEXT_BODY) + 44;
    }

    private static int backendTag(final TextRenderer text, final int x, final int y,
                                  final String label, final boolean on, final boolean pending,
                                  final boolean error, final boolean warn, final boolean strike) {
        final int w = backendTagWidth(text, label);
        final float pulse = pending ? 0.42f + 0.35f * (float) ((Math.sin(System.currentTimeMillis() / 180.0) + 1.0) * 0.5) : 1f;
        final Color base = error || !on ? AppTheme.RED : warn ? AppTheme.AMBER : pending ? AppTheme.TEXT_FAINT : AppTheme.GREEN;
        final Color c = AppTheme.alpha(base, Math.max(70, Math.min(255, (int) (255 * pulse))));
        RenderSystem.fill(x, y, w, 22, AppTheme.alpha(AppTheme.BG_1, 190));
        RenderSystem.rect(x, y, w, 22, AppTheme.STROKE_BRIGHT, 1f);
        pip(x + 8, y + 7, c);
        final int textX = x + 24;
        final int textW = scaledWidth(text, label, AppTheme.TEXT_BODY);
        final int textY = compactTextY(text, y, 22, AppTheme.TEXT_BODY);
        text.render(label.toUpperCase(), textX, textY, c, AppTheme.TEXT_BODY);
        if (strike) {
            RenderSystem.lineH(textX, textY + Math.max(1, text.glyphHeight(AppTheme.TEXT_BODY) / 2), textW, c, 1f);
        }
        return w;
    }

    public static void renderBanner(final AppContext ctx, final int x, final int y, final int maxW, final int maxH) {
        if (ctx.bannerTextureId <= 0 || ctx.bannerWidth <= 0 || ctx.bannerHeight <= 0) return;
        final float scale = Math.min((float) maxW / ctx.bannerWidth, (float) maxH / ctx.bannerHeight);
        final int w = (int) (ctx.bannerWidth * scale);
        final int h = (int) (ctx.bannerHeight * scale);
        RenderSystem.bindTexture(ctx.bannerTextureId);
        RenderSystem.color(1f, 1f, 1f, 1f);
        RenderSystem.blit(x, y + (maxH - h) / 2f, w, h);
    }

    private static int bannerRenderedWidth(final AppContext ctx, final int maxW, final int maxH) {
        if (ctx.bannerTextureId <= 0 || ctx.bannerWidth <= 0 || ctx.bannerHeight <= 0) return maxW;
        final float scale = Math.min((float) maxW / ctx.bannerWidth, (float) maxH / ctx.bannerHeight);
        return (int) (ctx.bannerWidth * scale);
    }

    public static boolean isTitlebarControl(final double mx, final int windowW) {
        return mx >= windowW - WIN_BUTTON_W * 3;
    }

    public static boolean handleTitlebarClick(final double mx, final double my, final int windowW,
                                              final Runnable minimize, final Runnable maximize,
                                              final Runnable close) {
        if (my < 0 || my > TITLEBAR_H || mx < windowW - WIN_BUTTON_W * 3) return false;
        final int idx = (int) ((mx - (windowW - WIN_BUTTON_W * 3)) / WIN_BUTTON_W);
        switch (idx) {
            case 0 -> minimize.run();
            case 1 -> maximize.run();
            case 2 -> close.run();
            default -> {
                return false;
            }
        }
        return true;
    }
}
