package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Selector;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Screen for selecting sources from an MRL.
 */
public class SourceSelectorScreen extends Screen {

    private final Consumer<HomeScreen.Action> navigator;
    private final Selector<MRL.Source> selector;

    public SourceSelectorScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.selector = new Selector<MRL.Source>()
                .labelProvider(src -> {
                    final String title = src.metadata() != null && src.metadata().title() != null
                            ? src.metadata().title() : "Untitled";
                    return text.padOrTruncate(title, 35);
                })
                .statusProvider(src -> {
                    final MRL.Quality best = src.availableQualities().stream()
                            .max(Comparator.comparingInt(q -> q.threshold))
                            .orElse(MRL.Quality.UNKNOWN);
                    return "[" + best.name() + "] [" + src.type().name() + "]";
                })
                .onSelect(this::handleSelect)
                .onBack(this::goBack)
                .onSelectionChanged(ctx::playSelectionSound)
                .showBack(true)
                .backLabel("[BACK]")
                .minWidth(AppContext.MENU_WIDTH);
    }

    @Override
    public void onEnter() {
        if (this.ctx.availableSources != null) {
            this.selector.clear();
            this.selector.title("Select Source - " + this.ctx.selectedMRLName);
            this.selector.items(Arrays.asList(this.ctx.availableSources));
        }
    }

    private void handleSelect(final int index, final MRL.Source source) {
        this.ctx.sourceSelectorIndex = index;
        this.ctx.selectedSource = source;
        this.ctx.playerEscPressed = false;
        this.navigator.accept(HomeScreen.Action.PLAYER);
    }

    private void goBack() {
        this.ctx.clearSourceState();
        this.navigator.accept(HomeScreen.Action.MRL_SELECTOR);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        if (this.ctx.availableSources == null) return;

        DrawTool.setupOrtho(windowW, windowH);

        final int y = this.renderBanner(windowW, windowH) + 10;
        // Calculate max height: from current Y to bottom bar area (windowH - 100 for padding)
        final int maxHeight = windowH - 100 - y;
        this.selector.maxHeight(maxHeight);
        this.selector.calculateBounds(this.text, AppContext.PADDING, y);
        this.selector.render(this.text, windowW, windowH);

        DrawTool.restoreProjection();
    }

    private int renderBanner(final int windowW, final int windowH) {
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
            case GLFW_KEY_ESCAPE -> this.goBack();
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
    public void handleScroll(final double yOffset) {
        this.selector.handleScroll(yOffset);
    }

    @Override
    public String instructions() {
        return "UP/DOWN: Navigate | ENTER: Select | ESC: Back";
    }
}
