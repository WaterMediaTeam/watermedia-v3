package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Badge;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ProgressBar;
import org.watermedia.bootstrap.app.element.StatusSquare;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.Theme;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Main home screen with grid-based menu options grouped by sections, fully on the retained view tree:
 * the menu grid is an element tree and the upload-logs / cleanup dialogs are {@link Dialog}s living on
 * the screen overlay, bound every frame to {@code ctx.upload} / {@code ctx.cleanup}. All flow logic
 * stays in the app ({@code navigateAction}); this screen only reflects state and fires actions.
 */
public class HomeScreen extends Screen {

    public enum Action {
        OPEN_MULTIMEDIA, UPLOAD_LOGS, CLEANUP,
        SETTINGS,
        REGION_SELECTOR,
        MRL_SELECTOR, PLAYER,
        EXIT, BACK
    }

    private static final int UPLOAD_ROW_H = 52;
    private static final int UPLOAD_ROW_DETAIL_H = 58;
    private static final int UPLOAD_PANEL_PAD = 14;
    private static final int CLEANUP_ROW_H = 58;
    private static final int REPO_ROW_H = 56;
    // THREE MOD CARDS PLUS THE TWO GAPS — THE SAME VIEWPORT THE FIXED-HEIGHT LEGACY DIALOG SHOWED
    private static final int REPO_LIST_H = REPO_ROW_H * 3 + 8 * 2;

    // ACTION TILE FILLS (PRE-BAKED FROM THE LEGACY FLOAT FILLS)
    private static final Color TILE_BG_DISABLED = new Color(15, 20, 36, 148);
    private static final Color TILE_BG_EXIT = new Color(51, 10, 20, 235);
    private static final Color TILE_BG_EXIT_SEL = new Color(66, 18, 28, 235);
    private static final Color TILE_BG_SEL = new Color(AppTheme.BG_2.getRed(), 34, 66, 235);

    // ACCENT PALETTES — HOISTED SO THE PER-TILE/PER-CARD DRAW PATHS NEVER REALLOCATE THE ARRAY
    private static final Color[] CATEGORY_PALETTE = {AppTheme.NEON, AppTheme.CYAN, AppTheme.AMBER, AppTheme.GREEN, AppTheme.NEON_LIGHT};
    private static final Color[] REPO_PALETTE = {AppTheme.AMBER, AppTheme.CYAN, AppTheme.NEON_LIGHT, AppTheme.NEON};

    // CACHED, REFERENCE-STABLE KEYBIND LISTS — LET KeybindsBar SKIP ITS REBUILD ON THE STEADY PER-FRAME PATH
    private static final List<Keybind> KEYS_UPLOAD_REPORT = List.of(
            new Keybind("UP/DOWN", "Repository"), new Keybind("ENTER", "Submit"), new Keybind("ESC", "Close"));
    private static final List<Keybind> KEYS_UPLOAD = List.of(
            new Keybind("ENTER", "Continue"), new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_CLEANUP = List.of(
            new Keybind("ENTER", "Continue"), new Keybind("ESC", "Close"));
    private static final List<Keybind> KEYS_MENU = List.of(
            new Keybind("ARROWS", "Navigate"), new Keybind("ENTER", "Select"), new Keybind("ESC", "Exit"));

    private record MenuEntry(String label, String meta, Action action, int groupIndex) {
    }

    private record RepoTarget(String name, String slug, String url, Color accent) {
    }

    private final Consumer<Action> navigator;
    private final List<MenuEntry> actions = new ArrayList<>();
    private final List<MenuEntry> mediaTests = new ArrayList<>();
    private final List<MenuEntry> entertainment = new ArrayList<>();
    private int selectedPanel;
    private int selectedAction;
    private int selectedMedia;
    // NUMBER OF MEDIA TILES THAT ACTUALLY FIT/ARE LAID OUT THIS FRAME; KEYBOARD SELECTION IS CLAMPED TO IT
    // SO ARROWS/ENTER CANNOT LAND ON AN OVERFLOWING TILE THAT IS NEITHER HIGHLIGHTED NOR CLICKABLE
    private int visibleMediaCount;
    private int selectedEntertainment;

    // RETAINED TREE — BUILT ONCE, BOUND TO LIVE STATE EVERY FRAME IN onUpdate
    private HomeBody grid;
    private Dialog uploadDialog;
    private Dialog cleanupDialog;
    private ListView<AppContext.UploadFileEntry> fileList;
    private Parent filesPanel;
    private ReportPane reportPane;
    private ListView<RepoTarget> repoList;
    private Text noMods;
    private Button uploadCancel;
    private Button uploadPrimary;
    private Button cleanupCancel;
    private Button cleanupPrimary;
    private List<AppContext.UploadFileEntry> uploadRows = List.of();
    private List<RepoTarget> repoTargets = List.of();
    private int repoSelected;

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
        super.onEnter(); // BUILDS THE TREE ON FIRST ENTRY, AFTER THE MENU LISTS EXIST
        this.refreshCacheSize();
    }

    private void rebuildMenu() {
        this.actions.clear();
        this.mediaTests.clear();
        this.entertainment.clear();

        this.actions.add(new MenuEntry("Play media", "ENTER", Action.OPEN_MULTIMEDIA, -1));
        this.actions.add(new MenuEntry("Upload Logs", AppContext.IN_MODS ? "U" : "LOCKED", Action.UPLOAD_LOGS, -1));
        this.actions.add(new MenuEntry("Cleanup cache", this.cacheLabel, Action.CLEANUP, -1));
        // TODO: SETTINGS IS STILL WIP — KEEP IT DEBUG-ONLY UNTIL THE MENU IS PRODUCTION-READY
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

        // CLAMP THE KEYBOARD SELECTION TO THE FRESH LISTS (M-10 COMPANION: NEVER POINT AT A GONE TILE)
        this.selectedAction = Math.max(0, Math.min(this.selectedAction, this.actions.size() - 1));
        this.selectedMedia = Math.max(0, Math.min(this.selectedMedia, Math.max(0, this.mediaTests.size() - 1)));
        this.selectedEntertainment = Math.max(0, Math.min(this.selectedEntertainment, Math.max(0, this.entertainment.size() - 1)));
        if (this.selectedPanel == 1 && this.mediaTests.isEmpty()) this.selectedPanel = 0;
        if (this.selectedPanel == 2 && this.entertainment.isEmpty()) this.selectedPanel = 0;

        // REBUILD THE TILE ELEMENTS SO THEY MATCH THE CURRENT MENU ENTRIES
        if (this.grid != null) this.grid.sync();
    }

    // ==========================================================================
    // TREE CONSTRUCTION
    // ==========================================================================

    @Override
    protected Element<?> build() {
        this.grid = new HomeBody();
        this.grid.sync();
        final Parent column = Parent.column()
                .size(MAX_PARENT, MAX_PARENT)
                .add(new Header().name("Multimedia API").sub("main menu").right("v" + WaterMedia.VERSION))
                // SAME CONTENT RECT AS THE LEGACY SCREEN: 22px SIDES, 20px BELOW THE HEADER, 10px ABOVE THE FOOTER
                .add(this.grid.size(MAX_PARENT, MAX_PARENT).padding(new Spacing(20, 22, 10, 22)));

        // BOTH DIALOGS STAY MOUNTED ON THE OVERLAY; onUpdate ONLY TOGGLES THEIR VISIBILITY
        this.uploadDialog = this.buildUploadDialog();
        this.cleanupDialog = this.buildCleanupDialog();
        this.overlay().add(this.uploadDialog.visible(false));
        this.overlay().add(this.cleanupDialog.visible(false));
        return column;
    }

