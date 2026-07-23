package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Badge;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Media (MRL) selector as a fully-retained screen: a {@link Header} band, a left column with the filter
 * field, a section head row and the selectable media rows in a {@link ListView}, and a live preview stack
 * on the right — the TV frame with the playing thumbnail under a localized {@link CrtOverlay}, the
 * metadata panel (title, media-type tag, URI, status pip) and real COPY/PLAY {@link Button}s. The
 * loading wait is a modal {@link Dialog} on the screen overlay.
 */
public final class MRLSelectorScreen extends Screen {

    private static final long LOAD_TIMEOUT_MS = 30000L;

    // CACHED, REFERENCE-STABLE KEYBIND LISTS — LET KeybindsBar SKIP ITS REBUILD ON THE STEADY PER-FRAME PATH
    private static final List<Keybind> KEYS_LOADING = List.of(new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_DEFAULT = List.of(
            new Keybind("ARROWS", "Navigate"), new Keybind("ENTER", "Select"), new Keybind("ESC", "Back"));

    private final Consumer<HomeScreen.Action> navigator;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private long loadStartTime;
    private AppContext.TestURI pendingUri;

    private Header header;
    private SearchField search;
    private ListView<AppContext.TestURI> list;
    private PreviewPane preview;
    // HEADER ROW ELEMENTS — TEXT REFRESHED EACH FRAME FROM THE LIVE GROUP NAME / ITEM COUNT / FAILED COUNT
    private Text headerLabel;
    private Text headerCount;
    private Badge headerFailed;
    // LOADING MODAL — BUILT LAZILY, SHOWN ON THE SCREEN OVERLAY WHILE AN MRL FETCH IS IN FLIGHT
    private Dialog loadDialog;
    private Text loadName;
    // CURRENTLY VISIBLE (SEARCH-FILTERED) ITEMS — THE ListView'S BACKING DATA
    private List<AppContext.TestURI> filtered = new ArrayList<>();

    // THUMBNAIL PLAYERS KEYED BY MRL NAME
    private final Map<String, MediaPlayer> thumbPlayers = new LinkedHashMap<>();
    private final Set<String> thumbAttempted = new HashSet<>();
    private final Set<String> thumbSubscribed = new HashSet<>();
    private final Set<String> groupSubscriptions = new HashSet<>();

    public MRLSelectorScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    protected Element<?> build() {
        this.search = new SearchField()
                .onChange(v -> this.applyFilter())
                .width(Element.MAX_PARENT)
                .height(30);

        // SECTION HEAD ROW — A HORIZONTAL LAYOUT: NEON BAR + GROUP LABEL + ITEM COUNT + [FLEX SPACER] + FAILED BADGE
        this.headerLabel = new Text().bold(true).scale(AppTheme.TEXT_SECTION).color(AppTheme.NEON).gravity(Gravity.CENTER);
        this.headerCount = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_FAINT).gravity(Gravity.CENTER);
        this.headerFailed = new Badge().gravity(Gravity.CENTER);
        final Parent headRow = Parent.row()
                .spacing(8)
                .width(Element.MAX_PARENT)
                .height(24)
                .add(new Box().size(4, 20).background(AppTheme.NEON).glow(AppTheme.NEON, 0.16f).gravity(Gravity.CENTER))
                .add(this.headerLabel)
                .add(this.headerCount)
                .add(new Box().size(0, 1).weight(1f))
                .add(this.headerFailed);

        this.list = new ListView<AppContext.TestURI>()
                .rowHeight(60)
                .spacing(6)
                .rowFactory((uri, index) -> {
                    final MediaRow row = new MediaRow(uri);
                    // HOVER SELECTS THE ROW (selectOnHover) AND, LIKE THE LEGACY MOUSE-MOVE PATH, CHIMES ON CHANGE
                    row.onHover(v -> { if (!v.selected()) this.ctx.playSelectionSound(); });
                    return row;
                })
                .onSelect((uri, index) -> this.openRow(uri))
                .selectOnHover(true)
                // ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                // WEIGHT 1 → THE LIST FILLS THE LEFTOVER VERTICAL SPACE AND SCISSORS ROWS WITHIN ITS OWN BOX
                .width(Element.MAX_PARENT)
                .weight(1f);

        // LEFT COLUMN: SEARCH, SECTION HEAD, LIST — EACH CONFINED TO ITS BOX BY THE LAYOUT (PADDING/SPACING)
        final Parent leftCol = Parent.column()
                .spacing(8)
                .padding(new Spacing(8, 4, 8, 10))
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(this.search)
                .add(headRow)
                .add(this.list);

        this.preview = new PreviewPane();
        this.header = new Header().name("Select media").right("v" + WaterMedia.VERSION);
        return Parent.column()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(this.header)
                .add(new SelectorPane(leftCol).width(Element.MAX_PARENT).weight(1f));
    }

    @Override
    public void onEnter() {
        super.onEnter();
        this.stopLoad();
        this.groupSubscriptions.clear();
        this.thumbSubscribed.clear();
        this.search.value("").focused(false);
        this.applyFilter();
        this.list.selection(0);
        this.subscribeGroupMRLs();
    }

    @Override
    public void onExit() {
        this.stopLoad();
        this.releaseThumbs();
    }

    @Override
    public void releaseMedia() {
        // HOT-SWAP HOOK — DROP EVERY THUMBNAIL PLAYER (OFF-THREAD) SO THE NEXT FRAME REBUILDS ON THE NEW ENGINE
        this.releaseThumbs();
    }

    @Override
    public List<Keybind> keybinds() {
        return this.loading ? KEYS_LOADING : KEYS_DEFAULT;
    }

