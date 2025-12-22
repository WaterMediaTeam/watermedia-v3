package org.watermedia.test;

import org.lwjgl.openal.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class MediaPlayerWindowTest {
    private static final String NAME = "WATERMeDIA: Multimedia API";

    // ============================================================
    // MEDIA SOURCES - DYNAMIC HASHMAP
    // ============================================================
    private static final LinkedHashMap<String, MRL> MEDIA_SOURCES = new LinkedHashMap<>();

    private static final DateFormat FORMAT = new SimpleDateFormat("HH:mm:ss");

    static {
        FORMAT.setTimeZone(TimeZone.getTimeZone("GMT-00:00"));
    }

    // ============================================================
    // APPLICATION STATE
    // ============================================================
    private enum AppState {
        SOURCE_SELECTOR,
        MRL_SOURCE_SELECTOR,
        QUALITY_SELECTOR,
        PLAYER_RUNNING,
        PLAYER_FINISHED
    }

    private static volatile AppState currentState = AppState.SOURCE_SELECTOR;

    // SOURCE SELECTOR STATE
    private static volatile int sourceSelectorIndex = 0;
    private static String[] sourceKeys;

    // QUALITY SELECTOR STATE
    private static volatile int qualitySelectorIndex = 0;
    private static MRL.Quality[] availableQualities;
    private static volatile MRL.Quality selectedQuality = MRL.Quality.HIGHEST;

    // MRL SOURCE SELECTOR STATE
    private static volatile int mrlSourceSelectorIndex = 0;
    private static MRL.Source[] availableSources;

    // PLAYER FINISHED STATE
    private static volatile String playerFinishedReason = "";

    // CURRENT SELECTED MRL AND SOURCE
    private static volatile MRL selectedMRL = null;
    private static volatile MRL.Source selectedSource = null;

    // THE WINDOW HANDLE
    private static long window;
    private static volatile MediaPlayer player;
    private static final Queue<Runnable> executor = new ConcurrentLinkedQueue<>();
    private static Thread thread;

    // TEXT RENDERING SYSTEM
    private static final Map<Character, CharTexture> charTextureCache = new HashMap<>();
    private static Font textFont;
    private static final int FONT_SIZE = 24;

    // FADE OVERLAY WIDTH
    private static final int FADE_WIDTH = 350;

    // ALERT BOX DIMENSIONS
    private static final int ALERT_WIDTH = 280;
    private static final int ALERT_HEIGHT = 40;
    private static final int ALERT_MARGIN = 15;

    // COLORS
    private static final Color COLOR_GREEN = new Color(0, 255, 0);
    private static final Color COLOR_ORANGE = new Color(255, 165, 0);
    private static final Color COLOR_WHITE = Color.WHITE;
    private static final Color COLOR_BLACK = Color.BLACK;

    // CHAR TEXTURE DATA HOLDER
    private record CharTexture(int textureId, int width, int height) {}

    public static void main(final String... args) {
        WaterMedia.start("Java Test", null, null, true);

        // Populate source keys array
        MEDIA_SOURCES.put("GIFTS #1 (1920x1080)", MRL.get(URI.create("https://blog.phonehouse.es/wp-content/uploads/2018/01/giphy-1-1.gif")));
        MEDIA_SOURCES.put("STREAMABLE #2 (1280x720)", MRL.get(URI.create("https://streamable.com/6yszde")));
        MEDIA_SOURCES.put("RAKKUN #4 (1280x720)", MRL.get(URI.create("https://files.catbox.moe/1n0jn9.mp4")));
        MEDIA_SOURCES.put("4K VIDEO #5 (3840x2160)", MRL.get(URI.create("https://lf-tk-sg.ibytedtos.com/obj/tcs-client-sg/resources/hevc_4k25P_main_1.mp4")));
        MEDIA_SOURCES.put("8K VIDEO #6 (7680x4320)", MRL.get(URI.create("https://lf-tk-sg.ibytedtos.com/obj/tcs-client-sg/resources/hevc_8k60P_bilibili_1.mp4")));
        MEDIA_SOURCES.put("KICK STREAM #7 (???x???)", MRL.get(URI.create("https://kick.com/yosoyrick")));
        MEDIA_SOURCES.put("IMGUR MULTISOURCE #8 (???x???)", MRL.get(URI.create("https://imgur.com/gallery/random-images-videos-to-test-k1e4ufO")));
        sourceKeys = MEDIA_SOURCES.keySet().toArray(new String[0]);

        init();

        // Make the window visible
        glfwShowWindow(window);

        // Main application loop
        mainLoop();

        // Cleanup
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).close();
        System.exit(0);
    }

    private static void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, NAME, NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create the GLFW window");

        // Setup key callback - handles all states
        glfwSetKeyCallback(window, MediaPlayerWindowTest::handleKeyInput);

        // Center the window
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1);
            final IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);

            final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(window, (vidmode.width() - pWidth.get(0)) / 2, (vidmode.height() - pHeight.get(0)) / 2);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);

        GL.createCapabilities();

        // Open Audio Device
        final long device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) throw new IllegalStateException("Failed to open a new Audio Device");

        final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(context);

        final ALCCapabilities alcCaps = ALC.createCapabilities(device);
        final ALCapabilities alCaps = AL.createCapabilities(alcCaps);

        thread = Thread.currentThread();
        textFont = new Font("Consolas", Font.PLAIN, FONT_SIZE);
    }

    private static void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        if (action != GLFW_RELEASE) return;

        switch (currentState) {
            case SOURCE_SELECTOR -> handleSourceSelectorKeys(key);
            case MRL_SOURCE_SELECTOR -> handleMrlSourceSelectorKeys(key);
            case QUALITY_SELECTOR -> handleQualitySelectorKeys(key);
            case PLAYER_RUNNING -> handlePlayerKeys(key);
            case PLAYER_FINISHED -> handlePlayerFinishedKeys(key);
        }
    }

    private static void handleSourceSelectorKeys(final int key) {
        final int totalOptions = sourceKeys.length + 1; // +1 for EXIT option
        switch (key) {
            case GLFW_KEY_UP -> {
                sourceSelectorIndex--;
                if (sourceSelectorIndex < 0) sourceSelectorIndex = totalOptions - 1;
            }
            case GLFW_KEY_DOWN -> {
                sourceSelectorIndex++;
                if (sourceSelectorIndex >= totalOptions) sourceSelectorIndex = 0;
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> selectSource();
            case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true);
        }
    }

    private static void handleMrlSourceSelectorKeys(final int key) {
        if (availableSources == null || availableSources.length == 0) return;

        final int totalOptions = availableSources.length + 1; // +1 for BACK option
        switch (key) {
            case GLFW_KEY_UP -> {
                mrlSourceSelectorIndex--;
                if (mrlSourceSelectorIndex < 0) mrlSourceSelectorIndex = totalOptions - 1;
            }
            case GLFW_KEY_DOWN -> {
                mrlSourceSelectorIndex++;
                if (mrlSourceSelectorIndex >= totalOptions) mrlSourceSelectorIndex = 0;
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> selectMrlSource();
            case GLFW_KEY_ESCAPE -> {
                // If we came from player, go back to player
                if (player != null) {
                    currentState = AppState.PLAYER_RUNNING;
                    if (player.paused()) {
                        player.resume();
                    }
                } else {
                    currentState = AppState.SOURCE_SELECTOR;
                    availableSources = null;
                }
            }
        }
    }

    private static void handleQualitySelectorKeys(final int key) {
        if (availableQualities == null || availableQualities.length == 0) return;

        final int totalOptions = availableQualities.length + 1; // +1 for BACK option
        switch (key) {
            case GLFW_KEY_UP -> {
                qualitySelectorIndex--;
                if (qualitySelectorIndex < 0) qualitySelectorIndex = totalOptions - 1;
            }
            case GLFW_KEY_DOWN -> {
                qualitySelectorIndex++;
                if (qualitySelectorIndex >= totalOptions) qualitySelectorIndex = 0;
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> selectQuality();
            case GLFW_KEY_ESCAPE -> {
                // Go back to MRL source selector if multiple sources, otherwise to source selector
                if (availableSources != null && availableSources.length > 1) {
                    currentState = AppState.MRL_SOURCE_SELECTOR;
                } else {
                    currentState = AppState.SOURCE_SELECTOR;
                }
                availableQualities = null;
            }
        }
    }

    private static void handlePlayerKeys(final int key) {
        if (player == null) return;

        switch (key) {
            // ESC - Return to source selector
            case GLFW_KEY_ESCAPE -> stopPlayerAndReturnToMenu();

            // ENTER - Open quality selector
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> openQualitySelectorFromPlayer();

            // TAB - Open MRL source selector (if multiple sources available)
            case GLFW_KEY_TAB -> openMrlSourceSelectorFromPlayer();

            // Basic controls
            case GLFW_KEY_SPACE -> player.togglePlay();
            case GLFW_KEY_LEFT -> player.rewind();
            case GLFW_KEY_RIGHT -> player.foward();
            case GLFW_KEY_S -> player.stop();

            // Volume control
            case GLFW_KEY_UP -> player.volume(player.volume() + 5);
            case GLFW_KEY_DOWN -> player.volume(player.volume() - 5);

            // Frame stepping
            case GLFW_KEY_PERIOD -> player.nextFrame();      // . = next frame
            case GLFW_KEY_COMMA -> player.previousFrame();   // , = previous frame

            // Percentage seek (0-9 keys for 0%-90%)
            case GLFW_KEY_0, GLFW_KEY_KP_0 -> seekToPercentage(0);
            case GLFW_KEY_1, GLFW_KEY_KP_1 -> seekToPercentage(10);
            case GLFW_KEY_2, GLFW_KEY_KP_2 -> seekToPercentage(20);
            case GLFW_KEY_3, GLFW_KEY_KP_3 -> seekToPercentage(30);
            case GLFW_KEY_4, GLFW_KEY_KP_4 -> seekToPercentage(40);
            case GLFW_KEY_5, GLFW_KEY_KP_5 -> seekToPercentage(50);
            case GLFW_KEY_6, GLFW_KEY_KP_6 -> seekToPercentage(60);
            case GLFW_KEY_7, GLFW_KEY_KP_7 -> seekToPercentage(70);
            case GLFW_KEY_8, GLFW_KEY_KP_8 -> seekToPercentage(80);
            case GLFW_KEY_9, GLFW_KEY_KP_9 -> seekToPercentage(90);
        }
    }

    private static void handlePlayerFinishedKeys(final int key) {
        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER, GLFW_KEY_ESCAPE -> {
                currentState = AppState.SOURCE_SELECTOR;
                playerFinishedReason = "";
            }
        }
    }

    private static void openQualitySelectorFromPlayer() {
        if (selectedSource == null) return;

        // Pause the player while selecting
        if (player != null && player.playing()) {
            player.pause();
        }

        final Set<MRL.Quality> qualities = selectedSource.availableQualities();
        if (qualities == null || qualities.isEmpty()) return;

        availableQualities = qualities.toArray(new MRL.Quality[0]);
        Arrays.sort(availableQualities, Comparator.comparingInt(q -> q.threshold));
        qualitySelectorIndex = 0;

        // Find current quality in list
        for (int i = 0; i < availableQualities.length; i++) {
            if (availableQualities[i] == selectedQuality) {
                qualitySelectorIndex = i;
                break;
            }
        }

        currentState = AppState.QUALITY_SELECTOR;
    }

    private static void openMrlSourceSelectorFromPlayer() {
        if (selectedMRL == null) return;
        if (availableSources == null || availableSources.length <= 1) return;

        // Pause the player while selecting
        if (player != null && player.playing()) {
            player.pause();
        }

        currentState = AppState.MRL_SOURCE_SELECTOR;
    }

    private static void seekToPercentage(final int percentage) {
        if (player == null) return;
        final long duration = player.duration();
        if (duration > 0) {
            final long targetTime = (duration * percentage) / 100;
            player.seek(targetTime);
        }
    }

    private static void selectSource() {
        // Last option is EXIT
        if (sourceSelectorIndex >= sourceKeys.length) {
            glfwSetWindowShouldClose(window, true);
            return;
        }

        final String selectedKey = sourceKeys[sourceSelectorIndex];
        selectedMRL = MEDIA_SOURCES.get(selectedKey);

        // Only allow selection if MRL is ready
        if (!selectedMRL.ready()) {
            return; // Do nothing if not ready
        }

        // Get all available sources from MRL
        availableSources = selectedMRL.sources();
        if (availableSources == null || availableSources.length == 0) {
            return;
        }

        // If only one source, skip to quality selector
        if (availableSources.length == 1) {
            selectedSource = availableSources[0];
            openQualitySelector();
        } else {
            // Multiple sources - show source selector
            mrlSourceSelectorIndex = 0;
            currentState = AppState.MRL_SOURCE_SELECTOR;
        }
    }

    private static void selectMrlSource() {
        // Last option is BACK
        if (mrlSourceSelectorIndex >= availableSources.length) {
            // If we came from player, go back to player
            if (player != null) {
                currentState = AppState.PLAYER_RUNNING;
                if (player.paused()) {
                    player.resume();
                }
            } else {
                currentState = AppState.SOURCE_SELECTOR;
            }
            return;
        }

        // If changing source while playing, stop current player
        final boolean wasPlaying = player != null;
        if (wasPlaying) {
            player.stop();
            player.release();
            player = null;
        }

        selectedSource = availableSources[mrlSourceSelectorIndex];
        openQualitySelector();
    }

    private static void openQualitySelector() {
        if (selectedSource == null) return;

        final Set<MRL.Quality> qualities = selectedSource.availableQualities();
        if (qualities != null && !qualities.isEmpty()) {
            availableQualities = qualities.toArray(new MRL.Quality[0]);
            Arrays.sort(availableQualities, Comparator.comparingInt(q -> q.threshold));
            qualitySelectorIndex = 0;
            selectedQuality = MRL.Quality.HIGHEST;
            currentState = AppState.QUALITY_SELECTOR;
        } else {
            // No qualities available, start player directly
            selectedQuality = MRL.Quality.UNKNOWN;
            startPlayer();
        }
    }

    private static void selectQuality() {
        // Last option is BACK
        if (qualitySelectorIndex >= availableQualities.length) {
            // Go back to MRL source selector if multiple sources, otherwise to source selector
            if (availableSources != null && availableSources.length > 1) {
                currentState = AppState.MRL_SOURCE_SELECTOR;
            } else {
                currentState = AppState.SOURCE_SELECTOR;
            }
            availableQualities = null;
            return;
        }

        selectedQuality = availableQualities[qualitySelectorIndex];
        startPlayer();
    }

    private static void startPlayer() {
        if (selectedMRL == null || selectedSource == null) return;

        if (player != null) {
            player.setQuality(selectedQuality);
            currentState = AppState.PLAYER_RUNNING;
            return;
        }

        // Find the source index
        final int sourceIndex = availableSources != null ? mrlSourceSelectorIndex : 0;

        // Use MRL's createPlayer method with source index
        player = selectedMRL.createPlayer(
                sourceIndex,
                Thread.currentThread(),
                MediaPlayerWindowTest::execute,
                null,
                null,
                true,
                true
        );

        if (player != null) {
            player.setQuality(selectedQuality);
            player.start();
            currentState = AppState.PLAYER_RUNNING;
        }
    }

    private static void stopPlayerAndReturnToMenu() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        currentState = AppState.SOURCE_SELECTOR;
    }

    private static void transitionToFinishedState(final String reason) {
        playerFinishedReason = reason;
        if (player != null) {
            player.release();
            player = null;
        }
        currentState = AppState.PLAYER_FINISHED;
    }

    private static void mainLoop() {
        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {
            System.out.println(MemoryUtil.memASCII(message));
        }, 0);

        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glfwSetWindowSizeCallback(window, (w, width, height) -> glViewport(0, 0, width, height));

        while (!glfwWindowShouldClose(window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            switch (currentState) {
                case SOURCE_SELECTOR -> renderSourceSelector();
                case MRL_SOURCE_SELECTOR -> renderMrlSourceSelector();
                case QUALITY_SELECTOR -> renderQualitySelector();
                case PLAYER_RUNNING -> renderPlayer();
                case PLAYER_FINISHED -> renderPlayerFinished();
            }

            // Always render FFMPEG alert if not loaded
            renderFFmpegAlert();

            glfwSwapBuffers(window);
            glfwPollEvents();

            while (!executor.isEmpty()) {
                executor.poll().run();
            }
        }

        // Cleanup player if running
        if (player != null) {
            player.stop();
            player.release();
        }
    }

    private static void renderFFmpegAlert() {
        if (FFMediaPlayer.loaded()) return; // Don't show if FFMPEG is available

        final int[] windowSize = getWindowSize();
        final int windowWidth = windowSize[0];

        setupOrthoProjection(windowWidth, windowSize[1]);
        glDisable(GL_TEXTURE_2D);

        final int alertX = windowWidth - ALERT_WIDTH - ALERT_MARGIN;
        final int alertY = ALERT_MARGIN;

        // Draw orange background box
        glColor4f(COLOR_ORANGE.getRed() / 255f, COLOR_ORANGE.getGreen() / 255f, COLOR_ORANGE.getBlue() / 255f, 0.9f);
        glBegin(GL_QUADS);
        {
            glVertex2f(alertX, alertY);
            glVertex2f(alertX + ALERT_WIDTH, alertY);
            glVertex2f(alertX + ALERT_WIDTH, alertY + ALERT_HEIGHT);
            glVertex2f(alertX, alertY + ALERT_HEIGHT);
        }
        glEnd();

        // Draw border
        glColor4f(0.8f, 0.4f, 0, 1f);
        glLineWidth(2f);
        glBegin(GL_LINE_LOOP);
        {
            glVertex2f(alertX, alertY);
            glVertex2f(alertX + ALERT_WIDTH, alertY);
            glVertex2f(alertX + ALERT_WIDTH, alertY + ALERT_HEIGHT);
            glVertex2f(alertX, alertY + ALERT_HEIGHT);
        }
        glEnd();

        glEnable(GL_TEXTURE_2D);

        // Draw alert text
        final String alertText = "! FFMPEG UNAVAILABLE";
        final int textX = alertX + 10;
        final int textY = alertY + 10;
        renderText(alertText, textX, textY, COLOR_BLACK);

        restoreProjection();
    }

    private static void renderSourceSelector() {
        final int[] windowSize = getWindowSize();
        final int windowWidth = windowSize[0];
        final int windowHeight = windowSize[1];

        setupOrthoProjection(windowWidth, windowHeight);

        final ArrayList<String> linesList = new ArrayList<>();
        linesList.add("=== Select Video Source ===");
        linesList.add("");

        for (int i = 0; i < sourceKeys.length; i++) {
            final String prefix = (i == sourceSelectorIndex) ? "> " : "  ";
            final String arrow = (i == sourceSelectorIndex) ? " <" : "";
            final MRL mrl = MEDIA_SOURCES.get(sourceKeys[i]);
            final String status = getMRLStatusString(mrl);
            linesList.add(prefix + sourceKeys[i] + " [" + status + "]" + arrow);
        }

        // Add EXIT option
        final String exitPrefix = (sourceSelectorIndex == sourceKeys.length) ? "> " : "  ";
        final String exitArrow = (sourceSelectorIndex == sourceKeys.length) ? " <" : "";
        linesList.add(exitPrefix + "[EXIT]" + exitArrow);

        linesList.add("");
        linesList.add("Use UP/DOWN arrows to navigate");
        linesList.add("Press ENTER to select (only READY sources)");
        linesList.add("Press ESC to quit");

        final String[] lines = linesList.toArray(new String[0]);
        renderMenuText(lines, windowWidth, windowHeight);
        restoreProjection();
    }

    private static void renderMrlSourceSelector() {
        final int[] windowSize = getWindowSize();
        final int windowWidth = windowSize[0];
        final int windowHeight = windowSize[1];

        setupOrthoProjection(windowWidth, windowHeight);

        final ArrayList<String> linesList = new ArrayList<>();
        linesList.add("=== Select Media Source ===");
        linesList.add("MRL: " + sourceKeys[sourceSelectorIndex]);
        linesList.add("Sources available: " + (availableSources != null ? availableSources.length : 0));
        linesList.add("");

        if (availableSources != null) {
            for (int i = 0; i < availableSources.length; i++) {
                final String prefix = (i == mrlSourceSelectorIndex) ? "> " : "  ";
                final String arrow = (i == mrlSourceSelectorIndex) ? " <" : "";
                final MRL.Source src = availableSources[i];

                // Build source description
                final String typeName = src.type().name();
                final int qualityCount = src.availableQualities().size();
                final String metaTitle = (src.metadata() != null && src.metadata().title() != null)
                        ? src.metadata().title()
                        : "Untitled";

                // Truncate title if too long
                final String displayTitle = metaTitle.length() > 25
                        ? metaTitle.substring(0, 22) + "..."
                        : metaTitle;

                final String sourceInfo = String.format("SOURCE #%d [%s] - %s (%d qualities)",
                        i + 1, typeName, displayTitle, qualityCount);
                linesList.add(prefix + sourceInfo + arrow);
            }
        }

        // Add BACK option
        final int backIndex = availableSources != null ? availableSources.length : 0;
        final String backPrefix = (mrlSourceSelectorIndex == backIndex) ? "> " : "  ";
        final String backArrow = (mrlSourceSelectorIndex == backIndex) ? " <" : "";
        linesList.add(backPrefix + "[BACK]" + backArrow);

        linesList.add("");
        linesList.add("Use UP/DOWN arrows to navigate");
        linesList.add("Press ENTER to select");
        linesList.add("Press ESC to go back");

        final String[] lines = linesList.toArray(new String[0]);
        renderMenuText(lines, windowWidth, windowHeight);
        restoreProjection();
    }

    private static void renderQualitySelector() {
        final int[] windowSize = getWindowSize();
        final int windowWidth = windowSize[0];
        final int windowHeight = windowSize[1];

        setupOrthoProjection(windowWidth, windowHeight);

        final ArrayList<String> linesList = new ArrayList<>();
        linesList.add("=== Select Quality ===");
        if (selectedMRL != null) {
            linesList.add("Source: " + sourceKeys[sourceSelectorIndex]);
        }
        linesList.add("");

        if (availableQualities != null) {
            for (int i = 0; i < availableQualities.length; i++) {
                final String prefix = (i == qualitySelectorIndex) ? "> " : "  ";
                final String arrow = (i == qualitySelectorIndex) ? " <" : "";
                final MRL.Quality q = availableQualities[i];
                final String qualityName = q.name() + " (" + q.threshold + "p)";
                linesList.add(prefix + qualityName + arrow);
            }
        }

        // Add BACK option
        final int backIndex = availableQualities != null ? availableQualities.length : 0;
        final String backPrefix = (qualitySelectorIndex == backIndex) ? "> " : "  ";
        final String backArrow = (qualitySelectorIndex == backIndex) ? " <" : "";
        linesList.add(backPrefix + "[BACK]" + backArrow);

        linesList.add("");
        linesList.add("Use UP/DOWN arrows to navigate");
        linesList.add("Press ENTER to select");
        linesList.add("Press ESC to go back");

        final String[] lines = linesList.toArray(new String[0]);
        renderMenuText(lines, windowWidth, windowHeight);
        restoreProjection();
    }

    private static void renderPlayer() {
        if (player == null) return;

        // Check if player ended, error, or stopped - transition to finished state
        if (player.ended()) {
            transitionToFinishedState("ENDED");
            return;
        }
        if (player.error()) {
            transitionToFinishedState("ERROR");
            return;
        }
        if (player.stopped()) {
            transitionToFinishedState("STOPPED");
            return;
        }

        // Render video texture
        glBindTexture(GL_TEXTURE_2D, player.texture());
        glColor4f(1, 1, 1, 1);

        glBegin(GL_QUADS);
        {
            glTexCoord2f(0, 1); glVertex2f(-1, -1);
            glTexCoord2f(0, 0); glVertex2f(-1, 1);
            glTexCoord2f(1, 0); glVertex2f(1, 1);
            glTexCoord2f(1, 1); glVertex2f(1, -1);
        }
        glEnd();

        // Render fade overlays and debug overlay
        renderFadeOverlays();
        renderDebugOverlay();
    }

    private static void renderPlayerFinished() {
        final int[] windowSize = getWindowSize();
        final int windowWidth = windowSize[0];
        final int windowHeight = windowSize[1];

        setupOrthoProjection(windowWidth, windowHeight);

        final String[] lines = {
                "=== Video Playback Finished ===",
                "",
                "Video has " + playerFinishedReason,
                "",
                "> [GO BACK] <",
                "",
                "Press ENTER or ESC to return"
        };

        renderMenuText(lines, windowWidth, windowHeight);
        restoreProjection();
    }

    private static void renderFadeOverlays() {
        final int[] windowSize = getWindowSize();
        final int windowWidth = windowSize[0];
        final int windowHeight = windowSize[1];

        setupOrthoProjection(windowWidth, windowHeight);
        glDisable(GL_TEXTURE_2D);

        // Left fade overlay (dark to transparent)
        glBegin(GL_QUADS);
        {
            // Left edge - fully dark
            glColor4f(0, 0, 0, 0.85f);
            glVertex2f(0, 0);
            glVertex2f(0, windowHeight);

            // Right edge of left fade - transparent
            glColor4f(0, 0, 0, 0.0f);
            glVertex2f(FADE_WIDTH, windowHeight);
            glVertex2f(FADE_WIDTH, 0);
        }
        glEnd();

        // Right fade overlay (transparent to dark)
        glBegin(GL_QUADS);
        {
            // Left edge of right fade - transparent
            glColor4f(0, 0, 0, 0.0f);
            glVertex2f(windowWidth - FADE_WIDTH, 0);
            glVertex2f(windowWidth - FADE_WIDTH, windowHeight);

            // Right edge - fully dark
            glColor4f(0, 0, 0, 0.85f);
            glVertex2f(windowWidth, windowHeight);
            glVertex2f(windowWidth, 0);
        }
        glEnd();

        glEnable(GL_TEXTURE_2D);
        restoreProjection();
    }

    private static void renderDebugOverlay() {
        if (player == null) return;

        final int[] windowSize = getWindowSize();
        final int windowWidth = windowSize[0];
        final int windowHeight = windowSize[1];

        setupOrthoProjection(windowWidth, windowHeight);
        glDisable(GL_DEPTH_TEST);

        final String engineName = player.getClass().getSimpleName();

        // Calculate current source index
        final int currentSourceIndex = (availableSources != null) ? mrlSourceSelectorIndex + 1 : 1;
        final int totalSources = (availableSources != null) ? availableSources.length : 1;

        // Left side - Player info
        final ArrayList<String> leftLines = new ArrayList<>();
        leftLines.add("Engine: " + engineName);
        leftLines.add("Source: " + currentSourceIndex + "/" + totalSources);
        leftLines.add("Size: " + player.width() + "x" + player.height());
        leftLines.add("Status: " + player.status());
        leftLines.add("Time: " + FORMAT.format(new Date(player.time())) + " / " + FORMAT.format(new Date(player.duration())));
        leftLines.add("Speed: " + String.format("%.2f", player.speed()));
        leftLines.add("Is Live: " + player.liveSource());
        leftLines.add("Volume: " + player.volume() + "%");
        leftLines.add("Quality: " + selectedQuality.name());
        leftLines.add("");
        leftLines.add("--- Controls ---");
        leftLines.add("SPACE: Play/Pause");
        leftLines.add("S: Stop | ESC: Menu");
        leftLines.add("LEFT/RIGHT: -/+5s");
        leftLines.add("UP/DOWN: Volume");
        leftLines.add("0-9: Seek 0%-90%");
        leftLines.add("./,: Next/Prev Frame");
        leftLines.add("ENTER: Quality Select");
        if (totalSources > 1) {
            leftLines.add("TAB: Source Select");
        }

        // Right side - Metadata
        final ArrayList<String> rightLines = new ArrayList<>();
        rightLines.add("--- Metadata ---");

        if (selectedSource != null && selectedSource.metadata() != null) {
            final MRL.Metadata meta = selectedSource.metadata();
            rightLines.add("Title: " + (meta.title() != null ? meta.title() : "N/A"));
            rightLines.add("Author: " + (meta.author() != null ? meta.author() : "N/A"));
            rightLines.add("Description:");
            if (meta.description() != null) {
                // Word wrap description
                final String desc = meta.description();
                final int maxLen = 30;
                for (int i = 0; i < desc.length(); i += maxLen) {
                    rightLines.add("  " + desc.substring(i, Math.min(i + maxLen, desc.length())));
                }
            } else {
                rightLines.add("  N/A");
            }
            rightLines.add("Duration: " + (meta.duration() > 0 ? FORMAT.format(new Date(meta.duration())) : "N/A"));
            if (meta.publishedAt() != null) {
                rightLines.add("Published: " + meta.publishedAt().toString());
            }
        } else {
            rightLines.add("Unavailable");
        }

        final int lineHeight = FONT_SIZE + 5;
        final int leftStartY = 20;
        final int rightStartY = 20;
        final int leftX = 20;
        final int rightX = windowWidth - FADE_WIDTH + 20;

        // Render left side - GREEN text
        int currentY = leftStartY;
        for (final String line : leftLines) {
            renderText(line, leftX, currentY, COLOR_GREEN);
            currentY += lineHeight;
        }

        // Render right side - GREEN text
        currentY = rightStartY;
        for (final String line : rightLines) {
            renderText(line, rightX, currentY, COLOR_GREEN);
            currentY += lineHeight;
        }

        restoreProjection();
        glEnable(GL_DEPTH_TEST);
    }

    private static String getMRLStatusString(final MRL mrl) {
        if (mrl == null) return "FAILED";
        if (mrl.error()) return "FAILED";
        if (mrl.ready()) return "READY";
        if (mrl.busy()) return "BUSY";
        return "UNKNOWN";
    }

    private static void renderMenuText(final String[] lines, final int windowWidth, final int windowHeight) {
        final int lineHeight = FONT_SIZE + 8;
        final int totalTextHeight = lines.length * lineHeight;
        final int startY = (windowHeight - totalTextHeight) / 2;

        int currentY = startY;
        for (final String line : lines) {
            final int textWidth = calculateTextWidth(line);
            final int textX = (windowWidth - textWidth) / 2;
            renderText(line, textX, currentY, COLOR_GREEN);
            currentY += lineHeight;
        }
    }

    private static int calculateTextWidth(final String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (final char c : text.toCharArray()) {
            final CharTexture charTex = getCharTexture(c);
            if (charTex != null) {
                width += charTex.width;
            }
        }
        return width;
    }

    private static int[] getWindowSize() {
        final IntBuffer widthBuffer = MemoryUtil.memAllocInt(1);
        final IntBuffer heightBuffer = MemoryUtil.memAllocInt(1);
        glfwGetWindowSize(window, widthBuffer, heightBuffer);
        final int windowWidth = widthBuffer.get(0);
        final int windowHeight = heightBuffer.get(0);
        MemoryUtil.memFree(widthBuffer);
        MemoryUtil.memFree(heightBuffer);
        return new int[]{windowWidth, windowHeight};
    }

    private static void setupOrthoProjection(final int windowWidth, final int windowHeight) {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0, windowWidth, windowHeight, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
    }

    private static void restoreProjection() {
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();
    }

    private static void renderText(final String text, final int x, final int y, final Color color) {
        if (text == null || text.isEmpty()) return;

        glColor4f(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, color.getAlpha() / 255.0f);

        int currentX = x;

        for (final char c : text.toCharArray()) {
            final CharTexture charTex = getCharTexture(c);
            if (charTex == null) continue;

            glBindTexture(GL_TEXTURE_2D, charTex.textureId);

            glBegin(GL_QUADS);
            {
                glTexCoord2f(0, 0); glVertex2f(currentX, y);
                glTexCoord2f(1, 0); glVertex2f(currentX + charTex.width, y);
                glTexCoord2f(1, 1); glVertex2f(currentX + charTex.width, y + charTex.height);
                glTexCoord2f(0, 1); glVertex2f(currentX, y + charTex.height);
            }
            glEnd();

            currentX += charTex.width;
        }
    }

    private static CharTexture getCharTexture(final char c) {
        if (charTextureCache.containsKey(c)) {
            return charTextureCache.get(c);
        }

        final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g2d = img.createGraphics();

        g2d.setFont(textFont);
        final FontRenderContext frc = g2d.getFontRenderContext();
        final Rectangle2D bounds = textFont.getStringBounds(String.valueOf(c), frc);

        final int charWidth = (int) Math.ceil(bounds.getWidth());
        final int charHeight = (int) Math.ceil(bounds.getHeight());

        g2d.dispose();

        if (charWidth == 0 || charHeight == 0) {
            return null;
        }

        final BufferedImage charImage = new BufferedImage(charWidth, charHeight, BufferedImage.TYPE_INT_ARGB);
        g2d = charImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g2d.setFont(textFont);
        g2d.setColor(COLOR_WHITE);
        final int cx = -(int) bounds.getX();
        final int cy = -(int) bounds.getY();
        g2d.drawString(String.valueOf(c), cx, cy);
        g2d.dispose();

        final int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);

        final int[] pixels = new int[charWidth * charHeight];
        charImage.getRGB(0, 0, charWidth, charHeight, pixels, 0, charWidth);

        final ByteBuffer buffer = MemoryUtil.memAlloc(charWidth * charHeight * 4);
        for (int i = 0; i < charHeight; i++) {
            for (int j = 0; j < charWidth; j++) {
                final int pixel = pixels[i * charWidth + j];
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // RED
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // GREEN
                buffer.put((byte) (pixel & 0xFF));         // BLUE
                buffer.put((byte) ((pixel >> 24) & 0xFF)); // ALPHA
            }
        }
        buffer.flip();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, charWidth, charHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        MemoryUtil.memFree(buffer);

        final CharTexture charTex = new CharTexture(textureId, charWidth, charHeight);
        charTextureCache.put(c, charTex);

        return charTex;
    }

    public static void execute(final Runnable command) {
        if (command == null) throw new IllegalArgumentException("Command cannot be null");
        executor.add(command);
    }
}