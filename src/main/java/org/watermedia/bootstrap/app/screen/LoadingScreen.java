package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;

import java.awt.Color;

/**
 * Boot splash. Renders the banner plus two progress bars:
 *   - outer: WaterMedia.step()/steps()  — APIs registry
 *   - inner: currentAPI.step()/steps() — work units inside the current API
 */
public class LoadingScreen extends Screen {

    private static final int BAR_HEIGHT = 26;
    private static final int LABEL_TO_BAR = 10;
    private static final int BAR_TO_NEXT_LABEL = 32;
    private static final int TITLE_TO_BARS = 50;
    private static final int BANNER_TO_TITLE = 40;
    private static final int BANNER_MAX_HEIGHT = 180;

    public LoadingScreen(final TextRenderer text, final AppContext ctx) {
        super(text, ctx);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        DrawTool.setupOrtho(windowW, windowH);

        final WaterMediaAPI api = WaterMedia.currentAPI();
        final int outerStep = WaterMedia.step();
        final int outerSteps = WaterMedia.steps();
        final int innerStep = api != null ? api.step() : 0;
        final int innerSteps = api != null ? api.steps() : 0;
        final String apiName = api != null ? api.name() : "Bootstrapping";
        final String stepName = api != null ? api.stepName() : "";

        final float outerProgress = clamp01((float) outerStep / Math.max(1, outerSteps));
        final float innerProgress = clamp01((float) innerStep / Math.max(1, innerSteps));

        final int lineH = this.text.lineHeight();
        final int barW = Math.min(720, windowW - 160);
        final int barX = (windowW - barW) / 2;

        // BANNER DIMENSIONS
        int bannerW = 0, bannerH = 0;
        if (this.ctx.bannerTextureId > 0) {
            final int targetH = Math.min(BANNER_MAX_HEIGHT, windowH / 4);
            final float scale = (float) targetH / this.ctx.bannerHeight;
            bannerH = (int) (this.ctx.bannerHeight * scale);
            bannerW = (int) (this.ctx.bannerWidth * scale);
        }

        // VERTICAL LAYOUT — sum of every section so we can center the block.
        final int contentH =
                bannerH + (bannerH > 0 ? BANNER_TO_TITLE : 0)
                        + lineH + TITLE_TO_BARS
                        + lineH + LABEL_TO_BAR + BAR_HEIGHT
                        + BAR_TO_NEXT_LABEL
                        + lineH + LABEL_TO_BAR + BAR_HEIGHT;

        int y = Math.max(40, (windowH - contentH) / 2);

        // BANNER
        if (bannerH > 0) {
            final int bannerX = (windowW - bannerW) / 2;
            DrawTool.bindTexture(this.ctx.bannerTextureId);
            DrawTool.color(1f, 1f, 1f, 1f);
            DrawTool.blit(bannerX, y, bannerW, bannerH);
            y += bannerH + BANNER_TO_TITLE;
        }

        // TITLE
        final String title = "Initializing WATERMeDIA";
        this.text.render(title, (windowW - this.text.width(title)) / 2, y, Colors.WHITE);
        y += lineH + TITLE_TO_BARS;

        // OUTER BAR — APIs (e.g. MediaAPI 2/3)
        final String outerLabel = apiName + "  " + outerStep + "/" + outerSteps;
        this.text.render(outerLabel, barX, y, Colors.WHITE);
        y += lineH + LABEL_TO_BAR;
        renderBar(barX, y, barW, BAR_HEIGHT, outerProgress, Colors.BLUE);
        y += BAR_HEIGHT + BAR_TO_NEXT_LABEL;

        // INNER BAR — work units inside current API (e.g. Loading FFMPEG 19/19)
        final String innerLabel = "Loading " + (stepName.isEmpty() ? "..." : stepName)
                + "  " + innerStep + "/" + innerSteps;
        this.text.render(innerLabel, barX, y, Colors.GRAY);
        y += lineH + LABEL_TO_BAR;
        renderBar(barX, y, barW, BAR_HEIGHT, innerProgress, Colors.GREEN);

        DrawTool.restoreProjection();
    }

    private static void renderBar(final int x, final int y, final int w, final int h,
                                  final float progress, final Color fill) {
        DrawTool.disableTextures();
        DrawTool.fill(x, y, w, h, 0.12f, 0.12f, 0.12f, 1f);
        if (progress > 0f) {
            DrawTool.fill(x, y, w * progress, h, fill);
        }
        DrawTool.rect(x, y, w, h,
                fill.getRed() / 255f, fill.getGreen() / 255f, fill.getBlue() / 255f, 1f, 2);
        DrawTool.enableTextures();
    }

    private static float clamp01(final float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    public String instructions() {
        return "Loading... please wait";
    }
}
