package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Boot splash matching the handoff loading layout: centered banner, glowing
 * pack icon, one thin aggregate progress bar, and a stacked boot log.
 */
public class LoadingScreen extends Screen {

    private static final int BANNER_MAX_W = 1000;
    private static final int BANNER_MAX_H = 170;
    private static final int BANNER_UP_SHIFT = 18;
    private static final int ICON_SIZE = 72;
    private static final int DUCK_SIZE = 216;
    private static final int BAR_W = 380;
    private static final int BAR_H = 8;
    private static final int STATUS_LINE_H = 28;
    private static final int PROGRESS_ANIM_MS = 520;
    private static final float TITLE_SCALE = 0.84f;
    private static final float STATUS_SCALE = 0.78f;

    private final List<StatusLine> completedApis = new ArrayList<>();

    private WaterMediaAPI trackedApi;
    private String trackedApiName = "";
    private String trackedStepName = "";
    private float displayedProgress;
    private float progressFrom;
    private float progressTarget;
    private long progressAnimStartMs;

    public LoadingScreen(final TextRenderer text, final AppContext ctx) {
        super(text, ctx);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        AppChrome.background(windowW, windowH);
        AppChrome.titlebar(this.text, this.ctx, windowW, AppContext.APP_NAME);

        RenderSystem.setupOrtho(windowW, windowH);

        final WaterMediaAPI api = WaterMedia.currentAPI();
        this.updateApiHistory(api);

        final int bodyTop = AppChrome.TITLEBAR_H;
        final int bodyBottom = windowH - AppChrome.FOOTER_H;
        final int bodyH = Math.max(1, bodyBottom - bodyTop);
        final ImageBox banner = this.fitImage(this.ctx.bannerWidth, this.ctx.bannerHeight,
                Math.min(BANNER_MAX_W, Math.max(180, windowW - 96)), BANNER_MAX_H);
        final List<StatusLine> statusLines = this.statusLines(api);

        final int loadingImageSize = this.ctx.duckFrameTextureIds.length > 0 ? DUCK_SIZE : ICON_SIZE;
        final int contentH = banner.h() + 24 + loadingImageSize + 22
                + this.text.glyphHeight(TITLE_SCALE) + 24
                + BAR_H + 26
                + Math.min(statusLines.size(), 8) * STATUS_LINE_H;
        final int bannerShift = banner.valid() ? BANNER_UP_SHIFT : 0;
        int y = bodyTop + Math.max(32, (bodyH - contentH - bannerShift) / 2) + bannerShift;

        if (banner.valid()) {
            final int bannerX = (windowW - banner.w()) / 2;
            this.renderImageGlow(this.ctx.bannerGlowTextureId, this.ctx.bannerGlowWidth, this.ctx.bannerGlowHeight,
                    this.ctx.bannerWidth, this.ctx.bannerHeight, bannerX, y - bannerShift, banner.w(), banner.h());
            this.renderTexture(this.ctx.bannerTextureId, bannerX, y - bannerShift, banner.w(), banner.h());
            y += banner.h() + 24;
        }

        if (this.ctx.duckFrameTextureIds.length > 0 && this.ctx.duckFrameWidth > 0 && this.ctx.duckFrameHeight > 0) {
            final ImageBox duck = this.fitImage(this.ctx.duckFrameWidth, this.ctx.duckFrameHeight, DUCK_SIZE, DUCK_SIZE);
            final int duckX = (windowW - duck.w()) / 2;
            final int frame = (int) ((System.currentTimeMillis() / 90L) % this.ctx.duckFrameTextureIds.length);
            this.renderTexture(this.ctx.duckFrameTextureIds[frame], duckX, y, duck.w(), duck.h());
            y += DUCK_SIZE + 22;
        } else if (this.ctx.iconTextureId > 0) {
            final ImageBox icon = this.fitImage(this.ctx.iconWidth, this.ctx.iconHeight, ICON_SIZE, ICON_SIZE);
            final int iconX = (windowW - icon.w()) / 2;
            this.renderImageGlow(this.ctx.iconGlowTextureId, this.ctx.iconGlowWidth, this.ctx.iconGlowHeight,
                    this.ctx.iconWidth, this.ctx.iconHeight, iconX, y, icon.w(), icon.h());
            this.renderTexture(this.ctx.iconTextureId, iconX, y, icon.w(), icon.h());
            y += ICON_SIZE + 22;
        }

        final String title = "LOADING WATERMEDIA...";
        final float titleW = this.spacedWidth(title, TITLE_SCALE, 3);
        this.renderSpaced(title, (windowW - titleW) / 2f, y, AppTheme.NEON_LIGHT, TITLE_SCALE, 3);
        y += this.text.glyphHeight(TITLE_SCALE) + 24;

        final int barW = Math.min(BAR_W, Math.max(180, windowW - 96));
        final int barX = (windowW - barW) / 2;
        this.renderProgressBar(barX, y, barW, BAR_H, this.animatedProgress(this.targetProgress()));
        y += BAR_H + 26;

        final int statusW = Math.min(920, Math.max(360, windowW - 96));
        final int statusX = (windowW - statusW) / 2;
        final int maxLines = Math.max(4, Math.min(8, (bodyBottom - y - 12) / STATUS_LINE_H));
        final int firstLine = Math.max(0, statusLines.size() - maxLines);
        for (int i = firstLine; i < statusLines.size(); i++) {
            this.renderStatusLine(statusLines.get(i), statusX, y);
            y += STATUS_LINE_H;
        }

        RenderSystem.restoreProjection();
        AppChrome.footer(this.text, this.ctx, windowW, windowH, "ESC: Cancel", -1f);
    }

