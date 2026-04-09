package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Dialog;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glDisable;

/**
 * Screen for media playback with overlay and dialogs.
 */
public class PlayerScreen extends Screen {

    private enum DialogState {NONE, QUALITY, FINISHED}

    private final Consumer<HomeScreen.Action> navigator;
    private final GLEngine.Builder glEngineBuilder;
    private final Dialog finishedDialog;

    private DialogState dialogState = DialogState.NONE;
    private int qualitySelectedIndex = 0;

    // Cached bounds for quality dialog items
    private Dimension qualityDialogBounds = Dimension.ZERO;

    public PlayerScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.finishedDialog = new Dialog()
                .minWidth(380)
                .minHeight(170)
                .onSelectionChanged(ctx::playSelectionSound);

        this.glEngineBuilder = new GLEngine.Builder(Thread.currentThread(), this.ctx);
    }

    @Override
    public void onEnter() {
        this.dialogState = DialogState.NONE;
        this.ctx.playerEscPressed = false;
        this.startPlayer();
    }

    @Override
    public void onExit() {
        this.dialogState = DialogState.NONE;
        this.ctx.releasePlayer();
    }

    private void startPlayer() {
        if (this.ctx.selectedMRL == null || this.ctx.selectedSource == null) return;

        this.ctx.releasePlayer();

        this.ctx.player = this.ctx.selectedMRL.createPlayer(
                this.ctx.sourceSelectorIndex,
                this.glEngineBuilder.build(), ALEngine.buildDefault()
        );

        if (this.ctx.player == null) {
            this.ctx.showError("Player Error",
                    "WaterMedia failed to create media player.\nNo compatible player engine available.",
                    this::returnToMenu);
            return;
        }

        this.ctx.player.quality(this.ctx.selectedQuality);
        this.ctx.player.start();
    }

    @Override
    public void render(final int windowW, final int windowH) {
        final MediaPlayer player = this.ctx.player;

        // CHECK PLAYER STATE (ONLY WHEN NO DIALOG IS ACTIVE)
        if (player != null && this.dialogState == DialogState.NONE) {
            if (player.ended()) {
                this.showFinishedDialog("ENDED", false);
            } else if (player.error()) {
                this.showFinishedDialog("ERROR", true);
            } else if (player.stopped() && !this.ctx.playerEscPressed) {
                this.showFinishedDialog("STOPPED", false);
            }
        }

        // RENDER VIDEO
        this.renderVideo(windowW, windowH);
        this.renderOverlay(windowW, windowH);

        // RENDER ACTIVE DIALOG ON TOP
        switch (this.dialogState) {
            case QUALITY -> this.renderQualityDialog(windowW, windowH);
            case FINISHED -> this.renderFinishedDialog(windowW, windowH);
        }
    }

    private void renderVideo(final int windowW, final int windowH) {
        final MediaPlayer player = this.ctx.player;

        // BLACK BACKGROUND
        DrawTool.setupOrtho(windowW, windowH);
        DrawTool.disableTextures();
        DrawTool.fill(0, 0, windowW, windowH, 0, 0, 0, 1);
        DrawTool.enableTextures();
        DrawTool.restoreProjection();

        if (player == null) return;

        final int videoW = player.width();
        final int videoH = player.height();

        float renderW = windowW, renderH = windowH, offsetX = 0, offsetY = 0;

        if (videoW > 0 && videoH > 0) {
            final float videoAspect = (float) videoW / videoH;
            final float windowAspect = (float) windowW / windowH;

            if (videoAspect > windowAspect) {
                renderW = windowW;
                renderH = windowW / videoAspect;
                offsetY = (windowH - renderH) / 2;
            } else {
                renderH = windowH;
                renderW = windowH * videoAspect;
                offsetX = (windowW - renderW) / 2;
            }
        }

        DrawTool.bindTexture((int) player.texture());
        DrawTool.color(1, 1, 1, 1);
        DrawTool.blitNDC(
                (offsetX / windowW) * 2 - 1,
                (offsetY / windowH) * 2 - 1,
                ((offsetX + renderW) / windowW) * 2 - 1,
                ((offsetY + renderH) / windowH) * 2 - 1
        );
    }

    private void renderOverlay(final int windowW, final int windowH) {
        final MediaPlayer player = this.ctx.player;
        if (player == null) return;

        DrawTool.setupOrtho(windowW, windowH);
        glDisable(GL_DEPTH_TEST);

        // FADES
        DrawTool.disableTextures();
        DrawTool.fadeLeft(windowW, windowH, 380, 0.9f);
        DrawTool.fadeBottom(windowW, windowH, 120, 0.9f);
        DrawTool.enableTextures();

        int y = 20;

        // DEBUG INFO
        y = this.renderLabel("--- Debug Info ---", null, 15, y);
        if (this.ctx.selectedMRL != null) {
            y = this.renderLabel("MRL:", this.text.truncate(this.ctx.selectedMRL.uri.toString(), 35), 15, y);
        }
        y = this.renderLabel("Engine:", player.getClass().getSimpleName(), 15, y);
        y = this.renderLabel("Source:", (this.ctx.sourceSelectorIndex + 1) + "/" +
                (this.ctx.availableSources != null ? this.ctx.availableSources.length : 1), 15, y);
        y = this.renderLabel("Size:", player.width() + "x" + player.height(), 15, y);
        y = this.renderLabel("Status:", player.status().name(), 15, y);

        final long duration = player.duration();
        final String timeValue = duration <= 0
                ? this.ctx.formatTime(player.time())
                : this.ctx.formatTime(player.time()) + " / " + this.ctx.formatTime(duration);
        y = this.renderLabel("Time:", timeValue, 15, y);
        y = this.renderLabel("Volume:", player.volume() + "%", 15, y);
        y = this.renderLabel("Quality:", this.ctx.selectedQuality.name(), 15, y);
        y = this.renderLabel("Speed:", String.format("%.2f", player.speed()) + "x", 15, y);
        y = this.renderLabel("Live:", player.liveSource() ? "Yes" : "No", 15, y);

        // METADATA
        y += 15;
        y = this.renderLabel("--- Metadata ---", null, 15, y);

        if (this.ctx.selectedSource != null && this.ctx.selectedSource.metadata() != null) {
            final MRL.Metadata meta = this.ctx.selectedSource.metadata();
            y = this.renderLabel("Title:", this.text.truncate(meta.title(), 35), 15, y);
            y = this.renderLabel("Author:", this.text.truncate(meta.author(), 35), 15, y);
            y = this.renderLabel("Duration:", meta.duration() > 0 ? this.ctx.formatTime(meta.duration()) : "Unknown", 15, y);
            if (meta.publishedAt() != null) {
                this.renderLabel("Published:", this.text.truncate(meta.publishedAt().toString(), 30), 15, y);
            }
        } else {
            this.text.render("Unavailable", 15, y, Colors.GRAY);
        }

        DrawTool.restoreProjection();
    }

    private int renderLabel(final String label, final String value, final int x, final int y) {
        this.text.render(label, x, y, Colors.BLUE);
        if (value != null) {
            this.text.render(" " + value, x + this.text.width(label), y, Colors.GRAY);
        }
        return y + this.text.lineHeight();
    }

    // QUALITY DIALOG
    private void openQualityDialog() {
        if (this.ctx.selectedSource == null) return;
        final var qualities = this.ctx.selectedSource.availableQualities();
        if (qualities == null || qualities.isEmpty()) return;

        this.ctx.availableQualities = qualities.toArray(new MRL.Quality[0]);
        Arrays.sort(this.ctx.availableQualities, Comparator.comparingInt(q -> q.threshold));

        // FIND CURRENT QUALITY INDEX
        this.qualitySelectedIndex = 0;
        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            if (this.ctx.availableQualities[i] == this.ctx.selectedQuality) {
                this.qualitySelectedIndex = i;
                break;
            }
        }

        this.dialogState = DialogState.QUALITY;
    }

    private void renderQualityDialog(final int windowW, final int windowH) {
        if (this.ctx.availableQualities == null) return;

        final int lineH = this.text.lineHeight();
        final int padding = 20;
        final int contentH = lineH + 15 + (this.ctx.availableQualities.length * lineH);
        int contentW = 0;

        for (final MRL.Quality q : this.ctx.availableQualities) {
            contentW = Math.max(contentW, this.text.width("> " + q.name() + " (" + q.threshold + "p)"));
        }

        final int dialogW = Math.max(350, contentW + padding * 2);
        final int dialogH = contentH + padding * 2;

        this.qualityDialogBounds = Dimension.centered(windowW, windowH, dialogW, dialogH);

        DrawTool.setupOrtho(windowW, windowH);
        DrawTool.dialogBox(this.qualityDialogBounds.x(), this.qualityDialogBounds.y(), dialogW, dialogH, Colors.BLUE, 3);

        int y = this.qualityDialogBounds.y() + padding;
        this.text.render("Select Quality", this.qualityDialogBounds.x() + padding, y, Colors.BLUE);
        y += lineH + 15;

        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            final MRL.Quality q = this.ctx.availableQualities[i];
            final boolean selected = (i == this.qualitySelectedIndex);
            final String line = (selected ? "> " : "  ") + q.name() + " (" + q.threshold + "p)";
            this.text.render(line, this.qualityDialogBounds.x() + padding, y, selected ? Colors.BLUE : Colors.GRAY);
            y += lineH;
        }

        DrawTool.restoreProjection();
    }

    // FINISHED DIALOG
    private void showFinishedDialog(final String reason, final boolean isError) {
        if (this.ctx.playerEscPressed) {
            this.ctx.playerEscPressed = false;
            this.returnToMenu();
            return;
        }

        this.ctx.finishedReason = reason;
        this.ctx.finishedWasError = isError;

        this.finishedDialog.title(isError ? "Playback Error" : "Playback Finished")
                .borderColor(isError ? Colors.RED : Colors.BLUE)
                .clearContent()
                .clearButtons()
                .addLine("Reason: " + reason)
                .addButton(isError ? "Retry" : "Replay", this::restartPlayer)
                .addButton("Close", this::returnToMenu);

        // SELECT CLOSE BY DEFAULT ON ERROR (INDEX 1), REPLAY OTHERWISE (INDEX 0)
        this.finishedDialog.setSelectedIndex(isError ? 1 : 0);
        this.dialogState = DialogState.FINISHED;

        this.ctx.releasePlayer();
        this.ctx.playSelectionSound();
    }

    private void renderFinishedDialog(final int windowW, final int windowH) {
        this.finishedDialog.centerIn(this.text, windowW, windowH);
        this.finishedDialog.show();
        this.finishedDialog.render(this.text, windowW, windowH);
    }

    private void restartPlayer() {
        this.dialogState = DialogState.NONE;
        this.startPlayer();
    }

    // NAVIGATION
    private void returnToMenu() {
        this.dialogState = DialogState.NONE;
        this.ctx.releasePlayer();

        if (this.ctx.selectedMRL == null || this.ctx.selectedGroup == null) {
            this.navigator.accept(HomeScreen.Action.BACK);
        } else if (this.ctx.availableSources != null && this.ctx.availableSources.length > 1) {
            this.navigator.accept(HomeScreen.Action.SOURCE_SELECTOR);
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

    // INPUT HANDLING
    @Override
    protected void onKeyRelease(final int key) {
        switch (this.dialogState) {
            case QUALITY -> this.handleQualityKey(key);
            case FINISHED -> this.handleFinishedKey(key);
            case NONE -> this.handlePlayerKey(key);
        }
    }

    private void handlePlayerKey(final int key) {
        final MediaPlayer player = this.ctx.player;
        if (player == null) return;

        switch (key) {
            case GLFW_KEY_ESCAPE -> {
                this.ctx.playerEscPressed = true;
                this.returnToMenu();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.openQualityDialog();
            case GLFW_KEY_SPACE -> player.togglePlay();
            case GLFW_KEY_LEFT -> player.rewind();
            case GLFW_KEY_RIGHT -> player.foward();
            case GLFW_KEY_U -> player.seekQuick(player.time() + 5_000);
            case GLFW_KEY_Y -> player.seekQuick(player.time() - 5_000);
            case GLFW_KEY_S -> player.stop();
            case GLFW_KEY_UP -> player.volume(player.volume() + 5);
            case GLFW_KEY_DOWN -> player.volume(player.volume() - 5);
            case GLFW_KEY_PERIOD -> player.nextFrame();
            case GLFW_KEY_COMMA -> player.previousFrame();
            case GLFW_KEY_N -> this.navigateSource(1);
            case GLFW_KEY_B -> this.navigateSource(-1);
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
                if (this.ctx.player != null) this.ctx.player.quality(this.ctx.selectedQuality);
                this.dialogState = DialogState.NONE;
            }
            case GLFW_KEY_ESCAPE -> this.dialogState = DialogState.NONE;
        }
    }

    private void handleFinishedKey(final int key) {
        switch (key) {
            // USE LEFT/RIGHT FOR HORIZONTAL BUTTON NAVIGATION
            case GLFW_KEY_LEFT -> this.finishedDialog.navigateLeft();
            case GLFW_KEY_RIGHT -> this.finishedDialog.navigateRight();
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.finishedDialog.confirm();
            case GLFW_KEY_ESCAPE -> this.returnToMenu();
        }
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        switch (this.dialogState) {
            case QUALITY -> this.handleQualityHover(mx, my);
            case FINISHED -> this.finishedDialog.handleHover(mx, my);
        }
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        switch (this.dialogState) {
            case QUALITY -> this.handleQualityClick(mx, my);
            case FINISHED -> this.finishedDialog.handleClick(mx, my);
        }
    }

    private void handleQualityHover(final double mx, final double my) {
        final int padding = 20;
        final int lineH = this.text.lineHeight();
        final int startY = this.qualityDialogBounds.y() + padding + lineH + 15;

        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            final int itemY = startY + i * lineH;
            final Dimension area = new Dimension(this.qualityDialogBounds.x() + padding, itemY,
                    this.qualityDialogBounds.width() - padding * 2, lineH);
            if (area.contains(mx, my) && this.qualitySelectedIndex != i) {
                this.qualitySelectedIndex = i;
                this.ctx.playSelectionSound();
                break;
            }
        }
    }

    private void handleQualityClick(final double mx, final double my) {
        final int padding = 20;
        final int lineH = this.text.lineHeight();
        final int startY = this.qualityDialogBounds.y() + padding + lineH + 15;

        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            final int itemY = startY + i * lineH;
            final Dimension area = new Dimension(this.qualityDialogBounds.x() + padding, itemY,
                    this.qualityDialogBounds.width() - padding * 2, lineH);
            if (area.contains(mx, my)) {
                this.qualitySelectedIndex = i;
                this.ctx.selectedQuality = this.ctx.availableQualities[this.qualitySelectedIndex];
                if (this.ctx.player != null) this.ctx.player.quality(this.ctx.selectedQuality);
                this.dialogState = DialogState.NONE;
                break;
            }
        }
    }

    @Override
    public String instructions() {
        return switch (this.dialogState) {
            case QUALITY -> "UP/DOWN: Navigate | ENTER: Select | ESC: Cancel";
            case FINISHED -> "LEFT/RIGHT: Select | ENTER: Confirm | ESC: Close";
            case NONE -> this.ctx.availableSources != null && this.ctx.availableSources.length > 1
                    ? "SPACE: Play/Pause | ESC: Menu | Arrows: Seek/Vol | ENTER: Quality | N/B: Source"
                    : "SPACE: Play/Pause | ESC: Menu | Arrows: Seek/Vol | ENTER: Quality | 0-9: Jump";
        };
    }
}
