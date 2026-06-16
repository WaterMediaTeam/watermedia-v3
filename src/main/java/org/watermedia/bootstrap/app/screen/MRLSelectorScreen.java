package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.util.MediaType;
import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.ThreadTool;

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
 * Screen for selecting MRLs from a group, displayed as a grid with thumbnail backgrounds.
 */
public class MRLSelectorScreen extends Screen {

    private static final long LOAD_TIMEOUT_MS = 30000L;

    private final Consumer<HomeScreen.Action> navigator;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private long loadStartTime;
    private AppContext.TestURI pendingUri;
    private GLEngine.Builder glEngineBuilder;
    private int selectedIndex;
    private int scrollOffset;
    private String searchText = "";
    private boolean searchFocused;
    private Dimension searchBounds = Dimension.ZERO;
    private Dimension copyBounds = Dimension.ZERO;
    private Dimension playBounds = Dimension.ZERO;
    private final List<Dimension> rowBounds = new ArrayList<>();
    private final List<Integer> rowIndices = new ArrayList<>();

    // THUMBNAIL PLAYERS KEYED BY MRL NAME
    private final Map<String, MediaPlayer> thumbnailPlayers = new LinkedHashMap<>();
    private final Set<String> thumbnailAttempted = new HashSet<>();
    private final Set<String> thumbnailSubscriptions = new HashSet<>();
    private final Set<String> groupSubscriptions = new HashSet<>();

    public MRLSelectorScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;

