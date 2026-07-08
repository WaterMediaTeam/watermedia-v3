package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
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
import org.watermedia.bootstrap.app.view.Button;
import org.watermedia.bootstrap.app.view.Canvas;
import org.watermedia.bootstrap.app.view.View;
import org.watermedia.bootstrap.app.view.ViewGroup;
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
 * Screen with dialog for opening custom multimedia URLs, built on the retained view tree: the HomeScreen
 * background, the centered dialog frame and the live preview panel are painted in {@link #renderChrome},
 * while the URL/search field and the results/history dropdown live in the content tree as ported custom
 * views. Instructions are shown in the bottom bar, not in the dialog.
 */
public class OpenMultimediaScreen extends ViewScreen {

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

    // CONTENT TREE + THE RECT IT OCCUPIES (INPUT FIELD + RESULTS DROPDOWN), COMPUTED IN renderChrome
    private Body body;
    private InputField field;
    private DropdownView dropdown;
    private int contentBoxX;
    private int contentBoxY;
    private int contentBoxW;
    private int contentBoxH;

    // RESULT THUMBNAILS RENDER THROUGH SHORT-LIVED IMAGE PLAYERS, KEYED BY THUMBNAIL URI (MIRRORS
    // MRLSelectorScreen). THE ACTIVE RENDER BACKEND SUPPLIES THE ENGINE; RELEASED ON A NEW SEARCH/EXIT.
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
    protected View<?> build() {
        this.field = new InputField().width(View.MATCH_PARENT).height(40);
        this.dropdown = new DropdownView().width(View.MATCH_PARENT).height(View.WRAP_CONTENT);
        this.body = new Body().add(this.field).add(this.dropdown).width(View.MATCH_PARENT).height(View.MATCH_PARENT);
        return this.body;
    }

    @Override
    public void onEnter() {
        super.onEnter();
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
    // (renderChrome() AND instructions()) ON A CONTINUOUSLY-REDRAWN SCREEN, SO THE RESULT IS MEMOIZED AGAINST THE
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
    // THE OLD THUMBNAILS AND LET renderChrome() POLL THE FRESH HANDLE AS RESULTS LAND OFF-THREAD.
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
        if (!this.thumbPlayers.isEmpty()) {
            // SNAPSHOT AND CLEAR FIRST SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING PLAYER
            final List<MediaPlayer> players = new ArrayList<>(this.thumbPlayers.values());
            this.thumbPlayers.clear();
            this.releaseAsync(players);
        }
        this.thumbAttempted.clear();
        this.thumbSubscribed.clear();
    }

    // HANDS RESULT-THUMBNAIL PLAYERS OFF FOR BACKGROUND RELEASE — MIRRORS AppContext.releasePlayer. THE
    // PLAYERS MUST ALREADY BE REMOVED FROM thumbPlayers SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING
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
                final MediaPlayer player = MediaAPI.createPlayer(mrl, i, RenderSystem.mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
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
        if (player != null && player.texture() != 0 && player.width() > 0 && player.height() > 0) {
            RenderSystem.bindMediaTexture(player.texture());
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
        // super = A FOCUSED FIELD (VIA THE TREE'S textInputActive); PLUS THE SCREEN'S OWN TRIGGERS: POLL THE
        // SEARCH HANDLE WHILE RESULTS ARRIVE, AND KEEP TICKING WHILE THE PREVIEW IS ANIMATING
        return super.wantsContinuousRender() || AppChrome.crtEnabled() || this.loading || this.inputFocused
                || (this.search != null && !this.searchRenderedDone)
                || System.currentTimeMillis() - this.animPreviewStart < PREVIEW_ANIM_MS;
    }

    @Override
    public void render(final int windowW, final int windowH) {
        super.render(windowW, windowH); // HOMESCREEN BG + DIALOG CHROME (renderChrome) THEN THE VIEW TREE
        // THE LOAD MODAL OVERLAYS EVERYTHING AND IS CENTERED IN THE FULL WINDOW, SO IT PAINTS LAST
        if (this.loading) this.renderLoadingDialog(windowW, windowH);
    }

    @Override
    protected void renderChrome(final int windowW, final int windowH) {
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

        // THE INPUT LABEL (THE FIELD BOX/TEXT/CARET ITSELF IS DRAWN BY THE VIEW TREE — SEE InputField)
        this.text.render(searchMode ? "SEARCH" : "URL OR PATH", tbX, y - 20,
                searchMode ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY);
        final boolean pasteHover = this.pasteBounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        Button.render(this.text, this.pasteBounds.x(), this.pasteBounds.y(), this.pasteBounds.width(), this.pasteBounds.height(),
                "PASTE", "", "copy", 12, AppTheme.NEON, AppTheme.NEON_LIGHT, false, pasteHover, true);
        // RELOAD ONLY MAKES SENSE FOR A VALID URI/MRL — DISABLED WHEN EMPTY OR IN SEARCH MODE
        this.renderInlineButton(this.reloadBounds, "RELOAD", "reload", AppTheme.NEON_LIGHT, !searchMode && !displayUrl.isEmpty());

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
        // BUTTONS SIZE TO THEIR CONTENT SO THE LABEL AND THE HOTKEY CHIP KEEP A CONSISTENT 12px GAP (NO
        // OVERLAP LIKE THE OLD FIXED 128px WIDTH, NO EXCESS EITHER)
        final String playLabel = searchMode ? "SEARCH" : "PLAY";
        // WIDTHS MATCH THE DRAWN BUTTON EXACTLY BY PASSING EACH BUTTON'S ICON (SEE renderDialogButton CALLS BELOW)
        final int cancelW = this.dialogButtonWidth("CANCEL", "ESC", "x");
        final int saveW = this.dialogButtonWidth("SAVE", "SPACE", "save");
        final int playW = this.dialogButtonWidth(playLabel, "ENTER", searchMode ? "search" : "play");
        this.cancelBounds = new Dimension(dialogX + padding, footY, cancelW, 36);
        this.playBounds = new Dimension(dialogX + dialogW - padding - playW, footY, playW, 36);
        this.saveBounds = new Dimension(this.playBounds.x() - saveW - 12, footY, saveW, 36);
        this.renderDialogButton("CANCEL", "ESC", "x", this.cancelBounds, AppTheme.TEXT_SOFT);
        // SAVE STORES A URL/PATH; IT MAKES NO SENSE FOR A SEARCH QUERY, SO IT IS DISABLED IN SEARCH MODE
        this.renderDialogButton("SAVE", "SPACE", "save", this.saveBounds,
                displayUrl.isEmpty() || searchMode ? AppTheme.TEXT_FAINT : AppTheme.NEON_LIGHT);
        // PRIMARY ACTION TOGGLES PLAY (GREEN) ↔ SEARCH (CYAN) WITH THE INPUT
        this.renderDialogButton(playLabel, "ENTER", searchMode ? "search" : "play", this.playBounds,
                displayUrl.isEmpty() ? AppTheme.TEXT_FAINT : (searchMode ? AppTheme.CYAN : AppTheme.GREEN));

        // THE TREE CONTENT RECT: THE INPUT FIELD SITS AT THE TOP AND THE RESULTS DROPDOWN OVERLAYS THE AREA
        // DOWN TO JUST ABOVE THE FOOTER (CLAMPED, SO THE BUTTONS STAY CLICKABLE — SAME BUDGET AS THE LEGACY DRAW)
        this.contentBoxX = tbX;
        this.contentBoxY = y;
        this.contentBoxW = tbW;
        this.contentBoxH = Math.max(tbH, (this.cancelBounds.y() - 10) - y);

        // RESULTS DROPDOWN IS ONLY LIVE IN SEARCH MODE; LEAVING IT (E.G. THE QUERY BECAME A URL) DROPS ANY
        // STALE SEARCH/THUMBNAILS. THE BOX ITSELF IS PAINTED BY THE VIEW TREE (DropdownView).
        if (searchMode) {
            this.dropdown.visible(true);
        } else {
            this.dropdown.visible(false);
            if (this.search != null) this.clearSearch();
            this.dropdownBounds = Dimension.ZERO;
            this.resultRowBounds.clear();
            this.historyRowBounds.clear();
        }
        // POLLED SEARCH: ENSURES ONE FINAL FRAME AFTER COMPLETION (SEE wantsContinuousRender)
        this.searchRenderedDone = this.search == null || this.search.done();

        RenderSystem.restoreProjection();
    }

    @Override
    protected int contentX(final int windowW, final int windowH) {
        return this.contentBoxX;
    }

    @Override
    protected int contentY(final int windowW, final int windowH) {
        return this.contentBoxY;
    }

    @Override
    protected int contentW(final int windowW, final int windowH) {
        return this.contentBoxW;
    }

    @Override
    protected int contentH(final int windowW, final int windowH) {
        return this.contentBoxH;
    }

    private void renderInlineButton(final Dimension bounds, final String label, final String icon,
                                    final java.awt.Color color, final boolean enabled) {
        final boolean hover = enabled && bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        // NO HOTKEY CHIP — color IS BOTH ACCENT AND LABEL COLOR
        Button.render(this.text, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                label, "", icon, 12, color, color, false, hover, enabled);
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

    // INTRINSIC WIDTH THAT FITS THE ICON + LABEL + 12px GAP + HOTKEY CHIP, MATCHING renderDialogButton EXACTLY
    private int dialogButtonWidth(final String label, final String hotkey, final String icon) {
        return Button.width(this.text, label, hotkey, icon, 12);
    }

    private void renderDialogButton(final String label, final String hotkey, final String icon,
                                    final Dimension bounds, final java.awt.Color color) {
        final boolean hover = bounds.contains(this.ctx.mouseX, this.ctx.mouseY);
        final boolean enabled = color != AppTheme.TEXT_FAINT;
        // color IS BOTH THE ACCENT (BORDER) AND THE LABEL COLOR; hotkey RENDERS AS THE TRAILING CHIP
        Button.render(this.text, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                label, hotkey, icon, 12, color, color, false, hover, enabled);
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

        // LET THE FOCUSED FIELD CONSUME BACKSPACE/DELETE (PRESS+REPEAT) VIA THE TREE FIRST
        if (this.root.key(key, action)) return;

        switch (key) {
            case GLFW_KEY_SPACE -> {
                // IN SEARCH MODE SPACE IS A QUERY CHARACTER (HANDLED BY THE FIELD), NOT THE SAVE SHORTCUT
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
    public void handleMouseClick(final double mx, final double my) {
        if (this.loading) return;

        // CAPTURE THE DROPDOWN HIT BEFORE THE TREE POSSIBLY CLEARS ITS BOUNDS (selectResult -> clearSearch).
        // THE DROPDOWN TAKES PRIORITY AND KEEPS THE INPUT FOCUSED: A RESULT REPLACES THE INPUT WITH ITS RAW URL;
        // A HISTORY ENTRY REFILLS THE QUERY AND RE-SEARCHES (SEE DropdownView#dispatchClick).
        final boolean inDropdown = this.dropdownBounds.contains(mx, my);
        super.handleMouseClick(mx, my);
        if (inDropdown) return; // INSIDE THE DROPDOWN — THE TREE HANDLED IT (OR SWALLOWED THE CLICK)

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
    public String instructions() {
        if (this.loading) return "ESC: Cancel";
        if (this.searchMode()) return "TYPE: Search | CLICK: Pick result | V: Paste | ESC: Cancel";
        return "ENTER: Play | SPACE: Save | V: Paste | R: Reload | ESC: Cancel";
    }

    // ROOT CONTENT: STACKS THE INPUT FIELD AT THE TOP OF THE CONTENT RECT AND THE RESULTS DROPDOWN 2px BELOW
    // IT (inputBounds.bottom() + 2), EXACTLY WHERE THE LEGACY DRAW PLACED THEM.
    private final class Body extends ViewGroup<Body> {

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            field.measure(innerAvailWidth, 40);
            dropdown.measure(innerAvailWidth, Math.max(0, innerAvailHeight - 42));
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            field.layout(this.left, this.top);
            dropdown.layout(this.left, this.top + 42);
        }
    }

    // THE URL/SEARCH FIELD — A DELIBERATE PORT OF THE LEGACY INPUT BOX (FOCUS GLOW, BG_2 FILL, NEON/STROKE
    // BORDER, LINK/SEARCH GLYPH, TRUNCATED VALUE OR PLACEHOLDER, 2px NEON_LIGHT CARET AT 480ms). FOCUS AND
    // TEXT MUTATION STAY ON THE SCREEN (inputFocused/customUrlText); THE FIELD DRIVES textInputActive AND
    // CHAR/BACKSPACE INPUT THROUGH THE TREE.
    private final class InputField extends View<InputField> {

        @Override
        public boolean textInputActive() {
            return inputFocused;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            final boolean search = searchMode();
            final String displayUrl = ctx.customUrlText != null ? ctx.customUrlText : "";
            if (inputFocused) RenderSystem.glowRect(x, y, w, h, 0f, AppTheme.NEON, 0.28f);
            RenderSystem.fill(x, y, w, h, AppTheme.BG_2);
            RenderSystem.rect(x, y, w, h, inputFocused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1);
            PixelIcon.draw(search ? "search" : "link", x + 10, y + (h - 14) / 2, 14, search ? AppTheme.CYAN : AppTheme.TEXT_FAINT);

            final float inputScale = AppTheme.TEXT_BUTTON;
            final int inputTextX = x + 32;
            final int inputTextY = y + Math.max(0, (h - text.glyphHeight(inputScale)) / 2);
            final String truncatedUrl = text.truncateToWidth(displayUrl, w - 46, inputScale);
            final boolean emptyInput = truncatedUrl.isEmpty();
            text.render(emptyInput ? "search, or paste a URL / path..." : truncatedUrl,
                    inputTextX, inputTextY,
                    emptyInput ? AppTheme.TEXT_FAINT : AppTheme.TEXT, inputScale);
            if (inputFocused && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
                final int caretTextW = emptyInput ? 0 : text.width(truncatedUrl, inputScale);
                final int caretX = Math.min(x + w - 10, inputTextX + caretTextW + (emptyInput ? -5 : 1));
                RenderSystem.fill(caretX, inputTextY, 2, text.glyphHeight(inputScale), AppTheme.NEON_LIGHT);
            }
        }

        @Override
        public boolean dispatchChar(final int codepoint) {
            if (!inputFocused || loading) return false;
            if (ctx.ctrlDown) return false;
            if (codepoint < 32 || codepoint == 127) return false;
            ctx.customUrlText = (ctx.customUrlText == null ? "" : ctx.customUrlText) + new String(Character.toChars(codepoint));
            lastEditMs = System.currentTimeMillis();
            ensurePreviewMRL();
            return true;
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            // BACKSPACE/DELETE ARE FIELD KEYS ONLY WHILE FOCUSED; OTHERWISE THEY FALL THROUGH TO SCREEN NAV.
            // handleKey ALREADY GATED action TO PRESS+REPEAT, SO DELETION REPEATS WHEN THE KEY IS HELD.
            if (key != GLFW_KEY_BACKSPACE && key != GLFW_KEY_DELETE) return false;
            if (!inputFocused) return false;
            if (ctx.customUrlText != null && !ctx.customUrlText.isEmpty()) {
                ctx.customUrlText = ctx.ctrlDown ? deleteLastWord(ctx.customUrlText) : ctx.customUrlText.substring(0, ctx.customUrlText.length() - 1);
                lastEditMs = System.currentTimeMillis();
                ensurePreviewMRL();
            }
            return true;
        }
    }

    // THE RESULTS DROPDOWN — A DELIBERATE PORT OF THE LEGACY renderDropdown: A SHADOWED, NEON-BORDERED BOX
    // BELOW THE INPUT WITH A RESULTS SECTION (OR "SEARCHING..." PLACEHOLDER) THEN A SEPARATOR AND THE
    // RECENT-QUERY HISTORY, CLIPPED AND SCROLLED WITHIN ITS BUDGET. onMeasure SIZES THE BOX (SO contains()
    // MATCHES dropdownBounds); onDraw PAINTS IT AND RECORDS THE PER-ROW HIT BOXES. IT OWNS ITS OWN SCROLL AND
    // CLICK ROUTING. THE STRUCTURE (SECTION HEADERS, TWO ROW HEIGHTS) DOES NOT MAP ONTO A UNIFORM ListView,
    // SO A FAITHFUL PORT BEATS DEDUP.
    private final class DropdownView extends View<DropdownView> {

        private int boxH;

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.contentWidth = innerAvailWidth;
            if (!this.visible) {
                this.contentHeight = 0;
                this.boxH = 0;
                return;
            }
            final List<PlatformResult> results = search != null ? search.results() : List.of();
            final List<String> history = search != null ? search.history() : PlatformAPI.searchHistory();
            final boolean searching = search != null && !search.done();
            if (results.isEmpty() && history.isEmpty() && !searching) {
                this.contentHeight = 0;
                this.boxH = 0;
                return;
            }

            final boolean hasSearch = search != null;
            final int rowH = 38;
            final int histH = 26;
            final int headH = 20;
            final int pad = 6;

            int contentH = pad;
            if (hasSearch) contentH += headH + (results.isEmpty() && searching ? rowH : results.size() * rowH);
            if (!history.isEmpty()) contentH += headH + history.size() * histH;
            contentH += pad;

            final int availH = Math.max(rowH, innerAvailHeight);
            this.boxH = Math.min(contentH, availH);
            dropdownScroll = Math.max(0, Math.min(dropdownScroll, contentH - this.boxH));
            this.contentHeight = this.boxH;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            resultRowBounds.clear();
            resultRowItems.clear();
            historyRowBounds.clear();
            historyRowItems.clear();
            dropdownBounds = Dimension.ZERO;
            if (this.boxH <= 0) return;

            final int x = this.left;
            final int top = this.top;
            final int width = this.measuredWidth;
            final int boxH = this.boxH;
            final int windowH = canvas.windowHeight();

            final List<PlatformResult> results = search != null ? search.results() : List.of();
            final List<String> history = search != null ? search.history() : PlatformAPI.searchHistory();
            final boolean searching = search != null && !search.done();
            final boolean hasSearch = search != null;
            final int rowH = 38;
            final int histH = 26;
            final int headH = 20;
            final int pad = 6;

            dropdownBounds = new Dimension(x, top, width, boxH);

            RenderSystem.shadowRect(x, top, width, boxH, 0f, 0.45f);
            RenderSystem.fill(x, top, width, boxH, AppTheme.BG_2_ALPHA);
            RenderSystem.rect(x, top, width, boxH, AppTheme.NEON, 1f);

            RenderSystem.clip(x, top, width, boxH, windowH);
            int y = top + pad - dropdownScroll;

            if (hasSearch) {
                final String resultsHead = searching ? "SEARCHING..." : "RESULTS (" + results.size() + ")";
                text.render(resultsHead, x + 10, y + (headH - text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2f, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
                y += headH;

                if (results.isEmpty() && searching) {
                    text.render("Looking across platforms...", x + 14, y + (rowH - text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, AppTheme.NEON, AppTheme.TEXT_BODY);
                    y += rowH;
                } else {
                    for (final PlatformResult result: results) {
                        final Dimension row = new Dimension(x + 2, y, width - 4, rowH);
                        resultRowBounds.add(row);
                        resultRowItems.add(result);
                        renderResultRow(result, row);
                        y += rowH;
                    }
                }
            }

            if (!history.isEmpty()) {
                RenderSystem.lineH(x + 10, y + headH / 2f, width - 20, AppTheme.STROKE, 1f);
                text.render("RECENT", x + 10, y + (headH - text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2f, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE);
                y += headH;
                for (final String entry: history) {
                    final Dimension row = new Dimension(x + 2, y, width - 4, histH);
                    historyRowBounds.add(row);
                    historyRowItems.add(entry);
                    if (dropdownBounds.contains(ctx.mouseX, ctx.mouseY) && row.contains(ctx.mouseX, ctx.mouseY)) {
                        RenderSystem.fill(row.x(), row.y(), row.width(), row.height(), AppTheme.alpha(AppTheme.NEON_DARK, 60));
                    }
                    PixelIcon.draw("search", x + 12, y + (histH - 11) / 2, 11, AppTheme.TEXT_FAINT);
                    text.render(text.truncateToWidth(entry, width - 44, AppTheme.TEXT_BODY), x + 32,
                            y + (histH - text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
                    y += histH;
                }
            }

            RenderSystem.clearClip();
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            if (!this.visible || !dropdownBounds.contains(mx, my)) return false;
            for (int i = 0; i < resultRowBounds.size(); i++) {
                if (resultRowBounds.get(i).contains(mx, my)) {
                    selectResult(resultRowItems.get(i));
                    return true;
                }
            }
            for (int i = 0; i < historyRowBounds.size(); i++) {
                if (historyRowBounds.get(i).contains(mx, my)) {
                    ctx.customUrlText = historyRowItems.get(i);
                    doSearch();
                    return true;
                }
            }
            return true; // INSIDE THE DROPDOWN BUT NOT ON A ROW — SWALLOW THE CLICK
        }

        @Override
        public boolean dispatchScroll(final double mx, final double my, final double amount) {
            if (!this.visible || !dropdownBounds.contains(mx, my)) return false;
            dropdownScroll = Math.max(0, dropdownScroll - (int) (amount * 28));
            this.invalidate();
            return true;
        }
    }
}
