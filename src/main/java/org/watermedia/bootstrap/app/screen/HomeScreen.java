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
import org.watermedia.bootstrap.app.view.Button;
import org.watermedia.bootstrap.app.view.Canvas;
import org.watermedia.bootstrap.app.view.View;
import org.watermedia.bootstrap.app.view.ViewGroup;
import org.watermedia.tools.ThreadTool;

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
public class HomeScreen extends ViewScreen {

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
    private int selectedPanel;
    private int selectedAction;
    private int selectedMedia;
    // NUMBER OF MEDIA TILES THAT ACTUALLY FIT/ARE DRAWN THIS FRAME; KEYBOARD SELECTION IS CLAMPED TO IT
    // SO ARROWS/ENTER CANNOT LAND ON AN OVERFLOWING TILE THAT IS NEITHER HIGHLIGHTED NOR CLICKABLE
    private int visibleMediaCount;
    private int selectedEntertainment;
    private Dimension uploadTooltipAnchor;
    private Dimension uploadDialogCloseBounds = Dimension.ZERO;
    private Dimension uploadDialogXBounds = Dimension.ZERO;
    private Dimension uploadDialogPrimaryBounds = Dimension.ZERO;
    private Dimension cleanupDialogCloseBounds = Dimension.ZERO;
    private Dimension cleanupDialogXBounds = Dimension.ZERO;
    private Dimension cleanupDialogPrimaryBounds = Dimension.ZERO;
    private final List<RepoHit> repoHits = new ArrayList<>();
    private int repoSelected;
    private int repoScrollOffset;
    private int repoVisibleRows = 1;
    private static final int UPLOAD_ROW_H = 52;
    private static final int UPLOAD_ROW_DETAIL_H = 58;
    private static final int UPLOAD_PANEL_PAD = 14;
    private static final int CLEANUP_ROW_H = 58;
    private static final int REPO_ROW_H = 56;
    private static final int REPO_ROW_STEP = 64;

    // CACHE-SIZE LABEL FOR THE CLEANUP ENTRY, COMPUTED OFF THE RENDER THREAD (Files.walk BLOCKS ON LARGE
    // CACHES). rebuildMenu() READS THE LAST PUBLISHED VALUE; cacheWalking COALESCES OVERLAPPING WALKS.
    private volatile String cacheLabel = "-- MB";
    private volatile boolean cacheWalking;