    private float targetProgress() {
        return clamp01((float) WaterMedia.completedWorkSteps() / Math.max(1, WaterMedia.totalWorkSteps()));
    }

    private float animatedProgress(final float target) {
        final long now = System.currentTimeMillis();
        if (Math.abs(target - this.progressTarget) > 0.001f) {
            this.progressFrom = this.displayedProgress;
            this.progressTarget = target;
            this.progressAnimStartMs = now;
        }

        final double t = clamp01((now - this.progressAnimStartMs) / (double) PROGRESS_ANIM_MS);
        this.displayedProgress = (float) MathUtil.easeInOutQuad(this.progressFrom, this.progressTarget, t);
        if (t >= 1.0) this.displayedProgress = this.progressTarget;
        return clamp01(this.displayedProgress);
    }

    private void renderProgressBar(final int x, final int y, final int w, final int h, final float progress) {
        final float fillW = Math.max(0f, (w - 2) * progress);
        RenderSystem.fill(x, y, w, h,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 0.92f);
        RenderSystem.rect(x, y, w, h, AppTheme.NEON_DARK, 1f);
        RenderSystem.glowRect(x, y, w, h, 0f, AppTheme.NEON, 0.20f);
        if (fillW > 0f) {
            RenderSystem.fillGradientH(x + 1, y + 1, fillW, Math.max(1, h - 2),
                    AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                    AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
            RenderSystem.glowRect(x + 1, y + 1, fillW, Math.max(1, h - 2), 0f, AppTheme.NEON, 0.36f);
        }
    }

    private ImageBox fitImage(final int sourceW, final int sourceH, final int maxW, final int maxH) {
        if (sourceW <= 0 || sourceH <= 0 || maxW <= 0 || maxH <= 0) return new ImageBox(0, 0);
        final float scale = Math.min((float) maxW / sourceW, (float) maxH / sourceH);
        return new ImageBox(Math.max(1, Math.round(sourceW * scale)), Math.max(1, Math.round(sourceH * scale)));
    }

    private void renderImageGlow(final int textureId, final int glowW, final int glowH,
                                 final int sourceW, final int sourceH,
                                 final int imageX, final int imageY, final int imageW, final int imageH) {
        if (textureId <= 0 || glowW <= 0 || glowH <= 0 || sourceW <= 0 || sourceH <= 0) return;
        final float scaleX = (float) imageW / sourceW;
        final float scaleY = (float) imageH / sourceH;
        final float padX = (glowW - sourceW) * 0.5f * scaleX;
        final float padY = (glowH - sourceH) * 0.5f * scaleY;
        this.renderTexture(textureId, imageX - padX, imageY - padY, glowW * scaleX, glowH * scaleY);
    }

    private void renderTexture(final int textureId, final float x, final float y, final float w, final float h) {
        if (textureId <= 0 || w <= 0 || h <= 0) return;
        RenderSystem.bindTexture(textureId);
        RenderSystem.color(1f, 1f, 1f, 1f);
        RenderSystem.blit(x, y, w, h);
    }

    private void updateApiHistory(final WaterMediaAPI api) {
        if (api == null) return;
        if (this.trackedApi != null && this.trackedApi != api) {
            this.completedApis.add(ok(this.apiMessage(this.trackedApiName, this.trackedStepName)));
        }

        this.trackedApi = api;
        this.trackedApiName = api.name();
        final String stepName = clean(api.stepName());
        if (!stepName.isEmpty()) this.trackedStepName = stepName;
    }

    private List<StatusLine> statusLines(final WaterMediaAPI api) {
        final List<StatusLine> lines = new ArrayList<>();
        lines.add(ok("init OpenGL context"));
        lines.add(ok("render engine: OpenGL"));

        if (this.ctx.audioError) {
            lines.add(fail("init audio output"));
        } else if (this.ctx.audioReady) {
            lines.add(ok("init audio output"));
        } else {
            lines.add(pending("init audio output"));
        }

        lines.addAll(this.completedApis);

        if (api != null) {
            final String stepName = clean(api.stepName());
            final String fallback = stepName.isEmpty() ? this.trackedStepName : stepName;
            final String message = this.apiMessage(api.name(), fallback);
            final boolean complete = api.steps() <= 0 || api.step() >= api.steps();
            lines.add(complete ? ok(message) : pending(message));
        } else {
            lines.add(pending("prepare API registry"));
        }
        return lines;
    }

    private String apiMessage(final String apiName, final String stepName) {
        final String name = clean(apiName).isEmpty() ? "WaterMedia API" : apiName;
        return clean(stepName).isEmpty() ? name : name + ": " + stepName;
    }

    private void renderStatusLine(final StatusLine line, final int x, final int y) {
        this.text.render(line.tag(), x, y, line.tagColor(), STATUS_SCALE);
        final float messageX = x + this.text.width(line.tag() + " ", STATUS_SCALE);
        this.text.render(line.message(), messageX, y, line.messageColor(), STATUS_SCALE);
    }

    private void renderSpaced(final String value, final float x, final float y,
                              final Color color, final float scale, final int spacing) {
        float cursor = x;
        for (int i = 0; i < value.length(); i++) {
            final String c = String.valueOf(value.charAt(i));
            this.text.render(c, cursor, y, color, scale);
            cursor += this.text.width(c, scale) + spacing;
        }
    }

    private float spacedWidth(final String value, final float scale, final int spacing) {
        float width = 0f;
        for (int i = 0; i < value.length(); i++) {
            width += this.text.width(String.valueOf(value.charAt(i)), scale);
            if (i + 1 < value.length()) width += spacing;
        }
        return width;
    }

    private static StatusLine ok(final String message) {
        return new StatusLine("[OK]", message, AppTheme.GREEN, AppTheme.TEXT);
    }

    private static StatusLine pending(final String message) {
        return new StatusLine("[..]", message, AppTheme.TEXT_FAINT, AppTheme.TEXT_SOFT);
    }

    private static StatusLine fail(final String message) {
        return new StatusLine("[NO]", message, AppTheme.RED, AppTheme.TEXT_SOFT);
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }

    private static float clamp01(final float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static double clamp01(final double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    @Override
    public String instructions() {
        return "Loading... please wait";
    }

    private record StatusLine(String tag, String message, Color tagColor, Color messageColor) {
    }

    private record ImageBox(int w, int h) {
        boolean valid() {
            return this.w > 0 && this.h > 0;
        }
    }
}
