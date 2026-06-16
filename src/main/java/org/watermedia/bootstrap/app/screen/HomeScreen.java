package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Main home screen with grid-based menu options grouped by sections.
 */
public class HomeScreen extends Screen {

    public enum Action {
        OPEN_MULTIMEDIA, UPLOAD_LOGS, CLEANUP,
        SETTINGS,
        REGION_SELECTOR,
        MRL_SELECTOR, PLAYER,
        EXIT, BACK
    }

    private final Consumer<Action> navigator;
    private final List<MenuEntry> actions = new ArrayList<>();
    private final List<MenuEntry> mediaTests = new ArrayList<>();
    private final List<MenuEntry> entertainment = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int selectedPanel;
    private int selectedAction;
    private int selectedMedia;
    private int selectedEntertainment;
    private Dimension uploadTooltipAnchor;
    private Dimension uploadDialogCloseBounds = Dimension.ZERO;
    private Dimension uploadDialogXBounds = Dimension.ZERO;
    private Dimension uploadDialogPrimaryBounds = Dimension.ZERO;
    private Dimension cleanupDialogCloseBounds = Dimension.ZERO;
    private Dimension cleanupDialogXBounds = Dimension.ZERO;
    private Dimension cleanupDialogPrimaryBounds = Dimension.ZERO;
    private static final int UPLOAD_ROW_H = 52;
    private static final int UPLOAD_ROW_DETAIL_H = 58;
    private static final int UPLOAD_PANEL_PAD = 14;
    private static final int CLEANUP_ROW_H = 58;