    public HomeScreen(final TextRenderer text, final AppContext ctx, final Consumer<Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    public void onEnter() {
        this.rebuildMenu();
        this.refreshCacheSize();
    }

    private void rebuildMenu() {
        this.actions.clear();
        this.mediaTests.clear();
        this.entertainment.clear();

        this.actions.add(new MenuEntry("Play media", "ENTER", Action.OPEN_MULTIMEDIA, -1));
        this.actions.add(new MenuEntry("Upload Logs", AppContext.IN_MODS ? "U" : "LOCKED", Action.UPLOAD_LOGS, -1));
        this.actions.add(new MenuEntry("Cleanup cache", this.cacheLabel, Action.CLEANUP, -1));
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
        // REBUILD THE CONTENT TREE SO THE TILE VIEWS MATCH THE CURRENT MENU ENTRIES
        this.rebuild();
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
        return super.wantsContinuousRender() || this.ctx.backendsLoading || this.ctx.upload.working || this.ctx.cleanup.working;
    }

    @Override
    protected View<?> build() {
        final HomeBody body = new HomeBody();
        for (int i = 0; i < this.actions.size(); i++) {
            final ActionTile tile = new ActionTile(i);
            body.actionViews.add(tile);
            body.add(tile);
        }
        for (int i = 0; i < this.mediaTests.size(); i++) {
            final MediaTile tile = new MediaTile(i);
            body.mediaViews.add(tile);
            body.add(tile);
        }
        for (int i = 0; i < this.entertainment.size(); i++) {
            final EntertainmentTile tile = new EntertainmentTile(i);
            body.entViews.add(tile);
            body.add(tile);
        }
        return body.width(View.MATCH_PARENT).height(View.MATCH_PARENT);
    }

    @Override
    protected void renderChrome(final int windowW, final int windowH) {
        AppChrome.screen(this.text, this.ctx, windowW, windowH, "Multimedia API", "main menu", "v" + WaterMedia.VERSION);
    }

    // CONTENT RECT — MIRRORS THE LEGACY x/y/contentH: LEFT MARGIN 22, TOP contentTop()+10, RIGHT AT windowW-22
    @Override
    protected int contentX(final int windowW, final int windowH) {
        return 22;
    }

    @Override
    protected int contentY(final int windowW, final int windowH) {
        return AppChrome.contentTop() + 10;
    }

    @Override
    protected int contentW(final int windowW, final int windowH) {
        return windowW - 44;
    }

    @Override
    protected int contentH(final int windowW, final int windowH) {
        return AppChrome.contentBottom(windowH) - (AppChrome.contentTop() + 10);
    }

    @Override
    public void render(final int windowW, final int windowH) {
        super.render(windowW, windowH); // WINDOW CHROME (renderChrome) THEN THE MENU VIEW TREE
        // THE UPLOAD/CLEANUP DIALOGS ARE FULL-WINDOW MODALS PAINTED ON TOP OF THE MENU
        if (this.ctx.upload.visible || this.ctx.cleanup.visible) {
            RenderSystem.setupOrtho(windowW, windowH);
            if (this.ctx.upload.visible) this.renderUploadLogsDialog(windowW, windowH);
            if (this.ctx.cleanup.visible) this.renderCleanupDialog(windowW, windowH);
            RenderSystem.restoreProjection();
        }
    }

    private boolean actionEnabled(final MenuEntry entry) {
        if (entry.action() == Action.UPLOAD_LOGS) return AppContext.IN_MODS;
        if (entry.action() == Action.SETTINGS) return WaterMedia.LOGGER.isDebugEnabled();
        return true;
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
        final boolean report = this.ctx.upload.stage >= 3;
        // OUTSIDE THE REPORT STAGE THE REPO PICKER STATE IS DORMANT — KEEP IT RESET SO IT STARTS FRESH.
        if (!report) {
            this.repoScrollOffset = 0;
            this.repoSelected = 0;
            this.repoHits.clear();
        }
        final int dialogW = Math.min(report ? 900 : 890, windowW - 48);
        final int filePanelH = report ? 0 : this.uploadFilePanelHeight();
        final int dialogH = report
                ? Math.min(648, windowH - 36)
                : Math.min(166 + filePanelH + 28 + 86, windowH - 36);
        final Dimension dialog = Dimension.centered(windowW, windowH, dialogW, dialogH);
        final int x = dialog.x();
        final int y = dialog.y();

        RenderSystem.fill(0, 0, windowW, windowH, 0f, 0f, 0f, 0.58f);
        RenderSystem.shadowRect(x, y, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(x, y, dialogW, dialogH, 0f, this.ctx.upload.done ? AppTheme.GREEN : AppTheme.NEON, 0.26f);
        RenderSystem.fill(x, y, dialogW, dialogH, AppTheme.alpha(AppTheme.BG_1, 248));
        RenderSystem.rect(x, y, dialogW, dialogH, this.ctx.upload.done ? AppTheme.GREEN : AppTheme.NEON_LIGHT, 1.5f);
        RenderSystem.fill(x, y, dialogW, 64, AppTheme.alpha(AppTheme.BG_2, 244));
        RenderSystem.lineH(x, y + 64, dialogW, AppTheme.STROKE_BRIGHT, 1f);

        this.uploadDialogXBounds = new Dimension(x + dialogW - 52, y + 18, 32, 32);
        final boolean closeHover = this.uploadDialogXBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.text.renderBold(this.ctx.upload.done ? "SUCCESS" : "UPLOAD LOG FILES",
                x + 22, y + 24, this.ctx.upload.done ? AppTheme.GREEN : AppTheme.CYAN, AppTheme.TEXT_BUTTON);
        AppChrome.dialogCloseButton(this.uploadDialogXBounds, closeHover);

        this.renderUploadStepper(x + 46, y + 86);
        RenderSystem.lineH(x, y + 138, dialogW, AppTheme.STROKE_BRIGHT, 1f);

        final int contentX = x + 28;
        final int contentY = y + 166;
        final int contentW = dialogW - 56;
        if (report) {
            this.renderUploadReport(y, contentX, contentY, contentW, dialogH, windowH);
        } else {
            this.renderUploadFilesPanel(contentX, contentY, contentW, filePanelH);
        }

        RenderSystem.lineH(x, y + dialogH - 86, dialogW, AppTheme.STROKE_BRIGHT, 1f);
        AppChrome.amberCube(x + 4, y + dialogH - 12, 8);
        AppChrome.amberCube(x + dialogW - 12, y + dialogH - 12, 8);
        this.renderUploadDialogButtons(x, y, dialogW, dialogH);
    }

    // STAGE 3 — REPORT SCREEN: A LARGE WATERMEDIA BANNER (PRIMARY TARGET) PLUS A SCROLLABLE LIST OF
    // SUSPECTED-MOD REPOSITORIES, EACH WITH ITS OWN SUBMIT BUTTON. THE LIST SCROLLS SO THE DIALOG STAYS COMPACT.
    private void renderUploadReport(final int y, final int contentX, final int contentY,
                                    final int contentW, final int dialogH, final int windowH) {
        final List<RepoTarget> repos = this.uploadRepoTargets();
        this.repoHits.clear();

        // CLIPBOARD CONFIRMATION STRIP
        final int confH = 30;
        final boolean copied = this.ctx.upload.issueCopied;
        RenderSystem.fill(contentX, contentY, contentW, confH, AppTheme.alpha(copied ? AppTheme.GREEN : AppTheme.BG_2, copied ? 26 : 164));
        RenderSystem.rect(contentX, contentY, contentW, confH, copied ? AppTheme.GREEN : AppTheme.STROKE_BRIGHT, 1f);
        PixelIcon.draw(copied ? "check" : "copy", contentX + 12, contentY + (confH - 14) / 2, 14, copied ? AppTheme.GREEN : AppTheme.TEXT_FAINT);
        final String confMsg = copied
                ? "Report template copied to clipboard — paste it into the issue body"
                : "Clipboard copy unavailable — write the report manually";
        this.text.render(this.text.truncateToWidth(confMsg, contentW - 44, AppTheme.TEXT_BODY),
                contentX + 34, this.centerTextY(contentY, confH, AppTheme.TEXT_BODY), copied ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);

        // HERO — WATERMEDIA (PRIMARY REPO, GOES FIRST)
        final int heroY = contentY + confH + 14;
        final int heroH = 104;
        this.renderRepoHero(repos.get(0), contentX, heroY, contentW, heroH);

        // SUSPECTED-MOD LIST HEADER
        final int listHeadY = heroY + heroH + 16;
        this.text.renderBold("SUSPECTED MODS", contentX, this.centerBoldTextY(listHeadY, 20, AppTheme.TEXT_BUTTON), AppTheme.NEON, AppTheme.TEXT_BUTTON);
        this.text.render("SUBMIT TO THE MATCHING REPOSITORY", contentX + this.text.widthBold("SUSPECTED MODS", AppTheme.TEXT_BUTTON) + 14,
                this.centerTextY(listHeadY, 20, AppTheme.TEXT_SUBTITLE), AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);

        // SCROLLABLE LIST OF SMALLER MOD BANNERS (TARGETS 1..n)
        final int mods = repos.size() - 1;
        final int listTop = listHeadY + 26;
        if (mods == 0) {
            this.repoVisibleRows = 0;
            this.repoScrollOffset = 0;
            this.text.render("No known suspect mods detected in this instance — submit to WaterMedia above.",
                    contentX, listTop + 4, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
            return;
        }
        final int listBottom = y + dialogH - 86 - 12;
        final int listH = Math.max(REPO_ROW_H, listBottom - listTop);
        this.repoVisibleRows = Math.max(1, Math.min(mods, (listH + (REPO_ROW_STEP - REPO_ROW_H)) / REPO_ROW_STEP));
        final boolean needsScroll = mods > this.repoVisibleRows;
        this.repoScrollOffset = Math.max(0, Math.min(Math.max(0, mods - this.repoVisibleRows), this.repoScrollOffset));
        final int cardW = contentW - (needsScroll ? 16 : 0);

        RenderSystem.clip(contentX, listTop, contentW, listH, windowH);
        for (int i = 0; i < this.repoVisibleRows && i + this.repoScrollOffset < mods; i++) {
            final int targetIdx = i + this.repoScrollOffset + 1;
            this.renderRepoCard(repos.get(targetIdx), targetIdx, contentX, listTop + i * REPO_ROW_STEP, cardW, REPO_ROW_H);
        }
        RenderSystem.clearClip();
        if (needsScroll) {
            this.renderRepoScrollBar(contentX + contentW - 5, listTop, listH, mods);
        }
    }

    private void renderRepoHero(final RepoTarget repo, final int x, final int y, final int w, final int h) {
        final boolean selected = this.repoSelected == 0;
        final int pad = 14;
        RenderSystem.glowRect(x, y, w, h, 0f, AppTheme.GREEN, selected ? 0.26f : 0.12f);
        RenderSystem.fill(x, y, w, h, AppTheme.alpha(AppTheme.GREEN, selected ? 30 : 18));
        RenderSystem.rect(x, y, w, h, AppTheme.GREEN, selected ? 2f : 1.5f);
        RenderSystem.fill(x, y, 4, h, AppTheme.GREEN);

        // PACK.PNG LOGO — SQUARE, ASPECT-PRESERVED, CENTERED IN ITS BOX
        final int logoBox = h - pad * 2;
        final int logoX = x + pad + 8;
        if (this.ctx.assets.iconId > 0 && this.ctx.assets.iconWidth > 0 && this.ctx.assets.iconHeight > 0) {
            final float s = Math.min((float) logoBox / this.ctx.assets.iconWidth, (float) logoBox / this.ctx.assets.iconHeight);
            final int lw = (int) (this.ctx.assets.iconWidth * s);
            final int lh = (int) (this.ctx.assets.iconHeight * s);
            RenderSystem.bindTexture(this.ctx.assets.iconId);
            RenderSystem.color(1f, 1f, 1f, 1f);
            RenderSystem.blit(logoX + (logoBox - lw) / 2f, y + pad + (logoBox - lh) / 2f, lw, lh);
        }

        final int textX = logoX + logoBox + 20;
        final int textRight = x + w - pad;
        // PRIMARY BADGE TOP-RIGHT
        final int badgeW = this.text.width("PRIMARY", AppTheme.TEXT_SUBTITLE) + 20;
        RenderSystem.fill(textRight - badgeW, y + pad, badgeW, 20, AppTheme.alpha(AppTheme.GREEN, 40));
        RenderSystem.rect(textRight - badgeW, y + pad, badgeW, 20, AppTheme.GREEN, 1f);
        this.text.render("PRIMARY", textRight - badgeW + 10, this.centerTextY(y + pad, 20, AppTheme.TEXT_SUBTITLE), AppTheme.GREEN, AppTheme.TEXT_SUBTITLE);

        // SUBMIT BUTTON — BOTTOM-RIGHT
        final Dimension submit = new Dimension(textRight - 150, y + h - pad - 32, 150, 32);
        final boolean active = selected || submit.contains(this.ctx.mouseX, this.ctx.mouseY);

        // TEXT — EACH LINE CLAMPED SO IT NEVER SPILLS PAST THE BADGE, THE BUTTON OR THE HERO EDGE
        this.text.renderBold(this.text.truncateToWidth("SUBMIT A BUG REPORT ON WATERMEDIA ISSUE TRACKER",
                        textRight - badgeW - 12 - textX, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD),
                textX, y + 16, AppTheme.GREEN, AppTheme.TEXT_BUTTON);
        this.text.render(this.text.truncateToWidth("AND — if a listed mod is the suspect — also file it in that mod's repo below.",
                        textRight - textX, AppTheme.TEXT_BODY),
                textX, y + 40, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        this.text.render(this.text.truncateToWidth(repo.slug(), submit.x() - 12 - textX, AppTheme.TEXT_SUBTITLE),
                textX, y + 64, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);

        this.renderRepoSubmit(submit, AppTheme.GREEN, active);
        this.repoHits.add(new RepoHit(0, new Dimension(x, y, w, h), submit));
    }

    private void renderRepoCard(final RepoTarget repo, final int targetIdx, final int x, final int y, final int w, final int h) {
        final boolean selected = this.repoSelected == targetIdx;
        final Color accent = repo.accent();
        RenderSystem.fill(x, y, w, h, selected ? AppTheme.alpha(accent, 30) : AppTheme.alpha(AppTheme.BG_2, 210));
        RenderSystem.rect(x, y, w, h, selected ? accent : AppTheme.STROKE_BRIGHT, selected ? 2f : 1f);
        if (selected) RenderSystem.glowRect(x, y, w, h, 0f, accent, 0.18f);
        RenderSystem.fill(x, y, 4, h, accent);
        AppChrome.statusPip(x + 16, y + (h - 10) / 2, 10, accent, true);
        this.text.renderBold(repo.name().toUpperCase(), x + 36, y + 12, selected ? accent : AppTheme.TEXT, AppTheme.TEXT_BUTTON);
        this.text.render(repo.slug(), x + 36, y + 32, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);

        final Dimension submit = new Dimension(x + w - 138, y + (h - 32) / 2, 122, 32);
        final boolean active = selected || submit.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.renderRepoSubmit(submit, accent, active);
        this.repoHits.add(new RepoHit(targetIdx, new Dimension(x, y, w, h), submit));
    }

    private void renderRepoSubmit(final Dimension b, final Color accent, final boolean active) {
        Button.render(this.text, b.x(), b.y(), b.width(), b.height(),
                "SUBMIT", "", "link", 12, accent, accent, false, active, true);
    }

    private void renderRepoScrollBar(final int x, final int y, final int h, final int total) {
        RenderSystem.fill(x, y, 4, h, AppTheme.alpha(AppTheme.BG_3, 190));
        final int thumbH = Math.max(24, Math.round(h * (this.repoVisibleRows / (float) total)));
        final int maxScroll = Math.max(1, total - this.repoVisibleRows);
        final int thumbY = y + Math.round((h - thumbH) * (this.repoScrollOffset / (float) maxScroll));
        RenderSystem.fill(x, thumbY, 4, thumbH, AppTheme.NEON);
        RenderSystem.glowRect(x, thumbY, 4, thumbH, 0f, AppTheme.NEON, 0.24f);
    }

    private List<RepoTarget> uploadRepoTargets() {
        final String wmUrl = this.ctx.upload.issueUrl != null && this.ctx.upload.issueUrl.startsWith("http")
                ? this.ctx.upload.issueUrl
                : "https://github.com/WaterMediaTeam/watermedia/issues/new";
        final List<RepoTarget> repos = new ArrayList<>();
        repos.add(new RepoTarget("WaterMedia", "WaterMediaTeam/watermedia", wmUrl, AppTheme.GREEN));
        // ONLY LIST SUSPECT MODS WHOSE JAR WAS ACTUALLY FOUND IN THE INSTANCE (SEE WaterMediaApp.scanSuspectMods)
        final Color[] palette = {AppTheme.AMBER, AppTheme.CYAN, AppTheme.NEON_LIGHT, AppTheme.NEON};
        int idx = 0;
        for (final AppContext.SuspectMod mod: AppContext.SUSPECT_MODS) {
            if (this.ctx.upload.suspectModIds.contains(mod.id())) {
                repos.add(new RepoTarget(mod.name(), mod.slug(), mod.url(), palette[idx % palette.length]));
            }
            idx++;
        }
        return repos;
    }

    private RepoTarget selectedRepo() {
        final List<RepoTarget> repos = this.uploadRepoTargets();
        return repos.get(Math.max(0, Math.min(repos.size() - 1, this.repoSelected)));
    }

    private void submitRepo(final RepoTarget repo, final int index) {
        this.repoSelected = index;
        this.ctx.upload.repoUrl = repo.url();
        this.navigator.accept(Action.UPLOAD_LOGS);
        this.ctx.playSelectionSound();
    }

    private void moveRepoSelection(final int delta) {
        final int count = this.uploadRepoTargets().size();
        this.repoSelected = Math.max(0, Math.min(count - 1, this.repoSelected + delta));
        // KEEP THE SELECTED MOD (TARGETS 1..n-1) INSIDE THE SCROLL VIEWPORT
        if (this.repoSelected >= 1) {
            final int modIdx = this.repoSelected - 1;
            if (modIdx < this.repoScrollOffset) this.repoScrollOffset = modIdx;
            if (modIdx >= this.repoScrollOffset + this.repoVisibleRows) this.repoScrollOffset = modIdx - this.repoVisibleRows + 1;
            final int mods = count - 1;
            this.repoScrollOffset = Math.max(0, Math.min(Math.max(0, mods - this.repoVisibleRows), this.repoScrollOffset));
        }
        this.ctx.playSelectionSound();
        this.ctx.requestRender();
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
        final boolean complete = this.ctx.upload.stage > step
                || this.ctx.upload.done
                || (step == 2 && this.ctx.upload.uploadsDone);
        final boolean active = this.ctx.upload.stage == step && !this.ctx.upload.done;
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
        final boolean detailed = (this.ctx.upload.stage >= 3 && entry.uploaded && !entry.url.isBlank())
                || (this.ctx.upload.stage == 2 && entry.present && entry.valid && !errored);
        final int nameY = detailed ? this.centerTextY(y, 28, AppTheme.TEXT_BODY) : this.centerTextY(y, h, AppTheme.TEXT_BODY);
        AppChrome.statusPip(x + 4, y + Math.max(0, (h - 10) / 2), 10, stateColor, true);
        this.text.render(entry.name, x + 28, nameY, entry.present ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        if (this.ctx.upload.stage >= 3 && entry.uploaded && !entry.url.isBlank()) {
            this.text.render(entry.url, x + 28, y + 28, AppTheme.CYAN, AppTheme.TEXT_SUBTITLE);
        } else if (this.ctx.upload.stage == 2 && entry.present) {
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
        final String tag = this.ctx.upload.stage == 2 && errored
                ? "ERROR"
                : this.ctx.upload.stage == 2 && entry.present && entry.valid && !entry.uploaded
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
        return this.ctx.upload.stage >= 2 ? UPLOAD_ROW_DETAIL_H : UPLOAD_ROW_H;
    }

    private List<AppContext.UploadFileEntry> visibleUploadFiles() {
        final List<AppContext.UploadFileEntry> files = new ArrayList<>();
        for (final AppContext.UploadFileEntry entry: this.ctx.upload.files) {
            if (this.ctx.upload.stage <= 1) {
                files.add(entry);
            } else if (this.ctx.upload.stage == 2) {
                if (entry.present && entry.valid) files.add(entry);
                else if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) files.add(entry);
            } else if (entry.uploaded) {
                files.add(entry);
            }
        }
        return files;
    }

    private void renderUploadDialogButtons(final int x, final int y, final int dialogW, final int dialogH) {
        this.uploadDialogCloseBounds = new Dimension(x + 22, y + dialogH - 68, 170, 48);
        final boolean cancelHover = this.uploadDialogCloseBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.renderDialogButton(this.uploadDialogCloseBounds, this.ctx.upload.done ? "CLOSE" : "CANCEL", "ESC",
                AppTheme.TEXT, AppTheme.STROKE_BRIGHT, cancelHover);

        final String label = this.uploadPrimaryLabel();
        final int primaryW = Math.max(246, this.text.widthBold(label, AppTheme.TEXT_BUTTON) + this.text.width("ENTER", AppTheme.TEXT_SUBTITLE) + 112);
        this.uploadDialogPrimaryBounds = new Dimension(x + dialogW - primaryW - 22, y + dialogH - 68, primaryW, 48);
        final boolean primaryEnabled = this.uploadPrimaryEnabled();
        final boolean primaryHover = primaryEnabled && this.uploadDialogPrimaryBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        final Color primaryColor = !primaryEnabled ? AppTheme.TEXT_FAINT : (this.ctx.upload.done || this.ctx.upload.uploadsDone) ? AppTheme.GREEN : AppTheme.CYAN;
        this.renderDialogButton(this.uploadDialogPrimaryBounds, label, "ENTER", primaryColor, primaryColor, primaryHover, primaryEnabled);
    }

    private void renderDialogButton(final Dimension b, final String label, final String key,
                                    final Color textColor, final Color borderColor, final boolean hover) {
        this.renderDialogButton(b, label, key, textColor, borderColor, hover, true);
    }

    private void renderDialogButton(final Dimension b, final String label, final String key,
                                    final Color textColor, final Color borderColor, final boolean hover,
                                    final boolean enabled) {
        // ICON DERIVED FROM THE LABEL PREFIX (EMPTY = NO ICON); borderColor IS THE ACCENT, key IS THE HOTKEY CHIP
        final String icon = label.startsWith("UPLOAD") ? "upload" : label.startsWith("OPEN") ? "link" : label.startsWith("GENERATE") ? "check" : label.startsWith("CLEAN") ? "broom" : "";
        Button.render(this.text, b.x(), b.y(), b.width(), b.height(),
                label, key, icon, 12, borderColor, textColor, false, hover, enabled);
    }

    private String uploadPrimaryLabel() {
        if (this.ctx.upload.stage <= 1) return "UPLOAD TO MCLO.GS";
        if (this.ctx.upload.stage == 2) return "GENERATE REPORT";
        return "OPEN " + this.selectedRepo().name().toUpperCase();
    }

    private boolean uploadPrimaryEnabled() {
        if (this.ctx.upload.working) return false;
        if (this.ctx.upload.stage <= 1) {
            for (final AppContext.UploadFileEntry entry: this.ctx.upload.files) if (entry.present) return true;
            return false;
        }
        if (this.ctx.upload.stage == 2) {
            for (final AppContext.UploadFileEntry entry: this.ctx.upload.files) if (entry.uploaded) return true;
            return false;
        }
        return true;
    }

    private void renderCleanupDialog(final int windowW, final int windowH) {
        final int dialogW = Math.min(740, windowW - 48);
        final int panelH = UPLOAD_PANEL_PAD * 2 + CLEANUP_ROW_H;
        final int dialogH = Math.min(166 + panelH + 28 + 86, windowH - 36);
        final Dimension dialog = Dimension.centered(windowW, windowH, dialogW, dialogH);
        final int x = dialog.x();
        final int y = dialog.y();
        final Color accent = this.ctx.cleanup.error ? AppTheme.RED : this.ctx.cleanup.done ? AppTheme.GREEN : AppTheme.CYAN;

        RenderSystem.fill(0, 0, windowW, windowH, 0f, 0f, 0f, 0.58f);
        RenderSystem.shadowRect(x, y, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(x, y, dialogW, dialogH, 0f, accent, 0.24f);
        RenderSystem.fill(x, y, dialogW, dialogH, AppTheme.alpha(AppTheme.BG_1, 248));
        RenderSystem.rect(x, y, dialogW, dialogH, accent, 1.5f);
        RenderSystem.fill(x, y, dialogW, 64, AppTheme.alpha(AppTheme.BG_2, 244));
        RenderSystem.lineH(x, y + 64, dialogW, AppTheme.STROKE_BRIGHT, 1f);

        this.cleanupDialogXBounds = new Dimension(x + dialogW - 52, y + 18, 32, 32);
        final boolean closeHover = this.cleanupDialogXBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.text.renderBold(this.ctx.cleanup.done ? "CACHE CLEANED" : "CLEAN CACHE",
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
        final boolean complete = this.ctx.cleanup.stage > step || (step == 2 && this.ctx.cleanup.done);
        final boolean active = this.ctx.cleanup.stage == step && !this.ctx.cleanup.done;
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
        final String name = this.ctx.cleanup.fileCount == 1 ? "1 FILE" : this.ctx.cleanup.fileCount + " FILES";
        final String tag = this.cleanupStateLabel();
        final boolean progress = this.ctx.cleanup.stage == 2 && this.ctx.cleanup.working;
        final int nameY = progress ? this.centerTextY(y, 28, AppTheme.TEXT_BODY) : this.centerTextY(y, h, AppTheme.TEXT_BODY);

        AppChrome.statusPip(x + 4, y + Math.max(0, (h - 10) / 2), 10, stateColor, true);
        this.text.render(name, x + 28, nameY, this.ctx.cleanup.fileCount > 0 ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        if (progress) {
            final int barX = x + 28;
            final int barY = y + 31;
            final int barW = Math.max(120, w - 124);
            RenderSystem.fill(barX, barY, barW, 6, AppTheme.BG_3);
            RenderSystem.fillGradientH(barX, barY, barW * (Math.max(0, Math.min(100, this.ctx.cleanup.progress)) / 100f), 6,
                    AppTheme.NEON_DARK.getRed() / 255f, AppTheme.NEON_DARK.getGreen() / 255f, AppTheme.NEON_DARK.getBlue() / 255f, 1f,
                    AppTheme.NEON_LIGHT.getRed() / 255f, AppTheme.NEON_LIGHT.getGreen() / 255f, AppTheme.NEON_LIGHT.getBlue() / 255f, 1f);
        }

        final int tagH = 26;
        final int tagY = y + Math.max(0, (h - tagH) / 2);
        final int sizeY = progress ? nameY : this.centerTextY(tagY, tagH, AppTheme.TEXT_SUBTITLE);
        this.text.render(this.ctx.cleanup.sizeLabel, x + w - 180, sizeY, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        final int tagW = Math.max(84, this.text.width(tag, AppTheme.TEXT_SUBTITLE) + 22);
        final int tagX = x + w - tagW;
        RenderSystem.fill(tagX, tagY, tagW, tagH, AppTheme.alpha(AppTheme.BG_1, 210));
        RenderSystem.rect(tagX, tagY, tagW, tagH, stateColor, 1.5f);
        this.text.render(tag, tagX + 11, this.centerTextY(tagY, tagH, AppTheme.TEXT_SUBTITLE), stateColor, AppTheme.TEXT_SUBTITLE);
    }

    private Color cleanupStateColor() {
        if (this.ctx.cleanup.error) return AppTheme.RED;
        if ("EMPTY".equals(this.ctx.cleanup.state)) return AppTheme.AMBER;
        if (this.ctx.cleanup.working) return AppTheme.NEON_LIGHT;
        if (this.ctx.cleanup.done || "FOUND".equals(this.ctx.cleanup.state)) return AppTheme.GREEN;
        return AppTheme.TEXT_FAINT;
    }

    private String cleanupStateLabel() {
        if (this.ctx.cleanup.stage == 2 && this.ctx.cleanup.working) {
            return Math.max(0, Math.min(100, this.ctx.cleanup.progress)) + "%";
        }
        return this.ctx.cleanup.state;
    }

    private void renderCleanupDialogButtons(final int x, final int y, final int dialogW, final int dialogH) {
        this.cleanupDialogCloseBounds = new Dimension(x + 22, y + dialogH - 68, 170, 48);
        final boolean cancelHover = this.cleanupDialogCloseBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        this.renderDialogButton(this.cleanupDialogCloseBounds, this.ctx.cleanup.done ? "CLOSE" : "CANCEL", "ESC",
                AppTheme.TEXT, AppTheme.STROKE_BRIGHT, cancelHover);

        final String label = this.cleanupPrimaryLabel();
        final int primaryW = Math.max(220, this.text.widthBold(label, AppTheme.TEXT_BUTTON) + this.text.width("ENTER", AppTheme.TEXT_SUBTITLE) + 112);
        this.cleanupDialogPrimaryBounds = new Dimension(x + dialogW - primaryW - 22, y + dialogH - 68, primaryW, 48);
        final boolean primaryEnabled = this.cleanupPrimaryEnabled();
        final boolean primaryHover = primaryEnabled && this.cleanupDialogPrimaryBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        final Color primaryColor = !primaryEnabled ? AppTheme.TEXT_FAINT : this.ctx.cleanup.done ? AppTheme.GREEN : AppTheme.CYAN;
        this.renderDialogButton(this.cleanupDialogPrimaryBounds, label, "ENTER", primaryColor, primaryColor, primaryHover, primaryEnabled);
    }

    private String cleanupPrimaryLabel() {
        return this.ctx.cleanup.stage <= 1 ? "CLEAN CACHE" : "CLOSE";
    }

    private boolean cleanupPrimaryEnabled() {
        if (this.ctx.cleanup.working) return false;
        return this.ctx.cleanup.stage > 1 || (this.ctx.cleanup.fileCount > 0 && !this.ctx.cleanup.error);
    }

    private void closeCleanupDialog() {
        this.ctx.cleanup.visible = false;
        this.rebuildMenu();
        this.refreshCacheSize();
        this.ctx.requestRender();
    }

    private void refreshCacheSize() {
        // COALESCE: SKIP IF A WALK IS ALREADY RUNNING SO RE-ENTERING HOME DOES NOT SPAWN REDUNDANT WALKS
        if (this.cacheWalking) return;
        this.cacheWalking = true;
        ThreadTool.createStarted("WaterMedia-CacheSize", () -> {
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
            this.cacheLabel = Math.max(0, Math.round(bytes / 1024f / 1024f)) + " MB";
            this.cacheWalking = false;
            // REBUILD ON THE RENDER THREAD SO THE CLEANUP ENTRY PICKS UP THE NEW LABEL
            this.ctx.execute(this::rebuildMenu);
        });
    }

    @Override
    protected void onKeyRelease(final int key) {
        if (this.ctx.cleanup.visible) {
            if (key == GLFW_KEY_ESCAPE) {
                this.closeCleanupDialog();
                this.ctx.playSelectionSound();
            } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                if (this.cleanupPrimaryEnabled()) {
                    if (this.ctx.cleanup.stage > 1) {
                        this.closeCleanupDialog();
                    } else {
                        this.navigator.accept(Action.CLEANUP);
                    }
                    this.ctx.playSelectionSound();
                }
            }
            return;
        }

        if (this.ctx.upload.visible) {
            if (this.ctx.upload.stage >= 3) {
                switch (key) {
                    case GLFW_KEY_ESCAPE -> {
                        this.ctx.upload.visible = false;
                        this.ctx.playSelectionSound();
                    }
                    case GLFW_KEY_UP -> this.moveRepoSelection(-1);
                    case GLFW_KEY_DOWN -> this.moveRepoSelection(1);
                    case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.submitRepo(this.selectedRepo(), this.repoSelected);
                }
                return;
            }
            if (key == GLFW_KEY_ESCAPE) {
                this.ctx.upload.visible = false;
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
        // A VISIBLE DIALOG IS MODAL — SUPPRESS TREE HOVER SO THE MENU BEHIND IT DOES NOT SELF-SELECT.
        // THE REPORT LIST DOES NOT HIGHLIGHT ON HOVER; ITS SUBMIT BUTTON READS ctx.mouseX/Y DIRECTLY.
        if (this.ctx.upload.visible || this.ctx.cleanup.visible) return;
        // HOVER FEEDS THE TILE VIEWS, WHICH SELF-SELECT THEIR PANEL/INDEX AND CHIME ON CHANGE
        super.handleMouseMove(mx, my);
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (this.ctx.cleanup.visible) {
            if (this.cleanupDialogCloseBounds.contains(mx, my) || this.cleanupDialogXBounds.contains(mx, my)) {
                this.closeCleanupDialog();
                this.ctx.playSelectionSound();
            } else if (this.cleanupPrimaryEnabled() && this.cleanupDialogPrimaryBounds.contains(mx, my)) {
                if (this.ctx.cleanup.stage > 1) {
                    this.closeCleanupDialog();
                } else {
                    this.navigator.accept(Action.CLEANUP);
                }
                this.ctx.playSelectionSound();
            }
            return;
        }

        if (this.ctx.upload.visible) {
            if (this.uploadDialogCloseBounds.contains(mx, my) || this.uploadDialogXBounds.contains(mx, my)) {
                this.ctx.upload.visible = false;
                this.ctx.playSelectionSound();
                return;
            }
            if (this.ctx.upload.stage >= 3) {
                for (final RepoHit hit: this.repoHits) {
                    if (hit.submit().contains(mx, my)) {
                        this.submitRepo(this.uploadRepoTargets().get(hit.index()), hit.index());
                        return;
                    }
                    if (hit.card().contains(mx, my)) {
                        // CARD BODY ONLY SELECTS; THE SUBMIT BUTTON OPENS THE ISSUE TRACKER
                        if (this.repoSelected != hit.index()) {
                            this.repoSelected = hit.index();
                            this.ctx.playSelectionSound();
                            this.ctx.requestRender();
                        }
                        return;
                    }
                }
                if (this.uploadPrimaryEnabled() && this.uploadDialogPrimaryBounds.contains(mx, my)) {
                    this.submitRepo(this.selectedRepo(), this.repoSelected);
                }
                return;
            }
            if (this.uploadPrimaryEnabled() && this.uploadDialogPrimaryBounds.contains(mx, my)) {
                this.navigator.accept(Action.UPLOAD_LOGS);
                this.ctx.playSelectionSound();
            }
            return;
        }

        // OUTSIDE A DIALOG THE TILE VIEWS OWN HIT-TESTING — LET THE TREE DISPATCH THE CLICK
        super.handleMouseClick(mx, my);
    }

    private void moveSelection(final int delta) {
        if (this.selectedPanel == 0 && !this.actions.isEmpty()) {
            this.selectedAction = Math.max(0, Math.min(this.actions.size() - 1, this.selectedAction + delta));
        } else if (this.selectedPanel == 2) {
            if (delta < 0 && !this.mediaTests.isEmpty()) {
                this.selectedPanel = 1;
                this.selectedMedia = Math.max(0, this.visibleMediaCount - 1);
            } else {
                this.selectedEntertainment = Math.max(0, Math.min(this.entertainment.size() - 1, this.selectedEntertainment + delta));
            }
        } else if (!this.mediaTests.isEmpty()) {
            final int next = this.selectedMedia + delta * 2;
            if (delta > 0 && next >= this.mediaTests.size() && !this.entertainment.isEmpty()) {
                this.selectedPanel = 2;
                this.selectedEntertainment = 0;
            } else {
                this.selectedMedia = Math.max(0, Math.min(this.visibleMediaCount - 1, next));
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
        } else if (this.selectedPanel == 1 && this.selectedMedia < this.visibleMediaCount) {
            this.handleSelect(this.mediaTests.get(this.selectedMedia));
        } else if (this.selectedPanel == 2 && this.selectedEntertainment < this.entertainment.size()) {
            this.handleSelect(this.entertainment.get(this.selectedEntertainment));
        }
    }

    @Override
    public void handleScroll(final double yOffset) {
        if (this.ctx.upload.visible && this.ctx.upload.stage >= 3) {
            final int mods = this.uploadRepoTargets().size() - 1;
            final int max = Math.max(0, mods - this.repoVisibleRows);
            this.repoScrollOffset = Math.max(0, Math.min(max, this.repoScrollOffset - (int) Math.signum(yOffset)));
            this.ctx.requestRender();
        }
    }

    @Override
    public String instructions() {
        if (this.ctx.upload.visible) {
            return this.ctx.upload.stage >= 3
                    ? "UP/DOWN: Repository | ENTER: Submit | ESC: Close"
                    : "ENTER: Continue | ESC: Cancel";
        }
        if (this.ctx.cleanup.visible) {
            return "ENTER: Continue | ESC: Close";
        }
        return "ARROWS: Navigate | ENTER: Select | ESC: Exit";
    }

    // ==========================================================================
    // CONTENT VIEW TREE — THE MENU GRID PORTED ONTO THE RETAINED VIEW FRAMEWORK
    // ==========================================================================

    // FIXED TWO-PANEL GRID: THE ACTION COLUMN ON THE LEFT, THE MEDIA-TILE GRID (PLUS THE ENTERTAINMENT
    // ROWS) ON THE RIGHT. GEOMETRY MIRRORS THE LEGACY render() EXACTLY, INCLUDING THE visibleMediaCount
    // OVERFLOW RULE (TILES THAT DO NOT FIT ARE HIDDEN, SO THEY ARE NEITHER DRAWN NOR HIT-TESTED) AND THE
    // ENTERTAINMENT-FITS CHECK. THE SECTION HEADS AND THE UPLOAD TOOLTIP ARE PAINTED HERE.
    private final class HomeBody extends ViewGroup<HomeBody> {

        private static final int TILE_H = 94;
        private final List<ActionTile> actionViews = new ArrayList<>();
        private final List<MediaTile> mediaViews = new ArrayList<>();
        private final List<EntertainmentTile> entViews = new ArrayList<>();
        private int leftW;
        private int rightXRel;
        private int rightW;
        private int tileW;
        private int rightXAbs;
        private int entYAbs;
        private boolean entHeadVisible;

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final int gap = 18;
            final int colGap = 10;
            this.leftW = Math.max(330, (innerAvailWidth - gap) / 2);
            this.rightXRel = this.leftW + gap;
            this.rightW = innerAvailWidth - this.rightXRel;
            this.tileW = Math.max(160, (this.rightW - colGap) / 2);
            for (final ActionTile tile: this.actionViews) {
                tile.size(this.leftW, 56);
                tile.measure(this.leftW, 56);
            }
            for (final MediaTile tile: this.mediaViews) {
                tile.size(this.tileW, TILE_H);
                tile.measure(this.tileW, TILE_H);
            }
            for (final EntertainmentTile tile: this.entViews) {
                tile.size(this.rightW, 72);
                tile.measure(this.rightW, 72);
            }
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int colGap = 10;
            final int contentH = this.measuredHeight;
            int rowY = this.top + 36;
            for (final ActionTile tile: this.actionViews) {
                tile.layout(this.left, rowY);
                rowY += 64;
            }
            this.rightXAbs = this.left + this.rightXRel;
            int fit = this.mediaViews.size();
            for (int i = 0; i < this.mediaViews.size(); i++) {
                final int col = i % 2;
                final int row = i / 2;
                final int tx = this.rightXAbs + col * (this.tileW + colGap);
                final int ty = this.top + 36 + row * (TILE_H + 10);
                // TILES THAT OVERFLOW THE PANEL ARE HIDDEN — NEITHER DRAWN NOR HIT-TESTED
                if (ty + TILE_H > this.top + contentH) {
                    fit = i;
                    break;
                }
                this.mediaViews.get(i).visible(true).layout(tx, ty);
            }
            for (int i = fit; i < this.mediaViews.size(); i++) this.mediaViews.get(i).visible(false);
            HomeScreen.this.visibleMediaCount = fit;

            this.entHeadVisible = false;
            if (!this.entViews.isEmpty()) {
                final int mediaRows = Math.max(1, (this.mediaViews.size() + 1) / 2);
                this.entYAbs = this.top + 36 + mediaRows * (TILE_H + 10) + 24;
                if (this.entYAbs + 112 <= this.top + contentH) {
                    this.entHeadVisible = true;
                    int entRowY = this.entYAbs + 36;
                    for (final EntertainmentTile tile: this.entViews) {
                        tile.visible(true).layout(this.rightXAbs, entRowY);
                        entRowY += 82;
                    }
                } else {
                    for (final EntertainmentTile tile: this.entViews) tile.visible(false);
                }
            }
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            uploadTooltipAnchor = null;
            AppChrome.sectionHead(text, "Actions", actions.size() + " available", this.left, this.top);
            AppChrome.sectionHead(text, "Media tests", mediaTests.size() + " categories", this.rightXAbs, this.top);
            if (this.entHeadVisible) {
                AppChrome.sectionHead(text, "Entertaiment", entertainment.size() + " available", this.rightXAbs, this.entYAbs);
            }
            super.onDraw(canvas); // TILES
            // THE UPLOAD-LOGS TOOLTIP IS ANCHORED TO ITS ACTION TILE (SET DURING THE TILE DRAW) AND SITS ON TOP
            if (uploadTooltipAnchor != null) renderUploadLogsTooltip(uploadTooltipAnchor);
        }
    }

    // ONE ACTION ROW — PORTS THE LEGACY drawAction ONTO ITS OWN BOX AND READS THE SCREEN'S selectedPanel/
    // selectedAction. HOVER SELF-SELECTS (CHIMING ON CHANGE); CLICK ROUTES THROUGH handleSelect (WHICH NO-OPS
    // FOR DISABLED ACTIONS). DISABLED ACTIONS STAY HOVERABLE/CLICKABLE, EXACTLY LIKE THE LEGACY MENU.
    private final class ActionTile extends View<ActionTile> {

        private final int index;

        private ActionTile(final int index) {
            this.index = index;
            this.onHover(v -> {
                if (selectedPanel != 0 || selectedAction != this.index) {
                    selectedPanel = 0;
                    selectedAction = this.index;
                    HomeScreen.this.ctx.playSelectionSound();
                }
            });
            this.onClick(v -> handleSelect(actions.get(this.index)));
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = actions.get(this.index);
            final boolean selected = selectedPanel == 0 && selectedAction == this.index;
            final Dimension b = new Dimension(this.left, this.top, this.measuredWidth, this.measuredHeight);
            final boolean enabled = actionEnabled(entry);
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
            PixelIcon.draw(actionIcon(entry.action()), b.x() + 14, b.y() + 17, 18, accent);
            text.renderBold(entry.label().toUpperCase(), b.x() + 48, centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_BUTTON), textColor, AppTheme.TEXT_BUTTON);
            final int hintW = text.width(entry.meta(), AppTheme.TEXT_BODY) + 14;
            RenderSystem.fill(b.right() - hintW - 12, b.y() + 17, hintW, 22, AppTheme.alpha(AppTheme.BG_1, 180));
            RenderSystem.rect(b.right() - hintW - 12, b.y() + 17, hintW, 22, AppTheme.STROKE, 1f);
            text.render(entry.meta(), b.right() - hintW - 5, centerTextY(b.y() + 17, 22, AppTheme.TEXT_BODY), AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
            if (entry.action() == Action.UPLOAD_LOGS && selected && !HomeScreen.this.ctx.upload.visible) uploadTooltipAnchor = b;
        }
    }

    // ONE MEDIA-TEST TILE — PORTS THE LEGACY drawMediaTile.
    private final class MediaTile extends View<MediaTile> {

        private final int index;

        private MediaTile(final int index) {
            this.index = index;
            this.onHover(v -> {
                if (selectedPanel != 1 || selectedMedia != this.index) {
                    selectedPanel = 1;
                    selectedMedia = this.index;
                    HomeScreen.this.ctx.playSelectionSound();
                }
            });
            this.onClick(v -> handleSelect(mediaTests.get(this.index)));
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = mediaTests.get(this.index);
            final boolean selected = selectedPanel == 1 && selectedMedia == this.index;
            final Dimension b = new Dimension(this.left, this.top, this.measuredWidth, this.measuredHeight);
            final Color folderColor = categoryColor(entry.groupIndex(), 0);
            final Color titleColor = folderColor;
            final Color accent = selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT;
            if (selected) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, AppTheme.NEON, 0.28f);
            RenderSystem.fill(b.x(), b.y(), b.width(), b.height(),
                    selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
            RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), accent, 2f);
            PixelIcon.draw("folder", b.x() + 14, b.y() + 15, 18, folderColor);
            text.renderBold(text.truncateToWidth(entry.label().toUpperCase(), b.width() - 62, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD),
                    b.x() + 42, centerBoldTextY(b.y() + 15, 18, AppTheme.TEXT_BUTTON), titleColor, AppTheme.TEXT_BUTTON);
            text.render((entry.meta() + " - click to load").toUpperCase(Locale.ROOT), b.x() + 14, b.y() + 50, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        }
    }

    // ONE ENTERTAINMENT ROW — PORTS THE LEGACY drawEntertainmentTile.
    private final class EntertainmentTile extends View<EntertainmentTile> {

        private final int index;

        private EntertainmentTile(final int index) {
            this.index = index;
            this.onHover(v -> {
                if (selectedPanel != 2 || selectedEntertainment != this.index) {
                    selectedPanel = 2;
                    selectedEntertainment = this.index;
                    HomeScreen.this.ctx.playSelectionSound();
                }
            });
            this.onClick(v -> handleSelect(entertainment.get(this.index)));
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = entertainment.get(this.index);
            final boolean selected = selectedPanel == 2 && selectedEntertainment == this.index;
            final Dimension b = new Dimension(this.left, this.top, this.measuredWidth, this.measuredHeight);
            final Color accent = selected ? AppTheme.GREEN : AppTheme.NEON_LIGHT;
            if (selected) RenderSystem.glowRect(b.x(), b.y(), b.width(), b.height(), 0f, AppTheme.GREEN, 0.30f);
            RenderSystem.fill(b.x(), b.y(), b.width(), b.height(),
                    selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
            RenderSystem.rect(b.x(), b.y(), b.width(), b.height(), selected ? AppTheme.GREEN : AppTheme.STROKE_BRIGHT, 2f);
            final String label = entry.label().toUpperCase(Locale.ROOT);
            final int iconSize = 34;
            final int gap = 18;
            final int labelW = text.widthBold(label, AppTheme.TEXT_DISPLAY);
            final int groupW = iconSize + gap + labelW;
            final int groupX = b.x() + Math.max(0, (b.width() - groupW) / 2);
            PixelIcon.draw("tv", groupX, b.y() + Math.max(0, (b.height() - iconSize) / 2), iconSize, accent);
            text.renderBold(label, groupX + iconSize + gap, centerBoldTextY(b.y(), b.height(), AppTheme.TEXT_DISPLAY), accent, AppTheme.TEXT_DISPLAY);
        }
    }

    private record MenuEntry(String label, String meta, Action action, int groupIndex) {
    }

    private record RepoTarget(String name, String slug, String url, Color accent) {
    }

    private record RepoHit(int index, Dimension card, Dimension submit) {
    }
}
