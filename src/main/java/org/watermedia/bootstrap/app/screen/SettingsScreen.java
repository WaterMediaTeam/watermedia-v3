package org.watermedia.bootstrap.app.screen;

import me.srrapero720.waterconfig.ConfigGroup;
import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.IConfigField;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Settings screen built from app-runtime settings plus WaterMediaConfig metadata.
 */
public final class SettingsScreen extends Screen {

    private static final int SIDEBAR_W = 248;
    private static final int ROW_H = 64;
    private static final int BUTTON_H = 34;
    private static final int CONTROL_W = 320;
    private static final int CONTROL_H = 26;
    private static final int SPEC_H = 56;
    private static final int SPEC_STEP = 64;
    private static final int SECTION_H = 46;
    private static final int SECTION_STEP = 54;

    private final Consumer<HomeScreen.Action> navigator;
    private final List<SettingSpec> specs = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();

    private int activeSpecIndex;
    private int activeSectionIndex;
    private int selectedRow;
    private int rowScroll;

    private Dimension saveBounds = Dimension.ZERO;
    private Dimension resetBounds = Dimension.ZERO;
    private boolean editing;
    private Setting editingSetting;
    private String editBuffer = "";
    private String statusText = "READY";
    private Color statusColor = AppTheme.TEXT_FAINT;

