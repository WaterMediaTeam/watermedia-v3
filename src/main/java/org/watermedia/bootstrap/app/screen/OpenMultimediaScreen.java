package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MediaPlayer;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.platform.PlatformSearch;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.MediaCatalog;
import org.watermedia.bootstrap.app.MediaSession;
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
 * Dialog over the home screen that previews and plays a custom URL or path, or searches the platforms
 * when the input is neither.
 */
public class OpenMultimediaScreen extends Screen {

    private static final long LOAD_TIMEOUT_MS = 30000L;
    private static final int SEARCH_PREVIEW_H = 130;      // SHRUNK PREVIEW HEIGHT WHILE SEARCHING
    private static final long PREVIEW_ANIM_MS = 220L;     // PREVIEW GROW/SHRINK EASE DURATION
    private static final long SEARCH_DEBOUNCE_MS = 320L;  // QUIET PERIOD AFTER TYPING BEFORE AUTO-SEARCHING

    // TRANSLUCENT SO THE HOME SCREEN STAYS VISIBLE BEHIND THE DIALOG
    private static final Color SCRIM_COLOR = AppTheme.rgba(5, 8, 26, 166);
    private static final Color CLOSE_HOVER = AppTheme.rgba(255, 132, 160, 255);
    private static final Color GRADIENT_TOP = AppTheme.rgba(0, 0, 0, 0);

    // DROPDOWN ROW KINDS: ONE FLAT Row LIST FEEDS THE ListView
    private static final int ROW_HEAD = 0;
    private static final int ROW_WAIT = 1;
    private static final int ROW_RESULT = 2;
    private static final int ROW_RECENT = 3;
    private static final int ROW_HISTORY = 4;

    private record Row(int kind, PlatformResult result, String text) {
    }

    // CONSTANT LISTS SO keybinds() ALLOCATES NOTHING PER FRAME
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
    // THE LINK OR QUERY BEING TYPED; THE SCREEN OUTLIVES ITS VISITS, SO IT IS STILL THERE ON THE NEXT ONE
    private String urlText = "";

    // THE PlatformSearch HANDLE FILLS IN OFF-THREAD AND onUpdate POLLS IT EVERY FRAME;
    // searchRenderedDone GUARANTEES ONE FINAL FRAME ONCE IT COMPLETES
    private PlatformSearch search;
    private boolean searchRenderedDone = true;
    // TIME OF THE LAST KEYSTROKE, WHICH THE AUTO-SEARCH DEBOUNCE WAITS ON
    private long lastEditMs;
    // searchMode() CACHED FOR searchModeText, THE TRIMMED INPUT IT WAS COMPUTED FROM (null = NOT COMPUTED YET)
    private boolean searchMode;
    private String searchModeText;
    private float animPreviewH;
    private float animPreviewFrom;
    // -1 MAKES THE NEXT PREVIEW HEIGHT SNAP INSTEAD OF EASING
    private int animPreviewTarget = -1;
    private long animPreviewStart;

    // STATE BEHIND THE CURRENT DROPDOWN ROWS; syncDropdown REBUILDS THEM ONLY WHEN IT CHANGES
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

    // RESULT THUMBNAIL PLAYERS, RELEASED ON EACH NEW SEARCH AND ON EXIT
    private final Map<URI, MediaPlayer> thumbPlayers = new LinkedHashMap<>();
    private final Set<URI> thumbAttempted = new HashSet<>();
    private final Set<URI> thumbSubscribed = new HashSet<>();