    private Dialog buildUploadDialog() {
        final StageStrip strip = new StageStrip(new String[]{"SCAN", "UPLOAD", "ISSUE"},
                step -> this.ctx.upload.stage > step || this.ctx.upload.done || (step == 2 && this.ctx.upload.uploadsDone),
                step -> this.ctx.upload.stage == step && !this.ctx.upload.done);

        this.fileList = new ListView<AppContext.UploadFileEntry>()
                .rowFactory(this::uploadRow)
                .selectionColor(null)
                .hoverColor(null);
        this.fileList.width(MAX_PARENT);
        this.filesPanel = Parent.column()
                .padding(new Spacing(UPLOAD_PANEL_PAD, 18, UPLOAD_PANEL_PAD, 18))
                .add(this.fileList);
        this.filesPanel.background(AppTheme.alpha(AppTheme.BG_2, 164))
                .border(AppTheme.STROKE_BRIGHT, 1f)
                .width(MAX_PARENT);

        // STAGE 3 — REPORT PANE: CLIPBOARD STRIP, WATERMEDIA HERO, SUSPECTED-MOD LIST WITH SUBMIT BUTTONS
        this.repoList = new ListView<RepoTarget>()
                .rowFactory((repo, i) -> new RepoCard(repo, i + 1))
                .rowHeight(REPO_ROW_H)
                .spacing(8)
                .selectionColor(null)
                .hoverColor(null)
                .scrollbarWidth(4);
        this.repoList.size(MAX_PARENT, REPO_LIST_H).margin(new Spacing(6, 0, 0, 0));
        this.noMods = new Text("No known suspect mods detected in this instance — submit to WaterMedia above.")
                .color(AppTheme.TEXT_FAINT);
        this.noMods.width(MAX_PARENT).margin(new Spacing(10, 0, 0, 0)).visible(false);
        this.reportPane = new ReportPane();
        this.reportPane.width(MAX_PARENT);
        this.reportPane
                .add(new CopyStrip().size(MAX_PARENT, 30))
                .add(new RepoHero().size(MAX_PARENT, 104).margin(new Spacing(14, 0, 0, 0)))
                .add(Parent.row().spacing(14)
                        .add(new Text("SUSPECTED MODS").bold(true).scale(AppTheme.TEXT_BUTTON).color(AppTheme.NEON))
                        .add(new Text("SUBMIT TO THE MATCHING REPOSITORY").scale(AppTheme.TEXT_SUBTITLE)
                                .color(AppTheme.TEXT_FAINT).gravity(Gravity.CENTER))
                        .margin(new Spacing(16, 0, 0, 0)))
                .add(this.repoList)
                .add(this.noMods);

        this.uploadCancel = new Button("CANCEL").subText("ESC")
                .accent(AppTheme.STROKE_BRIGHT).textColor(AppTheme.TEXT);
        this.uploadCancel.size(170, Theme.BUTTON_LG).onClick(b -> this.closeUpload());
        this.uploadPrimary = new Button().subText("ENTER");
        this.uploadPrimary.height(Theme.BUTTON_LG).onClick(b -> this.uploadPrimaryAction());

        return new Dialog()
                .title("UPLOAD LOG FILES")
                // STEPPER LIVES IN THE DIALOG HEADER BAND, BELOW THE TITLE BAND — SEPARATED BY ITS OWN HAIRLINE
                .header(strip)
                .content(this.filesPanel)
                .content(this.reportPane)
                .onDismiss(this::closeUpload)
                .onPrimary(this::uploadPrimaryAction)
                .actions(this.uploadCancel, this.uploadPrimary);
    }

    private Dialog buildCleanupDialog() {
        final StageStrip strip = new StageStrip(new String[]{"SCAN", "CLEAN"},
                step -> this.ctx.cleanup.stage > step || (step == 2 && this.ctx.cleanup.done),
                step -> this.ctx.cleanup.stage == step && !this.ctx.cleanup.done);

        final FileRow row = new FileRow();
        row.pip.status(() -> {
            if (this.ctx.cleanup.error) return StatusSquare.Status.ERROR;
            if ("EMPTY".equals(this.ctx.cleanup.state)) return StatusSquare.Status.WARN;
            if (this.ctx.cleanup.working) return StatusSquare.Status.PENDING;
            if (this.ctx.cleanup.done || "FOUND".equals(this.ctx.cleanup.state)) return StatusSquare.Status.OK;
            return StatusSquare.Status.OFF;
        });
        row.binder = r -> {
            final int count = this.ctx.cleanup.fileCount;
            r.twoLine = this.ctx.cleanup.stage == 2 && this.ctx.cleanup.working;
            r.name.text(count == 1 ? "1 FILE" : count + " FILES")
                    .color(count > 0 ? AppTheme.TEXT : AppTheme.TEXT_SOFT);
            r.url.visible(false);
            r.bar.visible(r.twoLine)
                    .progress(Math.max(0, Math.min(100, this.ctx.cleanup.progress)) / 100f);
            r.size.text(this.ctx.cleanup.sizeLabel);
            r.tag.label(this.cleanupStateLabel()).color(this.cleanupStateColor());
        };
        row.size(MAX_PARENT, CLEANUP_ROW_H);
        final Parent panel = Parent.column()
                .padding(new Spacing(UPLOAD_PANEL_PAD, 18, UPLOAD_PANEL_PAD, 18))
                .add(row);
        panel.background(AppTheme.alpha(AppTheme.BG_2, 164))
                .border(AppTheme.STROKE_BRIGHT, 1f)
                .width(MAX_PARENT);

        this.cleanupCancel = new Button("CANCEL").subText("ESC")
                .accent(AppTheme.STROKE_BRIGHT).textColor(AppTheme.TEXT);
        this.cleanupCancel.size(170, Theme.BUTTON_LG).onClick(b -> this.dismissCleanup());
        this.cleanupPrimary = new Button().subText("ENTER");
        this.cleanupPrimary.height(Theme.BUTTON_LG).onClick(b -> this.cleanupPrimaryAction());

        return new Dialog()
                .title("CLEAN CACHE")
                // STEPPER LIVES IN THE DIALOG HEADER BAND, BELOW THE TITLE BAND — SEPARATED BY ITS OWN HAIRLINE
                .header(strip)
                .content(panel)
                .onDismiss(this::dismissCleanup)
                .onPrimary(this::cleanupPrimaryAction)
                .actions(this.cleanupCancel, this.cleanupPrimary);
    }

    // ==========================================================================
    // FRAME BINDING — PULLS ctx.upload / ctx.cleanup INTO THE RETAINED DIALOGS
    // ==========================================================================

