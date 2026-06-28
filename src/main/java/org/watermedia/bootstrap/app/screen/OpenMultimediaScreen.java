package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.platform.PlatformSearch;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.Metadata;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.ThreadTool;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Screen with dialog for opening custom multimedia URLs.
 * Renders HomeScreen as background with dialog overlay.
 * Instructions are shown in the bottom bar, not in the dialog.
 */
public class OpenMultimediaScreen extends Screen {

    private static final long LOAD_TIMEOUT_MS = 30000L;
    private static final int SEARCH_PREVIEW_H = 130;      // SHRUNK PREVIEW HEIGHT WHILE SEARCHING
    private static final long PREVIEW_ANIM_MS = 220L;     // PREVIEW GROW/SHRINK EASE DURATION
    private static final long SEARCH_DEBOUNCE_MS = 320L;  // QUIET PERIOD AFTER TYPING BEFORE AUTO-SEARCHING

    private final Consumer<HomeScreen.Action> navigator;
    private final HomeScreen homeScreen;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private volatile int previewGeneration;
    private long loadStartTime;
    private Dimension pasteBounds = Dimension.ZERO;
    private Dimension reloadBounds = Dimension.ZERO;
    private Dimension cancelBounds = Dimension.ZERO;
    private Dimension playBounds = Dimension.ZERO;
    private Dimension saveBounds = Dimension.ZERO;
    private Dimension inputBounds = Dimension.ZERO;
    private Dimension closeBounds = Dimension.ZERO;
    private boolean inputFocused = true;
    private volatile MRL previewMRL;
    private String previewUrl = "";

    // SEARCH MODE: WHEN THE INPUT IS NOT A VALID URI THE PLAY BUTTON BECOMES SEARCH AND A RESULTS
    // DROPDOWN OPENS BELOW THE INPUT. THE PlatformSearch HANDLE FILLS IN OFF-THREAD; WE POLL IT WHILE
    // RENDERING. searchRenderedDone GUARANTEES ONE FINAL FRAME ONCE THE SEARCH COMPLETES.
    private PlatformSearch search;
    private boolean searchRenderedDone = true;
    private long lastEditMs;                // TIMESTAMP OF THE LAST INPUT EDIT (DRIVES THE AUTO-SEARCH DEBOUNCE)
    private boolean searchMode;             // CACHED searchMode() RESULT (AVOIDS A PER-FRAME FILE STAT + URI PARSE)
    private String searchModeText;          // TRIMMED INPUT THE CACHED searchMode WAS COMPUTED FOR (null = NOT YET COMPUTED)
    private float animPreviewH;             // CURRENT ANIMATED PREVIEW HEIGHT
    private float animPreviewFrom;          // PREVIEW HEIGHT WHEN THE CURRENT ANIMATION BEGAN
    private int animPreviewTarget = -1;     // HEIGHT THE ANIMATION IS EASING TOWARD (-1 = UNINITIALIZED)
    private long animPreviewStart;          // START TIMESTAMP OF THE CURRENT PREVIEW ANIMATION
    private int dropdownScroll;
    private Dimension dropdownBounds = Dimension.ZERO;
    private final List<Dimension> resultRowBounds = new ArrayList<>();
    private final List<PlatformResult> resultRowItems = new ArrayList<>();
    private final List<Dimension> historyRowBounds = new ArrayList<>();
    private final List<String> historyRowItems = new ArrayList<>();

    // RESULT THUMBNAILS RENDER THROUGH SHORT-LIVED IMAGE PLAYERS, KEYED BY THUMBNAIL URI (MIRRORS
    // MRLSelectorScreen). BUILT LAZILY ON THE GL THREAD DURING render(), RELEASED ON A NEW SEARCH/EXIT.
    private GLEngine.Builder thumbEngine;
    private final Map<URI, MediaPlayer> thumbPlayers = new LinkedHashMap<>();
    private final Set<URI> thumbAttempted = new HashSet<>();
    private final Set<URI> thumbSubscribed = new HashSet<>();

