package org.watermedia.bootstrap.app;

import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaPlayer;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceStop;

/**
 * Window, input, selection, dialog and audio state shared by the screens, plus the render-thread task queue.
 */
public final class AppContext implements Executor {

    public static final String APP_NAME = "WATERMeDIA: Multimedia API";
    public static final String REPO = "SrRapero720/watermedia"; // MIRRORS github IN gradle.properties
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final boolean IN_MODS = new File("").getAbsoluteFile().getName().equalsIgnoreCase("mods");

    // MODS BUILT ON WATERMEDIA, OFFERED AS ISSUE TARGETS IN THE THIRD UPLOAD STAGE WHEN A JAR NAME IN THE
    // MODS FOLDER CONTAINS THEIR id
    public static final List<SuspectMod> SUSPECT_MODS = List.of(
            new SuspectMod("fancymenu", "FancyMenu", "Keksuccino/FancyMenu", "https://github.com/Keksuccino/FancyMenu/issues/new"),
            new SuspectMod("waterframes", "WaterFrames", "SrRapero720/waterframes", "https://github.com/SrRapero720/waterframes/issues/new"),
            new SuspectMod("watervision", "WaterVision", "SrRapero720/watervision", "https://github.com/SrRapero720/watervision/issues/new"),
            new SuspectMod("littleframes", "LittleFrames", "CreativeMD/littleframes", "https://github.com/CreativeMD/littleframes/issues/new")
    );

    // WINDOW STATE
    public int windowWidth = 1280;
    public int windowHeight = 720;
    public long windowHandle;
    public boolean windowMaximized;
    private volatile boolean renderRequested = true;
    // GLOBAL UI SCALE (PHYSICAL PX PER LOGICAL PX)
    public volatile float uiScale = 1f;

    /** Returns the window width in logical pixels, the coordinate space of the element tree. */
    public int logicalWidth() {
        return Math.round(this.windowWidth / this.uiScale);
    }

    /** Returns the window height in logical pixels, the coordinate space of the element tree. */
    public int logicalHeight() {
        return Math.round(this.windowHeight / this.uiScale);
    }

    // CRT OVERLAY (SCANLINES AND NEON BANDS), TOGGLED BY THE C SHORTCUT AND THE SETTINGS ROW
    public volatile boolean crt = true;

    // MOUSE, IN LOGICAL PX
    public double mouseX;
    public double mouseY;
    // PHYSICAL CURSOR COORDS FOR THE TITLEBAR DRAG
    public volatile double mouseRawX;
    public volatile double mouseRawY;
    public boolean mouseDown;
    public boolean mousePressed;
    public boolean mouseClicked;
    public boolean ctrlDown;
    public boolean selectionSoundEnabled = true;

    // TEXT RENDERER
    public TextRenderer text;

    // RENDER-THREAD TASKS DRAINED BY processExecutor(); PRIVATE SO EVERY ENQUEUE GOES THROUGH execute(), WHICH
    // WAKES THE LOOP
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    // MRL DATA
    public final LinkedHashMap<String, MRL> groupMRLs = new LinkedHashMap<>();
    public final List<TestURI> customTests = new ArrayList<>();
    public URIGroup[] uriGroups;
    public IptvChannel[] iptvChannels = new IptvChannel[0];

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

    public String customUrlText = "";

    // UPLOAD LOGS DIALOG STATE
    public final UploadState upload = new UploadState();

    // CLEANUP CACHE DIALOG STATE
    public final CleanupState cleanup = new CleanupState();

    // GLOBAL ERROR DIALOG
    final ErrorState error = new ErrorState();

    // GLOBAL EXIT-CONFIRM DIALOG, RAISED BY THE TITLEBAR CLOSE AND THE EXIT ACTION
    public volatile boolean exitConfirm;

    // RECORDS
    public record TestURI(String name, String uri, boolean debug) {
    }

    public record URIGroup(String name, TestURI[] uris) {
    }

    record IptvCatalog(String generatedAt, IptvChannel[] channels) {
    }

    public record SuspectMod(String id, String name, String slug, String url) {
    }