    @Override
    protected void onUpdate() {
        if (this.uploadDialog == null) return; // TREE NOT BUILT YET

        final var up = this.ctx.upload;
        final boolean report = up.stage >= 3;
        if (this.uploadDialog.visible() != up.visible || this.cleanupDialog.visible() != this.ctx.cleanup.visible) {
            this.invalidate();
        }

        this.uploadDialog.visible(up.visible)
                .title(up.done ? "SUCCESS" : "UPLOAD LOG FILES")
                .accent(up.done ? AppTheme.GREEN : AppTheme.CYAN)
                .panelWidth(Math.min(report ? 900 : 890, this.ctx.logicalWidth() - 48));
        this.filesPanel.visible(!report);
        this.reportPane.visible(report);

        if (up.visible && !report) {
            final int rowH = up.stage >= 2 ? UPLOAD_ROW_DETAIL_H : UPLOAD_ROW_H;
            this.fileList.rowHeight(rowH);
            final List<AppContext.UploadFileEntry> rows = this.visibleUploadFiles();
            // ROWS ARE REBUILT ONLY WHEN MEMBERSHIP CHANGES; ROW CONTENT BINDS ITSELF EVERY FRAME
            if (!rows.equals(this.uploadRows)) {
                this.uploadRows = rows;
                this.fileList.items(rows);
            }
            // AN EMPTY SCAN KEEPS ONE BLANK ROW OF PANEL HEIGHT, LIKE THE LEGACY PANEL DID
            this.fileList.height(rows.isEmpty() ? rowH : WRAP_CONTENT);
        }

        if (up.visible && report) {
            final List<RepoTarget> targets = this.uploadRepoTargets();
            if (!targets.equals(this.repoTargets)) {
                this.repoTargets = targets;
                this.repoList.items(targets.subList(1, targets.size()));
            }
            final boolean mods = targets.size() > 1;
            this.repoList.visible(mods);
            this.noMods.visible(!mods);
        } else if (!this.repoTargets.isEmpty()) {
            // OUTSIDE THE REPORT STAGE THE REPO PICKER STATE IS DORMANT — RESET SO IT STARTS FRESH
            this.repoTargets = List.of();
            this.repoSelected = 0;
            this.repoList.items(List.of()).selection(-1);
        }

        this.uploadCancel.label(up.done ? "CLOSE" : "CANCEL");
        final boolean upEnabled = this.uploadPrimaryEnabled();
        final String upLabel = this.uploadPrimaryLabel();
        final Color upColor = !upEnabled ? AppTheme.TEXT_FAINT : up.done || up.uploadsDone ? AppTheme.GREEN : AppTheme.CYAN;
        this.uploadPrimary.label(upLabel)
                .icon(upLabel.startsWith("UPLOAD") ? "upload" : upLabel.startsWith("OPEN") ? "link" : upLabel.startsWith("GENERATE") ? "check" : "")
                .accent(upColor).textColor(upColor)
                .enabled(upEnabled);

        final var cl = this.ctx.cleanup;
        this.cleanupDialog.visible(cl.visible)
                .title(cl.done ? "CACHE CLEANED" : "CLEAN CACHE")
                .accent(cl.error ? AppTheme.RED : cl.done ? AppTheme.GREEN : AppTheme.CYAN)
                .panelWidth(Math.min(740, this.ctx.logicalWidth() - 48));
        this.cleanupCancel.label(cl.done ? "CLOSE" : "CANCEL");
        final boolean clEnabled = this.cleanupPrimaryEnabled();
        final String clLabel = this.cleanupPrimaryLabel();
        final Color clColor = !clEnabled ? AppTheme.TEXT_FAINT : cl.done ? AppTheme.GREEN : AppTheme.CYAN;
        this.cleanupPrimary.label(clLabel)
                .icon(clLabel.startsWith("CLEAN") ? "broom" : "")
                .accent(clColor).textColor(clColor)
                .enabled(clEnabled);
    }