    @Override
    public boolean continuous() {
        // CARET BLINK, LOADING DOTS AND LIVE THUMBNAILS — THE GLOBAL CRT ANIMATION IS THE SHELL'S CONCERN
        return this.loading || this.textInputActive() || this.thumbsAnimating();
    }

    @Override
    protected void onUpdate() {
        if (this.search == null || this.ctx == null || this.ctx.selectedGroup == null) return;
        if (this.loading) {
            this.checkLoadingState();
            // THE WAIT ENDED THIS FRAME (NAVIGATED OR ERRORED) — SKIP THE REBUILDS SO A NAVIGATION'S
            // onExit RELEASE IS NOT UNDONE BY RE-CREATING THUMBNAIL PLAYERS ON AN ALREADY-EXITED SCREEN
            if (!this.loading) return;
            // ANIMATED DOTS ON THE MODAL TITLE (LEGACY 500ms CADENCE)
            if (this.loadDialog != null) {
                this.loadDialog.title("Loading" + ".".repeat((int) ((System.currentTimeMillis() / 500L) % 4L)));
            }
        }
        this.subscribeGroupMRLs();
        this.updateThumbs(this.activeThumbNames());
        this.header.sub(this.ctx.selectedGroup.name());
        this.headerLabel.text(this.ctx.selectedGroup.name().toUpperCase(Locale.ROOT));
        this.headerCount.text(this.filtered.size() + " ITEMS");
        int failed = 0;
        for (final MRL mrl: this.ctx.groupMRLs.values()) {
            if (failed(mrl)) failed++;
        }
        this.headerFailed.label(failed + " FAILED").color(failed > 0 ? AppTheme.RED : AppTheme.TEXT_FAINT);
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // THE TREE FIRST: A FOCUSED FILTER FIELD OR THE LOADING MODAL CONSUMES ITS OWN KEYS (ESC CANCELS)
        if (super.dispatchKey(key, action)) return true;
        if (action != GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_SLASH -> this.search.focused(true).invalidate();
            case GLFW_KEY_UP -> {
                this.list.moveSelection(-1);
                this.ctx.playSelectionSound();
            }
            case GLFW_KEY_DOWN -> {
                this.list.moveSelection(1);
                this.ctx.playSelectionSound();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                final AppContext.TestURI uri = this.selectedUri();
                if (uri != null) {
                    final MRL mrl = this.mrlFor(uri);
                    if (regenerable(mrl)) this.reloadMRL(uri);
                    else this.handleSelect(uri);
                }
            }
            case GLFW_KEY_ESCAPE -> {
                this.ctx.clearGroupState();
                this.navigator.accept(HomeScreen.Action.BACK);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // RECOMPUTES THE SEARCH-FILTERED ITEM LIST, KEEPING THE SAME ITEM SELECTED WHEN IT SURVIVES THE FILTER
    private void applyFilter() {
        final AppContext.TestURI prev = this.selectedUri();
        final List<AppContext.TestURI> next = new ArrayList<>();
        if (this.ctx.selectedGroup != null) {
            final String q = this.search.value() == null ? "" : this.search.value().trim().toLowerCase(Locale.ROOT);
            for (final AppContext.TestURI uri: this.ctx.selectedGroup.uris()) {
                if (q.isEmpty() || uri.name().toLowerCase(Locale.ROOT).contains(q) || uri.uri().toLowerCase(Locale.ROOT).contains(q)) {
                    next.add(uri);
                }
            }
        }
        this.filtered = next;
        this.list.items(next);
        final int idx = prev == null ? 0 : next.indexOf(prev);
        this.list.selection(idx < 0 ? 0 : idx);
    }

    // THE ITEM DRIVING THE PREVIEW/ACTIONS: THE SELECTED ROW, OR THE FIRST ITEM WHEN THE FILTER HIDES EVERYTHING
    private AppContext.TestURI selectedUri() {
        if (this.ctx.selectedGroup == null) return null;
        final AppContext.TestURI[] uris = this.ctx.selectedGroup.uris();
        if (!this.filtered.isEmpty()) {
            final int i = this.list.selectedIndex();
            if (i >= 0 && i < this.filtered.size()) return this.filtered.get(i);
        }
        return uris.length > 0 ? uris[0] : null;
    }

    private void openRow(final AppContext.TestURI uri) {
        this.search.focused(false); // A ROW ACTIVATION BLURS THE FILTER FIELD (LEGACY BEHAVIOR)
        this.handleSelect(uri);
    }

    private void subscribeGroupMRLs() {
        if (this.ctx.selectedGroup == null) return;
        for (final AppContext.TestURI uri: this.ctx.selectedGroup.uris()) {
            final MRL mrl = this.ctx.groupMRLs.get(uri.name());
            if (mrl != null && !loaded(mrl) && this.groupSubscriptions.add(uri.name())) {
                mrl.subscribe(done -> this.ctx.requestRender());
            }
        }
    }

    private void subscribeThumbMRL(final URI uri, final MRL mrl) {
        if (uri == null || mrl == null || loaded(mrl)) return;
        if (this.thumbSubscribed.add(uri.toString())) {
            mrl.subscribe(done -> this.ctx.requestRender());
        }
    }

    // RESOLVE-OR-CREATE, WITH SUBSCRIBE + REQUEST-RENDER. ONLY CALL FROM onUpdate / INPUT PATHS — NEVER FROM onDraw
    private MRL mrlFor(final AppContext.TestURI uri) {
        if (uri == null) return null;
        MRL mrl = this.ctx.groupMRLs.get(uri.name());
        if (mrl != null) return mrl;
        mrl = MediaAPI.mrl(uri.uri());
        this.ctx.groupMRLs.put(uri.name(), mrl);
        if (!loaded(mrl) && this.groupSubscriptions.add(uri.name())) {
            mrl.subscribe(done -> this.ctx.requestRender());
        }
        this.ctx.requestRender();
        return mrl;
    }

    // READ-ONLY LOOKUP FOR THE DRAW PATH — RETURNS null ON A MISS (RESOLUTION HAPPENS IN onUpdate VIA mrlFor)
    private MRL mrlOf(final AppContext.TestURI uri) {
        return uri == null ? null : this.ctx.groupMRLs.get(uri.name());
    }

    private void scheduleLoadTimeout(final int generation) {
        final long deadline = this.loadStartTime + LOAD_TIMEOUT_MS;
        ThreadTool.createStarted("MRLSelectorScreen-LoadTimeout", () -> {
            final long wait = Math.max(0L, deadline - System.currentTimeMillis());
            ThreadTool.sleep(wait);
            if (this.loading && this.loadGeneration == generation) {
                this.ctx.requestRender();
            }
        });
    }

    private void releaseInactiveThumbs(final Set<String> activeNames) {
        List<MediaPlayer> evicted = null;
        final Iterator<Map.Entry<String, MediaPlayer>> it = this.thumbPlayers.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, MediaPlayer> entry = it.next();
            if (activeNames.contains(entry.getKey())) continue;
            // REMOVE FROM THE ACTIVE MAP FIRST, THEN HAND OFF FOR BACKGROUND RELEASE (SEE releaseAsync)
            if (evicted == null) evicted = new ArrayList<>();
            evicted.add(entry.getValue());
            it.remove();
            this.thumbAttempted.remove(entry.getKey());
        }
        if (evicted != null) this.releaseAsync(evicted);
    }

    // BACKGROUND RELEASE OF EVICTED THUMBNAIL PLAYERS (ALREADY REMOVED FROM thumbPlayers): stop() IS NON-BLOCKING,
    // THE BLOCKING release() RUNS ON THE SHUTDOWN-AWAITED CHAIN SO A TEARDOWN CANNOT OUTLIVE THE RENDER-DEVICE DESTROY.
    private void releaseAsync(final List<MediaPlayer> players) {
        if (players.isEmpty()) return;
        for (final MediaPlayer player: players) player.stop();
        // HAND THE BLOCKING release() TO THE SHARED, SHUTDOWN-AWAITED RELEASE CHAIN SO A THUMBNAIL TEARDOWN
        // IN FLIGHT CANNOT OUTLIVE THE RENDER-DEVICE DESTROY ON EXIT (VULKAN SAFETY).
        this.ctx.releaseAsync("WaterMedia-ThumbnailRelease", () -> {
            for (final MediaPlayer player: players) player.release();
        });
    }

    // KEEPS THUMBNAIL PLAYERS SCOPED TO ENTRIES THE SELECTOR IS CURRENTLY SHOWING.
    private void updateThumbs(final Set<String> activeNames) {
        if (this.ctx.selectedGroup == null) return;

        this.releaseInactiveThumbs(activeNames);

        for (final AppContext.TestURI uri: this.ctx.selectedGroup.uris()) {
            final String name = uri.name();
            if (!activeNames.contains(name)) continue;
            if (this.thumbPlayers.containsKey(name)) continue;
            if (this.thumbAttempted.contains(name)) continue;

            final MRL mrl = this.mrlFor(uri);
            if (mrl == null) continue;
            if (!loaded(mrl)) continue; // ONLY BUILD THUMBNAILS FOR FULLY LOADED MRLS

            final var sources = mrl.sources();
            if (sources.isEmpty()) {
                this.thumbAttempted.add(name);
                continue;
            }

            MediaPlayer player = null;
            boolean pendingThumbnail = false;

            // TRY THUMBNAIL URI FIRST
            for (final MRL.Source src: sources) {
                final URI thumbnailUri = src.thumbnail();
                if (thumbnailUri == null) continue;
                final MRL thumbnailMrl = MediaAPI.mrl(thumbnailUri.toString());
                final MRL.Status thumbStatus = thumbnailMrl.status();
                if (thumbStatus == MRL.Status.FETCHING) {
                    pendingThumbnail = true;
                    this.subscribeThumbMRL(thumbnailUri, thumbnailMrl);
                    break;
                }
                if (thumbStatus != MRL.Status.LOADED) continue; // ERROR/EXPIRED/BLOCKED/FORGOTTEN — TRY NEXT SOURCE
                player = MediaAPI.createPlayer(thumbnailMrl, RenderSystem.mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                if (player != null) break;
            }

            if (pendingThumbnail) continue; // RETRY NEXT FRAME

            // FALLBACK: USE FIRST IMAGE SOURCE DIRECTLY
            if (player == null) {
                for (int i = 0; i < sources.size(); i++) {
                    if (sources.get(i).type() == MediaType.IMAGE) {
                        player = MediaAPI.createPlayer(mrl, i, RenderSystem.mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                        break;
                    }
                }
            }

            this.thumbAttempted.add(name);

            if (player != null) {
                player.repeat(true);
                player.start();
                this.thumbPlayers.put(name, player);
            }
        }
    }

    private void releaseThumbs() {
        if (!this.thumbPlayers.isEmpty()) {
            // SNAPSHOT AND CLEAR FIRST SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING PLAYER
            final List<MediaPlayer> players = new ArrayList<>(this.thumbPlayers.values());
            this.thumbPlayers.clear();
            this.releaseAsync(players);
        }
        this.thumbAttempted.clear();
        this.thumbSubscribed.clear();
        this.groupSubscriptions.clear();
    }

    private void handleSelect(final AppContext.TestURI uri) {
        this.ctx.selectedMRLName = uri.name();
        final MRL mrl = this.mrlFor(uri);
        this.ctx.selectedMRL = mrl;

        if (mrl == null) {
            this.ctx.showError("Null", "The MRL is null", null);
            return;
        }

        switch (mrl.status()) {
            case LOADED -> this.proceedWithMRL();
            case FETCHING -> {
                this.loading = true;
                this.loadGeneration++;
                this.loadStartTime = System.currentTimeMillis();
                this.pendingUri = uri;
                mrl.subscribe(done -> this.ctx.requestRender());
                this.scheduleLoadTimeout(this.loadGeneration);
                this.showLoadDialog();
                this.ctx.requestRender();
            }
            case ERROR -> this.ctx.showError("Error", "Unable to open media, exception occurred on opening", null);
            case BLOCKED -> this.ctx.showError("Blocked", "This media was blocked by the platform", null);
            // EXPIRED SOURCES AND FORGOTTEN (CACHE-EVICTED) MRLS ARE NO LONGER USABLE —
            // DROP THE CACHED INSTANCE SO THE NEXT ACCESS REGENERATES A FRESH ONE.
            case EXPIRED, FORGOTTEN -> this.ctx.showError("MRL expired", "Re-freshing MRL", () -> {
                this.ctx.selectedMRL = null;
                this.ctx.groupMRLs.remove(uri.name());
            });
        }
    }

    private void reloadMRL(final AppContext.TestURI uri) {
        if (uri == null) return;
        final String name = uri.name();
        MRL mrl = this.ctx.groupMRLs.get(name);
        // FORGOTTEN MRLS WERE EVICTED FROM THE CACHE — FETCH A FRESH INSTANCE INSTEAD
        // OF RELOADING THE DISPOSED ONE.
        if (mrl == null || mrl.status() == MRL.Status.FORGOTTEN) {
            mrl = MediaAPI.mrl(uri.uri());
            this.ctx.groupMRLs.put(name, mrl);
        } else {
            mrl.reload();
        }

        final MediaPlayer thumbnail = this.thumbPlayers.remove(name);
        if (thumbnail != null) this.releaseAsync(List.of(thumbnail));
        this.thumbAttempted.remove(name);
        this.groupSubscriptions.remove(name);
        if (!loaded(mrl) && this.groupSubscriptions.add(name)) {
            mrl.subscribe(done -> this.ctx.requestRender());
        }
        this.ctx.selectedMRLName = name;
        this.ctx.selectedMRL = mrl;
        this.ctx.requestRender();
    }

    private void proceedWithMRL() {
        final var sourcesList = this.ctx.selectedMRL.sources();
        this.ctx.availableSources = sourcesList.toArray(MRL.Source[]::new);
        if (this.ctx.availableSources.length == 0) return;

        this.ctx.sourceSelectorIndex = 0;
        this.ctx.selectedSource = this.ctx.availableSources[0];
        this.navigator.accept(HomeScreen.Action.PLAYER);
    }

    // ENDS THE LOADING WAIT: INVALIDATES THE TIMEOUT GENERATION AND HIDES THE MODAL
    private void stopLoad() {
        this.loading = false;
        this.loadGeneration++;
        this.pendingUri = null;
        if (this.loadDialog != null) this.hideDialog(this.loadDialog);
    }

    private void showLoadDialog() {
        if (this.loadDialog == null) {
            // LEGACY BLUE ACCENT / GRAY BODY → NEAREST THEME HUES (NEON / TEXT_SOFT)
            this.loadName = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT).width(Element.MAX_PARENT);
            this.loadDialog = new Dialog()
                    .accent(AppTheme.NEON)
                    .title("Loading")
                    .panelWidth(400)
                    .onDismiss(this::stopLoad); // ESC CANCELS THE WAIT (LEGACY BEHAVIOR)
            this.loadDialog.content(this.loadName);
            this.loadDialog.content(new Text("ESC to cancel").scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT).width(Element.MAX_PARENT));
        }
        this.loadName.text(this.pendingUri == null ? "" : this.pendingUri.name());
        this.hideDialog(this.loadDialog); // IDEMPOTENT RE-SHOW
        this.showDialog(this.loadDialog);
    }

    private void checkLoadingState() {
        if (this.ctx.selectedMRL == null) {
            this.stopLoad();
            return;
        }

        final MRL.Status status = this.ctx.selectedMRL.status();
        if (status == MRL.Status.LOADED) {
            this.stopLoad();
            this.proceedWithMRL();
            return;
        }

        // ANY TERMINAL NON-LOADED STATE (ERROR/BLOCKED/EXPIRED/FORGOTTEN) ENDS THE WAIT.
        if (status != MRL.Status.FETCHING) {
            this.stopLoad();
            this.ctx.showError("Error", "Unable to open media: " + status.name(), null);
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.stopLoad();
            this.ctx.showError("Load Error", "MRL loading timed out", null);
        }
    }

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

    // THE SELECTED ITEM PLUS EVERY ROW CURRENTLY ON SCREEN (FROM THE PREVIOUS FRAME'S LAYOUT). THIS SCOPES
    // THUMBNAIL PLAYERS TO WHAT THE SELECTOR IS SHOWING, EXACTLY LIKE THE LEGACY VISIBLE-WINDOW COMPUTATION.
    private Set<String> activeThumbNames() {
        final Set<String> active = new LinkedHashSet<>();
        final AppContext.TestURI selected = this.selectedUri();
        if (selected != null) active.add(selected.name());
        final int viewTop = this.list.top();
        final int viewBottom = viewTop + this.list.measuredHeight();
        for (final Element<?> child: this.list.children()) {
            if (!(child instanceof MediaRow row) || row.measuredHeight() <= 0) continue;
            final int rowTop = row.top();
            if (rowTop + row.measuredHeight() > viewTop && rowTop < viewBottom) active.add(row.uri.name());
        }
        return active;
    }

    // ===== SHARED CANVAS PORTS OF THE LEGACY CHROME PRIMITIVES (SAME PIXELS, NO CHROME HELPERS) =====

    // PORT OF THE TV FRAME — OUTER PLATE, FOCUS BORDER/GLOW, INNER SCREEN WELL, AMBER FOOT CUBES
    private static void tvFrame(final Canvas canvas, final int x, final int y, final int w, final int h, final boolean focused) {
        canvas.fill(x, y, w, h, AppTheme.BG_2);
        canvas.stroke(x, y, w, h, focused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 2f);
        if (focused) canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.48f);
        canvas.fill(x + 6, y + 6, w - 12, h - 12, AppTheme.BG_0);
        canvas.stroke(x + 6, y + 6, w - 12, h - 12, AppTheme.STROKE, 1f);
        cube(canvas, x + 2, y + h - 12);
        cube(canvas, x + w - 12, y + h - 12);
    }

    private static void cube(final Canvas canvas, final int x, final int y) {
        canvas.glow(x, y, 10, 10, 0f, AppTheme.AMBER, 0.32f);
        canvas.fill(x, y, 10, 10, AppTheme.AMBER);
    }

    // PORT OF THE AMBER CORNER TRIANGLE — ROW-STRIP RASTER (THE CANVAS HAS NO TRIANGLE PRIMITIVE)
    private static void triangle(final Canvas canvas, final int x, final int y, final int size, final boolean left) {
        canvas.glow(x, y, size, size, 0f, AppTheme.AMBER, 0.32f);
        for (int i = 0; i < size; i++) {
            if (left) {
                canvas.fill(x, y + i, size - i, 1f, AppTheme.AMBER);
            } else {
                canvas.fill(x + size - 1 - i, y + i, i + 1, 1f, AppTheme.AMBER);
            }
        }
    }

    // PORT OF THE STATUS PIP — CIRCLE AS A HORIZONTAL-STRIP RASTER (THE CANVAS HAS NO CIRCLE PRIMITIVE)
    private static void pip(final Canvas canvas, final int x, final int y, final int size, final Color color, final boolean circle) {
        if (circle) {
            final float r = size / 2f;
            for (int i = 0; i < size; i++) {
                final float dy = i + 0.5f - r;
                final float half = (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
                canvas.fill(x + r - half, y + i, half * 2f, 1f, color);
            }
        } else {
            canvas.fill(x, y, size, size, color);
        }
        canvas.glow(x, y, size, size, 0f, color, 0.5f);
    }

    // SHARED THUMBNAIL CONTENT — LIVE FRAME (COVER-FIT + CLIP) OR THE STATUS FALLBACK. THE CRT EFFECT ON
    // TOP IS A CrtOverlay CHILD OF THE CALLING CONTAINER, DRAWN AFTER THIS (LEGACY DRAW ORDER).
    private void drawThumb(final Canvas canvas, final AppContext.TestURI uri, final int x, final int y,
                           final int w, final int h, final boolean mini) {
        final MediaPlayer player = this.thumbPlayers.get(uri.name());
        if (player != null && player.texture() != 0 && player.width() > 0 && player.height() > 0) {
            canvas.pushClip(x, y, w, h);
            final float imgAspect = (float) player.width() / player.height();
            final float boxAspect = (float) w / h;
            float bw = w;
            float bh = h;
            float bx = x;
            float by = y;
            if (imgAspect > boxAspect) {
                bw = h * imgAspect;
                bx = x + (w - bw) / 2f;
            } else {
                bh = w / imgAspect;
                by = y + (h - bh) / 2f;
            }
            canvas.mediaImage(player.texture(), bx, by, bw, bh);
            canvas.popClip();
            return;
        }
        final MRL mrl = this.mrlOf(uri);
        final MRL.Status status = mrl == null ? null : mrl.status();
        final boolean ready = status == MRL.Status.LOADED;
        // ERROR/BLOCKED/EXPIRED/FORGOTTEN — A FINAL STATE THAT NEEDS REGENERATION.
        final boolean failed = status != null && status != MRL.Status.LOADED && status != MRL.Status.FETCHING;
        canvas.fill(x, y, w, h, AppTheme.BG_0);
        if (mini && !failed) {
            if (ready) {
                final float okScale = AppTheme.TEXT_TINY;
                canvas.text("[OK]", x + (w - canvas.textWidth("[OK]", okScale, false)) / 2,
                        y + (h - canvas.textHeight(okScale, false)) / 2f, AppTheme.GREEN, okScale, false);
            }
            return;
        }
        final String label = failed ? (mini ? "[" + statusLabel(mrl) + "]" : statusLabel(mrl))
                : ready ? "[ media thumbnail ]" : "LOADING...";
        final Color color = failed ? statusColor(mrl) : ready ? AppTheme.TEXT_SOFT : AppTheme.NEON;
        if (!mini && failed) {
            PixelIcon.draw("warn", x + w / 2 - 14, y + h / 2 - 36, 28, statusColor(mrl));
        }
        final float scale = mini ? AppTheme.TEXT_TINY : AppTheme.TEXT_BUTTON;
        final float textY = mini
                ? y + (h - canvas.textHeight(scale, false)) / 2f
                : y + h / 2f - this.text.lineHeight(scale) / 2f + 22;
        canvas.text(label, x + (w - canvas.textWidth(label, scale, false)) / 2, textY, color, scale, false);
    }

    // ===== MRL STATUS HELPERS =====
    // STATES ARE MUTUALLY EXCLUSIVE: LOADED (usable), FETCHING (in flight), ERROR/BLOCKED
    // (load failed), EXPIRED (sources no longer valid), FORGOTTEN (evicted from the cache).
    // THE BOOLEAN PREDICATES LIVE ON MRL.Status; THESE WRAP THEM NULL-SAFELY FOR THE UI.

    private static boolean loaded(final MRL mrl) {
        return mrl != null && mrl.status().loaded();
    }

    private static boolean failed(final MRL mrl) {
        return mrl != null && mrl.status().failed();
    }

    private static boolean regenerable(final MRL mrl) {
        return mrl != null && mrl.status().regenerable();
    }

    private static String statusLabel(final MRL mrl) {
        if (mrl == null) return "NULL";
        return switch (mrl.status()) {
            case LOADED -> "READY";
            case FETCHING -> "LOADING";
            case ERROR -> "ERROR";
            case BLOCKED -> "BLOCKED";
            case EXPIRED -> "EXPIRED";
            case FORGOTTEN -> "FORGOTTEN";
        };
    }

    private static Color statusColor(final MRL mrl) {
        if (mrl == null) return AppTheme.TEXT_FAINT;
        return switch (mrl.status()) {
            case LOADED -> AppTheme.GREEN;
            case FETCHING -> AppTheme.NEON;
            case ERROR, BLOCKED -> AppTheme.RED;
            case EXPIRED, FORGOTTEN -> AppTheme.AMBER;
        };
    }

    private static MediaType firstMediaType(final MRL mrl) {
        if (!loaded(mrl) || mrl.sources().isEmpty()) return null;
        return mrl.sources().get(0).type();
    }

    // PLAIN NESTED LOOP (NO STREAM/Optional) — THIS RUNS PER FRAME IN THE PREVIEW DRAW PATH
    private static String bestQuality(final MRL mrl) {
        if (!loaded(mrl)) return "UNKNOWN";
        MediaQuality best = MediaQuality.UNKNOWN;
        for (final MRL.Source source: mrl.sources()) {
            for (final MediaQuality q: source.qualities().keySet()) {
                if (q.threshold > best.threshold) best = q;
            }
        }
        return best.name();
    }

    // SPLITS THE CONTENT BAND INTO THE LEFT (SEARCH + LIST) COLUMN AND THE RIGHT PREVIEW STACK USING THE
    // LEGACY GEOMETRY: 10px GAP UNDER THE HEADER RULE AND OVER THE FOOTER, LEFT WIDTH = CLAMPED WINDOW THIRD.
    private final class SelectorPane extends Group<SelectorPane> {

        private final Parent leftCol;
        private int leftW;
        private int listH;

        private SelectorPane(final Parent leftCol) {
            this.leftCol = leftCol;
            this.add(leftCol);
            this.add(preview);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.leftW = Math.min(380, Math.max(320, innerAvailWidth / 3));
            this.listH = Math.max(0, innerAvailHeight - 20);
            this.leftCol.measure(this.leftW, this.listH);
            if (preview.visible()) {
                preview.measure(Math.max(0, innerAvailWidth - this.leftW - 36), this.listH);
            }
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.leftCol.layout(this.innerLeft(), this.innerTop() + 10);
            if (preview.visible()) preview.layout(this.innerLeft() + this.leftW + 18, this.innerTop() + 10);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            // LEFT COLUMN PLATE + DIVIDER — ONLY WHEN THE GROUP HAS ITEMS (LEGACY CHROME ORDER)
            if (ctx.selectedGroup != null && ctx.selectedGroup.uris().length > 0) {
                final int y = this.top + 10;
                canvas.fill(this.left, y, this.leftW, this.listH, AppTheme.alpha(AppTheme.BG_1, 150));
                canvas.line(this.left + this.leftW, y, this.left + this.leftW, y + this.listH, AppTheme.STROKE_BRIGHT, 1f);
            }
            super.onDraw(canvas);
        }
    }

    // THE RIGHT PREVIEW STACK — TV FRAME WITH THE LIVE THUMBNAIL AND A LOCALIZED CRT OVERLAY ON TOP,
    // THEN THE METADATA PANEL (TITLE, TYPE TAG, URI, STATUS PIP) WITH REAL COPY/PLAY BUTTONS.
    private final class PreviewPane extends Group<PreviewPane> {

        private final CrtOverlay crt = new CrtOverlay();
        private final Button copyBtn;
        private final Button playBtn;
        private int tvY;
        private int tvH;
        private int panelY;
        private int panelH;

        private PreviewPane() {
            this.width = MAX_PARENT;
            this.height = MAX_PARENT;
            this.copyBtn = new Button("COPY LINK").icon("copy")
                    .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT)
                    .size(154, 38)
                    .onClick(b -> {
                        final AppContext.TestURI sel = selectedUri();
                        if (sel != null) {
                            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sel.uri()), null);
                            ctx.playSelectionSound();
                        }
                    });
            this.playBtn = new Button("PLAY").icon("play").subText("ENTER")
                    .accent(AppTheme.GREEN).textColor(AppTheme.GREEN)
                    .size(130, 38)
                    .onClick(b -> {
                        final AppContext.TestURI sel = selectedUri();
                        if (sel != null) {
                            final MRL mrl = mrlFor(sel);
                            if (regenerable(mrl)) reloadMRL(sel);
                            else handleSelect(sel);
                        }
                    });
            this.add(this.crt);
            this.add(this.copyBtn);
            this.add(this.playBtn);
        }

