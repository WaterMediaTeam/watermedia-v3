package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.platform.PlatformSearch;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.IconButton;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.watermedia.bootstrap.app.render.RenderSystem.mediaEngineSupplier;

/**
 * Screen with a dialog for opening custom multimedia URLs, fully retained: a scrim over the live
 * {@link HomeScreen} (mounted underneath via {@link #under()}), a centered dialog panel composed of a
 * title row, the live preview (with a localized {@link CrtOverlay}), the URL/search field with its
 * inline buttons, the results/history dropdown as a real {@link ListView}, and the footer actions —
 * all laid out and hit-tested by the view tree. The loading modal is a {@link Dialog} on the screen
 * overlay, and thumbnail players are torn down off the render thread.
 */
public class OpenMultimediaScreen extends Screen {

    private static final long LOAD_TIMEOUT_MS = 30000L;
    private static final int SEARCH_PREVIEW_H = 130;      // SHRUNK PREVIEW HEIGHT WHILE SEARCHING
    private static final long PREVIEW_ANIM_MS = 220L;     // PREVIEW GROW/SHRINK EASE DURATION
    private static final long SEARCH_DEBOUNCE_MS = 320L;  // QUIET PERIOD AFTER TYPING BEFORE AUTO-SEARCHING

    // LEGACY SCRIM 0.02/0.03/0.10 @ 0.65 — HOME STAYS VISIBLE (AND ANIMATING) BEHIND IT
    private static final Color SCRIM_COLOR = AppTheme.rgba(5, 8, 26, 166);
    private static final Color CLOSE_HOVER = AppTheme.rgba(255, 132, 160, 255);
    private static final Color GRADIENT_TOP = AppTheme.rgba(0, 0, 0, 0);

    // DROPDOWN ROW KINDS — ONE FLAT Row LIST DRIVES THE ListView (HEADERS, PLACEHOLDER, RESULTS, HISTORY)
    private static final int ROW_HEAD = 0;
    private static final int ROW_WAIT = 1;
    private static final int ROW_RESULT = 2;
    private static final int ROW_RECENT = 3;
    private static final int ROW_HISTORY = 4;

    private record Row(int kind, PlatformResult result, String text) {
    }