    public SettingsScreen(final TextRenderer text, final AppContext ctx,
                          final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    public void onEnter() {
        this.rebuildSpecs();
        this.editing = false;
        this.editingSetting = null;
        this.editBuffer = "";
        this.clampSelection();
    }

    private void rebuildSpecs() {
        this.specs.clear();
        this.specs.add(this.buildAppSpec());
        this.specs.add(this.buildInstanceSpec());
        if (this.activeSpecIndex >= this.specs.size()) this.activeSpecIndex = Math.max(0, this.specs.size() - 1);
        this.clampSelection();
    }

    private SettingSpec buildAppSpec() {
        final SettingSpec spec = new SettingSpec("App", "runtime shell", false, null);

        final SettingSection general = new SettingSection("General", "Bootstrap UI behavior");
        general.settings.add(new RuntimeSetting("CRT overlay", "app.general.crtOverlay", "BOOL",
                "Global scanline/glow treatment. Keyboard shortcut: C.",
                () -> AppChrome.crtEnabled() ? "ON" : "OFF",
                () -> AppChrome.crtEnabled(),
                value -> {
                    if (value != AppChrome.crtEnabled()) AppChrome.toggleCrt();
                },
                true));
        general.settings.add(new RuntimeSetting("Selection sound", "app.general.selectionSound", "BOOL",
                "Enable the UI tick used by menu and settings navigation.",
                () -> this.ctx.selectionSoundEnabled ? "ON" : "OFF",
                () -> this.ctx.selectionSoundEnabled,
                value -> this.ctx.selectionSoundEnabled = value,
                true));
        general.settings.add(new ReadOnlySetting("Audio device", "app.general.audioDevice", "STATE",
                "OpenAL output used by the bootstrap shell.",
                () -> this.ctx.audioError ? "ERROR" : this.ctx.audioReady ? "READY" : "OFFLINE"));
        spec.sections.add(general);

        final SettingSection renderer = new SettingSection("Renderer", "Current bootstrap render backend");
        renderer.settings.add(new ReadOnlySetting("Render backend", "app.renderer.backend", "STATE",
                "The refactored app renderer is using the OpenGL backend.",
                () -> "OPENGL"));
        renderer.settings.add(new ReadOnlySetting("Window", "app.renderer.window", "SIZE",
                "Current framebuffer size.",
                () -> this.ctx.windowWidth + "x" + this.ctx.windowHeight));
        renderer.settings.add(new ReadOnlySetting("FFmpeg", "app.renderer.ffmpeg", "STATE",
                "Media playback backend availability.",
                () -> FFMediaPlayer.loaded() ? "LOADED" : FFMediaPlayer.loadError() ? "ERROR" : "UNLOADED"));
        spec.sections.add(renderer);

        final SettingSection cache = new SettingSection("Cache", "App cache and diagnostics helpers");
        cache.settings.add(new ReadOnlySetting("Cache path", "app.cache.path", "PATH",
                "WaterMedia temporary cache directory.",
                () -> safePath(() -> WaterMedia.tmp().resolve("cache"))));
        cache.settings.add(new ReadOnlySetting("Cache size", "app.cache.size", "STATE",
                "Use the main menu cleanup flow to remove these files.",
                this::cacheSizeLabel));
        cache.settings.add(new ReadOnlySetting("Upload logs", "app.diagnostics.uploadLogs", "STATE",
                "Minecraft context is required for log collection.",
                () -> AppContext.IN_MODS ? "AVAILABLE" : "MINECRAFT ONLY"));
        spec.sections.add(cache);

        return spec;
    }

    private SettingSpec buildInstanceSpec() {
        final ConfigSpec configSpec = this.waterMediaConfigSpec();
        final SettingSpec spec = new SettingSpec("Instance config", "watermedia.toml", true, configSpec);
        if (configSpec == null) {
            final SettingSection unavailable = new SettingSection("Unavailable", "WaterConfig did not expose the active spec");
            unavailable.settings.add(new ReadOnlySetting("WaterMediaConfig", "watermedia", "STATE",
                    "The app could not read the registered WaterConfig spec.",
                    () -> WaterConfig.isRegistered(WaterMedia.ID) ? "REGISTERED" : "NOT REGISTERED"));
            spec.sections.add(unavailable);
            return spec;
        }

        this.collectConfigSections(configSpec, "", spec);
        if (spec.sections.isEmpty()) {
            final SettingSection empty = new SettingSection("Empty", "No fields were discovered");
            empty.settings.add(new ReadOnlySetting("WaterMediaConfig", WaterMedia.ID, "STATE",
                    "No configurable fields were discovered in the active spec.",
                    () -> "EMPTY"));
            spec.sections.add(empty);
        }
        return spec;
    }

    @SuppressWarnings("unchecked")
    private ConfigSpec waterMediaConfigSpec() {
        if (!WaterConfig.isRegistered(WaterMedia.ID)) return null;
        try {
            final Class<?> registry = Class.forName("me.srrapero720.waterconfig.WaterConfigRegistry");
            final Field specsField = registry.getDeclaredField("SPECS");
            specsField.setAccessible(true);
            final Map<String, ConfigSpec> registered = (Map<String, ConfigSpec>) specsField.get(null);
            return registered.get(WaterMedia.ID);
        } catch (final ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private void collectConfigSections(final ConfigGroup group, final String parentPath, final SettingSpec spec) {
        final String groupName = group.name();
        final boolean root = group instanceof ConfigSpec;
        final String groupPath = root ? "" : parentPath.isEmpty() ? groupName : parentPath + "." + groupName;
        final SettingSection section = root ? null : new SettingSection(titleCase(groupName), groupPath);
        final Collection<IConfigField<?, ?>> fields = group.getFields();

        for (final IConfigField<?, ?> field: fields) {
            if (field instanceof ConfigGroup child) {
                continue;
            }
            if (section != null) {
                final String path = groupPath.isEmpty() ? field.name() : groupPath + "." + field.name();
                section.settings.add(new ConfigSetting(field, path));
            }
        }

        if (section != null && !section.settings.isEmpty()) {
            spec.sections.add(section);
        }

        for (final IConfigField<?, ?> field: fields) {
            if (field instanceof ConfigGroup child) {
                this.collectConfigSections(child, groupPath, spec);
            }
        }
    }

    @Override
    public void render(final int windowW, final int windowH) {
        final SettingSpec spec = this.activeSpec();
        final SettingSection section = this.activeSection();
        final boolean dirty = spec != null && spec.configSpec != null && spec.configSpec.isDirty();
        AppChrome.screen(this.text, this.ctx, windowW, windowH,
                "Settings", section == null ? "" : section.name.toLowerCase(Locale.ROOT), dirty ? "DIRTY" : "READY");

        this.hits.clear();
        final int x = 22;
        final int y = AppChrome.contentTop() + 10;
        final int bottom = AppChrome.contentBottom(windowH);
        final int h = Math.max(160, bottom - y);
        final int contentX = x + SIDEBAR_W + 18;
        final int contentW = Math.max(320, windowW - contentX - x);

        RenderSystem.setupOrtho(windowW, windowH);
        this.renderSidebar(x, y, SIDEBAR_W, h, spec);
        this.renderSettingsPane(contentX, y, contentW, h, spec, section);
        RenderSystem.restoreProjection();
    }

    private void renderSidebar(final int x, final int y, final int w, final int h, final SettingSpec active) {
        AppChrome.panel(x, y, w, h, true);
        this.text.renderBold("SPECS", x + 18, y + 18, AppTheme.NEON, AppTheme.TEXT_BODY);

        int cursorY = y + 46;
        for (int i = 0; i < this.specs.size(); i++) {
            final SettingSpec spec = this.specs.get(i);
            final Dimension bounds = new Dimension(x + 14, cursorY, w - 28, SPEC_H);
            this.renderSidebarButton(bounds, spec.title, spec.subtitle, i == this.activeSpecIndex, i == 0 ? AppTheme.CYAN : AppTheme.AMBER);
            this.hits.add(new Hit(bounds, HitType.SPEC, i));
            cursorY += SPEC_STEP;
        }

        cursorY += 8;
        RenderSystem.lineH(x + 14, cursorY, w - 28, AppTheme.STROKE_BRIGHT, 1f);
        cursorY += 18;
        this.text.renderBold("SECTIONS", x + 18, cursorY, AppTheme.NEON, AppTheme.TEXT_BODY);
        cursorY += 28;

        if (active == null) return;
        for (int i = 0; i < active.sections.size(); i++) {
            final SettingSection section = active.sections.get(i);
            final Dimension bounds = new Dimension(x + 14, cursorY, w - 28, SECTION_H);
            final boolean selected = i == this.activeSectionIndex;
            final Color accent = selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
            if (selected) {
                RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, AppTheme.NEON, 0.20f);
                RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(), AppTheme.alpha(AppTheme.NEON_DARK, 42));
            }
            RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), selected ? AppTheme.NEON : AppTheme.STROKE, 1f);
            this.renderSelectionCube(bounds.x() + 10, bounds.y() + 15, selected);
            this.text.renderBold(this.text.truncateToWidth(section.name.toUpperCase(Locale.ROOT), bounds.width() - 46, AppTheme.TEXT_BODY, java.awt.Font.BOLD),
                    bounds.x() + 28, this.centerBoldTextY(bounds.y(), bounds.height(), AppTheme.TEXT_BODY), accent, AppTheme.TEXT_BODY);
            this.hits.add(new Hit(bounds, HitType.SECTION, i));
            cursorY += SECTION_STEP;
            if (cursorY + SECTION_STEP > y + h - 12) break;
        }
    }

    private void renderSidebarButton(final Dimension b, final String title, final String subtitle,
                                     final boolean selected, final Color accent) {
        if (selected) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, accent, 0.24f);
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(),
                selected ? AppTheme.alpha(AppTheme.BG_3, 230) : AppTheme.alpha(AppTheme.BG_2, 180));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), selected ? accent : AppTheme.STROKE_BRIGHT, selected ? 1.5f : 1f);
        this.renderSelectionCube(b.x() + 11, b.y() + 20, selected);
        this.text.renderBold(title.toUpperCase(Locale.ROOT), b.x() + 30, b.y() + 9, selected ? accent : AppTheme.TEXT_SOFT, AppTheme.TEXT_BUTTON);
        this.text.render(this.text.truncateToWidth(subtitle.toUpperCase(Locale.ROOT), b.width() - 42, AppTheme.TEXT_SUBTITLE),
                b.x() + 30, b.y() + 33, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
    }

    private void renderSelectionCube(final int x, final int y, final boolean selected) {
        RenderSystem.rect(x, y, 8, 8, selected ? AppTheme.AMBER : AppTheme.TEXT_FAINT, 1f);
        if (selected) {
            RenderSystem.fill(x + 1, y + 1, 6, 6, AppTheme.AMBER);
            RenderSystem.glowRect(x, y, 8, 8, 0f, AppTheme.AMBER, 0.35f);
        }
    }

    private void renderSettingsPane(final int x, final int y, final int w, final int h,
                                    final SettingSpec spec, final SettingSection section) {
        AppChrome.panel(x, y, w, h, true);
        if (spec == null || section == null) return;

        final int headerH = 74;
        final int titleX = x + 20;
        this.text.renderBold(section.name.toUpperCase(Locale.ROOT), titleX, y + 16, AppTheme.NEON_LIGHT, AppTheme.TEXT_SECTION);
        this.text.render(this.text.truncateToWidth(section.detail, Math.max(80, w - 360)),
                titleX, y + 44, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);

        this.resetBounds = new Dimension(x + w - 210, y + 18, 86, BUTTON_H);
        this.saveBounds = new Dimension(x + w - 112, y + 18, 92, BUTTON_H);
        this.renderActionButton(this.resetBounds, "RESET", AppTheme.AMBER, this.resetBounds.contains(this.ctx.mouseX, this.ctx.mouseY));
        this.renderActionButton(this.saveBounds, spec.persistent ? "SAVE" : "APPLY", AppTheme.CYAN, this.saveBounds.contains(this.ctx.mouseX, this.ctx.mouseY));
        this.hits.add(new Hit(this.resetBounds, HitType.RESET, -1));
        this.hits.add(new Hit(this.saveBounds, HitType.SAVE, -1));

        RenderSystem.lineH(x, y + headerH, w, AppTheme.STROKE_BRIGHT, 1f);

        final int rowsY = y + headerH + 10;
        final int rowsH = Math.max(ROW_H, h - headerH - 20);
        this.ensureSelectedVisible(rowsH);
        RenderSystem.clip(x + 12, rowsY, w - 24, rowsH, this.ctx.windowHeight);
        final int visibleRows = Math.max(1, rowsH / ROW_H);
        for (int i = 0; i < visibleRows && i + this.rowScroll < section.settings.size(); i++) {
            final int rowIndex = i + this.rowScroll;
            final Setting setting = section.settings.get(rowIndex);
            final Dimension row = new Dimension(x + 20, rowsY + i * ROW_H, w - 40, ROW_H - 8);
            this.renderSettingRow(setting, row, rowIndex == this.selectedRow);
            this.hits.add(new Hit(row, HitType.ROW, rowIndex));
        }
        RenderSystem.clearClip();

        if (section.settings.size() > visibleRows) {
            this.renderScrollBar(x + w - 10, rowsY, rowsH, section.settings.size(), visibleRows);
        }
    }

    private void renderStatusStrip(final int x, final int y, final int w,
                                   final SettingSpec spec, final SettingSection section) {
        final int h = 26;
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, 180));
        RenderSystem.rect(x, y, w, h, AppTheme.STROKE, 1f);
        AppChrome.statusPip(x + 9, y + 8, 10, this.statusColor, true);
        this.text.render(this.text.truncateToWidth(this.statusText, Math.max(60, w / 2)),
                x + 28, this.centerTextY(y, h, AppTheme.TEXT_SUBTITLE), this.statusColor, AppTheme.TEXT_SUBTITLE);
        final String count = section.settings.size() + " FIELDS";
        final String file = spec.persistent && spec.configSpec != null ? spec.configSpec.path().getFileName().toString() : "RUNTIME";
        final String right = count + " / " + file;
        this.text.render(right, x + w - this.text.width(right, AppTheme.TEXT_SUBTITLE) - 10,
                this.centerTextY(y, h, AppTheme.TEXT_SUBTITLE), AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
    }

    private void renderActionButton(final Dimension b, final String label, final Color accent, final boolean hover) {
        if (hover) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, accent, 0.24f);
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(), hover ? AppTheme.alpha(AppTheme.BG_3, 230) : AppTheme.alpha(AppTheme.BG_2, 210));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), hover ? accent : AppTheme.STROKE_BRIGHT, 1f);
        final int iconSize = 13;
        PixelIcon.draw("check", b.x() + 10, b.y() + 10, iconSize, accent);
        this.text.renderBold(label, b.x() + 30, this.centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_BUTTON), hover ? accent : AppTheme.TEXT_SOFT, AppTheme.TEXT_BUTTON);
    }

    private void renderSettingRow(final Setting setting, final Dimension b, final boolean selected) {
        final Color accent = setting.accent();
        if (selected) {
            RenderSystem.fill(b.x(), b.y(), b.width(), b.height(), AppTheme.alpha(AppTheme.NEON_DARK, 36));
            RenderSystem.lineV(b.x(), b.y() + 6, b.height() - 12, accent, 3f);
            RenderSystem.glowRect(b.x(), b.y() + 4, b.width(), b.height() - 8, 0f, accent, 0.14f);
        }

        for (int dx = b.x(); dx < b.right(); dx += 8) {
            RenderSystem.fill(dx, b.bottom() - 1, 4, 1, AppTheme.alpha(AppTheme.STROKE_BRIGHT, 76));
        }

        final Dimension control = this.controlBounds(b, setting);
        final int labelMaxW = Math.max(80, control.x() - b.x() - 30);
        final boolean detailed = selected && !setting.note.isBlank();
        final int labelY = detailed ? b.y() + 10 : this.centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_BODY);
        this.text.renderBold(this.text.truncateToWidth(setting.label.toUpperCase(Locale.ROOT), labelMaxW, AppTheme.TEXT_BODY, java.awt.Font.BOLD),
                b.x() + 18, labelY, selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);

        if (detailed) {
            this.text.render(this.text.truncateToWidth(setting.note, labelMaxW, AppTheme.TEXT_SUBTITLE),
                    b.x() + 18, b.y() + 35, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        }

        this.renderSettingControl(setting, control, selected);
    }

    private Dimension controlBounds(final Dimension row, final Setting setting) {
        final int maxW = Math.max(120, row.width() - 240);
        final int w = Math.min(setting.controlWidth(), maxW);
        return new Dimension(row.right() - w - 14, row.y() + Math.max(0, (row.height() - CONTROL_H) / 2), w, CONTROL_H);
    }

    private void renderSettingControl(final Setting setting, final Dimension b, final boolean selected) {
        if (!setting.mutable) {
            this.renderReadOnlyControl(setting, b);
            return;
        }
        if (setting.isBooleanControl()) {
            this.renderSwitchControl(b, setting.booleanValue(), selected);
        } else if (setting.isSeekbarControl()) {
            this.renderSeekbarControl(setting, b, selected);
        } else if (setting.isEnumControl()) {
            this.renderEnumControl(setting, b, selected);
        } else if (setting.isSpinnerControl()) {
            this.renderSpinnerControl(setting, b, selected);
        } else {
            this.renderTextFieldControl(setting, b, selected);
        }
    }

    private void renderReadOnlyControl(final Setting setting, final Dimension b) {
        final String value = setting.valueLabel();
        final int w = Math.min(b.width(), Math.max(112, this.text.width(value, AppTheme.TEXT_BODY) + 30));
        final int x = b.right() - w;
        final Color color = statusColor(value);
        AppChrome.statusPip(x + 9, b.y() + 8, 10, color, true);
        this.text.render(this.text.truncateToWidth(value.toUpperCase(Locale.ROOT), w - 28),
                x + 27, this.centerTextY(b.y(), b.height(), AppTheme.TEXT_BODY), color, AppTheme.TEXT_BODY);
    }

    private void renderSwitchControl(final Dimension b, final boolean on, final boolean selected) {
        final int w = 48;
        final int h = 22;
        final int x = b.right() - w;
        final int y = b.y() + 2;
        final Color border = selected || on ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT;
        if (on || selected) RenderSystem.glowRect(x, y, w, h, 0f, on ? AppTheme.AMBER : AppTheme.NEON, on ? 0.32f : 0.18f);
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, 210));
        RenderSystem.rect(x, y, w, h, border, 1f);
        final int knob = h - 4;
        final int knobX = on ? x + w - knob - 2 : x + 2;
        RenderSystem.fill(knobX, y + 2, knob, knob, on ? AppTheme.AMBER : AppTheme.NEON_LIGHT);
    }

    private void renderEnumControl(final Setting setting, final Dimension b, final boolean selected) {
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(), AppTheme.alpha(AppTheme.BG_1, 210));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, 1f);
        RenderSystem.lineV(b.x() + 26, b.y(), b.height(), AppTheme.STROKE, 1f);
        RenderSystem.lineV(b.right() - 26, b.y(), b.height(), AppTheme.STROKE, 1f);
        PixelIcon.draw("arrow-left", b.x() + 8, b.y() + 8, 10, AppTheme.TEXT_FAINT);
        PixelIcon.draw("arrow-right", b.right() - 18, b.y() + 8, 10, AppTheme.TEXT_FAINT);
        final String value = setting.valueLabel().toUpperCase(Locale.ROOT);
        this.text.renderBold(this.text.truncateToWidth(value, b.width() - 68),
                b.x() + 36, this.centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_BODY), selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
    }

    private void renderSpinnerControl(final Setting setting, final Dimension b, final boolean selected) {
        final int stepW = 28;
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(), AppTheme.alpha(AppTheme.BG_1, 210));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), selected ? AppTheme.AMBER : AppTheme.STROKE_BRIGHT, 1f);
        RenderSystem.lineV(b.x() + stepW, b.y(), b.height(), AppTheme.STROKE, 1f);
        RenderSystem.lineV(b.right() - stepW, b.y(), b.height(), AppTheme.STROKE, 1f);
        this.text.render("-", b.x() + 11, this.centerTextY(b.y(), b.height(), AppTheme.TEXT_BODY), AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        this.text.render("+", b.right() - 19, this.centerTextY(b.y(), b.height(), AppTheme.TEXT_BODY), AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        final String suffix = setting.suffix();
        final String value = setting.valueLabel();
        final int suffixW = suffix.isBlank() ? 0 : this.text.width(suffix, AppTheme.TEXT_SUBTITLE) + 16;
        if (!suffix.isBlank()) {
            RenderSystem.fill(b.right() - stepW - suffixW, b.y(), suffixW, b.height(), AppTheme.alpha(AppTheme.BG_2, 210));
            RenderSystem.lineV(b.right() - stepW - suffixW, b.y(), b.height(), AppTheme.STROKE, 1f);
            this.text.render(suffix.toUpperCase(Locale.ROOT), b.right() - stepW - suffixW + 8,
                    this.centerTextY(b.y(), b.height(), AppTheme.TEXT_SUBTITLE), AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        }
        this.text.render(this.text.truncateToWidth(value, b.width() - stepW * 2 - suffixW - 20),
                b.x() + stepW + 10, this.centerTextY(b.y(), b.height(), AppTheme.TEXT_BODY),
                selected ? AppTheme.AMBER : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
    }

    private void renderSeekbarControl(final Setting setting, final Dimension b, final boolean selected) {
        final String value = setting.valueLabel();
        final String suffix = setting.suffix();
        final String display = suffix.isBlank() ? value : value + " " + suffix;
        final int valueW = Math.max(58, this.text.width(display, AppTheme.TEXT_SUBTITLE) + 6);
        final int trackX = b.x();
        final int trackW = Math.max(80, b.width() - valueW - 14);
        final int trackY = b.y() + 10;
        final float pct = setting.rangePercent();

        RenderSystem.fill(trackX, trackY, trackW, 6, AppTheme.BG_3);
        RenderSystem.fillGradientH(trackX, trackY, trackW * pct, 6,
                AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
        RenderSystem.glowRect(trackX, trackY, trackW * pct, 6, 0f, AppTheme.NEON, selected ? 0.34f : 0.18f);
        final int knobX = trackX + Math.round(trackW * pct);
        RenderSystem.fill(knobX - 2, trackY - 4, 4, 14, AppTheme.AMBER);
        this.text.render(display, b.right() - valueW, this.centerTextY(b.y(), b.height(), AppTheme.TEXT_SUBTITLE),
                selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE);
    }

    private void renderTextFieldControl(final Setting setting, final Dimension b, final boolean selected) {
        final boolean active = this.editing && this.editingSetting == setting;
        final String value = active ? this.editBuffer : setting.valueLabel();
        final String suffix = setting.suffix();
        final int suffixW = suffix.isBlank() ? 0 : Math.max(36, this.text.width(suffix, AppTheme.TEXT_SUBTITLE) + 16);
        if (active || selected) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, AppTheme.NEON, active ? 0.28f : 0.14f);
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(), AppTheme.alpha(AppTheme.BG_1, 220));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), active ? AppTheme.NEON_LIGHT : selected ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1f);
        if (!suffix.isBlank()) {
            RenderSystem.fill(b.right() - suffixW, b.y(), suffixW, b.height(), AppTheme.alpha(AppTheme.BG_2, 210));
            RenderSystem.lineV(b.right() - suffixW, b.y(), b.height(), AppTheme.STROKE, 1f);
            this.text.render(suffix.toUpperCase(Locale.ROOT), b.right() - suffixW + 8,
                    this.centerTextY(b.y(), b.height(), AppTheme.TEXT_SUBTITLE), AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        }
        final int textMaxW = b.width() - suffixW - 22;
        final String visible = this.text.truncateToWidth(value, textMaxW, AppTheme.TEXT_BODY);
        this.text.render(visible, b.x() + 10, this.centerTextY(b.y(), b.height(), AppTheme.TEXT_BODY),
                value.isBlank() ? AppTheme.TEXT_FAINT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        if (active && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
            final int cursorX = Math.min(b.right() - suffixW - 8, b.x() + 10 + this.text.width(visible, AppTheme.TEXT_BODY) + 2);
            RenderSystem.fill(cursorX, b.y() + 6, 2, b.height() - 12, AppTheme.NEON_LIGHT);
        }
    }

    private static Color statusColor(final String value) {
        final String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (normalized.contains("READY") || normalized.contains("LOADED") || normalized.contains("AVAILABLE")) return AppTheme.GREEN;
        if (normalized.contains("ERROR") || normalized.contains("UNLOADED")) return AppTheme.RED;
        if (normalized.contains("ONLY") || normalized.contains("OFFLINE")) return AppTheme.AMBER;
        return AppTheme.TEXT_FAINT;
    }

    private void renderScrollBar(final int x, final int y, final int h, final int total, final int visible) {
        RenderSystem.fill(x, y, 4, h, AppTheme.alpha(AppTheme.BG_3, 190));
        final int thumbH = Math.max(24, Math.round(h * (visible / (float) total)));
        final int maxScroll = Math.max(1, total - visible);
        final int thumbY = y + Math.round((h - thumbH) * (this.rowScroll / (float) maxScroll));
        RenderSystem.fill(x, thumbY, 4, thumbH, AppTheme.NEON);
        RenderSystem.glowRect(x, thumbY, 4, thumbH, 0f, AppTheme.NEON, 0.24f);
    }

    @Override
    protected void onKeyRelease(final int key) {
        if (this.editing) {
            this.handleEditingKey(key);
            return;
        }

        switch (key) {
            case GLFW_KEY_UP -> this.moveRow(-1);
            case GLFW_KEY_DOWN -> this.moveRow(1);
            case GLFW_KEY_PAGE_UP -> this.moveSection(-1);
            case GLFW_KEY_PAGE_DOWN -> this.moveSection(1);
            case GLFW_KEY_TAB -> this.switchSpec(this.activeSpecIndex + 1);
            case GLFW_KEY_LEFT -> this.adjustSelected(-1);
            case GLFW_KEY_RIGHT -> this.adjustSelected(1);
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.activateSelected();
            case GLFW_KEY_R -> this.resetActiveSection();
            case GLFW_KEY_S -> this.saveActiveSpec();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
        }
    }

    private void handleEditingKey(final int key) {
        switch (key) {
            case GLFW_KEY_ESCAPE -> this.cancelEdit();
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.commitEdit();
            case GLFW_KEY_BACKSPACE -> {
                if (!this.editBuffer.isEmpty()) {
                    this.editBuffer = this.editBuffer.substring(0, this.editBuffer.length() - 1);
                    this.ctx.requestRender();
                }
            }
        }
    }

    @Override
    public void handleChar(final int codepoint) {
        if (!this.editing || codepoint < 32 || codepoint == 127) return;
        this.editBuffer += new String(Character.toChars(codepoint));
        this.ctx.requestRender();
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        if (this.editing) return;
        for (final Hit hit: this.hits) {
            if (hit.type == HitType.ROW && hit.bounds.contains(mx, my)) {
                if (this.selectedRow != hit.index) {
                    this.selectedRow = hit.index;
                    this.ensureSelectedVisible(this.visibleRowsHeight());
                    this.ctx.playSelectionSound();
                }
                return;
            }
        }
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        for (final Hit hit: this.hits) {
            if (!hit.bounds.contains(mx, my)) continue;
            switch (hit.type) {
                case SPEC -> this.switchSpec(hit.index);
                case SECTION -> this.switchSection(hit.index);
                case ROW -> {
                    this.selectedRow = hit.index;
                    final Setting setting = this.selectedSetting();
                    if (setting != null && setting.mutable) {
                        final Dimension control = this.controlBounds(hit.bounds, setting);
                        if (control.contains(mx, my)) {
                            this.activateControl(setting, control, mx);
                        } else {
                            this.activateSelected();
                        }
                    }
                }
                case SAVE -> this.saveActiveSpec();
                case RESET -> this.resetActiveSection();
            }
            this.ctx.playSelectionSound();
            return;
        }
    }

    @Override
    public void handleScroll(final double yOffset) {
        final SettingSection section = this.activeSection();
        if (section == null) return;
        final int visibleRows = Math.max(1, this.visibleRowsHeight() / ROW_H);
        final int maxScroll = Math.max(0, section.settings.size() - visibleRows);
        this.rowScroll = Math.max(0, Math.min(maxScroll, this.rowScroll - (int) Math.signum(yOffset)));
        this.ctx.requestRender();
    }

    private void switchSpec(final int index) {
        if (this.specs.isEmpty()) return;
        this.activeSpecIndex = Math.floorMod(index, this.specs.size());
        this.activeSectionIndex = 0;
        this.selectedRow = 0;
        this.rowScroll = 0;
        this.editing = false;
        this.setStatus("READY", AppTheme.TEXT_FAINT);
    }

    private void switchSection(final int index) {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) return;
        this.activeSectionIndex = Math.max(0, Math.min(spec.sections.size() - 1, index));
        this.selectedRow = 0;
        this.rowScroll = 0;
        this.editing = false;
        this.setStatus("READY", AppTheme.TEXT_FAINT);
    }

    private void moveSection(final int delta) {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) return;
        this.switchSection(Math.floorMod(this.activeSectionIndex + delta, spec.sections.size()));
        this.ctx.playSelectionSound();
    }

    private void moveRow(final int delta) {
        final SettingSection section = this.activeSection();
        if (section == null || section.settings.isEmpty()) return;
        this.selectedRow = Math.max(0, Math.min(section.settings.size() - 1, this.selectedRow + delta));
        this.ensureSelectedVisible(this.visibleRowsHeight());
        this.ctx.playSelectionSound();
    }

    private void adjustSelected(final int delta) {
        final Setting setting = this.selectedSetting();
        if (setting == null || !setting.mutable) return;
        try {
            if (setting.adjust(delta, this.ctx.ctrlDown ? 10 : 1)) {
                this.setStatus("CHANGED " + setting.key, AppTheme.AMBER);
                this.ctx.playSelectionSound();
            } else if (setting.editableText()) {
                this.beginEdit(setting);
            }
        } catch (final RuntimeException e) {
            this.setStatus("INVALID: " + compactError(e), AppTheme.RED);
        }
    }

    private void activateSelected() {
        final Setting setting = this.selectedSetting();
        if (setting == null || !setting.mutable) return;
        if (setting.editableText()) {
            this.beginEdit(setting);
            return;
        }
        this.adjustSelected(1);
    }

    private void activateControl(final Setting setting, final Dimension control, final double mx) {
        try {
            if (setting.editableText()) {
                this.beginEdit(setting);
            } else if (setting.click(control, mx)) {
                this.setStatus("CHANGED " + setting.key, AppTheme.AMBER);
                this.ctx.playSelectionSound();
            }
        } catch (final RuntimeException e) {
            this.setStatus("INVALID: " + compactError(e), AppTheme.RED);
        }
    }

    private void beginEdit(final Setting setting) {
        this.editing = true;
        this.editingSetting = setting;
        this.editBuffer = setting.editValue();
        this.setStatus("EDITING " + setting.key, AppTheme.NEON_LIGHT);
    }

    private void cancelEdit() {
        this.editing = false;
        this.editingSetting = null;
        this.editBuffer = "";
        this.setStatus("EDIT CANCELLED", AppTheme.TEXT_FAINT);
    }

    private void commitEdit() {
        if (this.editingSetting == null) {
            this.cancelEdit();
            return;
        }
        try {
            this.editingSetting.commit(this.editBuffer);
            this.setStatus("CHANGED " + this.editingSetting.key, AppTheme.AMBER);
            this.editing = false;
            this.editingSetting = null;
            this.editBuffer = "";
        } catch (final RuntimeException e) {
            this.setStatus("INVALID: " + compactError(e), AppTheme.RED);
        }
    }

    private void resetActiveSection() {
        final SettingSection section = this.activeSection();
        if (section == null) return;
        try {
            for (final Setting setting: section.settings) {
                if (setting.mutable) setting.reset();
            }
            this.setStatus("RESET " + section.name.toUpperCase(Locale.ROOT), AppTheme.AMBER);
        } catch (final RuntimeException e) {
            this.setStatus("RESET FAILED: " + compactError(e), AppTheme.RED);
        }
    }

    private void saveActiveSpec() {
        final SettingSpec spec = this.activeSpec();
        if (spec == null) return;
        if (!spec.persistent || spec.configSpec == null) {
            this.setStatus("APP SETTINGS APPLY IMMEDIATELY", AppTheme.CYAN);
            return;
        }
        try {
            final Method save = ConfigSpec.class.getDeclaredMethod("save");
            save.setAccessible(true);
            save.invoke(spec.configSpec);
            this.setStatus("SAVED " + spec.configSpec.path().getFileName(), AppTheme.GREEN);
        } catch (final ReflectiveOperationException e) {
            this.setStatus("SAVE FAILED: " + compactError(e), AppTheme.RED);
        }
    }

    private void clampSelection() {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) {
            this.activeSectionIndex = 0;
            this.selectedRow = 0;
            this.rowScroll = 0;
            return;
        }
        this.activeSectionIndex = Math.max(0, Math.min(spec.sections.size() - 1, this.activeSectionIndex));
        final SettingSection section = this.activeSection();
        if (section == null || section.settings.isEmpty()) {
            this.selectedRow = 0;
            this.rowScroll = 0;
            return;
        }
        this.selectedRow = Math.max(0, Math.min(section.settings.size() - 1, this.selectedRow));
        this.rowScroll = Math.max(0, Math.min(this.rowScroll, section.settings.size() - 1));
    }

    private void ensureSelectedVisible(final int rowsH) {
        final SettingSection section = this.activeSection();
        if (section == null || section.settings.isEmpty()) return;
        final int visible = Math.max(1, rowsH / ROW_H);
        if (this.selectedRow < this.rowScroll) this.rowScroll = this.selectedRow;
        if (this.selectedRow >= this.rowScroll + visible) this.rowScroll = this.selectedRow - visible + 1;
        this.rowScroll = Math.max(0, Math.min(Math.max(0, section.settings.size() - visible), this.rowScroll));
    }

    private int visibleRowsHeight() {
        return Math.max(ROW_H, AppChrome.contentBottom(this.ctx.windowHeight) - (AppChrome.contentTop() + 10) - 94);
    }

    private SettingSpec activeSpec() {
        return this.specs.isEmpty() ? null : this.specs.get(Math.max(0, Math.min(this.specs.size() - 1, this.activeSpecIndex)));
    }

    private SettingSection activeSection() {
        final SettingSpec spec = this.activeSpec();
        return spec == null || spec.sections.isEmpty()
                ? null
                : spec.sections.get(Math.max(0, Math.min(spec.sections.size() - 1, this.activeSectionIndex)));
    }

    private Setting selectedSetting() {
        final SettingSection section = this.activeSection();
        return section == null || section.settings.isEmpty()
                ? null
                : section.settings.get(Math.max(0, Math.min(section.settings.size() - 1, this.selectedRow)));
    }

    private void setStatus(final String status, final Color color) {
        this.statusText = status == null ? "READY" : status;
        this.statusColor = color == null ? AppTheme.TEXT_FAINT : color;
        this.ctx.requestRender();
    }

    @Override
    public String instructions() {
        return this.editing
                ? "ENTER: Commit | BACKSPACE: Delete | ESC: Cancel"
                : "UP/DOWN: Field | LEFT/RIGHT: Change | TAB: Spec | PGUP/PGDN: Section | S: Save | ESC: Back";
    }

    private String cacheSizeLabel() {
        try {
            final Path cache = WaterMedia.tmp().resolve("cache");
            if (!java.nio.file.Files.exists(cache)) return "0 MB";
            try (final var stream = java.nio.file.Files.walk(cache)) {
                final long bytes = stream.filter(java.nio.file.Files::isRegularFile).mapToLong(path -> {
                    try {
                        return java.nio.file.Files.size(path);
                    } catch (final Exception ignored) {
                        return 0L;
                    }
                }).sum();
                return Math.max(0, Math.round(bytes / 1024f / 1024f)) + " MB";
            }
        } catch (final Exception e) {
            return "UNKNOWN";
        }
    }

    private int centerTextY(final int y, final int h, final float scale) {
        return y + Math.max(0, (h - this.text.glyphHeight(scale)) / 2);
    }

    private int centerBoldTextY(final int y, final int h, final float scale) {
        return y + Math.max(0, (h - this.text.glyphHeightBold(scale)) / 2);
    }

    private static String safePath(final Supplier<Path> supplier) {
        try {
            final Path path = supplier.get();
            return path == null ? "" : path.toAbsolutePath().toString();
        } catch (final RuntimeException e) {
            return "UNAVAILABLE";
        }
    }

    private static String titleCase(final String value) {
        if (value == null || value.isBlank()) return "Settings";
        final String spaced = value.replace('_', ' ').replace('-', ' ');
        final StringBuilder out = new StringBuilder(spaced.length());
        boolean upper = true;
        for (int i = 0; i < spaced.length(); i++) {
            final char c = spaced.charAt(i);
            if (Character.isWhitespace(c)) {
                upper = true;
                out.append(c);
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String compactError(final Throwable throwable) {
        final Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        final String message = cause.getMessage();
        if (message == null || message.isBlank()) return cause.getClass().getSimpleName();
        return message.length() > 78 ? message.substring(0, 75) + "..." : message;
    }

    private enum HitType {
        SPEC, SECTION, ROW, SAVE, RESET
    }

    private record Hit(Dimension bounds, HitType type, int index) {
    }

    private static final class SettingSpec {
        private final String title;
        private final String subtitle;
        private final boolean persistent;
        private final ConfigSpec configSpec;
        private final List<SettingSection> sections = new ArrayList<>();

        private SettingSpec(final String title, final String subtitle, final boolean persistent, final ConfigSpec configSpec) {
            this.title = title;
            this.subtitle = subtitle;
            this.persistent = persistent;
            this.configSpec = configSpec;
        }
    }

    private static final class SettingSection {
        private final String name;
        private final String detail;
        private final List<Setting> settings = new ArrayList<>();

        private SettingSection(final String name, final String detail) {
            this.name = name;
            this.detail = detail;
        }
    }

    private abstract static class Setting {
        protected final String label;
        protected final String key;
        protected final String type;
        protected final String note;
        protected final boolean mutable;

        protected Setting(final String label, final String key, final String type, final String note, final boolean mutable) {
            this.label = label;
            this.key = key;
            this.type = type;
            this.note = note == null || note.isBlank() ? "No description provided." : note;
            this.mutable = mutable;
        }

        abstract String valueLabel();

        String editValue() {
            return this.valueLabel();
        }

        boolean editableText() {
            return false;
        }

        boolean isBooleanControl() {
            return false;
        }

        boolean isEnumControl() {
            return false;
        }

        boolean isSeekbarControl() {
            return false;
        }

        boolean isSpinnerControl() {
            return false;
        }

        boolean booleanValue() {
            return false;
        }

        int controlWidth() {
            return CONTROL_W;
        }

        String suffix() {
            return "";
        }

        float rangePercent() {
            return 0f;
        }

        boolean click(final Dimension control, final double mx) {
            return this.adjust(1, 1);
        }

        boolean adjust(final int direction, final int step) {
            return false;
        }

        void commit(final String value) {
        }

        void reset() {
        }

        Color accent() {
            return this.mutable ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
        }
    }

    private static final class ReadOnlySetting extends Setting {
        private final Supplier<String> value;

        private ReadOnlySetting(final String label, final String key, final String type,
                                final String note, final Supplier<String> value) {
            super(label, key, type, note, false);
            this.value = value;
        }

        @Override
        String valueLabel() {
            final String current = this.value.get();
            return current == null || current.isBlank() ? "EMPTY" : current;
        }
    }

    private static final class RuntimeSetting extends Setting {
        private final Supplier<String> valueLabel;
        private final Supplier<Boolean> getter;
        private final Consumer<Boolean> setter;
        private final boolean defaultValue;

        private RuntimeSetting(final String label, final String key, final String type,
                               final String note, final Supplier<String> valueLabel,
                               final Supplier<Boolean> getter, final Consumer<Boolean> setter,
                               final boolean defaultValue) {
            super(label, key, type, note, true);
            this.valueLabel = valueLabel;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
        }

        @Override
        String valueLabel() {
            return this.valueLabel.get();
        }

        @Override
        boolean isBooleanControl() {
            return true;
        }

        @Override
        boolean booleanValue() {
            return this.getter.get();
        }

        @Override
        int controlWidth() {
            return 72;
        }

        @Override
        boolean adjust(final int direction, final int step) {
            this.setter.accept(!this.getter.get());
            return true;
        }

        @Override
        void reset() {
            this.setter.accept(this.defaultValue);
        }

        @Override
        Color accent() {
            return this.getter.get() ? AppTheme.CYAN : AppTheme.TEXT_FAINT;
        }
    }

    private static final class ConfigSetting extends Setting {
        private final IConfigField<?, ?> field;
        private final Class<?> valueType;
        private final Double minValue;
        private final Double maxValue;

        private ConfigSetting(final IConfigField<?, ?> field, final String key) {
            super(titleCase(field.name()), key, typeName(field), firstComment(field), true);
            this.field = field;
            this.valueType = field.type();
            this.minValue = numberLimit(field, "minValueString");
            this.maxValue = numberLimit(field, "maxValueString");
        }

        @Override
        String valueLabel() {
            final Object value = this.field.get();
            if (value == null) return "EMPTY";
            if (value instanceof Boolean bool) return bool ? "ON" : "OFF";
            if (value instanceof Path path) {
                final String raw = path.toString();
                return raw.isBlank() ? "." : raw;
            }
            return String.valueOf(value);
        }

        @Override
        String editValue() {
            final Object value = this.field.get();
            return value == null ? "" : String.valueOf(value);
        }

        @Override
        boolean editableText() {
            return this.isStringLike() || (this.isNumber() && !this.isSeekbarControl() && !this.isSpinnerControl());
        }

        @Override
        boolean isBooleanControl() {
            return this.isBoolean();
        }

        @Override
        boolean isEnumControl() {
            return this.valueType.isEnum();
        }

        @Override
        boolean isSeekbarControl() {
            return this.isNumber() && this.hasUsableRange();
        }

        @Override
        boolean isSpinnerControl() {
            return this.isInteger() && !this.hasUsableRange();
        }

        @Override
        boolean booleanValue() {
            return Boolean.TRUE.equals(this.field.get());
        }

        @Override
        int controlWidth() {
            if (this.isBooleanControl()) return 72;
            if (this.isEnumControl()) return 150;
            if (this.isSeekbarControl()) return CONTROL_W;
            if (this.isSpinnerControl()) return 190;
            if (Path.class.isAssignableFrom(this.valueType)) return CONTROL_W + 120;
            return CONTROL_W;
        }

        @Override
        String suffix() {
            final String lowerKey = this.key.toLowerCase(Locale.ROOT);
            if (lowerKey.endsWith("mb")) return "MB";
            if (lowerKey.endsWith("ms")) return "MS";
            if (lowerKey.endsWith("bytes")) return "B";
            return "";
        }

        @Override
        float rangePercent() {
            if (!this.hasUsableRange()) return 0f;
            final double value = ((Number) this.field.get()).doubleValue();
            final double min = this.minValue;
            final double max = this.maxValue;
            return (float) Math.max(0d, Math.min(1d, (value - min) / Math.max(1d, max - min)));
        }

        @Override
        boolean click(final Dimension control, final double mx) {
            if (this.isSeekbarControl()) {
                final double pct = Math.max(0d, Math.min(1d, (mx - control.x()) / Math.max(1d, control.width())));
                this.setValue(this.valueForPercent(pct));
                return true;
            }
            if (this.isSpinnerControl() || this.isEnumControl()) {
                return this.adjust(mx < control.x() + control.width() / 2d ? -1 : 1, 1);
            }
            return this.adjust(1, 1);
        }

        @Override
        boolean adjust(final int direction, final int step) {
            if (this.isBoolean()) {
                this.setValue(!Boolean.TRUE.equals(this.field.get()));
                return true;
            }
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants == null || constants.length == 0) return false;
                final Object current = this.field.get();
                int currentIndex = 0;
                for (int i = 0; i < constants.length; i++) {
                    if (constants[i] == current) {
                        currentIndex = i;
                        break;
                    }
                }
                final int next = Math.floorMod(currentIndex + direction, constants.length);
                this.setValue(constants[next]);
                return true;
            }
            if (this.isInteger()) {
                final Number current = (Number) this.field.get();
                final Object next = parseNumber(String.valueOf(current.longValue() + (long) direction * Math.max(1, step)), this.valueType);
                this.setValue(this.clampNumber(next));
                return true;
            }
            if (this.isNumber()) {
                final Number current = (Number) this.field.get();
                final double delta = this.hasUsableRange()
                        ? Math.max(1d, (this.maxValue - this.minValue) / 100d) * direction * Math.max(1, step)
                        : direction * Math.max(1, step);
                this.setValue(this.numberOf(current.doubleValue() + delta));
                return true;
            }
            return false;
        }

        @Override
        void commit(final String value) {
            this.setValue(this.parse(value));
        }

        @Override
        void reset() {
            this.field.reset();
        }

        @Override
        Color accent() {
            final Object value = this.field.get();
            if (value instanceof Boolean bool) return bool ? AppTheme.GREEN : AppTheme.TEXT_FAINT;
            if (this.valueType.isEnum()) return AppTheme.CYAN;
            if (this.isNumber()) return AppTheme.AMBER;
            return AppTheme.NEON_LIGHT;
        }

        private Object parse(final String raw) {
            final String value = raw == null ? "" : raw.trim();
            if (this.isBoolean()) {
                if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) return Boolean.TRUE;
                if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return Boolean.FALSE;
                throw new IllegalArgumentException("Expected on/off or true/false");
            }
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants != null) {
                    for (final Object constant: constants) {
                        if (((Enum<?>) constant).name().equalsIgnoreCase(value)) return constant;
                    }
                }
                throw new IllegalArgumentException("Unknown enum value: " + value);
            }
            if (this.isNumber()) return this.clampNumber(parseNumber(value, this.valueType));
            if (Path.class.isAssignableFrom(this.valueType)) return Path.of(value);
            return raw == null ? "" : raw;
        }

        private boolean hasUsableRange() {
            if (this.minValue == null || this.maxValue == null) return false;
            if (!Double.isFinite(this.minValue) || !Double.isFinite(this.maxValue)) return false;
            if (this.maxValue <= this.minValue) return false;
            final double span = this.maxValue - this.minValue;
            if (this.valueType == Integer.class || this.valueType == int.class) return span <= 1_000_000d;
            if (this.valueType == Long.class || this.valueType == long.class) return span <= 1_000_000d;
            if (this.valueType == Short.class || this.valueType == short.class) return true;
            if (this.valueType == Byte.class || this.valueType == byte.class) return true;
            return span <= 10_000d;
        }

        private Object valueForPercent(final double pct) {
            final double raw = this.minValue + (this.maxValue - this.minValue) * Math.max(0d, Math.min(1d, pct));
            if (this.isInteger()) {
                return this.numberOf(Math.round(raw));
            }
            return this.numberOf(raw);
        }

        private Object clampNumber(final Object value) {
            if (!(value instanceof Number number)) return value;
            double next = number.doubleValue();
            if (this.minValue != null) next = Math.max(next, this.minValue);
            if (this.maxValue != null) next = Math.min(next, this.maxValue);
            return this.numberOf(next);
        }

        private Object numberOf(final double value) {
            if (this.valueType == Integer.class || this.valueType == int.class) return (int) Math.round(value);
            if (this.valueType == Long.class || this.valueType == long.class) return Math.round(value);
            if (this.valueType == Short.class || this.valueType == short.class) return (short) Math.round(value);
            if (this.valueType == Byte.class || this.valueType == byte.class) return (byte) Math.round(value);
            if (this.valueType == Float.class || this.valueType == float.class) return (float) value;
            if (this.valueType == Double.class || this.valueType == double.class) return value;
            throw new IllegalArgumentException("Unsupported number type: " + this.valueType.getSimpleName());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void setValue(final Object next) {
            final Object previous = this.field.get();
            try {
                ((IConfigField) this.field).set0(next);
            } catch (final RuntimeException e) {
                try {
                    ((IConfigField) this.field).set0(previous);
                } catch (final RuntimeException ignored) {
                }
                throw e;
            }
        }

        private boolean isBoolean() {
            return this.valueType == Boolean.class || this.valueType == boolean.class;
        }

        private boolean isInteger() {
            return this.valueType == Integer.class || this.valueType == int.class
                    || this.valueType == Long.class || this.valueType == long.class
                    || this.valueType == Short.class || this.valueType == short.class
                    || this.valueType == Byte.class || this.valueType == byte.class;
        }

        private boolean isNumber() {
            return this.isInteger()
                    || this.valueType == Float.class || this.valueType == float.class
                    || this.valueType == Double.class || this.valueType == double.class;
        }

        private boolean isStringLike() {
            return this.valueType == String.class || Path.class.isAssignableFrom(this.valueType);
        }

        private static Object parseNumber(final String raw, final Class<?> type) {
            if (type == Integer.class || type == int.class) return Integer.parseInt(raw);
            if (type == Long.class || type == long.class) return Long.parseLong(raw);
            if (type == Short.class || type == short.class) return Short.parseShort(raw);
            if (type == Byte.class || type == byte.class) return Byte.parseByte(raw);
            if (type == Float.class || type == float.class) return Float.parseFloat(raw);
            if (type == Double.class || type == double.class) return Double.parseDouble(raw);
            throw new IllegalArgumentException("Unsupported number type: " + type.getSimpleName());
        }

        private static Double numberLimit(final IConfigField<?, ?> field, final String methodName) {
            try {
                final Method method = field.getClass().getMethod(methodName);
                final Object value = method.invoke(field);
                if (value == null) return null;
                return Double.parseDouble(String.valueOf(value));
            } catch (final ReflectiveOperationException | NumberFormatException e) {
                return null;
            }
        }

        private static String typeName(final IConfigField<?, ?> field) {
            final Class<?> type = field.type();
            if (type == Boolean.class || type == boolean.class) return "BOOL";
            if (type == Integer.class || type == int.class) return "INT";
            if (type == Long.class || type == long.class) return "LONG";
            if (type == Short.class || type == short.class) return "SHORT";
            if (type == Byte.class || type == byte.class) return "BYTE";
            if (type == Float.class || type == float.class) return "FLOAT";
            if (type == Double.class || type == double.class) return "DOUBLE";
            if (type.isEnum()) return "ENUM";
            if (Path.class.isAssignableFrom(type)) return "PATH";
            if (type == String.class) return "TEXT";
            return type.getSimpleName().toUpperCase(Locale.ROOT);
        }

        private static String firstComment(final IConfigField<?, ?> field) {
            final String[] comments = field.comments();
            if (comments == null || comments.length == 0) return "WaterMediaConfig option.";
            final StringBuilder out = new StringBuilder();
            for (final String comment: comments) {
                if (comment == null || comment.isBlank()) continue;
                if (out.length() > 0) out.append(" ");
                out.append(comment.trim());
                if (out.length() > 180) break;
            }
            return out.isEmpty() ? "WaterMediaConfig option." : out.toString();
        }
    }
}
