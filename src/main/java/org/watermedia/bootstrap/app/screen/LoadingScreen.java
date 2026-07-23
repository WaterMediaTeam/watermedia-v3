package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.Assets;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Boot splash matching the handoff loading layout, as a fully retained tree: a centered column with
 * the glowing banner, the animated duck (or the glowing pack icon as fallback), the spaced title, one
 * thin aggregate progress bar and the stacked boot log tail. Live boot state (progress ramp, duck
 * frame, log lines) is pulled in the elements' {@code onUpdate} hooks every frame.
 */
public final class LoadingScreen extends Screen {

    private static final int BANNER_MAX_W = 1000;
    private static final int BANNER_MAX_H = 170;
    private static final int BANNER_UP_SHIFT = 18;
    private static final int ICON_SIZE = 72;
    private static final int DUCK_SIZE = 216;
    private static final int BAR_W = 380;
    private static final int BAR_H = 8;
    private static final int STATUS_LINE_H = 28;
    private static final int PROGRESS_ANIM_MS = 520;
    private static final int TITLE_SPACING = 3;
    private static final float TITLE_SCALE = AppTheme.TEXT_DISPLAY;
    private static final float STATUS_SCALE = AppTheme.TEXT_SECTION;

    // RENDER ENGINE LABEL FOR THE BOOT LOG — FIXED BEFORE THE SPLASH EXISTS, INJECTED SO THE RETAINED
    // TREE STAYS FULLY DECOUPLED FROM THE RENDER BACKEND
    private final String engine;

    /**
     * Creates the boot splash.
     *
     * @param text   the shared text renderer
     * @param ctx    the application context
     * @param engine the display name of the active render engine, shown in the boot log
     */
    public LoadingScreen(final TextRenderer text, final AppContext ctx, final String engine) {
        super(text, ctx);
        this.engine = engine == null ? "" : engine;
    }

    @Override
    protected Element<?> build() {
        return new CenterFrame().add(Parent.column()
                .width(MAX_PARENT)
                .add(new Banner().gravity(Gravity.CENTER).margin(new Spacing(0, 0, 24, 0)))
                .add(new Duck().gravity(Gravity.CENTER).margin(new Spacing(0, 0, 22, 0)))
                .add(new Text("LOADING WATERMEDIA...").scale(TITLE_SCALE).letterSpacing(TITLE_SPACING)
                        .color(AppTheme.NEON_LIGHT).gravity(Gravity.CENTER).margin(new Spacing(0, 0, 24, 0)))
                .add(new Bar().gravity(Gravity.CENTER).margin(new Spacing(0, 0, 26, 0)))
                .add(new BootLog().gravity(Gravity.CENTER)));
    }

    @Override
    public boolean continuous() {
        return true;
    }

    // NO keybinds() OVERRIDE: THE BOOT SPLASH HAS NO CANCEL PATH (BOOT IS ALREADY IN FLIGHT AND THE
    // SCREEN HOLDS NO NAVIGATOR), SO IT ADVERTISES NONE — THE BASE RETURNS AN EMPTY LIST

