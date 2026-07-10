package org.watermedia.bootstrap.app;

import com.google.gson.Gson;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceStop;

/**
 * Shared application context and state.
 */
public final class AppContext implements Executor {

    public static final String APP_NAME = "WATERMeDIA: Multimedia API";
    public static final Gson GSON = new Gson();
    public static final DateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");
    public static final boolean IN_MODS = new File("").getAbsoluteFile().getName().equalsIgnoreCase("mods");

    // KNOWN MODS THAT BUILD ON WATERMEDIA — CANDIDATES FOR THE STAGE-3 "SUSPECTED MODS" LIST. THE SIMPLE
    // NAME IS THE MOD ID; A CANDIDATE IS ONLY SHOWN WHEN A JAR WHOSE FILENAME CONTAINS THAT ID IS PRESENT.
    public static final List<SuspectMod> SUSPECT_MODS = List.of(
            new SuspectMod("fancymenu", "FancyMenu", "Keksuccino/FancyMenu", "https://github.com/Keksuccino/FancyMenu/issues/new"),
            new SuspectMod("waterframes", "WaterFrames", "SrRapero720/waterframes", "https://github.com/SrRapero720/waterframes/issues/new"),
            new SuspectMod("watervision", "WaterVision", "SrRapero720/watervision", "https://github.com/SrRapero720/watervision/issues/new"),
            new SuspectMod("littleframes", "LittleFrames", "CreativeMD/littleframes", "https://github.com/CreativeMD/littleframes/issues/new")
    );

    public static final int PADDING = 20;
    public static final int MENU_WIDTH = 500;

    static {
        TIME_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT-00:00"));
    }

    // WINDOW STATE
    public int windowWidth = 1280;
    public int windowHeight = 720;
    public long windowHandle;
    public boolean windowMaximized;
    private volatile boolean renderRequested = true;
    // GLOBAL UI SCALE (PHYSICAL PX PER LOGICAL PX)
    public volatile float uiScale = 1f;

    /** Window width in LOGICAL pixels — the coordinate space the whole element tree lives in. */
    public int logicalWidth() {
        return Math.round(this.windowWidth / this.uiScale);
    }

    /** Window height in LOGICAL pixels — the coordinate space the whole element tree lives in. */
    public int logicalHeight() {
        return Math.round(this.windowHeight / this.uiScale);
    }

    // GLOBAL CRT OVERLAY TOGGLE (SCANLINES + NEON BANDS) — DRIVEN BY THE 'C' SHORTCUT AND THE SETTINGS ROW
    public volatile boolean crt = true;

    // MOUSE
    public double mouseX;
    public double mouseY;
    // RAW PHYSICAL CURSOR COORDS (TITLEBAR DRAG USES THESE); mouseX/mouseY BECOME LOGICAL IN A FUTURE WAVE
    public volatile double mouseRawX;
    public volatile double mouseRawY;
    public boolean mouseDown;
    public boolean mousePressed;
    public boolean mouseClicked;
    public boolean ctrlDown;
    public boolean selectionSoundEnabled = true;

    // TEXT RENDERER
    public TextRenderer text;

    // EXECUTOR FOR GL THREAD TASKS
    public final Queue<Runnable> executor = new ConcurrentLinkedQueue<>();

    // MRL DATA
    public final LinkedHashMap<String, MRL> groupMRLs = new LinkedHashMap<>();
    public final List<TestURI> customTests = new ArrayList<>();
    public URIGroup[] uriGroups;
    public IptvChannel[] iptvChannels = new IptvChannel[0];
    public String selectedIptvRegion = "";

    // SELECTION STATE
    public URIGroup selectedGroup;
    public MRL selectedMRL;
    public String selectedMRLName = "";
    public MRL.Source[] availableSources;
    public MRL.Source selectedSource;
    public MediaQuality[] availableQualities;
    public MediaQuality selectedQuality = MediaQuality.HIGHER;
    public int sourceSelectorIndex;

    // PLAYER
    public MediaPlayer player;
    private volatile Thread releaseThread;

    // BANNER, ICON AND DUCK GPU TEXTURE HANDLES
    public final Assets assets = new Assets();
    public boolean backendsLoading = true;

    // AUDIO
    public int soundBuffer = -1;
    public int soundSource = -1;
    public boolean audioReady;
    public boolean audioError;
    // AUDIO BACKEND CHOSEN FOR THE NEXT CREATED PLAYER (SETTINGS SCREEN) — NOT A FALLBACK
    public AudioEngine audioEngine = AudioEngine.OPENAL;

    public String customUrlText = "";

    // UPLOAD LOGS DIALOG STATE
    public final UploadState upload = new UploadState();

    // CLEANUP CACHE DIALOG STATE
    public final CleanupState cleanup = new CleanupState();

    // GLOBAL ERROR DIALOG
    public final ErrorState error = new ErrorState();

    // GLOBAL EXIT-CONFIRM DIALOG (TITLEBAR CLOSE / EXIT ACTION) — RENDERED BY THE WINDOW SHELL
    public volatile boolean exitConfirm;

