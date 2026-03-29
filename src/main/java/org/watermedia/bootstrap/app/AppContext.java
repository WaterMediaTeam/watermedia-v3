package org.watermedia.bootstrap.app;

import com.google.gson.Gson;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

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

    // Window state
    public int windowWidth = 1280;
    public int windowHeight = 720;
    public long windowHandle;

    // Mouse
    public double mouseX;
    public double mouseY;
    public boolean mouseClicked;

    // Text renderer
    public TextRenderer text;

    // Executor for GL thread tasks
    public final Queue<Runnable> executor = new ConcurrentLinkedQueue<>();

    // MRL data
    public final LinkedHashMap<String, MRL> groupMRLs = new LinkedHashMap<>();
    public final List<TestURI> customTests = new ArrayList<>();
    public URIGroup[] uriGroups;

    // Selection state
    public URIGroup selectedGroup;
    public MRL selectedMRL;
    public String selectedMRLName = "";
    public MRL.Source[] availableSources;
    public MRL.Source selectedSource;
    public MRL.Quality[] availableQualities;
    public MRL.Quality selectedQuality = MRL.Quality.HIGHER;
    public int sourceSelectorIndex;

    // Player
    public MediaPlayer player;
    public boolean playerEscPressed;

    // Banner
    public int bannerTextureId = -1;
    public int bannerWidth;
    public int bannerHeight;

    // Audio
    public int soundBuffer = -1;
    public int soundSource = -1;

    // Dialog state
    public String finishedReason = "";
    public boolean finishedWasError;
    public String customUrlText = "";

    // Global error dialog
    public String globalErrorTitle;
    public String globalErrorMessage;
    public Runnable globalErrorOnClose;

    // Records
    public record TestURI(String name, String uri) {
    }

    public record URIGroup(String name, TestURI[] uris) {
    }

    @Override
    public void execute(final Runnable task) {
        if (task != null) this.executor.add(task);
    }

    public void processExecutor() {
        while (!this.executor.isEmpty()) {
            final Runnable task = this.executor.poll();
            if (task != null) task.run();
        }
    }

    public String formatTime(final long ms) {
        return TIME_FORMAT.format(new Date(ms));
    }

    public void clearGroupState() {
        this.selectedGroup = null;
        this.groupMRLs.clear();
    }

    public void clearSourceState() {
        this.availableSources = null;
        this.selectedSource = null;
    }

    public void releasePlayer() {
        if (this.player != null) {
            this.player.stop();
            this.player.release();
            this.player = null;
        }
    }

    public void playSelectionSound() {
        if (this.soundSource > 0) {
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