    public OpenMultimediaScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // THE SHELL KEEPS THE HOME SCREEN MOUNTED UNDER THIS ONE IN THE CENTER SLOT
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
                // ROWS DRAW THEIR OWN HOVER FILL, SO THE LIST HIGHLIGHTS ARE TRANSPARENT
                .selectionColor(AppTheme.alpha(AppTheme.NEON, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .padding(new Spacing(6, 2, 6, 2))
                .background(AppTheme.BG_2_ALPHA)
                .border(AppTheme.NEON, 1f)
                .shadow(0.45f)
                // A CLICK INSIDE THE BOX BUT NOT ON A ROW IS SWALLOWED
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

        // LOADING MODAL; onUpdate REFRESHES ITS TITLE DOTS AND URL
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
                // A CLICK OUTSIDE THE PANEL BLURS THE FIELD AND NEVER REACHES THE HOME SCREEN
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
        this.animPreviewTarget = -1; // SNAP THE PREVIEW SIZE ON ENTRY INSTEAD OF EASING
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

    // RENDER BACKEND HOT-SWAP: THE ROWS REBUILD THEIR THUMBNAIL PLAYERS ON THE NEW ENGINE AS THEY DRAW
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
        // CARET BLINK, LOADING DOTS, SEARCH POLLING, PREVIEW EASE AND ANIMATED THUMBNAILS
        return this.textInputActive() || this.loading
                || (this.search != null && !this.searchRenderedDone)
                || System.currentTimeMillis() - this.animPreviewStart < PREVIEW_ANIM_MS
                || Previews.animating(this.thumbPlayers.values());
    }

    // ==========================================================================
    // PER-FRAME BINDING — PULLS LIVE STATE INTO THE TREE BEFORE MEASURE/DRAW
    // ==========================================================================

    @Override
    protected void onUpdate() {
        this.ensurePreviewMRL();
        if (this.loading) this.checkLoadingState();
        this.syncLoadDialog();

        final String url = this.urlText != null ? this.urlText : "";
        if (this.loading) {
            this.loadDialog.title("Loading" + ".".repeat((int) ((System.currentTimeMillis() / 500) % 4)));
            this.loadUrl.text(url.isEmpty() ? "(empty)" : url);
        }

        final boolean search = this.searchMode();

        // AUTO-SEARCH THE QUERY ONCE TYPING SETTLES
        if (search) {
            final String query = url.trim();
            final boolean stale = this.search == null || !this.search.query().equals(query);
            if (stale && !query.isEmpty() && System.currentTimeMillis() - this.lastEditMs >= SEARCH_DEBOUNCE_MS) {
                this.doSearch();
            }
        }

        // RELOAD AND SAVE ONLY TAKE A URL OR PATH; THE PRIMARY BUTTON SWITCHES BETWEEN PLAY AND SEARCH
        final boolean empty = url.isEmpty();
        this.reload.enabled(!search && !empty);
        this.save.accent(search || empty ? AppTheme.TEXT_FAINT : AppTheme.NEON_LIGHT).enabled(!search && !empty);
        final Color playColor = empty ? AppTheme.TEXT_FAINT : search ? AppTheme.CYAN : AppTheme.GREEN;
        this.play.label(search ? "SEARCH" : "PLAY").icon(search ? "search" : "play")
                .accent(playColor).textColor(playColor).enabled(!empty);

        // STATUS CHIP: SEARCH PROGRESS IN SEARCH MODE, OTHERWISE THE PREVIEW MRL STATE
        if (search) {
            final boolean done = this.search != null && this.search.done();
            this.chip.set(done ? this.search.results().size() + " RESULTS" : "SEARCHING",
                    done ? AppTheme.CYAN : AppTheme.NEON);
        } else {
            this.chip.set(this.statusLabel(), Previews.color(this.previewMRL));
        }

        this.syncDropdown(search);
        this.searchRenderedDone = this.search == null || this.search.done();
    }

    // FOLLOWS THE loading FLAG, WHICH ALSO FLIPS OUTSIDE INPUT EVENTS
    private void syncLoadDialog() {
        if (this.loading == this.loadShown || this.loadDialog == null) return;
        this.loadShown = this.loading;
        if (this.loading) this.showDialog(this.loadDialog);
        else this.hideDialog(this.loadDialog);
    }

    private void cancelLoading() {
        this.loading = false;
        this.loadGeneration++;
        this.ctx.media.selectedMRL = null;
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
            final String url = this.urlText != null ? this.urlText : "";
            switch (key) {
                // ONLY WHILE THE FIELD IS BLURRED: A TYPED SPACE IS INPUT, NOT THE SAVE SHORTCUT
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
                // ONLY WHILE THE FIELD IS BLURRED: AN R TYPED INTO A URL MUST NOT TRIGGER A NETWORK RELOAD
                case GLFW_KEY_R -> {
                    if (!this.field.textInputActive() && !this.searchMode() && !url.isEmpty()) this.reloadPreviewMRL();
                }
            }
        }
        return true; // MODAL: THE HOME SCREEN UNDERNEATH MUST NEVER SEE KEYS
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (super.dispatchScroll(mx, my, amount)) return true; // DROPDOWN / LOADING DIALOG
        return this.contains(mx, my); // SWALLOWED INSIDE THE SLOT SO THE HOME SCREEN DOES NOT SCROLL
    }

