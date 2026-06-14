package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.Metadata;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.ThreadTool;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.net.URI;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Screen with dialog for opening custom multimedia URLs.
 * Renders HomeScreen as background with dialog overlay.
 * Instructions are shown in the bottom bar, not in the dialog.
 */
public class OpenMultimediaScreen extends Screen {

    private static final long LOAD_TIMEOUT_MS = 30000L;

    private final Consumer<HomeScreen.Action> navigator;
    private final HomeScreen homeScreen;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private volatile int previewGeneration;
    private long loadStartTime;
    private Dimension pasteBounds = Dimension.ZERO;
    private Dimension reloadBounds = Dimension.ZERO;
    private Dimension cancelBounds = Dimension.ZERO;
    private Dimension playBounds = Dimension.ZERO;
    private Dimension saveBounds = Dimension.ZERO;
    private Dimension inputBounds = Dimension.ZERO;
    private Dimension closeBounds = Dimension.ZERO;
    private boolean inputFocused = true;
    private volatile MRL previewMRL;
    private String previewUrl = "";

    public OpenMultimediaScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator,
                                final HomeScreen homeScreen) {
        super(text, ctx);
        this.navigator = navigator;
        this.homeScreen = homeScreen;
    }

    @Override
    public void onEnter() {
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.inputFocused = true;
        this.previewMRL = null;
        this.previewUrl = "";
    }

    @Override
    public void onExit() {
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.previewMRL = null;
        this.previewUrl = "";
    }

    private void pasteFromClipboard() {
        try {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                this.ctx.customUrlText = ((String) clipboard.getData(DataFlavor.stringFlavor)).trim().replace("\"", "");
                this.ensurePreviewMRL();
            }
        } catch (final Exception e) {
            this.ctx.customUrlText = "";
            this.clearPreview();
        }
    }

    private void reloadPreviewMRL() {
        final String url = this.ctx.customUrlText == null ? "" : this.ctx.customUrlText.trim();
        if (url.isEmpty()) return;
        if (this.previewMRL == null || !url.equals(this.previewUrl)) {
            this.previewUrl = "";
            this.ensurePreviewMRL();
            return;
        }

        final int generation = ++this.previewGeneration;
        this.previewMRL.reload();
        this.loadStartTime = System.currentTimeMillis();
        this.subscribePreviewMRL(this.previewMRL, generation);
        this.ctx.requestRender();
    }

    private void saveCustomUrl() {
        if (this.ctx.customUrlText == null || this.ctx.customUrlText.isEmpty()) return;
        final String name = this.ctx.customUrlText.length() > 40
                ? this.ctx.customUrlText.substring(0, 37) + "..."
                : this.ctx.customUrlText;
        this.ctx.customTests.add(new AppContext.TestURI(name, this.ctx.customUrlText, false));
        this.ctx.customUrlText = "";
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    private void playCustomUrl() {
        if (this.ctx.customUrlText == null || this.ctx.customUrlText.isEmpty()) return;

        this.ensurePreviewMRL();
        if (this.previewMRL == null) return;
        switch (this.previewMRL.status()) {
            case LOADED -> { /* PROCEED BELOW */ }
            case FETCHING -> {
                this.beginLoading(this.previewMRL);
                return;
            }
            case ERROR -> {
                this.ctx.showError("Invalid URL", "Failed to load this URL.", null);
                return;
            }
            case BLOCKED -> {
                this.ctx.showError("Blocked", "This media was blocked by the platform.", null);
                return;
            }
            // EXPIRED/FORGOTTEN SOURCES ARE NO LONGER USABLE — DISPOSE THE PREVIEW AND
            // REGENERATE A FRESH MRL BEFORE PLAYING.
            case EXPIRED, FORGOTTEN -> {
                this.previewUrl = "";
                this.ensurePreviewMRL();
                if (this.previewMRL != null) this.beginLoading(this.previewMRL);
                return;
            }
        }

        this.ctx.selectedMRL = this.previewMRL;
        this.ctx.selectedMRLName = this.previewTitle();
        this.ctx.selectedGroup = null;
        final var sourcesList = this.ctx.selectedMRL.sources();
        this.ctx.availableSources = sourcesList.toArray(MRL.Source[]::new);
        if (this.ctx.availableSources.length == 0) {
            this.ctx.showError("No Sources", "No sources available for this URL", null);
            return;
        }

        this.ctx.sourceSelectorIndex = 0;
        this.ctx.selectedSource = this.ctx.availableSources[0];
        this.navigator.accept(HomeScreen.Action.PLAYER);
    }

    private void ensurePreviewMRL() {
        final String url = this.ctx.customUrlText == null ? "" : this.ctx.customUrlText.trim();
        if (url.isEmpty()) {
            this.clearPreview();
            return;
        }
        if (url.equals(this.previewUrl)) return;
        this.previewUrl = url;
        final int generation = ++this.previewGeneration;
        try {
            final File file = new File(url);
            if (!file.exists()) {
                final URI uri = URI.create(url);
                if (uri.getScheme() == null) {
                    this.previewMRL = null;
                    return;
                }
            }
            this.previewMRL = MediaAPI.getMRL(url);
            this.loadStartTime = System.currentTimeMillis();
            this.subscribePreviewMRL(this.previewMRL, generation);
        } catch (final Exception ignored) {
            this.previewMRL = null;
        }
    }

    private void clearPreview() {
        if (this.previewMRL != null || !this.previewUrl.isEmpty()) {
            this.previewGeneration++;
        }
        this.previewMRL = null;
        this.previewUrl = "";
    }

    private void subscribePreviewMRL(final MRL mrl, final int generation) {
        if (mrl == null || loaded(mrl)) return;
        mrl.subscribe(done -> {
            if (this.previewGeneration == generation && this.previewMRL == done) {
                this.ctx.requestRender();
            }
        });
    }

    private void beginLoading(final MRL mrl) {
        this.ctx.selectedMRL = mrl;
        this.ctx.selectedMRLName = this.previewTitle();
        this.loading = true;
        this.loadGeneration++;
        this.loadStartTime = System.currentTimeMillis();
        if (!loaded(mrl)) {
            final int generation = this.loadGeneration;
            mrl.subscribe(done -> {
                if (this.loading && this.loadGeneration == generation && this.ctx.selectedMRL == done) {
                    this.ctx.requestRender();
                }
            });
            this.scheduleLoadTimeout(generation);
        }
        this.ctx.requestRender();
    }

    private void scheduleLoadTimeout(final int generation) {
        final long deadline = this.loadStartTime + LOAD_TIMEOUT_MS;
        ThreadTool.createStarted("OpenMultimediaLoadTimeout", () -> {
            final long wait = Math.max(0L, deadline - System.currentTimeMillis());
            ThreadTool.sleep(wait);
            if (this.loading && this.loadGeneration == generation) {
                this.ctx.requestRender();
            }
        });
    }

    private void checkLoadingState() {
        if (this.ctx.selectedMRL == null) {
            this.loading = false;
            this.loadGeneration++;
            return;
        }

        final MRL.Status status = this.ctx.selectedMRL.status();
        // ANY TERMINAL NON-LOADED STATE (ERROR/BLOCKED/EXPIRED/FORGOTTEN) ENDS THE WAIT.
        if (status != MRL.Status.LOADED && status != MRL.Status.FETCHING) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: " + status.name(), null);
            this.ctx.selectedMRL = null;
            return;
        }

        if (status == MRL.Status.LOADED) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.selectedMRLName = this.previewTitle();
            final var sourcesList = this.ctx.selectedMRL.sources();
            this.ctx.availableSources = sourcesList.toArray(MRL.Source[]::new);
            if (this.ctx.availableSources.length == 0) {
                this.ctx.showError("No Sources", "No sources available for this URL", null);
                this.ctx.selectedMRL = null;
                return;
            }

            this.ctx.sourceSelectorIndex = 0;
            this.ctx.selectedSource = this.ctx.availableSources[0];
            this.navigator.accept(HomeScreen.Action.PLAYER);
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: Timeout", null);
            this.ctx.selectedMRL = null;
        }
    }

    private void renderLoadingDialog(final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);

        final int dots = (int) ((System.currentTimeMillis() / 500) % 4);
        final String loadingText = "Loading" + ".".repeat(dots);
        final String urlText = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";

        final int padding = 20;
        final int lineH = this.text.lineHeight(AppTheme.TEXT_BODY);

        final int contentW = Math.max(this.text.widthBold(loadingText, AppTheme.TEXT_BUTTON),
                Math.max(this.text.width(urlText, AppTheme.TEXT_BODY), this.text.width("ESC to cancel", AppTheme.TEXT_BODY)));
        final int dialogW = Math.min(Math.max(contentW + padding * 2 + 40, 400), windowW - 100);
        final int dialogH = padding + lineH + 15 + lineH + 10 + lineH + padding;

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = (windowH - dialogH) / 2;

        RenderSystem.dialogBox(dialogX, dialogY, dialogW, dialogH, Colors.BLUE, 3);

        int y = dialogY + padding;
        this.text.renderBold(loadingText, dialogX + padding, y, Colors.BLUE, AppTheme.TEXT_BUTTON);
        y += lineH + 15;

        final String truncatedUrl = this.text.truncateToWidth(urlText, dialogW - padding * 2, AppTheme.TEXT_BODY);
        this.text.render(truncatedUrl.isEmpty() ? "(empty)" : truncatedUrl,
                dialogX + padding, y, Colors.GRAY, AppTheme.TEXT_BODY);
        y += lineH + 10;

        this.text.render("ESC to cancel", dialogX + padding, y, Colors.GRAY, AppTheme.TEXT_BODY);

        RenderSystem.restoreProjection();
    }

    @Override
    public boolean wantsContinuousRender() {
        return AppChrome.crtEnabled() || this.loading || this.inputFocused;
    }

    @Override
    public void render(final int windowW, final int windowH) {
        // RENDER HOME SCREEN AS BACKGROUND
        this.homeScreen.render(windowW, windowH);
        this.ensurePreviewMRL();
        if (this.loading) {
            this.checkLoadingState();
        }

        RenderSystem.setupOrtho(windowW, windowH);

        final String displayUrl = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";

        final int padding = 20;
        final int titleH = 56;
        final int verticalMargin = 36;
        final int dialogH = Math.max(260, windowH - AppChrome.FOOTER_H - verticalMargin * 2);
        final int reservedH = titleH + 20 + 28 + 18 + 42 + 12 + 26 + 20 + 38 + 18;
        final int targetPreviewH = Math.max(80, dialogH - reservedH);
        final int maxDialogW = Math.max(360, windowW - 100);
        int dialogW = Math.min(Math.max(Math.round(targetPreviewH * 16f / 9f) + padding * 2, 660), maxDialogW);
        final int previewW = dialogW - padding * 2;
        final int previewH = Math.round(previewW * 9f / 16f);

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = verticalMargin;

        RenderSystem.fill(0, 0, windowW, windowH, 0.02f, 0.03f, 0.10f, 0.65f);
        RenderSystem.shadowRect(dialogX, dialogY, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(dialogX, dialogY, dialogW, dialogH, 0f, AppTheme.NEON, 0.35f);
        RenderSystem.fill(dialogX, dialogY, dialogW, dialogH, AppTheme.BG_1);
        RenderSystem.rect(dialogX, dialogY, dialogW, dialogH, AppTheme.NEON, 1f);
        AppChrome.amberCube(dialogX - 1, dialogY - 1, 10);
        AppChrome.amberCube(dialogX + dialogW - 9, dialogY - 1, 10);
        AppChrome.amberCube(dialogX - 1, dialogY + dialogH - 9, 10);
        AppChrome.amberCube(dialogX + dialogW - 9, dialogY + dialogH - 9, 10);
        RenderSystem.fill(dialogX, dialogY, dialogW, titleH, AppTheme.BG_2);
        RenderSystem.lineH(dialogX, dialogY + titleH, dialogW, AppTheme.STROKE_BRIGHT, 1f);
        this.text.renderBold("OPEN MULTIMEDIA", dialogX + 20,
                dialogY + Math.max(0, (titleH - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f),
                AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
        this.closeBounds = new Dimension(dialogX + dialogW - 44, dialogY + 14, 26, 26);
        final boolean closeHover = this.closeBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        AppChrome.dialogCloseButton(this.closeBounds, closeHover);

        final int previewX = dialogX + padding;
        final int previewY = dialogY + titleH + 20;
        AppChrome.tvFrame(previewX, previewY, previewW, previewH, true);
        this.renderPreview(previewX + 6, previewY + 6, previewW - 12, previewH - 12);
        final String chip = this.bestQuality();
        if (chip != null) {
            final int chipW = this.text.width(chip, AppTheme.TEXT_BODY) + 18;
            final int chipX = previewX + previewW - chipW - 8;
            final int chipY = previewY;
            RenderSystem.fill(chipX, chipY, chipW, 22, AppTheme.alpha(AppTheme.BG_1, 235));
            RenderSystem.rect(chipX, chipY, chipW, 22, AppTheme.NEON, 1f);
            RenderSystem.glowRect(chipX, chipY, chipW, 22, 0f, AppTheme.NEON, 0.34f);
            this.text.render(chip, chipX + 9, chipY + 5, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY);
        }

        final int y = previewY + previewH + 28;
        final int tbX = dialogX + padding;
        final int pasteW = 104;
        final int reloadW = 112;
        final int gap = 8;
        final int tbW = dialogW - padding * 2 - pasteW - reloadW - gap * 2;
        final int tbH = 40;
        this.pasteBounds = new Dimension(tbX + tbW + gap, y, pasteW, tbH);
        this.reloadBounds = new Dimension(this.pasteBounds.right() + gap, y, reloadW, tbH);
        this.inputBounds = new Dimension(tbX, y, tbW, tbH);

        this.text.render("URL OR PATH", tbX, y - 20, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        if (this.inputFocused) RenderSystem.glowRect(tbX, y, tbW, tbH, 0f, AppTheme.NEON, 0.28f);
        RenderSystem.fill(tbX, y, tbW, tbH, AppTheme.BG_2);
        RenderSystem.rect(tbX, y, tbW, tbH, this.inputFocused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1);
        PixelIcon.draw("copy", tbX + 10, y + (tbH - 14) / 2, 14, AppTheme.TEXT_FAINT);
        final boolean pasteHover = this.pasteBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(this.pasteBounds.x(), this.pasteBounds.y(), this.pasteBounds.width(), this.pasteBounds.height(),
                pasteHover ? AppTheme.alpha(AppTheme.NEON_DARK, 82) : AppTheme.BG_2);
        RenderSystem.rect(this.pasteBounds.x(), this.pasteBounds.y(), this.pasteBounds.width(), this.pasteBounds.height(), AppTheme.NEON, 1f);
        PixelIcon.draw("copy", this.pasteBounds.x() + 12, this.pasteBounds.y() + (this.pasteBounds.height() - 12) / 2, 12, AppTheme.NEON_LIGHT);
        this.text.renderBold("PASTE", this.pasteBounds.x() + 32,
                this.pasteBounds.y() + Math.max(0, (this.pasteBounds.height() - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f),
                AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
        this.renderInlineButton(this.reloadBounds, "RELOAD", "reload", AppTheme.NEON_LIGHT, !displayUrl.isEmpty());

        final float inputScale = AppTheme.TEXT_BUTTON;
        final int inputTextX = tbX + 32;
        final int inputTextY = y + Math.max(0, (tbH - this.text.glyphHeight(inputScale)) / 2);
        final String truncatedUrl = this.text.truncateToWidth(displayUrl, tbW - 46, inputScale);
        final boolean emptyInput = truncatedUrl.isEmpty();
        this.text.render(emptyInput ? "https://... or C:\\path\\file.mp4" : truncatedUrl,
                inputTextX, inputTextY,
                emptyInput ? AppTheme.TEXT_FAINT : AppTheme.TEXT, inputScale);
        if (this.inputFocused && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
            final int caretTextW = emptyInput ? 0 : this.text.width(truncatedUrl, inputScale);
            final int caretX = Math.min(tbX + tbW - 10, inputTextX + caretTextW + (emptyInput ? -5 : 1));
            RenderSystem.fill(caretX, inputTextY, 2, this.text.glyphHeight(inputScale), AppTheme.NEON_LIGHT);
        }

        final int detectedY = y + tbH + 12;
        final String detected = this.statusLabel();
        final java.awt.Color detectedColor = this.statusColor();
        final int detectedW = this.text.width(detected, AppTheme.TEXT_BODY) + 34;
        RenderSystem.fill(tbX, detectedY, detectedW, 24, AppTheme.alpha(detectedColor, detectedColor == AppTheme.TEXT_FAINT ? 24 : 36));
        RenderSystem.rect(tbX, detectedY, detectedW, 24, detectedColor, 1f);
        AppChrome.statusPip(tbX + 8, detectedY + 8, 8, detectedColor, false);
        this.text.render(detected, tbX + 24, detectedY + 5, detectedColor, AppTheme.TEXT_BODY);

        final int footY = dialogY + dialogH - 58;
        this.cancelBounds = new Dimension(dialogX + padding, footY, 128, 36);
        this.playBounds = new Dimension(dialogX + dialogW - padding - 128, footY, 128, 36);
        this.saveBounds = new Dimension(this.playBounds.x() - 140, footY, 128, 36);
        renderDialogButton("CANCEL", "ESC", "x", this.cancelBounds, AppTheme.TEXT_SOFT);
        renderDialogButton("SAVE", "SPACE", "save", this.saveBounds, displayUrl.isEmpty() ? AppTheme.TEXT_FAINT : AppTheme.NEON_LIGHT);
        renderDialogButton("PLAY", "ENTER", "play", this.playBounds, displayUrl.isEmpty() ? AppTheme.TEXT_FAINT : AppTheme.GREEN);

        RenderSystem.restoreProjection();

        if (this.loading) {
            this.renderLoadingDialog(windowW, windowH);
        }
    }

    private void renderInlineButton(final Dimension bounds, final String label, final String icon,
                                    final java.awt.Color color, final boolean enabled) {
        final java.awt.Color actual = enabled ? color : AppTheme.TEXT_FAINT;
        final boolean hover = enabled && bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover ? AppTheme.alpha(AppTheme.NEON_DARK, 82) : AppTheme.BG_2);
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), actual, 1f);
        if (enabled) RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, actual, hover ? 0.24f : 0.12f);
        PixelIcon.draw(icon, bounds.x() + 12, bounds.y() + (bounds.height() - 13) / 2, 13, actual);
        this.text.renderBold(label, bounds.x() + 32,
                bounds.y() + Math.max(0, (bounds.height() - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f),
                actual, AppTheme.TEXT_BUTTON);
    }

    private void renderPreview(final int x, final int y, final int w, final int h) {
        RenderSystem.fill(x, y, w, h, AppTheme.BG_0);
        AppChrome.crtOverlay(x, y, w, h, this.ctx.windowHeight);
        if (this.previewMRL == null) {
            PixelIcon.draw("copy", x + w / 2 - 16, y + h / 2 - 32, 32, AppTheme.TEXT_FAINT);
            this.text.render("NO MEDIA", x + w / 2 - this.text.width("NO MEDIA", AppTheme.TEXT_BODY) / 2, y + h / 2 + 12, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
            return;
        }
        final MRL.Status status = this.previewMRL.status();
        if (status == MRL.Status.FETCHING) {
            this.text.renderBold("LOADING", x + 22, y + h - 48, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
            this.text.render(this.text.truncateToWidth(this.previewUrl, w - 44, AppTheme.TEXT_SUBTITLE), x + 22, y + h - 24, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
            return;
        }
        if (status != MRL.Status.LOADED) {
            // ERROR/BLOCKED/EXPIRED/FORGOTTEN — SHOW THE EXACT STATE.
            final java.awt.Color color = this.statusColor();
            final String label = this.statusLabel();
            PixelIcon.draw("warn", x + w / 2 - 16, y + h / 2 - 36, 32, color);
            this.text.renderBold(label, x + w / 2 - this.text.widthBold(label, AppTheme.TEXT_BUTTON) / 2, y + h / 2 + 8, color, AppTheme.TEXT_BUTTON);
            return;
        }

        final MRL.Source src = this.firstSource();
        final Metadata meta = src != null ? src.metadata() : null;
        final String title = meta != null && meta.title() != null ? meta.title() : this.previewTitle();
        final String desc = meta != null && meta.desc() != null ? meta.desc() : this.previewUrl;
        final String duration = meta != null && meta.duration() > 0 ? this.ctx.formatTime(meta.duration()) : "--:--";
        RenderSystem.fillGradientV(x, y + h / 2f, w, h / 2f,
                0f, 0f, 0f, 0f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.88f);
        this.text.renderBold(this.text.truncateToWidth(title.toUpperCase(), w - 44, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD), x + 22, y + h - 76, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
        this.text.render(this.text.truncateToWidth(desc, w - 44, AppTheme.TEXT_SUBTITLE), x + 22, y + h - 48, AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE);
        this.text.render(duration, x + 22, y + h - 24, AppTheme.CYAN, AppTheme.TEXT_BODY);
    }

    private static boolean loaded(final MRL mrl) {
        return mrl != null && mrl.status().loaded();
    }

    private String statusLabel() {
        if (this.previewMRL == null) return "AWAITING URL";
        return switch (this.previewMRL.status()) {
            case LOADED -> "READY";
            case FETCHING -> "LOADING";
            case ERROR -> "ERROR";
            case BLOCKED -> "BLOCKED";
            case EXPIRED -> "EXPIRED";
            case FORGOTTEN -> "FORGOTTEN";
        };
    }

    private java.awt.Color statusColor() {
        if (this.previewMRL == null) return AppTheme.TEXT_FAINT;
        return switch (this.previewMRL.status()) {
            case LOADED -> AppTheme.GREEN;
            case FETCHING -> AppTheme.NEON;
            case ERROR, BLOCKED -> AppTheme.RED;
            case EXPIRED, FORGOTTEN -> AppTheme.AMBER;
        };
    }

    private MRL.Source firstSource() {
        if (!loaded(this.previewMRL) || this.previewMRL.sources().isEmpty()) return null;
        return this.previewMRL.sources().get(0);
    }

    private String previewTitle() {
        final MRL.Source src = this.firstSource();
        final Metadata meta = src != null ? src.metadata() : null;
        if (meta != null && meta.title() != null && !meta.title().isEmpty()) return meta.title();
        return "Custom URL";
    }

    private String bestQuality() {
        final MRL.Source src = this.firstSource();
        if (src == null || src.availableQualities().isEmpty()) return null;
        final MediaQuality q = src.availableQualities().stream()
                .max(java.util.Comparator.comparingInt(value -> value.threshold))
                .orElse(MediaQuality.UNKNOWN);
        return q == MediaQuality.UNKNOWN ? null : q.name();
    }

    private void renderDialogButton(final String label, final String hotkey, final String icon,
                                    final Dimension bounds, final java.awt.Color color) {
        final boolean hover = bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        final boolean enabled = color != AppTheme.TEXT_FAINT;
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover && enabled ? AppTheme.alpha(color, 54) : AppTheme.BG_2);
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color, 1.6f);
        if (enabled) RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, color, hover ? 0.28f : 0.16f);
        final float labelScale = AppTheme.TEXT_BUTTON;
        PixelIcon.draw(icon, bounds.x() + 12, bounds.y() + (bounds.height() - 13) / 2, 13, color);
        this.text.renderBold(label, bounds.x() + 32,
                bounds.y() + Math.max(0, (bounds.height() - this.text.glyphHeightBold(labelScale)) / 2f),
                color, labelScale);
        final float hotkeyScale = AppTheme.TEXT_SUBTITLE;
        final int hkW = this.text.width(hotkey, hotkeyScale) + 12;
        final int hkH = 20;
        final int hkX = bounds.right() - hkW - 8;
        final int hkY = bounds.y() + (bounds.height() - hkH) / 2;
        RenderSystem.rect(hkX, hkY, hkW, hkH, AppTheme.STROKE, 1f);
        this.text.render(hotkey, hkX + 6,
                hkY + Math.max(0, (hkH - this.text.glyphHeight(hotkeyScale)) / 2f),
                AppTheme.TEXT_FAINT, hotkeyScale);
    }

    @Override
    public void handleKey(final int key, final int action) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
        if (this.loading) {
            if (key == GLFW_KEY_ESCAPE) {
                this.loading = false;
                this.loadGeneration++;
                this.ctx.selectedMRL = null;
            }
            return;
        }

        switch (key) {
            case GLFW_KEY_BACKSPACE, GLFW_KEY_DELETE -> {
                if (this.inputFocused && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
                    this.ctx.customUrlText = this.ctx.ctrlDown ? this.deleteLastWord(this.ctx.customUrlText) : this.ctx.customUrlText.substring(0, this.ctx.customUrlText.length() - 1);
                    this.ensurePreviewMRL();
                }
            }
            case GLFW_KEY_SPACE -> {
                if (action == GLFW_PRESS && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) this.saveCustomUrl();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (action == GLFW_PRESS && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) this.playCustomUrl();
            }
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
            case GLFW_KEY_V -> {
                if (action == GLFW_PRESS && this.ctx.ctrlDown) this.pasteFromClipboard();
            }
            case GLFW_KEY_R -> {
                if (action == GLFW_PRESS) this.reloadPreviewMRL();
            }
        }
    }

    private String deleteLastWord(final String value) {
        int i = value.length();
        while (i > 0 && Character.isWhitespace(value.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(value.charAt(i - 1)) && "/\\?&=#.:_-".indexOf(value.charAt(i - 1)) < 0) i--;
        if (i > 0 && "/\\?&=#.:_-".indexOf(value.charAt(i - 1)) >= 0) i--;
        return value.substring(0, Math.max(0, i));
    }

    @Override
    public void handleChar(final int codepoint) {
        if (!this.inputFocused || this.loading) return;
        if (this.ctx.ctrlDown) return;
        if (codepoint < 32 || codepoint == 127) return;
        this.ctx.customUrlText = (this.ctx.customUrlText == null ? "" : this.ctx.customUrlText) + new String(Character.toChars(codepoint));
        this.ensurePreviewMRL();
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (this.loading) return;
        this.inputFocused = this.inputBounds.contains(mx, my);
        if (this.closeBounds.contains(mx, my)) {
            this.navigator.accept(HomeScreen.Action.BACK);
        } else if (this.pasteBounds.contains(mx, my)) {
            this.pasteFromClipboard();
        } else if (this.reloadBounds.contains(mx, my)) {
            this.reloadPreviewMRL();
        } else if (this.cancelBounds.contains(mx, my)) {
            this.navigator.accept(HomeScreen.Action.BACK);
        } else if (this.saveBounds.contains(mx, my) && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
            this.saveCustomUrl();
        } else if (this.playBounds.contains(mx, my) && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
            this.playCustomUrl();
        }
    }

    @Override
    public String instructions() {
        if (this.loading) return "ESC: Cancel";
        return "ENTER: Play | SPACE: Save | V: Paste | R: Reload | ESC: Cancel";
    }
}
