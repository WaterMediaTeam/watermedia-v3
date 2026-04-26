package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Grid;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Screen for selecting MRLs from a group, displayed as a grid with thumbnail backgrounds.
 */
public class MRLSelectorScreen extends Screen {

    private final Consumer<HomeScreen.Action> navigator;
    private final Grid<AppContext.TestURI> grid;
    private boolean loading;
    private long loadStartTime;
    private AppContext.TestURI pendingUri;
    private GLEngine.Builder glEngineBuilder;

    // THUMBNAIL PLAYERS KEYED BY MRL NAME
    private final Map<String, MediaPlayer> thumbnailPlayers = new LinkedHashMap<>();
    private final java.util.Set<String> thumbnailAttempted = new java.util.HashSet<>();

    public MRLSelectorScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.grid = new Grid<AppContext.TestURI>()
                .columns(6)
                .cellHeight(120)
                .cellGap(10)
                .borderWidth(4f)
                .innerPadding(10)
                .labelProvider(AppContext.TestURI::name)
                .sublabelProvider(AppContext.TestURI::uri)
                .iconProvider(uri -> {
                    final MRL mrl = ctx.groupMRLs.get(uri.name());
                    if (mrl == null) return Grid.Icon.STATUS_NULL;
                    if (mrl.error()) return Grid.Icon.STATUS_ERROR;
                    if (mrl.ready()) return Grid.Icon.STATUS_READY;
                    if (mrl.busy()) return Grid.Icon.STATUS_LOADING;
                    return Grid.Icon.STATUS_UNKNOWN;
                })
                .iconColorProvider(uri -> {
                    final MRL mrl = ctx.groupMRLs.get(uri.name());
                    if (mrl == null) return Colors.GRAY;
                    if (mrl.error()) return Colors.RED;
                    if (mrl.ready()) return Colors.GREEN;
                    if (mrl.busy()) return Colors.BLUE;
                    return Colors.GRAY;
                })
                .borderColorProvider(uri -> {
                    final MRL mrl = ctx.groupMRLs.get(uri.name());
                    if (mrl != null && mrl.error()) return Colors.RED;
                    return Colors.BLUE;
                })
                .thumbnailProvider(uri -> this.thumbnailPlayers.get(uri.name()))
                .onSelect(this::handleSelect)
                .onSelectionChanged(ctx::playSelectionSound);

