package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Screen for media playback with overlay and dialogs.
 */
public class PlayerScreen extends Screen {

    private enum DialogState {NONE, QUALITY, VIDEO}

    private static final float META_SCALE = AppTheme.TEXT_SECTION;
    private static final float META_HEAD_SCALE = AppTheme.TEXT_DISPLAY;
    private static final float META_DESC_LABEL_SCALE = AppTheme.TEXT_BUTTON;
    private static final float META_DESC_SCALE = AppTheme.TEXT_SECTION;
    private static final float[] SPEED_VALUES = {0.25f, 0.50f, 0.75f, 1.0f, 1.25f, 1.50f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f};
    private static final String[] SPEED_LABELS = {"0.25x", "0.50x", "0.75x", "1.0x", "1.25x", "1.50x", "1.75x", "2.0x", "2.5x", "3.0x", "4.0x"};
    private static final int RES_FIELD_MAX_DIGITS = 5;

    private final Consumer<HomeScreen.Action> navigator;
    private final GLEngine.Builder glEngineBuilder;

    private DialogState dialogState = DialogState.NONE;
    private int qualitySelectedIndex = 0;
    private int sourceSelectedIndex = 0;
    private int sourceScrollOffset = 0;
    private int sourceVisibleRows = 0;
    private int debugPanelWidth = 250;
    private boolean debugOpen = true;
    private boolean endedSoundPlayed;
    private boolean loopEnabled = true;
    private boolean speedSpinnerOpen;
    private int speedSelectedIndex = 3;
    private String resWidthText = "";
    private String resHeightText = "";
    private int videoFocusField; // 0 NONE, 1 WIDTH, 2 HEIGHT
    private int lodSelectedIndex;

    // CACHED BOUNDS FOR QUALITY DIALOG ITEMS
    private Dimension qualityDialogBounds = Dimension.ZERO;
    private Dimension videoDialogBounds = Dimension.ZERO;
    private Dimension resWidthBounds = Dimension.ZERO;
    private Dimension resHeightBounds = Dimension.ZERO;
    private Dimension topBackBounds = Dimension.ZERO;
    private Dimension topSourcesBounds = Dimension.ZERO;
    private Dimension topDebugBounds = Dimension.ZERO;
    private Dimension seekBounds = Dimension.ZERO;
    private Dimension volumeBounds = Dimension.ZERO;
    private final Map<String, Dimension> transportButtons = new LinkedHashMap<>();
    private final List<Dimension> speedOptionBounds = new ArrayList<>();
    private final List<Dimension> sourceRowBounds = new ArrayList<>();
    private final List<Integer> sourceRowIndices = new ArrayList<>();