        @Override
        protected void onUpdate() {
            final AppContext.URIGroup group = ctx == null ? null : ctx.selectedGroup;
            final AppContext.TestURI sel = group != null && group.uris().length > 0 ? selectedUri() : null;
            this.visible = sel != null;
            if (sel == null) return;
            final MRL mrl = mrlFor(sel);
            final boolean regen = regenerable(mrl);
            final String playLabel = regen ? "RELOAD" : "PLAY";
            final String playIcon = regen ? "reload" : "play";
            final Color playColor = regen ? AppTheme.NEON_LIGHT : AppTheme.GREEN;
            this.copyBtn.enabled(mrl != null);
            this.playBtn.label(playLabel).icon(playIcon)
                    .accent(playColor).textColor(playColor)
                    .enabled(regen || loaded(mrl))
                    .width(Math.max(130, Button.width(text, playLabel, "ENTER", playIcon, 12)));
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // LEGACY STACK GEOMETRY: 16px GAP BETWEEN TV AND PANEL, 18px STACK MARGINS TOP+BOTTOM
            this.panelH = Math.min(112, Math.max(96, innerAvailHeight / 5));
            this.tvH = Math.max(220, innerAvailHeight - this.panelH - 16 - 36);
            this.crt.measure(Math.max(0, innerAvailWidth - 16), Math.max(0, this.tvH - 16));
            this.copyBtn.measure(innerAvailWidth, innerAvailHeight);
            this.playBtn.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int stackH = this.tvH + 16 + this.panelH;
            this.tvY = this.top + Math.max(0, (this.measuredHeight - stackH) / 2);
            this.panelY = this.tvY + this.tvH + 16;
            this.crt.layout(this.left + 8, this.tvY + 8);
            final int playX = this.left + this.measuredWidth - this.playBtn.measuredWidth() - 18;
            this.playBtn.layout(playX, this.panelY + 34);
            this.copyBtn.layout(playX - 166, this.panelY + 34);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final AppContext.TestURI sel = selectedUri();
            if (sel == null) return;
            final int x = this.left;
            final int w = this.measuredWidth;
            tvFrame(canvas, x, this.tvY, w, this.tvH, true);
            drawThumb(canvas, sel, x + 8, this.tvY + 8, w - 16, this.tvH - 16, false);

            // METADATA PANEL PLATE + AMBER CORNER TRIANGLES (PORT OF THE LEGACY PANEL CHROME)
            canvas.gradientV(x, this.panelY, w, this.panelH,
                    AppTheme.alpha(AppTheme.BG_2, 217), AppTheme.alpha(AppTheme.BG_1, 217));
            canvas.stroke(x, this.panelY, w, this.panelH, AppTheme.STROKE_BRIGHT, 1f);
            triangle(canvas, x - 1, this.panelY - 1, 10, true);
            triangle(canvas, x + w - 9, this.panelY + this.panelH - 9, 10, false);

            final MRL mrl = mrlOf(sel);
            final String title = text.truncateToWidth(sel.name().toUpperCase(Locale.ROOT), w - 410, AppTheme.TEXT_SECTION, Font.BOLD);
            canvas.text(title, x + 16, this.panelY + 14, AppTheme.NEON_LIGHT, AppTheme.TEXT_SECTION, true);
            final MediaType type = firstMediaType(mrl);
            if (type != null) {
                // MEDIA-TYPE TAG — OUTLINED GLOWING CHIP IN THE TYPE HUE (IMAGE/VIDEO/AUDIO/OTHER)
                final Color tc = type == MediaType.IMAGE ? AppTheme.GREEN
                        : type == MediaType.VIDEO ? AppTheme.AMBER
                        : type == MediaType.AUDIO ? AppTheme.CYAN
                        : AppTheme.TEXT_FAINT;
                final int tagX = x + 28 + text.widthBold(title, AppTheme.TEXT_SECTION);
                final int tagY = this.panelY + 12;
                final int tagW = text.width(type.name(), AppTheme.TEXT_BODY) + 22;
                canvas.fill(tagX, tagY, tagW, 22, AppTheme.alpha(AppTheme.BG_1, 188));
                canvas.stroke(tagX, tagY, tagW, 22, tc, 1f);
                canvas.glow(tagX, tagY, tagW, 22, 0f, tc, 0.16f);
                canvas.text(type.name(), tagX + 11,
                        tagY + Math.max(0, (22 - text.glyphHeight(AppTheme.TEXT_BODY)) / 2),
                        tc, AppTheme.TEXT_BODY, false);
            }
            canvas.text(text.truncateToWidth(sel.uri(), w - 270, AppTheme.TEXT_BODY),
                    x + 16, this.panelY + 42, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);

            final Color sc = statusColor(mrl);
            final int pipY = this.panelY + 72;
            pip(canvas, x + 18, pipY, 10, sc, true);
            canvas.text(bestQuality(mrl) + " - " + statusLabel(mrl), x + 36,
                    pipY + (10 - text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, sc, AppTheme.TEXT_BODY, false);
            super.onDraw(canvas); // CRT OVER THE THUMBNAIL, THEN THE COPY/PLAY BUTTONS
        }
    }

