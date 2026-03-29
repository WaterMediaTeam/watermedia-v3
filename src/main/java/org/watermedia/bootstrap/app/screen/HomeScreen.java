package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Grid;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Main home screen with grid-based menu options grouped by sections.
 */
public class HomeScreen extends Screen {

    public enum Action {
        OPEN_MULTIMEDIA, UPLOAD_LOGS, CLEANUP,
        MRL_SELECTOR, SOURCE_SELECTOR, PLAYER,
        EXIT, BACK
    }

    private final Consumer<Action> navigator;
    private final Grid<MenuEntry> grid;

    public HomeScreen(final TextRenderer text, final AppContext ctx, final Consumer<Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.grid = new Grid<MenuEntry>()
                .columns(6)
                .cellHeight(130)
                .cellGap(14)
                .borderWidth(4f)
                .labelProvider(MenuEntry::label)
                .iconProvider(MenuEntry::icon)
                .borderColorProvider(e -> {
                    if (e.action() == Action.EXIT) return Colors.RED;
                    if (e.action() == Action.OPEN_MULTIMEDIA) return Colors.GREEN;
                    return Colors.BLUE;
                })
                .iconColorProvider(e -> {
                    if (e.action() == Action.EXIT) return Colors.RED;
                    if (e.action() == Action.OPEN_MULTIMEDIA) return Colors.GREEN;
                    return Colors.BLUE;
                })
                .onSelect((idx, entry) -> this.handleSelect(entry))
                .onSelectionChanged(ctx::playSelectionSound);
    }

    @Override
    public void onEnter() {
        this.rebuildMenu();
    }

    private void rebuildMenu() {
        this.grid.clear();

        // Actions section (Multimedia + Tools + Exit)
        this.grid.section("Actions");
        this.grid.item(new MenuEntry("Play", Action.OPEN_MULTIMEDIA, -1, Grid.Icon.PLAY));
        this.grid.item(new MenuEntry("Upload Logs", Action.UPLOAD_LOGS, -1, Grid.Icon.UPLOAD));
        this.grid.item(new MenuEntry("Cleanup", Action.CLEANUP, -1, Grid.Icon.CLEANUP));
        this.grid.item(new MenuEntry("Exit", Action.EXIT, -1, Grid.Icon.EXIT));

        // Media Tests section
        this.grid.section("Media Tests");
        if (this.ctx.uriGroups != null) {
            for (int i = 0; i < this.ctx.uriGroups.length; i++) {
                this.grid.item(new MenuEntry(this.ctx.uriGroups[i].name(), null, i, Grid.Icon.FOLDER));
            }
        }
        if (!this.ctx.customTests.isEmpty()) {
            final String label = "Custom (" + this.ctx.customTests.size() + ")";
            this.grid.item(new MenuEntry(label, null, -2, Grid.Icon.CUSTOM));
        }
    }

    private void handleSelect(final MenuEntry entry) {
        if (entry.groupIndex() >= 0 && entry.groupIndex() < this.ctx.uriGroups.length) {
            this.openGroup(this.ctx.uriGroups[entry.groupIndex()]);
        } else if (entry.groupIndex() == -2) {
            this.openCustomTests();
        } else if (entry.action() != null) {
            this.navigator.accept(entry.action());
        }
    }

    private void openGroup(final AppContext.URIGroup group) {
        this.ctx.selectedGroup = group;
        this.ctx.groupMRLs.clear();
        for (final AppContext.TestURI testUri : group.uris()) {
            this.ctx.groupMRLs.put(testUri.name(), MediaAPI.getMRL(testUri.uri()));
        }
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    private void openCustomTests() {
        if (this.ctx.customTests.isEmpty()) return;
        this.ctx.selectedGroup = new AppContext.URIGroup("Custom Tests",
                this.ctx.customTests.toArray(new AppContext.TestURI[0]));
        this.ctx.groupMRLs.clear();
        for (final AppContext.TestURI uri : this.ctx.customTests) {
            this.ctx.groupMRLs.put(uri.name(), MediaAPI.getMRL(uri.uri()));
        }
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        DrawTool.setupOrtho(windowW, windowH);

        final int y = this.renderBanner(windowW, windowH) + 10;
        final int maxHeight = windowH - 100 - y;
        final int availableWidth = windowW - AppContext.PADDING * 2;

        this.grid.maxHeight(maxHeight);
        this.grid.calculateBounds(this.text, AppContext.PADDING, y, availableWidth);
        this.grid.render(this.text, windowW, windowH);

        // Render warnings at absolute top-right corner
        this.renderWarnings(windowW);

        DrawTool.restoreProjection();
    }

    private void renderWarnings(final int windowW) {
        int level = 0;
        if (!FFMediaPlayer.loaded()) {
            this.renderWarning("NO FFMPEG ENGINE", windowW, level++);
        }
        if (!AppContext.IN_MODS) {
            this.renderWarning("NO MC CONTEXT", windowW, level++);
        }
    }

    private void renderWarning(final String text, final int windowW, final int level) {
        final int lineH = this.text.lineHeight();
        final int textWidth = this.text.width(text);
        final int hPadding = 8;
        final int triSize = lineH - 6;
        final int triMargin = 6;
        final int margin = 10;

        final int boxH = lineH;
        final int boxW = hPadding + triSize + triMargin + textWidth + hPadding;
        final int boxX = windowW - boxW - margin;
        final int boxY = margin + (boxH + 5) * level;

        DrawTool.disableTextures();

        DrawTool.fill(boxX, boxY, boxW, boxH, 1f, 0.5f, 0f, 0.9f);
        DrawTool.rect(boxX, boxY, boxW, boxH, 0.8f, 0.35f, 0f, 1f, 2);

        final float triX = boxX + hPadding;
        final float triCenterY = boxY + boxH / 2f;
        DrawTool.fillRoundedTriangle(
                triX + triSize / 2f, triCenterY - triSize / 2f + 1,
                triX, triCenterY + triSize / 2f - 1,
                triX + triSize, triCenterY + triSize / 2f - 1,
                2f,
                0.7f, 0.3f, 0f, 1f);

        DrawTool.enableTextures();

        final int textX = boxX + hPadding + triSize + triMargin;
        final int textY = boxY + 8;
        this.text.render(text, textX, textY, Colors.BLACK);
    }

    public int renderBanner(final int windowW, final int windowH) {
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
        switch (key) {
            case GLFW_KEY_UP -> this.grid.moveUp();
            case GLFW_KEY_DOWN -> this.grid.moveDown();
            case GLFW_KEY_LEFT -> this.grid.moveLeft();
            case GLFW_KEY_RIGHT -> this.grid.moveRight();
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.grid.confirm();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(Action.EXIT);
        }
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        this.grid.handleHover(mx, my);
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        this.grid.handleClick(mx, my);
    }

    @Override
    public void handleScroll(final double yOffset) {
        this.grid.handleScroll(yOffset);
    }

    @Override
    public String instructions() {
        return "ARROWS: Navigate | ENTER: Select | ESC: Exit";
    }

    private record MenuEntry(String label, Action action, int groupIndex, Grid.Icon icon) {
    }
}
