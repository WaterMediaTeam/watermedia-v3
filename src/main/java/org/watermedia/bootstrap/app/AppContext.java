package org.watermedia.bootstrap.app;

import com.google.gson.Gson;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final AtomicBoolean renderRequested = new AtomicBoolean(true);

    // MOUSE
    public double mouseX;
    public double mouseY;
    public boolean mouseDown;
    public boolean mousePressed;
    public boolean mouseClicked;
    public boolean ctrlDown;
    public boolean selectionSoundEnabled = true;

    // CONFIG STATUS SHOWN BY SETTINGS IN THE ENGINE STRIP
    public volatile boolean configStatusVisible;
    public volatile String configStatusText = "READY";
    public volatile boolean configStatusPulse;
    public volatile boolean configStatusWarn;
    public volatile boolean configStatusError;
    public volatile boolean configStatusStrike;

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

    // BANNER
    public int bannerTextureId = -1;
    public int bannerWidth;
    public int bannerHeight;
    public int bannerGlowTextureId = -1;
    public int bannerGlowWidth;
    public int bannerGlowHeight;
    public int iconTextureId = -1;
    public int iconWidth;
    public int iconHeight;
    public int iconGlowTextureId = -1;
    public int iconGlowWidth;
    public int iconGlowHeight;
    public int[] duckFrameTextureIds = new int[0];
    public int duckFrameWidth;
    public int duckFrameHeight;
    public boolean backendsLoading = true;

    // AUDIO
    public int soundBuffer = -1;
    public int soundSource = -1;
    public boolean audioReady;
    public boolean audioError;

    public String customUrlText = "";

    // UPLOAD LOGS DIALOG STATE
    public volatile boolean uploadDialogVisible;
    public volatile boolean uploadDialogWorking;
    public volatile boolean uploadDialogDone;
    public volatile boolean uploadDialogError;
    public volatile boolean uploadUploadsDone;
    public volatile boolean uploadIssueCopied;
    public volatile boolean uploadIssueOpened;
    public volatile int uploadDialogStage = 1;
    public volatile String uploadDialogStatus = "READY";
    public volatile String uploadIssueUrl = "github.com/watermedia/issues/new";
    public final List<UploadFileEntry> uploadDialogFiles = new CopyOnWriteArrayList<>();

    // CLEANUP CACHE DIALOG STATE
    public volatile boolean cleanupDialogVisible;
    public volatile boolean cleanupDialogWorking;
    public volatile boolean cleanupDialogDone;
    public volatile boolean cleanupDialogError;
    public volatile int cleanupDialogStage = 1;
    public volatile int cleanupFileCount;
    public volatile String cleanupSizeLabel = "0 B";
    public volatile String cleanupDialogState = "READY";
    public volatile int cleanupProgress;

    // GLOBAL ERROR DIALOG
    public String globalErrorTitle;
    public String globalErrorMessage;
    public Runnable globalErrorOnClose;

    // RECORDS
    public record TestURI(String name, String uri, boolean debug) {
    }

    public record URIGroup(String name, TestURI[] uris) {
    }

    public record IptvCatalog(String generatedAt, IptvChannel[] channels) {
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
        this.renderRequested.set(true);
        if (this.windowHandle != 0L) {
            glfwPostEmptyEvent();
        }
    }

    public boolean renderRequested() {
        return this.renderRequested.get();
    }

    public boolean consumeRenderRequest() {
        return this.renderRequested.getAndSet(false);
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
        if (this.player != null) {
            this.player.stop();
            this.player.release();
            this.player = null;
        }
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
        this.globalErrorTitle = title;
        this.globalErrorMessage = message;
        this.globalErrorOnClose = onClose;
    }

    /**
     * Shows a global error dialog with default title.
     */
    public void showError(final String message, final Runnable onClose) {
        this.showError("Error", message, onClose);
    }

    /**
     * Checks if there's an active error dialog.
     */
    public boolean hasError() {
        return this.globalErrorMessage != null;
    }

    /**
     * Clears the error dialog.
     */
    public void clearError() {
        final Runnable callback = this.globalErrorOnClose;
        this.globalErrorTitle = null;
        this.globalErrorMessage = null;
        this.globalErrorOnClose = null;
        if (callback != null) callback.run();
    }
}