    // CACHED, REFERENCE-STABLE KEYBIND LISTS — LET KeybindsBar SKIP ITS REBUILD ON THE STEADY PER-FRAME PATH
    private static final List<Keybind> KEYS_LOADING = List.of(new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_SEARCH = List.of(
            new Keybind("TYPE", "Search"), new Keybind("CLICK", "Pick result"),
            new Keybind("CTRL+V", "Paste"), new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_DEFAULT = List.of(
            new Keybind("ENTER", "Play"), new Keybind("SPACE", "Save"),
            new Keybind("CTRL+V", "Paste"), new Keybind("R", "Reload"), new Keybind("ESC", "Cancel"));

    private final Consumer<HomeScreen.Action> navigator;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private volatile int previewGeneration;
    private long loadStartTime;
    private volatile MRL previewMRL;
    private String previewUrl = "";

    // SEARCH MODE: WHEN THE INPUT IS NOT A VALID URI THE PLAY BUTTON BECOMES SEARCH AND A RESULTS
    // DROPDOWN OPENS BELOW THE INPUT. THE PlatformSearch HANDLE FILLS IN OFF-THREAD; onUpdate POLLS IT
    // EVERY FRAME. searchRenderedDone GUARANTEES ONE FINAL FRAME ONCE THE SEARCH COMPLETES.
    private PlatformSearch search;
    private boolean searchRenderedDone = true;
    private long lastEditMs;                // TIMESTAMP OF THE LAST INPUT EDIT (DRIVES THE AUTO-SEARCH DEBOUNCE)
    private boolean searchMode;             // CACHED searchMode() RESULT (AVOIDS A PER-FRAME FILE STAT + URI PARSE)
    private String searchModeText;          // TRIMMED INPUT THE CACHED searchMode WAS COMPUTED FOR (null = NOT YET COMPUTED)
    private float animPreviewH;             // CURRENT ANIMATED PREVIEW HEIGHT
    private float animPreviewFrom;          // PREVIEW HEIGHT WHEN THE CURRENT ANIMATION BEGAN
    private int animPreviewTarget = -1;     // HEIGHT THE ANIMATION IS EASING TOWARD (-1 = UNINITIALIZED)
    private long animPreviewStart;          // START TIMESTAMP OF THE CURRENT PREVIEW ANIMATION

    // DROPDOWN ROW CACHE — REBUILD THE ListView ITEMS ONLY WHEN THE POLLED SEARCH DATA ACTUALLY CHANGED
    private PlatformSearch rowsSearch;
    private int rowsResults;
    private int rowsHistory;
    private boolean rowsSearching;
    private boolean rowsBuilt;

    // TREE ELEMENTS BOUND FROM LIVE STATE IN onUpdate
    private InputField field;
    private Button reload;
    private Button save;
    private Button play;
    private PreviewPanel preview;
    private StatusChip chip;
    private ListView<Row> dropdown;
    private Dialog loadDialog;
    private Text loadUrl;
    private boolean loadShown;

    // RESULT THUMBNAILS RENDER THROUGH SHORT-LIVED IMAGE PLAYERS, KEYED BY THUMBNAIL URI (MIRRORS
    // MRLSelectorScreen). THE ACTIVE RENDER BACKEND SUPPLIES THE ENGINE; RELEASED ON A NEW SEARCH/EXIT.
    private final Map<URI, MediaPlayer> thumbPlayers = new LinkedHashMap<>();
    private final Set<URI> thumbAttempted = new HashSet<>();
    private final Set<URI> thumbSubscribed = new HashSet<>();

    public OpenMultimediaScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // THE HOME SCREEN STAYS MOUNTED (AND ANIMATING) UNDERNEATH — THE SHELL STACKS BOTH IN THE CENTER SLOT
    @Override
    public String under() {
        return "home";
    }

    @Override
    protected Element<?> build() {
        this.field = new InputField().size(Element.MAX_PARENT, 40);
        this.reload = new Button("RELOAD").icon("reload")
                .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT)
                .size(112, 40).onClick(b -> this.reloadPreviewMRL());
        this.preview = new PreviewPanel().width(Element.MAX_PARENT).margin(new Spacing(20, 20, 0, 20));
        this.chip = new StatusChip();
        this.dropdown = new ListView<Row>()
                .rowFactory((row, index) -> this.rowFor(row))
                .onSelect((row, index) -> this.pickRow(row))
                // ROWS SELF-DRAW THEIR HOVER FILL (TWO DIFFERENT ALPHAS), SO SUPPRESS THE LIST HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .padding(new Spacing(6, 2, 6, 2))
                .background(AppTheme.BG_2_ALPHA)
                .border(AppTheme.NEON, 1f)
                .shadow(0.45f)
                // A CLICK INSIDE THE BOX BUT NOT ON A ROW IS SWALLOWED (LEGACY BEHAVIOR)
                .consumeTouch(true)
                .visible(false)
                .width(Element.MAX_PARENT);

        final Button cancel = new Button("CANCEL").subText("ESC").icon("x")
                .accent(AppTheme.TEXT_SOFT).textColor(AppTheme.TEXT_SOFT).height(36)
                .onClick(b -> this.navigator.accept(HomeScreen.Action.BACK));
        this.save = new Button("SAVE").subText("SPACE").icon("save")
                .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT).height(36)
                .onClick(b -> this.saveCustomUrl());
        this.play = new Button("PLAY").subText("ENTER").icon("play")
                .accent(AppTheme.GREEN).textColor(AppTheme.GREEN).height(36)
                .onClick(b -> {
                    if (this.searchMode()) this.doSearch();
                    else this.playCustomUrl();
                });

        final Parent footer = Parent.row().spacing(12)
                .width(Element.MAX_PARENT).height(36)
                .margin(new Spacing(0, 20, 22, 20))
                .add(cancel)
                .add(new Box().size(0, 1).weight(1f))
                .add(this.save)
                .add(this.play);

        final Parent column = Parent.column()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(new TitleRow().width(Element.MAX_PARENT).height(56))
                .add(this.preview)
                .add(new InputBlock().width(Element.MAX_PARENT).height(68).margin(new Spacing(0, 20, 0, 20)))
                .add(new MiddleZone().width(Element.MAX_PARENT).weight(1f).margin(new Spacing(0, 20, 0, 20)))
                .add(footer);

        final PanelBox panel = new PanelBox().add(column)
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .background(AppTheme.BG_1)
                .border(AppTheme.NEON, 1f)
                .glow(AppTheme.NEON, 0.35f)
                .shadow(0.55f);

        // LOADING MODAL — A REAL Dialog ON THE SCREEN OVERLAY (TITLE DOTS AND URL REFRESHED IN onUpdate)
        this.loadUrl = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT).width(Element.MAX_PARENT);
        this.loadDialog = new Dialog()
                .title("Loading")
                .accent(AppTheme.NEON)
                .panelWidth(480)
                .content(this.loadUrl)
                .content(new Text("ESC to cancel").scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT))
                .onDismiss(this::cancelLoading);

        final Box scrim = new Box().size(Element.MAX_PARENT, Element.MAX_PARENT)
                .background(SCRIM_COLOR)
                .consumeTouch(true)
                // A CLICK OUTSIDE THE PANEL BLURS THE FIELD (LEGACY OUTSIDE-CLICK BEHAVIOR), NEVER REACHES HOME
                .onClick(b -> this.field.focused(false).invalidate());
        return new Body(scrim, panel);
    }

    @Override
    public void onEnter() {
        super.onEnter(); // BUILDS THE TREE ON FIRST ENTRY
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.previewMRL = null;
        this.previewUrl = "";
        this.animPreviewTarget = -1; // SNAP THE PREVIEW TO ITS CORRECT SIZE ON ENTRY (NO STRAY ANIMATION)
        this.clearSearch();
        this.field.focused(true);
        this.syncLoadDialog();
    }

    @Override
    public void onExit() {
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.previewMRL = null;
        this.previewUrl = "";
        this.clearSearch();
        this.syncLoadDialog();
    }

    // HOT-SWAP HOOK: DROP EVERY THUMBNAIL PLAYER (RELEASED OFF-THREAD, SEE releaseAsync)
    @Override
    public void releaseMedia() {
        this.releaseThumbs();
    }

    @Override
    public List<Keybind> keybinds() {
        if (this.loading) return KEYS_LOADING;
        return this.searchMode() ? KEYS_SEARCH : KEYS_DEFAULT;
    }

    @Override
    public boolean continuous() {
        // FIELD CARET BLINK + LOADING DOTS + SEARCH POLLING + PREVIEW EASE + ANIMATED THUMBNAILS (GIF/WEBP)
        return this.textInputActive() || this.loading
                || (this.search != null && !this.searchRenderedDone)
                || System.currentTimeMillis() - this.animPreviewStart < PREVIEW_ANIM_MS
                || this.thumbsAnimating();
    }

    // ==========================================================================
    // PER-FRAME BINDING — PULLS LIVE STATE INTO THE TREE BEFORE MEASURE/DRAW
    // ==========================================================================

    @Override
    protected void onUpdate() {
        this.ensurePreviewMRL();
        if (this.loading) this.checkLoadingState();
        this.syncLoadDialog();

        final String url = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";
        if (this.loading) {
            this.loadDialog.title("Loading" + ".".repeat((int) ((System.currentTimeMillis() / 500) % 4)));
            this.loadUrl.text(url.isEmpty() ? "(empty)" : url);
        }

        final boolean search = this.searchMode();

        // AUTO-SEARCH: ONCE TYPING SETTLES (DEBOUNCE), SEARCH THE CURRENT QUERY — NO ENTER NEEDED
        if (search) {
            final String query = url.trim();
            final boolean stale = this.search == null || !this.search.query().equals(query);
            if (stale && !query.isEmpty() && System.currentTimeMillis() - this.lastEditMs >= SEARCH_DEBOUNCE_MS) {
                this.doSearch();
            }
        }

        // BUTTON BINDINGS — SAME ENABLE/ACCENT RULES AS THE LEGACY DRAW: RELOAD/SAVE ONLY FOR A URL/PATH,
        // THE PRIMARY ACTION TOGGLES PLAY (GREEN) <-> SEARCH (CYAN) WITH THE INPUT
        final boolean empty = url.isEmpty();
        this.reload.enabled(!search && !empty);
        this.save.accent(search || empty ? AppTheme.TEXT_FAINT : AppTheme.NEON_LIGHT).enabled(!search && !empty);
        final Color playColor = empty ? AppTheme.TEXT_FAINT : search ? AppTheme.CYAN : AppTheme.GREEN;
        this.play.label(search ? "SEARCH" : "PLAY").icon(search ? "search" : "play")
                .accent(playColor).textColor(playColor).enabled(!empty);

        // DETECTED-STATUS CHIP: RESULT COUNT/WORKING STATE IN SEARCH MODE, OTHERWISE THE PREVIEW MRL STATE
        if (search) {
            final boolean done = this.search != null && this.search.done();
            this.chip.set(done ? this.search.results().size() + " RESULTS" : "SEARCHING",
                    done ? AppTheme.CYAN : AppTheme.NEON);
        } else {
            this.chip.set(this.statusLabel(), this.statusColor());
        }

        this.syncDropdown(search);
        // POLLED SEARCH: GUARANTEES ONE FINAL FRAME AFTER COMPLETION (SEE continuous)
        this.searchRenderedDone = this.search == null || this.search.done();
    }

    // SHOWS/HIDES THE LOADING Dialog WHEN THE loading FLAG FLIPS (THE FLAG ALSO CHANGES OFF-EVENT PATHS)
    private void syncLoadDialog() {
        if (this.loading == this.loadShown || this.loadDialog == null) return;
        this.loadShown = this.loading;
        if (this.loading) this.showDialog(this.loadDialog);
        else this.hideDialog(this.loadDialog);
    }

    private void cancelLoading() {
        this.loading = false;
        this.loadGeneration++;
        this.ctx.selectedMRL = null;
        this.ctx.requestRender();
    }

    // ==========================================================================
    // INPUT — THE TREE HANDLES POINTERS; ONLY THE SCREEN SHORTCUTS LIVE HERE
    // ==========================================================================

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // TREE FIRST: THE LOADING DIALOG (FULLY MODAL) AND THE FOCUSED FIELD (BACKSPACE/DELETE) CONSUME THEIRS
        if (super.dispatchKey(key, action)) return true;
        if (action == GLFW_RELEASE) {
            final String url = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";
            switch (key) {
                // FIRE ONLY WHILE THE FIELD IS BLURRED: A TYPED SPACE (PATH OR SEARCH QUERY) IS INPUT, NOT THE SAVE SHORTCUT
                case GLFW_KEY_SPACE -> {
                    if (!this.field.textInputActive() && !this.searchMode() && !url.isEmpty()) this.saveCustomUrl();
                }
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                    if (!url.isEmpty()) {
                        if (this.searchMode()) this.doSearch();
                        else this.playCustomUrl();
                    }
                }
                case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
                case GLFW_KEY_V -> {
                    if (this.ctx.ctrlDown) this.pasteFromClipboard();
                }
                // FIRE ONLY WHILE THE FIELD IS BLURRED: AN 'r' TYPED INTO A URL MUST NOT TRIGGER A NETWORK RELOAD
                case GLFW_KEY_R -> {
                    if (!this.field.textInputActive() && !this.searchMode() && !url.isEmpty()) this.reloadPreviewMRL();
                }
            }
        }
        return true; // MODAL OVER HOME — THE LIVE UNDERLAY MUST NEVER SEE KEYS
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (super.dispatchScroll(mx, my, amount)) return true; // DROPDOWN / LOADING DIALOG
        return this.contains(mx, my); // SWALLOW INSIDE THE SLOT — HOME UNDERNEATH MUST NOT SCROLL
    }

    // ==========================================================================
    // ACTIONS (STATE LAYER — UNCHANGED SEMANTICS FROM THE LEGACY SCREEN)
    // ==========================================================================

    private void pasteFromClipboard() {
        try {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                this.ctx.customUrlText = ((String) clipboard.getData(DataFlavor.stringFlavor)).trim().replace("\"", "");
                this.lastEditMs = System.currentTimeMillis();
                this.ensurePreviewMRL();
            }
        } catch (final Exception ignored) {
            // A TRANSIENT CLIPBOARD FAILURE (BUSY ON WINDOWS / FLAVOR ERROR) MUST NOT WIPE THE URL THE USER ALREADY TYPED
        }
    }

    private void reloadPreviewMRL() {
        final String url = this.ctx.customUrlText == null ? "" : this.ctx.customUrlText.trim();
        if (url.isEmpty()) return;
        if (this.previewMRL == null || !url.equals(this.previewUrl)) {
            this.previewUrl = "";
            this.ensurePreviewMRL();
            return;
        }

        final int generation = ++this.previewGeneration;
        this.previewMRL.reload();
        this.loadStartTime = System.currentTimeMillis();
        this.subscribePreviewMRL(this.previewMRL, generation);
        this.ctx.requestRender();
    }

    private void saveCustomUrl() {
        if (this.ctx.customUrlText == null || this.ctx.customUrlText.isEmpty()) return;
        final String name = this.ctx.customUrlText.length() > 40
                ? this.ctx.customUrlText.substring(0, 37) + "..."
                : this.ctx.customUrlText;
        this.ctx.customTests.add(new AppContext.TestURI(name, this.ctx.customUrlText, false));
        this.ctx.customUrlText = "";
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    private void playCustomUrl() {
        if (this.ctx.customUrlText == null || this.ctx.customUrlText.isEmpty()) return;

        this.ensurePreviewMRL();
        if (this.previewMRL == null) return;
        switch (this.previewMRL.status()) {
            case LOADED -> { /* PROCEED BELOW */ }
            case FETCHING -> {
                this.beginLoading(this.previewMRL);
                return;
            }
            case ERROR -> {
                this.ctx.showError("Invalid URL", "Failed to load this URL.", null);
                return;
            }
            case BLOCKED -> {
                this.ctx.showError("Blocked", "This media was blocked by the platform.", null);
                return;
            }
            // EXPIRED/FORGOTTEN SOURCES ARE NO LONGER USABLE — DISPOSE THE PREVIEW AND
            // REGENERATE A FRESH MRL BEFORE PLAYING.
            case EXPIRED, FORGOTTEN -> {
                this.previewUrl = "";
                this.ensurePreviewMRL();
                if (this.previewMRL != null) this.beginLoading(this.previewMRL);
                return;
            }
        }

        this.ctx.selectedMRL = this.previewMRL;
        this.ctx.selectedMRLName = this.previewTitle();
        this.ctx.selectedGroup = null;
        final var sourcesList = this.ctx.selectedMRL.sources();
        this.ctx.availableSources = sourcesList.toArray(MRL.Source[]::new);
        if (this.ctx.availableSources.length == 0) {
            this.ctx.showError("No Sources", "No sources available for this URL", null);
            return;
        }

        this.ctx.sourceSelectorIndex = 0;
        this.ctx.selectedSource = this.ctx.availableSources[0];
        this.navigator.accept(HomeScreen.Action.PLAYER);
    }

    private void ensurePreviewMRL() {
        final String url = this.ctx.customUrlText == null ? "" : this.ctx.customUrlText.trim();
        if (url.isEmpty()) {
            this.clearPreview();
            return;
        }
        if (url.equals(this.previewUrl)) return;
        this.previewUrl = url;
        final int generation = ++this.previewGeneration;
        try {
            if (!loadable(url)) {
                this.previewMRL = null;
                return;
            }
            this.previewMRL = MediaAPI.mrl(url);
            this.loadStartTime = System.currentTimeMillis();
            this.subscribePreviewMRL(this.previewMRL, generation);
        } catch (final Exception ignored) {
            this.previewMRL = null;
        }
    }

    private void clearPreview() {
        if (this.previewMRL != null || !this.previewUrl.isEmpty()) {
            this.previewGeneration++;
        }
        this.previewMRL = null;
        this.previewUrl = "";
    }

    // ==========================================================================
    // SEARCH MODE
    // ==========================================================================

    // TRUE WHEN THE INPUT IS NOT A LOADABLE URI/FILE, SO THE DIALOG SEARCHES INSTEAD OF PLAYING. SHARES
    // loadable() WITH ensurePreviewMRL SO THE "URL/PATH VS QUERY" RULE CAN NEVER DRIFT. THIS RUNS EVERY
    // FRAME ON A CONTINUOUSLY-REDRAWN SCREEN, SO THE RESULT IS MEMOIZED AGAINST THE INPUT TEXT: THE
    // BLOCKING file.exists() STAT AND URI PARSE ONLY RE-RUN WHEN THE TEXT ACTUALLY CHANGES.
    private boolean searchMode() {
        final String text = this.ctx.customUrlText == null ? "" : this.ctx.customUrlText.trim();
        if (!text.equals(this.searchModeText)) {
            this.searchModeText = text;
            this.searchMode = !text.isEmpty() && !loadable(text);
        }
        return this.searchMode;
    }

    // TRUE WHEN text NAMES A PLAYABLE TARGET: AN EXISTING FILE, OR A URI THAT CARRIES A SCHEME. THE SINGLE
    // "URL/PATH VS SEARCH QUERY" RULE SHARED BY searchMode() AND ensurePreviewMRL(). CALLERS PASS A TRIMMED,
    // NON-EMPTY STRING.
    private static boolean loadable(final String text) {
        if (new File(text).exists()) return true;
        try {
            return URI.create(text).getScheme() != null;
        } catch (final IllegalArgumentException e) {
            return false; // NOT EVEN A PARSEABLE URI (E.G. CONTAINS SPACES) — TREAT AS A SEARCH QUERY
        }
    }

    // EASES THE PREVIEW HEIGHT TOWARD target (EASE-OUT QUAD) AND RETURNS THE CURRENT HEIGHT. A NEW target
    // RESTARTS THE EASE FROM WHEREVER IT IS NOW, SO RAPID MODE FLIPS STAY SMOOTH RATHER THAN SNAPPING.
    private int animatePreviewH(final int target) {
        if (this.animPreviewTarget != target) {
            this.animPreviewFrom = this.animPreviewTarget < 0 ? target : this.animPreviewH; // FIRST FRAME SNAPS
            this.animPreviewTarget = target;
            this.animPreviewStart = System.currentTimeMillis();
        }
        final float t = Math.min(1f, (System.currentTimeMillis() - this.animPreviewStart) / (float) PREVIEW_ANIM_MS);
        final float eased = 1f - (1f - t) * (1f - t);
        this.animPreviewH = this.animPreviewFrom + (this.animPreviewTarget - this.animPreviewFrom) * eased;
        return Math.round(this.animPreviewH);
    }

    // LAUNCHES AN ASYNC SEARCH FOR THE CURRENT QUERY. PlatformAPI SUPERSEDES ANY PREVIOUS SEARCH; WE DROP
    // THE OLD THUMBNAILS AND LET onUpdate POLL THE FRESH HANDLE AS RESULTS LAND OFF-THREAD.
    private void doSearch() {
        final String query = this.ctx.customUrlText == null ? "" : this.ctx.customUrlText.trim();
        if (query.isEmpty()) return;
        this.releaseThumbs();
        this.searchRenderedDone = false;
        this.rowsBuilt = false;
        this.search = PlatformAPI.search(query);
        this.ctx.requestRender();
    }

    // REPLACES THE INPUT WITH THE RESULT'S RAW URL AND LEAVES SEARCH MODE SO IT CAN BE PLAYED.
    private void selectResult(final PlatformResult result) {
        if (result == null || result.url() == null) return;
        this.ctx.customUrlText = result.url().toString();
        this.clearSearch();
        this.field.focused(true);
        this.ensurePreviewMRL();
        this.ctx.playSelectionSound();
        this.ctx.requestRender();
    }

    // DROPDOWN ROW ACTIVATION: A RESULT REPLACES THE INPUT WITH ITS RAW URL AND KEEPS THE FIELD FOCUSED;
    // A HISTORY ENTRY REFILLS THE QUERY AND RE-SEARCHES IMMEDIATELY.
    private void pickRow(final Row row) {
        if (row == null) return;
        if (row.kind() == ROW_RESULT) {
            this.selectResult(row.result());
        } else if (row.kind() == ROW_HISTORY) {
            this.ctx.customUrlText = row.text();
            this.doSearch();
        }
    }

    private void clearSearch() {
        this.search = null;
        this.searchRenderedDone = true;
        this.rowsBuilt = false;
        if (this.dropdown != null) {
            this.dropdown.items(List.of());
            this.dropdown.visible(false);
        }
        this.releaseThumbs();
    }

    // REBUILDS THE DROPDOWN'S FLAT ROW LIST FROM THE POLLED SEARCH STATE. OUTSIDE SEARCH MODE ANY STALE
    // SEARCH/THUMBNAILS ARE DROPPED; INSIDE IT THE ROWS ONLY REBUILD WHEN THE BACKING DATA CHANGED, SO THE
    // PER-FRAME POLL NEVER CHURNS ELEMENTS.
    private void syncDropdown(final boolean searchMode) {
        if (!searchMode) {
            if (this.search != null) this.clearSearch();
            if (this.dropdown.visible()) this.dropdown.visible(false);
            this.rowsBuilt = false;
            return;
        }

        final boolean searching = this.search != null && !this.search.done();
        final List<PlatformResult> results = this.search != null ? this.search.results() : List.of();
        final List<String> history = this.search != null ? this.search.history() : PlatformAPI.searchHistory();
        if (this.rowsBuilt && this.search == this.rowsSearch && results.size() == this.rowsResults
                && history.size() == this.rowsHistory && searching == this.rowsSearching) {
            return;
        }
        this.rowsSearch = this.search;
        this.rowsResults = results.size();
        this.rowsHistory = history.size();
        this.rowsSearching = searching;
        this.rowsBuilt = true;

        final List<Row> rows = new ArrayList<>();
        if (this.search != null) {
            rows.add(new Row(ROW_HEAD, null, searching ? "SEARCHING..." : "RESULTS (" + results.size() + ")"));
            if (results.isEmpty() && searching) {
                rows.add(new Row(ROW_WAIT, null, null));
            } else {
                for (final PlatformResult result: results) rows.add(new Row(ROW_RESULT, result, null));
            }
        }
        if (!history.isEmpty()) {
            rows.add(new Row(ROW_RECENT, null, null));
            for (final String entry: history) rows.add(new Row(ROW_HISTORY, null, entry));
        }
        this.dropdown.items(rows);
        this.dropdown.visible(!rows.isEmpty());
    }

    private Element<?> rowFor(final Row row) {
        return switch (row.kind()) {
            case ROW_HEAD -> new HeadRow(row.text(), false).height(20).enabled(false);
            case ROW_RECENT -> new HeadRow("RECENT", true).height(20).enabled(false);
            case ROW_WAIT -> new WaitRow().height(38).enabled(false);
            case ROW_RESULT -> new ResultRow(row.result()).height(38);
            default -> new HistoryRow(row.text()).height(26);
        };
    }

    // ==========================================================================
    // THUMBNAIL PLAYERS
    // ==========================================================================

    private void releaseThumbs() {
        if (!this.thumbPlayers.isEmpty()) {
            // SNAPSHOT AND CLEAR FIRST SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING PLAYER
            final List<MediaPlayer> players = new ArrayList<>(this.thumbPlayers.values());
            this.thumbPlayers.clear();
            this.releaseAsync(players);
        }
        this.thumbAttempted.clear();
        this.thumbSubscribed.clear();
    }

    // HANDS RESULT-THUMBNAIL PLAYERS OFF FOR BACKGROUND RELEASE (M-08) — MIRRORS AppContext.releasePlayer.
    // THE PLAYERS MUST ALREADY BE REMOVED FROM thumbPlayers SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING
    // PLAYER. stop() IS NON-BLOCKING; release() JOINS THE DECODE THREADS AND SO RUNS OFF THE RENDER THREAD.
    // THE ENGINE TEARDOWN IS THREAD-SAFE (GL DEFERS ITS TEXTURE DELETES TO THE RENDER EXECUTOR).
    private void releaseAsync(final List<MediaPlayer> players) {
        if (players.isEmpty()) return;
        for (final MediaPlayer player: players) player.stop();
        // HAND THE BLOCKING release() TO THE SHARED, SHUTDOWN-AWAITED RELEASE CHAIN SO A THUMBNAIL TEARDOWN
        // IN FLIGHT CANNOT OUTLIVE THE RENDER-DEVICE DESTROY ON EXIT (VULKAN SAFETY).
        this.ctx.releaseAsync("WaterMedia-ThumbnailRelease", () -> {
            for (final MediaPlayer player: players) player.release();
        });
    }

    // LAZILY BUILDS AN IMAGE PLAYER FOR A RESULT THUMBNAIL (KEYED BY URI). RETURNS null WHILE THE THUMBNAIL
    // MRL IS STILL FETCHING (SUBSCRIBED TO RE-RENDER ON READY) OR WHEN IT EXPOSES NO USABLE IMAGE SOURCE.
    private MediaPlayer thumbPlayer(final URI thumbnail) {
        if (thumbnail == null) return null;
        final MediaPlayer existing = this.thumbPlayers.get(thumbnail);
        if (existing != null) return existing;
        if (this.thumbAttempted.contains(thumbnail)) return null;

        final MRL mrl = MediaAPI.mrl(thumbnail.toString());
        final MRL.Status status = mrl.status();
        if (status == MRL.Status.FETCHING) {
            if (this.thumbSubscribed.add(thumbnail)) mrl.subscribe(done -> this.ctx.requestRender());
            return null; // RETRY NEXT FRAME
        }
        this.thumbAttempted.add(thumbnail);
        if (status != MRL.Status.LOADED) return null;

        final var sources = mrl.sources();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).type() == MediaType.IMAGE) {
                final MediaPlayer player = MediaAPI.createPlayer(mrl, i,
                        mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                if (player != null) {
                    player.repeat(true); // LOOP ANIMATED THUMBNAILS (GIF/WEBP) INSTEAD OF FREEZING ON THE LAST FRAME
                    player.start();
                    this.thumbPlayers.put(thumbnail, player);
                }
                return player;
            }
        }
        return null;
    }

    // TRUE WHILE ANY DROPDOWN THUMBNAIL IS ACTIVELY ANIMATING — DRIVES continuous()
    private boolean thumbsAnimating() {
        for (final MediaPlayer player: this.thumbPlayers.values()) {
            if (player == null || player.error() || player.stopped() || player.ended()) continue;
            if (player instanceof TxMediaPlayer) {
                if (player.duration() > 0L && !player.paused()) return true;
            } else if (!player.paused()) {
                return true;
            }
        }
        return false;
    }

    // ==========================================================================
    // PREVIEW / LOADING STATE
    // ==========================================================================

    private void subscribePreviewMRL(final MRL mrl, final int generation) {
        if (mrl == null || loaded(mrl)) return;
        mrl.subscribe(done -> {
            if (this.previewGeneration == generation && this.previewMRL == done) {
                this.ctx.requestRender();
            }
        });
    }

    private void beginLoading(final MRL mrl) {
        this.ctx.selectedMRL = mrl;
        this.ctx.selectedMRLName = this.previewTitle();
        this.loading = true;
        this.loadGeneration++;
        this.loadStartTime = System.currentTimeMillis();
        if (!loaded(mrl)) {
            final int generation = this.loadGeneration;
            mrl.subscribe(done -> {
                if (this.loading && this.loadGeneration == generation && this.ctx.selectedMRL == done) {
                    this.ctx.requestRender();
                }
            });
            this.scheduleLoadTimeout(generation);
        }
        this.ctx.requestRender();
    }

    private void scheduleLoadTimeout(final int generation) {
        final long deadline = this.loadStartTime + LOAD_TIMEOUT_MS;
        ThreadTool.createStarted("OpenMultimediaScreen-LoadTimeout", () -> {
            final long wait = Math.max(0L, deadline - System.currentTimeMillis());
            ThreadTool.sleep(wait);
            if (this.loading && this.loadGeneration == generation) {
                this.ctx.requestRender();
            }
        });
    }

    private void checkLoadingState() {
        if (this.ctx.selectedMRL == null) {
            this.loading = false;
            this.loadGeneration++;
            return;
        }

        final MRL.Status status = this.ctx.selectedMRL.status();
        // ANY TERMINAL NON-LOADED STATE (ERROR/BLOCKED/EXPIRED/FORGOTTEN) ENDS THE WAIT.
        if (status != MRL.Status.LOADED && status != MRL.Status.FETCHING) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: " + status.name(), null);
            this.ctx.selectedMRL = null;
            return;
        }

        if (status == MRL.Status.LOADED) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.selectedMRLName = this.previewTitle();
            final var sourcesList = this.ctx.selectedMRL.sources();
            this.ctx.availableSources = sourcesList.toArray(MRL.Source[]::new);
            if (this.ctx.availableSources.length == 0) {
                this.ctx.showError("No Sources", "No sources available for this URL", null);
                this.ctx.selectedMRL = null;
                return;
            }

            this.ctx.sourceSelectorIndex = 0;
            this.ctx.selectedSource = this.ctx.availableSources[0];
            // M-07: THIS RUNS INSIDE THE FRAME'S UPDATE PASS — QUEUE THE NAVIGATION SO THE MOUNT SWAP
            // HAPPENS BETWEEN FRAMES (AppContext.execute DRAINS BEFORE THE NEXT RENDER), NEVER MID-TREE
            this.ctx.execute(() -> this.navigator.accept(HomeScreen.Action.PLAYER));
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: Timeout", null);
            this.ctx.selectedMRL = null;
        }
    }

    private static boolean loaded(final MRL mrl) {
        return mrl != null && mrl.status().loaded();
    }

    private String statusLabel() {
        if (this.previewMRL == null) return "AWAITING URL";
        return switch (this.previewMRL.status()) {
            case LOADED -> "READY";
            case FETCHING -> "LOADING";
            case ERROR -> "ERROR";
            case BLOCKED -> "BLOCKED";
            case EXPIRED -> "EXPIRED";
            case FORGOTTEN -> "FORGOTTEN";
        };
    }

    private Color statusColor() {
        if (this.previewMRL == null) return AppTheme.TEXT_FAINT;
        return switch (this.previewMRL.status()) {
            case LOADED -> AppTheme.GREEN;
            case FETCHING -> AppTheme.NEON;
            case ERROR, BLOCKED -> AppTheme.RED;
            case EXPIRED, FORGOTTEN -> AppTheme.AMBER;
        };
    }

    private MRL.Source firstSource() {
        if (!loaded(this.previewMRL) || this.previewMRL.sources().isEmpty()) return null;
        return this.previewMRL.sources().get(0);
    }

    private String previewTitle() {
        final MRL.Source src = this.firstSource();
        final Metadata meta = src != null ? src.metadata() : null;
        if (meta != null && meta.title() != null && !meta.title().isEmpty()) return meta.title();
        return "Custom URL";
    }

    // PLAIN LOOP (NO STREAM/Optional) — THIS RUNS PER FRAME IN THE PREVIEW DRAW PATH
    private String bestQuality() {
        final MRL.Source src = this.firstSource();
        if (src == null) return null;
        MediaQuality best = MediaQuality.UNKNOWN;
        for (final MediaQuality q: src.qualities().keySet()) {
            if (q.threshold > best.threshold) best = q;
        }
        return best == MediaQuality.UNKNOWN ? null : best.name();
    }

    private String deleteLastWord(final String value) {
        int i = value.length();
        while (i > 0 && Character.isWhitespace(value.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(value.charAt(i - 1)) && "/\\?&=#.:_-".indexOf(value.charAt(i - 1)) < 0) i--;
        if (i > 0 && "/\\?&=#.:_-".indexOf(value.charAt(i - 1)) >= 0) i--;
        return value.substring(0, Math.max(0, i));
    }

    // LEGACY amberCube — GLOWING AMBER ACCENT SQUARE (PANEL AND TV-FRAME CORNERS)
    private static void amberCube(final Canvas canvas, final int x, final int y) {
        canvas.glow(x, y, 10, 10, 0f, AppTheme.AMBER, 0.32f);
        canvas.fill(x, y, 10, 10, AppTheme.AMBER);
    }

    // ==========================================================================
    // TREE — BODY (SCRIM + CENTERED DIALOG PANEL WITH THE LEGACY GEOMETRY)
    // ==========================================================================

    // FULL-SLOT ROOT: THE SCRIM COVERS THE WHOLE SLOT (HOME LIVES BEHIND IT) AND THE PANEL IS CENTERED
    // WITH THE LEGACY DIALOG MATH, SLOT-RELATIVE: 8px TOP MARGIN AND A 36px BOTTOM GAP.
    private final class Body extends Group<Body> {

        private final Box scrim;
        private final PanelBox panel;

        private Body(final Box scrim, final PanelBox panel) {
            this.scrim = scrim;
            this.panel = panel;
            this.width = MAX_PARENT;
            this.height = MAX_PARENT;
            this.add(scrim);
            this.add(panel);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.scrim.measure(innerAvailWidth, innerAvailHeight);
            final boolean search = searchMode();
            final int dialogH = Math.max(260, innerAvailHeight - 44);
            final int reserved = 56 + 20 + 28 + 18 + 42 + 12 + 26 + 20 + 38 + 18;
            final int targetPreviewH = Math.max(80, dialogH - reserved);
            final int maxDialogW = Math.max(360, innerAvailWidth - 100);
            final int dialogW = Math.min(Math.max(Math.round(targetPreviewH * 16f / 9f) + 40, 660), maxDialogW);
            // SEARCH MODE HAS NOTHING TO PREVIEW ("NO MEDIA"), SO SHRINK THE PREVIEW AND HAND THE FREED
            // VERTICAL SPACE TO THE RESULTS DROPDOWN. animatePreviewH EASES BETWEEN THE TWO HEIGHTS SO THE
            // PREVIEW GROWS/SHRINKS SMOOTHLY INSTEAD OF SNAPPING WHEN THE MODE FLIPS.
            final int fullPreviewH = Math.round((dialogW - 40) * 9f / 16f);
            preview.height(animatePreviewH(search ? Math.min(fullPreviewH, SEARCH_PREVIEW_H) : fullPreviewH));
            this.panel.measure(dialogW, dialogH);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.scrim.layout(this.left, this.top);
            this.panel.layout(this.left + Math.max(0, (this.measuredWidth - this.panel.measuredWidth()) / 2),
                    this.top + 8);
        }
    }

    // THE DIALOG PANEL BOX — DECOR (SHADOW/GLOW/FILL/BORDER) VIA THE ELEMENT DECORATORS, PLUS THE FOUR
    // AMBER CORNER CUBES DRAWN UNDER THE CHILDREN, EXACTLY LIKE THE LEGACY DRAW ORDER.
    private static final class PanelBox extends Group<PanelBox> {

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            for (final Element<?> child: this.children) child.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            for (final Element<?> child: this.children) child.layout(this.innerLeft(), this.innerTop());
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            amberCube(canvas, this.left - 1, this.top - 1);
            amberCube(canvas, this.left + this.measuredWidth - 9, this.top - 1);
            amberCube(canvas, this.left - 1, this.top + this.measuredHeight - 9);
            amberCube(canvas, this.left + this.measuredWidth - 9, this.top + this.measuredHeight - 9);
            super.onDraw(canvas);
        }
    }

    // DIALOG TITLE ROW — BG_2 STRIP, BOLD TITLE, BOTTOM DIVIDER AND THE RED CLOSE BUTTON PINNED RIGHT
    private final class TitleRow extends Group<TitleRow> {

        private final CloseBtn close = new CloseBtn();

        private TitleRow() {
            this.add(this.close.onClick(b -> navigator.accept(HomeScreen.Action.BACK)));
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.close.measure(26, 26);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.close.layout(this.left + this.measuredWidth - 44, this.top + 14);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.BG_2);
            canvas.fill(this.left, this.top + this.measuredHeight, this.measuredWidth, 1, AppTheme.STROKE_BRIGHT);
            canvas.text("OPEN MULTIMEDIA", this.left + 20,
                    this.top + Math.max(0, (this.measuredHeight - canvas.textHeight(AppTheme.TEXT_BUTTON, true)) / 2f),
                    AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON, true);
            super.onDraw(canvas);
        }
    }

    // LEGACY DIALOG CLOSE — AN ICON-ONLY BUTTON IN THE DESTRUCTIVE RED HUE THAT BRIGHTENS ON HOVER
    private static final class CloseBtn extends Element<CloseBtn> {

        private CloseBtn() {
            this.width = 26;
            this.height = 26;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final Color color = this.hovered ? CLOSE_HOVER : AppTheme.RED;
            IconButton.render(canvas, this.left, this.top, this.measuredWidth, this.measuredHeight,
                    "x", 14, color, color, false, this.hovered, true);
        }
    }

    // LABEL + INPUT ROW — THE "URL OR PATH"/"SEARCH" LABEL SITS 20px ABOVE THE FIELD, WITH THE PASTE(104)
    // AND RELOAD(112) BUTTONS TRAILING AT 8px GAPS, EXACTLY LIKE THE LEGACY ROW.
    private final class InputBlock extends Group<InputBlock> {

        private final Button paste = new Button("PASTE").icon("copy")
                .accent(AppTheme.NEON).textColor(AppTheme.NEON_LIGHT)
                .size(104, 40).onClick(b -> pasteFromClipboard());

        private InputBlock() {
            this.add(field);
            this.add(this.paste);
            this.add(reload);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            field.measure(Math.max(0, innerAvailWidth - 232), 40);
            this.paste.measure(104, 40);
            reload.measure(112, 40);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int y = this.top + 28; // LABEL ZONE ABOVE (LEGACY LABEL AT INPUT TOP - 20)
            field.layout(this.left, y);
            this.paste.layout(this.left + field.measuredWidth() + 8, y);
            reload.layout(this.paste.left() + 104 + 8, y);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean search = searchMode();
            canvas.text(search ? "SEARCH" : "URL OR PATH", this.left, this.top + 8,
                    search ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            super.onDraw(canvas);
        }
    }

    // ZONE BETWEEN THE INPUT ROW AND THE FOOTER: THE STATUS CHIP AT +12 AND THE RESULTS DROPDOWN AT +2
    // OVERLAYING IT (LEGACY STACKING), WITH THE DROPDOWN CAPPED 10px ABOVE THE FOOTER AND ALIGNED TO THE
    // INPUT FIELD'S COLUMN.
    private final class MiddleZone extends Group<MiddleZone> {

        private MiddleZone() {
            this.add(chip);     // UNDER THE DROPDOWN, SAME PAINT ORDER AS THE LEGACY DRAW
            this.add(dropdown);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            chip.measure(innerAvailWidth, 24);
            dropdown.measure(Math.max(0, innerAvailWidth - 232), Math.max(0, innerAvailHeight - 12));
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            chip.layout(this.left, this.top + 12);
            dropdown.layout(this.left, this.top + 2);
        }
    }

    // DETECTED-STATUS CHIP — TINTED FILL + OUTLINE + SQUARE STATUS PIP + LABEL (LEGACY GEOMETRY)
    private final class StatusChip extends Element<StatusChip> {

        private String label = "";
        private Color color = AppTheme.TEXT_FAINT;

        private void set(final String label, final Color color) {
            this.label = label;
            this.color = color;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.contentWidth = (this.ctx != null && this.ctx.text != null
                    ? this.ctx.text.width(this.label, AppTheme.TEXT_BODY) : 0) + 34;
            this.contentHeight = 24;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(x, y, w, h, AppTheme.alpha(this.color, this.color == AppTheme.TEXT_FAINT ? 24 : 36));
            canvas.stroke(x, y, w, h, this.color, 1f);
            canvas.fill(x + 8, y + 8, 8, 8, this.color);
            canvas.glow(x + 8, y + 8, 8, 8, 0f, this.color, 0.5f);
            canvas.text(this.label, x + 24, y + 5, this.color, AppTheme.TEXT_BODY, false);
        }
    }

    // ==========================================================================
    // TREE — PREVIEW PANEL (TV FRAME + LOCALIZED CRT + STATE CONTENT + QUALITY CHIP)
    // ==========================================================================

    private final class PreviewPanel extends Group<PreviewPanel> {

        private final CrtOverlay crt = new CrtOverlay();

        private PreviewPanel() {
            this.add(this.crt);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.crt.measure(Math.max(0, innerAvailWidth - 12), Math.max(0, innerAvailHeight - 12));
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.crt.layout(this.left + 6, this.top + 6);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;

            // TV FRAME (LEGACY LOOK, ALWAYS FOCUSED) WITH THE INNER SCREEN WELL
            canvas.fill(x, y, w, h, AppTheme.BG_2);
            canvas.stroke(x, y, w, h, AppTheme.NEON, 2f);
            canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.48f);
            canvas.fill(x + 6, y + 6, w - 12, h - 12, AppTheme.BG_0);
            canvas.stroke(x + 6, y + 6, w - 12, h - 12, AppTheme.STROKE, 1f);
            amberCube(canvas, x + 2, y + h - 12);
            amberCube(canvas, x + w - 12, y + h - 12);

            super.onDraw(canvas); // LOCALIZED CRT SCANLINES/BANDS, UNDER THE STATE TEXT

            final int ix = x + 6;
            final int iy = y + 6;
            final int iw = w - 12;
            final int ih = h - 12;
            final MRL mrl = previewMRL; // READ ONCE PER DRAW — THE FIELD FLIPS OFF-THREAD
            if (mrl == null) {
                PixelIcon.draw("copy", ix + iw / 2 - 16, iy + ih / 2 - 32, 32, AppTheme.TEXT_FAINT);
                canvas.text("NO MEDIA", ix + iw / 2 - canvas.textWidth("NO MEDIA", AppTheme.TEXT_BODY, false) / 2,
                        iy + ih / 2 + 12, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            } else {
                final MRL.Status status = mrl.status();
                if (status == MRL.Status.FETCHING) {
                    canvas.text("LOADING", ix + 22, iy + ih - 48, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON, true);
                    canvas.text(canvas.text().truncateToWidth(previewUrl, iw - 44, AppTheme.TEXT_SUBTITLE),
                            ix + 22, iy + ih - 24, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
                } else if (status != MRL.Status.LOADED) {
                    // ERROR/BLOCKED/EXPIRED/FORGOTTEN — SHOW THE EXACT STATE.
                    final Color color = statusColor();
                    final String label = statusLabel();
                    PixelIcon.draw("warn", ix + iw / 2 - 16, iy + ih / 2 - 36, 32, color);
                    canvas.text(label, ix + iw / 2 - canvas.textWidth(label, AppTheme.TEXT_BUTTON, true) / 2,
                            iy + ih / 2 + 8, color, AppTheme.TEXT_BUTTON, true);
                } else {
                    final MRL.Source src = firstSource();
                    final Metadata meta = src != null ? src.metadata() : null;
                    final String title = meta != null && meta.title() != null ? meta.title() : previewTitle();
                    final String desc = meta != null && meta.desc() != null ? meta.desc() : previewUrl;
                    final String duration = meta != null && meta.duration() > 0 ? ctx.formatTime(meta.duration()) : "--:--";
                    canvas.gradientV(ix, iy + ih / 2f, iw, ih / 2f, GRADIENT_TOP, AppTheme.alpha(AppTheme.BG_1, 224));
                    canvas.text(canvas.text().truncateToWidth(title.toUpperCase(Locale.ROOT), iw - 44, AppTheme.TEXT_BUTTON, Font.BOLD),
                            ix + 22, iy + ih - 76, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON, true);
                    canvas.text(canvas.text().truncateToWidth(desc, iw - 44, AppTheme.TEXT_SUBTITLE),
                            ix + 22, iy + ih - 48, AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE, false);
                    canvas.text(duration, ix + 22, iy + ih - 24, AppTheme.CYAN, AppTheme.TEXT_BODY, false);
                }
            }

            // BEST-QUALITY CHIP OVER THE FRAME'S TOP-RIGHT CORNER
            final String chipLabel = bestQuality();
            if (chipLabel != null) {
                final int chipW = canvas.textWidth(chipLabel, AppTheme.TEXT_BODY, false) + 18;
                final int chipX = x + w - chipW - 8;
                canvas.fill(chipX, y, chipW, 22, AppTheme.alpha(AppTheme.BG_1, 235));
                canvas.stroke(chipX, y, chipW, 22, AppTheme.NEON, 1f);
                canvas.glow(chipX, y, chipW, 22, 0f, AppTheme.NEON, 0.34f);
                canvas.text(chipLabel, chipX + 9, y + 5, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY, false);
            }
        }
    }

    // ==========================================================================
    // TREE — URL/SEARCH FIELD (CUSTOM: SHARED ctx.customUrlText STATE, CTRL-AWARE EDITING)
    // ==========================================================================

    // CUSTOM INPUT BOX (NOT THE FRAMEWORK TextField): BINDS THE SHARED ctx.customUrlText, FEEDS THE AUTO-SEARCH
    // DEBOUNCE + LIVE PREVIEW ON EVERY EDIT, AND SUPPORTS DELETE / CTRL+BACKSPACE WORD-DELETE OVER FULL UNICODE.
    private final class InputField extends Element<InputField> {

        @Override
        public boolean textInputActive() {
            return this.focused;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            final boolean search = searchMode();
            final String value = ctx.customUrlText != null ? ctx.customUrlText : "";
            if (this.focused) canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.28f);
            canvas.fill(x, y, w, h, AppTheme.BG_2);
            canvas.stroke(x, y, w, h, this.focused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1f);
            PixelIcon.draw(search ? "search" : "link", x + 10, y + (h - 14) / 2, 14,
                    search ? AppTheme.CYAN : AppTheme.TEXT_FAINT);

            final float scale = AppTheme.TEXT_BUTTON;
            final int textX = x + 32;
            final int textY = y + Math.max(0, (h - canvas.textHeight(scale, false)) / 2);
            final String draw = canvas.text().truncateToWidth(value, w - 46, scale);
            final boolean empty = draw.isEmpty();
            canvas.text(empty ? "search, or paste a URL / path..." : draw, textX, textY,
                    empty ? AppTheme.TEXT_FAINT : AppTheme.TEXT, scale, false);
            if (this.focused && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
                final int caretW = empty ? 0 : canvas.textWidth(draw, scale, false);
                final int caretX = Math.min(x + w - 10, textX + caretW + (empty ? -5 : 1));
                canvas.fill(caretX, textY, 2, canvas.textHeight(scale, false), AppTheme.NEON_LIGHT);
            }
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            if (this.contains(mx, my)) {
                if (!this.focused) {
                    this.focused = true;
                    this.invalidate();
                }
                return true;
            }
            this.focused = false;
            return false;
        }

        @Override
        public boolean dispatchChar(final int codepoint) {
            if (!this.focused || loading || ctx.ctrlDown) return false;
            if (codepoint < 32 || codepoint == 127) return false;
            ctx.customUrlText = (ctx.customUrlText == null ? "" : ctx.customUrlText)
                    + new String(Character.toChars(codepoint));
            lastEditMs = System.currentTimeMillis();
            ensurePreviewMRL();
            return true;
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            // BACKSPACE/DELETE ARE FIELD KEYS ONLY WHILE FOCUSED (EDIT ON PRESS+REPEAT, RELEASE SWALLOWED);
            // EVERYTHING ELSE FALLS THROUGH TO THE SCREEN SHORTCUTS
            if (!this.focused || (key != GLFW_KEY_BACKSPACE && key != GLFW_KEY_DELETE)) return false;
            if (action != GLFW_RELEASE && ctx.customUrlText != null && !ctx.customUrlText.isEmpty()) {
                ctx.customUrlText = ctx.ctrlDown
                        ? deleteLastWord(ctx.customUrlText)
                        : ctx.customUrlText.substring(0, ctx.customUrlText.length() - 1);
                lastEditMs = System.currentTimeMillis();
                ensurePreviewMRL();
            }
            return true;
        }
    }

    // ==========================================================================
    // TREE — DROPDOWN ROWS (CANVAS-ONLY, SAME LOOK AS THE LEGACY DROPDOWN)
    // ==========================================================================

    // SECTION HEADER ("RESULTS (n)"/"SEARCHING..."/"RECENT"); THE RECENT VARIANT DRAWS THE SEPARATOR LINE
    private static final class HeadRow extends Element<HeadRow> {

        private final String label;
        private final boolean separator;

        private HeadRow(final String label, final boolean separator) {
            this.label = label;
            this.separator = separator;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            if (this.separator) canvas.fill(x + 8, y + h / 2f, w - 16, 1, AppTheme.STROKE);
            canvas.text(this.label, x + 8, y + (h - canvas.textHeight(AppTheme.TEXT_SUBTITLE, false)) / 2f,
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
        }
    }

    // "LOOKING ACROSS PLATFORMS..." PLACEHOLDER WHILE THE FIRST RESULTS ARE STILL IN FLIGHT
    private static final class WaitRow extends Element<WaitRow> {

        @Override
        protected void onDraw(final Canvas canvas) {
            canvas.text("Looking across platforms...", this.left + 12,
                    this.top + (this.measuredHeight - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2f,
                    AppTheme.NEON, AppTheme.TEXT_BODY, false);
        }
    }

    // ONE SEARCH RESULT: HOVER FILL + 16:9 THUMBNAIL (LIVE MEDIA TEXTURE, ASPECT-FIT) + PLATFORM TAG + TITLE
    private final class ResultRow extends Element<ResultRow> {

        private final PlatformResult result;

        private ResultRow(final PlatformResult result) {
            this.result = result;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            if (this.hovered) canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.NEON_DARK, 70));

            final int thumbH = h - 8;
            final int thumbW = Math.round(thumbH * 16f / 9f);
            final int thumbX = x + 6;
            final int thumbY = y + 4;
            // MEDIA TEXTURE READ PER DRAW; ASPECT-FIT NEVER OVERFLOWS THE CELL, SO THE LIST CLIP SUFFICES
            final MediaPlayer player = thumbPlayer(this.result.thumbnail());
            if (player != null && player.texture() != 0 && player.width() > 0 && player.height() > 0) {
                final float imgAspect = (float) player.width() / player.height();
                final float boxAspect = (float) thumbW / thumbH;
                float bw = thumbW;
                float bh = thumbH;
                if (imgAspect > boxAspect) bh = thumbW / imgAspect;
                else bw = thumbH * imgAspect;
                canvas.mediaImage(player.texture(), thumbX + (thumbW - bw) / 2f, thumbY + (thumbH - bh) / 2f, bw, bh);
            } else {
                canvas.fill(thumbX, thumbY, thumbW, thumbH, AppTheme.BG_0);
                canvas.stroke(thumbX, thumbY, thumbW, thumbH, AppTheme.STROKE, 1f);
                PixelIcon.draw(this.result.thumbnail() == null ? "warn" : "tv",
                        thumbX + (thumbW - 12) / 2, thumbY + (thumbH - 12) / 2, 12, AppTheme.TEXT_FAINT);
            }

            final int textX = thumbX + thumbW + 10;
            final int textMaxW = Math.max(20, x + w - textX - 8);
            final String platform = this.result.platform() == null ? "?" : this.result.platform();
            final String tag = platform + ":";
            final int tagW = canvas.textWidth(tag, AppTheme.TEXT_BODY, true);
            final float textY = y + (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2f;
            canvas.text(tag, textX, textY, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY, true);
            final String title = this.result.title() == null || this.result.title().isEmpty()
                    ? "(untitled)" : this.result.title();
            canvas.text(canvas.text().truncateToWidth(title, Math.max(20, textMaxW - tagW - 6), AppTheme.TEXT_BODY),
                    textX + tagW + 6, textY, AppTheme.TEXT, AppTheme.TEXT_BODY, false);
        }
    }

    // ONE RECENT-QUERY ENTRY: HOVER FILL + SEARCH GLYPH + TRUNCATED QUERY TEXT
    private static final class HistoryRow extends Element<HistoryRow> {

        private final String entry;

        private HistoryRow(final String entry) {
            this.entry = entry;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            if (this.hovered) canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.NEON_DARK, 60));
            PixelIcon.draw("search", x + 10, y + (h - 11) / 2, 11, AppTheme.TEXT_FAINT);
            canvas.text(canvas.text().truncateToWidth(this.entry, w - 40, AppTheme.TEXT_BODY), x + 30,
                    y + (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2f,
                    AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
        }
    }
}
