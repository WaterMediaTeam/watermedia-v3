package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
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

import java.util.Locale;

/**
 * Boot splash as a fully retained tree: a centered column with the glowing banner, the animated duck
 * (or the glowing pack icon as fallback), the spaced title and three captioned progress bars — the
 * overall module boot, the active module's steps and the download/extraction byte progress. Live boot
 * state is pulled from the {@link WaterMedia} metrics in the elements' {@code onUpdate} hooks every frame.
 */
public final class LoadingScreen extends Screen {

    private static final int BANNER_MAX_W = 1000;
    private static final int BANNER_MAX_H = 170;
    private static final int BANNER_UP_SHIFT = 18;
    private static final int ICON_SIZE = 72;
    private static final int DUCK_SIZE = 216;
    private static final int BAR_W = 380;
    private static final int BAR_H = 8;
    private static final int CAPTION_GAP = 8;
    private static final int CAPTION_H = 22;
    private static final int PROGRESS_ANIM_MS = 520;
    private static final int TITLE_SPACING = 3;
    private static final float TITLE_SCALE = AppTheme.TEXT_DISPLAY;
    private static final float CAPTION_SCALE = AppTheme.TEXT_SECTION;

    /**
     * Creates the boot splash.
     *
     * @param text the shared text renderer
     * @param ctx  the application context
     */
    public LoadingScreen(final TextRenderer text, final AppContext ctx) {
        super(text, ctx);
    }

