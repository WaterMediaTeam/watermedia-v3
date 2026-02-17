package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Selector;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Screen for selecting MRLs from a group.
 */
public class MRLSelectorScreen extends Screen {

    private final Consumer<HomeScreen.Action> navigator;
    private final Selector<AppContext.TestURI> selector;

    public MRLSelectorScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.selector = new Selector<AppContext.TestURI>()
                .labelProvider(uri -> text.padOrTruncate(uri.name(), 35))
                .statusProvider(uri -> {
                    final MRL mrl = ctx.groupMRLs.get(uri.name());
                    final int count = mrl != null && mrl.ready() ? mrl.sourceCount() : 0;
                    return "[" + count + "] [" + this.getMRLStatus(mrl) + "]";
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
        if (this.ctx.selectedGroup != null) {
            this.selector.clear();
            this.selector.title("Select Media - " + this.ctx.selectedGroup.name());
            for (final AppContext.TestURI uri : this.ctx.selectedGroup.uris()) {
                this.selector.item(uri);
            }
        }
    }

    private String getMRLStatus(final MRL mrl) {
        if (mrl == null) return "NULL";
        if (mrl.error()) return "ERROR";
        if (mrl.ready()) return "READY";
        if (mrl.busy()) return "LOADING";
        return "UNKNOWN";
    }

    private void handleSelect(final int index, final AppContext.TestURI uri) {
        this.ctx.selectedMRLName = uri.name();
        this.ctx.selectedMRL = this.ctx.groupMRLs.get(uri.name());

        if (this.ctx.selectedMRL == null || !this.ctx.selectedMRL.ready()) {
            if (this.ctx.selectedMRL == null) {
                this.ctx.showError("Null", "The MRL is null", null);
            }
            if (this.ctx.selectedMRL.error()) {
                this.ctx.showError("Error", "Unable to open media, exception occurred on opening", null);
            }
            if (this.ctx.selectedMRL.expired()) {
                this.ctx.showError("MRL expired", "Re-freshing MRL", () -> {
                    this.ctx.selectedMRL = null;
                    this.ctx.groupMRLs.remove(uri.name());
                });
            }
        }

        this.ctx.availableSources = this.ctx.selectedMRL.sources();
        if (this.ctx.availableSources.length == 0) return;

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

    private void goBack() {
        this.ctx.clearGroupState();
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        if (this.ctx.selectedGroup == null) return;

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