    public record IptvChannel(String name, String url, String logo, String region,
                              String group, String tvgId, String source) {
    }

    public static final class UploadFileEntry {
        public final String name;
        public final Path path;
        public volatile String sizeLabel = "-";
        public volatile String state = "PENDING";
        public volatile String url = "";
        public volatile int progress;
        public volatile boolean present;
        public volatile boolean valid;
        public volatile boolean uploaded;

        public UploadFileEntry(final String name, final Path path) {
            this.name = name;
            this.path = path;
        }
    }

    @Override
    public void execute(final Runnable task) {
        if (task != null) {
            this.tasks.add(task);
            this.requestRender();
        }
    }

    public void requestRender() {
        // ONLY THE FIRST REQUEST AFTER A CONSUMED FRAME WAKES THE LOOP; LATER ONES FIND THE FLAG ALREADY RAISED
        if (this.renderRequested) return;
        this.renderRequested = true;
        if (this.windowHandle != 0L) glfwPostEmptyEvent();
    }

    public boolean renderRequested() {
        return this.renderRequested;
    }

    public boolean consumeRenderRequest() {
        // ONLY THE RENDER THREAD CLEARS THE FLAG AND WRITERS ONLY SET IT, SO A RACING REQUEST IS
        // RE-DELIVERED BY glfwPostEmptyEvent()
        final boolean requested = this.renderRequested;
        this.renderRequested = false;
        return requested;
    }

    public boolean processExecutor() {
        boolean processed = false;
        while (!this.tasks.isEmpty()) {
            final Runnable task = this.tasks.poll();
            if (task != null) {
                task.run();
                processed = true;
            }
        }
        if (processed) this.requestRender();
        return processed;
    }

    public String formatTime(final long ms) {
        // ms IS ELAPSED MEDIA TIME, NOT WALL CLOCK; IT WRAPS AT 24H
        return LocalTime.ofSecondOfDay(Math.max(0L, ms) / 1000L % 86_400L).format(TIME_FORMAT);
    }

    /** Takes the sources of {@link #selectedMRL} and selects the first; returns {@code false} when it has none. */
    public boolean selectSources() {
        this.availableSources = this.selectedMRL.sources().toArray(MRL.Source[]::new);
        if (this.availableSources.length == 0) return false;
        this.sourceSelectorIndex = 0;
        this.selectedSource = this.availableSources[0];
        return true;
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
        // release() CLOSES FFMPEG, JOINS THE DECODE THREADS AND DRAINS THE GPU FOR HUNDREDS OF MS, SO IT
        // RUNS OFF THE RENDER THREAD TO KEEP THE UI RESPONSIVE WHEN LEAVING THE PLAYER
        this.releaseAsync("WaterMedia-PlayerRelease", p::release);
    }

    /** Runs a blocking media teardown off the render thread, after any previous one; call it on the render thread. */
    public void releaseAsync(final String name, final Runnable release) {
        final Thread prev = this.releaseThread;
        // CHAINED BECAUSE awaitPlayerRelease() JOINS ONLY THE LATEST THREAD, WHICH THEN OUTLASTS EVERY EARLIER RELEASE
        this.releaseThread = ThreadTool.thread(name, () -> {
            if (prev != null) ThreadTool.join(prev);
            release.run();
        });
    }

    /** Blocks until every {@link #releaseAsync} finishes; call it on the render thread before destroying the render device. */
    public void awaitPlayerRelease() {
        final Thread t = this.releaseThread;
        if (t == null) return;
        // PUMPS THE EXECUTOR: A BACKGROUND OPENGL RELEASE QUEUES ITS TEXTURE DELETES ON THIS THREAD, WHICH HAS
        // LEFT THE LOOP
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

    /** Shows the global error dialog. */
    public void showError(final String title, final String message, final Runnable onClose) {
        this.error.show(title, message, onClose);
    }

    /** Returns whether the error dialog is active. */
    public boolean hasError() {
        return this.error.has();
    }

    /** Clears the error dialog and runs its close callback. */
    public void clearError() {
        this.error.clear();
    }
}
