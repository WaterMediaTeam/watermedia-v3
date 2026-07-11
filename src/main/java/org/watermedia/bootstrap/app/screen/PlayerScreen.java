package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Dropdown;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.Icon;
import org.watermedia.bootstrap.app.element.IconButton;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ParentFrame;
import org.watermedia.bootstrap.app.element.SeekBar;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.TextField;
import org.watermedia.bootstrap.app.element.VideoSurface;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static org.lwjgl.glfw.GLFW.*;
import static org.watermedia.bootstrap.app.render.RenderSystem.mediaEngineSupplier;

/**
 * Screen for media playback: a fully retained tree stacking the {@link VideoSurface} under a HUD layer
 * (top info row, metrics panel, transport bar), with the source/quality and video-settings dialogs
 * hosted as modal {@link Dialog}s on the screen overlay.
 */
public class PlayerScreen extends Screen {

    private static final float META_SCALE = AppTheme.TEXT_SECTION;
    private static final float META_HEAD_SCALE = AppTheme.TEXT_DISPLAY;
    private static final float META_DESC_LABEL_SCALE = AppTheme.TEXT_BUTTON;
    private static final float META_DESC_SCALE = AppTheme.TEXT_SECTION;
    private static final int RES_FIELD_MAX_DIGITS = 5;
    // PLAYBACK-SPEED PRESETS FOR THE SPEED DROPDOWN — VALUE + DISPLAY LABEL, INDEX 3 (1.0x) IS THE DEFAULT
    private static final float[] SPEED_VALUES = {0.25f, 0.50f, 0.75f, 1.0f, 1.25f, 1.50f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f};
    private static final String[] SPEED_LABELS = {"0.25x", "0.50x", "0.75x", "1.0x", "1.25x", "1.50x", "1.75x", "2.0x", "2.5x", "3.0x", "4.0x"};
    // EVERY METRIC ROW LABEL (WITHOUT THE COLON) — SIZES THE FIXED LABEL COLUMN SO VALUES ALIGN WITHOUT OVERLAP
    private static final String[] METRIC_LABELS = {"Engine", "MRL", "Source", "FPS", "Status", "Time",
            "Volume", "Quality", "Dimensions", "Speed", "Live", "Title", "Author", "Published"};
    // WHEEL STEP (LOGICAL PX PER NOTCH) FOR THE METRICS PANEL — MATCHES ParentScroll SO SCROLLING FEELS THE SAME
    private static final int METRIC_SCROLL_STEP = 48;
    // HOISTED FORMATTER: REBUILDING IT PER FRAME (UP TO TWICE) WAS PURE OVERHEAD
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());
    // HUD FADE / TOP-BAND GRADIENT STOPS (PORTED FROM THE LEGACY fadeLeft/fadeBottom/fillGradientV CALLS)
    private static final Color FADE_DARK = new Color(0, 0, 0, 230);
    private static final Color FADE_CLEAR = new Color(0, 0, 0, 0);
    private static final Color BAND_DARK = new Color(6, 9, 26, 235);
    private static final Color BAND_CLEAR = new Color(6, 9, 26, 0);

    private final Consumer<HomeScreen.Action> navigator;

    // LIVE PLAYBACK STATE
    private boolean debugOpen = true;
    private boolean endedSoundPlayed;
    private boolean loopEnabled = true;
    private float speed = 1f;
    // SWALLOWS THE ESC/ENTER RELEASE WHOSE PRESS JUST CLOSED THE SPEED DROPDOWN MENU, SO ONE TAP DOES NOT
    // ALSO LEAVE THE SCREEN (THE MENU CLOSES ITSELF ON PRESS; THE SCREEN SHORTCUTS ACT ON RELEASE)
    private boolean swallowKeyUp;
    private String resWidthText = "";
    private String resHeightText = "";
    private int lodSelectedIndex;
    private int sourceSelectedIndex;
    private int qualitySelectedIndex;
    // DEFERS THE SCROLL-SELECTION-INTO-VIEW UNTIL THE SOURCE LIST HAS A REAL VIEWPORT (FIRST LAYOUT),
    // THEN NEVER AGAIN — FREE WHEEL/SCROLLBAR SCROLLING IS NOT STOLEN BACK (SAME RULE AS LEGACY)
    private boolean sourceFollowPending;

    // TREE
    private Hud hud;
    private Dialog qualityDialog;
    private ListView<MRL.Source> srcList;
    private ListView<MediaQuality> qualList;
    private Parent qualLeftCol;
    private Parent qualRightCol;
    private Box qualDivider;
    private SectionHead qualHead;
    private Dialog videoDialog;
    private TextField resWidthField;
    private TextField resHeightField;
    private Text activeMaxText;
    private Parent videoLeftCol;
    private Parent videoRightCol;
    private Box videoDivider;

    public PlayerScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    protected Element<?> build() {
        this.hud = new Hud();
        // VIDEO AREA STACK: THE FRAME BLIT AT THE BOTTOM, THE LOCALIZED CRT EFFECT OVER IT (GATED BY ctx.crt,
        // NEVER HIT-TESTABLE), AND THE HUD ON TOP. THE GLOBAL SHELL CRT IS GONE — THIS IS THE PLAYER'S OWN
        return new ParentFrame().add(new VideoSurface()).add(new CrtOverlay()).add(this.hud);
    }

    @Override
    public void onEnter() {
        super.onEnter();
        this.hideQualityDialog();
        this.hideVideoDialog();
        this.debugOpen = true;
        this.endedSoundPlayed = false;
        this.loopEnabled = true;
        this.speed = 1f;
        this.swallowKeyUp = false;
        this.resWidthText = "";
        this.resHeightText = "";
        this.lodSelectedIndex = 0;
        this.startPlayer();
    }

    @Override
    public void onExit() {
        this.hideQualityDialog();
        this.hideVideoDialog();
        this.ctx.releasePlayer();
    }

    @Override
    public void releaseMedia() {
        this.ctx.releasePlayer();
    }

    @Override
    public boolean continuous() {
        // TEXT INPUT (CARET BLINK) PLUS THE PLAYER-ACTIVE TRIGGER (VIDEO NEEDS FRAMES)
        final MediaPlayer player = this.ctx.player;
        return this.textInputActive()
                || (player != null && !player.error() && !player.stopped()
                    && (!player.ended() || !this.endedSoundPlayed));
    }

    @Override
    public List<Keybind> keybinds() {
        if (this.videoDialog != null) {
            return List.of(new Keybind("TYPE", "Resolution"), new Keybind("TAB", "Field"),
                    new Keybind("UP/DOWN", "LOD"), new Keybind("ENTER/ESC", "Close"));
        }
        if (this.qualityDialog != null) {
            return List.of(new Keybind("ARROWS", "Navigate"), new Keybind("ENTER", "Select"),
                    new Keybind("ESC", "Cancel"));
        }
        final boolean multi = this.ctx.availableSources != null && this.ctx.availableSources.length > 1;
        return List.of(new Keybind("SPACE", "Play/Pause"), new Keybind("L", "Loop"),
                new Keybind("V", "Video"), new Keybind("ESC", "Menu"),
                new Keybind("ENTER", multi ? "Sources" : "Quality"));
    }

    @Override
    protected void onUpdate() {
        final MediaPlayer player = this.ctx.player;
        // ENDED CHIME — ONCE PER ENDING, ONLY WHILE NO DIALOG IS ACTIVE (SAME RULE AS LEGACY)
        if (player != null && this.qualityDialog == null && this.videoDialog == null) {
            if (player.ended()) {
                if (!this.endedSoundPlayed) {
                    this.ctx.playSelectionSound();
                    this.endedSoundPlayed = true;
                }
            } else if (!player.stopped() && !player.error()) {
                this.endedSoundPlayed = false;
            }
        }
        if (this.qualityDialog != null) this.syncQualityDialog();
        if (this.videoDialog != null) this.syncVideoDialog();
    }

    // ==========================================================================
    // PLAYER CONTROL
    // ==========================================================================

    private void startPlayer() {
        if (this.ctx.selectedMRL == null || this.ctx.selectedSource == null) return;

        this.ctx.releasePlayer();

        this.ctx.player = MediaAPI.createPlayer(
                this.ctx.selectedMRL,
                this.ctx.sourceSelectorIndex,
                // THE ACTIVE RENDER BACKEND DECIDES THE ENGINE (GLEngine / VKEngine) — THE SCREEN STAYS AGNOSTIC
                mediaEngineSupplier(Thread.currentThread(), this.ctx),
                // AUDIO BACKEND PICKED BY THE USER IN SETTINGS (OpenAL / Java Sound), RESOLVED AT CREATION
                this.ctx.audioEngine.supplier()
        );

        if (this.ctx.player == null) {
            this.ctx.showError("Player Error",
                    "WaterMedia failed to create media player.\nNo compatible player engine available.",
                    this::returnToMenu);
            return;
        }

        this.ctx.player.quality(this.ctx.selectedQuality);
        this.ctx.player.repeat(this.loopEnabled);
        this.ctx.player.speed(this.speed);
        this.applyVideoSettings();
        this.ctx.player.start();
        this.endedSoundPlayed = false;
    }

    private void returnToMenu() {
        this.hideQualityDialog();
        this.hideVideoDialog();
        this.ctx.releasePlayer();

        if (this.ctx.selectedMRL == null || this.ctx.selectedGroup == null) {
            this.navigator.accept(HomeScreen.Action.BACK);
        } else {
            this.navigator.accept(HomeScreen.Action.MRL_SELECTOR);
        }
    }

    private void navigateSource(final int delta) {
        if (this.ctx.availableSources == null || this.ctx.availableSources.length <= 1) return;
        final int newIndex = (this.ctx.sourceSelectorIndex + delta + this.ctx.availableSources.length) % this.ctx.availableSources.length;
        if (newIndex != this.ctx.sourceSelectorIndex) {
            this.ctx.sourceSelectorIndex = newIndex;
            this.ctx.selectedSource = this.ctx.availableSources[this.ctx.sourceSelectorIndex];
            this.startPlayer();
        }
    }

    private void seekToPercent(final int percent) {
        final MediaPlayer player = this.ctx.player;
        if (player == null || player.duration() <= 0) return;
        player.seek((player.duration() * percent) / 100);
    }

    private void togglePlayback(final MediaPlayer player) {
        if (player.ended() || player.stopped()) {
            player.repeat(this.loopEnabled);
            player.start();
            this.endedSoundPlayed = false;
            this.ctx.requestRender();
            return;
        }
        player.togglePlay();
    }

    private void toggleLoop(final MediaPlayer player) {
        this.loopEnabled = !this.loopEnabled;
        player.repeat(this.loopEnabled);
        this.ctx.requestRender();
    }

    // PUSHES THE CUSTOM MAX RESOLUTION AND LOD TO THE LIVE PLAYER. BOTH APPLY ON THE FLY
    // TO THE NEXT UPLOADED FRAME; RE-APPLIED FROM startPlayer SO THEY SURVIVE PLAYER RESTARTS.
    private void applyVideoSettings() {
        final MediaPlayer player = this.ctx.player;
        if (player == null) return;
        player.maxSize(this.parseDim(this.resWidthText, player.sourceWidth()), this.parseDim(this.resHeightText, player.sourceHeight()));
        player.lod(MediaPlayer.LodLevel.values()[this.lodSelectedIndex]);
    }

    // PARSES A RESOLUTION FIELD: EMPTY OR NON-POSITIVE MEANS UNLIMITED (NO_SIZE); A POSITIVE
    // VALUE IS CLAMPED TO THE NATIVE SOURCE SIZE SO THE CAP NEVER EXCEEDS THE ACTIVE MAXIMUM.
    private int parseDim(final String text, final int nativeMax) {
        if (text.isEmpty()) return 0;
        final int value;
        try {
            value = Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            return 0;
        }
        if (value <= 0) return 0;
        return nativeMax > 0 ? Math.min(value, nativeMax) : value;
    }

    private String lodLabel() {
        return MediaPlayer.LodLevel.values()[this.lodSelectedIndex].name();
    }

    private String maxSizeLabel() {
        final MediaPlayer player = this.ctx.player;
        if (player == null) return "FULL";
        final int mw = player.maxWidth();
        final int mh = player.maxHeight();
        if (mw == 0 && mh == 0) return "FULL";
        return (mw == 0 ? "*" : mw) + "x" + (mh == 0 ? "*" : mh);
    }

    private String formatDate(final Metadata meta) {
        return DATE_FORMAT.format(meta.postedAt());
    }

    private MediaType currentMediaType() {
        return this.ctx.selectedSource == null ? null : this.ctx.selectedSource.type();
    }

    private String playerResolution(final MediaPlayer player) {
        if (player == null || player.width() <= 0 || player.height() <= 0) return "--";
        return player.width() + "x" + player.height();
    }

    private static Color mediaTypeColor(final MediaType type) {
        if (type == MediaType.IMAGE) return AppTheme.GREEN;
        if (type == MediaType.VIDEO) return AppTheme.AMBER;
        if (type == MediaType.AUDIO) return AppTheme.CYAN;
        return AppTheme.TEXT_FAINT;
    }

    private int typeTagWidth(final MediaType type) {
        final String label = type == null ? "MEDIA" : type.name();
        return this.text.width(label, AppTheme.TEXT_BODY) + 22;
    }

    private void drawTypeTag(final Canvas canvas, final int x, final int y, final MediaType type) {
        final String label = type == null ? "MEDIA" : type.name();
        final Color color = mediaTypeColor(type);
        final int w = this.text.width(label, AppTheme.TEXT_BODY) + 22;
        canvas.fill(x, y, w, 22, AppTheme.alpha(AppTheme.BG_1, 188));
        canvas.stroke(x, y, w, 22, color, 1f);
        canvas.glow(x, y, w, 22, 0f, color, 0.16f);
        canvas.text(label, x + 11, y + Math.max(0, (22 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2), color, AppTheme.TEXT_BODY, false);
    }

    private String sourceTitle(final MRL.Source source, final int sourceIndex) {
        final Metadata meta = source != null ? source.metadata() : null;
        if (meta != null && meta.title() != null && !meta.title().isBlank()) return meta.title();
        if (this.ctx.selectedMRLName != null && !this.ctx.selectedMRLName.isBlank()) return this.ctx.selectedMRLName;
        return "SOURCE " + (sourceIndex + 1);
    }

    // ==========================================================================
    // KEYBOARD — THE TREE (INCLUDING OPEN DIALOGS) ALWAYS WINS FIRST
    // ==========================================================================

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // THE SPEED DROPDOWN MENU LIVES ON OUR OVERLAY AND SWALLOWS ITS OWN ESC/ENTER ON PRESS. IF THIS PRESS
        // CLOSED IT, EAT THE MATCHING RELEASE SO ONE TAP IS NOT ALSO A SCREEN SHORTCUT (E.G. LEAVE THE SCREEN)
        final boolean menuOpen = this.speedMenuOpen();
        if (super.dispatchKey(key, action)) {
            if (menuOpen && action != GLFW_RELEASE && !this.speedMenuOpen()) this.swallowKeyUp = true;
            return true;
        }
        if (action != GLFW_RELEASE) return false;
        if (this.swallowKeyUp) {
            this.swallowKeyUp = false;
            return true;
        }
        // A KEY THAT DID NOT CLOSE A STILL-OPEN MENU: KEEP THE SCREEN SHORTCUTS SUPPRESSED WHILE IT SHOWS
        if (menuOpen) return false;
        // ESC MUST LEAVE THE SCREEN EVEN WHEN PLAYER CREATION FAILED (ctx.player == null)
        if (key == GLFW_KEY_ESCAPE) {
            this.returnToMenu();
            return true;
        }
        final MediaPlayer player = this.ctx.player;
        if (player == null) return false;
        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.openQualityDialog();
            case GLFW_KEY_V -> this.openVideoDialog();
            case GLFW_KEY_SPACE -> this.togglePlayback(player);
            case GLFW_KEY_LEFT -> player.rewind();
            case GLFW_KEY_RIGHT -> player.forward();
            case GLFW_KEY_U -> player.seekQuick(player.time() + 5_000);
            case GLFW_KEY_Y -> player.seekQuick(player.time() - 5_000);
            case GLFW_KEY_S -> player.stop();
            case GLFW_KEY_UP -> player.volume(player.volume() + 5);
            case GLFW_KEY_DOWN -> player.volume(player.volume() - 5);
            case GLFW_KEY_PERIOD -> player.nextFrame();
            case GLFW_KEY_COMMA -> player.previousFrame();
            case GLFW_KEY_N -> this.navigateSource(1);
            case GLFW_KEY_B -> this.navigateSource(-1);
            case GLFW_KEY_L -> this.toggleLoop(player);
            case GLFW_KEY_0, GLFW_KEY_KP_0 -> this.seekToPercent(0);
            case GLFW_KEY_1, GLFW_KEY_KP_1 -> this.seekToPercent(10);
            case GLFW_KEY_2, GLFW_KEY_KP_2 -> this.seekToPercent(20);
            case GLFW_KEY_3, GLFW_KEY_KP_3 -> this.seekToPercent(30);
            case GLFW_KEY_4, GLFW_KEY_KP_4 -> this.seekToPercent(40);
            case GLFW_KEY_5, GLFW_KEY_KP_5 -> this.seekToPercent(50);
            case GLFW_KEY_6, GLFW_KEY_KP_6 -> this.seekToPercent(60);
            case GLFW_KEY_7, GLFW_KEY_KP_7 -> this.seekToPercent(70);
            case GLFW_KEY_8, GLFW_KEY_KP_8 -> this.seekToPercent(80);
            case GLFW_KEY_9, GLFW_KEY_KP_9 -> this.seekToPercent(90);
            default -> {
                return false;
            }
        }
        return true;
    }

    // TRUE WHILE THE SPEED DROPDOWN'S FLOATING MENU IS MOUNTED ON OUR OVERLAY. THE OVERLAY ONLY EVER HOSTS
    // OUR TWO DIALOGS PLUS THAT MENU, SO ANY OTHER CHILD IS THE MENU (THE DIALOGS ARE MUTUALLY EXCLUSIVE WITH IT).
    private boolean speedMenuOpen() {
        for (final Element<?> child: this.overlay().children()) {
            if (child != this.qualityDialog && child != this.videoDialog) return true;
        }
        return false;
    }

    // NEAREST SPEED PRESET INDEX FOR A LIVE PLAYER SPEED — KEEPS THE DROPDOWN SELECTION IN SYNC WITH THE PLAYER
    private int nearestSpeedIndex(final float value) {
        int nearest = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_VALUES.length; i++) {
            final float delta = Math.abs(SPEED_VALUES[i] - value);
            if (delta < best) {
                best = delta;
                nearest = i;
            }
        }
        return nearest;
    }

    // ==========================================================================
    // QUALITY DIALOG (SOURCE + QUALITY SELECTION)
    // ==========================================================================

    private void openQualityDialog() {
        if (this.ctx.availableSources == null || this.ctx.availableSources.length == 0) return;
        this.sourceSelectedIndex = Math.max(0, Math.min(this.ctx.sourceSelectorIndex, this.ctx.availableSources.length - 1));
        this.loadQualitiesForSource(this.sourceSelectedIndex);
        if (this.ctx.availableQualities == null || this.ctx.availableQualities.length == 0) return;

        this.srcList = new ListView<>();
        this.srcList.rowHeight(50).spacing(8).scrollbarWidth(3)
                .selectionColor(null).hoverColor(null)
                .rowFactory((src, i) -> {
                    final SourceRow row = new SourceRow(src, i);
                    // HOVER PREVIEWS THE SOURCE (LOADS ITS QUALITIES) WITHOUT APPLYING — SAME AS LEGACY
                    row.onHover(r -> {
                        if (this.sourceSelectedIndex != i) {
                            this.srcList.selection(i);
                            this.selectSource(i, false);
                            this.ctx.playSelectionSound();
                            r.invalidate();
                        }
                    });
                    return row;
                })
                .onSelect((src, i) -> {
                    this.selectSource(i, true);
                    this.ctx.playSelectionSound();
                })
                .items(Arrays.asList(this.ctx.availableSources))
                .selection(this.sourceSelectedIndex)
                .width(MAX_PARENT);

        this.qualList = new ListView<>();
        this.qualList.rowHeight(34).spacing(6).scrollbarWidth(3)
                .selectionColor(null).hoverColor(null)
                .rowFactory((q, i) -> {
                    final QualityRow row = new QualityRow(q);
                    row.onHover(r -> {
                        if (this.qualList.selectedIndex() != i) {
                            this.qualList.selection(i);
                            this.qualitySelectedIndex = i;
                            this.ctx.playSelectionSound();
                            r.invalidate();
                        }
                    });
                    return row;
                })
                .onSelect((q, i) -> this.applyQuality(i))
                .items(Arrays.asList(this.ctx.availableQualities))
                .selection(this.qualitySelectedIndex)
                .width(MAX_PARENT);

        final SectionHead srcHead = new SectionHead("Sources");
        srcHead.count(this.ctx.availableSources.length + " items");
        this.qualHead = new SectionHead("Quality");
        this.qualHead.count(this.ctx.availableQualities.length + " available");

        this.qualLeftCol = Parent.column().spacing(12).add(srcHead).add(this.srcList);
        this.qualLeftCol.margin(new Spacing(0, 24, 0, 4));
        this.qualRightCol = Parent.column().spacing(12).add(this.qualHead).add(this.qualList);
        this.qualRightCol.margin(new Spacing(0, 2, 0, 21));
        this.qualDivider = new Box().background(AppTheme.STROKE_BRIGHT);

        final DialogKeys content = new DialogKeys(this::qualityKey);
        content.add(this.qualLeftCol).add(this.qualDivider).add(this.qualRightCol);

        this.qualityDialog = new Dialog()
                .accent(AppTheme.NEON)
                .content(content)
                .onDismiss(this::hideQualityDialog)
                .onPrimary(() -> this.applyQuality(this.qualList.selectedIndex()));
        // CLICKS INSIDE THE PANEL MUST NOT FALL THROUGH TO THE SCRIM — LEGACY IGNORED THEM
        this.qualityDialog.panel().consumeTouch(true);

        this.syncQualityDialog();
        this.showDialog(this.qualityDialog);
        this.sourceFollowPending = true;
    }

    private void hideQualityDialog() {
        if (this.qualityDialog == null) return;
        this.hideDialog(this.qualityDialog);
        this.qualityDialog = null;
        this.srcList = null;
        this.qualList = null;
        this.qualLeftCol = null;
        this.qualRightCol = null;
        this.qualDivider = null;
        this.qualHead = null;
        this.sourceFollowPending = false;
    }

    private void syncQualityDialog() {
        // LOGICAL PX — THE TREE (AND THE DIALOG PANEL) IS MEASURED IN LOGICAL COORDINATES
        final int winW = this.ctx.logicalWidth();
        final int winH = this.ctx.logicalHeight();
        final int srcCount = this.ctx.availableSources != null ? this.ctx.availableSources.length : 1;
        final int qualCount = this.ctx.availableQualities != null ? this.ctx.availableQualities.length : 1;
        final int dialogW = Math.min(Math.max(620, (int) (winW * 0.60f)), winW - 72);
        final int dialogH = Math.min(Math.max(320, 98 + Math.max(Math.min(srcCount, 5) * 58, Math.max(1, qualCount) * 40)), winH - 96);
        final int split = Math.min(dialogW - 250, Math.max(360, (int) (dialogW * 0.72f)));
        final int listH = dialogH - 82;
        this.qualityDialog.panelWidth(dialogW);
        this.qualLeftCol.width(split - 48);
        this.qualRightCol.width(dialogW - split - 44);
        this.qualDivider.size(1, listH + 42);
        this.srcList.height(listH);
        this.qualList.height(listH);
        // FOLLOW THE INITIAL SELECTION INTO VIEW ONCE THE LIST HAS A MEASURED VIEWPORT, THEN NEVER AGAIN
        if (this.sourceFollowPending && this.srcList.innerHeight() > 0) {
            final int idx = Math.max(0, this.srcList.selectedIndex());
            this.srcList.selection(-1);
            this.srcList.moveSelection(idx);
            this.sourceFollowPending = false;
        }
    }

    private boolean qualityKey(final int key) {
        switch (key) {
            case GLFW_KEY_LEFT -> {
                if (this.srcList.selectedIndex() > 0) {
                    this.srcList.moveSelection(-1);
                    this.selectSource(this.srcList.selectedIndex(), true);
                    this.ctx.playSelectionSound();
                }
                return true;
            }
            case GLFW_KEY_RIGHT -> {
                if (this.ctx.availableSources != null && this.srcList.selectedIndex() < this.ctx.availableSources.length - 1) {
                    this.srcList.moveSelection(1);
                    this.selectSource(this.srcList.selectedIndex(), true);
                    this.ctx.playSelectionSound();
                }
                return true;
            }
            case GLFW_KEY_UP -> {
                this.moveQualitySelection(-1);
                return true;
            }
            case GLFW_KEY_DOWN -> {
                this.moveQualitySelection(1);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void moveQualitySelection(final int delta) {
        final int before = this.qualList.selectedIndex();
        this.qualList.moveSelection(delta);
        if (this.qualList.selectedIndex() != before) {
            this.qualitySelectedIndex = this.qualList.selectedIndex();
            this.ctx.playSelectionSound();
        }
    }

    private void loadQualitiesForSource(final int sourceIndex) {
        if (this.ctx.availableSources == null || sourceIndex < 0 || sourceIndex >= this.ctx.availableSources.length) return;
        final var qualities = this.ctx.availableSources[sourceIndex].availableQualities();
        if (qualities == null || qualities.isEmpty()) return;

        this.ctx.availableQualities = qualities.toArray(new MediaQuality[0]);
        Arrays.sort(this.ctx.availableQualities, Comparator.comparingInt(q -> q.threshold));

        // FIND CURRENT QUALITY INDEX
        this.qualitySelectedIndex = 0;
        for (int i = 0; i < this.ctx.availableQualities.length; i++) {
            if (this.ctx.availableQualities[i] == this.ctx.selectedQuality) {
                this.qualitySelectedIndex = i;
                break;
            }
        }
    }

    private void selectSource(final int sourceIndex, final boolean apply) {
        if (this.ctx.availableSources == null || sourceIndex < 0 || sourceIndex >= this.ctx.availableSources.length) return;
        this.sourceSelectedIndex = sourceIndex;
        this.loadQualitiesForSource(sourceIndex);
        this.refreshQualityList();
        if (!apply || sourceIndex == this.ctx.sourceSelectorIndex) return;
        if (this.ctx.availableQualities != null && this.ctx.availableQualities.length > 0) {
            this.ctx.selectedQuality = this.ctx.availableQualities[this.qualitySelectedIndex];
        }
        this.ctx.sourceSelectorIndex = sourceIndex;
        this.ctx.selectedSource = this.ctx.availableSources[sourceIndex];
        this.startPlayer();
    }

    private void refreshQualityList() {
        if (this.qualList == null || this.ctx.availableQualities == null) return;
        this.qualList.items(Arrays.asList(this.ctx.availableQualities)).selection(this.qualitySelectedIndex);
        if (this.qualHead != null) this.qualHead.count(this.ctx.availableQualities.length + " available");
    }

    private void applyQuality(final int index) {
        if (this.ctx.availableQualities == null || index < 0 || index >= this.ctx.availableQualities.length) return;
        this.qualitySelectedIndex = index;
        this.ctx.selectedQuality = this.ctx.availableQualities[index];
        if (this.ctx.availableSources != null && this.sourceSelectedIndex != this.ctx.sourceSelectorIndex) {
            this.ctx.sourceSelectorIndex = this.sourceSelectedIndex;
            this.ctx.selectedSource = this.ctx.availableSources[this.sourceSelectedIndex];
            this.startPlayer();
        } else if (this.ctx.player != null) {
            this.ctx.player.quality(this.ctx.selectedQuality);
        }
        this.hideQualityDialog();
    }

    // ==========================================================================
    // VIDEO DIALOG (UPLOAD RESOLUTION CAP + LOD LEVEL)
    // ==========================================================================

    private void openVideoDialog() {
        if (this.ctx.player == null || this.ctx.player.error()) return;

        this.resWidthField = new TextField();
        this.resWidthField.placeholder("AUTO").maxLength(RES_FIELD_MAX_DIGITS).accent(AppTheme.NEON_LIGHT)
                .value(this.resWidthText).height(34).margin(new Spacing(6, 0, 0, 0))
                .onChange(v -> this.resEdit(this.resWidthField, true, v));
        this.resHeightField = new TextField();
        this.resHeightField.placeholder("AUTO").maxLength(RES_FIELD_MAX_DIGITS).accent(AppTheme.NEON_LIGHT)
                .value(this.resHeightText).height(34).margin(new Spacing(6, 0, 0, 0))
                .onChange(v -> this.resEdit(this.resHeightField, false, v));
        this.activeMaxText = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.CYAN).margin(new Spacing(14, 0, 0, 0));

        this.videoLeftCol = Parent.column()
                .add(new SectionHead("Resolution").count("custom px"))
                .add(new Text("WIDTH").scale(AppTheme.TEXT_SUBTITLE).color(AppTheme.TEXT_FAINT).margin(new Spacing(12, 0, 0, 0)))
                .add(this.resWidthField)
                .add(new Text("HEIGHT").scale(AppTheme.TEXT_SUBTITLE).color(AppTheme.TEXT_FAINT).margin(new Spacing(20, 0, 0, 0)))
                .add(this.resHeightField)
                .add(this.activeMaxText)
                .add(new Text("EMPTY FIELD = UNLIMITED").scale(AppTheme.TEXT_SUBTITLE).color(AppTheme.TEXT_FAINT).margin(new Spacing(10, 0, 0, 0)));
        this.videoLeftCol.margin(new Spacing(0, 22, 0, 4));

        final MediaPlayer.LodLevel[] lods = MediaPlayer.LodLevel.values();
        final Parent lodCol = Parent.column().spacing(6).margin(new Spacing(10, 0, 0, 0)).width(MAX_PARENT);
        for (int i = 0; i < lods.length; i++) {
            final int idx = i;
            lodCol.add(new LodRow(idx, lods[idx].name(), lods[idx].percent() + "%")
                    .size(MAX_PARENT, 34)
                    .onClick(r -> {
                        this.clearResFocus();
                        this.selectVideoLod(idx);
                    }));
        }
        this.videoRightCol = Parent.column()
                .add(new SectionHead("Level of Detail").count(lods.length + " levels"))
                .add(lodCol);
        this.videoRightCol.margin(new Spacing(0, 2, 0, 23));
        this.videoDivider = new Box().background(AppTheme.STROKE_BRIGHT);

        final DialogKeys content = new DialogKeys(this::videoKey);
        content.add(this.videoLeftCol).add(this.videoDivider).add(this.videoRightCol);

        this.videoDialog = new Dialog()
                .accent(AppTheme.NEON)
                .content(content)
                .onDismiss(this::hideVideoDialog)
                .onPrimary(this::hideVideoDialog)
                .dismissOnScrim(this::hideVideoDialog);
        // A CLICK INSIDE THE PANEL (OUTSIDE THE FIELDS/ROWS) DROPS FIELD FOCUS — SAME AS LEGACY
        this.videoDialog.panel().onClick(p -> this.clearResFocus());

        this.syncVideoDialog();
        this.showDialog(this.videoDialog);
    }

    private void hideVideoDialog() {
        if (this.videoDialog == null) return;
        this.hideDialog(this.videoDialog);
        this.videoDialog = null;
        this.resWidthField = null;
        this.resHeightField = null;
        this.activeMaxText = null;
        this.videoLeftCol = null;
        this.videoRightCol = null;
        this.videoDivider = null;
    }

    private void syncVideoDialog() {
        final int winW = this.ctx.logicalWidth();
        final int dialogW = Math.min(Math.max(580, (int) (winW * 0.54f)), winW - 72);
        final int colW = dialogW / 2 - 46;
        this.videoDialog.panelWidth(dialogW);
        this.videoLeftCol.width(colW);
        this.videoRightCol.width(colW);
        // DIVIDER SPANS THE TALLER COLUMN — BOTH HEIGHTS ARE CONTENT-DRIVEN
        final int subH = this.text.glyphHeight(AppTheme.TEXT_SUBTITLE);
        final int bodyH = this.text.glyphHeight(AppTheme.TEXT_BODY);
        final int leftH = 30 + 12 + subH + 6 + 34 + 20 + subH + 6 + 34 + 14 + bodyH + 10 + subH;
        final int rightH = 30 + 10 + MediaPlayer.LodLevel.values().length * 40 - 6;
        this.videoDivider.size(1, Math.max(leftH, rightH));

        final MediaPlayer player = this.ctx.player;
        final int srcW = player == null ? 0 : player.sourceWidth();
        final int srcH = player == null ? 0 : player.sourceHeight();
        this.activeMaxText.text("ACTIVE MAX  " + (srcW > 0 && srcH > 0 ? srcW + " x " + srcH : "PENDING"));
    }

    private boolean videoKey(final int key) {
        switch (key) {
            case GLFW_KEY_TAB -> {
                final TextField target = this.resWidthField.focused() ? this.resHeightField : this.resWidthField;
                this.resWidthField.focused(false);
                this.resHeightField.focused(false);
                target.focused(true).invalidate();
                return true;
            }
            case GLFW_KEY_DELETE -> {
                // BACKSPACE IS HANDLED BY THE FIELD ITSELF; LEGACY TREATED DELETE THE SAME WAY
                final TextField field = this.focusedResField();
                if (field != null && !field.value().isEmpty()) {
                    final String next = field.value().substring(0, field.value().length() - 1);
                    field.value(next).invalidate();
                    this.resEdit(field, field == this.resWidthField, next);
                }
                return true;
            }
            case GLFW_KEY_UP -> {
                this.selectVideoLod(this.lodSelectedIndex - 1);
                return true;
            }
            case GLFW_KEY_DOWN -> {
                this.selectVideoLod(this.lodSelectedIndex + 1);
                return true;
            }
            case GLFW_KEY_ESCAPE -> {
                // FIRST ESC ONLY DROPS FIELD FOCUS; THE NEXT ONE FALLS THROUGH TO THE DIALOG DISMISS
                if (this.resWidthField.focused() || this.resHeightField.focused()) {
                    this.clearResFocus();
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private TextField focusedResField() {
        if (this.resWidthField != null && this.resWidthField.focused()) return this.resWidthField;
        if (this.resHeightField != null && this.resHeightField.focused()) return this.resHeightField;
        return null;
    }

    private void clearResFocus() {
        if (this.resWidthField != null) this.resWidthField.focused(false).invalidate();
        if (this.resHeightField != null) this.resHeightField.focused(false).invalidate();
    }

    // SANITIZES A RESOLUTION FIELD EDIT (DIGITS ONLY, CLAMPED TO THE NATIVE SOURCE SIZE) AND
    // PUSHES THE NEW CAP TO THE LIVE PLAYER.
    private void resEdit(final TextField field, final boolean width, final String raw) {
        final MediaPlayer player = this.ctx.player;
        final int nativeMax = player == null ? 0 : (width ? player.sourceWidth() : player.sourceHeight());
        final StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && digits.length() < RES_FIELD_MAX_DIGITS; i++) {
            final char c = raw.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        String value = digits.toString();
        if (!value.isEmpty() && nativeMax > 0 && Integer.parseInt(value) > nativeMax) {
            value = String.valueOf(nativeMax);
        }
        if (!value.equals(raw)) field.value(value);
        if (width) this.resWidthText = value;
        else this.resHeightText = value;
        this.applyVideoSettings();
        this.ctx.requestRender();
    }

    private void selectVideoLod(final int index) {
        final int next = Math.max(0, Math.min(MediaPlayer.LodLevel.values().length - 1, index));
        if (next == this.lodSelectedIndex) return;
        this.lodSelectedIndex = next;
        this.applyVideoSettings();
        this.ctx.playSelectionSound();
        this.ctx.requestRender();
    }

    // ==========================================================================
    // INNER ELEMENTS
    // ==========================================================================

    // HORIZONTAL DIALOG CONTENT ROW WITH A KEY FALLBACK: THE CHILDREN (TEXT FIELDS) GET THE KEY FIRST,
    // THEN THE HANDLER RUNS ON RELEASE (LEGACY DIALOG KEYS ACTED ON RELEASE) — THE HOSTING Dialog STILL
    // SWALLOWS WHATEVER IS LEFT, KEEPING THE MODAL CONTRACT
    private static final class DialogKeys extends Parent {

        private final IntPredicate keys;

        DialogKeys(final IntPredicate keys) {
            super(Orientation.HORIZONTAL);
            this.keys = keys;
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            if (super.dispatchKey(key, action)) return true;
            return action == GLFW_RELEASE && this.keys.test(key);
        }
    }

    // SECTION HEADER (NEON PIP + BOLD LABEL + FAINT COUNT) — CANVAS PORT OF THE LEGACY DIALOG HEADS
    private final class SectionHead extends Element<SectionHead> {

        private final String label;
        private String count = "";

        SectionHead(final String label) {
            this.label = label.toUpperCase();
            this.width = MAX_PARENT;
            this.height = 30;
        }

        SectionHead count(final String value) {
            this.count = value == null ? "" : value.toUpperCase();
            return this;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top + 3;
            canvas.fill(x, y, 4, 21, AppTheme.NEON);
            canvas.glow(x, y, 4, 21, 0f, AppTheme.NEON, 0.16f);
            canvas.text(this.label, x + 18, y + Math.max(0, (21 - canvas.textHeight(AppTheme.TEXT_SECTION, true)) / 2),
                    AppTheme.NEON, AppTheme.TEXT_SECTION, true);
            if (!this.count.isEmpty()) {
                canvas.text(this.count, x + 30 + canvas.textWidth(this.label, AppTheme.TEXT_SECTION, true),
                        y + Math.max(0, (21 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                        AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            }
        }
    }

    // LABELED TRANSPORT BUTTON WITH THE CYAN VALUE CHIP (MAX-SIZE) — STOCK Button HAS NO CHIP-COLOR
    // KNOB, SO IT PAINTS THROUGH THE SHARED DESIGN-SYSTEM STATIC FOR AN IDENTICAL LOOK
    private final class ChipButton extends Element<ChipButton> {

        private final String icon;
        private final Color accent;
        private String label = "";
        private String chip = "";

        ChipButton(final String icon, final Color accent) {
            this.icon = icon;
            this.accent = accent;
        }

        ChipButton label(final String value) {
            this.label = value == null ? "" : value;
            return this;
        }

        ChipButton chip(final String value) {
            this.chip = value == null ? "" : value;
            return this;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // WRAP TO CONTENT (BORDER PADDING + ICON + LABEL + THE CYAN VALUE CHIP) VIA THE SHARED BUTTON
            // METRIC, SO A LONG QUALITY NAME OR RESOLUTION CHIP IS NEVER CLIPPED
            this.contentWidth = Button.width(PlayerScreen.this.text, this.label, this.chip, this.icon, 12);
            this.contentHeight = 30;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            Button.render(canvas, this.left, this.top, this.measuredWidth, this.measuredHeight,
                    this.label, this.chip, this.icon, 12, this.accent, this.accent, AppTheme.CYAN,
                    false, this.hovered, true);
        }
    }

    // SOURCE ROW FOR THE QUALITY DIALOG: STATE ICON + BOLD TITLE + MEDIA TYPE TAG + SUBTITLE
    private final class SourceRow extends Group<SourceRow> {

        private final MRL.Source source;
        private final int index;
        private final Icon icon = new Icon("folder").iconSize(14);

        SourceRow(final MRL.Source source, final int index) {
            this.source = source;
            this.index = index;
            this.add(this.icon);
        }

        @Override
        protected void onUpdate() {
            final boolean active = this.index == PlayerScreen.this.ctx.sourceSelectorIndex;
            this.icon.icon(active ? "play" : "folder").color(active ? AppTheme.GREEN : AppTheme.NEON_LIGHT);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.icon.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = 0;
            this.contentHeight = 0;
        }

        @Override
        protected void onLayout() {
            this.icon.layout(this.left + 12, this.top + 15);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(this.left, this.top, w, h,
                    this.selected ? AppTheme.alpha(AppTheme.NEON_DARK, 90) : AppTheme.alpha(AppTheme.BG_1, 150));
            canvas.stroke(this.left, this.top, w, h,
                    this.selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, this.selected ? 2f : 1f);
            if (this.selected) canvas.glow(this.left, this.top, w, h, 0f, AppTheme.NEON, 0.20f);
            final int tagW = PlayerScreen.this.typeTagWidth(this.source.type());
            PlayerScreen.this.drawTypeTag(canvas, this.left + w - tagW - 12, this.top + Math.max(0, (h - 22) / 2), this.source.type());
            final String title = PlayerScreen.this.text.truncateToWidth(
                    PlayerScreen.this.sourceTitle(this.source, this.index).toUpperCase(),
                    w - tagW - 68, AppTheme.TEXT_BODY, Font.BOLD);
            canvas.text(title, this.left + 34, this.top + 10,
                    this.selected ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, true);
            final int count = PlayerScreen.this.ctx.availableSources != null ? PlayerScreen.this.ctx.availableSources.length : 1;
            canvas.text("SOURCE " + (this.index + 1) + "/" + count + " - " + this.source.availableQualities().size() + " QUALITIES",
                    this.left + 34, this.top + 32, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
            super.onDraw(canvas);
        }
    }

    // QUALITY ROW: SELECTION PIP + BOLD NAME + THRESHOLD VALUE
    private final class QualityRow extends Element<QualityRow> {

        private final MediaQuality quality;

        QualityRow(final MediaQuality quality) {
            this.quality = quality;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(this.left, this.top, w, h,
                    this.selected ? AppTheme.alpha(AppTheme.NEON_DARK, 88) : AppTheme.alpha(AppTheme.BG_1, 148));
            canvas.stroke(this.left, this.top, w, h,
                    this.selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, 1f);
            canvas.fill(this.left + 8, this.top + Math.max(0, (h - 8) / 2), 8, 8,
                    this.selected ? AppTheme.AMBER : AppTheme.TEXT_FAINT);
            canvas.text(this.quality.name().toUpperCase(), this.left + 28,
                    this.top + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, true)) / 2),
                    this.selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, true);
            canvas.text(this.quality.threshold + "p", this.left + w - 62,
                    this.top + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                    this.selected ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
        }
    }

    // LOD ROW FOR THE VIDEO DIALOG: SAME SKIN AS QUALITY ROWS PLUS A HOVER TINT AND RIGHT-ALIGNED DETAIL
    private final class LodRow extends Element<LodRow> {

        private final int index;
        private final String label;
        private final String detail;

        LodRow(final int index, final String label, final String detail) {
            this.index = index;
            this.label = label;
            this.detail = detail;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean sel = this.index == PlayerScreen.this.lodSelectedIndex;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(this.left, this.top, w, h,
                    sel ? AppTheme.alpha(AppTheme.NEON_DARK, 88)
                            : this.hovered ? AppTheme.alpha(AppTheme.NEON_DARK, 44) : AppTheme.alpha(AppTheme.BG_1, 148));
            canvas.stroke(this.left, this.top, w, h,
                    sel ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, sel ? 2f : 1f);
            canvas.fill(this.left + 8, this.top + Math.max(0, (h - 8) / 2), 8, 8, sel ? AppTheme.AMBER : AppTheme.TEXT_FAINT);
            canvas.text(this.label, this.left + 28,
                    this.top + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, true)) / 2),
                    sel ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, true);
            canvas.text(this.detail, this.left + w - canvas.textWidth(this.detail, AppTheme.TEXT_BODY, false) - 12,
                    this.top + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                    sel ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
        }
    }

    // METRICS/METADATA PANEL (DEBUG) — DRAW-ONLY LEAF PORTING THE LEGACY renderMetric/renderWrappedMetric/
    // renderPanelHead/renderDescriptionBox STACK ONTO THE CANVAS, READING THE LIVE PLAYER EVERY FRAME
    private final class MetricsPanel extends Element<MetricsPanel> {

        // WHEEL OFFSET (LOGICAL PX) AND THE PREVIOUS FRAME'S MEASURED OVERFLOW THAT CLAMPS IT; labelColW IS THE
        // FIXED LABEL-COLUMN WIDTH (WIDEST LABEL + GAP), COMPUTED ONCE SO EVERY VALUE COLUMN LINES UP
        private int scroll;
        private int maxScroll;
        private int labelColW;

        @Override
        public boolean dispatchScroll(final double mx, final double my, final double amount) {
            if (!this.visible || this.maxScroll <= 0 || !this.contains(mx, my)) return false;
            final int next = Math.max(0, Math.min(this.maxScroll, this.scroll - (int) (amount * METRIC_SCROLL_STEP)));
            if (next != this.scroll) {
                this.scroll = next;
                this.invalidate();
            }
            return true;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MediaPlayer player = PlayerScreen.this.ctx.player;
            if (player == null) return;
            final int px = this.left;
            final int py = this.top;
            final int pw = this.measuredWidth;
            final int ph = this.measuredHeight;
            // PANEL FRAME — DRAWN UNCLIPPED SO THE BORDER/GLOW STAY CRISP
            canvas.stroke(px, py, pw, ph, AppTheme.NEON, 2f);
            canvas.glow(px, py, pw, ph, 0f, AppTheme.NEON, 0.20f);
            canvas.fill(px, py, pw, ph, AppTheme.alpha(AppTheme.BG_1, 209));

            // CLAMP THE WHEEL OFFSET TO LAST FRAME'S OVERFLOW, THEN CLIP EVERY METRIC TO THE PANEL RECT SO A
            // LONG METADATA BLOCK CANNOT SPILL OVER THE SEEK BAR; CONTENT IS DRAWN SHIFTED UP BY scroll
            this.scroll = Math.max(0, Math.min(this.scroll, this.maxScroll));
            canvas.pushClip(px, py, pw, ph);

            final AppContext ctx = PlayerScreen.this.ctx;
            final Metadata meta = ctx.selectedSource != null ? ctx.selectedSource.metadata() : null;
            final int x = px + 14;
            final int top0 = py + 14 - this.scroll;
            int y = top0;

            y = this.head(canvas, "ENGINE", x, y);
            y = this.metric(canvas, "Engine", player.getClass().getSimpleName(), x, y, AppTheme.NEON_LIGHT);
            if (ctx.selectedMRL != null) {
                y = this.wrappedMetric(canvas, "MRL", ctx.selectedMRL.uri.toString(), x, y, AppTheme.TEXT_SOFT, 2);
            }
            y = this.metric(canvas, "Source", (ctx.sourceSelectorIndex + 1) + "/" +
                    (ctx.availableSources != null ? ctx.availableSources.length : 1), x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "FPS", String.format("%.2f", player.fps()), x, y, AppTheme.GREEN);
            y = this.metric(canvas, "Status", player.status().name(), x, y, AppTheme.TEXT_SOFT);

            final long duration = player.duration();
            final String timeValue = duration <= 0
                    ? ctx.formatTime(player.time())
                    : ctx.formatTime(player.time()) + " / " + ctx.formatTime(duration);
            y = this.metric(canvas, "Time", timeValue, x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "Volume", player.volume() + "%", x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "Quality", player.quality().name() + " - " + PlayerScreen.this.maxSizeLabel(), x, y, AppTheme.CYAN);
            y = this.metric(canvas, "Dimensions", PlayerScreen.this.playerResolution(player), x, y, AppTheme.CYAN);
            y = this.metric(canvas, "Speed", String.format("%.2f", player.speed()) + "x", x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "Live", player.liveSource() ? "Yes" : "No", x, y, AppTheme.TEXT_SOFT);

            y += 12;
            y = this.head(canvas, "METADATA", x, y);

            if (meta != null) {
                y = this.wrappedMetric(canvas, "Title", meta.title(), x, y, AppTheme.TEXT_SOFT, 2);
                y = this.metric(canvas, "Author", meta.author(), x, y, AppTheme.TEXT_SOFT);
                if (meta.postedAt() != null) {
                    y = this.metric(canvas, "Published", PlayerScreen.this.formatDate(meta), x, y, AppTheme.TEXT_SOFT);
                }
                y = this.descriptionBox(canvas, meta, x, y + 8, pw - 28);
            } else {
                canvas.text("Unavailable", x, y, AppTheme.TEXT_FAINT, META_SCALE, false);
                y += PlayerScreen.this.text.lineHeight(META_SCALE);
            }
            canvas.popClip();

            // DEFERRED OVERFLOW MEASURE FOR THE NEXT FRAME'S CLAMP, PLUS A LIVE SCROLL THUMB WHEN CONTENT OVERFLOWS
            final int contentH = y - top0;
            final int viewH = ph - 28;
            this.maxScroll = Math.max(0, contentH - viewH);
            if (this.maxScroll > 0) {
                final int trackH = ph - 32;
                final int thumbH = Math.max(26, (int) ((long) trackH * viewH / contentH));
                final int thumbY = py + 16 + (int) ((long) (trackH - thumbH) * this.scroll / this.maxScroll);
                canvas.fill(px + pw - 18, py + 16, 3, trackH, AppTheme.alpha(AppTheme.BG_3, 140));
                canvas.fill(px + pw - 18, thumbY, 3, thumbH, AppTheme.alpha(AppTheme.NEON, 160));
                canvas.glow(px + pw - 18, thumbY, 3, thumbH, 0f, AppTheme.NEON, 0.35f);
            }
        }

        private int metric(final Canvas canvas, final String label, final String value, final int x, final int y, final Color valueColor) {
            final TextRenderer text = PlayerScreen.this.text;
            final int valueX = this.valueX(x);
            final int maxValueW = Math.max(80, this.left + this.measuredWidth - valueX - 14);
            canvas.text(label + ":", x, y, AppTheme.TEXT_FAINT, META_SCALE, false);
            if (value != null) {
                canvas.text(text.truncateToWidth(value, maxValueW, META_SCALE), valueX, y, valueColor, META_SCALE, false);
            }
            return y + text.lineHeight(META_SCALE) + 4;
        }

        private int wrappedMetric(final Canvas canvas, final String label, final String value, final int x, final int y,
                                  final Color valueColor, final int maxLines) {
            final TextRenderer text = PlayerScreen.this.text;
            final int valueX = this.valueX(x);
            final int maxPixelW = Math.max(80, this.left + this.measuredWidth - valueX - 14);
            final List<String> lines = this.wrap(value, maxPixelW, META_SCALE, maxLines);
            canvas.text(label + ":", x, y, AppTheme.TEXT_FAINT, META_SCALE, false);
            final int lineH = text.lineHeight(META_SCALE) + 2;
            for (int i = 0; i < lines.size(); i++) {
                canvas.text(lines.get(i), valueX, y + i * lineH, valueColor, META_SCALE, false);
            }
            return y + Math.max(text.lineHeight(META_SCALE), lines.size() * lineH) + 4;
        }

        private int head(final Canvas canvas, final String label, final int x, final int y) {
            canvas.text("| " + label, x, y, AppTheme.NEON_LIGHT, META_HEAD_SCALE, true);
            final int lineY = y + canvas.textHeight(META_HEAD_SCALE, true) / 2;
            for (int dx = x + 118; dx < this.left + this.measuredWidth - 14; dx += 8) {
                canvas.fill(dx, lineY, 4, 1, AppTheme.STROKE_BRIGHT);
            }
            return y + PlayerScreen.this.text.lineHeight(META_HEAD_SCALE) + 6;
        }

        private int descriptionBox(final Canvas canvas, final Metadata meta, final int x, final int y, final int w) {
            final TextRenderer text = PlayerScreen.this.text;
            final String desc = meta != null && meta.desc() != null && !meta.desc().isBlank()
                    ? meta.desc()
                    : "No description available.";
            final List<String> lines = this.wrap(desc, w - 20, META_DESC_SCALE, 3);
            final int labelH = text.lineHeight(META_DESC_LABEL_SCALE);
            final int lineH = text.lineHeight(META_DESC_SCALE) + 2;
            final int h = 22 + labelH + lines.size() * lineH + 14;
            canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 188));
            // DRAWS THE DOTTED BORDER WITHOUT EXTRA GEOMETRY STATE.
            for (int dx = x; dx < x + w; dx += 8) {
                canvas.fill(dx, y, Math.min(4, x + w - dx), 1, AppTheme.STROKE_BRIGHT);
                canvas.fill(dx, y + h - 1, Math.min(4, x + w - dx), 1, AppTheme.STROKE_BRIGHT);
            }
            for (int dy = y; dy < y + h; dy += 8) {
                canvas.fill(x, dy, 1, Math.min(4, y + h - dy), AppTheme.STROKE_BRIGHT);
                canvas.fill(x + w - 1, dy, 1, Math.min(4, y + h - dy), AppTheme.STROKE_BRIGHT);
            }
            canvas.text("DESCRIPTION", x + 10, y + 10, AppTheme.TEXT_FAINT, META_DESC_LABEL_SCALE, true);
            for (int i = 0; i < lines.size(); i++) {
                canvas.text(lines.get(i), x + 10, y + 20 + labelH + i * lineH, AppTheme.TEXT_SOFT, META_DESC_SCALE, false);
            }
            return y + h + 10;
        }

        // VALUE COLUMN X: A FIXED OFFSET SIZED TO THE WIDEST METRIC LABEL (+ GAP), COMPUTED ONCE. THIS KEEPS
        // EVERY VALUE ALIGNED (THE MONOSPACED LOOK) WHILE GUARANTEEING NO LABEL EVER OVERLAPS ITS VALUE
        private int valueX(final int x) {
            if (this.labelColW == 0) {
                final TextRenderer text = PlayerScreen.this.text;
                int max = 0;
                for (final String label: METRIC_LABELS) max = Math.max(max, text.width(label + ":", META_SCALE));
                this.labelColW = max + 14;
            }
            return x + this.labelColW;
        }

        private List<String> wrap(final String value, final int maxPixelWidth, final float scale, final int maxLines) {
            final List<String> lines = new ArrayList<>();
            String remaining = value == null ? "" : value.trim();
            while (!remaining.isEmpty() && lines.size() < maxLines) {
                final int fit = this.fitPrefix(remaining, maxPixelWidth, scale);
                if (fit >= remaining.length()) {
                    lines.add(remaining);
                    break;
                }
                final int preferred = this.wrapBreak(remaining, fit);
                lines.add(remaining.substring(0, preferred).trim());
                remaining = remaining.substring(Math.min(preferred, remaining.length())).trim();
            }
            if (lines.isEmpty()) lines.add("Unavailable.");
            return lines;
        }

        private int fitPrefix(final String value, final int maxPixelWidth, final float scale) {
            // width() IS MONOTONIC NON-DECREASING IN PREFIX LENGTH (PER-GLYPH ADVANCE >= 1, TRACKING >= 0), SO
            // BINARY-SEARCH THE LONGEST FITTING PREFIX INSTEAD OF RE-MEASURING EVERY GROWING SUBSTRING (WAS
            // O(n^2) EACH FRAME). RESULT IS PIXEL-IDENTICAL TO THE LINEAR SCAN; ALWAYS CONSUME >= 1 CHAR SO wrap() CANNOT STALL.
            final TextRenderer text = PlayerScreen.this.text;
            int lo = 1, hi = value.length(), lastFit = 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (text.width(value.substring(0, mid), scale) > maxPixelWidth) {
                    hi = mid - 1;
                } else {
                    lastFit = mid;
                    lo = mid + 1;
                }
            }
            return lastFit;
        }

        private int wrapBreak(final String value, final int fit) {
            final int limit = Math.max(1, Math.min(fit, value.length()));
            int best = -1;
            // PREFER VISUALLY STABLE BREAKS FOR LONG URLS AND TITLES.
            for (int i = limit - 1; i > Math.max(0, limit - 18); i--) {
                final char c = value.charAt(i);
                if (Character.isWhitespace(c) || c == '/' || c == '?' || c == '&' || c == '-' || c == '_') {
                    best = i + 1;
                    break;
                }
            }
            return best > 4 ? best : limit;
        }
    }

    // THE HUD LAYER: TOP INFO ROW, METRICS PANEL AND TRANSPORT BAR AT THE LEGACY PIXEL POSITIONS.
    // CHROME (FADES, BAR PANELS, DIVIDERS, STATIC TEXTS) IS PAINTED IN onDraw; EVERY INTERACTIVE PIECE
    // IS A REAL CHILD ELEMENT PLACED BY AN ABSOLUTE onLayout PORTED FROM THE LEGACY MATH.
    private final class Hud extends Group<Hud> {

        private final Button back;
        private final Icon srcIcon;
        private final MetricsPanel metrics = new MetricsPanel();
        private final Button dbg;
        private final ChipButton qual;
        private final ChipButton lod;
        private final IconButton prev;
        private final IconButton rew;
        private final IconButton stopBtn;
        private final IconButton play;
        private final IconButton fwd;
        private final IconButton next;
        private final Icon spk;
        private final SeekBar seek;
        private final SeekBar vol;
        private final Button loop;
        private final Dropdown speedDrop;

        // FRAME STATE (REBUILT EVERY UPDATE/MEASURE/LAYOUT PASS)
        private boolean errorMode = true;
        private boolean multiSource;
        private int transportYL;   // LOCAL Y OF THE TRANSPORT BAR TOP
        private int panelW;
        private int panelH;
        private int barW;
        private String leftTime = "";
        private String rightTime = "--:--";
        // ABSOLUTE GEOMETRY FOR onDraw
        private int topYA;
        private int transportYA;
        private int controlsYA;
        private int dividerXA;
        private int volDividerXA;
        private int volPctXA;
        private String titleStr = "";
        private String authorStr = "";
        private String srcLabel = "";
        private boolean showTypeTag;
        private int typeTagXA;
        private MediaType tagType;
        private int srcTagXA;
        private int srcTagW;

        Hud() {
            this.size(MAX_PARENT, MAX_PARENT);
            // WRAP THE WIDTH SO "BACK" (WITH ITS ARROW) IS NEVER TRUNCATED TO DOTS
            this.back = new Button("BACK").icon("arrow-left")
                    .accent(AppTheme.TEXT_SOFT).textColor(AppTheme.TEXT_SOFT).width(WRAP_CONTENT).height(34)
                    .onClick(b -> PlayerScreen.this.returnToMenu());
            this.srcIcon = new Icon("folder").iconSize(12).color(AppTheme.NEON_LIGHT);
            this.dbg = new Button("DEBUG").icon("debug")
                    .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT).size(112, 30)
                    .onClick(b -> {
                        PlayerScreen.this.debugOpen = !PlayerScreen.this.debugOpen;
                        PlayerScreen.this.ctx.playSelectionSound();
                    });
            // WRAP TO CONTENT: THE QUALITY/LOD LABELS AND THEIR RESOLUTION CHIPS VARY, SO A FIXED WIDTH CLIPPED THEM
            this.qual = new ChipButton("folder", AppTheme.CYAN);
            this.qual.width(WRAP_CONTENT).height(30).onClick(v -> {
                PlayerScreen.this.openQualityDialog();
                PlayerScreen.this.ctx.playSelectionSound();
            });
            this.lod = new ChipButton("tv", AppTheme.AMBER);
            this.lod.width(WRAP_CONTENT).height(30).onClick(v -> {
                PlayerScreen.this.openVideoDialog();
                PlayerScreen.this.ctx.playSelectionSound();
            });
            this.prev = this.key("prev", AppTheme.TEXT_SOFT, 42, p -> PlayerScreen.this.navigateSource(-1));
            this.rew = this.key("rewind", AppTheme.TEXT_SOFT, 46, MediaPlayer::rewind);
            this.stopBtn = this.key("stop", AppTheme.RED, 42, MediaPlayer::stop);
            this.play = this.key("play", AppTheme.GREEN, 78, PlayerScreen.this::togglePlayback);
            this.fwd = this.key("forward", AppTheme.TEXT_SOFT, 46, MediaPlayer::forward);
            this.next = this.key("next", AppTheme.TEXT_SOFT, 42, p -> PlayerScreen.this.navigateSource(1));
            this.spk = new Icon("speaker").iconSize(14).color(AppTheme.TEXT_SOFT);
            this.seek = new SeekBar().trackHeight(6).onChange(v -> {
                final MediaPlayer p = PlayerScreen.this.ctx.player;
                if (p != null && !p.error() && p.duration() > 0) p.seek((long) (p.duration() * v));
            });
            this.vol = new SeekBar().trackHeight(5);
            this.vol.size(130, 20).onChange(v -> {
                final MediaPlayer p = PlayerScreen.this.ctx.player;
                if (p != null && !p.error()) p.volume(Math.round(v * 100f));
            });
            this.loop = new Button("ON").icon("repeat").size(78, 30)
                    .onClick(b -> {
                        final MediaPlayer p = PlayerScreen.this.ctx.player;
                        if (p == null || p.error()) return;
                        PlayerScreen.this.toggleLoop(p);
                        PlayerScreen.this.ctx.playSelectionSound();
                    });
            // SPEED PRESETS AS A FLOATING MENU ANCHORED TO THE CONTROL (FLIPS ABOVE WHEN PINNED TO THE BOTTOM
            // EDGE — THE FRAMEWORK HANDLES THAT). SCOPED TO THE SCREEN OVERLAY SO IT PAINTS ON TOP OF THE HUD
            this.speedDrop = new Dropdown().mode(Dropdown.Mode.ATTACH).accent(AppTheme.NEON)
                    .items(Arrays.asList(SPEED_LABELS)).selected(3)
                    .overlayHost(PlayerScreen.this.overlay())
                    .onSelect(i -> {
                        final MediaPlayer p = PlayerScreen.this.ctx.player;
                        if (p != null && !p.error() && p.speed(SPEED_VALUES[i])) {
                            PlayerScreen.this.speed = SPEED_VALUES[i];
                            PlayerScreen.this.ctx.playSelectionSound();
                        }
                    });
            this.speedDrop.size(90, 30);
            this.add(this.metrics).add(this.srcIcon).add(this.spk)
                    .add(this.seek).add(this.vol)
                    .add(this.dbg).add(this.qual).add(this.lod)
                    .add(this.prev).add(this.rew).add(this.stopBtn).add(this.play).add(this.fwd).add(this.next)
                    .add(this.loop).add(this.speedDrop)
                    .add(this.back);
        }

        // ICON-ONLY TRANSPORT KEY WITH THE SHARED GUARD: NO-OP WITHOUT A HEALTHY PLAYER, CHIME ON HIT
        private IconButton key(final String icon, final Color color, final int w, final Consumer<MediaPlayer> action) {
            final IconButton button = new IconButton(icon).accent(color).iconColor(color);
            button.size(w, 30).onClick(b -> {
                final MediaPlayer p = PlayerScreen.this.ctx.player;
                if (p == null || p.error()) return;
                action.accept(p);
                PlayerScreen.this.ctx.playSelectionSound();
            });
            return button;
        }

        @Override
        protected void onUpdate() {
            final MediaPlayer p = PlayerScreen.this.ctx.player;
            this.errorMode = p == null || p.error();
            this.multiSource = PlayerScreen.this.ctx.availableSources != null && PlayerScreen.this.ctx.availableSources.length > 1;
            final boolean alive = !this.errorMode;
            this.metrics.visible(alive && PlayerScreen.this.debugOpen);
            this.srcIcon.visible(alive);
            this.spk.visible(alive);
            this.seek.visible(alive);
            this.vol.visible(alive);
            this.dbg.visible(alive);
            this.qual.visible(alive);
            this.lod.visible(alive);
            this.prev.visible(alive && this.multiSource);
            this.rew.visible(alive);
            this.stopBtn.visible(alive);
            this.play.visible(alive);
            this.fwd.visible(alive);
            this.next.visible(alive && this.multiSource);
            this.loop.visible(alive);
            this.speedDrop.visible(alive);
            if (!alive) return;

            final long duration = Math.max(0, p.duration());
            this.seek.enabled(duration > 0);
            this.seek.value(duration > 0 ? (float) p.time() / duration : 0f);
            this.vol.value(Math.max(0, Math.min(100, p.volume())) / 100f);
            this.spk.color(this.vol.hovered() ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT);
            final boolean playing = p.playing();
            this.play.icon(playing ? "pause" : "play")
                    .accent(playing ? AppTheme.AMBER : AppTheme.GREEN)
                    .iconColor(playing ? AppTheme.AMBER : AppTheme.GREEN);
            final boolean loopOn = PlayerScreen.this.loopEnabled;
            this.loop.label(loopOn ? "ON" : "OFF")
                    .accent(loopOn ? AppTheme.GREEN : AppTheme.TEXT_FAINT)
                    .textColor(loopOn ? AppTheme.GREEN : AppTheme.TEXT_FAINT);
            final String maxSize = PlayerScreen.this.maxSizeLabel();
            this.qual.label(p.quality().name()).chip(maxSize);
            this.lod.label(PlayerScreen.this.lodLabel()).chip(maxSize);
            // KEEP THE DROPDOWN SELECTION MIRRORING THE LIVE PLAYER SPEED (SNAPPED TO THE NEAREST PRESET);
            // LOCK IT WHEN THE PLAYER CANNOT CHANGE SPEED (LIVE STREAM, OR AN ENGINE STUCK AT 1.0×)
            this.speedDrop.enabled(p.canSpeed());
            this.speedDrop.selected(PlayerScreen.this.nearestSpeedIndex((float) p.speed()));
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // LOCAL GEOMETRY (WINDOW X == LOCAL X; Y IS OFFSET BY this.top AT LAYOUT). LEGACY:
            // transportY = max(TITLEBAR_H + 120, windowH - FOOTER_H - 92) → LOCAL max(120, H - 92)
            this.transportYL = Math.max(120, innerAvailHeight - 92);
            this.panelW = Math.min(405, Math.max(240, innerAvailWidth / 4));
            this.panelH = Math.max(220, this.transportYL - 80 - 14);
            this.metrics.size(this.panelW, this.panelH);

            final MediaPlayer p = PlayerScreen.this.ctx.player;
            final long duration = p == null ? 0 : Math.max(0, p.duration());
            this.leftTime = p == null ? "" : PlayerScreen.this.ctx.formatTime(p.time());
            this.rightTime = duration > 0 ? PlayerScreen.this.ctx.formatTime(duration) : "--:--";
            final int rightTimeW = PlayerScreen.this.text.width(this.rightTime, AppTheme.TEXT_BODY);
            this.barW = Math.max(120, innerAvailWidth - 112 - rightTimeW - 44);
            this.seek.size(this.barW, 20);

            for (final Element<?> child: this.children) {
                if (child.visible()) child.measure(innerAvailWidth, innerAvailHeight);
            }
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int x0 = this.innerLeft();
            final int y0 = this.innerTop();
            final int w = this.innerWidth();
            this.topYA = y0 + 24;
            // BACK KEEPS ITS CONSTANT SPOT IN EVERY STATE — ON AN IMMEDIATE ERROR IT IS STILL CLICKABLE (M-13)
            this.back.layout(x0 + 16, this.topYA);
            if (this.errorMode) return;

            this.transportYA = y0 + this.transportYL;
            this.controlsYA = this.transportYA + 42;
            this.metrics.layout(x0 + 14, y0 + 80);
            this.seek.layout(x0 + 112, this.transportYA + 4);

            // LEFT CLUSTER: DEBUG / QUALITY / LOD + DIVIDER — ADVANCE BY EACH CONTROL'S MEASURED WIDTH (8PX GAP)
            // SO THE WRAP-TO-CONTENT QUALITY/LOD BUTTONS PACK TIGHTLY WITHOUT GAPS OR CLIPPING
            final int rowY = this.controlsYA - 5;
            int lx = x0 + 28;
            this.dbg.layout(lx, rowY);
            lx += this.dbg.measuredWidth() + 8;
            this.qual.layout(lx, rowY);
            lx += this.qual.measuredWidth() + 8;
            this.lod.layout(lx, rowY);
            this.dividerXA = lx + this.lod.measuredWidth() + 14;

            // CENTER CLUSTER: WINDOW-CENTERED TRANSPORT KEYS, PUSHED RIGHT OF THE DIVIDER WHEN CRAMPED
            final int controlsW = 244 + (this.multiSource ? 100 : 0);
            int cx = Math.max(this.dividerXA + 20, x0 + (w - controlsW) / 2);
            if (this.multiSource) {
                this.prev.layout(cx, rowY);
                cx += 50;
            }
            this.rew.layout(cx, rowY);
            cx += 54;
            this.stopBtn.layout(cx, rowY);
            cx += 50;
            this.play.layout(cx, rowY);
            cx += 86;
            this.fwd.layout(cx, rowY);
            cx += 54;
            if (this.multiSource) this.next.layout(cx, rowY);

            // RIGHT CLUSTER: VOLUME + DIVIDER + LOOP + SPEED (RIGHT GROUP WIDTH 413, ANCHORED RIGHT)
            int rx = Math.max(cx + 78, x0 + w - 28 - 413);
            this.spk.layout(rx + 8, rowY + 8);
            this.vol.layout(rx + 30, this.controlsYA);
            this.volPctXA = rx + 170;
            rx += 222;
            this.volDividerXA = rx;
            rx += 14;
            this.loop.layout(rx, rowY);
            rx += 86;
            this.speedDrop.layout(rx, rowY);

            // TOP INFO ROW STRINGS AND TAG POSITIONS (TRUNCATION IS WINDOW-WIDTH DEPENDENT)
            final TextRenderer text = PlayerScreen.this.text;
            final AppContext ctx = PlayerScreen.this.ctx;
            final MediaPlayer p = ctx.player;
            final Metadata meta = ctx.selectedSource != null ? ctx.selectedSource.metadata() : null;
            final String title = meta != null && meta.title() != null ? meta.title() : ctx.selectedMRLName;
            final String author = meta != null && meta.author() != null ? meta.author() : p.getClass().getSimpleName();
            final String host = ctx.selectedMRL != null && ctx.selectedMRL.uri.getHost() != null ? ctx.selectedMRL.uri.getHost() : "local";
            final String posted = meta != null && meta.postedAt() != null ? PlayerScreen.this.formatDate(meta) : "unknown date";
            final int sources = ctx.availableSources != null ? ctx.availableSources.length : 1;
            this.titleStr = text.truncateToWidth(title, Math.max(180, w - 520), AppTheme.TEXT_SECTION, Font.BOLD);
            int tagX = x0 + 108 + text.widthBold(this.titleStr, AppTheme.TEXT_SECTION) + 12;
            this.tagType = PlayerScreen.this.currentMediaType();
            this.showTypeTag = this.tagType != null && tagX < x0 + w - 180;
            if (this.showTypeTag) {
                this.typeTagXA = tagX;
                tagX += PlayerScreen.this.typeTagWidth(this.tagType) + 8;
            }
            this.srcLabel = (ctx.sourceSelectorIndex + 1) + "/" + sources;
            this.srcTagW = text.width(this.srcLabel, AppTheme.TEXT_BODY) + 46;
            final boolean showSrc = tagX + this.srcTagW < x0 + w - 20;
            this.srcIcon.visible(showSrc);
            if (showSrc) {
                this.srcTagXA = tagX;
                this.srcIcon.layout(tagX + 9, this.topYA - 6 + 6);
            }
            this.authorStr = text.truncateToWidth(author + " - " + host + " - " + posted, Math.max(180, w - 520), AppTheme.TEXT_BODY);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            if (!this.errorMode) {
                final int winW = canvas.windowWidth();
                // FADES CONSTRAINED TO THE HUD'S OWN LAID-OUT BOX (THE CENTER SLOT) — NEVER WINDOW
                // COORDINATES, OR THEY WOULD PAINT OVER THE SHELL'S TITLEBAR/KEYBINDS BAR
                final int hudY = this.top;
                final int hudH = this.measuredHeight;
                canvas.gradientH(this.left, hudY, 380, hudH, FADE_DARK, FADE_CLEAR);
                canvas.gradientV(this.left, hudY + hudH - 120, winW, 120, FADE_CLEAR, FADE_DARK);
                canvas.gradientV(this.left, hudY, winW, 96, BAND_DARK, BAND_CLEAR);

                // TRANSPORT BAR CHROME
                canvas.fill(14, this.transportYA, winW - 28, 76, AppTheme.alpha(AppTheme.BG_1, 224));
                canvas.stroke(14, this.transportYA, winW - 28, 76, AppTheme.NEON, 2f);
                canvas.glow(14, this.transportYA, winW - 28, 76, 0f, AppTheme.NEON, 0.16f);
                canvas.fill(28, this.transportYA + 74, 10, 3, AppTheme.AMBER);
                canvas.fill(winW - 38, this.transportYA + 74, 10, 3, AppTheme.AMBER);
                canvas.stroke(this.left, hudY, winW, hudH, AppTheme.STROKE_BRIGHT, 1f);

                // TOP INFO ROW
                canvas.text(this.titleStr, 108, this.topYA - 3, AppTheme.TEXT, AppTheme.TEXT_SECTION, true);
                if (this.showTypeTag) {
                    PlayerScreen.this.drawTypeTag(canvas, this.typeTagXA, this.topYA - 5, this.tagType);
                }
                if (this.srcIcon.visible()) {
                    canvas.fill(this.srcTagXA, this.topYA - 6, this.srcTagW, 24, AppTheme.alpha(AppTheme.BG_1, 188));
                    canvas.stroke(this.srcTagXA, this.topYA - 6, this.srcTagW, 24, AppTheme.NEON_LIGHT, 1f);
                    canvas.text(this.srcLabel, this.srcTagXA + 28,
                            this.topYA - 6 + Math.max(0, (24 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2) + 1,
                            AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY, false);
                }
                canvas.text(this.authorStr, 108, this.topYA + 24, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);

                // TIME LABELS, VOLUME PERCENT AND VERTICAL DIVIDERS
                final int timeY = this.transportYA + Math.max(0, (28 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2);
                canvas.text(this.leftTime, 26, timeY, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY, false);
                canvas.text(this.rightTime, 112 + this.barW + 18, timeY, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
                canvas.fill(this.dividerXA, this.controlsYA - 6, 1, 32, AppTheme.STROKE_BRIGHT);
                canvas.fill(this.volDividerXA, this.controlsYA - 6, 1, 32, AppTheme.STROKE_BRIGHT);
                final MediaPlayer p = PlayerScreen.this.ctx.player;
                if (p != null) {
                    final int volume = Math.max(0, Math.min(100, p.volume()));
                    canvas.text(volume + "%", this.volPctXA,
                            this.controlsYA - 5 + Math.max(0, (30 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                            AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
                }
            }
            super.onDraw(canvas);
        }

        @Override
        public Element<?> dispatchPress(final double mx, final double my) {
            final Element<?> hit = super.dispatchPress(mx, my);
            // THE PRESS THAT CAPTURES A BAR CHIMES ONCE (MATCHING THE LEGACY CLICK); DRAG MOVES STAY SILENT
            if (hit == this.seek || hit == this.vol) PlayerScreen.this.ctx.playSelectionSound();
            return hit;
        }
    }
}