    @Override
    protected Element<?> build() {
        return new CenterFrame().add(Parent.column()
                .width(MAX_PARENT)
                .add(new Banner().gravity(Gravity.CENTER).margin(new Spacing(0, 0, 24, 0)))
                .add(new Duck().gravity(Gravity.CENTER).margin(new Spacing(0, 0, 22, 0)))
                .add(new Text("LOADING WATERMEDIA...").scale(TITLE_SCALE).letterSpacing(TITLE_SPACING)
                        .color(AppTheme.NEON_LIGHT).gravity(Gravity.CENTER).margin(new Spacing(0, 0, 24, 0)))
                .add(new ModuleBar().gravity(Gravity.CENTER).margin(new Spacing(0, 0, 14, 0)))
                .add(new StepBar().gravity(Gravity.CENTER).margin(new Spacing(0, 0, 14, 0)))
                .add(new WorkBar().gravity(Gravity.CENTER)));
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

    // WORK FRACTION OF THE DEMANDING TASK IN FLIGHT — FULL WHEN NONE IS ACTIVE SO STEP RATIOS COMPLETE
    private static float workFrac() {
        final long total = WaterMedia.workTotal();
        return total > 0 ? clamp01((float) WaterMedia.work() / total) : 1f;
    }

    // HUMAN-READABLE BYTES FOR THE WORK CAPTION — ONE UNIT FOR BOTH VALUES PICKED FROM THE LARGEST;
    // A ZERO TOTAL (UNKNOWN SIZE) SHOWS ONLY THE PROCESSED AMOUNT
    private static String bytes(final long done, final long total) {
        final boolean mb = Math.max(done, total) >= 1L << 20;
        final double div = mb ? 1048576.0 : 1024.0;
        final String unit = mb ? " MB" : " KB";
        return total > 0
                ? String.format(Locale.ROOT, "%.1f/%.1f%s", done / div, total / div, unit)
                : String.format(Locale.ROOT, "%.1f%s", done / div, unit);
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

    // SHARED BAR CHASSIS — NEON TRACK + GRADIENT FILL WITH A CENTERED CAPTION BELOW; SUBCLASSES FEED
    // THE GOAL RATIO AND CAPTION EVERY FRAME, THE SHOWN VALUE OPTIONALLY CHASES IT WITH THE EASE RAMP
    private abstract static class Bar<T extends Bar<T>> extends Element<T> {

        protected String caption = "";
        protected String alert = ""; // RED SUFFIX (FAILED STEPS) — EMPTY WHEN CLEAN
        private float shown;
        private float from;
        private float target;
        private long startMs;

        // FEEDS THE GOAL RATIO; EASED CHASES WITH THE EASE-IN-OUT RAMP, OTHERWISE IT SNAPS (LIVE BYTES)
        protected final void goal(final float goal, final boolean eased) {
            if (!eased) {
                this.shown = this.target = clamp01(goal);
                return;
            }
            final long now = System.currentTimeMillis();
            if (Math.abs(goal - this.target) > 0.001f) {
                this.from = this.shown;
                this.target = clamp01(goal);
                this.startMs = now;
            }
            final double t = clamp01((now - this.startMs) / (double) PROGRESS_ANIM_MS);
            this.shown = t >= 1.0 ? this.target : clamp01((float) MathUtil.easeInOutQuad(this.from, this.target, t));
        }

        // DROPS THE RAMP STATE TO ZERO — USED WHEN THE TRACKED SCOPE CHANGES (NEW MODULE)
        protected final void rebase() {
            this.shown = 0f;
            this.from = 0f;
            this.target = 0f;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.contentWidth = Math.min(BAR_W, Math.max(180, innerAvailWidth - 96));
            this.contentHeight = BAR_H + CAPTION_GAP + CAPTION_H;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final float fillW = Math.max(0f, (w - 2) * this.shown);
            canvas.fill(x, y, w, BAR_H, AppTheme.alpha(AppTheme.BG_2, 235));
            canvas.stroke(x, y, w, BAR_H, AppTheme.NEON_DARK, 1f);
            canvas.glow(x, y, w, BAR_H, 0f, AppTheme.NEON, 0.20f);
            if (fillW > 0f) {
                canvas.gradientH(x + 1, y + 1, fillW, BAR_H - 2, AppTheme.NEON_DARK, AppTheme.NEON_LIGHT);
                canvas.glow(x + 1, y + 1, fillW, BAR_H - 2, 0f, AppTheme.NEON, 0.36f);
            }
            // CAPTION CENTERED UNDER THE TRACK, THE ALERT SUFFIX PAINTED IN RED RIGHT AFTER IT
            final int captionW = canvas.textWidth(this.caption, CAPTION_SCALE, false);
            final int alertW = this.alert.isEmpty() ? 0 : canvas.textWidth(this.alert, CAPTION_SCALE, false);
            final float tx = x + (w - captionW - alertW) * 0.5f;
            final int ty = y + BAR_H + CAPTION_GAP;
            canvas.text(this.caption, tx, ty, AppTheme.TEXT, CAPTION_SCALE, false);
            if (alertW > 0) canvas.text(this.alert, tx + captionW, ty, AppTheme.RED, CAPTION_SCALE, false);
        }
    }

    // BAR 1 (MAIN) — OVERALL BOOT: MODULES DONE PLUS THE ACTIVE MODULE'S STEP FRACTION (ITSELF REFINED
    // BY THE LIVE WORK BYTES); OUTSIDE THE MODULE WALK IT IS THE APP ITSELF LOADING ITS OWN PIECES
    private static final class ModuleBar extends Bar<ModuleBar> {

        @Override
        protected void onUpdate() {
            final int steps = WaterMedia.steps();
            final int step = WaterMedia.step();
            if (!this.ctx.backendsLoading) {
                this.goal(1f, true);
                this.caption = "Starting: WaterMediaApp";
            } else if (step > 0 && steps > 0) {
                final int taskSteps = WaterMedia.taskSteps();
                final float taskFrac = taskSteps > 0 ? clamp01((WaterMedia.taskStep() - 1 + workFrac()) / taskSteps) : 0f;
                this.goal(clamp01((step - 1 + taskFrac) / steps), true);
                this.caption = "Starting: " + clean(WaterMedia.stepName()) + " (" + step + "/" + steps + ")";
            } else {
                this.goal(0f, true);
                this.caption = "Starting: WaterMediaApp";
            }
            final int failed = WaterMedia.failures().size();
            this.alert = failed > 0 ? " [" + failed + " step(s) failed]" : "";
        }
    }

    // BAR 2 — ACTIVE MODULE STEPS: VISIBLE ONLY WHILE THE BOOT PUBLISHES STEPS, GONE ONCE THE MODULES
    // FINISH AND ONLY THE APP KEEPS LOADING; THE RAMP REBASES WHEN THE ACTIVE MODULE CHANGES
    private static final class StepBar extends Bar<StepBar> {

        private int tracked; // LAST MODULE INDEX SEEN — A CHANGE DROPS THE RAMP TO ZERO

        @Override
        protected void onUpdate() {
            final int taskSteps = WaterMedia.taskSteps();
            final int taskStep = WaterMedia.taskStep();
            this.visible = this.ctx.backendsLoading && taskSteps > 0 && taskStep > 0;
            if (!this.visible) return;
            final int module = WaterMedia.step();
            if (module != this.tracked) {
                this.tracked = module;
                this.rebase();
            }
            this.goal(clamp01((taskStep - 1 + workFrac()) / taskSteps), true);
            final String step = clean(WaterMedia.taskName());
            this.caption = (step.isEmpty() ? "Loading" : "Loading: " + step) + " (" + taskStep + "/" + taskSteps + ")";
        }
    }

    // BAR 3 — DEMANDING WORK (DOWNLOAD/EXTRACTION BYTES): ONLY VISIBLE WHILE ONE IS IN FLIGHT; AN
    // UNKNOWN TOTAL KEEPS THE TRACK EMPTY AND THE CAPTION COUNTING RAW BYTES
    private static final class WorkBar extends Bar<WorkBar> {

        @Override
        protected void onUpdate() {
            final long total = WaterMedia.workTotal();
            this.visible = this.ctx.backendsLoading && (total > 0 || !clean(WaterMedia.workName()).isEmpty());
            if (!this.visible) return;
            final long done = WaterMedia.work();
            this.goal(total > 0 ? clamp01((float) done / total) : 0f, false);
            this.caption = (WaterMedia.workRemote() ? "Downloading: " : "Extracting: ") + bytes(done, total);
        }
    }
}