    public PlayerScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.glEngineBuilder = new GLEngine.Builder(Thread.currentThread(), this.ctx);
    }

    @Override
    public void onEnter() {
        this.dialogState = DialogState.NONE;
        this.debugOpen = true;
        this.endedSoundPlayed = false;
        this.loopEnabled = true;
        this.speedSpinnerOpen = false;
        this.speedSelectedIndex = 3;
        this.resWidthText = "";
        this.resHeightText = "";
        this.videoFocusField = 0;
        this.lodSelectedIndex = 0;
        this.startPlayer();
    }

    @Override
    public void onExit() {
        this.dialogState = DialogState.NONE;
        this.speedSpinnerOpen = false;
        this.ctx.releasePlayer();
    }

    private void startPlayer() {
        if (this.ctx.selectedMRL == null || this.ctx.selectedSource == null) return;

        this.ctx.releasePlayer();

        this.ctx.player = MediaAPI.createPlayer(
                this.ctx.selectedMRL,
                this.ctx.sourceSelectorIndex,
                this.glEngineBuilder::build, ALEngine::buildDefault
        );

        if (this.ctx.player == null) {
            this.ctx.showError("Player Error",
                    "WaterMedia failed to create media player.\nNo compatible player engine available.",
                    this::returnToMenu);
            return;
        }

        this.ctx.player.quality(this.ctx.selectedQuality);
        this.ctx.player.repeat(this.loopEnabled);
        this.ctx.player.speed(SPEED_VALUES[this.speedSelectedIndex]);
        this.applyVideoSettings();
        this.ctx.player.start();
        this.endedSoundPlayed = false;
    }

    @Override
    public boolean wantsContinuousRender() {
        final MediaPlayer player = this.ctx.player;
        return player != null && !player.error() && !player.stopped()
                && (!player.ended() || !this.endedSoundPlayed);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        final MediaPlayer player = this.ctx.player;

        // CHECK PLAYER STATE (ONLY WHEN NO DIALOG IS ACTIVE)
        if (player != null && this.dialogState == DialogState.NONE) {
            if (player.ended()) {
                if (!this.endedSoundPlayed) {
                    this.ctx.playSelectionSound();
                    this.endedSoundPlayed = true;
                }
            } else if (!player.stopped() && !player.error()) {
                this.endedSoundPlayed = false;
            }
        }

        // RENDER VIDEO
        this.renderVideo(windowW, windowH);
        this.renderOverlay(windowW, windowH);

        // RENDER ACTIVE DIALOG ON TOP
        switch (this.dialogState) {
            case QUALITY -> this.renderQualityDialog(windowW, windowH);
            case VIDEO -> this.renderVideoDialog(windowW, windowH);
        }
    }

    private void renderVideo(final int windowW, final int windowH) {
        final MediaPlayer player = this.ctx.player;

        // BLACK BACKGROUND
        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(0, 0, windowW, windowH, 0, 0, 0, 1);
        RenderSystem.restoreProjection();

        if (player == null || player.ended() || player.stopped() || player.error() || player.texture() <= 0) return;

        final int videoW = player.width();
        final int videoH = player.height();

        float renderW = windowW, renderH = windowH, offsetX = 0, offsetY = 0;
        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;

        if (videoW > 0 && videoH > 0) {
            final float srcAspect = (float) videoW / videoH;

            // CENTER-CROP THE UPLOADED FRAME TO THE CUSTOM RESOLUTION ASPECT WHEN BOTH CAPS ARE
            // SET. maxSize KEEPS THE TEXTURE AT THE SOURCE ASPECT, SO WE ONLY NARROW THE SAMPLED
            // UV REGION — NO DISTORTION, JUST TRIMMED EDGES — AND DISPLAY IT AT THE CAP ASPECT.
            float dispAspect = srcAspect;
            final int capW = player.maxWidth();
            final int capH = player.maxHeight();
            if (capW > 0 && capH > 0) {
                dispAspect = (float) capW / capH;
                if (dispAspect > srcAspect) {
                    final float frac = srcAspect / dispAspect;
                    v0 = (1f - frac) / 2f;
                    v1 = 1f - v0;
                } else if (dispAspect < srcAspect) {
                    final float frac = dispAspect / srcAspect;
                    u0 = (1f - frac) / 2f;
                    u1 = 1f - u0;
                }
            }

            final float windowAspect = (float) windowW / windowH;
            if (dispAspect > windowAspect) {
                renderW = windowW;
                renderH = windowW / dispAspect;
                offsetY = (windowH - renderH) / 2;
            } else {
                renderH = windowH;
                renderW = windowH * dispAspect;
                offsetX = (windowW - renderW) / 2;
            }
        }

        RenderSystem.bindTexture((int) player.texture());
        RenderSystem.color(1, 1, 1, 1);
        RenderSystem.blitNDC(
                (offsetX / windowW) * 2 - 1,
                (offsetY / windowH) * 2 - 1,
                ((offsetX + renderW) / windowW) * 2 - 1,
                ((offsetY + renderH) / windowH) * 2 - 1,
                u0, v0, u1, v1
        );
    }

    private void renderOverlay(final int windowW, final int windowH) {
        final MediaPlayer player = this.ctx.player;
        if (player == null || player.error()) return;

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.disableDepthTest();

        // FADES
        AppChrome.crtOverlay(0, 0, windowW, windowH);
        RenderSystem.fadeLeft(windowW, windowH, 380, 0.9f);
        RenderSystem.fadeBottom(windowW, windowH, 120, 0.9f);
        RenderSystem.fillGradientV(0, AppChrome.TITLEBAR_H, windowW, 96,
                6f / 255f, 9f / 255f, 26f / 255f, 0.92f,
                6f / 255f, 9f / 255f, 26f / 255f, 0f);
        final int topY = AppChrome.TITLEBAR_H + 24;
        final int transportY = Math.max(AppChrome.TITLEBAR_H + 120, windowH - AppChrome.FOOTER_H - 92);
        final int debugY = topY + 56;
        this.debugPanelWidth = Math.min(405, Math.max(240, windowW / 4));
        int debugH = 0;
        if (this.debugOpen) {
            debugH = Math.max(220, transportY - debugY - 14);
            RenderSystem.rect(14, debugY, this.debugPanelWidth, debugH, AppTheme.NEON, 2f);
            RenderSystem.glowRect(14, debugY, this.debugPanelWidth, debugH, 0f, AppTheme.NEON, 0.20f);
            RenderSystem.fill(14, debugY, this.debugPanelWidth, debugH,
                    AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.82f);
        }

        RenderSystem.fill(14, transportY, windowW - 28, 76,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.88f);
        RenderSystem.rect(14, transportY, windowW - 28, 76, AppTheme.NEON, 2f);
        RenderSystem.glowRect(14, transportY, windowW - 28, 76, 0f, AppTheme.NEON, 0.16f);
        RenderSystem.fill(28, transportY + 74, 10, 3, AppTheme.AMBER);
        RenderSystem.fill(windowW - 38, transportY + 74, 10, 3, AppTheme.AMBER);
        RenderSystem.rect(0, 0, windowW, windowH, AppTheme.STROKE_BRIGHT, 1f);

        final Metadata meta = this.ctx.selectedSource != null ? this.ctx.selectedSource.metadata() : null;
        final String title = meta != null && meta.title() != null ? meta.title() : this.ctx.selectedMRLName;
        final String author = meta != null && meta.author() != null ? meta.author() : player.getClass().getSimpleName();

        final String host = this.ctx.selectedMRL != null && this.ctx.selectedMRL.uri.getHost() != null ? this.ctx.selectedMRL.uri.getHost() : "local";
        final String posted = meta != null && meta.postedAt() != null ? this.formatDate(meta) : "unknown date";
        final int sources = this.ctx.availableSources != null ? this.ctx.availableSources.length : 1;
        final String resolution = this.playerResolution(player);
        this.topBackBounds = new Dimension(16, topY, 76, 34);
        this.topDebugBounds = Dimension.ZERO;
        this.renderHudButton("BACK", this.topBackBounds, AppTheme.TEXT_SOFT, "arrow-left");
        final String titleText = this.text.truncateToWidth(title, Math.max(180, windowW - 520), AppTheme.TEXT_SECTION, java.awt.Font.BOLD);
        this.text.renderBold(titleText, 108, topY - 3, AppTheme.TEXT, AppTheme.TEXT_SECTION);
        int tagX = 108 + this.text.widthBold(titleText, AppTheme.TEXT_SECTION) + 12;
        final MediaType mediaType = this.currentMediaType();
        if (mediaType != null && tagX < windowW - 180) {
            tagX += AppChrome.mediaTypeTag(this.text, tagX, topY - 5, mediaType) + 8;
        }
        final String sourceLabel = (this.ctx.sourceSelectorIndex + 1) + "/" + sources;
        final int sourceTagW = this.text.width(sourceLabel, AppTheme.TEXT_BODY) + 46;
        if (tagX + sourceTagW < windowW - 20) {
            this.topSourcesBounds = new Dimension(tagX, topY - 6, sourceTagW, 24);
            this.renderHudLabel(sourceLabel, this.topSourcesBounds, AppTheme.NEON_LIGHT, "folder");
        } else {
            this.topSourcesBounds = Dimension.ZERO;
        }
        this.text.render(this.text.truncateToWidth(author + " - " + host + " - " + posted, Math.max(180, windowW - 520), AppTheme.TEXT_BODY),
                108, topY + 24, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);

        if (this.debugOpen) {
            int y = debugY + 14;

            y = this.renderPanelHead("ENGINE", 28, y);
            y = this.renderMetric("Engine", player.getClass().getSimpleName(), 28, y, AppTheme.NEON_LIGHT);
            if (this.ctx.selectedMRL != null) {
                y = this.renderWrappedMetric("MRL", this.ctx.selectedMRL.uri.toString(), 28, y, AppTheme.TEXT_SOFT, 2);
            }
            y = this.renderMetric("Source", (this.ctx.sourceSelectorIndex + 1) + "/" +
                    (this.ctx.availableSources != null ? this.ctx.availableSources.length : 1), 28, y, AppTheme.TEXT_SOFT);
            y = this.renderMetric("FPS", String.format("%.2f", player.fps()), 28, y, AppTheme.GREEN);
            y = this.renderMetric("Status", player.status().name(), 28, y, AppTheme.TEXT_SOFT);

            final long duration = player.duration();
            final String timeValue = duration <= 0
                    ? this.ctx.formatTime(player.time())
                    : this.ctx.formatTime(player.time()) + " / " + this.ctx.formatTime(duration);
            y = this.renderMetric("Time", timeValue, 28, y, AppTheme.TEXT_SOFT);
            y = this.renderMetric("Volume", player.volume() + "%", 28, y, AppTheme.TEXT_SOFT);
            y = this.renderMetric("Quality", player.quality().name() + " - " + this.maxSizeLabel(), 28, y, AppTheme.CYAN);
            y = this.renderMetric("Dimensions", resolution, 28, y, AppTheme.CYAN);
            y = this.renderMetric("Speed", String.format("%.2f", player.speed()) + "x", 28, y, AppTheme.TEXT_SOFT);
            y = this.renderMetric("Live", player.liveSource() ? "Yes" : "No", 28, y, AppTheme.TEXT_SOFT);

            y += 12;
            y = this.renderPanelHead("METADATA", 28, y);

            if (meta != null) {
                y = this.renderWrappedMetric("Title", meta.title(), 28, y, AppTheme.TEXT_SOFT, 2);
                y = this.renderMetric("Author", meta.author(), 28, y, AppTheme.TEXT_SOFT);
                if (meta.postedAt() != null) {
                    y = this.renderMetric("Published", this.formatDate(meta), 28, y, AppTheme.TEXT_SOFT);
                }
                y = this.renderDescriptionBox(meta, 28, y + 8, this.debugPanelWidth - 28);
            } else {
                this.text.render("Unavailable", 28, y, AppTheme.TEXT_FAINT, META_SCALE);
                y += this.text.lineHeight(META_SCALE);
            }
            if (y > debugY + debugH - 8) {
                final int trackH = Math.max(40, debugH - 32);
                final int thumbH = Math.max(26, (int) (trackH * ((debugH - 24) / (float) Math.max(debugH - 24, y - debugY))));
                RenderSystem.fill(14 + this.debugPanelWidth - 18, debugY + 16, 3, trackH, AppTheme.alpha(AppTheme.BG_3, 140));
                RenderSystem.fill(14 + this.debugPanelWidth - 18, debugY + 16, 3, thumbH, AppTheme.alpha(AppTheme.NEON, 160));
                RenderSystem.glowRect(14 + this.debugPanelWidth - 18, debugY + 16, 3, thumbH, 0f, AppTheme.NEON, 0.35f);
            }
        }

        this.renderTransport(player, windowW, transportY);

        RenderSystem.restoreProjection();
        AppChrome.titlebar(this.text, this.ctx, windowW, "WATERMeDIA - Now Playing");
    }

    private int renderMetric(final String label, final String value, final int x, final int y, final java.awt.Color valueColor) {
        final int valueX = this.metricValueX(x);
        final int maxValueW = Math.max(80, 14 + this.debugPanelWidth - valueX - 14);
        this.text.render(label + ":", x, y, AppTheme.TEXT_FAINT, META_SCALE);
        if (value != null) {
            this.text.render(this.text.truncateToWidth(value, maxValueW, META_SCALE), valueX, y, valueColor, META_SCALE);
        }
        return y + this.text.lineHeight(META_SCALE) + 4;
    }

    private int renderWrappedMetric(final String label, final String value, final int x, final int y,
                                   final java.awt.Color valueColor, final int maxLines) {
        final float scale = META_SCALE;
        final int valueX = this.metricValueX(x);
        final int maxPixelW = Math.max(80, 14 + this.debugPanelWidth - valueX - 14);
        final List<String> lines = this.wrap(value, maxPixelW, scale, maxLines);
        this.text.render(label + ":", x, y, AppTheme.TEXT_FAINT, scale);
        final int lineH = this.text.lineHeight(scale) + 2;
        for (int i = 0; i < lines.size(); i++) {
            this.text.render(lines.get(i), valueX, y + i * lineH, valueColor, scale);
        }
        return y + Math.max(this.text.lineHeight(scale), lines.size() * lineH) + 4;
    }

    private int renderPanelHead(final String label, final int x, final int y) {
        this.text.renderBold("| " + label, x, y, AppTheme.NEON_LIGHT, META_HEAD_SCALE);
        for (int dx = x + 118; dx < x + this.debugPanelWidth - 28; dx += 8) {
            RenderSystem.fill(dx, y + this.text.glyphHeightBold(META_HEAD_SCALE) / 2, 4, 1, AppTheme.STROKE_BRIGHT);
        }
        return y + this.text.lineHeight(META_HEAD_SCALE) + 6;
    }

    private int renderDescriptionBox(final Metadata meta, final int x, final int y, final int w) {
        final String desc = meta != null && meta.desc() != null && !meta.desc().isBlank()
                ? meta.desc()
                : "No description available.";
        final List<String> lines = this.wrap(desc, w - 20, META_DESC_SCALE, 3);
        final int labelH = this.text.lineHeight(META_DESC_LABEL_SCALE);
        final int lineH = this.text.lineHeight(META_DESC_SCALE) + 2;
        final int h = 22 + labelH + lines.size() * lineH + 14;
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 188));
        // DRAWS THE DOTTED BORDER WITHOUT EXTRA GEOMETRY STATE.
        for (int dx = x; dx < x + w; dx += 8) {
            RenderSystem.fill(dx, y, Math.min(4, x + w - dx), 1, AppTheme.STROKE_BRIGHT);
            RenderSystem.fill(dx, y + h - 1, Math.min(4, x + w - dx), 1, AppTheme.STROKE_BRIGHT);
        }
        for (int dy = y; dy < y + h; dy += 8) {
            RenderSystem.fill(x, dy, 1, Math.min(4, y + h - dy), AppTheme.STROKE_BRIGHT);
            RenderSystem.fill(x + w - 1, dy, 1, Math.min(4, y + h - dy), AppTheme.STROKE_BRIGHT);
        }
        this.text.renderBold("DESCRIPTION", x + 10, y + 10, AppTheme.TEXT_FAINT, META_DESC_LABEL_SCALE);
        for (int i = 0; i < lines.size(); i++) {
            this.text.render(lines.get(i), x + 10, y + 20 + labelH + i * lineH, AppTheme.TEXT_SOFT, META_DESC_SCALE);
        }
        return y + h + 10;
    }

    private int metricValueX(final int x) {
        return x + Math.min(118, Math.max(92, this.debugPanelWidth / 4));
    }

    private List<String> wrap(final String value, final int maxPixelWidth, final float scale, final int maxLines) {
        final List<String> lines = new ArrayList<>();
        String remaining = value == null ? "" : value.trim();
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            final int fit = this.fitPrefix(remaining, maxPixelWidth, scale);
            if (fit >= remaining.length()) {
                lines.add(remaining);
                break;
            }
            final int preferred = this.wrapBreak(remaining, fit);
            final String line = remaining.substring(0, preferred).trim();
            lines.add(line);
            remaining = remaining.substring(Math.min(preferred, remaining.length())).trim();
        }
        if (lines.isEmpty()) lines.add("Unavailable.");
        return lines;
    }

    private int fitPrefix(final String value, final int maxPixelWidth, final float scale) {
        int lastFit = 1;
        for (int i = 1; i <= value.length(); i++) {
            if (this.text.width(value.substring(0, i), scale) > maxPixelWidth) break;
            lastFit = i;
        }
        return lastFit;
    }

    private int wrapBreak(final String value, final int fit) {
        final int limit = Math.max(1, Math.min(fit, value.length()));
        int best = -1;
        // PREFER VISUALLY STABLE BREAKS FOR LONG URLS AND TITLES.
        for (int i = limit - 1; i > Math.max(0, limit - 18); i--) {
            final char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '?' || c == '&' || c == '-' || c == '_') {
                best = i + 1;
                break;
            }
        }
        return best > 4 ? best : limit;
    }

    private String formatDate(final Metadata meta) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy")
                .withZone(ZoneId.systemDefault())
                .format(meta.postedAt());
    }

    private MediaType currentMediaType() {
        return this.ctx.selectedSource == null ? null : this.ctx.selectedSource.type();
    }

    private String playerResolution(final MediaPlayer player) {
        if (player == null || player.width() <= 0 || player.height() <= 0) return "--";
        return player.width() + "x" + player.height();
    }

    private void renderTransport(final MediaPlayer player, final int windowW, final int y) {
        this.transportButtons.clear();
        this.speedOptionBounds.clear();
        final long duration = Math.max(0, player.duration());
        final float progress = duration > 0 ? Math.min(1f, Math.max(0f, (float) player.time() / duration)) : 0f;
        final String leftTime = this.ctx.formatTime(player.time());
        final String rightTime = duration > 0 ? this.ctx.formatTime(duration) : "--:--";
        final float timeScale = AppTheme.TEXT_BODY;
        final int timeTextY = y + Math.max(0, (28 - this.text.glyphHeight(timeScale)) / 2);
        final int barX = 112;
        final int barY = y + 11;
        final int rightTimeW = this.text.width(rightTime, timeScale);
        final int barW = Math.max(120, windowW - barX - rightTimeW - 44);
        this.seekBounds = new Dimension(barX, barY - 7, barW, 20);
        RenderSystem.fill(barX, barY, barW, 6, AppTheme.BG_3);
        RenderSystem.fillGradientH(barX, barY, barW * progress, 6,
                AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
        RenderSystem.fill(barX + barW * progress - 2, barY - 3, 4, 12, AppTheme.AMBER);
        this.text.render(leftTime, 26, timeTextY, AppTheme.NEON_LIGHT, timeScale);
        this.text.render(rightTime, barX + barW + 18, timeTextY, AppTheme.TEXT_SOFT, timeScale);

        final int controlsY = y + 42;
        int leftX = 28;
        leftX = this.renderTransportButton("debug", "DEBUG", leftX, controlsY - 5, 112, 30, AppTheme.NEON_LIGHT) + 8;
        leftX = this.renderTransportButton("quality", player.quality().name(), leftX, controlsY - 5, 210, 30, AppTheme.CYAN) + 8;
        final int lodRight = this.renderTransportButton("lod", this.lodLabel(), leftX, controlsY - 5, 230, 30, AppTheme.AMBER);
        final int qualityDividerX = lodRight + 14;
        RenderSystem.lineV(qualityDividerX, controlsY - 6, 32, AppTheme.STROKE_BRIGHT, 1f);

        final int sourceCount = this.ctx.availableSources != null ? this.ctx.availableSources.length : 1;
        final boolean multiSource = sourceCount > 1;
        int controlsW = 0;
        if (multiSource) controlsW += 42 + 8;
        controlsW += 46 + 8 + 42 + 8 + 78 + 8 + 46 + 8;
        if (multiSource) controlsW += 42 + 8;
        int x = Math.max(qualityDividerX + 20, (windowW - controlsW) / 2);
        if (multiSource) x = this.renderTransportButton("prev", "", x, controlsY - 5, 42, 30, AppTheme.TEXT_SOFT) + 8;
        x = this.renderTransportButton("rewind", "", x, controlsY - 5, 46, 30, AppTheme.TEXT_SOFT) + 8;
        x = this.renderTransportButton("stop", "", x, controlsY - 5, 42, 30, AppTheme.RED) + 8;
        x = this.renderTransportButton(player.playing() ? "pause" : "play", "", x, controlsY - 5, 78, 30,
                player.playing() ? AppTheme.AMBER : AppTheme.GREEN) + 8;
        x = this.renderTransportButton("forward", "", x, controlsY - 5, 46, 30, AppTheme.TEXT_SOFT) + 8;
        if (multiSource) this.renderTransportButton("next", "", x, controlsY - 5, 42, 30, AppTheme.TEXT_SOFT);

        final int volumeW = 208;
        final int rightGroupW = volumeW + 14 + 1 + 14 + 78 + 8 + 90;
        int rightX = Math.max(x + 78, windowW - 28 - rightGroupW);
        rightX = this.renderVolumeControl(player, rightX, controlsY - 5, volumeW, 30) + 14;
        RenderSystem.lineV(rightX, controlsY - 6, 32, AppTheme.STROKE_BRIGHT, 1f);
        rightX += 14;
        rightX = this.renderTransportButton("loop", this.loopEnabled ? "ON" : "OFF", rightX, controlsY - 5, 78, 30,
                this.loopEnabled ? AppTheme.GREEN : AppTheme.TEXT_FAINT) + 8;
        this.renderTransportButton("speed", this.speedLabel(), rightX, controlsY - 5, 90, 30, AppTheme.TEXT_SOFT);
        if (this.speedSpinnerOpen) {
            this.renderSpeedSpinner();
        }
    }

    private void renderSpeedSpinner() {
        final Dimension button = this.transportButtons.get("speed");
        if (button == null) return;

        final int rowH = 26;
        final int padding = 4;
        final int panelW = Math.max(button.width(), 96);
        final int panelH = SPEED_VALUES.length * rowH + padding * 2;
        final int panelX = button.x();
        final int panelY = button.y() - panelH - 4;

        RenderSystem.shadowRect(panelX, panelY, panelW, panelH, 0f, 0.42f);
        RenderSystem.fill(panelX, panelY, panelW, panelH, AppTheme.alpha(AppTheme.BG_1, 238));
        RenderSystem.rect(panelX, panelY, panelW, panelH, AppTheme.STROKE_BRIGHT, 1.5f);
        RenderSystem.glowRect(panelX, panelY, panelW, panelH, 0f, AppTheme.NEON, 0.18f);

        for (int i = 0; i < SPEED_VALUES.length; i++) {
            final Dimension row = new Dimension(panelX + padding, panelY + padding + i * rowH,
                    panelW - padding * 2, rowH - 2);
            this.speedOptionBounds.add(row);
            final boolean selected = i == this.speedSelectedIndex;
            final boolean hover = row.contains(this.ctx.mouseX, this.ctx.mouseY);
            final java.awt.Color color = selected ? AppTheme.GREEN : hover ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT;
            RenderSystem.fill(row.x(), row.y(), row.width(), row.height(),
                    selected ? AppTheme.alpha(AppTheme.GREEN, 45) : hover ? AppTheme.alpha(AppTheme.NEON_DARK, 88) : AppTheme.alpha(AppTheme.BG_2, 110));
            if (selected || hover) {
                RenderSystem.rect(row.x(), row.y(), row.width(), row.height(), color, selected ? 1.5f : 1f);
            }
            if (selected) {
                RenderSystem.fill(row.x() + 7, row.y() + 9, 6, 6, AppTheme.AMBER);
            }
            this.text.render(SPEED_LABELS[i], row.x() + 18,
                    row.y() + Math.max(0, (row.height() - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f),
                    color, AppTheme.TEXT_BODY);
        }
    }

    private int renderVolumeControl(final MediaPlayer player, final int x, final int y, final int w, final int h) {
        final int volume = Math.max(0, Math.min(100, player.volume()));
        final int trackX = x + 30;
        final int trackY = y + h / 2 - 3;
        final int trackW = w - 78;
        this.volumeBounds = new Dimension(trackX, trackY - 7, trackW, 20);
        final boolean hover = this.volumeBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        PixelIcon.draw("speaker", x + 8, y + 8, 14, hover ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT);
        RenderSystem.fill(trackX, trackY, trackW, 5, AppTheme.BG_3);
        RenderSystem.fillGradientH(trackX, trackY, trackW * (volume / 100f), 5,
                AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
        RenderSystem.fill(trackX + trackW * (volume / 100f) - 2, trackY - 3, 4, 11, AppTheme.AMBER);
        this.text.render(volume + "%", trackX + trackW + 10,
                y + Math.max(0, (h - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f), AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        return x + w;
    }

    private void renderHudButton(final String label, final Dimension bounds, final java.awt.Color color, final String icon) {
        final boolean hover = bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover ? AppTheme.alpha(AppTheme.NEON_DARK, 84) : AppTheme.alpha(AppTheme.BG_1, 192));
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color, 2f);
        RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, color, 0.18f);
        PixelIcon.draw(icon, bounds.x() + 10, bounds.y() + 10, 13, color);
        this.text.renderBold(label, bounds.x() + 30,
                bounds.y() + Math.max(0, (bounds.height() - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f),
                color, AppTheme.TEXT_BUTTON);
    }

    private void renderHudLabel(final String label, final Dimension bounds, final java.awt.Color color, final String icon) {
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), AppTheme.alpha(AppTheme.BG_1, 188));
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color, 1f);
        final int iconSize = 12;
        final float scale = AppTheme.TEXT_BODY;
        PixelIcon.draw(icon, bounds.x() + 9, bounds.y() + (bounds.height() - iconSize) / 2, iconSize, color);
        this.text.render(label, bounds.x() + 28,
                bounds.y() + Math.max(0, (bounds.height() - this.text.glyphHeight(scale)) / 2) + 1,
                color, scale);
    }

    private int renderTransportButton(final String id, final String label, final int x, final int y,
                                      final int w, final int h, final java.awt.Color color) {
        final Dimension bounds = new Dimension(x, y, w, h);
        this.transportButtons.put(id, bounds);
        final boolean hover = bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(x, y, w, h, hover ? AppTheme.alpha(AppTheme.NEON_DARK, 82) : AppTheme.alpha(AppTheme.BG_2, 212));
        RenderSystem.rect(x, y, w, h, color, 2f);
        RenderSystem.glowRect(x, y, w, h, 0f, color, 0.14f);
        final String icon = this.transportIcon(id, label);
        int textX = x + 8;
        if (icon != null) {
            PixelIcon.draw(icon, label.isEmpty() ? x + (w - 14) / 2 : x + 8, y + 8, 14, color);
            textX = x + 28;
        }
        if (!label.isEmpty()) {
            final String badge = "quality".equals(id) || "lod".equals(id) ? this.maxSizeLabel() : null;
            final int badgeW = badge == null ? 0 : this.text.width(badge, AppTheme.TEXT_SUBTITLE) + 14;
            final int maxLabelW = w - (textX - x) - 8 - (badgeW == 0 ? 0 : badgeW + 8);
            this.text.renderBold(this.text.truncateToWidth(label, maxLabelW, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD),
                    textX, y + Math.max(0, (h - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f), color, AppTheme.TEXT_BUTTON);
            if (badge != null) {
                final int badgeH = 20;
                final int badgeX = x + w - badgeW - 7;
                final int badgeY = y + (h - badgeH) / 2;
                RenderSystem.fill(badgeX, badgeY, badgeW, badgeH, AppTheme.alpha(AppTheme.BG_1, 190));
                RenderSystem.rect(badgeX, badgeY, badgeW, badgeH, AppTheme.STROKE_BRIGHT, 1f);
                this.text.render(badge, badgeX + 7,
                        badgeY + Math.max(0, (badgeH - this.text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2f),
                        AppTheme.CYAN, AppTheme.TEXT_SUBTITLE);
            }
        }
        return bounds.right();
    }

    private String transportIcon(final String id, final String label) {
        return switch (id) {
            case "prev" -> "prev";
            case "next" -> "next";
            case "rewind" -> "rewind";
            case "forward" -> "forward";
            case "pause" -> "pause";
            case "play" -> "play";
            case "stop" -> "stop";
            case "volumeDown", "volumeUp" -> "speaker";
            case "quality" -> "folder";
            case "speed" -> "info";
            case "debug" -> "debug";
            case "loop" -> "repeat";
            case "lod" -> "tv";
            default -> null;
        };
    }

    // QUALITY DIALOG
    private void openQualityDialog() {
        if (this.ctx.availableSources == null || this.ctx.availableSources.length == 0) return;
        this.speedSpinnerOpen = false;
        this.sourceSelectedIndex = Math.max(0, Math.min(this.ctx.sourceSelectorIndex, this.ctx.availableSources.length - 1));
        this.loadQualitiesForSource(this.sourceSelectedIndex);
        if (this.ctx.availableQualities == null || this.ctx.availableQualities.length == 0) return;
        this.dialogState = DialogState.QUALITY;
    }

    private void loadQualitiesForSource(final int sourceIndex) {
        if (this.ctx.availableSources == null || sourceIndex < 0 || sourceIndex >= this.ctx.availableSources.length) return;
        final var qualities = this.ctx.availableSources[sourceIndex].availableQualities();
        if (qualities == null || qualities.isEmpty()) return;

        this.ctx.availableQualities = qualities.toArray(new MediaQuality[0]);
        Arrays.sort(this.ctx.availableQualities, Comparator.comparingInt(q -> q.threshold));

        // FIND CURRENT QUALITY INDEX
        this.qualitySelectedIndex = 0;
        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            if (this.ctx.availableQualities[i] == this.ctx.selectedQuality) {
                this.qualitySelectedIndex = i;
                break;
            }
        }
    }

    private void selectSource(final int sourceIndex, final boolean apply) {
        if (this.ctx.availableSources == null || sourceIndex < 0 || sourceIndex >= this.ctx.availableSources.length) return;
        this.sourceSelectedIndex = sourceIndex;
        this.loadQualitiesForSource(sourceIndex);
        if (!apply || sourceIndex == this.ctx.sourceSelectorIndex) return;
        if (this.ctx.availableQualities != null && this.ctx.availableQualities.length > 0) {
            this.ctx.selectedQuality = this.ctx.availableQualities[this.qualitySelectedIndex];
        }
        this.ctx.sourceSelectorIndex = sourceIndex;
        this.ctx.selectedSource = this.ctx.availableSources[sourceIndex];
        this.startPlayer();
    }

    private void renderQualityDialog(final int windowW, final int windowH) {
        if (this.ctx.availableSources == null || this.ctx.availableSources.length == 0) return;
        if (this.ctx.availableQualities == null) this.loadQualitiesForSource(this.sourceSelectedIndex);
        if (this.ctx.availableQualities == null) return;

        final int sourceCount = this.ctx.availableSources.length;
        final int qualityCount = this.ctx.availableQualities.length;
        final int dialogW = Math.min(Math.max(620, (int) (windowW * 0.60f)), windowW - 72);
        final int dialogH = Math.min(Math.max(320, 98 + Math.max(Math.min(sourceCount, 5) * 58, Math.max(1, qualityCount) * 40)), windowH - 96);

        this.qualityDialogBounds = Dimension.centered(windowW, windowH, dialogW, dialogH);

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(0, 0, windowW, windowH, 0f, 0f, 0f, 0.62f);
        RenderSystem.shadowRect(this.qualityDialogBounds.x(), this.qualityDialogBounds.y(), dialogW, dialogH, 0f, 0.5f);
        RenderSystem.glowRect(this.qualityDialogBounds.x(), this.qualityDialogBounds.y(), dialogW, dialogH, 0f, AppTheme.NEON, 0.22f);
        RenderSystem.fillGradientV(this.qualityDialogBounds.x(), this.qualityDialogBounds.y(), dialogW, dialogH,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 0.96f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.96f);
        RenderSystem.rect(this.qualityDialogBounds.x(), this.qualityDialogBounds.y(), dialogW, dialogH, AppTheme.NEON, 1.4f);

        final int x = this.qualityDialogBounds.x();
        final int y = this.qualityDialogBounds.y();
        final int splitX = x + Math.min(dialogW - 250, Math.max(360, (int) (dialogW * 0.72f)));
        RenderSystem.lineV(splitX, y + 18, dialogH - 36, AppTheme.STROKE_BRIGHT, 1f);

        AppChrome.sectionHead(this.text, "Sources", sourceCount + " items", x + 22, y + 20);
        AppChrome.sectionHead(this.text, "Quality", qualityCount + " available", splitX + 22, y + 20);

        final int sourceX = x + 24;
        final int sourceY = y + 62;
        final int sourceW = splitX - x - 48;
        final int sourceH = dialogH - 90;
        final int sourceRowH = 58;
        this.sourceVisibleRows = Math.max(1, sourceH / sourceRowH);
        if (this.sourceSelectedIndex < this.sourceScrollOffset) this.sourceScrollOffset = this.sourceSelectedIndex;
        if (this.sourceSelectedIndex >= this.sourceScrollOffset + this.sourceVisibleRows) {
            this.sourceScrollOffset = this.sourceSelectedIndex - this.sourceVisibleRows + 1;
        }
        this.sourceScrollOffset = Math.max(0, Math.min(Math.max(0, sourceCount - this.sourceVisibleRows), this.sourceScrollOffset));
        this.sourceRowBounds.clear();
        this.sourceRowIndices.clear();

        for (int rowIndex = 0; rowIndex < this.sourceVisibleRows && rowIndex + this.sourceScrollOffset < sourceCount; rowIndex++) {
            final int sourceIndex = rowIndex + this.sourceScrollOffset;
            final MRL.Source source = this.ctx.availableSources[sourceIndex];
            final boolean selected = sourceIndex == this.sourceSelectedIndex;
            final boolean active = sourceIndex == this.ctx.sourceSelectorIndex;
            final Dimension row = new Dimension(sourceX, sourceY + rowIndex * sourceRowH, sourceW, sourceRowH - 8);
            this.sourceRowBounds.add(row);
            this.sourceRowIndices.add(sourceIndex);
            RenderSystem.fill(row.x(), row.y(), row.width(), row.height(),
                    selected ? AppTheme.alpha(AppTheme.NEON_DARK, 90) : AppTheme.alpha(AppTheme.BG_1, 150));
            RenderSystem.rect(row.x(), row.y(), row.width(), row.height(),
                    selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, selected ? 2f : 1f);
            if (selected) RenderSystem.glowRect(row.x(), row.y(), row.width(), row.height(), 0f, AppTheme.NEON, 0.20f);
            PixelIcon.draw(active ? "play" : "folder", row.x() + 12, row.y() + 15, 14, active ? AppTheme.GREEN : AppTheme.NEON_LIGHT);
            final int typeTagW = this.mediaTypeTagWidth(source.type());
            final String sourceTitle = this.text.truncateToWidth(this.sourceTitle(source, sourceIndex).toUpperCase(), row.width() - typeTagW - 68, AppTheme.TEXT_BODY, java.awt.Font.BOLD);
            this.text.renderBold(sourceTitle, row.x() + 34, row.y() + 10, selected ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
            AppChrome.mediaTypeTag(this.text, row.right() - typeTagW - 12, row.y() + Math.max(0, (row.height() - 22) / 2), source.type());
            this.text.render("SOURCE " + (sourceIndex + 1) + "/" + sourceCount + " - " + source.availableQualities().size() + " QUALITIES",
                    row.x() + 34, row.y() + 32, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        }
        if (sourceCount > this.sourceVisibleRows) {
            final int trackX = splitX - 16;
            final int trackH = sourceH - 2;
            final int thumbH = Math.max(24, (int) (trackH * (this.sourceVisibleRows / (float) sourceCount)));
            final int thumbY = sourceY + (int) ((trackH - thumbH) * (this.sourceScrollOffset / (float) Math.max(1, sourceCount - this.sourceVisibleRows)));
            RenderSystem.fill(trackX, sourceY, 3, trackH, AppTheme.alpha(AppTheme.BG_3, 180));
            RenderSystem.fill(trackX, thumbY, 3, thumbH, AppTheme.NEON_LIGHT);
            RenderSystem.glowRect(trackX, thumbY, 3, thumbH, 0f, AppTheme.NEON, 0.32f);
        }
        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            final MediaQuality q = this.ctx.availableQualities[i];
            final boolean selected = (i == this.qualitySelectedIndex);
            final Dimension row = this.qualityRowBounds(i);
            RenderSystem.fill(row.x(), row.y(), row.width(), row.height(),
                    selected ? AppTheme.alpha(AppTheme.NEON_DARK, 88) : AppTheme.alpha(AppTheme.BG_1, 148));
            RenderSystem.rect(row.x(), row.y(), row.width(), row.height(),
                    selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, 1f);
            final int qualityPipY = row.y() + Math.max(0, (row.height() - 8) / 2);
            final int qualityTextY = row.y() + Math.max(0, (row.height() - this.text.glyphHeightBold(AppTheme.TEXT_BODY)) / 2);
            final int thresholdY = row.y() + Math.max(0, (row.height() - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2);
            RenderSystem.fill(row.x() + 8, qualityPipY, 8, 8, selected ? AppTheme.AMBER : AppTheme.TEXT_FAINT);
            this.text.renderBold(q.name().toUpperCase(), row.x() + 28, qualityTextY,
                    selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
            this.text.render(q.threshold + "p", row.right() - 62, thresholdY,
                    selected ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        }

        RenderSystem.restoreProjection();
    }

    private Dimension qualityRowBounds(final int index) {
        final int splitX = this.qualityDialogBounds.x() + Math.min(this.qualityDialogBounds.width() - 250,
                Math.max(360, (int) (this.qualityDialogBounds.width() * 0.72f)));
        final int rowX = splitX + 22;
        final int rowY = this.qualityDialogBounds.y() + 60 + index * 40;
        return new Dimension(rowX, rowY, this.qualityDialogBounds.right() - rowX - 22, 34);
    }

    private int mediaTypeTagWidth(final MediaType type) {
        final String label = type == null ? "MEDIA" : type.name();
        return this.text.width(label, AppTheme.TEXT_BODY) + 22;
    }

    private String sourceTitle(final MRL.Source source, final int sourceIndex) {
        final Metadata meta = source != null ? source.metadata() : null;
        if (meta != null && meta.title() != null && !meta.title().isBlank()) return meta.title();
        if (this.ctx.selectedMRLName != null && !this.ctx.selectedMRLName.isBlank()) return this.ctx.selectedMRLName;
        return "SOURCE " + (sourceIndex + 1);
    }

    // VIDEO DIALOG (UPLOAD RESOLUTION CAP + LOD LEVEL)
    private void openVideoDialog() {
        if (this.ctx.player == null || this.ctx.player.error()) return;
        this.speedSpinnerOpen = false;
        this.dialogState = DialogState.VIDEO;
    }

    // PUSHES THE CUSTOM MAX RESOLUTION AND LOD TO THE LIVE PLAYER. BOTH APPLY ON THE FLY
    // TO THE NEXT UPLOADED FRAME; RE-APPLIED FROM startPlayer SO THEY SURVIVE PLAYER RESTARTS.
    private void applyVideoSettings() {
        final MediaPlayer player = this.ctx.player;
        if (player == null) return;
        player.maxSize(this.parseDim(this.resWidthText, player.sourceWidth()), this.parseDim(this.resHeightText, player.sourceHeight()));
        player.lod(MediaPlayer.LodLevel.values()[this.lodSelectedIndex]);
    }

    // PARSES A RESOLUTION FIELD: EMPTY OR NON-POSITIVE MEANS UNLIMITED (NO_SIZE); A POSITIVE
    // VALUE IS CLAMPED TO THE NATIVE SOURCE SIZE SO THE CAP NEVER EXCEEDS THE ACTIVE MAXIMUM.
    private int parseDim(final String text, final int nativeMax) {
        if (text.isEmpty()) return 0;
        final int value;
        try {
            value = Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            return 0;
        }
        if (value <= 0) return 0;
        return nativeMax > 0 ? Math.min(value, nativeMax) : value;
    }

    private String lodLabel() {
        return MediaPlayer.LodLevel.values()[this.lodSelectedIndex].name();
    }

    private String maxSizeLabel() {
        final MediaPlayer player = this.ctx.player;
        if (player == null) return "FULL";
        final int mw = player.maxWidth();
        final int mh = player.maxHeight();
        if (mw == 0 && mh == 0) return "FULL";
        return (mw == 0 ? "*" : mw) + "x" + (mh == 0 ? "*" : mh);
    }

    private void renderVideoDialog(final int windowW, final int windowH) {
        final MediaPlayer.LodLevel[] lods = MediaPlayer.LodLevel.values();
        final MediaPlayer player = this.ctx.player;
        final int dialogW = Math.min(Math.max(580, (int) (windowW * 0.54f)), windowW - 72);
        final int dialogH = Math.min(Math.max(320, 96 + Math.max(lods.length * 40, 200)), windowH - 96);

        this.videoDialogBounds = Dimension.centered(windowW, windowH, dialogW, dialogH);
        final int x = this.videoDialogBounds.x();
        final int y = this.videoDialogBounds.y();

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(0, 0, windowW, windowH, 0f, 0f, 0f, 0.62f);
        RenderSystem.shadowRect(x, y, dialogW, dialogH, 0f, 0.5f);
        RenderSystem.glowRect(x, y, dialogW, dialogH, 0f, AppTheme.NEON, 0.22f);
        RenderSystem.fillGradientV(x, y, dialogW, dialogH,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 0.96f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.96f);
        RenderSystem.rect(x, y, dialogW, dialogH, AppTheme.NEON, 1.4f);

        final int splitX = x + dialogW / 2;
        RenderSystem.lineV(splitX, y + 18, dialogH - 36, AppTheme.STROKE_BRIGHT, 1f);
        AppChrome.sectionHead(this.text, "Resolution", "custom px", x + 22, y + 20);
        AppChrome.sectionHead(this.text, "Level of Detail", lods.length + " levels", splitX + 22, y + 20);

        // LEFT COLUMN — CUSTOM RESOLUTION TEXT INPUTS CLAMPED TO THE ACTIVE SOURCE SIZE
        final int fieldX = x + 24;
        final int fieldW = splitX - x - 46;
        this.resWidthBounds = new Dimension(fieldX, y + 80, fieldW, 34);
        this.resHeightBounds = new Dimension(fieldX, y + 152, fieldW, 34);
        this.text.render("WIDTH", fieldX, y + 62, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        this.renderInputField(this.resWidthBounds, this.resWidthText, this.videoFocusField == 1);
        this.text.render("HEIGHT", fieldX, y + 134, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        this.renderInputField(this.resHeightBounds, this.resHeightText, this.videoFocusField == 2);

        final int srcW = player == null ? 0 : player.sourceWidth();
        final int srcH = player == null ? 0 : player.sourceHeight();
        final String activeMax = srcW > 0 && srcH > 0 ? srcW + " x " + srcH : "PENDING";
        this.text.render("ACTIVE MAX  " + activeMax, fieldX, y + 200, AppTheme.CYAN, AppTheme.TEXT_BODY);
        this.text.render("EMPTY FIELD = UNLIMITED", fieldX, y + 222, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);

        // RIGHT COLUMN — LOD LEVELS
        for (int i = 0; i < lods.length; i++) {
            this.renderVideoRow(this.lodRowBounds(i), i == this.lodSelectedIndex, lods[i].name(), lods[i].percent() + "%");
        }

        RenderSystem.restoreProjection();
    }

    private void renderInputField(final Dimension box, final String value, final boolean focused) {
        RenderSystem.fill(box.x(), box.y(), box.width(), box.height(), AppTheme.alpha(AppTheme.BG_0, 220));
        RenderSystem.rect(box.x(), box.y(), box.width(), box.height(), focused ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, focused ? 2f : 1f);
        if (focused) RenderSystem.glowRect(box.x(), box.y(), box.width(), box.height(), 0f, AppTheme.NEON, 0.18f);
        final int textX = box.x() + 12;
        final int textY = box.y() + Math.max(0, (box.height() - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2);
        final boolean empty = value.isEmpty();
        if (!empty) {
            this.text.render(value, textX, textY, AppTheme.TEXT, AppTheme.TEXT_BODY);
        } else if (!focused) {
            this.text.render("AUTO", textX, textY, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        }
        if (focused) {
            final int caretX = textX + (empty ? 0 : this.text.width(value, AppTheme.TEXT_BODY)) + 2;
            RenderSystem.fill(caretX, box.y() + 7, 2, box.height() - 14, AppTheme.NEON_LIGHT);
        }
    }

    private void renderVideoRow(final Dimension row, final boolean selected, final String label, final String detail) {
        final boolean hover = row.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(row.x(), row.y(), row.width(), row.height(),
                selected ? AppTheme.alpha(AppTheme.NEON_DARK, 88) : hover ? AppTheme.alpha(AppTheme.NEON_DARK, 44) : AppTheme.alpha(AppTheme.BG_1, 148));
        RenderSystem.rect(row.x(), row.y(), row.width(), row.height(),
                selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, selected ? 2f : 1f);
        RenderSystem.fill(row.x() + 8, row.y() + Math.max(0, (row.height() - 8) / 2), 8, 8, selected ? AppTheme.AMBER : AppTheme.TEXT_FAINT);
        this.text.renderBold(label, row.x() + 28, row.y() + Math.max(0, (row.height() - this.text.glyphHeightBold(AppTheme.TEXT_BODY)) / 2),
                selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        this.text.render(detail, row.right() - this.text.width(detail, AppTheme.TEXT_BODY) - 12,
                row.y() + Math.max(0, (row.height() - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2),
                selected ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
    }

    private Dimension lodRowBounds(final int index) {
        final int rowX = this.videoDialogBounds.x() + this.videoDialogBounds.width() / 2 + 24;
        final int rowW = this.videoDialogBounds.right() - rowX - 22;
        return new Dimension(rowX, this.videoDialogBounds.y() + 60 + index * 40, rowW, 34);
    }

    // EDITS A RESOLUTION FIELD, CLAMPS THE TYPED VALUE TO THE NATIVE SOURCE SIZE, AND
    // PUSHES THE NEW CAP TO THE LIVE PLAYER.
    private void setResField(final boolean width, final String raw) {
        String value = raw;
        final MediaPlayer player = this.ctx.player;
        final int nativeMax = player == null ? 0 : (width ? player.sourceWidth() : player.sourceHeight());
        if (!value.isEmpty() && nativeMax > 0) {
            try {
                if (Integer.parseInt(value) > nativeMax) value = String.valueOf(nativeMax);
            } catch (final NumberFormatException e) {
                value = "";
            }
        }
        if (width) this.resWidthText = value;
        else this.resHeightText = value;
        this.applyVideoSettings();
        this.ctx.requestRender();
    }

    private void selectVideoLod(final int index) {
        final int next = Math.max(0, Math.min(MediaPlayer.LodLevel.values().length - 1, index));
        if (next == this.lodSelectedIndex) return;
        this.lodSelectedIndex = next;
        this.applyVideoSettings();
        this.ctx.playSelectionSound();
        this.ctx.requestRender();
    }

    private void handleVideoKey(final int key) {
        switch (key) {
            case GLFW_KEY_BACKSPACE, GLFW_KEY_DELETE -> {
                if (this.videoFocusField != 0) {
                    final String cur = this.videoFocusField == 1 ? this.resWidthText : this.resHeightText;
                    if (!cur.isEmpty()) this.setResField(this.videoFocusField == 1, cur.substring(0, cur.length() - 1));
                }
            }
            case GLFW_KEY_TAB -> this.videoFocusField = this.videoFocusField == 1 ? 2 : 1;
            case GLFW_KEY_UP -> this.selectVideoLod(this.lodSelectedIndex - 1);
            case GLFW_KEY_DOWN -> this.selectVideoLod(this.lodSelectedIndex + 1);
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.dialogState = DialogState.NONE;
            case GLFW_KEY_ESCAPE -> {
                if (this.videoFocusField != 0) this.videoFocusField = 0;
                else this.dialogState = DialogState.NONE;
            }
        }
    }

    private void handleVideoClick(final double mx, final double my) {
        if (this.resWidthBounds.contains(mx, my)) {
            this.videoFocusField = 1;
            this.ctx.playSelectionSound();
            return;
        }
        if (this.resHeightBounds.contains(mx, my)) {
            this.videoFocusField = 2;
            this.ctx.playSelectionSound();
            return;
        }
        for (int i = 0; i < MediaPlayer.LodLevel.values().length; i++) {
            if (this.lodRowBounds(i).contains(mx, my)) {
                this.videoFocusField = 0;
                this.selectVideoLod(i);
                return;
            }
        }
        if (this.videoDialogBounds.contains(mx, my)) this.videoFocusField = 0;
        else this.dialogState = DialogState.NONE;
    }

    @Override
    public void handleChar(final int codepoint) {
        if (this.dialogState != DialogState.VIDEO || this.videoFocusField == 0 || this.ctx.ctrlDown) return;
        if (codepoint < '0' || codepoint > '9') return;
        final String cur = this.videoFocusField == 1 ? this.resWidthText : this.resHeightText;
        if (cur.length() >= RES_FIELD_MAX_DIGITS) return;
        this.setResField(this.videoFocusField == 1, cur + (char) codepoint);
    }

    // NAVIGATION
    private void returnToMenu() {
        this.dialogState = DialogState.NONE;
        this.ctx.releasePlayer();

        if (this.ctx.selectedMRL == null || this.ctx.selectedGroup == null) {
            this.navigator.accept(HomeScreen.Action.BACK);
        } else {
            this.navigator.accept(HomeScreen.Action.MRL_SELECTOR);
        }
    }

    private void navigateSource(final int delta) {
        if (this.ctx.availableSources == null || this.ctx.availableSources.length <= 1) return;
        final int newIndex = (this.ctx.sourceSelectorIndex + delta + this.ctx.availableSources.length) % this.ctx.availableSources.length;
        if (newIndex != this.ctx.sourceSelectorIndex) {
            this.ctx.sourceSelectorIndex = newIndex;
            this.ctx.selectedSource = this.ctx.availableSources[this.ctx.sourceSelectorIndex];
            this.startPlayer();
        }
    }

    private void seekToPercent(final int percent) {
        final MediaPlayer player = this.ctx.player;
        if (player == null || player.duration() <= 0) return;
        player.seek((player.duration() * percent) / 100);
    }

    private void togglePlayback(final MediaPlayer player) {
        if (player.ended() || player.stopped()) {
            player.repeat(this.loopEnabled);
            player.start();
            this.endedSoundPlayed = false;
            this.ctx.requestRender();
            return;
        }
        player.togglePlay();
    }

    private void toggleLoop(final MediaPlayer player) {
        this.loopEnabled = !this.loopEnabled;
        player.repeat(this.loopEnabled);
        this.ctx.requestRender();
    }

    private String speedLabel() {
        return SPEED_LABELS[Math.max(0, Math.min(SPEED_LABELS.length - 1, this.speedSelectedIndex))];
    }

    private int nearestSpeedIndex(final float speed) {
        int nearest = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_VALUES.length; i++) {
            final float delta = Math.abs(SPEED_VALUES[i] - speed);
            if (delta < best) {
                best = delta;
                nearest = i;
            }
        }
        return nearest;
    }

    private void applySpeed(final MediaPlayer player, final int index) {
        if (index < 0 || index >= SPEED_VALUES.length) return;
        if (player.speed(SPEED_VALUES[index])) {
            this.speedSelectedIndex = index;
            this.speedSpinnerOpen = false;
            this.ctx.requestRender();
        }
    }

    // INPUT HANDLING
    @Override
    protected void onKeyRelease(final int key) {
        switch (this.dialogState) {
            case QUALITY -> this.handleQualityKey(key);
            case VIDEO -> this.handleVideoKey(key);
            case NONE -> this.handlePlayerKey(key);
        }
    }

    private void handlePlayerKey(final int key) {
        final MediaPlayer player = this.ctx.player;
        if (player == null) return;

        if (this.speedSpinnerOpen) {
            switch (key) {
                case GLFW_KEY_ESCAPE -> this.speedSpinnerOpen = false;
                case GLFW_KEY_UP -> this.speedSelectedIndex = Math.max(0, this.speedSelectedIndex - 1);
                case GLFW_KEY_DOWN -> this.speedSelectedIndex = Math.min(SPEED_VALUES.length - 1, this.speedSelectedIndex + 1);
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.applySpeed(player, this.speedSelectedIndex);
            }
            this.ctx.requestRender();
            return;
        }

        switch (key) {
            case GLFW_KEY_ESCAPE -> {
                this.returnToMenu();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.openQualityDialog();
            case GLFW_KEY_V -> this.openVideoDialog();
            case GLFW_KEY_SPACE -> this.togglePlayback(player);
            case GLFW_KEY_LEFT -> player.rewind();
            case GLFW_KEY_RIGHT -> player.forward();
            case GLFW_KEY_U -> player.seekQuick(player.time() + 5_000);
            case GLFW_KEY_Y -> player.seekQuick(player.time() - 5_000);
            case GLFW_KEY_S -> player.stop();
            case GLFW_KEY_UP -> player.volume(player.volume() + 5);
            case GLFW_KEY_DOWN -> player.volume(player.volume() - 5);
            case GLFW_KEY_PERIOD -> player.nextFrame();
            case GLFW_KEY_COMMA -> player.previousFrame();
            case GLFW_KEY_N -> this.navigateSource(1);
            case GLFW_KEY_B -> this.navigateSource(-1);
            case GLFW_KEY_L -> this.toggleLoop(player);
            case GLFW_KEY_0, GLFW_KEY_KP_0 -> this.seekToPercent(0);
            case GLFW_KEY_1, GLFW_KEY_KP_1 -> this.seekToPercent(10);
            case GLFW_KEY_2, GLFW_KEY_KP_2 -> this.seekToPercent(20);
            case GLFW_KEY_3, GLFW_KEY_KP_3 -> this.seekToPercent(30);
            case GLFW_KEY_4, GLFW_KEY_KP_4 -> this.seekToPercent(40);
            case GLFW_KEY_5, GLFW_KEY_KP_5 -> this.seekToPercent(50);
            case GLFW_KEY_6, GLFW_KEY_KP_6 -> this.seekToPercent(60);
            case GLFW_KEY_7, GLFW_KEY_KP_7 -> this.seekToPercent(70);
            case GLFW_KEY_8, GLFW_KEY_KP_8 -> this.seekToPercent(80);
            case GLFW_KEY_9, GLFW_KEY_KP_9 -> this.seekToPercent(90);
        }
    }

    private void handleQualityKey(final int key) {
        switch (key) {
            case GLFW_KEY_LEFT -> {
                if (this.sourceSelectedIndex > 0) {
                    this.selectSource(this.sourceSelectedIndex - 1, true);
                    this.ctx.playSelectionSound();
                }
            }
            case GLFW_KEY_RIGHT -> {
                if (this.ctx.availableSources != null && this.sourceSelectedIndex < this.ctx.availableSources.length - 1) {
                    this.selectSource(this.sourceSelectedIndex + 1, true);
                    this.ctx.playSelectionSound();
                }
            }
            case GLFW_KEY_UP -> {
                if (this.qualitySelectedIndex > 0) {
                    this.qualitySelectedIndex--;
                    this.ctx.playSelectionSound();
                }
            }
            case GLFW_KEY_DOWN -> {
                if (this.qualitySelectedIndex < this.ctx.availableQualities.length - 1) {
                    this.qualitySelectedIndex++;
                    this.ctx.playSelectionSound();
                }
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                this.ctx.selectedQuality = this.ctx.availableQualities[this.qualitySelectedIndex];
                if (this.ctx.availableSources != null && this.sourceSelectedIndex != this.ctx.sourceSelectorIndex) {
                    this.ctx.sourceSelectorIndex = this.sourceSelectedIndex;
                    this.ctx.selectedSource = this.ctx.availableSources[this.sourceSelectedIndex];
                    this.startPlayer();
                } else if (this.ctx.player != null) {
                    this.ctx.player.quality(this.ctx.selectedQuality);
                }
                this.dialogState = DialogState.NONE;
            }
            case GLFW_KEY_ESCAPE -> this.dialogState = DialogState.NONE;
        }
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        switch (this.dialogState) {
            case QUALITY -> this.handleQualityHover(mx, my);
            // VIDEO ROWS COMPUTE HOVER DIRECTLY FROM ctx.mouse DURING RENDER
            case VIDEO, NONE -> {
            }
        }
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        switch (this.dialogState) {
            case QUALITY -> this.handleQualityClick(mx, my);
            case VIDEO -> this.handleVideoClick(mx, my);
            case NONE -> this.handlePlayerMouse(mx, my);
        }
    }

    private void handlePlayerMouse(final double mx, final double my) {
        final MediaPlayer player = this.ctx.player;
        if (player == null || player.error()) return;

        if (this.speedSpinnerOpen) {
            for (int i = 0; i < this.speedOptionBounds.size(); i++) {
                if (this.speedOptionBounds.get(i).contains(mx, my)) {
                    this.applySpeed(player, i);
                    this.ctx.playSelectionSound();
                    return;
                }
            }
            final Dimension speedButton = this.transportButtons.get("speed");
            if (speedButton == null || !speedButton.contains(mx, my)) {
                this.speedSpinnerOpen = false;
            }
        }

        if (this.topBackBounds.contains(mx, my)) {
            this.returnToMenu();
            return;
        }
        if (this.topDebugBounds.contains(mx, my)) {
            this.debugOpen = !this.debugOpen;
            this.ctx.playSelectionSound();
            return;
        }
        if (this.seekBounds.contains(mx, my) && player.duration() > 0) {
            final float ratio = (float) Math.max(0, Math.min(1, (mx - this.seekBounds.x()) / Math.max(1, this.seekBounds.width())));
            player.seek((long) (player.duration() * ratio));
            this.ctx.playSelectionSound();
            return;
        }
        if (this.volumeBounds.contains(mx, my)) {
            final float ratio = (float) Math.max(0, Math.min(1, (mx - this.volumeBounds.x()) / Math.max(1, this.volumeBounds.width())));
            player.volume(Math.round(ratio * 100f));
            this.ctx.playSelectionSound();
            return;
        }

        for (final Map.Entry<String, Dimension> entry : this.transportButtons.entrySet()) {
            if (!entry.getValue().contains(mx, my)) continue;
            switch (entry.getKey()) {
                case "prev" -> this.navigateSource(-1);
                case "rewind" -> player.rewind();
                case "play", "pause" -> this.togglePlayback(player);
                case "forward" -> player.forward();
                case "next" -> this.navigateSource(1);
                case "stop" -> player.stop();
                case "volumeDown" -> player.volume(player.volume() - 5);
                case "volumeUp" -> player.volume(player.volume() + 5);
                case "quality" -> this.openQualityDialog();
                case "lod" -> this.openVideoDialog();
                case "debug" -> this.debugOpen = !this.debugOpen;
                case "loop" -> this.toggleLoop(player);
                case "speed" -> {
                    this.speedSpinnerOpen = !this.speedSpinnerOpen;
                    this.speedSelectedIndex = this.nearestSpeedIndex(player.speed());
                    this.ctx.requestRender();
                }
            }
            this.ctx.playSelectionSound();
            return;
        }
    }

    private void handleQualityHover(final double mx, final double my) {
        for (int i = 0; i < this.sourceRowBounds.size(); i++) {
            if (this.sourceRowBounds.get(i).contains(mx, my)) {
                final int sourceIndex = this.sourceRowIndices.get(i);
                if (sourceIndex != this.sourceSelectedIndex) {
                    this.selectSource(sourceIndex, false);
                    this.ctx.playSelectionSound();
                }
                return;
            }
        }
        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            if (this.qualityRowBounds(i).contains(mx, my) && this.qualitySelectedIndex != i) {
                this.qualitySelectedIndex = i;
                this.ctx.playSelectionSound();
                break;
            }
        }
    }

    private void handleQualityClick(final double mx, final double my) {
        for (int i = 0; i < this.sourceRowBounds.size(); i++) {
            if (this.sourceRowBounds.get(i).contains(mx, my)) {
                this.selectSource(this.sourceRowIndices.get(i), true);
                this.ctx.playSelectionSound();
                return;
            }
        }
        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            if (this.qualityRowBounds(i).contains(mx, my)) {
                this.qualitySelectedIndex = i;
                this.ctx.selectedQuality = this.ctx.availableQualities[this.qualitySelectedIndex];
                if (this.ctx.availableSources != null && this.sourceSelectedIndex != this.ctx.sourceSelectorIndex) {
                    this.ctx.sourceSelectorIndex = this.sourceSelectedIndex;
                    this.ctx.selectedSource = this.ctx.availableSources[this.sourceSelectedIndex];
                    this.startPlayer();
                } else if (this.ctx.player != null) {
                    this.ctx.player.quality(this.ctx.selectedQuality);
                }
                this.dialogState = DialogState.NONE;
                break;
            }
        }
    }

    @Override
    public void handleScroll(final double yOffset) {
        if (this.dialogState != DialogState.QUALITY || this.ctx.availableSources == null) return;
        final int max = Math.max(0, this.ctx.availableSources.length - Math.max(1, this.sourceVisibleRows));
        this.sourceScrollOffset = Math.max(0, Math.min(max, this.sourceScrollOffset + (int) (-yOffset)));
    }

    @Override
    public String instructions() {
        return switch (this.dialogState) {
            case QUALITY -> "ARROWS: Navigate | ENTER: Select | ESC: Cancel";
            case VIDEO -> "TYPE: Resolution | TAB: Field | UP/DOWN: LOD | ENTER/ESC: Close";
            case NONE -> this.ctx.availableSources != null && this.ctx.availableSources.length > 1
                    ? "SPACE: Play/Pause | L: Loop | V: Video | ESC: Menu | ENTER: Sources"
                    : "SPACE: Play/Pause | L: Loop | V: Video | ESC: Menu | ENTER: Quality";
        };
    }
}