        this.glEngineBuilder = new GLEngine.Builder(Thread.currentThread(), this.ctx);
    }

    @Override
    public void onEnter() {
        this.loading = false;
        this.loadGeneration++;
        this.pendingUri = null;
        this.selectedIndex = 0;
        this.scrollOffset = 0;
        this.searchFocused = false;
        this.groupSubscriptions.clear();
        this.thumbnailSubscriptions.clear();
        this.subscribeGroupMRLs();
    }

    @Override
    public void onExit() {
        this.loading = false;
        this.loadGeneration++;
        this.pendingUri = null;
        this.releaseThumbnailPlayers();
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
        final Iterator<Map.Entry<String, MediaPlayer>> it = this.thumbnailPlayers.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, MediaPlayer> entry = it.next();
            if (activeNames.contains(entry.getKey())) continue;
            entry.getValue().release();
            it.remove();
            this.thumbnailAttempted.remove(entry.getKey());
        }
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
                player = MediaAPI.createPlayer(thumbnailMrl, this.glEngineBuilder::build, () -> null);
                if (player != null) break;
            }

            if (pendingThumbnail) continue; // RETRY NEXT FRAME

            // FALLBACK: USE FIRST IMAGE SOURCE DIRECTLY
            if (player == null) {
                for (int i = 0; i < sources.size(); i++) {
                    if (sources.get(i).isImage()) {
                        player = MediaAPI.createPlayer(mrl, i, this.glEngineBuilder::build, () -> null);
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
        for (final MediaPlayer player: this.thumbnailPlayers.values()) {
            player.release();
        }
        this.thumbnailPlayers.clear();
        this.thumbnailAttempted.clear();
        this.thumbnailSubscriptions.clear();
        this.groupSubscriptions.clear();
    }

    private void handleSelect(final int index, final AppContext.TestURI uri) {
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
        if (thumbnail != null) thumbnail.release();
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
        return AppChrome.crtEnabled() || this.loading || this.searchFocused || this.hasActiveAnimatedThumbnail();
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
        if (this.ctx.selectedGroup == null) return;

        if (this.loading) {
            this.checkLoadingState();
        }

        this.subscribeGroupMRLs();

        final String groupName = this.ctx.selectedGroup != null ? this.ctx.selectedGroup.name() : "";
        AppChrome.screen(this.text, this.ctx, windowW, windowH, "Select media", groupName, "v" + WaterMedia.VERSION);

        this.renderSelectorLayout(windowW, windowH);

        if (this.loading) {
            this.renderLoadingDialog(windowW, windowH);
        }
    }

    private void renderSelectorLayout(final int windowW, final int windowH) {
        final AppContext.TestURI[] uris = this.ctx.selectedGroup.uris();
        if (uris.length == 0) return;
        final List<Integer> visible = this.visibleIndices(uris);
        if (visible.isEmpty()) {
            this.selectedIndex = 0;
        } else if (!visible.contains(this.selectedIndex)) {
            this.selectedIndex = visible.get(0);
        }

        final int top = AppChrome.contentTop();
        final int bottom = AppChrome.contentBottom(windowH);
        final int leftW = Math.min(380, Math.max(320, windowW / 3));
        final int rowH = 66;
        final int listX = 0;
        final int listY = top;
        final int listH = bottom - top;
        final int searchH = 44;
        final int rowsY = listY + searchH + 44;
        final int visibleRows = Math.max(1, (listY + listH - rowsY) / rowH);
        final int selectedPos = Math.max(0, visible.indexOf(this.selectedIndex));
        if (selectedPos < this.scrollOffset) this.scrollOffset = selectedPos;
        if (selectedPos >= this.scrollOffset + visibleRows) this.scrollOffset = selectedPos - visibleRows + 1;
        this.scrollOffset = Math.max(0, Math.min(Math.max(0, visible.size() - visibleRows), this.scrollOffset));
        this.updateThumbnailPlayers(this.activeThumbnailNames(uris, visible, visibleRows));

        RenderSystem.setupOrtho(windowW, windowH);
        RenderSystem.fill(listX, listY, leftW, listH, AppTheme.alpha(AppTheme.BG_1, 150));
        RenderSystem.lineV(leftW, listY, listH, AppTheme.STROKE_BRIGHT, 1f);
        this.searchBounds = new Dimension(12, listY + 8, leftW - 24, 30);
        RenderSystem.fill(this.searchBounds.x(), this.searchBounds.y(), this.searchBounds.width(), this.searchBounds.height(), AppTheme.BG_2);
        RenderSystem.rect(this.searchBounds.x(), this.searchBounds.y(), this.searchBounds.width(), this.searchBounds.height(),
                this.searchFocused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1f);
        if (this.searchFocused) RenderSystem.glowRect(this.searchBounds.x(), this.searchBounds.y(), this.searchBounds.width(), this.searchBounds.height(), 0f, AppTheme.NEON, 0.24f);
        final float searchScale = AppTheme.TEXT_BODY;
        final int searchTextX = this.searchBounds.x() + 30;
        final int searchTextY = this.searchBounds.y() + Math.max(0, (this.searchBounds.height() - this.text.glyphHeight(searchScale)) / 2);
        PixelIcon.draw("search", this.searchBounds.x() + 8, this.searchBounds.y() + (this.searchBounds.height() - 14) / 2, 14, AppTheme.TEXT_FAINT);
        this.text.render(this.searchText.isEmpty() ? "filter..." : this.searchText,
                searchTextX, searchTextY,
                this.searchText.isEmpty() ? AppTheme.TEXT_FAINT : AppTheme.TEXT, searchScale);
        if (this.searchFocused && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
            final int textW = this.searchText.isEmpty() ? 0 : this.text.width(this.searchText, searchScale);
            final int caretX = Math.min(this.searchBounds.right() - 8, searchTextX + textW + (this.searchText.isEmpty() ? -5 : 1));
            RenderSystem.fill(caretX, searchTextY, 2, this.text.glyphHeight(searchScale), AppTheme.NEON_LIGHT);
        }
        AppChrome.sectionHead(this.text, this.ctx.selectedGroup.name(), visible.size() + " items", 14, listY + searchH + 8);
        this.renderFailedTag(leftW, listY + searchH + 8);

        this.rowBounds.clear();
        this.rowIndices.clear();
        for (int i = 0; i < visibleRows && i + this.scrollOffset < visible.size(); i++) {
            final int index = visible.get(i + this.scrollOffset);
            final AppContext.TestURI uri = uris[index];
            final Dimension row = new Dimension(8, rowsY + i * rowH, leftW - 16, rowH - 6);
            this.rowBounds.add(row);
            this.rowIndices.add(index);
            this.renderMediaRow(uri, index, row, index == this.selectedIndex, windowH);
        }

        final int previewX = leftW + 18;
        final int previewW = windowW - previewX - 18;
        final int stackMargin = 18;
        final int stackGap = 16;
        final int panelH = Math.min(112, Math.max(96, (bottom - top) / 5));
        final int previewH = Math.max(220, bottom - top - panelH - stackGap - stackMargin * 2);
        final int stackH = previewH + stackGap + panelH;
        final int previewY = top + Math.max(0, (bottom - top - stackH) / 2);
        final AppContext.TestURI selected = uris[this.selectedIndex];
        AppChrome.tvFrame(previewX, previewY, previewW, previewH, true);
        this.renderThumbnailContent(selected, previewX + 8, previewY + 8, previewW - 16, previewH - 16, windowH, false);

        final int panelY = previewY + previewH + stackGap;
        AppChrome.panel(previewX, panelY, previewW, panelH, false);
        AppChrome.amberTriangle(previewX - 1, panelY - 1, 10, true);
        AppChrome.amberTriangle(previewX + previewW - 9, panelY + panelH - 9, 10, false);
        final MRL mrl = this.mrlFor(selected);
        final String title = this.text.truncateToWidth(selected.name().toUpperCase(), previewW - 410, AppTheme.TEXT_SECTION, java.awt.Font.BOLD);
        this.text.renderBold(title, previewX + 16, panelY + 14, AppTheme.NEON_LIGHT, AppTheme.TEXT_SECTION);
        final MediaType type = this.firstMediaType(mrl);
        if (type != null) {
            AppChrome.mediaTypeTag(this.text, previewX + 28 + this.text.widthBold(title, AppTheme.TEXT_SECTION), panelY + 12, type);
        }
        this.text.render(this.text.truncateToWidth(selected.uri(), previewW - 270, AppTheme.TEXT_BODY),
                previewX + 16, panelY + 42, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        final String status = statusLabel(mrl);
        final java.awt.Color statusColor = statusColor(mrl);
        final String quality = this.bestQuality(mrl);
        final int statusPipY = panelY + 72;
        AppChrome.statusPip(previewX + 18, statusPipY, 10, statusColor, true);
        this.text.render(quality + " - " + status, previewX + 36,
                statusPipY + (10 - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, statusColor, AppTheme.TEXT_BODY);
        final boolean regen = regenerable(mrl);
        final String playLabel = regen ? "RELOAD" : "PLAY";
        final String playIcon = regen ? "reload" : "play";
        final int playW = Math.max(130, this.panelButtonWidth(playLabel, "ENTER", AppTheme.TEXT_BUTTON));
        this.playBounds = new Dimension(previewX + previewW - playW - 18, panelY + 34, playW, 38);
        this.copyBounds = new Dimension(this.playBounds.x() - 166, panelY + 34, 154, 38);
        this.renderPanelButton("copy", "COPY LINK", null, this.copyBounds, AppTheme.NEON_LIGHT, mrl != null);
        this.renderPanelButton(playIcon, playLabel, "ENTER", this.playBounds,
                regen ? AppTheme.NEON_LIGHT : AppTheme.GREEN,
                regen || loaded(mrl));
        RenderSystem.restoreProjection();
    }

    private Set<String> activeThumbnailNames(final AppContext.TestURI[] uris,
                                             final List<Integer> visible,
                                             final int visibleRows) {
        final Set<String> active = new LinkedHashSet<>();
        if (this.selectedIndex >= 0 && this.selectedIndex < uris.length) {
            active.add(uris[this.selectedIndex].name());
        }
        for (int i = 0; i < visibleRows && i + this.scrollOffset < visible.size(); i++) {
            final int index = visible.get(i + this.scrollOffset);
            if (index >= 0 && index < uris.length) active.add(uris[index].name());
        }
        return active;
    }

    private void renderFailedTag(final int rightEdge, final int sectionY) {
        int failed = 0;
        for (final MRL mrl: this.ctx.groupMRLs.values()) {
            if (failed(mrl)) failed++;
        }
        final String label = failed + " FAILED";
        final int tagW = AppChrome.tagWidth(this.text, label);
        AppChrome.tag(this.text, rightEdge - tagW - 18, sectionY + 2, label, failed > 0 ? AppTheme.RED : AppTheme.TEXT_FAINT);
    }

    private List<Integer> visibleIndices(final AppContext.TestURI[] uris) {
        final List<Integer> visible = new ArrayList<>();
        final String q = this.searchText == null ? "" : this.searchText.trim().toLowerCase();
        for (int i = 0; i < uris.length; i++) {
            if (q.isEmpty() || uris[i].name().toLowerCase().contains(q) || uris[i].uri().toLowerCase().contains(q)) {
                visible.add(i);
            }
        }
        return visible;
    }

    private void renderMediaRow(final AppContext.TestURI uri, final int index, final Dimension row,
                                final boolean selected, final int windowH) {
        final MRL mrl = this.mrlFor(uri);
        final java.awt.Color stateColor = statusColor(mrl);
        if (selected) {
            RenderSystem.fill(row.x(), row.y(), row.width(), row.height(),
                    AppTheme.NEON.getRed() / 255f, AppTheme.NEON.getGreen() / 255f, AppTheme.NEON.getBlue() / 255f, 0.10f);
            RenderSystem.rect(row.x(), row.y(), row.width(), row.height(), AppTheme.NEON, 1f);
            RenderSystem.glowRect(row.x(), row.y(), row.width(), row.height(), 0f, AppTheme.NEON, 0.20f);
        }
        AppChrome.tvFrame(row.x() + 6, row.y() + 8, 70, 46, selected);
        this.renderThumbnailContent(uri, row.x() + 12, row.y() + 14, 58, 34, windowH, true);
        final int textX = row.x() + 88;
        final int statusX = row.right() - 19;
        final int maxTextW = Math.max(40, statusX - textX - 14);
        this.text.renderBold(this.text.truncateToWidth(uri.name().toUpperCase(), maxTextW, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD),
                textX, row.y() + 12, selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT, AppTheme.TEXT_BUTTON);
        this.text.render(this.text.truncateToWidth(uri.uri(), maxTextW, AppTheme.TEXT_SUBTITLE),
                textX, row.y() + 34, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        AppChrome.statusPip(statusX, row.y() + 25, 8, stateColor, false);
    }

    private void renderThumbnailContent(final AppContext.TestURI uri, final int x, final int y,
                                        final int w, final int h, final int windowH, final boolean mini) {
        final MediaPlayer player = this.thumbnailPlayers.get(uri.name());
        if (player != null && player.texture() > 0 && player.width() > 0 && player.height() > 0) {
            RenderSystem.clip(x, y, w, h, windowH);
            RenderSystem.bindTexture((int) player.texture());
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
            final java.awt.Color color = failed ? statusColor(mrl) : ready ? AppTheme.TEXT_SOFT : AppTheme.NEON;
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
                                   final java.awt.Color color, final boolean enabled) {
        final java.awt.Color actual = enabled ? color : AppTheme.TEXT_FAINT;
        final boolean hover = enabled && bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover ? AppTheme.alpha(AppTheme.NEON_DARK, 84) : AppTheme.alpha(AppTheme.BG_2, 220));
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), actual, 2f);
        if (enabled) RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, actual, 0.18f);
        final float scale = AppTheme.TEXT_BUTTON;
        final int iconSize = 15;
        PixelIcon.draw(icon, bounds.x() + 11, bounds.y() + (bounds.height() - iconSize) / 2, iconSize, actual);
        final int keyReserve = hotkey == null ? 0 : this.text.width(hotkey, AppTheme.TEXT_SUBTITLE) + 28;
        final int labelMaxW = Math.max(24, bounds.width() - 42 - keyReserve);
        this.text.renderBold(this.text.truncateToWidth(label, labelMaxW, scale, java.awt.Font.BOLD), bounds.x() + 34,
                bounds.y() + Math.max(0, (bounds.height() - this.text.glyphHeightBold(scale)) / 2f),
                actual, scale);
        if (hotkey != null) {
            final int keyW = this.text.width(hotkey, AppTheme.TEXT_SUBTITLE) + 12;
            final int keyH = 20;
            final int keyX = bounds.right() - keyW - 8;
            final int keyY = bounds.y() + (bounds.height() - keyH) / 2;
            RenderSystem.rect(keyX, keyY, keyW, keyH, AppTheme.STROKE, 1f);
            this.text.render(hotkey, keyX + 6,
                    keyY + Math.max(0, (keyH - this.text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2f),
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
        }
    }

    private int panelButtonWidth(final String label, final String hotkey, final float scale) {
        final int iconAndLeft = 42;
        final int rightPad = 8;
        final int keyW = hotkey == null ? 0 : this.text.width(hotkey, AppTheme.TEXT_SUBTITLE) + 20;
        final int keyGap = hotkey == null ? 0 : 10;
        return iconAndLeft + this.text.widthBold(label, scale) + keyGap + keyW + rightPad;
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

    private static java.awt.Color statusColor(final MRL mrl) {
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
            case GLFW_KEY_SLASH -> this.searchFocused = true;
            case GLFW_KEY_BACKSPACE -> {
                if (this.searchFocused && !this.searchText.isEmpty()) {
                    this.searchText = this.searchText.substring(0, this.searchText.length() - 1);
                    this.scrollOffset = 0;
                }
            }
            case GLFW_KEY_UP -> this.moveSelection(-1);
            case GLFW_KEY_DOWN -> this.moveSelection(1);
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (this.searchFocused) {
                    this.searchFocused = false;
                    return;
                }
                final AppContext.TestURI[] uris = this.ctx.selectedGroup.uris();
                if (uris.length > 0) {
                    final AppContext.TestURI uri = uris[this.selectedIndex];
                    final MRL mrl = this.mrlFor(uri);
                    if (regenerable(mrl)) this.reloadMRL(uri);
                    else this.handleSelect(this.selectedIndex, uri);
                }
            }
            case GLFW_KEY_ESCAPE -> {
                if (this.searchFocused) {
                    this.searchFocused = false;
                    if (!this.searchText.isEmpty()) {
                        this.searchText = "";
                        this.scrollOffset = 0;
                    }
                } else {
                    this.goBack();
                }
            }
        }
    }

    private void moveSelection(final int delta) {
        if (this.ctx.selectedGroup == null || this.ctx.selectedGroup.uris().length == 0) return;
        final List<Integer> visible = this.visibleIndices(this.ctx.selectedGroup.uris());
        if (visible.isEmpty()) return;
        final int pos = Math.max(0, visible.indexOf(this.selectedIndex));
        this.selectedIndex = visible.get(Math.max(0, Math.min(visible.size() - 1, pos + delta)));
        this.ctx.playSelectionSound();
    }

    @Override
    public void handleChar(final int codepoint) {
        if (!this.searchFocused || this.loading) return;
        if (codepoint < 32 || codepoint == 127) return;
        this.searchText += new String(Character.toChars(codepoint));
        this.scrollOffset = 0;
    }

    @Override
    public void handleMouseMove(final double mx, final double my) {
        if (this.loading) return;
        if (this.searchBounds.contains(mx, my)) {
            this.searchFocused = true;
            return;
        }
        for (int i = 0; i < this.rowBounds.size(); i++) {
            if (this.rowBounds.get(i).contains(mx, my)) {
                final int index = this.rowIndices.get(i);
                if (index != this.selectedIndex) {
                    this.selectedIndex = index;
                    this.ctx.playSelectionSound();
                }
                return;
            }
        }
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (this.loading) return;
        if (this.searchBounds.contains(mx, my)) {
            this.searchFocused = true;
            return;
        }
        this.searchFocused = false;
        final AppContext.TestURI[] uris = this.ctx.selectedGroup.uris();
        if (this.copyBounds.contains(mx, my) && this.selectedIndex >= 0 && this.selectedIndex < uris.length) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(uris[this.selectedIndex].uri()), null);
            this.ctx.playSelectionSound();
            return;
        }
        if (this.playBounds.contains(mx, my) && this.selectedIndex >= 0 && this.selectedIndex < uris.length) {
            final AppContext.TestURI uri = uris[this.selectedIndex];
            final MRL mrl = this.mrlFor(uri);
            if (regenerable(mrl)) this.reloadMRL(uri);
            else this.handleSelect(this.selectedIndex, uri);
            return;
        }
        for (int i = 0; i < this.rowBounds.size(); i++) {
            if (this.rowBounds.get(i).contains(mx, my)) {
                final int index = this.rowIndices.get(i);
                if (index >= 0 && index < uris.length) {
                    this.selectedIndex = index;
                    this.handleSelect(index, uris[index]);
                }
                return;
            }
        }
    }

    @Override
    public void handleScroll(final double yOffset) {
        if (this.loading) return;
        if (this.ctx.selectedGroup == null) return;
        final int visibleRows = Math.max(1, this.rowBounds.size());
        final int max = Math.max(0, this.visibleIndices(this.ctx.selectedGroup.uris()).size() - visibleRows);
        this.scrollOffset = Math.max(0, Math.min(max, this.scrollOffset + (int) (-yOffset)));
    }

    @Override
    public String instructions() {
        if (this.loading) return "ESC: Cancel";
        return "ARROWS: Navigate | ENTER: Select | ESC: Back";
    }
}