    // ASPECT-FIT INTO A BOX — ZERO WHEN THE SOURCE OR THE BOX IS INVALID (MISSING/LATE-LOADED TEXTURE)
    private static Fit fit(final int srcW, final int srcH, final int maxW, final int maxH) {
        if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) return new Fit(0, 0);
        final float scale = Math.min((float) maxW / srcW, (float) maxH / srcH);
        return new Fit(Math.max(1, Math.round(srcW * scale)), Math.max(1, Math.round(srcH * scale)));
    }

    // GLOW SPRITE BEHIND AN IMAGE — THE GLOW TEXTURE IS THE SOURCE PLUS BLUR PADDING, SO IT IS SCALED BY
    // THE IMAGE'S OWN SCALE AND OVERDRAWS EVENLY AROUND IT
    private static void drawGlow(final Canvas canvas, final int glowId, final int glowW, final int glowH,
                                 final int srcW, final int srcH, final int x, final int y, final int w, final int h) {
        if (glowId <= 0 || glowW <= 0 || glowH <= 0 || srcW <= 0 || srcH <= 0) return;
        final float sx = (float) w / srcW;
        final float sy = (float) h / srcH;
        canvas.image(glowId, x - (glowW - srcW) * 0.5f * sx, y - (glowH - srcH) * 0.5f * sy,
                glowW * sx, glowH * sy, null);
    }

    private static StatusLine ok(final String message) {
        return new StatusLine("[OK]", message, AppTheme.GREEN, AppTheme.TEXT);
    }

    private static StatusLine pending(final String message) {
        return new StatusLine("[..]", message, AppTheme.TEXT_FAINT, AppTheme.TEXT_SOFT);
    }

    private static StatusLine fail(final String message) {
        return new StatusLine("[NO]", message, AppTheme.RED, AppTheme.TEXT_SOFT);
    }

    private static String apiMessage(final String apiName, final String stepName) {
        final String name = clean(apiName).isEmpty() ? "WaterMedia API" : apiName;
        return clean(stepName).isEmpty() ? name : name + ": " + stepName;
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }

    private static float clamp01(final float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static double clamp01(final double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private record StatusLine(String tag, String message, Color tagColor, Color messageColor) {
    }

    // FITTED PIXEL SIZE — NAMED Fit (NOT Box) SO IT NEVER SHADOWS THE element.Box DRAWABLE THE SIBLING SCREENS USE
    private record Fit(int w, int h) {
    }

    // FULL-SLOT FRAME CENTERING THE CONTENT COLUMN — HORIZONTALLY EXACT, VERTICALLY CENTERED BUT NEVER
    // CLOSER THAN 32PX TO THE SLOT TOP, MATCHING THE LEGACY max(32, (bodyH - contentH) / 2) CLAMP
    private static final class CenterFrame extends Group<CenterFrame> {

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            for (final Element<?> child: this.children) {
                if (child.visible()) child.measure(innerAvailWidth, innerAvailHeight);
            }
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            for (final Element<?> child: this.children) {
                if (!child.visible()) continue;
                child.layout(this.innerLeft() + (this.innerWidth() - child.measuredWidth()) / 2,
                        this.innerTop() + Math.max(32, (this.innerHeight() - child.measuredHeight()) / 2));
            }
        }
    }

    // BANNER ZONE — MEASURES THE FITTED BANNER PLUS THE UP-SHIFT SLACK BELOW IT AND PAINTS GLOW + BANNER
    // AT ITS TOP, SO THE BANNER SITS BANNER_UP_SHIFT ABOVE ITS FLOW SLOT EXACTLY LIKE THE LEGACY SPLASH
    private static final class Banner extends Element<Banner> {

        private int imgW;
        private int imgH;

        @Override
        protected void onUpdate() {
            // NO BANNER SOURCE, NO BLOCK — THE COLUMN SKIPS THE ELEMENT AND ITS MARGIN ENTIRELY
            this.visible = this.ctx.assets.bannerWidth > 0 && this.ctx.assets.bannerHeight > 0;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final Assets a = this.ctx.assets;
            final Fit box = fit(a.bannerWidth, a.bannerHeight,
                    Math.min(BANNER_MAX_W, Math.max(180, innerAvailWidth - 96)), BANNER_MAX_H);
            this.imgW = box.w();
            this.imgH = box.h();
            this.contentWidth = box.w();
            this.contentHeight = box.h() > 0 ? box.h() + BANNER_UP_SHIFT : 0;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            // TEXTURE IDS READ PER DRAW — THEY CAN FINISH LOADING AFTER THE FIRST FRAMES
            final Assets a = this.ctx.assets;
            drawGlow(canvas, a.bannerGlowId, a.bannerGlowWidth, a.bannerGlowHeight,
                    a.bannerWidth, a.bannerHeight, this.left, this.top, this.imgW, this.imgH);
            canvas.image(a.bannerId, this.left, this.top, this.imgW, this.imgH, null);
        }
    }

    // ANIMATED DUCK (FALLBACK: GLOWING PACK ICON) — THE SLOT ALWAYS SPANS THE FULL DUCK/ICON BOX HEIGHT
    // AS THE LEGACY CENTERING DID; THE FRAME IS PICKED BY WALL TIME IN onUpdate AND PAINTED IN onDraw
    private static final class Duck extends Element<Duck> {

        private boolean duck;
        private int frame;
        private int imgW;
        private int imgH;

        @Override
        protected void onUpdate() {
            final int count = this.ctx.assets.duckFrameIds.length;
            this.frame = count > 0 ? (int) ((System.currentTimeMillis() / 90L) % count) : 0;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final Assets a = this.ctx.assets;
            this.duck = a.duckFrameIds.length > 0 && a.duckFrameWidth > 0 && a.duckFrameHeight > 0;
            final Fit box = this.duck
                    ? fit(a.duckFrameWidth, a.duckFrameHeight, DUCK_SIZE, DUCK_SIZE)
                    : fit(a.iconWidth, a.iconHeight, ICON_SIZE, ICON_SIZE);
            this.imgW = box.w();
            this.imgH = box.h();
            this.contentWidth = box.w();
            this.contentHeight = a.duckFrameIds.length > 0 ? DUCK_SIZE : ICON_SIZE;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final Assets a = this.ctx.assets;
            if (this.duck) {
                final int[] ids = a.duckFrameIds;
                if (ids.length == 0) return;
                canvas.image(ids[Math.min(this.frame, ids.length - 1)], this.left, this.top, this.imgW, this.imgH, null);
            } else if (a.iconId > 0) {
                drawGlow(canvas, a.iconGlowId, a.iconGlowWidth, a.iconGlowHeight,
                        a.iconWidth, a.iconHeight, this.left, this.top, this.imgW, this.imgH);
                canvas.image(a.iconId, this.left, this.top, this.imgW, this.imgH, null);
            }
        }
    }

    // AGGREGATE PROGRESS BAR — DARK TRACK, NEON OUTLINE AND GLOW, GRADIENT FILL; THE DISPLAYED VALUE
    // CHASES THE LIVE WaterMedia STEP RATIO WITH THE LEGACY EASE-IN-OUT RAMP
    private static final class Bar extends Element<Bar> {

        private float shown;
        private float from;
        private float target;
        private long startMs;

        @Override
        protected void onUpdate() {
            final float goal = clamp01((float) WaterMedia.completedWorkSteps() / Math.max(1, WaterMedia.totalWorkSteps()));
            final long now = System.currentTimeMillis();
            if (Math.abs(goal - this.target) > 0.001f) {
                this.from = this.shown;
                this.target = goal;
                this.startMs = now;
            }
            final double t = clamp01((now - this.startMs) / (double) PROGRESS_ANIM_MS);
            this.shown = t >= 1.0 ? this.target : clamp01((float) MathUtil.easeInOutQuad(this.from, this.target, t));
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.contentWidth = Math.min(BAR_W, Math.max(180, innerAvailWidth - 96));
            this.contentHeight = BAR_H;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            final float fillW = Math.max(0f, (w - 2) * this.shown);
            canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 235));
            canvas.stroke(x, y, w, h, AppTheme.NEON_DARK, 1f);
            canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.20f);
            if (fillW > 0f) {
                canvas.gradientH(x + 1, y + 1, fillW, Math.max(1, h - 2), AppTheme.NEON_DARK, AppTheme.NEON_LIGHT);
                canvas.glow(x + 1, y + 1, fillW, Math.max(1, h - 2), 0f, AppTheme.NEON, 0.36f);
            }
        }
    }

    // BOOT LOG TAIL — REBUILDS THE LINE LIST FROM LIVE BOOT STATE EVERY FRAME AND PAINTS AS MANY
    // TRAILING LINES AS FIT BETWEEN ITS TOP AND THE SLOT BOTTOM (THE LEGACY TAIL-SCROLL BEHAVIOR)
    private final class BootLog extends Element<BootLog> {

        private final List<StatusLine> completed = new ArrayList<>();
        private List<StatusLine> lines = List.of();
        private WaterMediaAPI trackedApi;
        private String trackedName = "";
        private String trackedStep = "";
        private String signature; // LAST INPUT SIGNATURE — THE LINE LIST IS ONLY REBUILT WHEN IT MOVES

        @Override
        protected void onUpdate() {
            final WaterMediaAPI api = WaterMedia.currentAPI();
            if (api != null) {
                // ARCHIVE THE PREVIOUS API AS A COMPLETED LINE WHEN THE ACTIVE ONE CHANGES
                if (this.trackedApi != null && this.trackedApi != api) {
                    this.completed.add(ok(apiMessage(this.trackedName, this.trackedStep)));
                }
                this.trackedApi = api;
                this.trackedName = api.name();
                final String step = clean(api.stepName());
                if (!step.isEmpty()) this.trackedStep = step;
            }

            // THE SPLASH REPAINTS EVERY FRAME; REBUILD THE LINE LIST (AND ITS STRINGS) ONLY WHEN A TRACKED
            // INPUT ACTUALLY CHANGED, KEEPING THE PREVIOUS LIST OTHERWISE
            final int audio = this.ctx.audioError ? 2 : this.ctx.audioReady ? 1 : 0;
            final String signature = api == null
                    ? audio + "|-|" + this.completed.size()
                    : audio + "|" + api.name() + "|" + this.trackedStep + "|" + api.step() + "/" + api.steps() + "|" + this.completed.size();
            if (signature.equals(this.signature)) return;
            this.signature = signature;

            final List<StatusLine> out = new ArrayList<>();
            out.add(ok("init " + LoadingScreen.this.engine + " context"));
            out.add(ok("render engine: " + LoadingScreen.this.engine));
            if (this.ctx.audioError) {
                out.add(fail("init audio output"));
            } else if (this.ctx.audioReady) {
                out.add(ok("init audio output"));
            } else {
                out.add(pending("init audio output"));
            }
            out.addAll(this.completed);
            if (api != null) {
                final String step = clean(api.stepName());
                final String message = apiMessage(api.name(), step.isEmpty() ? this.trackedStep : step);
                final boolean complete = api.steps() <= 0 || api.step() >= api.steps();
                out.add(complete ? ok(message) : pending(message));
            } else {
                out.add(pending("prepare API registry"));
            }
            this.lines = out;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.contentWidth = Math.min(920, Math.max(360, innerAvailWidth - 96));
            this.contentHeight = Math.min(this.lines.size(), 8) * STATUS_LINE_H;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int bottom = LoadingScreen.this.top() + LoadingScreen.this.measuredHeight();
            final int maxLines = Math.max(4, Math.min(8, (bottom - this.top - 12) / STATUS_LINE_H));
            int y = this.top;
            for (int i = Math.max(0, this.lines.size() - maxLines); i < this.lines.size(); i++) {
                final StatusLine line = this.lines.get(i);
                canvas.text(line.tag(), this.left, y, line.tagColor(), STATUS_SCALE, false);
                canvas.text(line.message(), this.left + canvas.textWidth(line.tag() + " ", STATUS_SCALE, false),
                        y, line.messageColor(), STATUS_SCALE, false);
                y += STATUS_LINE_H;
            }
        }
    }
}
