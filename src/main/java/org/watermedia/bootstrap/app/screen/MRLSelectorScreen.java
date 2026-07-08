package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.util.MediaType;
import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.view.Badge;
import org.watermedia.bootstrap.app.view.Box;
import org.watermedia.bootstrap.app.view.Button;
import org.watermedia.bootstrap.app.view.Canvas;
import org.watermedia.bootstrap.app.view.LinearLayout;
import org.watermedia.bootstrap.app.view.ListView;
import org.watermedia.bootstrap.app.view.TextView;
import org.watermedia.bootstrap.app.view.View;
import org.watermedia.bootstrap.app.view.ViewGroup;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Media (MRL) selector built on the view tree: {@link AppChrome} paints the window frame, the left-column
 * background and the live preview panel in {@link #renderChrome}, while the filter field and the selectable
 * media rows live in the content tree — a {@link ListView} that owns scrolling, hit-testing and selection.
 */
public final class MRLSelectorScreen extends ViewScreen {

    private static final long LOAD_TIMEOUT_MS = 30000L;

    private final Consumer<HomeScreen.Action> navigator;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private long loadStartTime;
    private AppContext.TestURI pendingUri;

    private SearchField search;
    private ListView<AppContext.TestURI> list;
    // HEADER ROW VIEWS — TEXT REFRESHED EACH FRAME FROM THE LIVE GROUP NAME / ITEM COUNT / FAILED COUNT
    private TextView headerLabel;
    private TextView headerCount;
    private Badge headerFailed;
    // CURRENTLY VISIBLE (SEARCH-FILTERED) ITEMS — THE ListView'S BACKING DATA
    private List<AppContext.TestURI> filtered = new ArrayList<>();
    // LEFT-COLUMN RECT, COMPUTED IN renderChrome AND RETURNED FROM THE CONTENT-RECT OVERRIDES
    private int leftW;
    private int listH;
    // PREVIEW-PANEL BUTTON HIT RECTS — DRAWN IN renderChrome, CLICKED IN handleMouseClick (OUTSIDE THE TREE)
    private Dimension copyBounds = Dimension.ZERO;
    private Dimension playBounds = Dimension.ZERO;

    // THUMBNAIL PLAYERS KEYED BY MRL NAME
    private final Map<String, MediaPlayer> thumbnailPlayers = new LinkedHashMap<>();
    private final Set<String> thumbnailAttempted = new HashSet<>();
    private final Set<String> thumbnailSubscriptions = new HashSet<>();
    private final Set<String> groupSubscriptions = new HashSet<>();

    public MRLSelectorScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    protected View<?> build() {
        this.search = new SearchField()
                .onChange(v -> this.applyFilter())
                .width(View.MATCH_PARENT)
                .height(30);

        // HEADER ROW — A HORIZONTAL LAYOUT: NEON BAR + GROUP LABEL + ITEM COUNT + [FLEX SPACER] + FAILED BADGE.
        // COMPOSED FROM PARAMETRIC VIEWS INSTEAD OF HARDCODED DRAW CALLS, SO IT KEEPS ITS OWN BOX ABOVE THE LIST.
        this.headerLabel = new TextView().bold(true).scale(AppTheme.TEXT_SECTION).color(AppTheme.NEON).gravity(Gravity.CENTER);
        this.headerCount = new TextView().scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_FAINT).gravity(Gravity.CENTER);
        this.headerFailed = new Badge().gravity(Gravity.CENTER);
        final LinearLayout header = LinearLayout.row()
                .spacing(8)
                .width(View.MATCH_PARENT)
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
                    // HOVER SELECTS THE ROW (selectOnHover) AND, LIKE THE LEGACY handleMouseMove, CHIMES ON CHANGE
                    row.onHover(v -> { if (!v.selected()) this.ctx.playSelectionSound(); });
                    return row;
                })
                .onSelect((uri, index) -> this.openRow(uri))
                .selectOnHover(true)
                // ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                // WEIGHT 1 → THE LIST FILLS THE LEFTOVER VERTICAL SPACE AND SCISSORS ROWS WITHIN ITS OWN BOX,
                // WHICH SITS BELOW THE HEADER — SO A SCROLLED ROW CAN NEVER REACH THE HEADER OR THE CHROME
                .width(View.MATCH_PARENT)
                .weight(1f);

        // VERTICAL COLUMN: SEARCH, HEADER, LIST — EACH CONFINED TO ITS BOX BY THE LAYOUT (PADDING/SPACING)
        final LinearLayout column = LinearLayout.column()
                .spacing(8)
                .padding(new Spacing(8, 4, 8, 10))
                .width(View.MATCH_PARENT)
                .height(View.MATCH_PARENT)
                .add(this.search)
                .add(header)
                .add(this.list);

        return new Body().add(column).width(View.MATCH_PARENT).height(View.MATCH_PARENT);
    }

    @Override
    public void onEnter() {
        super.onEnter();
        this.loading = false;
        this.loadGeneration++;
        this.pendingUri = null;
        this.groupSubscriptions.clear();
        this.thumbnailSubscriptions.clear();
        this.search.value("").focused(false);
        this.applyFilter();
        this.list.selection(0);
        this.subscribeGroupMRLs();
    }

    @Override
    public void onExit() {
        this.loading = false;
        this.loadGeneration++;
        this.pendingUri = null;
        this.releaseThumbnailPlayers();
    }

    // RECOMPUTES THE SEARCH-FILTERED ITEM LIST, KEEPING THE SAME ITEM SELECTED WHEN IT SURVIVES THE FILTER
    private void applyFilter() {
        final AppContext.TestURI prev = this.selectedUri();
        final List<AppContext.TestURI> next = new ArrayList<>();
        if (this.ctx.selectedGroup != null) {
            final String q = this.search.value() == null ? "" : this.search.value().trim().toLowerCase();
            for (final AppContext.TestURI uri: this.ctx.selectedGroup.uris()) {
                if (q.isEmpty() || uri.name().toLowerCase().contains(q) || uri.uri().toLowerCase().contains(q)) {
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

    private void subscribeThumbnailMRL(final URI uri, final MRL mrl) {
        if (uri == null || mrl == null || loaded(mrl)) return;
        if (this.thumbnailSubscriptions.add(uri.toString())) {
            mrl.subscribe(done -> this.ctx.requestRender());
        }
    }

    private MRL mrlFor(final AppContext.TestURI uri) {
        if (uri == null) return null;
        MRL mrl = this.ctx.groupMRLs.get(uri.name());
        if (mrl != null) return mrl;
        mrl = MediaAPI.getMRL(uri.uri());
        this.ctx.groupMRLs.put(uri.name(), mrl);
        if (!loaded(mrl) && this.groupSubscriptions.add(uri.name())) {
            mrl.subscribe(done -> this.ctx.requestRender());
        }
        this.ctx.requestRender();
        return mrl;
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

    private void releaseInactiveThumbnailPlayers(final Set<String> activeNames) {
        List<MediaPlayer> evicted = null;
        final Iterator<Map.Entry<String, MediaPlayer>> it = this.thumbnailPlayers.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, MediaPlayer> entry = it.next();
            if (activeNames.contains(entry.getKey())) continue;
            // REMOVE FROM THE ACTIVE MAP FIRST, THEN HAND OFF FOR BACKGROUND RELEASE (SEE releaseAsync)
            if (evicted == null) evicted = new ArrayList<>();
            evicted.add(entry.getValue());
            it.remove();
            this.thumbnailAttempted.remove(entry.getKey());
        }
        if (evicted != null) this.releaseAsync(evicted);
    }

    // HANDS THUMBNAIL PLAYERS OFF FOR BACKGROUND RELEASE — MIRRORS AppContext.releasePlayer. THE PLAYERS
    // MUST ALREADY BE REMOVED FROM thumbnailPlayers SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING PLAYER.
    // stop() IS NON-BLOCKING; release() JOINS THE DECODE THREADS (~HUNDREDS OF MS) AND SO RUNS OFF THE RENDER
    // THREAD. THE ENGINE TEARDOWN IS THREAD-SAFE (GL DEFERS ITS TEXTURE DELETES TO THE RENDER EXECUTOR).
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
    private void updateThumbnailPlayers(final Set<String> activeNames) {
        if (this.ctx.selectedGroup == null) return;

        this.releaseInactiveThumbnailPlayers(activeNames);

        for (final AppContext.TestURI uri: this.ctx.selectedGroup.uris()) {
            final String name = uri.name();
            if (!activeNames.contains(name)) continue;
            if (this.thumbnailPlayers.containsKey(name)) continue;
            if (this.thumbnailAttempted.contains(name)) continue;

            final MRL mrl = this.mrlFor(uri);
            if (mrl == null) continue;
            if (!loaded(mrl)) continue; // ONLY BUILD THUMBNAILS FOR FULLY LOADED MRLS

            final var sources = mrl.sources();
            if (sources.isEmpty()) {
                this.thumbnailAttempted.add(name);
                continue;
            }

            MediaPlayer player = null;
            boolean pendingThumbnail = false;

            // TRY THUMBNAIL URI FIRST
            for (final MRL.Source src: sources) {
                final URI thumbnailUri = src.thumbnail();
                if (thumbnailUri == null) continue;
                final MRL thumbnailMrl = org.watermedia.api.media.MediaAPI.getMRL(thumbnailUri.toString());
                final MRL.Status thumbStatus = thumbnailMrl.status();
                if (thumbStatus == MRL.Status.FETCHING) {
                    pendingThumbnail = true;
                    this.subscribeThumbnailMRL(thumbnailUri, thumbnailMrl);
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
                    if (sources.get(i).isImage()) {
                        player = MediaAPI.createPlayer(mrl, i, RenderSystem.mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                        break;
                    }
                }
            }

            this.thumbnailAttempted.add(name);

            if (player != null) {
                player.repeat(true);
                player.start();
                this.thumbnailPlayers.put(name, player);
            }
        }
    }

    private void releaseThumbnailPlayers() {
        if (!this.thumbnailPlayers.isEmpty()) {
            // SNAPSHOT AND CLEAR FIRST SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING PLAYER
            final List<MediaPlayer> players = new ArrayList<>(this.thumbnailPlayers.values());
            this.thumbnailPlayers.clear();
            this.releaseAsync(players);
        }
        this.thumbnailAttempted.clear();
        this.thumbnailSubscriptions.clear();
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
            mrl = MediaAPI.getMRL(uri.uri());
            this.ctx.groupMRLs.put(name, mrl);
        } else {
            mrl.reload();
        }

        final MediaPlayer thumbnail = this.thumbnailPlayers.remove(name);
        if (thumbnail != null) this.releaseAsync(List.of(thumbnail));
        this.thumbnailAttempted.remove(name);
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

    private void checkLoadingState() {
        if (this.ctx.selectedMRL == null) {
            this.loading = false;
            this.loadGeneration++;
            this.pendingUri = null;
            return;
        }

        final MRL.Status status = this.ctx.selectedMRL.status();
        if (status == MRL.Status.LOADED) {
            this.loading = false;
            this.loadGeneration++;
            this.pendingUri = null;
            this.proceedWithMRL();
            return;
        }

        // ANY TERMINAL NON-LOADED STATE (ERROR/BLOCKED/EXPIRED/FORGOTTEN) ENDS THE WAIT.
        if (status != MRL.Status.FETCHING) {
            this.loading = false;
            this.loadGeneration++;
            this.pendingUri = null;
            this.ctx.showError("Error", "Unable to open media: " + status.name(), null);
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.loading = false;
            this.loadGeneration++;
            this.pendingUri = null;
            this.ctx.showError("Load Error", "MRL loading timed out", null);
        }
    }

    private void renderLoadingDialog(final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);

        final int dots = (int) ((System.currentTimeMillis() / 500) % 4);
        final String loadingText = "Loading" + ".".repeat(dots);
        final String mrlName = this.pendingUri != null ? this.pendingUri.name() : "";

        final int padding = 20;
        final int lineH = this.text.lineHeight(AppTheme.TEXT_BODY);

        final int contentW = Math.max(this.text.widthBold(loadingText, AppTheme.TEXT_BUTTON),
                Math.max(this.text.width(mrlName, AppTheme.TEXT_BODY), this.text.width("ESC to cancel", AppTheme.TEXT_BODY)));
        final int dialogW = Math.min(Math.max(contentW + padding * 2 + 40, 400), windowW - 100);
        final int dialogH = padding + lineH + 15 + lineH + 10 + lineH + padding;

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = (windowH - dialogH) / 2;

        RenderSystem.dialogBox(dialogX, dialogY, dialogW, dialogH, Colors.BLUE, 3);

        int y = dialogY + padding;
        this.text.renderBold(loadingText, dialogX + padding, y, Colors.BLUE, AppTheme.TEXT_BUTTON);
        y += lineH + 15;

        this.text.render(mrlName, dialogX + padding, y, Colors.GRAY, AppTheme.TEXT_BODY);
        y += lineH + 10;

        this.text.render("ESC to cancel", dialogX + padding, y, Colors.GRAY, AppTheme.TEXT_BODY);

        RenderSystem.restoreProjection();
    }

    private void goBack() {
        this.ctx.clearGroupState();
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    @Override
    public boolean wantsContinuousRender() {
        // super = A FOCUSED FILTER FIELD (CARET BLINK); PLUS THE SCREEN'S OWN ANIMATION TRIGGERS
        return super.wantsContinuousRender() || AppChrome.crtEnabled() || this.loading || this.hasActiveAnimatedThumbnail();
    }

    private boolean hasActiveAnimatedThumbnail() {
        for (final MediaPlayer player: this.thumbnailPlayers.values()) {
            if (player == null || player.error() || player.stopped() || player.ended()) continue;
            if (player instanceof TxMediaPlayer) {
                if (player.duration() > 0L && !player.paused()) return true;
            } else if (!player.paused()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(final int windowW, final int windowH) {
        // REFRESH THE HEADER-ROW VIEWS FROM LIVE DATA BEFORE THE TREE MEASURES/DRAWS
        if (this.ctx.selectedGroup != null) {
            this.headerLabel.text(this.ctx.selectedGroup.name().toUpperCase());
            this.headerCount.text(this.filtered.size() + " ITEMS");
            int failed = 0;
            for (final MRL mrl: this.ctx.groupMRLs.values()) {
                if (failed(mrl)) failed++;
            }
            this.headerFailed.label(failed + " FAILED").color(failed > 0 ? AppTheme.RED : AppTheme.TEXT_FAINT);
        }
        super.render(windowW, windowH); // CHROME + PREVIEW (renderChrome) THEN THE VIEW TREE
        // THE LOAD MODAL OVERLAYS EVERYTHING AND IS CENTERED IN THE FULL WINDOW, SO IT PAINTS LAST
        if (this.loading) this.renderLoadingDialog(windowW, windowH);
    }

    @Override
    protected void renderChrome(final int windowW, final int windowH) {
        this.leftW = Math.min(380, Math.max(320, windowW / 3));
        final int top = AppChrome.contentTop();
        final int bottom = AppChrome.contentBottom(windowH);
        this.listH = bottom - top;
        if (this.ctx.selectedGroup == null) return;

        if (this.loading) this.checkLoadingState();
        this.subscribeGroupMRLs();
        this.updateThumbnailPlayers(this.activeThumbnailNames());

        final String groupName = this.ctx.selectedGroup.name();
        AppChrome.screen(this.text, this.ctx, windowW, windowH, "Select media", groupName, "v" + WaterMedia.VERSION);

        final AppContext.TestURI[] uris = this.ctx.selectedGroup.uris();
        if (uris.length == 0) return; // NO ITEMS — CHROME ONLY (LEGACY)

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(0, top, this.leftW, this.listH, AppTheme.alpha(AppTheme.BG_1, 150));
        RenderSystem.lineV(this.leftW, top, this.listH, AppTheme.STROKE_BRIGHT, 1f);
        // THE SEARCH FIELD, THE HEADER ROW (LABEL / ITEM COUNT / FAILED BADGE) AND THE ROWS ARE ALL DRAWN
        // BY THE VIEW TREE NOW, EACH INSIDE ITS OWN LAYOUT BOX

        // ===== RIGHT PREVIEW STACK =====
        final int previewX = this.leftW + 18;
        final int previewW = windowW - previewX - 18;
        final int stackMargin = 18;
        final int stackGap = 16;
        final int panelH = Math.min(112, Math.max(96, this.listH / 5));
        final int previewH = Math.max(220, this.listH - panelH - stackGap - stackMargin * 2);
        final int stackH = previewH + stackGap + panelH;
        final int previewY = top + Math.max(0, (this.listH - stackH) / 2);
        final AppContext.TestURI selected = this.selectedUri();
        if (selected == null) {
            RenderSystem.restoreProjection();
            return;
        }
        AppChrome.tvFrame(previewX, previewY, previewW, previewH, true);
        this.renderThumbnailContent(selected, previewX + 8, previewY + 8, previewW - 16, previewH - 16, windowH, false);

        final int panelY = previewY + previewH + stackGap;
        AppChrome.panel(previewX, panelY, previewW, panelH, false);
        AppChrome.amberTriangle(previewX - 1, panelY - 1, 10, true);
        AppChrome.amberTriangle(previewX + previewW - 9, panelY + panelH - 9, 10, false);
        final MRL mrl = this.mrlFor(selected);
        final String title = this.text.truncateToWidth(selected.name().toUpperCase(), previewW - 410, AppTheme.TEXT_SECTION, Font.BOLD);
        this.text.renderBold(title, previewX + 16, panelY + 14, AppTheme.NEON_LIGHT, AppTheme.TEXT_SECTION);
        final MediaType type = this.firstMediaType(mrl);
        if (type != null) {
            AppChrome.mediaTypeTag(this.text, previewX + 28 + this.text.widthBold(title, AppTheme.TEXT_SECTION), panelY + 12, type);
        }
        this.text.render(this.text.truncateToWidth(selected.uri(), previewW - 270, AppTheme.TEXT_BODY),
                previewX + 16, panelY + 42, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        final String status = statusLabel(mrl);
        final Color statusColor = statusColor(mrl);
        final String quality = this.bestQuality(mrl);
        final int statusPipY = panelY + 72;
        AppChrome.statusPip(previewX + 18, statusPipY, 10, statusColor, true);
        this.text.render(quality + " - " + status, previewX + 36,
                statusPipY + (10 - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, statusColor, AppTheme.TEXT_BODY);
        final boolean regen = regenerable(mrl);
        final String playLabel = regen ? "RELOAD" : "PLAY";
        final String playIcon = regen ? "reload" : "play";
        final int playW = Math.max(130, this.panelButtonWidth(playLabel, "ENTER", playIcon));
        this.playBounds = new Dimension(previewX + previewW - playW - 18, panelY + 34, playW, 38);
        this.copyBounds = new Dimension(this.playBounds.x() - 166, panelY + 34, 154, 38);
        this.renderPanelButton("copy", "COPY LINK", null, this.copyBounds, AppTheme.NEON_LIGHT, mrl != null);
        this.renderPanelButton(playIcon, playLabel, "ENTER", this.playBounds,
                regen ? AppTheme.NEON_LIGHT : AppTheme.GREEN,
                regen || loaded(mrl));
        RenderSystem.restoreProjection();
    }

    // THE SELECTED ITEM PLUS EVERY ROW CURRENTLY ON SCREEN (FROM THE PREVIOUS FRAME'S LAYOUT). THIS SCOPES
    // THUMBNAIL PLAYERS TO WHAT THE SELECTOR IS SHOWING, EXACTLY LIKE THE LEGACY VISIBLE-WINDOW COMPUTATION.
    private Set<String> activeThumbnailNames() {
        final Set<String> active = new LinkedHashSet<>();
        final AppContext.TestURI selected = this.selectedUri();
        if (selected != null) active.add(selected.name());
        final int viewTop = this.list.top();
        final int viewBottom = viewTop + this.list.measuredHeight();
        for (final View<?> child: this.list.children()) {
            if (!(child instanceof MediaRow row) || row.measuredHeight() <= 0) continue;
            final int rowTop = row.top();
            if (rowTop + row.measuredHeight() > viewTop && rowTop < viewBottom) active.add(row.uri.name());
        }
        return active;
    }

    @Override
    protected int contentX(final int windowW, final int windowH) {
        return 0;
    }

    @Override
    protected int contentY(final int windowW, final int windowH) {
        return AppChrome.contentTop();
    }

    @Override
    protected int contentW(final int windowW, final int windowH) {
        return this.leftW;
    }

    @Override
    protected int contentH(final int windowW, final int windowH) {
        return this.listH;
    }


    private void renderThumbnailContent(final AppContext.TestURI uri, final int x, final int y,
                                        final int w, final int h, final int windowH, final boolean mini) {
        final MediaPlayer player = this.thumbnailPlayers.get(uri.name());
        if (player != null && player.texture() != 0 && player.width() > 0 && player.height() > 0) {
            RenderSystem.clip(x, y, w, h, windowH);
            RenderSystem.bindMediaTexture(player.texture());
            RenderSystem.color(1f, 1f, 1f, 1f);
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
            RenderSystem.blit(bx, by, bw, bh);
            RenderSystem.clearClip();
            AppChrome.crtOverlay(x, y, w, h, windowH);
        } else {
            final MRL mrl = this.mrlFor(uri);
            final MRL.Status status = mrl == null ? null : mrl.status();
            final boolean ready = status == MRL.Status.LOADED;
            // ERROR/BLOCKED/EXPIRED/FORGOTTEN — A FINAL STATE THAT NEEDS REGENERATION.
            final boolean failed = status != null && status != MRL.Status.LOADED && status != MRL.Status.FETCHING;
            RenderSystem.fill(x, y, w, h, AppTheme.BG_0);
            if (mini && !failed) {
                if (ready) {
                    final String ok = "[OK]";
                    final float okScale = AppTheme.TEXT_TINY;
                    this.text.render(ok, x + (w - this.text.width(ok, okScale)) / 2,
                            y + (h - this.text.glyphHeight(okScale)) / 2f,
                            AppTheme.GREEN, okScale);
                }
                AppChrome.crtOverlay(x, y, w, h, windowH);
                return;
            }
            final String label = failed ? (mini ? "[" + statusLabel(mrl) + "]" : statusLabel(mrl))
                    : ready ? "[ media thumbnail ]" : "LOADING...";
            final Color color = failed ? statusColor(mrl) : ready ? AppTheme.TEXT_SOFT : AppTheme.NEON;
            if (!mini && failed) {
                PixelIcon.draw("warn", x + w / 2 - (mini ? 5 : 14), y + h / 2 - (mini ? 12 : 36), mini ? 10 : 28, statusColor(mrl));
            }
            final float scale = mini ? AppTheme.TEXT_TINY : AppTheme.TEXT_BUTTON;
            final float textY = mini
                    ? y + (h - this.text.glyphHeight(scale)) / 2f
                    : y + h / 2f - this.text.lineHeight(scale) / 2f + 22;
            this.text.render(label, x + (w - this.text.width(label, scale)) / 2,
                    textY, color, scale);
            AppChrome.crtOverlay(x, y, w, h, windowH);
        }
    }

    private void renderPanelButton(final String icon, final String label, final String hotkey, final Dimension bounds,
                                   final Color color, final boolean enabled) {
        final boolean hover = enabled && bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        // SHARED BUTTON LOOK: color IS BOTH THE ACCENT (BORDER) AND THE LABEL COLOR; ICON + HOTKEY CHIP CONSISTENT
        Button.render(this.text, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                label, hotkey == null ? "" : hotkey, icon, 12, color, color, false, hover, enabled);
    }

    private int panelButtonWidth(final String label, final String hotkey, final String icon) {
        return Button.width(this.text, label, hotkey == null ? "" : hotkey, icon, 12);
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

    private MediaType firstMediaType(final MRL mrl) {
        if (!loaded(mrl) || mrl.sources().isEmpty()) return null;
        return mrl.sources().get(0).type();
    }

    private String bestQuality(final MRL mrl) {
        if (!loaded(mrl) || mrl.sources().isEmpty()) return "UNKNOWN";
        return mrl.sources().stream()
                .flatMap(source -> source.availableQualities().stream())
                .max(java.util.Comparator.comparingInt(q -> q.threshold))
                .orElse(org.watermedia.api.util.MediaQuality.UNKNOWN)
                .name();
    }

    @Override
    protected void onKeyRelease(final int key) {
        if (this.loading) {
            if (key == GLFW_KEY_ESCAPE) {
                this.loading = false;
                this.loadGeneration++;
                this.pendingUri = null;
            }
            return;
        }

        switch (key) {
            // BACKSPACE/ENTER/ESC ARE CONSUMED BY A FOCUSED SearchField; THEY REACH HERE ONLY WHEN IT IS BLURRED
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
            case GLFW_KEY_ESCAPE -> this.goBack();
        }
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (this.loading) return; // MODAL — ESC CANCELS VIA onKeyRelease
        super.handleMouseClick(mx, my); // TREE: FILTER FOCUS/BLUR + ROW ACTIVATION
        // COPY/PLAY LIVE IN THE renderChrome PREVIEW PANEL, OUTSIDE THE TREE — HIT-TEST THEM HERE
        final AppContext.TestURI selected = this.selectedUri();
        if (selected == null) return;
        if (this.copyBounds.contains(mx, my)) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected.uri()), null);
            this.ctx.playSelectionSound();
        } else if (this.playBounds.contains(mx, my)) {
            final MRL mrl = this.mrlFor(selected);
            if (regenerable(mrl)) this.reloadMRL(selected);
            else this.handleSelect(selected);
        }
    }

    @Override
    public String instructions() {
        if (this.loading) return "ESC: Cancel";
        return "ARROWS: Navigate | ENTER: Select | ESC: Back";
    }

    // FILLS ITS SINGLE CHILD (THE LEFT-COLUMN LAYOUT) TO THE CONTENT RECT AND SWALLOWS ALL POINTER/SCROLL/
    // TEXT INPUT WHILE A LOAD IS IN FLIGHT (THE SELECTOR IS MODAL THEN).
    private final class Body extends ViewGroup<Body> {

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            for (final View<?> child: this.children) child.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            for (final View<?> child: this.children) child.layout(this.innerLeft(), this.innerTop());
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            return loading || super.dispatchClick(mx, my);
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            return !loading && super.dispatchHover(mx, my);
        }

        @Override
        public View<?> dispatchPress(final double mx, final double my) {
            return loading ? null : super.dispatchPress(mx, my);
        }

        @Override
        public boolean dispatchScroll(final double mx, final double my, final double amount) {
            return !loading && super.dispatchScroll(mx, my, amount);
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            return !loading && super.dispatchKey(key, action);
        }

        @Override
        public boolean dispatchChar(final int codepoint) {
            return !loading && super.dispatchChar(codepoint);
        }
    }

    // ONE SELECTABLE MEDIA ROW — PORTS THE LEGACY renderMediaRow ONTO ITS OWN BOX AND READS ITS
    // ListView-DRIVEN selected STATE (THUMBNAIL / LABEL / URI / STATUS PIP, EXACT COLORS AND OFFSETS).
    private final class MediaRow extends View<MediaRow> {

        private final AppContext.TestURI uri;

        private MediaRow(final AppContext.TestURI uri) {
            this.uri = uri;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            final int right = x + w;
            final int windowH = canvas.windowHeight();
            final MRL mrl = mrlFor(this.uri);
            final Color stateColor = statusColor(mrl);
            if (this.selected) {
                RenderSystem.fill(x, y, w, h,
                        AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0.10f);
                RenderSystem.rect(x, y, w, h, AppTheme.NEON, 1f);
                RenderSystem.glowRect(x, y, w, h, 0f, AppTheme.NEON, 0.20f);
            }
            AppChrome.tvFrame(x + 6, y + 8, 70, 46, this.selected);
            renderThumbnailContent(this.uri, x + 12, y + 14, 58, 34, windowH, true);
            final int textX = x + 88;
            final int statusX = right - 19;
            final int maxTextW = Math.max(40, statusX - textX - 14);
            text.renderBold(text.truncateToWidth(this.uri.name().toUpperCase(), maxTextW, AppTheme.TEXT_BUTTON, Font.BOLD),
                    textX, y + 12, this.selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT, AppTheme.TEXT_BUTTON);
            text.render(text.truncateToWidth(this.uri.uri(), maxTextW, AppTheme.TEXT_SUBTITLE),
                    textX, y + 34, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
            AppChrome.statusPip(statusX, y + 25, 8, stateColor, false);
        }
    }

    // THE FILTER FIELD — A DELIBERATE PORT OF THE LEGACY SEARCH BOX (BG_2 FILL, NEON/STROKE BORDER + GLOW ON
    // FOCUS, SEARCH GLYPH, 2px NEON_LIGHT CARET AT 480ms). THE BUILT-IN TextField DIFFERS, SO IT IS NOT USED.
    private static final class SearchField extends View<SearchField> {

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
            // LEGACY handleMouseMove FOCUSED THE FILTER ON HOVER (STICKY — NEVER BLURS ON LEAVE)
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