    // ONE SELECTABLE MEDIA ROW — PORTS THE LEGACY renderMediaRow ONTO ITS OWN BOX AND READS ITS
    // ListView-DRIVEN selected STATE (THUMBNAIL / LABEL / URI / STATUS PIP, EXACT COLORS AND OFFSETS).
    // THE MINI CRT EFFECT IS A CrtOverlay CHILD SITTING ON THE THUMB WELL.
    private final class MediaRow extends Group<MediaRow> {

        private final AppContext.TestURI uri;
        private final CrtOverlay crt = new CrtOverlay();

        private MediaRow(final AppContext.TestURI uri) {
            this.uri = uri;
            this.add(this.crt);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.crt.measure(58, 34); // THUMB INNER WELL
            this.contentWidth = 0;
            this.contentHeight = 0;
        }

        @Override
        protected void onLayout() {
            this.crt.layout(this.left + 12, this.top + 14);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            final MRL mrl = mrlOf(this.uri);
            final Color stateColor = statusColor(mrl);
            if (this.selected) {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.NEON, 26));
                canvas.stroke(x, y, w, h, AppTheme.NEON, 1f);
                canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.20f);
            }
            tvFrame(canvas, x + 6, y + 8, 70, 46, this.selected);
            drawThumb(canvas, this.uri, x + 12, y + 14, 58, 34, true);
            final int textX = x + 88;
            final int statusX = x + w - 19;
            final int maxTextW = Math.max(40, statusX - textX - 14);
            canvas.text(text.truncateToWidth(this.uri.name().toUpperCase(Locale.ROOT), maxTextW, AppTheme.TEXT_BUTTON, Font.BOLD),
                    textX, y + 12, this.selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT, AppTheme.TEXT_BUTTON, true);
            canvas.text(text.truncateToWidth(this.uri.uri(), maxTextW, AppTheme.TEXT_SUBTITLE),
                    textX, y + 34, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
            pip(canvas, statusX, y + 25, 8, stateColor, false);
            super.onDraw(canvas); // MINI CRT OVER THE THUMB WELL
        }
    }

    // THE FILTER FIELD — A DELIBERATE PORT OF THE LEGACY SEARCH BOX (BG_2 FILL, NEON/STROKE BORDER + GLOW ON
    // FOCUS, SEARCH GLYPH, 2px NEON_LIGHT CARET AT 480ms, HOVER-FOCUS AND ESC-CLEAR SEMANTICS). THE BUILT-IN
    // TextField DIFFERS ON ALL OF THOSE, SO IT IS NOT USED.
    private static final class SearchField extends Element<SearchField> {

        private String value = "";
        private Consumer<String> onChange;

        private SearchField onChange(final Consumer<String> handler) {
            this.onChange = handler;
            return this;
        }

        private SearchField value(final String v) {
            this.value = v == null ? "" : v;
            return this;
        }

        private String value() {
            return this.value;
        }

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
            final float scale = AppTheme.TEXT_BODY;
            canvas.fill(x, y, w, h, AppTheme.BG_2);
            canvas.stroke(x, y, w, h, this.focused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1f);
            if (this.focused) canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.24f);
            PixelIcon.draw("search", x + 8, y + (h - 14) / 2, 14, AppTheme.TEXT_FAINT);
            final boolean empty = this.value.isEmpty();
            final int textX = x + 30;
            final int textH = canvas.textHeight(scale, false);
            final int textY = y + Math.max(0, (h - textH) / 2);
            canvas.text(empty ? "filter..." : this.value, textX, textY, empty ? AppTheme.TEXT_FAINT : AppTheme.TEXT, scale, false);
            if (this.focused && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
                final int textW = empty ? 0 : canvas.textWidth(this.value, scale, false);
                final int caretX = Math.min(x + w - 8, textX + textW + (empty ? -5 : 1));
                canvas.fill(caretX, textY, 2, textH, AppTheme.NEON_LIGHT);
            }
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            // THE LEGACY MOUSE-MOVE PATH FOCUSED THE FILTER ON HOVER (STICKY — NEVER BLURS ON LEAVE)
            final boolean inside = this.contains(mx, my);
            this.hovered = inside;
            if (inside && !this.focused) {
                this.focused = true;
                this.invalidate();
            }
            return inside;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            if (this.contains(mx, my)) {
                this.focused = true;
                this.invalidate();
                return true;
            }
            this.focused = false;
            return false;
        }

        @Override
        public boolean dispatchChar(final int codepoint) {
            if (!this.focused || codepoint < 32 || codepoint == 127) return false;
            this.value += new String(Character.toChars(codepoint));
            this.invalidate();
            if (this.onChange != null) this.onChange.accept(this.value);
            return true;
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            if (!this.focused) return false; // ARROWS/SLASH/ENTER/ESC FALL THROUGH TO SCREEN NAV WHEN BLURRED
            switch (key) {
                case GLFW_KEY_BACKSPACE -> {
                    if (action != GLFW_RELEASE && !this.value.isEmpty()) {
                        this.value = this.value.substring(0, this.value.length() - 1);
                        this.invalidate();
                        if (this.onChange != null) this.onChange.accept(this.value);
                    }
                    return true;
                }
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                    if (action == GLFW_RELEASE) this.focused = false;
                    return true;
                }
                case GLFW_KEY_ESCAPE -> {
                    if (action == GLFW_RELEASE) {
                        this.focused = false;
                        if (!this.value.isEmpty()) {
                            this.value = "";
                            if (this.onChange != null) this.onChange.accept(this.value);
                        }
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
    }
}
