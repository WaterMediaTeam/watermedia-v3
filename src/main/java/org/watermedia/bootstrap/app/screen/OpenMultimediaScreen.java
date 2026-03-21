package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MediaAPI;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Screen with dialog for opening custom multimedia URLs.
 * Renders HomeScreen as background with dialog overlay.
 * Instructions are shown in the bottom bar, not in the dialog.
 */
public class OpenMultimediaScreen extends Screen {

    private final Consumer<HomeScreen.Action> navigator;
    private final HomeScreen homeScreen;
    private boolean loading;
    private long loadStartTime;

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
        this.pasteFromClipboard();
    }

    private void pasteFromClipboard() {
        try {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                this.ctx.customUrlText = ((String) clipboard.getData(DataFlavor.stringFlavor)).trim().replace("\"", "");
            }
        } catch (final Exception e) {
            this.ctx.customUrlText = "";
        }
    }

    private void addToCustomTests() {
        if (this.ctx.customUrlText == null || this.ctx.customUrlText.isEmpty()) return;
        final String name = this.ctx.customUrlText.length() > 40
                ? this.ctx.customUrlText.substring(0, 37) + "..."
                : this.ctx.customUrlText;
        this.ctx.customTests.add(new AppContext.TestURI(name, this.ctx.customUrlText));
        this.ctx.customUrlText = "";
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    private void playCustomUrl() {
        if (this.ctx.customUrlText == null || this.ctx.customUrlText.isEmpty()) return;

        try {
            this.ctx.selectedMRL = MediaAPI.getMRL(this.ctx.customUrlText);
            this.ctx.selectedMRLName = "Custom URL";
            this.ctx.selectedGroup = null;
            this.loading = true;
            this.loadStartTime = System.currentTimeMillis();
        } catch (final Exception e) {
            this.ctx.showError("Invalid URL", "Error: " + e.getMessage(), null);
            this.ctx.selectedMRL = null;
        }
    }

    private void checkLoadingState() {
        if (this.ctx.selectedMRL == null) {
            this.loading = false;
            return;
        }

        if (this.ctx.selectedMRL.ready()) {
            this.loading = false;
            this.ctx.availableSources = this.ctx.selectedMRL.sources();
            if (this.ctx.availableSources == null || this.ctx.availableSources.length == 0) {
                this.ctx.showError("No Sources", "No sources available for this URL", null);
                this.ctx.selectedMRL = null;
                return;
            }

            this.ctx.sourceSelectorIndex = 0;
            this.ctx.selectedSource = this.ctx.availableSources[0];
            this.ctx.playerEscPressed = false;
            this.navigator.accept(HomeScreen.Action.PLAYER);
            return;
        }

        if (this.ctx.selectedMRL.error()) {
            this.loading = false;
            this.ctx.showError("Load Error", "Failed to load URL: Error", null);
            this.ctx.selectedMRL = null;
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime > 30000) {
            this.loading = false;
            this.ctx.showError("Load Error", "Failed to load URL: Timeout", null);
            this.ctx.selectedMRL = null;
        }
    }

    private void renderLoadingDialog(final int windowW, final int windowH) {
        DrawTool.setupOrtho(windowW, windowH);

        final int dots = (int) ((System.currentTimeMillis() / 500) % 4);
        final String loadingText = "Loading" + ".".repeat(dots);
        final String urlText = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";

        final int padding = 20;
        final int lineH = this.text.lineHeight();

        final int contentW = Math.max(this.text.width(loadingText),
                Math.max(this.text.width(urlText), this.text.width("ESC to cancel")));
        final int dialogW = Math.min(Math.max(contentW + padding * 2 + 40, 400), windowW - 100);
        final int dialogH = padding + lineH + 15 + lineH + 10 + lineH + padding;

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = (windowH - dialogH) / 2;

        DrawTool.dialogBox(dialogX, dialogY, dialogW, dialogH, Colors.BLUE, 3);

        int y = dialogY + padding;
        this.text.render(loadingText, dialogX + padding, y, Colors.BLUE);
        y += lineH + 15;

        final String truncatedUrl = this.text.truncateToWidth(urlText, dialogW - padding * 2);
        this.text.render(truncatedUrl.isEmpty() ? "(empty)" : truncatedUrl,
                dialogX + padding, y, Colors.GRAY);
        y += lineH + 10;

        this.text.render("ESC to cancel", dialogX + padding, y, Colors.GRAY);

        DrawTool.restoreProjection();
    }

    @Override
    public void render(final int windowW, final int windowH) {
        // Render home screen as background
        this.homeScreen.render(windowW, windowH);

        // Check and render loading state
        if (this.loading) {
            this.checkLoadingState();
            if (this.loading) {
                this.renderLoadingDialog(windowW, windowH);
                return;
            }
        }

        // Render dialog overlay
        DrawTool.setupOrtho(windowW, windowH);

        final String displayUrl = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";

        // Calculate dialog size - only title + text box (no instructions inside)
        final int titleW = this.text.width("Open Multimedia");
        final int urlDisplayWidth = this.text.width(displayUrl.isEmpty() ? "(clipboard empty)" : displayUrl);
        final int contentW = Math.max(titleW, urlDisplayWidth);

        final int padding = 20;
        final int lineH = this.text.lineHeight();
        final int tbH = lineH + 10; // Text box height

        // Dialog dimensions: title + gap + textbox
        final int dialogW = Math.min(Math.max(contentW + padding * 2 + 40, 400), windowW - 100);
        final int dialogH = padding + lineH + 15 + tbH + padding; // title + gap + textbox + padding

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = (windowH - dialogH) / 2;

        DrawTool.dialogBox(dialogX, dialogY, dialogW, dialogH, Colors.BLUE, 3);

        int y = dialogY + padding;
        this.text.render("Open Multimedia", dialogX + padding, y, Colors.BLUE);
        y += lineH + 15;

        // Text box
        final int tbX = dialogX + padding;
        final int tbW = dialogW - padding * 2;

        DrawTool.disableTextures();
        DrawTool.fill(tbX, y, tbW, tbH, 0.1f, 0.1f, 0.1f, 1);
        DrawTool.rect(tbX, y, tbW, tbH, 0.31f, 0.71f, 1f, 0.5f, 1);
        DrawTool.enableTextures();

        final String truncatedUrl = this.text.truncateToWidth(displayUrl, tbW - 10);
        this.text.render(truncatedUrl.isEmpty() ? "(clipboard empty)" : truncatedUrl,
                tbX + 5, y + 5, truncatedUrl.isEmpty() ? Colors.GRAY : Colors.WHITE);

        DrawTool.restoreProjection();
    }

    @Override
    protected void onKeyRelease(final int key) {
        if (this.loading) {
            if (key == GLFW_KEY_ESCAPE) {
                this.loading = false;
                this.ctx.selectedMRL = null;
            }
            return;
        }

        switch (key) {
            case GLFW_KEY_SPACE -> {
                if (!this.ctx.customUrlText.isEmpty()) this.playCustomUrl();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (!this.ctx.customUrlText.isEmpty()) this.addToCustomTests();
            }
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
            case GLFW_KEY_V -> this.pasteFromClipboard();
        }
    }

    @Override
    public String instructions() {
        if (this.loading) return "ESC: Cancel";
        return "SPACE: Play | ENTER: Add to Tests | Ctrl+V: Refresh | ESC: Cancel";
    }
}