    // ==========================================================================
    // INPUT / SHELL HOOKS
    // ==========================================================================

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // THE TREE GOES FIRST — A VISIBLE DIALOG IS MODAL AND SWALLOWS EVERYTHING (ESC/ENTER/ARROWS)
        if (super.dispatchKey(key, action)) return true;
        if (action != GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_UP -> this.moveSelection(-1);
            case GLFW_KEY_DOWN -> this.moveSelection(1);
            case GLFW_KEY_LEFT -> this.switchPanel(0);
            case GLFW_KEY_RIGHT -> this.switchPanel(this.mediaTests.isEmpty() && !this.entertainment.isEmpty() ? 2 : 1);
            case GLFW_KEY_U -> {
                final int idx = this.actionIndex(Action.UPLOAD_LOGS);
                if (idx >= 0) {
                    this.selectedPanel = 0;
                    this.selectedAction = idx;
                    this.confirmSelection();
                }
            }
            case GLFW_KEY_S -> {
                // RESOLVE BY ACTION, NOT A HARDCODED SLOT — A MENU REORDER MUST NOT RETARGET S AT ANOTHER ENTRY
                final int idx = this.actionIndex(Action.SETTINGS);
                if (idx >= 0) {
                    this.selectedPanel = 0;
                    this.selectedAction = idx;
                    this.confirmSelection();
                }
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.confirmSelection();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(Action.EXIT);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Keybind> keybinds() {
        if (this.ctx.upload.visible) return this.ctx.upload.stage >= 3 ? KEYS_UPLOAD_REPORT : KEYS_UPLOAD;
        if (this.ctx.cleanup.visible) return KEYS_CLEANUP;
        return KEYS_MENU;
    }

    @Override
    public boolean continuous() {
        return this.ctx.backendsLoading || this.ctx.upload.working || this.ctx.cleanup.working;
    }

    // ==========================================================================
    // MENU BEHAVIOR
    // ==========================================================================

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
            this.ctx.groupMRLs.put(testUri.name(), MediaAPI.mrl(testUri.uri()));
        }
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    private void openCustomTests() {
        if (this.ctx.customTests.isEmpty()) return;
        this.openGroup(new AppContext.URIGroup("CUSTOM",
                this.ctx.customTests.toArray(new AppContext.TestURI[0])));
    }

    private boolean actionEnabled(final MenuEntry entry) {
        if (entry.action() == Action.UPLOAD_LOGS) return AppContext.IN_MODS;
        if (entry.action() == Action.SETTINGS) return WaterMedia.LOGGER.isDebugEnabled();
        return true;
    }

    private int actionIndex(final Action action) {
        for (int i = 0; i < this.actions.size(); i++) {
            if (this.actions.get(i).action() == action) return i;
        }
        return -1;
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

    private static Color categoryColor(final int index) {
        return CATEGORY_PALETTE[Math.floorMod(index, CATEGORY_PALETTE.length)];
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
        this.invalidate();
    }

    private void switchPanel(final int panel) {
        if (this.selectedPanel != panel) {
            this.selectedPanel = panel;
            this.ctx.playSelectionSound();
            this.invalidate();
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

    private void refreshCacheSize() {
        // COALESCE: SKIP IF A WALK IS ALREADY RUNNING SO RE-ENTERING HOME DOES NOT SPAWN REDUNDANT WALKS
        if (this.cacheWalking) return;
        this.cacheWalking = true;
        ThreadTool.createStarted("WaterMedia-CacheSize", () -> {
            final Path cache = WaterMedia.tmp().resolve("cache");
            long bytes = 0L;
            try {
                if (Files.exists(cache)) {
                    try (final var stream = Files.walk(cache)) {
                        bytes = stream.filter(Files::isRegularFile).mapToLong(path -> {
                            try {
                                return Files.size(path);
                            } catch (final IOException ignored) {
                                return 0L;
                            }
                        }).sum();
                    }
                }
            } catch (final Exception ignored) {
                // Files.walk WRAPS TRAVERSAL FAILURES IN UncheckedIOException — CATCH BROADLY SO THE finally ALWAYS RUNS
            } finally {
                // RESET IN finally: A THROWN WALK MUST NOT LEAVE cacheWalking LATCHED (WHICH WOULD FREEZE THE LABEL FOREVER)
                this.cacheLabel = Math.max(0, Math.round(bytes / 1024f / 1024f)) + " MB";
                this.cacheWalking = false;
                // REBUILD ON THE RENDER THREAD SO THE CLEANUP ENTRY PICKS UP THE NEW LABEL
                this.ctx.execute(this::rebuildMenu);
            }
        });
    }

    // ==========================================================================
    // UPLOAD DIALOG BEHAVIOR — STATE LIVES IN ctx.upload, FLOW IN navigateAction
    // ==========================================================================

    private void closeUpload() {
        this.ctx.upload.visible = false;
        this.ctx.playSelectionSound();
        this.invalidate();
    }

    private void uploadPrimaryAction() {
        if (this.ctx.upload.stage >= 3) {
            this.submitRepo(this.selectedRepo(), this.repoSelected);
            return;
        }
        if (!this.uploadPrimaryEnabled()) return;
        this.navigator.accept(Action.UPLOAD_LOGS);
        this.ctx.playSelectionSound();
    }

    private String uploadPrimaryLabel() {
        if (this.ctx.upload.stage <= 1) return "UPLOAD TO MCLO.GS";
        if (this.ctx.upload.stage == 2) return "GENERATE REPORT";
        return "OPEN " + this.selectedRepo().name().toUpperCase(Locale.ROOT);
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

    private List<RepoTarget> uploadRepoTargets() {
        final String wmUrl = this.ctx.upload.issueUrl != null && this.ctx.upload.issueUrl.startsWith("http")
                ? this.ctx.upload.issueUrl
                : "https://github.com/WaterMediaTeam/watermedia/issues/new";
        final List<RepoTarget> repos = new ArrayList<>();
        repos.add(new RepoTarget("WaterMedia", "WaterMediaTeam/watermedia", wmUrl, AppTheme.GREEN));
        // ONLY LIST SUSPECT MODS WHOSE JAR WAS ACTUALLY FOUND IN THE INSTANCE (SEE WaterMediaApp.scanSuspectMods)
        int idx = 0;
        for (final AppContext.SuspectMod mod: AppContext.SUSPECT_MODS) {
            if (this.ctx.upload.suspectModIds.contains(mod.id())) {
                repos.add(new RepoTarget(mod.name(), mod.slug(), mod.url(), REPO_PALETTE[idx % REPO_PALETTE.length]));
            }
            idx++;
        }
        return repos;
    }

    private RepoTarget selectedRepo() {
        final List<RepoTarget> repos = this.repoTargets.isEmpty() ? this.uploadRepoTargets() : this.repoTargets;
        return repos.get(Math.max(0, Math.min(repos.size() - 1, this.repoSelected)));
    }

    private void submitRepo(final RepoTarget repo, final int index) {
        this.repoSelected = index;
        this.ctx.upload.repoUrl = repo.url();
        this.navigator.accept(Action.UPLOAD_LOGS);
        this.ctx.playSelectionSound();
    }

    private void moveRepoSelection(final int delta) {
        final List<RepoTarget> targets = this.repoTargets.isEmpty() ? this.uploadRepoTargets() : this.repoTargets;
        this.repoSelected = Math.max(0, Math.min(targets.size() - 1, this.repoSelected + delta));
        // DRIVE THE LIST SELECTION SO THE PICKED MOD CARD (TARGETS 1..n) STAYS INSIDE THE SCROLL VIEWPORT
        if (this.repoSelected >= 1) {
            this.repoList.moveSelection(this.repoSelected - 1 - Math.max(0, this.repoList.selectedIndex()));
        } else {
            this.repoList.selection(-1);
        }
        this.ctx.playSelectionSound();
        this.invalidate();
    }

    // ROW FACTORY FOR THE UPLOAD FILE LIST — EACH ROW SELF-BINDS TO ITS LIVE ENTRY EVERY FRAME
    private Element<?> uploadRow(final AppContext.UploadFileEntry entry, final int index) {
        final FileRow row = new FileRow();
        row.pip.status(() -> uploadStatus(entry));
        row.binder = r -> {
            final int stage = this.ctx.upload.stage;
            final boolean errored = "FAILED".equals(entry.state) || "READ ERROR".equals(entry.state);
            final boolean linked = stage >= 3 && entry.uploaded && !entry.url.isBlank();
            final int pct = Math.max(0, Math.min(100, entry.progress));
            r.twoLine = linked || (stage == 2 && entry.present && entry.valid && !errored);
            r.name.text(entry.name).color(entry.present ? AppTheme.TEXT : AppTheme.TEXT_SOFT);
            r.url.visible(linked).text(entry.url);
            r.bar.visible(stage == 2 && entry.present && !linked).progress(pct / 100f);
            r.size.text(entry.sizeLabel);
            final String tag = stage == 2 && errored
                    ? "ERROR"
                    : stage == 2 && entry.present && entry.valid && !entry.uploaded
                    ? pct + "%"
                    : entry.state;
            r.tag.label(tag).color(uploadStateColor(entry));
            r.separator = index < this.uploadRows.size() - 1;
        };
        return row;
    }

    private static Color uploadStateColor(final AppContext.UploadFileEntry entry) {
        if (entry.uploaded || "READ OK".equals(entry.state)) return AppTheme.GREEN;
        if ("UPLOADING".equals(entry.state)) return AppTheme.NEON_LIGHT;
        if ("INVALID".equals(entry.state)) return AppTheme.AMBER;
        if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) return AppTheme.RED;
        return entry.present ? AppTheme.TEXT_FAINT : AppTheme.RED;
    }

    private static StatusSquare.Status uploadStatus(final AppContext.UploadFileEntry entry) {
        if (entry.uploaded || "READ OK".equals(entry.state)) return StatusSquare.Status.OK;
        if ("UPLOADING".equals(entry.state)) return StatusSquare.Status.PENDING;
        if ("INVALID".equals(entry.state)) return StatusSquare.Status.WARN;
        if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) return StatusSquare.Status.ERROR;
        return entry.present ? StatusSquare.Status.OFF : StatusSquare.Status.ERROR;
    }

    // ==========================================================================
    // CLEANUP DIALOG BEHAVIOR — STATE LIVES IN ctx.cleanup, FLOW IN navigateAction
    // ==========================================================================

    private void dismissCleanup() {
        this.closeCleanupDialog();
        this.ctx.playSelectionSound();
    }

    private void cleanupPrimaryAction() {
        if (!this.cleanupPrimaryEnabled()) return;
        if (this.ctx.cleanup.stage > 1) {
            this.closeCleanupDialog();
        } else {
            this.navigator.accept(Action.CLEANUP);
        }
        this.ctx.playSelectionSound();
    }

    private String cleanupPrimaryLabel() {
        return this.ctx.cleanup.stage <= 1 ? "CLEAN CACHE" : "CLOSE";
    }

    private boolean cleanupPrimaryEnabled() {
        if (this.ctx.cleanup.working) return false;
        return this.ctx.cleanup.stage > 1 || (this.ctx.cleanup.fileCount > 0 && !this.ctx.cleanup.error);
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

    private void closeCleanupDialog() {
        this.ctx.cleanup.visible = false;
        this.rebuildMenu();
        this.refreshCacheSize();
        this.invalidate();
    }

    // ==========================================================================
    // MENU GRID — FIXED TWO-PANEL LAYOUT PORTED ONTO THE RETAINED ELEMENT TREE
    // ==========================================================================

    // FIXED TWO-PANEL GRID (ACTIONS LEFT, MEDIA TILES + ENTERTAINMENT RIGHT) WITH THE SECTION HEADS AND
    // UPLOAD TOOLTIP. OVERFLOWING TILES ARE HIDDEN (visibleMediaCount), SO THEY ARE NEITHER DRAWN NOR HIT-TESTED.
    private final class HomeBody extends Group<HomeBody> {

        private static final int TILE_H = 94;
        private final List<ActionTile> actionElements = new ArrayList<>();
        private final List<MediaTile> mediaElements = new ArrayList<>();
        private final List<EntertainmentTile> entElements = new ArrayList<>();
        private UploadTip tip;
        private int leftW;
        private int rightXRel;
        private int rightW;
        private int tileW;
        private int rightXAbs;
        private int entYAbs;
        private boolean entHeadVisible;

        // RECREATES THE TILE ELEMENTS FROM THE CURRENT MENU LISTS (CALLED ON EVERY MENU REBUILD)
        private void sync() {
            this.children.clear();
            this.actionElements.clear();
            this.mediaElements.clear();
            this.entElements.clear();
            for (int i = 0; i < actions.size(); i++) {
                final ActionTile tile = new ActionTile(i);
                this.actionElements.add(tile);
                this.add(tile);
            }
            for (int i = 0; i < mediaTests.size(); i++) {
                final MediaTile tile = new MediaTile(i);
                this.mediaElements.add(tile);
                this.add(tile);
            }
            for (int i = 0; i < entertainment.size(); i++) {
                final EntertainmentTile tile = new EntertainmentTile(i);
                this.entElements.add(tile);
                this.add(tile);
            }
            // TOOLTIP LAST SO IT PAINTS ON TOP OF THE TILES BELOW ITS ANCHOR
            this.tip = new UploadTip();
            this.add(this.tip);
            this.invalidate();
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final int gap = 18;
            final int colGap = 10;
            this.leftW = Math.max(330, (innerAvailWidth - gap) / 2);
            this.rightXRel = this.leftW + gap;
            this.rightW = innerAvailWidth - this.rightXRel;
            this.tileW = Math.max(160, (this.rightW - colGap) / 2);
            for (final ActionTile tile: this.actionElements) {
                tile.size(this.leftW, 56);
                tile.measure(this.leftW, 56);
            }
            for (final MediaTile tile: this.mediaElements) {
                tile.size(this.tileW, TILE_H);
                tile.measure(this.tileW, TILE_H);
            }
            for (final EntertainmentTile tile: this.entElements) {
                tile.size(this.rightW, 72);
                tile.measure(this.rightW, 72);
            }
            if (this.tip != null && this.tip.visible()) this.tip.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int colGap = 10;
            final int x = this.innerLeft();
            final int y = this.innerTop();
            final int contentH = this.innerHeight();
            int rowY = y + 36;
            for (final ActionTile tile: this.actionElements) {
                tile.layout(x, rowY);
                rowY += 64;
            }
            this.rightXAbs = x + this.rightXRel;
            int fit = this.mediaElements.size();
            for (int i = 0; i < this.mediaElements.size(); i++) {
                final int col = i % 2;
                final int row = i / 2;
                final int tx = this.rightXAbs + col * (this.tileW + colGap);
                final int ty = y + 36 + row * (TILE_H + 10);
                // TILES THAT OVERFLOW THE PANEL ARE HIDDEN — NEITHER DRAWN NOR HIT-TESTED
                if (ty + TILE_H > y + contentH) {
                    fit = i;
                    break;
                }
                this.mediaElements.get(i).visible(true).layout(tx, ty);
            }
            for (int i = fit; i < this.mediaElements.size(); i++) this.mediaElements.get(i).visible(false);
            HomeScreen.this.visibleMediaCount = fit;

            this.entHeadVisible = false;
            if (!this.entElements.isEmpty()) {
                final int mediaRows = Math.max(1, (this.mediaElements.size() + 1) / 2);
                this.entYAbs = y + 36 + mediaRows * (TILE_H + 10) + 24;
                if (this.entYAbs + 112 <= y + contentH) {
                    this.entHeadVisible = true;
                    int entRowY = this.entYAbs + 36;
                    for (final EntertainmentTile tile: this.entElements) {
                        tile.visible(true).layout(this.rightXAbs, entRowY);
                        entRowY += 82;
                    }
                } else {
                    for (final EntertainmentTile tile: this.entElements) tile.visible(false);
                }
            }

            // THE TOOLTIP HANGS 8px UNDER ITS ANCHOR TILE (THE ELEMENT BOX INCLUDES THE 10px NOTCH ON TOP)
            if (this.tip != null && this.tip.visible()) {
                final int idx = actionIndex(Action.UPLOAD_LOGS);
                if (idx >= 0 && idx < this.actionElements.size()) {
                    final ActionTile anchor = this.actionElements.get(idx);
                    this.tip.layout(anchor.left(), anchor.top() + anchor.measuredHeight() + 8 - UploadTip.NOTCH_H);
                }
            }
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            this.head(canvas, "Actions", actions.size() + " available", this.innerLeft(), this.innerTop());
            this.head(canvas, "Media tests", mediaTests.size() + " categories", this.rightXAbs, this.innerTop());
            if (this.entHeadVisible) {
                this.head(canvas, "Entertainment", entertainment.size() + " available", this.rightXAbs, this.entYAbs);
            }
            super.onDraw(canvas); // TILES, THEN THE TOOLTIP ON TOP
        }

        // CANVAS PORT OF THE CHROME SECTION HEAD: NEON TICK + BOLD LABEL + FAINT COUNT
        private void head(final Canvas canvas, final String label, final String count, final int x, final int y) {
            final int headY = y + 3;
            final int headH = 21;
            canvas.fill(x, headY, 4, headH, AppTheme.NEON);
            canvas.glow(x, headY, 4, headH, 0f, AppTheme.NEON, 0.16f);
            final String up = label.toUpperCase(Locale.ROOT);
            canvas.text(up, x + 18, headY + Math.max(0, (headH - canvas.textHeight(AppTheme.TEXT_SECTION, true)) / 2),
                    AppTheme.NEON, AppTheme.TEXT_SECTION, true);
            if (count != null) {
                canvas.text(count.toUpperCase(Locale.ROOT), x + 30 + canvas.textWidth(up, AppTheme.TEXT_SECTION, true),
                        headY + Math.max(0, (headH - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                        AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            }
        }
    }

    // ONE ACTION ROW — DECOR (BACKGROUND/BORDER/GLOW) IS BOUND FROM LIVE STATE IN onUpdate, CONTENT IS
    // PAINTED THROUGH THE CANVAS. HOVER SELF-SELECTS (CHIMING ON CHANGE); CLICK ROUTES THROUGH handleSelect
    // (WHICH NO-OPS FOR DISABLED ACTIONS). DISABLED ACTIONS STAY HOVERABLE/CLICKABLE, LIKE THE LEGACY MENU.
    private final class ActionTile extends Element<ActionTile> {

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

        private Color accentColor(final MenuEntry entry, final boolean enabled) {
            return !enabled ? AppTheme.TEXT_FAINT : switch (entry.action()) {
                case OPEN_MULTIMEDIA -> AppTheme.GREEN;
                case EXIT -> AppTheme.RED;
                default -> AppTheme.NEON_LIGHT;
            };
        }

        @Override
        protected void onUpdate() {
            if (this.index >= actions.size()) return;
            final MenuEntry entry = actions.get(this.index);
            final boolean selected = selectedPanel == 0 && selectedAction == this.index;
            final boolean enabled = actionEnabled(entry);
            final Color accent = this.accentColor(entry, enabled);
            this.background(!enabled
                    ? TILE_BG_DISABLED
                    : entry.action() == Action.EXIT
                    ? (selected ? TILE_BG_EXIT_SEL : TILE_BG_EXIT)
                    : selected ? TILE_BG_SEL : AppTheme.alpha(AppTheme.BG_2, 235));
            this.border(!enabled
                    ? AppTheme.STROKE
                    : selected || entry.action() == Action.OPEN_MULTIMEDIA || entry.action() == Action.EXIT
                    ? accent
                    : AppTheme.STROKE_BRIGHT, 2f);
            this.glow(enabled ? selected ? accent : AppTheme.NEON : AppTheme.STROKE_BRIGHT,
                    enabled ? selected ? 0.28f : 0.08f : 0.03f);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = actions.get(this.index);
            final boolean selected = selectedPanel == 0 && selectedAction == this.index;
            final boolean enabled = actionEnabled(entry);
            final Color accent = this.accentColor(entry, enabled);
            final Color textColor = !enabled
                    ? AppTheme.TEXT_FAINT
                    : entry.action() == Action.OPEN_MULTIMEDIA || entry.action() == Action.EXIT
                    ? accent
                    : selected ? accent : AppTheme.TEXT;
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            // EXTRA SELECTION FLARE ON TOP OF THE DECOR GLOW (LEGACY STACKED BOTH)
            if (selected && enabled) canvas.glow(x, y, w, h, 0f, accent, 0.35f);
            PixelIcon.draw(actionIcon(entry.action()), x + 14, y + 17, 18, accent);
            canvas.text(entry.label().toUpperCase(Locale.ROOT), x + 48,
                    y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BUTTON, true)) / 2),
                    textColor, AppTheme.TEXT_BUTTON, true);
            final int hintW = canvas.textWidth(entry.meta(), AppTheme.TEXT_BODY, false) + 14;
            canvas.fill(x + w - hintW - 12, y + 17, hintW, 22, AppTheme.alpha(AppTheme.BG_1, 180));
            canvas.stroke(x + w - hintW - 12, y + 17, hintW, 22, AppTheme.STROKE, 1f);
            canvas.text(entry.meta(), x + w - hintW - 5,
                    y + 17 + Math.max(0, (22 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
        }
    }

    // ONE MEDIA-TEST TILE — DECOR FROM LIVE STATE, FOLDER ICON AND TITLES THROUGH THE CANVAS.
    private final class MediaTile extends Element<MediaTile> {

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
        protected void onUpdate() {
            final boolean selected = selectedPanel == 1 && selectedMedia == this.index;
            this.background(selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
            this.border(selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, 2f);
            this.glow(selected ? AppTheme.NEON : null, selected ? 0.28f : 0f);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = mediaTests.get(this.index);
            final Color folderColor = categoryColor(entry.groupIndex());
            final int x = this.left;
            final int y = this.top;
            PixelIcon.draw("folder", x + 14, y + 15, 18, folderColor);
            final String title = canvas.text().truncateToWidth(entry.label().toUpperCase(Locale.ROOT),
                    this.measuredWidth - 62, AppTheme.TEXT_BUTTON, Font.BOLD);
            canvas.text(title, x + 42,
                    y + 15 + Math.max(0, (18 - canvas.textHeight(AppTheme.TEXT_BUTTON, true)) / 2),
                    folderColor, AppTheme.TEXT_BUTTON, true);
            canvas.text((entry.meta() + " - click to load").toUpperCase(Locale.ROOT), x + 14, y + 50,
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
        }
    }

    // ONE ENTERTAINMENT ROW — DECOR FROM LIVE STATE, CENTERED TV ICON + LABEL THROUGH THE CANVAS.
    private final class EntertainmentTile extends Element<EntertainmentTile> {

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
        protected void onUpdate() {
            final boolean selected = selectedPanel == 2 && selectedEntertainment == this.index;
            this.background(selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
            this.border(selected ? AppTheme.GREEN : AppTheme.STROKE_BRIGHT, 2f);
            this.glow(selected ? AppTheme.GREEN : null, selected ? 0.30f : 0f);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = entertainment.get(this.index);
            final boolean selected = selectedPanel == 2 && selectedEntertainment == this.index;
            final Color accent = selected ? AppTheme.GREEN : AppTheme.NEON_LIGHT;
            final String label = entry.label().toUpperCase(Locale.ROOT);
            final int iconSize = 34;
            final int gap = 18;
            final int labelW = canvas.textWidth(label, AppTheme.TEXT_DISPLAY, true);
            final int groupW = iconSize + gap + labelW;
            final int groupX = this.left + Math.max(0, (this.measuredWidth - groupW) / 2);
            PixelIcon.draw("tv", groupX, this.top + Math.max(0, (this.measuredHeight - iconSize) / 2), iconSize, accent);
            canvas.text(label, groupX + iconSize + gap,
                    this.top + Math.max(0, (this.measuredHeight - canvas.textHeight(AppTheme.TEXT_DISPLAY, true)) / 2),
                    accent, AppTheme.TEXT_DISPLAY, true);
        }
    }

    // UPLOAD-LOGS TOOLTIP — VISIBLE WHILE ITS ACTION TILE IS SELECTED AND THE DIALOG IS CLOSED. IT IS
    // INPUT-TRANSPARENT SO THE TILES UNDERNEATH KEEP THEIR HOVER/CLICK BEHAVIOR, EXACTLY LIKE THE LEGACY
    // PAINTED-ON-TOP VERSION.
    private final class UploadTip extends Element<UploadTip> {

        private static final int BOX_H = 82;
        private static final int NOTCH_H = 10;

        @Override
        protected void onUpdate() {
            final int idx = actionIndex(Action.UPLOAD_LOGS);
            this.visible = idx >= 0 && selectedPanel == 0 && selectedAction == idx && !HomeScreen.this.ctx.upload.visible;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final boolean blocked = !AppContext.IN_MODS;
            final TextRenderer text = HomeScreen.this.ctx.text;
            final String title = blocked ? "NO MC CONTEXT" : "SENDS LOGS TO MCLO.GS";
            final String line1 = blocked ? "Upload logs is blocked outside" : "Reads logs/latest.log and crash reports,";
            final String line2 = blocked ? "the Minecraft mods folder." : "then uploads.";
            final int desiredW = Math.max(text.width(title, AppTheme.TEXT_BUTTON),
                    Math.max(text.width(line1, AppTheme.TEXT_BODY), text.width(line2, AppTheme.TEXT_BODY))) + 24;
            this.contentWidth = Math.min(Math.max(340, desiredW), Math.max(120, innerAvailWidth - 24));
            this.contentHeight = BOX_H + NOTCH_H;
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            return false; // INPUT-TRANSPARENT
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            return false; // INPUT-TRANSPARENT
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean blocked = !AppContext.IN_MODS;
            final String title = blocked ? "NO MC CONTEXT" : "SENDS LOGS TO MCLO.GS";
            final String line1 = blocked ? "Upload logs is blocked outside" : "Reads logs/latest.log and crash reports,";
            final String line2 = blocked ? "the Minecraft mods folder." : "then uploads.";
            final Color color = blocked ? AppTheme.RED : AppTheme.AMBER;
            final int x = this.left;
            final int y = this.top + NOTCH_H;
            final int w = this.measuredWidth;
            canvas.fill(x, y, w, BOX_H, AppTheme.alpha(AppTheme.BG_1, 242));
            canvas.stroke(x, y, w, BOX_H, color, 1f);
            canvas.glow(x, y, w, BOX_H, 0f, color, 0.24f);
            // POINTER NOTCH AS STACKED 2px STEPS (PIXEL-ART STAND-IN FOR THE LEGACY TRIANGLE)
            for (int k = 0; k < NOTCH_H / 2; k++) {
                final int half = Math.round(7f * (NOTCH_H - (2 * k + 1)) / NOTCH_H);
                if (half <= 0) continue;
                canvas.fill(x + 23 - half, y - 2 * (k + 1), half * 2, 2, color);
            }
            canvas.text(title, x + 12, y + 12, color, AppTheme.TEXT_BUTTON, true);
            canvas.text(line1, x + 12, y + 36, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
            canvas.text(line2, x + 12, y + 56, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
        }
    }

    // ==========================================================================
    // DIALOG BUILDING BLOCKS
    // ==========================================================================

    // LABELED STEP STRIP — THE CANVAS PORT OF THE LEGACY DIALOG STEPPER (NUMBERED 30px BOXES WITH LABELS
    // AND CONNECTORS). STEP STATE IS RESOLVED LIVE FROM ctx ON EVERY DRAW VIA THE TWO PREDICATES.
    private final class StageStrip extends Element<StageStrip> {

        private final String[] labels;
        private final IntPredicate complete;
        private final IntPredicate active;

        private StageStrip(final String[] labels, final IntPredicate complete, final IntPredicate active) {
            this.labels = labels;
            this.complete = complete;
            this.active = active;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            int w = 0;
            for (int i = 0; i < this.labels.length; i++) {
                if (i > 0) w += 56;
                w += 42 + HomeScreen.this.ctx.text.width(this.labels[i], AppTheme.TEXT_BODY);
            }
            this.contentWidth = w;
            this.contentHeight = 30;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int y = this.innerTop();
            int x = this.innerLeft();
            for (int i = 0; i < this.labels.length; i++) {
                final int step = i + 1;
                final boolean done = this.complete.test(step);
                final boolean act = this.active.test(step);
                final Color color = done ? AppTheme.GREEN : act ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
                canvas.fill(x, y, 30, 30, AppTheme.alpha(AppTheme.BG_2, 220));
                canvas.stroke(x, y, 30, 30, color, act || done ? 2f : 1f);
                if (done) {
                    PixelIcon.draw("check", x + 8, y + 8, 14, color);
                } else {
                    canvas.text(String.valueOf(step), x + 11, y + 7, color, AppTheme.TEXT_BODY, false);
                }
                canvas.text(this.labels[i], x + 42, y + 8, color, AppTheme.TEXT_BODY, false);
                final int end = x + 42 + canvas.textWidth(this.labels[i], AppTheme.TEXT_BODY, false);
                if (i < this.labels.length - 1) {
                    canvas.fill(end + 10, y + 15, 34, 1, AppTheme.STROKE_BRIGHT);
                }
                x = end + 56;
            }
        }
    }

    // ONE FILE/SUMMARY ROW: STATUS PIP + NAME (+ URL OR PROGRESS BAR UNDERNEATH) + SIZE + STATE BADGE.
    // A binder CONSUMER RE-READS LIVE STATE EVERY FRAME; LAYOUT MIRRORS THE LEGACY ROW METRICS.
    private static final class FileRow extends Group<FileRow> {

        private final StatusSquare pip = new StatusSquare();
        private final Text name = new Text();
        private final Text url = new Text().color(AppTheme.CYAN).scale(AppTheme.TEXT_SUBTITLE);
        private final ProgressBar bar = new ProgressBar().accent(AppTheme.NEON_LIGHT);
        private final Text size = new Text().color(AppTheme.TEXT_FAINT).scale(AppTheme.TEXT_SUBTITLE);
        private final Badge tag = new Badge();
        private Consumer<FileRow> binder;
        private boolean twoLine;
        private boolean separator;

        private FileRow() {
            this.pip.size(10, 10);
            this.url.visible(false);
            this.bar.visible(false);
            this.add(this.pip).add(this.name).add(this.url).add(this.bar).add(this.size).add(this.tag);
        }

        @Override
        protected void onUpdate() {
            if (this.binder != null) this.binder.accept(this);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.pip.measure(10, 10);
            final int nameW = Math.max(40, innerAvailWidth - 28 - 190);
            this.name.width(nameW).measure(nameW, 20);
            final int urlW = Math.max(40, innerAvailWidth - 40);
            this.url.width(urlW).measure(urlW, 16);
            final int barW = Math.max(120, innerAvailWidth - 124);
            this.bar.width(barW).measure(barW, 6);
            this.size.width(140).measure(140, 16);
            this.tag.measure(200, 26);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int x = this.innerLeft();
            final int y = this.innerTop();
            final int w = this.innerWidth();
            final int h = this.innerHeight();
            this.pip.layout(x + 4, y + Math.max(0, (h - 10) / 2));
            // TWO-LINE MODE CENTERS THE NAME IN THE TOP 28px BAND, WITH THE URL/PROGRESS BAR UNDERNEATH
            final int nameY = this.twoLine
                    ? y + Math.max(0, (28 - this.name.measuredHeight()) / 2)
                    : y + Math.max(0, (h - this.name.measuredHeight()) / 2);
            this.name.layout(x + 28, nameY);
            this.url.layout(x + 28, y + 28);
            this.bar.layout(x + 28, y + 31);
            final int sizeY = this.twoLine ? nameY : y + Math.max(0, (h - this.size.measuredHeight()) / 2);
            this.size.layout(x + w - 180, sizeY);
            this.tag.layout(x + w - this.tag.measuredWidth(), y + Math.max(0, (h - this.tag.measuredHeight()) / 2));
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            if (this.separator) {
                // DOTTED HAIRLINE BETWEEN ROWS, SAME CADENCE AS THE LEGACY PANEL
                final int endX = this.innerLeft() + this.innerWidth();
                final int sy = this.top + this.measuredHeight - 1;
                for (int dx = this.innerLeft() + 28; dx < endX; dx += 8) {
                    canvas.fill(dx, sy, 4, 1, AppTheme.alpha(AppTheme.STROKE_BRIGHT, 90));
                }
            }
        }
    }

    // REPORT PANE — HOSTS THE CLIPBOARD STRIP, HERO AND MOD LIST, AND OWNS THE STAGE-3 UP/DOWN KEYS
    // (RUNNING BEFORE THE DIALOG'S OWN ESC/ENTER FALLBACK).
    private final class ReportPane extends Parent {

        private ReportPane() {
            super(Orientation.VERTICAL);
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            if (!this.visible) return false;
            if (action == GLFW_RELEASE && (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN)) {
                moveRepoSelection(key == GLFW_KEY_UP ? -1 : 1);
                return true;
            }
            return super.dispatchKey(key, action);
        }
    }

    // CLIPBOARD CONFIRMATION STRIP — GREEN "COPIED" OR MUTED "UNAVAILABLE", RESOLVED LIVE PER DRAW.
    private final class CopyStrip extends Element<CopyStrip> {

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean copied = HomeScreen.this.ctx.upload.issueCopied;
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(x, y, w, h, AppTheme.alpha(copied ? AppTheme.GREEN : AppTheme.BG_2, copied ? 26 : 164));
            canvas.stroke(x, y, w, h, copied ? AppTheme.GREEN : AppTheme.STROKE_BRIGHT, 1f);
            PixelIcon.draw(copied ? "check" : "copy", x + 12, y + (h - 14) / 2, 14,
                    copied ? AppTheme.GREEN : AppTheme.TEXT_FAINT);
            final String msg = copied
                    ? "Report template copied to clipboard — paste it into the issue body"
                    : "Clipboard copy unavailable — write the report manually";
            canvas.text(canvas.text().truncateToWidth(msg, w - 44, AppTheme.TEXT_BODY), x + 34,
                    y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                    copied ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
        }
    }

    // WATERMEDIA HERO BANNER (PRIMARY REPORT TARGET) — LOGO, PRIMARY BADGE, COPY AND ITS SUBMIT BUTTON.
    // CLICKS ROUTE BY COORDINATE: THE SUBMIT BOX FIRES THE REPO ACTION, THE BODY ONLY SELECTS.
    private final class RepoHero extends Element<RepoHero> {

        private static final int PAD = 14;
        private int submitX;
        private int submitY;
        private boolean submitHover;

        @Override
        protected void onLayout() {
            this.submitX = this.left + this.measuredWidth - PAD - 150;
            this.submitY = this.top + this.measuredHeight - PAD - 32;
        }

        private boolean inSubmit(final double mx, final double my) {
            return mx >= this.submitX && mx < this.submitX + 150 && my >= this.submitY && my < this.submitY + 32;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
            if (this.inSubmit(mx, my) && !repoTargets.isEmpty()) {
                submitRepo(repoTargets.get(0), 0);
            } else if (repoSelected != 0) {
                // THE BODY ONLY SELECTS; THE SUBMIT BUTTON OPENS THE ISSUE TRACKER
                repoSelected = 0;
                HomeScreen.this.ctx.playSelectionSound();
                this.invalidate();
            }
            return true;
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            final boolean inside = this.visible && this.enabled && this.contains(mx, my);
            this.hovered = inside;
            this.submitHover = inside && this.inSubmit(mx, my);
            return inside;
        }

        @Override
        public void clearHover() {
            super.clearHover();
            this.submitHover = false;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            if (repoTargets.isEmpty()) return;
            final RepoTarget repo = repoTargets.get(0);
            final boolean selected = repoSelected == 0;
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.glow(x, y, w, h, 0f, AppTheme.GREEN, selected ? 0.26f : 0.12f);
            canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.GREEN, selected ? 30 : 18));
            canvas.stroke(x, y, w, h, AppTheme.GREEN, selected ? 2f : 1.5f);
            canvas.fill(x, y, 4, h, AppTheme.GREEN);

            // PACK.PNG LOGO — SQUARE, ASPECT-PRESERVED, CENTERED IN ITS BOX; TEXTURE ID READ PER DRAW
            final int logoBox = h - PAD * 2;
            final int logoX = x + PAD + 8;
            final var assets = HomeScreen.this.ctx.assets;
            if (assets.iconId > 0 && assets.iconWidth > 0 && assets.iconHeight > 0) {
                final float s = Math.min((float) logoBox / assets.iconWidth, (float) logoBox / assets.iconHeight);
                final int lw = (int) (assets.iconWidth * s);
                final int lh = (int) (assets.iconHeight * s);
                canvas.image(assets.iconId, logoX + (logoBox - lw) / 2f, y + PAD + (logoBox - lh) / 2f, lw, lh, null);
            }

            final int textX = logoX + logoBox + 20;
            final int textRight = x + w - PAD;
            // PRIMARY BADGE TOP-RIGHT
            final int badgeW = canvas.textWidth("PRIMARY", AppTheme.TEXT_SUBTITLE, false) + 20;
            canvas.fill(textRight - badgeW, y + PAD, badgeW, 20, AppTheme.alpha(AppTheme.GREEN, 40));
            canvas.stroke(textRight - badgeW, y + PAD, badgeW, 20, AppTheme.GREEN, 1f);
            canvas.text("PRIMARY", textRight - badgeW + 10,
                    y + PAD + Math.max(0, (20 - canvas.textHeight(AppTheme.TEXT_SUBTITLE, false)) / 2),
                    AppTheme.GREEN, AppTheme.TEXT_SUBTITLE, false);

            // TEXT — EACH LINE CLAMPED SO IT NEVER SPILLS PAST THE BADGE, THE BUTTON OR THE HERO EDGE
            canvas.text(canvas.text().truncateToWidth("SUBMIT A BUG REPORT ON WATERMEDIA ISSUE TRACKER",
                            textRight - badgeW - 12 - textX, AppTheme.TEXT_BUTTON, Font.BOLD),
                    textX, y + 16, AppTheme.GREEN, AppTheme.TEXT_BUTTON, true);
            canvas.text(canvas.text().truncateToWidth("AND — if a listed mod is the suspect — also file it in that mod's repo below.",
                            textRight - textX, AppTheme.TEXT_BODY),
                    textX, y + 40, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
            canvas.text(canvas.text().truncateToWidth(repo.slug(), this.submitX - 12 - textX, AppTheme.TEXT_SUBTITLE),
                    textX, y + 64, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);

            // SUBMIT — SHARED DESIGN-SYSTEM BUTTON LOOK; ACTIVE WHEN SELECTED OR ITS BOX IS HOVERED
            Button.render(canvas, this.submitX, this.submitY, 150, 32,
                    "SUBMIT", "", "link", 12, AppTheme.GREEN, AppTheme.GREEN, false,
                    selected || this.submitHover, true);
        }
    }

    // ONE SUSPECTED-MOD CARD IN THE REPORT LIST — ACCENT STRIPE, NAME/SLUG AND ITS OWN SUBMIT BUTTON.
    private final class RepoCard extends Element<RepoCard> {

        private final RepoTarget repo;
        private final int targetIdx; // INDEX INTO repoTargets (1..n — 0 IS THE HERO)
        private int submitX;
        private int submitY;
        private boolean submitHover;

        private RepoCard(final RepoTarget repo, final int targetIdx) {
            this.repo = repo;
            this.targetIdx = targetIdx;
        }

        @Override
        protected void onLayout() {
            this.submitX = this.left + this.measuredWidth - 138;
            this.submitY = this.top + (this.measuredHeight - 32) / 2;
        }

        private boolean inSubmit(final double mx, final double my) {
            return mx >= this.submitX && mx < this.submitX + 122 && my >= this.submitY && my < this.submitY + 32;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
            if (this.inSubmit(mx, my)) {
                submitRepo(this.repo, this.targetIdx);
            } else if (repoSelected != this.targetIdx) {
                // CARD BODY ONLY SELECTS; THE SUBMIT BUTTON OPENS THE ISSUE TRACKER
                repoSelected = this.targetIdx;
                HomeScreen.this.ctx.playSelectionSound();
                this.invalidate();
            }
            return true;
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            final boolean inside = this.visible && this.enabled && this.contains(mx, my);
            this.hovered = inside;
            this.submitHover = inside && this.inSubmit(mx, my);
            return inside;
        }

        @Override
        public void clearHover() {
            super.clearHover();
            this.submitHover = false;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean sel = repoSelected == this.targetIdx;
            final Color accent = this.repo.accent();
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(x, y, w, h, sel ? AppTheme.alpha(accent, 30) : AppTheme.alpha(AppTheme.BG_2, 210));
            canvas.stroke(x, y, w, h, sel ? accent : AppTheme.STROKE_BRIGHT, sel ? 2f : 1f);
            if (sel) canvas.glow(x, y, w, h, 0f, accent, 0.18f);
            canvas.fill(x, y, 4, h, accent);
            // STATUS PIP IN THE CARD ACCENT (SQUARE — THE RETAINED DESIGN LANGUAGE)
            canvas.fill(x + 16, y + (h - 10) / 2, 10, 10, accent);
            canvas.glow(x + 16, y + (h - 10) / 2, 10, 10, 0f, accent, 0.5f);
            canvas.text(this.repo.name().toUpperCase(Locale.ROOT), x + 36, y + 12,
                    sel ? accent : AppTheme.TEXT, AppTheme.TEXT_BUTTON, true);
            canvas.text(this.repo.slug(), x + 36, y + 32, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
            Button.render(canvas, this.submitX, this.submitY, 122, 32,
                    "SUBMIT", "", "link", 12, accent, accent, false,
                    sel || this.submitHover, true);
        }
    }
}