    // RECORDS
    public record TestURI(String name, String uri, boolean debug) {
    }

    public record URIGroup(String name, TestURI[] uris) {
    }

    public record IptvCatalog(String generatedAt, IptvChannel[] channels) {
    }

    public record SuspectMod(String id, String name, String slug, String url) {
    }

    public record IptvChannel(String name, String url, String logo, String region,
                              String group, String tvgId, String source) {
    }

    public static final class UploadFileEntry {
        public final String name;
        public final java.nio.file.Path path;
        public volatile String sizeLabel = "-";
        public volatile String state = "PENDING";
        public volatile String url = "";
        public volatile int progress;
        public volatile boolean present;
        public volatile boolean valid;
        public volatile boolean uploaded;

        public UploadFileEntry(final String name, final java.nio.file.Path path) {
            this.name = name;
            this.path = path;
        }
    }

    @Override
    public void execute(final Runnable task) {
        if (task != null) {
            this.executor.add(task);
            this.requestRender();
        }
    }

    public void requestRender() {
        this.renderRequested = true;
        if (this.windowHandle != 0L) {
            glfwPostEmptyEvent();
        }
    }

    public boolean renderRequested() {
        return this.renderRequested;
    }

    public boolean consumeRenderRequest() {
        // SINGLE-CONSUMER CLEAR ON THE MAIN/GL THREAD; WRITERS ONLY EVER SET TRUE,
        // SO A RACING REQUEST IS HARMLESSLY RE-DELIVERED VIA glfwPostEmptyEvent().
        final boolean requested = this.renderRequested;
        this.renderRequested = false;
        return requested;
    }

    public boolean processExecutor() {
        boolean processed = false;
        while (!this.executor.isEmpty()) {
            final Runnable task = this.executor.poll();
            if (task != null) {
                task.run();
                processed = true;
            }
        }
        if (processed) this.requestRender();
        return processed;
    }

    public String formatTime(final long ms) {
        return TIME_FORMAT.format(new Date(ms));
    }

    public void clearGroupState() {
        this.selectedGroup = null;
        this.groupMRLs.clear();
    }

    public void releasePlayer() {
        final MediaPlayer p = this.player;
        if (p == null) return;
        this.player = null; // RENDER-THREAD-ONLY FIELD; SAFE TO CLEAR BEFORE THE BACKGROUND TEARDOWN
        p.stop();
        // release() JOINS THE DECODER LIFECYCLE — FFMPEG CONTEXT CLOSE + DECODE-THREAD JOINS + GPU
        // DRAIN — WHICH BLOCKS ~HUNDREDS OF MS. RUN IT OFF THE RENDER THREAD SO LEAVING THE PLAYER
        // SCREEN DOES NOT FREEZE THE UI.
        this.releaseAsync("WaterMedia-PlayerRelease", p::release);
    }

    /**
     * Runs a blocking media teardown off the render thread, chained behind any prior release. Since
     * {@link #awaitPlayerRelease()} joins only the latest thread, this chain guarantees no earlier
     * release — the main player or a batch of thumbnails — outlives the render-device teardown. The
     * engine release is thread-safe (OpenGL defers its texture deletes to the render executor; Vulkan
     * submits under the shared queue lock). Call on the render thread; {@code release} runs off it.
     */
    public void releaseAsync(final String name, final Runnable release) {
        final Thread prev = this.releaseThread;
        this.releaseThread = ThreadTool.createStarted(name, () -> {
            if (prev != null) {
                while (prev.isAlive()) ThreadTool.join(prev);
            }
            release.run();
        });
    }

    /**
     * Blocks until the latest asynchronous {@link #releaseAsync} finishes (the main player or the last
     * thumbnail batch). Call this on the render thread before tearing down the render device on shutdown,
     * so a background Vulkan release never touches a destroyed device. The executor is pumped meanwhile
     * because a background OpenGL engine release dispatches its texture deletions onto this thread, which
     * is no longer running the loop.
     */
    public void awaitPlayerRelease() {
        final Thread t = this.releaseThread;
        if (t == null) return;
        while (t.isAlive()) {
            this.processExecutor();
            ThreadTool.sleep(2);
        }
        this.processExecutor();
    }

    public void playSelectionSound() {
        if (this.selectionSoundEnabled && this.soundSource > 0) {
            alSourceStop(this.soundSource);
            alSourcePlay(this.soundSource);
        }
    }

    /**
     * Shows a global error dialog.
     */
    public void showError(final String title, final String message, final Runnable onClose) {
        this.error.show(title, message, onClose);
    }

    /**
     * Shows a global error dialog with default title.
     */
    public void showError(final String message, final Runnable onClose) {
        this.error.show(message, onClose);
    }

    /**
     * Checks if there's an active error dialog.
     */
    public boolean hasError() {
        return this.error.has();
    }

    /**
     * Clears the error dialog.
     */
    public void clearError() {
        this.error.clear();
    }
}
