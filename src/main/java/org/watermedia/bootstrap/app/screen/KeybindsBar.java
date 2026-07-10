package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.KeyChip;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.StatusSquare;
import org.watermedia.bootstrap.app.element.StatusText;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The footer bar as a retained view — the replacement for the imperative chrome footer: the gradient
 * band with its top hairline, the active screen's keybind chips (icon key-caps plus labels) on the
 * left, and the backend status strip (FFMPEG / OPENGL / VULKAN) on the right. The bar pulls the
 * keybinds from the supplier given at construction on every {@code update()} pass and appends the
 * global {@code C: CRT ON/OFF} chip itself; an empty list (boot) renders just the chrome.
 */
public final class KeybindsBar extends Group<KeybindsBar> {

    /** Fixed footer height in pixels. */
    public static final int H = 44;

    private Supplier<List<Keybind>> source;
    private final Parent row;
    private final Parent binds;
    private List<Keybind> applied;

    public KeybindsBar(final Supplier<List<Keybind>> keybinds) {
        this.source = keybinds;
        this.width = MAX_PARENT;
        this.height = H;

        // LEFT CHIP BAND SITS AT footerY+9 (THE IMPERATIVE bandTop = y+10-1); THE STATUS STRIP AT
        // footerY+10 — HENCE THE ROW PADDING TOP 9 PLUS A 1px TOP MARGIN ON THE STATUS GROUP
        this.binds = Parent.row().spacing(0);
        final StatusText ffmpeg = new StatusText("FFMPEG")
                .status(() -> {
                    if (FFMediaPlayer.loadError()) return StatusSquare.Status.ERROR;
                    if (FFMediaPlayer.loaded()) return StatusSquare.Status.OK;
                    return this.ctx != null && this.ctx.backendsLoading ? StatusSquare.Status.PENDING : StatusSquare.Status.ERROR;
                });
        // RENDER ENGINES REFLECT THE ACTIVE BACKEND: OK = ACTIVE, OFF = AVAILABLE BUT INACTIVE (DIM),
        // ERROR+STRIKE = UNSUPPORTED. OPENGL IS ALWAYS AVAILABLE; VULKAN DEPENDS ON THE LOADER/DEVICE.
        final StatusText opengl = new StatusText("OpenGL")
                .status(() -> RenderSystem.vulkanActive() ? StatusSquare.Status.OFF : StatusSquare.Status.OK);
        final StatusText vulkan = new StatusText("Vulkan")
                .status(() -> {
                    if (!RenderSystem.vulkanAvailable()) return StatusSquare.Status.ERROR;
                    return RenderSystem.vulkanActive() ? StatusSquare.Status.OK : StatusSquare.Status.OFF;
                })
                .strike(() -> !RenderSystem.vulkanAvailable());
        final Parent status = Parent.row().spacing(0)
                .margin(new Spacing(1, 0, 0, 0))
                .add(ffmpeg)
                .add(opengl)
                .add(vulkan);
        this.row = Parent.row()
                .size(MAX_PARENT, MAX_PARENT)
                .padding(new Spacing(9, 18, 13, 14))
                .add(this.binds)
                .add(new Box().weight(1f))
                .add(status);
        this.add(this.row);
    }

    /** Pins a fixed keybind list, replacing the constructor supplier as the source. */
    public KeybindsBar keybinds(final List<Keybind> list) {
        final List<Keybind> copy = list == null ? List.of() : List.copyOf(list);
        this.source = () -> copy;
        this.rebuild(copy);
        return this;
    }

    @Override
    protected void onUpdate() {
        this.rebuild(this.source == null ? null : this.source.get());
    }

    private void rebuild(final List<Keybind> base) {
        final List<Keybind> list = new ArrayList<>(base == null ? List.of() : base);
        // THE BAR OWNS THE GLOBAL CRT CHIP — SCREENS NEVER DECLARE IT
        list.add(new Keybind("C", "CRT " + (this.ctx != null && this.ctx.crt ? "ON" : "OFF")));
        if (list.equals(this.applied)) return;
        this.applied = list;
        this.binds.clear();
        for (final Keybind bind: list) {
            if (!bind.key().isEmpty()) {
                this.binds.add(new KeyChip(bind.key())
                        .icon(KeyChip.keyIcon(bind.key()))
                        .margin(new Spacing(0, 6, 0, 0)));
            }
            this.binds.add(new Text(bind.label())
                    .scale(AppTheme.TEXT_BODY)
                    .color(AppTheme.TEXT_FAINT)
                    .height(22)
                    .margin(new Spacing(0, 18, 0, 0)));
        }
        this.invalidate();
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.row.measure(innerAvailWidth, innerAvailHeight);
        this.contentWidth = innerAvailWidth;
        this.contentHeight = innerAvailHeight;
    }

    @Override
    protected void onLayout() {
        this.row.layout(this.innerLeft(), this.innerTop());
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        // GRADIENT BAND WITH ITS TOP HAIRLINE — THE PLAYER OWNS ITS OWN SEEKBAR, SO NO PROGRESS TRACK HERE
        canvas.gradientV(x, y, w, H,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 1f,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 1f);
        canvas.lineH(x, y, w, AppTheme.STROKE_BRIGHT, 1f);
        super.onDraw(canvas);
    }
}
