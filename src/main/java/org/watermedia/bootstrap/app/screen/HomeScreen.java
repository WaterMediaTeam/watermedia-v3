package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Selector;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Main home screen with grouped menu options.
 */
public class HomeScreen extends Screen {

    public enum Action {
        OPEN_MULTIMEDIA, UPLOAD_LOGS, CLEANUP,
        MRL_SELECTOR, SOURCE_SELECTOR, PLAYER,
        EXIT, BACK
    }

    private final Consumer<Action> navigator;
    private final Selector<MenuEntry> selector;

    public HomeScreen(final TextRenderer text, final AppContext ctx, final Consumer<Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.selector = new Selector<MenuEntry>()
                .labelProvider(MenuEntry::label)
                .onSelect((idx, entry) -> this.handleSelect(entry))
                .onBack(() -> navigator.accept(Action.EXIT))
                .onSelectionChanged(ctx::playSelectionSound)
                .showBack(false)
                .indent(20)
                .minWidth(AppContext.MENU_WIDTH);
    }

    @Override
    public void onEnter() {
        this.rebuildMenu();
    }

    private void rebuildMenu() {
        this.selector.clear();

        // Multimedia section
        this.selector.section("Multimedia");
        this.selector.item("Play", new MenuEntry("Play", Action.OPEN_MULTIMEDIA, -1));

        // Tools section
        this.selector.section("Tools");
        this.selector.item("Upload your log files", new MenuEntry("Upload your log files", Action.UPLOAD_LOGS, -1));
        this.selector.item("Cleanup", new MenuEntry("Cleanup", Action.CLEANUP, -1));

        // Test URL Presets section
        this.selector.section("Test URL Presets");
        if (this.ctx.uriGroups != null) {
            for (int i = 0; i < this.ctx.uriGroups.length; i++) {
                final String name = this.ctx.uriGroups[i].name();
                this.selector.item(name, new MenuEntry(name, null, i));
            }
        }
        if (!this.ctx.customTests.isEmpty()) {
            final String label = "Custom Tests (" + this.ctx.customTests.size() + ")";
            this.selector.item(label, new MenuEntry(label, null, -2)); // -2 = custom tests
        }

        // Exit
        this.selector.showBack(true);
        this.selector.backLabel("[EXIT]");
    }

    private void handleSelect(final MenuEntry entry) {
        if (entry.groupIndex >= 0 && entry.groupIndex < this.ctx.uriGroups.length) {
            this.openGroup(this.ctx.uriGroups[entry.groupIndex]);
        } else if (entry.groupIndex == -2) {
            this.openCustomTests();
        } else if (entry.action != null) {
            this.navigator.accept(entry.action);
        }
    }

    private void openGroup(final AppContext.URIGroup group) {
        this.ctx.selectedGroup = group;
        this.ctx.groupMRLs.clear();
        for (final AppContext.TestURI testUri : group.uris()) {
            this.ctx.groupMRLs.put(testUri.name(), MRL.get(testUri.uri()));
        }
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    private void openCustomTests() {
        if (this.ctx.customTests.isEmpty()) return;
        this.ctx.selectedGroup = new AppContext.URIGroup("Custom Tests",
                this.ctx.customTests.toArray(new AppContext.TestURI[0]));
        this.ctx.groupMRLs.clear();
        for (final AppContext.TestURI uri : this.ctx.customTests) {
            this.ctx.groupMRLs.put(uri.name(), MRL.get(uri.uri()));
        }
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        DrawTool.setupOrtho(windowW, windowH);

        final int y = this.renderBanner(windowW, windowH) + 10;
        this.selector.calculateBounds(this.text, AppContext.PADDING, y);
        this.selector.render(this.text, windowW, windowH);

        DrawTool.restoreProjection();
    }

    public int renderBanner(final int windowW, final int windowH) {
        if (this.ctx.bannerTextureId <= 0) return AppContext.PADDING;

        final float scale = Math.min(1f, (float) Math.min(125, windowH - 200) / this.ctx.bannerHeight);
        final int renderH = (int) (this.ctx.bannerHeight * scale);
        final int renderW = (int) (this.ctx.bannerWidth * scale);

        DrawTool.bindTexture(this.ctx.bannerTextureId);
        DrawTool.color(1, 1, 1, 1);
        DrawTool.blit(AppContext.PADDING, AppContext.PADDING, renderW, renderH);

        final int lineY = AppContext.PADDING + renderH + 15;
        DrawTool.disableTextures();
        DrawTool.lineH(0, lineY, windowW, 0.31f, 0.71f, 1f, 1f, 4);
        DrawTool.enableTextures();

        return lineY + 20;
    }

    @Override
    protected void onKeyRelease(final int key) {
        switch (key) {
            case GLFW_KEY_UP -> this.selector.moveUp();
            case GLFW_KEY_DOWN -> this.selector.moveDown();
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.selector.confirm();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(Action.EXIT);
        }
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        this.selector.handleHover(mx, my);
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        this.selector.handleClick(mx, my);
    }

    @Override
    public String instructions() {
        return "UP/DOWN: Navigate | ENTER: Select | ESC: Exit";
    }

    private record MenuEntry(String label, Action action, int groupIndex) {
    }
}