    public HomeScreen(final TextRenderer text, final AppContext ctx, final Consumer<Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    public void onEnter() {
        this.rebuildMenu();
    }

    private void rebuildMenu() {
        this.actions.clear();
        this.mediaTests.clear();
        this.entertainment.clear();

        this.actions.add(new MenuEntry("Play media", "ENTER", Action.OPEN_MULTIMEDIA, -1));
        this.actions.add(new MenuEntry("Upload Logs", AppContext.IN_MODS ? "U" : "LOCKED", Action.UPLOAD_LOGS, -1));
        this.actions.add(new MenuEntry("Cleanup cache", this.cacheSizeLabel(), Action.CLEANUP, -1));
        // TODO: Settings is still WIP; keep it debug-only until the menu is production-ready.
        this.actions.add(new MenuEntry("Settings", WaterMedia.LOGGER.isDebugEnabled() ? "S" : "WIP", Action.SETTINGS, -1));
        this.actions.add(new MenuEntry("Exit", "ESC", Action.EXIT, -1));

        if (this.ctx.uriGroups != null) {
            for (int i = 0; i < this.ctx.uriGroups.length; i++) {
                final AppContext.URIGroup group = this.ctx.uriGroups[i];
                this.mediaTests.add(new MenuEntry(group.name(), group.uris().length + " samples", null, i));
            }
        }
        if (!this.ctx.customTests.isEmpty()) {
            final String label = "CUSTOM (" + this.ctx.customTests.size() + ")";
            this.mediaTests.add(new MenuEntry(label, "custom", null, -2));
        }
        if (this.ctx.iptvChannels.length > 0) {
            this.entertainment.add(new MenuEntry("Television", "", Action.REGION_SELECTOR, -1));
        }
    }

    private void handleSelect(final MenuEntry entry) {
        if (entry.groupIndex() >= 0 && entry.groupIndex() < this.ctx.uriGroups.length) {
            this.openGroup(this.ctx.uriGroups[entry.groupIndex()]);
        } else if (entry.groupIndex() == -2) {
            this.openCustomTests();
        } else if (entry.action() != null) {
            if (!this.actionEnabled(entry)) return;
            this.navigator.accept(entry.action());
        }
    }

    private void openGroup(final AppContext.URIGroup group) {
        this.ctx.selectedGroup = group;
        this.ctx.groupMRLs.clear();
        for (final AppContext.TestURI testUri: group.uris()) {
            this.ctx.groupMRLs.put(testUri.name(), MediaAPI.getMRL(testUri.uri()));
        }
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    private void openCustomTests() {
        if (this.ctx.customTests.isEmpty()) return;
        this.ctx.selectedGroup = new AppContext.URIGroup("CUSTOM",
                this.ctx.customTests.toArray(new AppContext.TestURI[0]));
        this.ctx.groupMRLs.clear();
        for (final AppContext.TestURI uri: this.ctx.customTests) {
            this.ctx.groupMRLs.put(uri.name(), MediaAPI.getMRL(uri.uri()));
        }
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    @Override
    public boolean wantsContinuousRender() {
        return this.ctx.backendsLoading || this.ctx.uploadDialogWorking || this.ctx.cleanupDialogWorking;
    }

    @Override
    public void render(final int windowW, final int windowH) {
        AppChrome.screen(this.text, this.ctx, windowW, windowH, "Multimedia API", "main menu", "v" + org.watermedia.WaterMedia.VERSION);

        this.hits.clear();
        final int x = 22;
        final int y = AppChrome.contentTop() + 10;
        final int gap = 18;
        final int contentH = AppChrome.contentBottom(windowH) - y;
        final int leftW = Math.max(330, (windowW - x * 2 - gap) / 2);
        final int rightX = x + leftW + gap;
        final int rightW = windowW - rightX - x;

        RenderSystem.setupOrtho(windowW, windowH);
        this.uploadTooltipAnchor = null;
        AppChrome.sectionHead(this.text, "Actions", this.actions.size() + " available", x, y);
        int rowY = y + 36;
        for (int i = 0; i < this.actions.size(); i++) {
            final MenuEntry entry = this.actions.get(i);
            final Dimension bounds = new Dimension(x, rowY, leftW, 56);
            this.drawAction(entry, bounds, this.selectedPanel == 0 && this.selectedAction == i, windowW, windowH);
            this.hits.add(new Hit(bounds, entry, 0, i));
            rowY += 64;
        }

        AppChrome.sectionHead(this.text, "Media tests", this.mediaTests.size() + " categories", rightX, y);
        final int colGap = 10;
        final int tileW = Math.max(160, (rightW - colGap) / 2);
        final int tileH = 94;
        for (int i = 0; i < this.mediaTests.size(); i++) {
            final int col = i % 2;
            final int row = i / 2;
            final int tx = rightX + col * (tileW + colGap);
            final int ty = y + 36 + row * (tileH + 10);
            if (ty + tileH > y + contentH) break;
            final Dimension bounds = new Dimension(tx, ty, tileW, tileH);
            this.drawMediaTile(this.mediaTests.get(i), bounds, this.selectedPanel == 1 && this.selectedMedia == i, windowW, windowH);
            this.hits.add(new Hit(bounds, this.mediaTests.get(i), 1, i));
        }
        if (!this.entertainment.isEmpty()) {
            final int mediaRows = Math.max(1, (this.mediaTests.size() + 1) / 2);
            final int entertainmentY = y + 36 + mediaRows * (tileH + 10) + 24;
            if (entertainmentY + 112 <= y + contentH) {
                AppChrome.sectionHead(this.text, "Entertaiment", this.entertainment.size() + " available", rightX, entertainmentY);
                int entertainmentRowY = entertainmentY + 36;
                for (int i = 0; i < this.entertainment.size(); i++) {
                    final Dimension bounds = new Dimension(rightX, entertainmentRowY, rightW, 72);
                    this.drawEntertainmentTile(this.entertainment.get(i), bounds, this.selectedPanel == 2 && this.selectedEntertainment == i);
                    this.hits.add(new Hit(bounds, this.entertainment.get(i), 2, i));
                    entertainmentRowY += 82;
                }
            }
        }
        if (this.uploadTooltipAnchor != null) {
            this.renderUploadLogsTooltip(this.uploadTooltipAnchor);
        }
        if (this.ctx.uploadDialogVisible) {
            this.renderUploadLogsDialog(windowW, windowH);
        }
        if (this.ctx.cleanupDialogVisible) {
            this.renderCleanupDialog(windowW, windowH);
        }
        RenderSystem.restoreProjection();
    }

    private void drawAction(final MenuEntry entry, final Dimension b, final boolean selected,
                            final int windowW, final int windowH) {
        final boolean enabled = this.actionEnabled(entry);
        final Color accent = !enabled ? AppTheme.TEXT_FAINT : switch (entry.action()) {
            case OPEN_MULTIMEDIA -> AppTheme.GREEN;
            case EXIT -> AppTheme.RED;
            default -> AppTheme.NEON_LIGHT;
        };
        final Color borderColor = !enabled
                ? AppTheme.STROKE
                : selected || entry.action() == Action.OPEN_MULTIMEDIA || entry.action() == Action.EXIT
                ? accent
                : AppTheme.STROKE_BRIGHT;
        final Color textColor = !enabled
                ? AppTheme.TEXT_FAINT
                : entry.action() == Action.OPEN_MULTIMEDIA || entry.action() == Action.EXIT
                ? accent
                : selected ? accent : AppTheme.TEXT;
        if (selected && enabled) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, accent, 0.35f);
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(),
                !enabled ? 0.06f : entry.action() == Action.EXIT ? (selected ? 0.26f : 0.20f) : AppTheme.BG_2.getRed() / 255f,
                !enabled ? 0.08f : entry.action() == Action.EXIT ? (selected ? 0.07f : 0.04f) : (selected ? 34f / 255f : AppTheme.BG_2.getGreen() / 255f),
                !enabled ? 0.14f : entry.action() == Action.EXIT ? (selected ? 0.11f : 0.08f) : (selected ? 66f / 255f : AppTheme.BG_2.getBlue() / 255f),
                enabled ? 0.92f : 0.58f);
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), borderColor, 2f);
        RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, enabled ? selected ? accent : AppTheme.NEON : AppTheme.STROKE_BRIGHT, enabled ? selected ? 0.28f : 0.08f : 0.03f);
        PixelIcon.draw(this.actionIcon(entry.action()), b.x() + 14, b.y() + 17, 18, accent);
        this.text.renderBold(entry.label().toUpperCase(), b.x() + 48, this.centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_BUTTON), textColor, AppTheme.TEXT_BUTTON);
        final int hintW = this.text.width(entry.meta(), AppTheme.TEXT_BODY) + 14;
        RenderSystem.fill(b.right() - hintW - 12, b.y() + 17, hintW, 22, AppTheme.alpha(AppTheme.BG_1, 180));
        RenderSystem.rect(b.right() - hintW - 12, b.y() + 17, hintW, 22, AppTheme.STROKE, 1f);
        this.text.render(entry.meta(), b.right() - hintW - 5, this.centerTextY(b.y() + 17, 22, AppTheme.TEXT_BODY), AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        if (entry.action() == Action.UPLOAD_LOGS && selected && !this.ctx.uploadDialogVisible) this.uploadTooltipAnchor = b;
    }

    private boolean actionEnabled(final MenuEntry entry) {
        if (entry.action() == Action.UPLOAD_LOGS) return AppContext.IN_MODS;
        if (entry.action() == Action.SETTINGS) return WaterMedia.LOGGER.isDebugEnabled();
        return true;
    }

    private void drawMediaTile(final MenuEntry entry, final Dimension b, final boolean selected,
                               final int windowW, final int windowH) {
        final Color folderColor = this.categoryColor(entry.groupIndex(), 0);
        final Color titleColor = folderColor;
        final Color accent = selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT;
        if (selected) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, AppTheme.NEON, 0.28f);
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(),
                selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), accent, 2f);
        PixelIcon.draw("folder", b.x() + 14, b.y() + 15, 18, folderColor);
        this.text.renderBold(this.text.truncateToWidth(entry.label().toUpperCase(), b.width() - 62, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD),
                b.x() + 42, this.centerBoldTextY(b.y() + 15, 18, AppTheme.TEXT_BUTTON), titleColor, AppTheme.TEXT_BUTTON);
        this.text.render((entry.meta() + " - click to load").toUpperCase(Locale.ROOT), b.x() + 14, b.y() + 50, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
    }

    private void drawEntertainmentTile(final MenuEntry entry, final Dimension b, final boolean selected) {
        final Color accent = selected ? AppTheme.GREEN : AppTheme.NEON_LIGHT;
        if (selected) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, AppTheme.GREEN, 0.30f);
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(),
                selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), selected ? AppTheme.GREEN : AppTheme.STROKE_BRIGHT, 2f);
        final String label = entry.label().toUpperCase(Locale.ROOT);
        final int iconSize = 34;
        final int gap = 18;
        final int labelW = this.text.widthBold(label, AppTheme.TEXT_DISPLAY);
        final int groupW = iconSize + gap + labelW;
        final int groupX = b.x() + Math.max(0, (b.width() - groupW) / 2);
        PixelIcon.draw("tv", groupX, b.y() + Math.max(0, (b.height() - iconSize) / 2), iconSize, accent);
        this.text.renderBold(label, groupX + iconSize + gap,
                this.centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_DISPLAY), accent, AppTheme.TEXT_DISPLAY);
    }

    private String actionIcon(final Action action) {
        return switch (action) {
            case OPEN_MULTIMEDIA -> "play";
            case UPLOAD_LOGS -> "upload";
            case CLEANUP -> "broom";
            case SETTINGS -> "settings";
            case EXIT -> "x";
            default -> "info";
        };
    }

    private Color categoryColor(final int index, final int offset) {
        final Color[] palette = {AppTheme.NEON, AppTheme.CYAN, AppTheme.AMBER, AppTheme.GREEN, AppTheme.NEON_LIGHT};
        return palette[Math.floorMod(index + offset, palette.length)];
    }

    private void renderUploadLogsTooltip(final Dimension anchor) {
        final int x = anchor.x();
        final int y = anchor.bottom() + 8;
        final float titleScale = AppTheme.TEXT_BUTTON;
        final float bodyScale = AppTheme.TEXT_BODY;
        final boolean blocked = !AppContext.IN_MODS;
        final String title = blocked ? "NO MC CONTEXT" : "SENDS LOGS TO MCLO.GS";
        final String line1 = blocked ? "Upload logs is blocked outside" : "Reads logs/latest.log and crash reports,";
        final String line2 = blocked ? "the Minecraft mods folder." : "then uploads.";
        final Color color = blocked ? AppTheme.RED : AppTheme.AMBER;
        final int desiredW = Math.max(this.text.width(title, titleScale),
                Math.max(this.text.width(line1, bodyScale), this.text.width(line2, bodyScale))) + 24;
        final int w = Math.min(Math.max(340, desiredW), this.ctx.windowWidth - x - 24);
        final int h = 82;
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, 242));
        RenderSystem.rect(x, y, w, h, color, 1f);
        RenderSystem.glowRect(x, y, w, h, 0f, color, 0.24f);
        RenderSystem.fillTriangle(x + 16, y, x + 30, y, x + 23, y - 10,
                color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, 1f);
        this.text.renderBold(title, x + 12, y + 12, color, titleScale);
        this.text.render(line1, x + 12, y + 36, AppTheme.TEXT_SOFT, bodyScale);
        this.text.render(line2, x + 12, y + 56, AppTheme.TEXT_SOFT, bodyScale);
    }

    private void renderUploadLogsDialog(final int windowW, final int windowH) {
        final int dialogW = Math.min(890, windowW - 48);
        final int filePanelH = this.uploadFilePanelHeight();
        final int issueBlockH = this.ctx.uploadDialogStage >= 3 ? 20 + 120 : 0;
        final int dialogH = Math.min(166 + filePanelH + issueBlockH + 28 + 86, windowH - 36);
        final Dimension dialog = Dimension.centered(windowW, windowH, dialogW, dialogH);
        final int x = dialog.x();
        final int y = dialog.y();

        RenderSystem.fill(0, 0, windowW, windowH, 0f, 0f, 0f, 0.58f);
        RenderSystem.shadowRect(x, y, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(x, y, dialogW, dialogH, 0f, this.ctx.uploadDialogDone ? AppTheme.GREEN : AppTheme.NEON, 0.26f);
        RenderSystem.fill(x, y, dialogW, dialogH, AppTheme.alpha(AppTheme.BG_1, 248));
        RenderSystem.rect(x, y, dialogW, dialogH, this.ctx.uploadDialogDone ? AppTheme.GREEN : AppTheme.NEON_LIGHT, 1.5f);
        RenderSystem.fill(x, y, dialogW, 64, AppTheme.alpha(AppTheme.BG_2, 244));
        RenderSystem.lineH(x, y + 64, dialogW, AppTheme.STROKE_BRIGHT, 1f);

        this.uploadDialogXBounds = new Dimension(x + dialogW - 52, y + 18, 32, 32);
        final boolean closeHover = this.uploadDialogXBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.text.renderBold(this.ctx.uploadDialogDone ? "SUCCESS" : "UPLOAD LOG FILES",
                x + 22, y + 24, this.ctx.uploadDialogDone ? AppTheme.GREEN : AppTheme.CYAN, AppTheme.TEXT_BUTTON);
        AppChrome.dialogCloseButton(this.uploadDialogXBounds, closeHover);

        this.renderUploadStepper(x + 46, y + 86);
        RenderSystem.lineH(x, y + 138, dialogW, AppTheme.STROKE_BRIGHT, 1f);

        final int contentX = x + 28;
        final int contentY = y + 166;
        final int contentW = dialogW - 56;
        this.renderUploadFilesPanel(contentX, contentY, contentW, filePanelH);
        if (this.ctx.uploadDialogStage >= 3) {
            this.renderUploadIssuePanel(contentX, contentY + filePanelH + 20, contentW, 120);
        }

        RenderSystem.lineH(x, y + dialogH - 86, dialogW, AppTheme.STROKE_BRIGHT, 1f);
        AppChrome.amberCube(x + 4, y + dialogH - 12, 8);
        AppChrome.amberCube(x + dialogW - 12, y + dialogH - 12, 8);
        this.renderUploadDialogButtons(x, y, dialogW, dialogH);
    }

    private void renderUploadStepper(final int x, final int y) {
        int cursor = x;
        cursor = this.renderUploadStep(1, "SCAN", cursor, y) + 18;
        RenderSystem.lineH(cursor - 8, y + 15, 34, AppTheme.STROKE_BRIGHT, 1f);
        cursor += 38;
        cursor = this.renderUploadStep(2, "UPLOAD", cursor, y) + 18;
        RenderSystem.lineH(cursor - 8, y + 15, 34, AppTheme.STROKE_BRIGHT, 1f);
        cursor += 38;
        this.renderUploadStep(3, "ISSUE", cursor, y);
    }

    private int renderUploadStep(final int step, final String label, final int x, final int y) {
        final boolean complete = this.ctx.uploadDialogStage > step
                || this.ctx.uploadDialogDone
                || (step == 2 && this.ctx.uploadUploadsDone);
        final boolean active = this.ctx.uploadDialogStage == step && !this.ctx.uploadDialogDone;
        final Color color = complete ? AppTheme.GREEN : active ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
        RenderSystem.fill(x, y, 30, 30, AppTheme.alpha(AppTheme.BG_2, 220));
        RenderSystem.rect(x, y, 30, 30, color, active || complete ? 2f : 1f);
        if (complete) {
            PixelIcon.draw("check", x + 8, y + 8, 14, color);
        } else {
            this.text.render(String.valueOf(step), x + 11, y + 7, color, AppTheme.TEXT_BODY);
        }
        this.text.render(label, x + 42, y + 8, color, AppTheme.TEXT_BODY);
        return x + 42 + this.text.width(label, AppTheme.TEXT_BODY);
    }

    private void renderUploadFilesPanel(final int x, final int y, final int w, final int h) {
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 164));
        RenderSystem.rect(x, y, w, h, AppTheme.STROKE_BRIGHT, 1f);
        final List<AppContext.UploadFileEntry> files = this.visibleUploadFiles();
        final int rowH = this.uploadRowHeight();
        int rowY = y + UPLOAD_PANEL_PAD;
        for (int i = 0; i < files.size(); i++) {
            final AppContext.UploadFileEntry entry = files.get(i);
            this.renderUploadFileRow(entry, x + 18, rowY, w - 36, rowH, i < files.size() - 1);
            rowY += rowH;
        }
    }

    private void renderUploadFileRow(final AppContext.UploadFileEntry entry, final int x, final int y,
                                     final int w, final int h, final boolean separator) {
        final Color stateColor = entry.uploaded || "READ OK".equals(entry.state)
                ? AppTheme.GREEN
                : "UPLOADING".equals(entry.state)
                ? AppTheme.NEON_LIGHT
                : "INVALID".equals(entry.state)
                ? AppTheme.AMBER
                : entry.present && !"FAILED".equals(entry.state) && !"READ ERROR".equals(entry.state) ? AppTheme.TEXT_FAINT : AppTheme.RED;
        final boolean errored = "FAILED".equals(entry.state) || "READ ERROR".equals(entry.state);
        final boolean detailed = (this.ctx.uploadDialogStage >= 3 && entry.uploaded && !entry.url.isBlank())
                || (this.ctx.uploadDialogStage == 2 && entry.present && entry.valid && !errored);
        final int nameY = detailed ? this.centerTextY(y, 28, AppTheme.TEXT_BODY) : this.centerTextY(y, h, AppTheme.TEXT_BODY);
        AppChrome.statusPip(x + 4, y + Math.max(0, (h - 10) / 2), 10, stateColor, true);
        this.text.render(entry.name, x + 28, nameY, entry.present ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        if (this.ctx.uploadDialogStage >= 3 && entry.uploaded && !entry.url.isBlank()) {
            this.text.render(entry.url, x + 28, y + 28, AppTheme.CYAN, AppTheme.TEXT_SUBTITLE);
        } else if (this.ctx.uploadDialogStage == 2 && entry.present) {
            final int barX = x + 28;
            final int barY = y + 31;
            final int barW = Math.max(120, w - 124);
            RenderSystem.fill(barX, barY, barW, 6, AppTheme.BG_3);
            RenderSystem.fillGradientH(barX, barY, barW * (Math.max(0, Math.min(100, entry.progress)) / 100f), 6,
                    AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                    AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
        }

        final int tagH = 26;
        final int tagY = y + Math.max(0, (h - tagH) / 2);
        final int sizeY = detailed ? nameY : this.centerTextY(tagY, tagH, AppTheme.TEXT_SUBTITLE);
        this.text.render(entry.sizeLabel, x + w - 180, sizeY, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        final String tag = this.ctx.uploadDialogStage == 2 && errored
                ? "ERROR"
                : this.ctx.uploadDialogStage == 2 && entry.present && entry.valid && !entry.uploaded
                ? Math.max(0, Math.min(100, entry.progress)) + "%"
                : entry.state;
        final int tagW = Math.max(84, this.text.width(tag, AppTheme.TEXT_SUBTITLE) + 22);
        final int tagX = x + w - tagW;
        RenderSystem.fill(tagX, tagY, tagW, tagH, AppTheme.alpha(AppTheme.BG_1, 210));
        RenderSystem.rect(tagX, tagY, tagW, tagH, stateColor, 1.5f);
        this.text.render(tag, tagX + 11, this.centerTextY(tagY, tagH, AppTheme.TEXT_SUBTITLE), stateColor, AppTheme.TEXT_SUBTITLE);
        if (separator) {
            for (int dx = x + 28; dx < x + w; dx += 8) {
                RenderSystem.fill(dx, y + h - 1, 4, 1, AppTheme.alpha(AppTheme.STROKE_BRIGHT, 90));
            }
        }
    }

    private int centerTextY(final int y, final int h, final float scale) {
        return y + Math.max(0, (h - this.text.glyphHeight(scale)) / 2);
    }

    private int centerBoldTextY(final int y, final int h, final float scale) {
        return y + Math.max(0, (h - this.text.glyphHeightBold(scale)) / 2);
    }

    private int uploadFilePanelHeight() {
        return UPLOAD_PANEL_PAD * 2 + this.uploadRowHeight() * Math.max(1, this.visibleUploadFiles().size());
    }

    private int uploadRowHeight() {
        return this.ctx.uploadDialogStage >= 2 ? UPLOAD_ROW_DETAIL_H : UPLOAD_ROW_H;
    }

    private List<AppContext.UploadFileEntry> visibleUploadFiles() {
        final List<AppContext.UploadFileEntry> files = new ArrayList<>();
        for (final AppContext.UploadFileEntry entry: this.ctx.uploadDialogFiles) {
            if (this.ctx.uploadDialogStage <= 1) {
                files.add(entry);
            } else if (this.ctx.uploadDialogStage == 2) {
                if (entry.present && entry.valid) files.add(entry);
                else if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) files.add(entry);
            } else if (entry.uploaded) {
                files.add(entry);
            }
        }
        return files;
    }

    private void renderUploadIssuePanel(final int x, final int y, final int w, final int h) {
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.GREEN, 22));
        RenderSystem.rect(x, y, w, h, AppTheme.GREEN, 1.5f);
        PixelIcon.draw("check", x + 22, y + 24, 14, this.ctx.uploadIssueCopied ? AppTheme.GREEN : AppTheme.TEXT_FAINT);
        this.text.render("Issue template copied to clipboard", x + 52, y + 20,
                this.ctx.uploadIssueCopied ? AppTheme.GREEN : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        PixelIcon.draw("link", x + 22, y + 54, 14, AppTheme.CYAN);
        this.text.render(this.text.truncateToWidth(this.ctx.uploadIssueUrl, w - 74, AppTheme.TEXT_BODY),
                x + 52, y + 50, AppTheme.CYAN, AppTheme.TEXT_BODY);
        this.text.render("Paste the template in the new issue body to share log links.",
                x + 22, y + 88, AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE);
    }

    private void renderUploadDialogButtons(final int x, final int y, final int dialogW, final int dialogH) {
        this.uploadDialogCloseBounds = new Dimension(x + 22, y + dialogH - 68, 170, 48);
        final boolean cancelHover = this.uploadDialogCloseBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.renderDialogButton(this.uploadDialogCloseBounds, this.ctx.uploadDialogDone ? "CLOSE" : "CANCEL", "ESC",
                AppTheme.TEXT, AppTheme.STROKE_BRIGHT, cancelHover);

        final String label = this.uploadPrimaryLabel();
        final int primaryW = Math.max(246, this.text.widthBold(label, AppTheme.TEXT_BUTTON) + this.text.width("ENTER", AppTheme.TEXT_SUBTITLE) + 112);
        this.uploadDialogPrimaryBounds = new Dimension(x + dialogW - primaryW - 22, y + dialogH - 68, primaryW, 48);
        final boolean primaryEnabled = this.uploadPrimaryEnabled();
        final boolean primaryHover = primaryEnabled && this.uploadDialogPrimaryBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        final Color primaryColor = !primaryEnabled ? AppTheme.TEXT_FAINT : (this.ctx.uploadDialogDone || this.ctx.uploadUploadsDone) ? AppTheme.GREEN : AppTheme.CYAN;
        this.renderDialogButton(this.uploadDialogPrimaryBounds, label, "ENTER", primaryColor, primaryColor, primaryHover, primaryEnabled);
    }

    private void renderDialogButton(final Dimension b, final String label, final String key,
                                    final Color textColor, final Color borderColor, final boolean hover) {
        this.renderDialogButton(b, label, key, textColor, borderColor, hover, true);
    }

    private void renderDialogButton(final Dimension b, final String label, final String key,
                                    final Color textColor, final Color borderColor, final boolean hover,
                                    final boolean enabled) {
        RenderSystem.fill(b.x(), b.y(), b.width(), b.height(),
                hover ? AppTheme.alpha(AppTheme.NEON_DARK, 92) : AppTheme.alpha(AppTheme.BG_2, 220));
        RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), borderColor, 1.5f);
        if (hover) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, borderColor, 0.24f);
        final String icon = label.startsWith("UPLOAD") ? "upload" : label.startsWith("OPEN") ? "link" : label.startsWith("GENERATE") ? "check" : label.startsWith("CLEAN") ? "broom" : null;
        int textX = b.x() + 22;
        if (icon != null) {
            PixelIcon.draw(icon, b.x() + 20, b.y() + 17, 14, textColor);
            textX = b.x() + 52;
        }
        final int keyW = Math.max(46, this.text.width(key, AppTheme.TEXT_SUBTITLE) + 16);
        final int keyX = b.right() - keyW - 18;
        final int maxLabelW = Math.max(40, keyX - textX - 18);
        this.text.renderBold(this.text.truncateToWidth(label, maxLabelW, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD),
                textX, this.centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_BUTTON), textColor, AppTheme.TEXT_BUTTON);
        RenderSystem.fill(keyX, b.y() + 14, keyW, 22, AppTheme.alpha(AppTheme.BG_1, 180));
        RenderSystem.rect(keyX, b.y() + 14, keyW, 22, AppTheme.STROKE, 1f);
        this.text.render(key, keyX + 8, this.centerTextY(b.y() + 14, 22, AppTheme.TEXT_SUBTITLE), enabled ? AppTheme.TEXT_FAINT : AppTheme.alpha(AppTheme.TEXT_FAINT, 110), AppTheme.TEXT_SUBTITLE);
    }

    private String uploadPrimaryLabel() {
        if (this.ctx.uploadDialogStage <= 1) return "UPLOAD TO MCLO.GS";
        if (this.ctx.uploadDialogStage == 2) return "GENERATE REPORT";
        return "OPEN GITHUB";
    }

    private boolean uploadPrimaryEnabled() {
        if (this.ctx.uploadDialogWorking) return false;
        if (this.ctx.uploadDialogStage <= 1) {
            for (final AppContext.UploadFileEntry entry: this.ctx.uploadDialogFiles) if (entry.present) return true;
            return false;
        }
        if (this.ctx.uploadDialogStage == 2) {
            for (final AppContext.UploadFileEntry entry: this.ctx.uploadDialogFiles) if (entry.uploaded) return true;
            return false;
        }
        return this.ctx.uploadDialogDone || this.ctx.uploadIssueCopied;
    }

    private void renderCleanupDialog(final int windowW, final int windowH) {
        final int dialogW = Math.min(740, windowW - 48);
        final int panelH = UPLOAD_PANEL_PAD * 2 + CLEANUP_ROW_H;
        final int dialogH = Math.min(166 + panelH + 28 + 86, windowH - 36);
        final Dimension dialog = Dimension.centered(windowW, windowH, dialogW, dialogH);
        final int x = dialog.x();
        final int y = dialog.y();
        final Color accent = this.ctx.cleanupDialogError ? AppTheme.RED : this.ctx.cleanupDialogDone ? AppTheme.GREEN : AppTheme.CYAN;

        RenderSystem.fill(0, 0, windowW, windowH, 0f, 0f, 0f, 0.58f);
        RenderSystem.shadowRect(x, y, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(x, y, dialogW, dialogH, 0f, accent, 0.24f);
        RenderSystem.fill(x, y, dialogW, dialogH, AppTheme.alpha(AppTheme.BG_1, 248));
        RenderSystem.rect(x, y, dialogW, dialogH, accent, 1.5f);
        RenderSystem.fill(x, y, dialogW, 64, AppTheme.alpha(AppTheme.BG_2, 244));
        RenderSystem.lineH(x, y + 64, dialogW, AppTheme.STROKE_BRIGHT, 1f);

        this.cleanupDialogXBounds = new Dimension(x + dialogW - 52, y + 18, 32, 32);
        final boolean closeHover = this.cleanupDialogXBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.text.renderBold(this.ctx.cleanupDialogDone ? "CACHE CLEANED" : "CLEAN CACHE",
                x + 22, y + 24, accent, AppTheme.TEXT_BUTTON);
        AppChrome.dialogCloseButton(this.cleanupDialogXBounds, closeHover);

        this.renderCleanupStepper(x + 46, y + 86);
        RenderSystem.lineH(x, y + 138, dialogW, AppTheme.STROKE_BRIGHT, 1f);

        final int contentX = x + 28;
        final int contentY = y + 166;
        final int contentW = dialogW - 56;
        this.renderCleanupPanel(contentX, contentY, contentW, panelH);

        RenderSystem.lineH(x, y + dialogH - 86, dialogW, AppTheme.STROKE_BRIGHT, 1f);
        AppChrome.amberCube(x + 4, y + dialogH - 12, 8);
        AppChrome.amberCube(x + dialogW - 12, y + dialogH - 12, 8);
        this.renderCleanupDialogButtons(x, y, dialogW, dialogH);
    }

    private void renderCleanupStepper(final int x, final int y) {
        int cursor = this.renderCleanupStep(1, "SCAN", x, y) + 18;
        RenderSystem.lineH(cursor - 8, y + 15, 34, AppTheme.STROKE_BRIGHT, 1f);
        cursor += 38;
        this.renderCleanupStep(2, "CLEAN", cursor, y);
    }

    private int renderCleanupStep(final int step, final String label, final int x, final int y) {
        final boolean complete = this.ctx.cleanupDialogStage > step || (step == 2 && this.ctx.cleanupDialogDone);
        final boolean active = this.ctx.cleanupDialogStage == step && !this.ctx.cleanupDialogDone;
        final Color color = complete ? AppTheme.GREEN : active ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
        RenderSystem.fill(x, y, 30, 30, AppTheme.alpha(AppTheme.BG_2, 220));
        RenderSystem.rect(x, y, 30, 30, color, active || complete ? 2f : 1f);
        if (complete) {
            PixelIcon.draw("check", x + 8, y + 8, 14, color);
        } else {
            this.text.render(String.valueOf(step), x + 11, y + 7, color, AppTheme.TEXT_BODY);
        }
        this.text.render(label, x + 42, y + 8, color, AppTheme.TEXT_BODY);
        return x + 42 + this.text.width(label, AppTheme.TEXT_BODY);
    }

    private void renderCleanupPanel(final int x, final int y, final int w, final int h) {
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 164));
        RenderSystem.rect(x, y, w, h, AppTheme.STROKE_BRIGHT, 1f);
        this.renderCleanupRow(x + 18, y + UPLOAD_PANEL_PAD, w - 36, CLEANUP_ROW_H);
    }

    private void renderCleanupRow(final int x, final int y, final int w, final int h) {
        final Color stateColor = this.cleanupStateColor();
        final String name = this.ctx.cleanupFileCount == 1 ? "1 FILE" : this.ctx.cleanupFileCount + " FILES";
        final String tag = this.cleanupStateLabel();
        final boolean progress = this.ctx.cleanupDialogStage == 2 && this.ctx.cleanupDialogWorking;
        final int nameY = progress ? this.centerTextY(y, 28, AppTheme.TEXT_BODY) : this.centerTextY(y, h, AppTheme.TEXT_BODY);

        AppChrome.statusPip(x + 4, y + Math.max(0, (h - 10) / 2), 10, stateColor, true);
        this.text.render(name, x + 28, nameY, this.ctx.cleanupFileCount > 0 ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        if (progress) {
            final int barX = x + 28;
            final int barY = y + 31;
            final int barW = Math.max(120, w - 124);
            RenderSystem.fill(barX, barY, barW, 6, AppTheme.BG_3);
            RenderSystem.fillGradientH(barX, barY, barW * (Math.max(0, Math.min(100, this.ctx.cleanupProgress)) / 100f), 6,
                    AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                    AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
        }

        final int tagH = 26;
        final int tagY = y + Math.max(0, (h - tagH) / 2);
        final int sizeY = progress ? nameY : this.centerTextY(tagY, tagH, AppTheme.TEXT_SUBTITLE);
        this.text.render(this.ctx.cleanupSizeLabel, x + w - 180, sizeY, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        final int tagW = Math.max(84, this.text.width(tag, AppTheme.TEXT_SUBTITLE) + 22);
        final int tagX = x + w - tagW;
        RenderSystem.fill(tagX, tagY, tagW, tagH, AppTheme.alpha(AppTheme.BG_1, 210));
        RenderSystem.rect(tagX, tagY, tagW, tagH, stateColor, 1.5f);
        this.text.render(tag, tagX + 11, this.centerTextY(tagY, tagH, AppTheme.TEXT_SUBTITLE), stateColor, AppTheme.TEXT_SUBTITLE);
    }

    private Color cleanupStateColor() {
        if (this.ctx.cleanupDialogError) return AppTheme.RED;
        if ("EMPTY".equals(this.ctx.cleanupDialogState)) return AppTheme.AMBER;
        if (this.ctx.cleanupDialogWorking) return AppTheme.NEON_LIGHT;
        if (this.ctx.cleanupDialogDone || "FOUND".equals(this.ctx.cleanupDialogState)) return AppTheme.GREEN;
        return AppTheme.TEXT_FAINT;
    }

    private String cleanupStateLabel() {
        if (this.ctx.cleanupDialogStage == 2 && this.ctx.cleanupDialogWorking) {
            return Math.max(0, Math.min(100, this.ctx.cleanupProgress)) + "%";
        }
        return this.ctx.cleanupDialogState;
    }

    private void renderCleanupDialogButtons(final int x, final int y, final int dialogW, final int dialogH) {
        this.cleanupDialogCloseBounds = new Dimension(x + 22, y + dialogH - 68, 170, 48);
        final boolean cancelHover = this.cleanupDialogCloseBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.renderDialogButton(this.cleanupDialogCloseBounds, this.ctx.cleanupDialogDone ? "CLOSE" : "CANCEL", "ESC",
                AppTheme.TEXT, AppTheme.STROKE_BRIGHT, cancelHover);

        final String label = this.cleanupPrimaryLabel();
        final int primaryW = Math.max(220, this.text.widthBold(label, AppTheme.TEXT_BUTTON) + this.text.width("ENTER", AppTheme.TEXT_SUBTITLE) + 112);
        this.cleanupDialogPrimaryBounds = new Dimension(x + dialogW - primaryW - 22, y + dialogH - 68, primaryW, 48);
        final boolean primaryEnabled = this.cleanupPrimaryEnabled();
        final boolean primaryHover = primaryEnabled && this.cleanupDialogPrimaryBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        final Color primaryColor = !primaryEnabled ? AppTheme.TEXT_FAINT : this.ctx.cleanupDialogDone ? AppTheme.GREEN : AppTheme.CYAN;
        this.renderDialogButton(this.cleanupDialogPrimaryBounds, label, "ENTER", primaryColor, primaryColor, primaryHover, primaryEnabled);
    }

    private String cleanupPrimaryLabel() {
        return this.ctx.cleanupDialogStage <= 1 ? "CLEAN CACHE" : "CLOSE";
    }

    private boolean cleanupPrimaryEnabled() {
        if (this.ctx.cleanupDialogWorking) return false;
        return this.ctx.cleanupDialogStage > 1 || (this.ctx.cleanupFileCount > 0 && !this.ctx.cleanupDialogError);
    }

    private void closeCleanupDialog() {
        this.ctx.cleanupDialogVisible = false;
        this.rebuildMenu();
        this.ctx.requestRender();
    }

    private String cacheSizeLabel() {
        final Path cache = WaterMedia.tmp().resolve("cache");
        long bytes = 0L;
        if (Files.exists(cache)) {
            try (final var stream = Files.walk(cache)) {
                bytes = stream.filter(Files::isRegularFile).mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (final IOException ignored) {
                        return 0L;
                    }
                }).sum();
            } catch (final IOException ignored) {
            }
        }
        return Math.max(0, Math.round(bytes / 1024f / 1024f)) + " MB";
    }

    @Override
    protected void onKeyRelease(final int key) {
        if (this.ctx.cleanupDialogVisible) {
            if (key == GLFW_KEY_ESCAPE) {
                this.closeCleanupDialog();
                this.ctx.playSelectionSound();
            } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                if (this.cleanupPrimaryEnabled()) {
                    if (this.ctx.cleanupDialogStage > 1) {
                        this.closeCleanupDialog();
                    } else {
                        this.navigator.accept(Action.CLEANUP);
                    }
                    this.ctx.playSelectionSound();
                }
            }
            return;
        }

        if (this.ctx.uploadDialogVisible) {
            if (key == GLFW_KEY_ESCAPE) {
                this.ctx.uploadDialogVisible = false;
                this.ctx.playSelectionSound();
            } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                if (this.uploadPrimaryEnabled()) {
                    this.navigator.accept(Action.UPLOAD_LOGS);
                    this.ctx.playSelectionSound();
                }
            }
            return;
        }

        switch (key) {
            case GLFW_KEY_UP -> this.moveSelection(-1);
            case GLFW_KEY_DOWN -> this.moveSelection(1);
            case GLFW_KEY_LEFT -> this.switchPanel(0);
            case GLFW_KEY_RIGHT -> this.switchPanel(this.mediaTests.isEmpty() && !this.entertainment.isEmpty() ? 2 : 1);
            case GLFW_KEY_U -> {
                this.selectedPanel = 0;
                this.selectedAction = 1;
                this.confirmSelection();
            }
            case GLFW_KEY_S -> {
                this.selectedPanel = 0;
                this.selectedAction = Math.min(3, this.actions.size() - 1);
                this.confirmSelection();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.confirmSelection();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(Action.EXIT);
        }
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        if (this.ctx.uploadDialogVisible || this.ctx.cleanupDialogVisible) return;
        for (final Hit hit: this.hits) {
            if (hit.bounds.contains(mx, my)) {
                if (hit.panel != this.selectedPanel || hit.index != this.selectedIndex(hit.panel)) {
                    this.selectedPanel = hit.panel;
                    if (hit.panel == 0) this.selectedAction = hit.index;
                    else if (hit.panel == 1) this.selectedMedia = hit.index;
                    else this.selectedEntertainment = hit.index;
                    this.ctx.playSelectionSound();
                }
                return;
            }
        }
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (this.ctx.cleanupDialogVisible) {
            if (this.cleanupDialogCloseBounds.contains(mx, my) || this.cleanupDialogXBounds.contains(mx, my)) {
                this.closeCleanupDialog();
                this.ctx.playSelectionSound();
            } else if (this.cleanupPrimaryEnabled() && this.cleanupDialogPrimaryBounds.contains(mx, my)) {
                if (this.ctx.cleanupDialogStage > 1) {
                    this.closeCleanupDialog();
                } else {
                    this.navigator.accept(Action.CLEANUP);
                }
                this.ctx.playSelectionSound();
            }
            return;
        }

        if (this.ctx.uploadDialogVisible) {
            if (this.uploadDialogCloseBounds.contains(mx, my) || this.uploadDialogXBounds.contains(mx, my)) {
                this.ctx.uploadDialogVisible = false;
                this.ctx.playSelectionSound();
            } else if (this.uploadPrimaryEnabled() && this.uploadDialogPrimaryBounds.contains(mx, my)) {
                this.navigator.accept(Action.UPLOAD_LOGS);
                this.ctx.playSelectionSound();
            }
            return;
        }

        for (final Hit hit: this.hits) {
            if (hit.bounds.contains(mx, my)) {
                this.handleSelect(hit.entry);
                return;
            }
        }
    }

    private void moveSelection(final int delta) {
        if (this.selectedPanel == 0 && !this.actions.isEmpty()) {
            this.selectedAction = Math.max(0, Math.min(this.actions.size() - 1, this.selectedAction + delta));
        } else if (this.selectedPanel == 2) {
            if (delta < 0 && !this.mediaTests.isEmpty()) {
                this.selectedPanel = 1;
                this.selectedMedia = Math.max(0, this.mediaTests.size() - 1);
            } else {
                this.selectedEntertainment = Math.max(0, Math.min(this.entertainment.size() - 1, this.selectedEntertainment + delta));
            }
        } else if (!this.mediaTests.isEmpty()) {
            final int next = this.selectedMedia + delta * 2;
            if (delta > 0 && next >= this.mediaTests.size() && !this.entertainment.isEmpty()) {
                this.selectedPanel = 2;
                this.selectedEntertainment = 0;
            } else {
                this.selectedMedia = Math.max(0, Math.min(this.mediaTests.size() - 1, next));
            }
        } else if (!this.entertainment.isEmpty()) {
            this.selectedPanel = 2;
            this.selectedEntertainment = Math.max(0, Math.min(this.entertainment.size() - 1, this.selectedEntertainment + delta));
        }
        this.ctx.playSelectionSound();
    }

    private void switchPanel(final int panel) {
        if (this.selectedPanel != panel) {
            this.selectedPanel = panel;
            this.ctx.playSelectionSound();
        }
    }

    private void confirmSelection() {
        if (this.selectedPanel == 0 && this.selectedAction < this.actions.size()) {
            this.handleSelect(this.actions.get(this.selectedAction));
        } else if (this.selectedPanel == 1 && this.selectedMedia < this.mediaTests.size()) {
            this.handleSelect(this.mediaTests.get(this.selectedMedia));
        } else if (this.selectedPanel == 2 && this.selectedEntertainment < this.entertainment.size()) {
            this.handleSelect(this.entertainment.get(this.selectedEntertainment));
        }
    }

    private int selectedIndex(final int panel) {
        return switch (panel) {
            case 0 -> this.selectedAction;
            case 1 -> this.selectedMedia;
            case 2 -> this.selectedEntertainment;
            default -> -1;
        };
    }

    @Override
    public String instructions() {
        return "ARROWS: Navigate | ENTER: Select | ESC: Exit";
    }

    private record MenuEntry(String label, String meta, Action action, int groupIndex) {
    }

    private record Hit(Dimension bounds, MenuEntry entry, int panel, int index) {
    }
}