    // ==========================================================================
    // ACTIONS
    // ==========================================================================

    private void pasteFromClipboard() {
        try {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                this.urlText = ((String) clipboard.getData(DataFlavor.stringFlavor)).trim().replace("\"", "");
                this.lastEditMs = System.currentTimeMillis();
                this.ensurePreviewMRL();
            }
        } catch (final Exception ignored) {
            // A BUSY CLIPBOARD OR A FLAVOR ERROR KEEPS THE URL ALREADY TYPED
        }
    }

    private void reloadPreviewMRL() {
        final String url = this.urlText == null ? "" : this.urlText.trim();
        if (url.isEmpty()) return;
        if (this.previewMRL == null || !url.equals(this.previewUrl)) {
            this.previewUrl = "";
            this.ensurePreviewMRL();
            return;
        }

        final int generation = ++this.previewGeneration;
        this.previewMRL = this.previewMRL.reload();
        this.loadStartTime = System.currentTimeMillis();
        this.subscribePreviewMRL(this.previewMRL, generation);
        this.ctx.requestRender();
    }

    private void saveCustomUrl() {
        if (this.urlText == null || this.urlText.isEmpty()) return;
        final String name = this.urlText.length() > 40
                ? this.urlText.substring(0, 37) + "..."
                : this.urlText;
        this.ctx.catalog.customTests.add(new MediaCatalog.TestURI(name, this.urlText, false));
        this.urlText = "";
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    private void playCustomUrl() {
        if (this.urlText == null || this.urlText.isEmpty()) return;

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
            // EXPIRED OR FORGOTTEN MRLS ARE UNUSABLE: REGENERATE A FRESH ONE BEFORE PLAYING
            case EXPIRED, FORGOTTEN -> {
                this.previewUrl = "";
                this.ensurePreviewMRL();
                if (this.previewMRL != null) this.beginLoading(this.previewMRL);
                return;
            }
        }

        this.ctx.media.selectedMRL = this.previewMRL;
        this.ctx.media.selectedMRLName = this.previewTitle();
        this.ctx.media.selectedGroup = null;
        if (!this.ctx.media.selectSources()) {
            this.ctx.showError("No Sources", "No sources available for this URL", null);
            return;
        }
        this.navigator.accept(HomeScreen.Action.PLAYER);
    }

    private void ensurePreviewMRL() {
        final String url = this.urlText == null ? "" : this.urlText.trim();
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

    // TRUE WHEN THE INPUT IS NOT loadable(); MEMOIZED ON THE INPUT TEXT BECAUSE IT RUNS EVERY FRAME
    // AND loadable() BLOCKS ON A FILE SYSTEM STAT
    private boolean searchMode() {
        final String text = this.urlText == null ? "" : this.urlText.trim();
        if (!text.equals(this.searchModeText)) {
            this.searchModeText = text;
            this.searchMode = !text.isEmpty() && !loadable(text);
        }
        return this.searchMode;
    }

    // THE ONE "URL/PATH VS SEARCH QUERY" RULE, SHARED BY searchMode() AND ensurePreviewMRL();
    // text MUST BE TRIMMED AND NON-EMPTY
    private static boolean loadable(final String text) {
        if (new File(text).exists()) return true;
        try {
            return URI.create(text).getScheme() != null;
        } catch (final IllegalArgumentException e) {
            return false; // UNPARSEABLE (E.G. HAS SPACES), SO IT IS A SEARCH QUERY
        }
    }

    // EASE-OUT QUAD TOWARD target; A NEW target RESTARTS FROM THE CURRENT HEIGHT SO RAPID MODE FLIPS
    // STAY SMOOTH
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

    // PlatformAPI SUPERSEDES ANY PREVIOUS SEARCH; onUpdate POLLS THE NEW HANDLE AS RESULTS LAND
    private void doSearch() {
        final String query = this.urlText == null ? "" : this.urlText.trim();
        if (query.isEmpty()) return;
        this.releaseThumbs();
        this.searchRenderedDone = false;
        this.rowsBuilt = false;
        this.search = PlatformAPI.search(query);
        this.ctx.requestRender();
    }

    // THE RESULT'S URL REPLACES THE INPUT, WHICH LEAVES SEARCH MODE SO IT CAN BE PLAYED
    private void selectResult(final PlatformResult result) {
        if (result == null || result.uri() == null) return;
        this.urlText = result.uri().toString();
        this.clearSearch();
        this.field.focused(true);
        this.ensurePreviewMRL();
        this.ctx.sound.play();
        this.ctx.requestRender();
    }

    private void pickRow(final Row row) {
        if (row == null) return;
        if (row.kind() == ROW_RESULT) {
            this.selectResult(row.result());
        } else if (row.kind() == ROW_HISTORY) {
            this.urlText = row.text();
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

    // OUTSIDE SEARCH MODE IT DROPS ANY STALE SEARCH; INSIDE, ROWS REBUILD ONLY WHEN THE POLLED DATA CHANGES
    private void syncDropdown(final boolean searchMode) {
        if (!searchMode) {
            if (this.search != null) this.clearSearch();
            if (this.dropdown.visible()) this.dropdown.visible(false);
            this.rowsBuilt = false;
            return;
        }

        final boolean searching = this.search != null && !this.search.done();
        final List<PlatformResult> results = this.search != null ? this.search.results() : List.of();
        final List<String> history = PlatformAPI.searchHistory();
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
            Previews.release(this.ctx, players);
        }
        this.thumbAttempted.clear();
        this.thumbSubscribed.clear();
    }

    // LAZY IMAGE PLAYER FOR A RESULT THUMBNAIL; null WHILE ITS MRL FETCHES OR WHEN IT HAS NO IMAGE SOURCE
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
                final MediaPlayer player = MediaAPI.player(mrl, i,
                        mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                if (player != null) {
                    player.repeat(true); // LOOP ANIMATED THUMBNAILS (GIF, WEBP)
                    player.start();
                    this.thumbPlayers.put(thumbnail, player);
                }
                return player;
            }
        }
        return null;
    }

    // ==========================================================================
    // PREVIEW / LOADING STATE
    // ==========================================================================

    private void subscribePreviewMRL(final MRL mrl, final int generation) {
        if (mrl == null || Previews.loaded(mrl)) return;
        mrl.subscribe(done -> {
            if (this.previewGeneration == generation && this.previewMRL == done) {
                this.ctx.requestRender();
            }
        });
    }

    private void beginLoading(final MRL mrl) {
        this.ctx.media.selectedMRL = mrl;
        this.ctx.media.selectedMRLName = this.previewTitle();
        this.loading = true;
        this.loadGeneration++;
        this.loadStartTime = System.currentTimeMillis();
        if (!Previews.loaded(mrl)) {
            final int generation = this.loadGeneration;
            mrl.subscribe(done -> {
                if (this.loading && this.loadGeneration == generation && this.ctx.media.selectedMRL == done) {
                    this.ctx.requestRender();
                }
            });
            this.scheduleLoadTimeout(generation);
        }
        this.ctx.requestRender();
    }

    private void scheduleLoadTimeout(final int generation) {
        final long deadline = this.loadStartTime + LOAD_TIMEOUT_MS;
        ThreadTool.thread("OpenMultimediaScreen-LoadTimeout", () -> {
            final long wait = Math.max(0L, deadline - System.currentTimeMillis());
            ThreadTool.sleep(wait);
            if (this.loading && this.loadGeneration == generation) {
                this.ctx.requestRender();
            }
        });
    }

    private void checkLoadingState() {
        if (this.ctx.media.selectedMRL == null) {
            this.loading = false;
            this.loadGeneration++;
            return;
        }

        final MRL.Status status = this.ctx.media.selectedMRL.status();
        // ANY FINAL STATE OTHER THAN LOADED (ERROR, BLOCKED, EXPIRED, FORGOTTEN) ENDS THE WAIT
        if (status != MRL.Status.LOADED && status != MRL.Status.FETCHING) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: " + status.name(), null);
            this.ctx.media.selectedMRL = null;
            return;
        }

        if (status == MRL.Status.LOADED) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.media.selectedMRLName = this.previewTitle();
            if (!this.ctx.media.selectSources()) {
                this.ctx.showError("No Sources", "No sources available for this URL", null);
                this.ctx.media.selectedMRL = null;
                return;
            }
            // QUEUED BECAUSE THIS RUNS INSIDE THE UPDATE PASS: THE MOUNT SWAP MUST HAPPEN BETWEEN FRAMES
            this.ctx.execute(() -> this.navigator.accept(HomeScreen.Action.PLAYER));
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: Timeout", null);
            this.ctx.media.selectedMRL = null;
        }
    }

    private String statusLabel() {
        return this.previewMRL == null ? "AWAITING URL" : Previews.label(this.previewMRL);
    }

    private MRL.Source firstSource() {
        if (!Previews.loaded(this.previewMRL) || this.previewMRL.sources().isEmpty()) return null;
        return this.previewMRL.sources().get(0);
    }

    private String previewTitle() {
        final MRL.Source src = this.firstSource();
        final Metadata meta = src != null ? src.metadata() : null;
        if (meta != null && meta.title() != null && !meta.title().isEmpty()) return meta.title();
        return "Custom URL";
    }

    // RUNS EVERY FRAME IN THE PREVIEW DRAW, SO IT AVOIDS STREAMS
    private String bestQuality() {
        final MRL.Source src = this.firstSource();
        if (src == null) return null;
        MediaQuality best = MediaQuality.UNKNOWN;
        for (final MediaQuality q: src.qualities().keySet()) {
            if (q.height() > best.height()) best = q;
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

    // ==========================================================================
    // TREE — BODY (SCRIM + CENTERED DIALOG PANEL)
    // ==========================================================================

    // THE SCRIM COVERS THE WHOLE SLOT AND THE PANEL IS CENTERED WITH AN 8 PX TOP MARGIN AND A 36 PX BOTTOM GAP
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
            final boolean search = OpenMultimediaScreen.this.searchMode();
            final int dialogH = Math.max(260, innerAvailHeight - 44);
            final int reserved = 56 + 20 + 28 + 18 + 42 + 12 + 26 + 20 + 38 + 18;
            final int targetPreviewH = Math.max(80, dialogH - reserved);
            final int maxDialogW = Math.max(360, innerAvailWidth - 100);
            final int dialogW = MathUtil.clamp(Math.round(targetPreviewH * 16f / 9f) + 40, 660, maxDialogW);
            // SEARCH MODE HAS NOTHING TO PREVIEW, SO THE PREVIEW SHRINKS AND THE RESULTS DROPDOWN GETS THE SPACE
            final int fullPreviewH = Math.round((dialogW - 40) * 9f / 16f);
            OpenMultimediaScreen.this.preview.height(OpenMultimediaScreen.this.animatePreviewH(search ? Math.min(fullPreviewH, SEARCH_PREVIEW_H) : fullPreviewH));
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

    // DIALOG PANEL: THE ELEMENT DECORATORS PAINT ITS FRAME AND THE FOUR AMBER CORNER CUBES GO UNDER THE CHILDREN
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
            canvas.cube(this.left - 1, this.top - 1, 10);
            canvas.cube(this.left + this.measuredWidth - 9, this.top - 1, 10);
            canvas.cube(this.left - 1, this.top + this.measuredHeight - 9, 10);
            canvas.cube(this.left + this.measuredWidth - 9, this.top + this.measuredHeight - 9, 10);
            super.onDraw(canvas);
        }
    }

    // DIALOG TITLE ROW — BG_2 STRIP, BOLD TITLE, BOTTOM DIVIDER AND THE RED CLOSE BUTTON PINNED RIGHT
    private final class TitleRow extends Group<TitleRow> {

        private final IconButton close = new IconButton("x").accent(AppTheme.RED).iconColor(AppTheme.RED)
                .hoverColor(CLOSE_HOVER).size(26, 26);

        private TitleRow() {
            this.add(this.close.onClick(b -> OpenMultimediaScreen.this.navigator.accept(HomeScreen.Action.BACK)));
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

    // INPUT ROW: THE MODE LABEL SITS 20 PX ABOVE THE FIELD AND PASTE AND RELOAD TRAIL IT AT 8 PX GAPS
    private final class InputBlock extends Group<InputBlock> {

        private final Button paste = new Button("PASTE").icon("copy")
                .accent(AppTheme.NEON).textColor(AppTheme.NEON_LIGHT)
                .size(104, 40).onClick(b -> OpenMultimediaScreen.this.pasteFromClipboard());

        private InputBlock() {
            this.add(OpenMultimediaScreen.this.field);
            this.add(this.paste);
            this.add(OpenMultimediaScreen.this.reload);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            OpenMultimediaScreen.this.field.measure(Math.max(0, innerAvailWidth - 232), 40);
            this.paste.measure(104, 40);
            OpenMultimediaScreen.this.reload.measure(112, 40);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int y = this.top + 28; // LABEL ZONE ABOVE THE FIELD
            OpenMultimediaScreen.this.field.layout(this.left, y);
            this.paste.layout(this.left + OpenMultimediaScreen.this.field.measuredWidth() + 8, y);
            OpenMultimediaScreen.this.reload.layout(this.paste.left() + 104 + 8, y);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean search = OpenMultimediaScreen.this.searchMode();
            canvas.text(search ? "SEARCH" : "URL OR PATH", this.left, this.top + 8,
                    search ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            super.onDraw(canvas);
        }
    }

    // ZONE BETWEEN THE INPUT ROW AND THE FOOTER: THE RESULTS DROPDOWN COVERS THE STATUS CHIP, MATCHES
    // THE INPUT FIELD'S WIDTH AND STOPS 10 PX ABOVE THE FOOTER
    private final class MiddleZone extends Group<MiddleZone> {

        private MiddleZone() {
            this.add(OpenMultimediaScreen.this.chip); // ADDED FIRST SO IT PAINTS UNDER THE DROPDOWN
            this.add(OpenMultimediaScreen.this.dropdown);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            OpenMultimediaScreen.this.chip.measure(innerAvailWidth, 24);
            OpenMultimediaScreen.this.dropdown.measure(Math.max(0, innerAvailWidth - 232), Math.max(0, innerAvailHeight - 12));
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            OpenMultimediaScreen.this.chip.layout(this.left, this.top + 12);
            OpenMultimediaScreen.this.dropdown.layout(this.left, this.top + 2);
        }
    }

    // DETECTED-STATUS CHIP: TINTED FILL, OUTLINE, SQUARE PIP AND LABEL
    private static final class StatusChip extends Element<StatusChip> {

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

            // TV FRAME, ALWAYS FOCUSED, WITH THE INNER SCREEN WELL
            canvas.fill(x, y, w, h, AppTheme.BG_2);
            canvas.stroke(x, y, w, h, AppTheme.NEON, 2f);
            canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.48f);
            canvas.fill(x + 6, y + 6, w - 12, h - 12, AppTheme.BG_0);
            canvas.stroke(x + 6, y + 6, w - 12, h - 12, AppTheme.STROKE, 1f);
            canvas.cube(x + 2, y + h - 12, 10);
            canvas.cube(x + w - 12, y + h - 12, 10);

            super.onDraw(canvas); // CRT UNDER THE STATE TEXT

            final int ix = x + 6;
            final int iy = y + 6;
            final int iw = w - 12;
            final int ih = h - 12;
            final MRL mrl = OpenMultimediaScreen.this.previewMRL; // READ ONCE PER DRAW — THE FIELD FLIPS OFF-THREAD
            if (mrl == null) {
                PixelIcon.draw("copy", ix + iw / 2 - 16, iy + ih / 2 - 32, 32, AppTheme.TEXT_FAINT);
                canvas.text("NO MEDIA", ix + iw / 2 - canvas.textWidth("NO MEDIA", AppTheme.TEXT_BODY, false) / 2,
                        iy + ih / 2 + 12, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            } else {
                final MRL.Status status = mrl.status();
                if (status == MRL.Status.FETCHING) {
                    canvas.text("LOADING", ix + 22, iy + ih - 48, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON, true);
                    canvas.text(canvas.text().truncateToWidth(OpenMultimediaScreen.this.previewUrl, iw - 44, AppTheme.TEXT_SUBTITLE),
                            ix + 22, iy + ih - 24, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
                } else if (status != MRL.Status.LOADED) {
                    // ERROR, BLOCKED, EXPIRED OR FORGOTTEN: SHOW THE EXACT STATE
                    final Color color = Previews.color(OpenMultimediaScreen.this.previewMRL);
                    final String label = OpenMultimediaScreen.this.statusLabel();
                    PixelIcon.draw("warn", ix + iw / 2 - 16, iy + ih / 2 - 36, 32, color);
                    canvas.text(label, ix + iw / 2 - canvas.textWidth(label, AppTheme.TEXT_BUTTON, true) / 2,
                            iy + ih / 2 + 8, color, AppTheme.TEXT_BUTTON, true);
                } else {
                    final MRL.Source src = OpenMultimediaScreen.this.firstSource();
                    final Metadata meta = src != null ? src.metadata() : null;
                    final String title = meta != null && meta.title() != null ? meta.title() : OpenMultimediaScreen.this.previewTitle();
                    final String desc = meta != null && meta.description() != null ? meta.description() : OpenMultimediaScreen.this.previewUrl;
                    final String duration = meta != null && meta.durationMs() > 0 ? MediaSession.formatTime(meta.durationMs()) : "--:--";
                    canvas.gradientV(ix, iy + ih / 2f, iw, ih / 2f, GRADIENT_TOP, AppTheme.alpha(AppTheme.BG_1, 224));
                    canvas.text(canvas.text().truncateToWidth(title.toUpperCase(Locale.ROOT), iw - 44, AppTheme.TEXT_BUTTON, Font.BOLD),
                            ix + 22, iy + ih - 76, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON, true);
                    canvas.text(canvas.text().truncateToWidth(desc, iw - 44, AppTheme.TEXT_SUBTITLE),
                            ix + 22, iy + ih - 48, AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE, false);
                    canvas.text(duration, ix + 22, iy + ih - 24, AppTheme.CYAN, AppTheme.TEXT_BODY, false);
                }
            }

            // BEST-QUALITY CHIP OVER THE FRAME'S TOP-RIGHT CORNER
            final String chipLabel = OpenMultimediaScreen.this.bestQuality();
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
    // TREE — URL/SEARCH FIELD
    // ==========================================================================

    // EDITS urlText DIRECTLY, FEEDING THE SEARCH DEBOUNCE AND THE PREVIEW ON EVERY EDIT;
    // CTRL+BACKSPACE DELETES A WORD
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
            final boolean search = OpenMultimediaScreen.this.searchMode();
            final String value = OpenMultimediaScreen.this.urlText != null ? OpenMultimediaScreen.this.urlText : "";
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
            if (!this.focused || OpenMultimediaScreen.this.loading || OpenMultimediaScreen.this.ctx.ctrlDown) return false;
            if (codepoint < 32 || codepoint == 127) return false;
            OpenMultimediaScreen.this.urlText = (OpenMultimediaScreen.this.urlText == null ? "" : OpenMultimediaScreen.this.urlText)
                    + new String(Character.toChars(codepoint));
            OpenMultimediaScreen.this.lastEditMs = System.currentTimeMillis();
            OpenMultimediaScreen.this.ensurePreviewMRL();
            return true;
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            // BACKSPACE/DELETE ARE FIELD KEYS ONLY WHILE FOCUSED (EDIT ON PRESS+REPEAT, RELEASE SWALLOWED);
            // EVERYTHING ELSE FALLS THROUGH TO THE SCREEN SHORTCUTS
            if (!this.focused || (key != GLFW_KEY_BACKSPACE && key != GLFW_KEY_DELETE)) return false;
            if (action != GLFW_RELEASE && OpenMultimediaScreen.this.urlText != null && !OpenMultimediaScreen.this.urlText.isEmpty()) {
                OpenMultimediaScreen.this.urlText = OpenMultimediaScreen.this.ctx.ctrlDown
                        ? OpenMultimediaScreen.this.deleteLastWord(OpenMultimediaScreen.this.urlText)
                        : OpenMultimediaScreen.this.urlText.substring(0, OpenMultimediaScreen.this.urlText.length() - 1);
                OpenMultimediaScreen.this.lastEditMs = System.currentTimeMillis();
                OpenMultimediaScreen.this.ensurePreviewMRL();
            }
            return true;
        }
    }

    // ==========================================================================
    // TREE — DROPDOWN ROWS
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
            final MediaPlayer player = OpenMultimediaScreen.this.thumbPlayer(this.result.thumbnail());
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
            final String platform = this.result.platform() == null ? "?" : this.result.platform().name();
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