    public OpenMultimediaScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator,
                                final HomeScreen homeScreen) {
        super(text, ctx);
        this.navigator = navigator;
        this.homeScreen = homeScreen;
    }

    @Override
    public void onEnter() {
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.inputFocused = true;
        this.previewMRL = null;
        this.previewUrl = "";
        this.animPreviewTarget = -1; // SNAP THE PREVIEW TO ITS CORRECT SIZE ON ENTRY (NO STRAY ANIMATION)
        this.clearSearch();
    }

    @Override
    public void onExit() {
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.previewMRL = null;
        this.previewUrl = "";
        this.clearSearch();
    }

    private void pasteFromClipboard() {
        try {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                this.ctx.customUrlText = ((String) clipboard.getData(DataFlavor.stringFlavor)).trim().replace("\"", "");
                this.lastEditMs = System.currentTimeMillis();
                this.ensurePreviewMRL();
            }
        } catch (final Exception e) {
            this.ctx.customUrlText = "";
            this.clearPreview();
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
            this.previewMRL = MediaAPI.getMRL(url);
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

    // ===== SEARCH MODE =====

    // TRUE WHEN THE INPUT IS NOT A LOADABLE URI/FILE, SO THE DIALOG SEARCHES INSTEAD OF PLAYING. SHARES
    // loadable() WITH ensurePreviewMRL SO THE "URL/PATH VS QUERY" RULE CAN NEVER DRIFT. THIS RUNS EVERY FRAME
    // (render() AND instructions()) ON A CONTINUOUSLY-REDRAWN SCREEN, SO THE RESULT IS MEMOIZED AGAINST THE
    // INPUT TEXT: THE BLOCKING file.exists() STAT AND URI PARSE ONLY RE-RUN WHEN THE TEXT ACTUALLY CHANGES.
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
    // THE OLD THUMBNAILS AND LET render() POLL THE FRESH HANDLE AS RESULTS LAND OFF-THREAD.
    private void doSearch() {
        final String query = this.ctx.customUrlText == null ? "" : this.ctx.customUrlText.trim();
        if (query.isEmpty()) return;
        this.releaseThumbs();
        this.dropdownScroll = 0;
        this.searchRenderedDone = false;
        this.search = PlatformAPI.search(query);
        this.ctx.requestRender();
    }

    // REPLACES THE INPUT WITH THE RESULT'S RAW URL AND LEAVES SEARCH MODE SO IT CAN BE PLAYED.
    private void selectResult(final PlatformResult result) {
        if (result == null || result.url() == null) return;
        this.ctx.customUrlText = result.url().toString();
        this.clearSearch();
        this.inputFocused = true;
        this.ensurePreviewMRL();
        this.ctx.playSelectionSound();
        this.ctx.requestRender();
    }

    private void clearSearch() {
        this.search = null;
        this.searchRenderedDone = true;
        this.dropdownScroll = 0;
        this.dropdownBounds = Dimension.ZERO;
        this.resultRowBounds.clear();
        this.resultRowItems.clear();
        this.historyRowBounds.clear();
        this.historyRowItems.clear();
        this.releaseThumbs();
    }

    private void releaseThumbs() {
        for (final MediaPlayer player: this.thumbPlayers.values()) player.release();
        this.thumbPlayers.clear();
        this.thumbAttempted.clear();
        this.thumbSubscribed.clear();
    }

    // LAZILY BUILDS AN IMAGE PLAYER FOR A RESULT THUMBNAIL (KEYED BY URI). RETURNS null WHILE THE THUMBNAIL
    // MRL IS STILL FETCHING (SUBSCRIBED TO RE-RENDER ON READY) OR WHEN IT EXPOSES NO USABLE IMAGE SOURCE.
    private MediaPlayer thumbPlayer(final URI thumbnail) {
        if (thumbnail == null) return null;
        final MediaPlayer existing = this.thumbPlayers.get(thumbnail);
        if (existing != null) return existing;
        if (this.thumbAttempted.contains(thumbnail)) return null;

        if (this.thumbEngine == null) this.thumbEngine = new GLEngine.Builder(Thread.currentThread(), this.ctx);

        final MRL mrl = MediaAPI.getMRL(thumbnail.toString());
        final MRL.Status status = mrl.status();
        if (status == MRL.Status.FETCHING) {
            if (this.thumbSubscribed.add(thumbnail)) mrl.subscribe(done -> this.ctx.requestRender());
            return null; // RETRY NEXT FRAME
        }
        this.thumbAttempted.add(thumbnail);
        if (status != MRL.Status.LOADED) return null;

        final var sources = mrl.sources();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).isImage()) {
                final MediaPlayer player = MediaAPI.createPlayer(mrl, i, this.thumbEngine::build, () -> null);
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

    // RENDERS THE RESULTS DROPDOWN BELOW THE INPUT: A RESULTS SECTION, THEN A SEPARATOR AND THE RECENT-QUERY
    // HISTORY. CONTENT SCROLLS WITHIN [top, maxBottom] (CLIPPED) AND THE PER-ROW HIT BOXES ARE RECORDED FOR
    // handleMouseClick. Thumbnails are aspect-fit so they never spill their cell — no nested clipping needed.
    private void renderDropdown(final int x, final int top, final int width, final int maxBottom, final int windowH) {
        this.resultRowBounds.clear();
        this.resultRowItems.clear();
        this.historyRowBounds.clear();
        this.historyRowItems.clear();
        this.dropdownBounds = Dimension.ZERO;

        final List<PlatformResult> results = this.search != null ? this.search.results() : List.of();
        final List<String> history = this.search != null ? this.search.history() : PlatformAPI.searchHistory();
        final boolean searching = this.search != null && !this.search.done();
        if (results.isEmpty() && history.isEmpty() && !searching) return;

        final boolean hasSearch = this.search != null;
        final int rowH = 38;
        final int histH = 26;
        final int headH = 20;
        final int pad = 6;

        int contentH = pad;
        if (hasSearch) contentH += headH + (results.isEmpty() && searching ? rowH : results.size() * rowH);
        if (!history.isEmpty()) contentH += headH + history.size() * histH;
        contentH += pad;

        final int availH = Math.max(rowH, maxBottom - top);
        final int boxH = Math.min(contentH, availH);
        this.dropdownScroll = Math.max(0, Math.min(this.dropdownScroll, contentH - boxH));
        this.dropdownBounds = new Dimension(x, top, width, boxH);

        RenderSystem.shadowRect(x, top, width, boxH, 0f, 0.45f);
        RenderSystem.fill(x, top, width, boxH, AppTheme.BG_2_ALPHA);
        RenderSystem.rect(x, top, width, boxH, AppTheme.NEON, 1f);

        RenderSystem.clip(x, top, width, boxH, windowH);
        int y = top + pad - this.dropdownScroll;

        if (hasSearch) {
            final String resultsHead = searching ? "SEARCHING..." : "RESULTS (" + results.size() + ")";
            this.text.render(resultsHead, x + 10, y + (headH - this.text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2f, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
            y += headH;

            if (results.isEmpty() && searching) {
                this.text.render("Looking across platforms...", x + 14, y + (rowH - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, AppTheme.NEON, AppTheme.TEXT_BODY);
                y += rowH;
            } else {
                for (final PlatformResult result: results) {
                    final Dimension row = new Dimension(x + 2, y, width - 4, rowH);
                    this.resultRowBounds.add(row);
                    this.resultRowItems.add(result);
                    this.renderResultRow(result, row);
                    y += rowH;
                }
            }
        }

        if (!history.isEmpty()) {
            RenderSystem.lineH(x + 10, y + headH / 2f, width - 20, AppTheme.STROKE, 1f);
            this.text.render("RECENT", x + 10, y + (headH - this.text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2f, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
            y += headH;
            for (final String entry: history) {
                final Dimension row = new Dimension(x + 2, y, width - 4, histH);
                this.historyRowBounds.add(row);
                this.historyRowItems.add(entry);
                if (this.dropdownBounds.contains(this.ctx.mouseX, this.ctx.mouseY) && row.contains(this.ctx.mouseX, this.ctx.mouseY)) {
                    RenderSystem.fill(row.x(), row.y(), row.width(), row.height(), AppTheme.alpha(AppTheme.NEON_DARK, 60));
                }
                PixelIcon.draw("search", x + 12, y + (histH - 11) / 2, 11, AppTheme.TEXT_FAINT);
                this.text.render(this.text.truncateToWidth(entry, width - 44, AppTheme.TEXT_BODY), x + 32,
                        y + (histH - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
                y += histH;
            }
        }

        RenderSystem.clearClip();
    }

    private void renderResultRow(final PlatformResult result, final Dimension row) {
        if (this.dropdownBounds.contains(this.ctx.mouseX, this.ctx.mouseY) && row.contains(this.ctx.mouseX, this.ctx.mouseY)) {
            RenderSystem.fill(row.x(), row.y(), row.width(), row.height(), AppTheme.alpha(AppTheme.NEON_DARK, 70));
        }

        final int thumbH = row.height() - 8;
        final int thumbW = Math.round(thumbH * 16f / 9f);
        final int thumbX = row.x() + 6;
        final int thumbY = row.y() + 4;
        this.renderThumb(result.thumbnail(), thumbX, thumbY, thumbW, thumbH);

        final int textX = thumbX + thumbW + 10;
        final int textMaxW = Math.max(20, row.right() - textX - 8);
        final String platform = result.platform() == null ? "?" : result.platform();
        final String platformTag = platform + ":";
        final int platformW = this.text.widthBold(platformTag, AppTheme.TEXT_BODY);
        final float textY = row.y() + (row.height() - this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f;
        this.text.renderBold(platformTag, textX, textY, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY);
        final String title = result.title() == null || result.title().isEmpty() ? "(untitled)" : result.title();
        this.text.render(this.text.truncateToWidth(title, Math.max(20, textMaxW - platformW - 6), AppTheme.TEXT_BODY),
                textX + platformW + 6, textY, AppTheme.TEXT, AppTheme.TEXT_BODY);
    }

    // ASPECT-FIT (NEVER OVERFLOWS THE CELL), SO IT NEEDS NO CLIP OF ITS OWN INSIDE THE DROPDOWN'S CLIP.
    private void renderThumb(final URI thumbnail, final int x, final int y, final int w, final int h) {
        final MediaPlayer player = this.thumbPlayer(thumbnail);
        if (player != null && player.texture() > 0 && player.width() > 0 && player.height() > 0) {
            RenderSystem.bindTexture((int) player.texture());
            RenderSystem.color(1f, 1f, 1f, 1f);
            final float imgAspect = (float) player.width() / player.height();
            final float boxAspect = (float) w / h;
            float bw = w;
            float bh = h;
            if (imgAspect > boxAspect) bh = w / imgAspect; else bw = h * imgAspect;
            RenderSystem.blit(x + (w - bw) / 2f, y + (h - bh) / 2f, bw, bh);
        } else {
            RenderSystem.fill(x, y, w, h, AppTheme.BG_0);
            RenderSystem.rect(x, y, w, h, AppTheme.STROKE, 1f);
            PixelIcon.draw(thumbnail == null ? "warn" : "tv", x + (w - 12) / 2, y + (h - 12) / 2, 12, AppTheme.TEXT_FAINT);
        }
    }

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
            this.navigator.accept(HomeScreen.Action.PLAYER);
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: Timeout", null);
            this.ctx.selectedMRL = null;
        }
    }

    private void renderLoadingDialog(final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);

        final int dots = (int) ((System.currentTimeMillis() / 500) % 4);
        final String loadingText = "Loading" + ".".repeat(dots);
        final String urlText = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";

        final int padding = 20;
        final int lineH = this.text.lineHeight(AppTheme.TEXT_BODY);

        final int contentW = Math.max(this.text.widthBold(loadingText, AppTheme.TEXT_BUTTON),
                Math.max(this.text.width(urlText, AppTheme.TEXT_BODY), this.text.width("ESC to cancel", AppTheme.TEXT_BODY)));
        final int dialogW = Math.min(Math.max(contentW + padding * 2 + 40, 400), windowW - 100);
        final int dialogH = padding + lineH + 15 + lineH + 10 + lineH + padding;

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = (windowH - dialogH) / 2;

        RenderSystem.dialogBox(dialogX, dialogY, dialogW, dialogH, Colors.BLUE, 3);

        int y = dialogY + padding;
        this.text.renderBold(loadingText, dialogX + padding, y, Colors.BLUE, AppTheme.TEXT_BUTTON);
        y += lineH + 15;

        final String truncatedUrl = this.text.truncateToWidth(urlText, dialogW - padding * 2, AppTheme.TEXT_BODY);
        this.text.render(truncatedUrl.isEmpty() ? "(empty)" : truncatedUrl,
                dialogX + padding, y, Colors.GRAY, AppTheme.TEXT_BODY);
        y += lineH + 10;

        this.text.render("ESC to cancel", dialogX + padding, y, Colors.GRAY, AppTheme.TEXT_BODY);

        RenderSystem.restoreProjection();
    }

    @Override
    public boolean wantsContinuousRender() {
        // POLL THE SEARCH HANDLE WHILE RESULTS ARRIVE, AND KEEP TICKING WHILE THE PREVIEW IS ANIMATING
        return AppChrome.crtEnabled() || this.loading || this.inputFocused
                || (this.search != null && !this.searchRenderedDone)
                || System.currentTimeMillis() - this.animPreviewStart < PREVIEW_ANIM_MS;
    }

    @Override
    public void render(final int windowW, final int windowH) {
        // RENDER HOME SCREEN AS BACKGROUND
        this.homeScreen.render(windowW, windowH);
        this.ensurePreviewMRL();
        if (this.loading) {
            this.checkLoadingState();
        }

        RenderSystem.setupOrtho(windowW, windowH);

        final String displayUrl = this.ctx.customUrlText != null ? this.ctx.customUrlText : "";
        final boolean searchMode = this.searchMode();

        // AUTO-SEARCH: ONCE TYPING SETTLES (DEBOUNCE), SEARCH THE CURRENT QUERY — NO ENTER NEEDED
        if (searchMode) {
            final String query = displayUrl.trim();
            final boolean stale = this.search == null || !this.search.query().equals(query);
            if (stale && !query.isEmpty() && System.currentTimeMillis() - this.lastEditMs >= SEARCH_DEBOUNCE_MS) {
                this.doSearch();
            }
        }

        final int padding = 20;
        final int titleH = 56;
        final int verticalMargin = 36;
        final int dialogH = Math.max(260, windowH - AppChrome.FOOTER_H - verticalMargin * 2);
        final int reservedH = titleH + 20 + 28 + 18 + 42 + 12 + 26 + 20 + 38 + 18;
        final int targetPreviewH = Math.max(80, dialogH - reservedH);
        final int maxDialogW = Math.max(360, windowW - 100);
        int dialogW = Math.min(Math.max(Math.round(targetPreviewH * 16f / 9f) + padding * 2, 660), maxDialogW);
        final int previewW = dialogW - padding * 2;
        // SEARCH MODE HAS NOTHING TO PREVIEW ("NO MEDIA"), SO SHRINK THE PREVIEW AND HAND THE FREED VERTICAL
        // SPACE TO THE RESULTS DROPDOWN (THE INPUT RISES; THE FOOTER STAYS PUT). animatePreviewH EASES BETWEEN
        // THE TWO HEIGHTS SO THE PREVIEW GROWS/SHRINKS SMOOTHLY INSTEAD OF SNAPPING WHEN THE MODE FLIPS.
        final int fullPreviewH = Math.round(previewW * 9f / 16f);
        final int previewH = this.animatePreviewH(searchMode ? Math.min(fullPreviewH, SEARCH_PREVIEW_H) : fullPreviewH);

        final int dialogX = (windowW - dialogW) / 2;
        final int dialogY = verticalMargin;

        RenderSystem.fill(0, 0, windowW, windowH, 0.02f, 0.03f, 0.10f, 0.65f);
        RenderSystem.shadowRect(dialogX, dialogY, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(dialogX, dialogY, dialogW, dialogH, 0f, AppTheme.NEON, 0.35f);
        RenderSystem.fill(dialogX, dialogY, dialogW, dialogH, AppTheme.BG_1);
        RenderSystem.rect(dialogX, dialogY, dialogW, dialogH, AppTheme.NEON, 1f);
        AppChrome.amberCube(dialogX - 1, dialogY - 1, 10);
        AppChrome.amberCube(dialogX + dialogW - 9, dialogY - 1, 10);
        AppChrome.amberCube(dialogX - 1, dialogY + dialogH - 9, 10);
        AppChrome.amberCube(dialogX + dialogW - 9, dialogY + dialogH - 9, 10);
        RenderSystem.fill(dialogX, dialogY, dialogW, titleH, AppTheme.BG_2);
        RenderSystem.lineH(dialogX, dialogY + titleH, dialogW, AppTheme.STROKE_BRIGHT, 1f);
        this.text.renderBold("OPEN MULTIMEDIA", dialogX + 20,
                dialogY + Math.max(0, (titleH - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f),
                AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
        this.closeBounds = new Dimension(dialogX + dialogW - 44, dialogY + 14, 26, 26);
        final boolean closeHover = this.closeBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        AppChrome.dialogCloseButton(this.closeBounds, closeHover);

        final int previewX = dialogX + padding;
        final int previewY = dialogY + titleH + 20;
        AppChrome.tvFrame(previewX, previewY, previewW, previewH, true);
        this.renderPreview(previewX + 6, previewY + 6, previewW - 12, previewH - 12);
        final String chip = this.bestQuality();
        if (chip != null) {
            final int chipW = this.text.width(chip, AppTheme.TEXT_BODY) + 18;
            final int chipX = previewX + previewW - chipW - 8;
            final int chipY = previewY;
            RenderSystem.fill(chipX, chipY, chipW, 22, AppTheme.alpha(AppTheme.BG_1, 235));
            RenderSystem.rect(chipX, chipY, chipW, 22, AppTheme.NEON, 1f);
            RenderSystem.glowRect(chipX, chipY, chipW, 22, 0f, AppTheme.NEON, 0.34f);
            this.text.render(chip, chipX + 9, chipY + 5, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY);
        }

        final int y = previewY + previewH + 28;
        final int tbX = dialogX + padding;
        final int pasteW = 104;
        final int reloadW = 112;
        final int gap = 8;
        final int tbW = dialogW - padding * 2 - pasteW - reloadW - gap * 2;
        final int tbH = 40;
        this.pasteBounds = new Dimension(tbX + tbW + gap, y, pasteW, tbH);
        this.reloadBounds = new Dimension(this.pasteBounds.right() + gap, y, reloadW, tbH);
        this.inputBounds = new Dimension(tbX, y, tbW, tbH);

        this.text.render(searchMode ? "SEARCH" : "URL OR PATH", tbX, y - 20,
                searchMode ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        if (this.inputFocused) RenderSystem.glowRect(tbX, y, tbW, tbH, 0f, AppTheme.NEON, 0.28f);
        RenderSystem.fill(tbX, y, tbW, tbH, AppTheme.BG_2);
        RenderSystem.rect(tbX, y, tbW, tbH, this.inputFocused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1);
        PixelIcon.draw(searchMode ? "search" : "link", tbX + 10, y + (tbH - 14) / 2, 14, searchMode ? AppTheme.CYAN : AppTheme.TEXT_FAINT);
        final boolean pasteHover = this.pasteBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(this.pasteBounds.x(), this.pasteBounds.y(), this.pasteBounds.width(), this.pasteBounds.height(),
                pasteHover ? AppTheme.alpha(AppTheme.NEON_DARK, 82) : AppTheme.BG_2);
        RenderSystem.rect(this.pasteBounds.x(), this.pasteBounds.y(), this.pasteBounds.width(), this.pasteBounds.height(), AppTheme.NEON, 1f);
        PixelIcon.draw("copy", this.pasteBounds.x() + 12, this.pasteBounds.y() + (this.pasteBounds.height() - 12) / 2, 12, AppTheme.NEON_LIGHT);
        this.text.renderBold("PASTE", this.pasteBounds.x() + 32,
                this.pasteBounds.y() + Math.max(0, (this.pasteBounds.height() - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f),
                AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
        // RELOAD ONLY MAKES SENSE FOR A VALID URI/MRL — DISABLED WHEN EMPTY OR IN SEARCH MODE
        this.renderInlineButton(this.reloadBounds, "RELOAD", "reload", AppTheme.NEON_LIGHT, !searchMode && !displayUrl.isEmpty());

        final float inputScale = AppTheme.TEXT_BUTTON;
        final int inputTextX = tbX + 32;
        final int inputTextY = y + Math.max(0, (tbH - this.text.glyphHeight(inputScale)) / 2);
        final String truncatedUrl = this.text.truncateToWidth(displayUrl, tbW - 46, inputScale);
        final boolean emptyInput = truncatedUrl.isEmpty();
        this.text.render(emptyInput ? "search, or paste a URL / path..." : truncatedUrl,
                inputTextX, inputTextY,
                emptyInput ? AppTheme.TEXT_FAINT : AppTheme.TEXT, inputScale);
        if (this.inputFocused && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
            final int caretTextW = emptyInput ? 0 : this.text.width(truncatedUrl, inputScale);
            final int caretX = Math.min(tbX + tbW - 10, inputTextX + caretTextW + (emptyInput ? -5 : 1));
            RenderSystem.fill(caretX, inputTextY, 2, this.text.glyphHeight(inputScale), AppTheme.NEON_LIGHT);
        }

        final int detectedY = y + tbH + 12;
        final String detected;
        final java.awt.Color detectedColor;
        if (searchMode) {
            // SEARCH IS AUTOMATIC NOW: SHOW RESULT COUNT WHEN DONE, OTHERWISE THE WORKING STATE (NO ENTER PROMPT)
            if (this.search != null && this.search.done()) {
                detected = this.search.results().size() + " RESULTS";
                detectedColor = AppTheme.CYAN;
            } else {
                detected = "SEARCHING";
                detectedColor = AppTheme.NEON;
            }
        } else {
            detected = this.statusLabel();
            detectedColor = this.statusColor();
        }
        final int detectedW = this.text.width(detected, AppTheme.TEXT_BODY) + 34;
        RenderSystem.fill(tbX, detectedY, detectedW, 24, AppTheme.alpha(detectedColor, detectedColor == AppTheme.TEXT_FAINT ? 24 : 36));
        RenderSystem.rect(tbX, detectedY, detectedW, 24, detectedColor, 1f);
        AppChrome.statusPip(tbX + 8, detectedY + 8, 8, detectedColor, false);
        this.text.render(detected, tbX + 24, detectedY + 5, detectedColor, AppTheme.TEXT_BODY);

        final int footY = dialogY + dialogH - 58;
        this.cancelBounds = new Dimension(dialogX + padding, footY, 128, 36);
        this.playBounds = new Dimension(dialogX + dialogW - padding - 128, footY, 128, 36);
        this.saveBounds = new Dimension(this.playBounds.x() - 140, footY, 128, 36);
        renderDialogButton("CANCEL", "ESC", "x", this.cancelBounds, AppTheme.TEXT_SOFT);
        // SAVE STORES A URL/PATH; IT MAKES NO SENSE FOR A SEARCH QUERY, SO IT IS DISABLED IN SEARCH MODE
        renderDialogButton("SAVE", "SPACE", "save", this.saveBounds,
                displayUrl.isEmpty() || searchMode ? AppTheme.TEXT_FAINT : AppTheme.NEON_LIGHT);
        // PRIMARY ACTION TOGGLES PLAY (GREEN) ↔ SEARCH (CYAN) WITH THE INPUT
        renderDialogButton(searchMode ? "SEARCH" : "PLAY", "ENTER", searchMode ? "search" : "play", this.playBounds,
                displayUrl.isEmpty() ? AppTheme.TEXT_FAINT : (searchMode ? AppTheme.CYAN : AppTheme.GREEN));

        // RESULTS DROPDOWN OVERLAYS THE AREA BETWEEN THE INPUT AND THE FOOTER (CLAMPED, SO BUTTONS STAY CLICKABLE)
        if (searchMode) {
            this.renderDropdown(this.inputBounds.x(), this.inputBounds.bottom() + 2,
                    this.inputBounds.width(), this.cancelBounds.y() - 10, windowH);
        } else {
            // LEFT SEARCH MODE (E.G. THE QUERY BECAME A URL) — DROP ANY STALE SEARCH/THUMBNAILS
            if (this.search != null) this.clearSearch();
            this.dropdownBounds = Dimension.ZERO;
            this.resultRowBounds.clear();
            this.historyRowBounds.clear();
        }
        // POLLED SEARCH: ENSURES ONE FINAL FRAME AFTER COMPLETION (SEE wantsContinuousRender)
        this.searchRenderedDone = this.search == null || this.search.done();

        RenderSystem.restoreProjection();

        if (this.loading) {
            this.renderLoadingDialog(windowW, windowH);
        }
    }

    private void renderInlineButton(final Dimension bounds, final String label, final String icon,
                                    final java.awt.Color color, final boolean enabled) {
        final java.awt.Color actual = enabled ? color : AppTheme.TEXT_FAINT;
        final boolean hover = enabled && bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover ? AppTheme.alpha(AppTheme.NEON_DARK, 82) : AppTheme.BG_2);
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), actual, 1f);
        if (enabled) RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, actual, hover ? 0.24f : 0.12f);
        PixelIcon.draw(icon, bounds.x() + 12, bounds.y() + (bounds.height() - 13) / 2, 13, actual);
        this.text.renderBold(label, bounds.x() + 32,
                bounds.y() + Math.max(0, (bounds.height() - this.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f),
                actual, AppTheme.TEXT_BUTTON);
    }

    private void renderPreview(final int x, final int y, final int w, final int h) {
        RenderSystem.fill(x, y, w, h, AppTheme.BG_0);
        AppChrome.crtOverlay(x, y, w, h, this.ctx.windowHeight);
        if (this.previewMRL == null) {
            PixelIcon.draw("copy", x + w / 2 - 16, y + h / 2 - 32, 32, AppTheme.TEXT_FAINT);
            this.text.render("NO MEDIA", x + w / 2 - this.text.width("NO MEDIA", AppTheme.TEXT_BODY) / 2, y + h / 2 + 12, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
            return;
        }
        final MRL.Status status = this.previewMRL.status();
        if (status == MRL.Status.FETCHING) {
            this.text.renderBold("LOADING", x + 22, y + h - 48, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
            this.text.render(this.text.truncateToWidth(this.previewUrl, w - 44, AppTheme.TEXT_SUBTITLE), x + 22, y + h - 24, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
            return;
        }
        if (status != MRL.Status.LOADED) {
            // ERROR/BLOCKED/EXPIRED/FORGOTTEN — SHOW THE EXACT STATE.
            final java.awt.Color color = this.statusColor();
            final String label = this.statusLabel();
            PixelIcon.draw("warn", x + w / 2 - 16, y + h / 2 - 36, 32, color);
            this.text.renderBold(label, x + w / 2 - this.text.widthBold(label, AppTheme.TEXT_BUTTON) / 2, y + h / 2 + 8, color, AppTheme.TEXT_BUTTON);
            return;
        }

        final MRL.Source src = this.firstSource();
        final Metadata meta = src != null ? src.metadata() : null;
        final String title = meta != null && meta.title() != null ? meta.title() : this.previewTitle();
        final String desc = meta != null && meta.desc() != null ? meta.desc() : this.previewUrl;
        final String duration = meta != null && meta.duration() > 0 ? this.ctx.formatTime(meta.duration()) : "--:--";
        RenderSystem.fillGradientV(x, y + h / 2f, w, h / 2f,
                0f, 0f, 0f, 0f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.88f);
        this.text.renderBold(this.text.truncateToWidth(title.toUpperCase(), w - 44, AppTheme.TEXT_BUTTON, java.awt.Font.BOLD), x + 22, y + h - 76, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON);
        this.text.render(this.text.truncateToWidth(desc, w - 44, AppTheme.TEXT_SUBTITLE), x + 22, y + h - 48, AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE);
        this.text.render(duration, x + 22, y + h - 24, AppTheme.CYAN, AppTheme.TEXT_BODY);
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

    private java.awt.Color statusColor() {
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

    private String bestQuality() {
        final MRL.Source src = this.firstSource();
        if (src == null || src.availableQualities().isEmpty()) return null;
        final MediaQuality q = src.availableQualities().stream()
                .max(java.util.Comparator.comparingInt(value -> value.threshold))
                .orElse(MediaQuality.UNKNOWN);
        return q == MediaQuality.UNKNOWN ? null : q.name();
    }

    private void renderDialogButton(final String label, final String hotkey, final String icon,
                                    final Dimension bounds, final java.awt.Color color) {
        final boolean hover = bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        final boolean enabled = color != AppTheme.TEXT_FAINT;
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover && enabled ? AppTheme.alpha(color, 54) : AppTheme.BG_2);
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), color, 1.6f);
        if (enabled) RenderSystem.glowRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0f, color, hover ? 0.28f : 0.16f);
        final float labelScale = AppTheme.TEXT_BUTTON;
        PixelIcon.draw(icon, bounds.x() + 12, bounds.y() + (bounds.height() - 13) / 2, 13, color);
        this.text.renderBold(label, bounds.x() + 32,
                bounds.y() + Math.max(0, (bounds.height() - this.text.glyphHeightBold(labelScale)) / 2f),
                color, labelScale);
        final float hotkeyScale = AppTheme.TEXT_SUBTITLE;
        final int hkW = this.text.width(hotkey, hotkeyScale) + 12;
        final int hkH = 20;
        final int hkX = bounds.right() - hkW - 8;
        final int hkY = bounds.y() + (bounds.height() - hkH) / 2;
        RenderSystem.rect(hkX, hkY, hkW, hkH, AppTheme.STROKE, 1f);
        this.text.render(hotkey, hkX + 6,
                hkY + Math.max(0, (hkH - this.text.glyphHeight(hotkeyScale)) / 2f),
                AppTheme.TEXT_FAINT, hotkeyScale);
    }

    @Override
    public void handleKey(final int key, final int action) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
        if (this.loading) {
            if (key == GLFW_KEY_ESCAPE) {
                this.loading = false;
                this.loadGeneration++;
                this.ctx.selectedMRL = null;
            }
            return;
        }

        switch (key) {
            case GLFW_KEY_BACKSPACE, GLFW_KEY_DELETE -> {
                if (this.inputFocused && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
                    this.ctx.customUrlText = this.ctx.ctrlDown ? this.deleteLastWord(this.ctx.customUrlText) : this.ctx.customUrlText.substring(0, this.ctx.customUrlText.length() - 1);
                    this.lastEditMs = System.currentTimeMillis();
                    this.ensurePreviewMRL();
                }
            }
            case GLFW_KEY_SPACE -> {
                // IN SEARCH MODE SPACE IS A QUERY CHARACTER (HANDLED BY handleChar), NOT THE SAVE SHORTCUT
                if (action == GLFW_PRESS && !this.searchMode() && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) this.saveCustomUrl();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (action == GLFW_PRESS && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
                    if (this.searchMode()) this.doSearch();
                    else this.playCustomUrl();
                }
            }
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
            case GLFW_KEY_V -> {
                if (action == GLFW_PRESS && this.ctx.ctrlDown) this.pasteFromClipboard();
            }
            case GLFW_KEY_R -> {
                if (action == GLFW_PRESS && !this.searchMode() && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) this.reloadPreviewMRL();
            }
        }
    }

    private String deleteLastWord(final String value) {
        int i = value.length();
        while (i > 0 && Character.isWhitespace(value.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(value.charAt(i - 1)) && "/\\?&=#.:_-".indexOf(value.charAt(i - 1)) < 0) i--;
        if (i > 0 && "/\\?&=#.:_-".indexOf(value.charAt(i - 1)) >= 0) i--;
        return value.substring(0, Math.max(0, i));
    }

    @Override
    public void handleChar(final int codepoint) {
        if (!this.inputFocused || this.loading) return;
        if (this.ctx.ctrlDown) return;
        if (codepoint < 32 || codepoint == 127) return;
        this.ctx.customUrlText = (this.ctx.customUrlText == null ? "" : this.ctx.customUrlText) + new String(Character.toChars(codepoint));
        this.lastEditMs = System.currentTimeMillis();
        this.ensurePreviewMRL();
    }

    @Override
    public void handleMouseClick(final double mx, final double my) {
        if (this.loading) return;

        // DROPDOWN TAKES PRIORITY AND KEEPS THE INPUT FOCUSED. A RESULT REPLACES THE INPUT WITH ITS RAW URL;
        // A HISTORY ENTRY REFILLS THE QUERY AND RE-SEARCHES.
        if (this.dropdownBounds.contains(mx, my)) {
            for (int i = 0; i < this.resultRowBounds.size(); i++) {
                if (this.resultRowBounds.get(i).contains(mx, my)) {
                    this.selectResult(this.resultRowItems.get(i));
                    return;
                }
            }
            for (int i = 0; i < this.historyRowBounds.size(); i++) {
                if (this.historyRowBounds.get(i).contains(mx, my)) {
                    this.ctx.customUrlText = this.historyRowItems.get(i);
                    this.doSearch();
                    return;
                }
            }
            return; // INSIDE THE DROPDOWN BUT NOT ON A ROW — SWALLOW THE CLICK
        }

        this.inputFocused = this.inputBounds.contains(mx, my);
        if (this.closeBounds.contains(mx, my)) {
            this.navigator.accept(HomeScreen.Action.BACK);
        } else if (this.pasteBounds.contains(mx, my)) {
            this.pasteFromClipboard();
        } else if (this.reloadBounds.contains(mx, my) && !this.searchMode() && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
            this.reloadPreviewMRL();
        } else if (this.cancelBounds.contains(mx, my)) {
            this.navigator.accept(HomeScreen.Action.BACK);
        } else if (this.saveBounds.contains(mx, my) && !this.searchMode() && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
            this.saveCustomUrl();
        } else if (this.playBounds.contains(mx, my) && this.ctx.customUrlText != null && !this.ctx.customUrlText.isEmpty()) {
            if (this.searchMode()) this.doSearch();
            else this.playCustomUrl();
        }
    }

    @Override
    public void handleScroll(final double yOffset) {
        if (this.loading) return;
        if (this.dropdownBounds.contains(this.ctx.mouseX, this.ctx.mouseY)) {
            this.dropdownScroll = Math.max(0, this.dropdownScroll - (int) (yOffset * 28));
            this.ctx.requestRender();
        }
    }

    @Override
    public String instructions() {
        if (this.loading) return "ESC: Cancel";
        if (this.searchMode()) return "TYPE: Search | CLICK: Pick result | V: Paste | ESC: Cancel";
        return "ENTER: Play | SPACE: Save | V: Paste | R: Reload | ESC: Cancel";
    }
}