        this.glEngineBuilder = new GLEngine.Builder(Thread.currentThread(), this.ctx);
    }

    @Override
    public void onEnter() {
        this.loading = false;
        this.pendingUri = null;
        if (this.ctx.selectedGroup != null) {
            this.grid.clear();
            this.grid.title("Select Media - " + this.ctx.selectedGroup.name());
            for (final AppContext.TestURI uri: this.ctx.selectedGroup.uris()) {
                this.grid.item(uri);
            }
        }
    }

    @Override
    public void onExit() {
        this.releaseThumbnailPlayers();
    }

    // CALLED EACH FRAME TO CREATE THUMBNAIL PLAYERS FOR MRLS THAT JUST BECAME READY.
    private void updateThumbnailPlayers() {
        if (this.ctx.selectedGroup == null) return;

        for (final AppContext.TestURI uri: this.ctx.selectedGroup.uris()) {
            final String name = uri.name();
            if (this.thumbnailAttempted.contains(name)) continue;

            final MRL mrl = this.ctx.groupMRLs.get(name);
            if (mrl == null) continue;
            if (!mrl.ready() && !mrl.error()) continue; // STILL LOADING, WAIT

            // MARK AS ATTEMPTED SO WE DON'T RETRY EVERY FRAME
            this.thumbnailAttempted.add(name);

            if (mrl.error()) continue; // NO THUMBNAIL FOR ERRORED MRLs

            final MRL.Source[] sources = mrl.sources();
            if (sources.length == 0) continue;

            final MediaPlayer player = mrl.createThumbnailPlayer(this.glEngineBuilder.build());

            if (player != null) {
                player.repeat(true);
                player.start();
                this.thumbnailPlayers.put(name, player);
            }
        }
    }

    private void releaseThumbnailPlayers() {
        for (final MediaPlayer player: this.thumbnailPlayers.values()) {
            player.release();
        }
        this.thumbnailPlayers.clear();
        this.thumbnailAttempted.clear();
    }

    private void handleSelect(final int index, final AppContext.TestURI uri) {
        this.ctx.selectedMRLName = uri.name();
        this.ctx.selectedMRL = this.ctx.groupMRLs.get(uri.name());

        if (this.ctx.selectedMRL == null) {
            this.ctx.showError("Null", "The MRL is null", null);
            return;
        }

        if (this.ctx.selectedMRL.error()) {
            this.ctx.showError("Error", "Unable to open media, exception occurred on opening", null);
            return;
        }

        if (this.ctx.selectedMRL.expired()) {
            this.ctx.showError("MRL expired", "Re-freshing MRL", () -> {
                this.ctx.selectedMRL = null;
                this.ctx.groupMRLs.remove(uri.name());
            });
            return;
        }

        if (this.ctx.selectedMRL.busy()) {
            this.loading = true;
            this.loadStartTime = System.currentTimeMillis();
            this.pendingUri = uri;
            return;
        }

        if (!this.ctx.selectedMRL.ready()) return;

        this.proceedWithMRL();
    }

    private void proceedWithMRL() {
        this.ctx.availableSources = this.ctx.selectedMRL.sources();
        if (this.ctx.availableSources == null || this.ctx.availableSources.length == 0) return;

        if (this.ctx.availableSources.length == 1) {
            this.ctx.sourceSelectorIndex = 0;
            this.ctx.selectedSource = this.ctx.availableSources[0];
            this.ctx.playerEscPressed = false;
            this.navigator.accept(HomeScreen.Action.PLAYER);
        } else {
            this.ctx.sourceSelectorIndex = 0;
            this.navigator.accept(HomeScreen.Action.SOURCE_SELECTOR);
        }
    }

    private void checkLoadingState() {
        if (this.ctx.selectedMRL == null) {
            this.loading = false;
            this.pendingUri = null;
            return;
        }

        if (this.ctx.selectedMRL.ready()) {
            this.loading = false;
            this.pendingUri = null;
            this.proceedWithMRL();
            return;
        }

        if (this.ctx.selectedMRL.error()) {
            this.loading = false;
            this.pendingUri = null;
            this.ctx.showError("Error", "Unable to open media, exception occurred on opening", null);
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime > 30000) {
            this.loading = false;
            this.pendingUri = null;
            this.ctx.showError("Load Error", "MRL loading timed out", null);
        }
    }

    private void renderLoadingDialog(final int windowW, final int windowH) {
        DrawTool.setupOrtho(windowW, windowH);

        final int dots = (int) ((System.currentTimeMillis() / 500) % 4);
        final String loadingText = "Loading" + ".".repeat(dots);
        final String mrlName = this.pendingUri != null ? this.pendingUri.name() : "";

        final int padding = 20;
        final int lineH = this.text.lineHeight();

        final int contentW = Math.max(this.text.width(loadingText),
                Math.max(this.text.width(mrlName), this.text.width("ESC to cancel")));
        final int dialogW = Math.min(Math.max(contentW + padding * 2 + 40, 400), windowW - 100);
        final int dialogH = padding + lineH + 15 + lineH + 10 + lineH + padding;

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = (windowH - dialogH) / 2;

        DrawTool.dialogBox(dialogX, dialogY, dialogW, dialogH, Colors.BLUE, 3);

        int y = dialogY + padding;
        this.text.render(loadingText, dialogX + padding, y, Colors.BLUE);
        y += lineH + 15;

        this.text.render(mrlName, dialogX + padding, y, Colors.GRAY);
        y += lineH + 10;

        this.text.render("ESC to cancel", dialogX + padding, y, Colors.GRAY);

        DrawTool.restoreProjection();
    }

    private void goBack() {
        this.ctx.clearGroupState();
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        if (this.ctx.selectedGroup == null) return;

        if (this.loading) {
            this.checkLoadingState();
        }

        this.updateThumbnailPlayers();

        DrawTool.setupOrtho(windowW, windowH);

        final int y = this.renderBanner(windowW, windowH) + 10;
        final int maxHeight = windowH - 100 - y;
        final int availableWidth = windowW - AppContext.PADDING * 2;

        this.grid.maxHeight(maxHeight);
        this.grid.calculateBounds(this.text, AppContext.PADDING, y, availableWidth);
        this.grid.render(this.text, windowW, windowH);

        DrawTool.restoreProjection();

        if (this.loading) {
            this.renderLoadingDialog(windowW, windowH);
        }
    }

    private int renderBanner(final int windowW, final int windowH) {
        if (this.ctx.bannerTextureId <= 0) return AppContext.PADDING;

        final int targetH = Math.min(Math.max(125, (int) (windowH * 0.17f)), windowH - 200);
        final float scale = (float) targetH / this.ctx.bannerHeight;
        final int renderH = (int) (this.ctx.bannerHeight * scale);
        final int renderW = (int) (this.ctx.bannerWidth * scale);

        final int bannerX = (windowW - renderW) / 2;

        DrawTool.bindTexture(this.ctx.bannerTextureId);
        DrawTool.color(1, 1, 1, 1);
        DrawTool.blit(bannerX, AppContext.PADDING, renderW, renderH);

        final int lineY = AppContext.PADDING + renderH + 15;
        DrawTool.disableTextures();
        DrawTool.lineH(0, lineY, windowW, 0.31f, 0.71f, 1f, 1f, 4);
        DrawTool.enableTextures();

        return lineY + 20;
    }

    @Override
    protected void onKeyRelease(final int key) {
        if (this.loading) {
            if (key == GLFW_KEY_ESCAPE) {
                this.loading = false;
                this.pendingUri = null;
            }
            return;
        }

        switch (key) {
            case GLFW_KEY_UP -> this.grid.moveUp();
            case GLFW_KEY_DOWN -> this.grid.moveDown();
            case GLFW_KEY_LEFT -> this.grid.moveLeft();
            case GLFW_KEY_RIGHT -> this.grid.moveRight();
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.grid.confirm();
            case GLFW_KEY_ESCAPE -> this.goBack();
        }
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        if (this.loading) return;
        this.grid.handleHover(mx, my);
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (this.loading) return;
        this.grid.handleClick(mx, my);
    }

    @Override
    public void handleScroll(final double yOffset) {
        if (this.loading) return;
        this.grid.handleScroll(yOffset);
    }

    @Override
    public String instructions() {
        if (this.loading) return "ESC: Cancel";
        return "ARROWS: Navigate | ENTER: Select | ESC: Back";
    }
}
