package org.watermedia.bootstrap.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.openal.*;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.decode.DecoderAPI;
import org.watermedia.api.decode.Image;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.tools.DrawTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class WaterMediaApp {
    // ============================================================
    // CONSTANTS
    // ============================================================
    private static final String NAME = "WATERMeDIA: Multimedia API";
    private static final Gson GSON = new Gson();
    private static final DateFormat FORMAT = new SimpleDateFormat("HH:mm:ss");
    private static final boolean mods = new File("").getAbsoluteFile().getName().equalsIgnoreCase("mods");

    // Menu indices
    private static final int MENU_MULTIMEDIA_PLAY = 0, MENU_TOOLS_UPLOAD_LOGS = 1, MENU_TOOLS_CLEANUP = 2;
    private static final int MENU_PRESETS_PLATFORM = 3, MENU_PRESETS_FORMAT = 4;

    // UI Constants
    private static final int MENU_PADDING = 20, MENU_WIDTH = 500;
    private static final int FONT_SIZE = 24;
    private static final int DIALOG_BORDER = 3;

    // Colors
    private static final Color COLOR_YELLOW = new Color(249, 255, 80);
    private static final Color COLOR_BLUE = new Color(80, 180, 255);
    private static final Color COLOR_RED = new Color(255, 100, 100);
    private static final Color COLOR_GREEN = new Color(100, 255, 100);
    private static final Color COLOR_WHITE = Color.WHITE;
    private static final Color COLOR_GRAY = new Color(128, 128, 128);

    // Console log buffer
    private static final List<ConsoleLine> consoleLines = new CopyOnWriteArrayList<>();
    private static volatile int consoleScroll = 0;
    private static volatile String consoleTitle = "";
    private static volatile boolean consoleWaitingForKey = false;
    private static volatile Runnable consoleOnClose = null;

    // ============================================================
    // WINDOW SIZE (updated via callback)
    // ============================================================
    private static volatile int windowWidth = 1280;
    private static volatile int windowHeight = 720;

    // ============================================================
    // RUNTIME STATE
    // ============================================================
    private static final Queue<Runnable> executor = new ConcurrentLinkedQueue<>();
    private static final Map<Character, CharTexture> charTextureCache = new HashMap<>();
    private static final LinkedHashMap<String, MRL> groupMRLs = new LinkedHashMap<>();
    private static final List<TestURI> customTests = new ArrayList<>();

    private static URIGroup[] uriGroups;
    private static volatile AppState currentState = AppState.HOME_SELECTOR;
    private static volatile AppState previousState = AppState.HOME_SELECTOR;

    // Selector indices
    private static volatile int homeSelectorIndex = 0;
    private static volatile int mrlSelectorIndex = 0;
    private static volatile int sourceSelectorIndex = 0;
    private static volatile int qualityDialogIndex = 0;
    private static volatile int finishedDialogIndex = 0;

    // Selected items
    private static volatile URIGroup selectedGroup = null;
    private static volatile MRL selectedMRL = null;
    private static volatile String selectedMRLName = "";
    private static volatile MRL.Source[] availableSources;
    private static volatile MRL.Source selectedSource = null;
    private static volatile MRL.Quality[] availableQualities;
    private static volatile MRL.Quality selectedQuality = MRL.Quality.HIGHEST;

    // Dialog state
    private static volatile String finishedReason = "";
    private static volatile boolean finishedWasError = false;
    private static volatile String customUrlText = "";
    private static volatile String playerErrorMessage = "";

    // Player
    private static volatile MediaPlayer player = null;
    private static volatile boolean escPressed = false;

    // Window & rendering
    private static long window;
    private static int bannerTextureId = -1, bannerWidth = 0, bannerHeight = 0;
    private static Font textFont;
    private static int lineHeight = FONT_SIZE + 6;

    // Mouse
    private static volatile double mouseX = 0, mouseY = 0;
    private static volatile boolean mouseClicked = false;
    private static volatile List<ClickableArea> clickableAreas = new ArrayList<>();

    // Audio
    private static int soundBuffer = -1, soundSource = -1;

    static {
        FORMAT.setTimeZone(TimeZone.getTimeZone("GMT-00:00"));
        initLogging();
    }

    private static void initLogging() {
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.DEBUG);

        final AppenderComponentBuilder console = builder.newAppender("Console", "Console")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        console.add(builder.newLayout("PatternLayout")
                .addAttribute("pattern", "%highlight{[%d{HH:mm:ss}] [%t/%level] [%logger/%marker]: %msg%n}"));

        // File appender
        final AppenderComponentBuilder file = builder.newAppender("File", "File")
                .addAttribute("fileName", "logs/watermedia-app.log")
                .addAttribute("append", true);
        file.add(builder.newLayout("PatternLayout")
                .addAttribute("pattern", "[%d{HH:mm:ss}] [%t/%level] [%logger/%marker]: %msg%n"));

        builder.add(console);
        builder.add(file);
        builder.add(builder.newRootLogger(Level.DEBUG)
                .add(builder.newAppenderRef("Console"))
                .add(builder.newAppenderRef("File")));

        Configurator.initialize(builder.build());
    }

    // ============================================================
    // RECORDS
    // ============================================================

    private record TestURI(String name, String uri) {
        URI toURI() { return URI.create(this.uri); }
    }

    private record URIGroup(String name, TestURI[] uris) {}

    private record CharTexture(int textureId, int width, int height) {}

    private record ClickableArea(int x, int y, int width, int height, int index) {
        boolean contains(final double mx, final double my) {
            return mx >= this.x && mx <= this.x + this.width && my >= this.y && my <= this.y + this.height;
        }
    }

    /**
     * Configuration for dialog rendering.
     */
    private record DialogConfig(String title, Color borderColor, int width, int height, int x, int y, int padding) {}

    /**
     * Configuration for menu option rendering.
     */
    private record MenuOption(String text, boolean selected, Color selectedColor) {
        MenuOption(final String text, final boolean selected) {
            this(text, selected, COLOR_YELLOW);
        }
    }

    /**
     * Console line with color.
     */
    private record ConsoleLine(String text, Color color) { }

    // ============================================================
    // MAIN
    // ============================================================

    public static void main(final String... args) {
        WaterMedia.start("Java Test", null, null, true);
        uriGroups = GSON.fromJson(IOTool.jarRead("uris.json"), URIGroup[].class);

        init();
        glfwShowWindow(window);
        mainLoop();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).close();
        System.exit(0);
    }

    // ============================================================
    // INITIALIZATION
    // ============================================================

    private static void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, NAME, NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create the GLFW window");

        // Callbacks
        glfwSetKeyCallback(window, WaterMediaApp::handleKeyInput);
        glfwSetCursorPosCallback(window, (w, x, y) -> { mouseX = x; mouseY = y; });
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE) mouseClicked = true;
        });
        glfwSetWindowSizeCallback(window, (w, width, height) -> {
            windowWidth = width;
            windowHeight = height;
            glViewport(0, 0, width, height);
        });

        // Center window
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1);
            final IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            windowWidth = pWidth.get(0);
            windowHeight = pHeight.get(0);

            final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(window, (vidmode.width() - windowWidth) / 2, (vidmode.height() - windowHeight) / 2);
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        // Audio
        final long device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) throw new IllegalStateException("Failed to open a new Audio Device");
        final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(context);
        ALC.createCapabilities(device);
        AL.createCapabilities(ALC.createCapabilities(device));

        textFont = new Font("Consolas", Font.PLAIN, FONT_SIZE);
        calculateLineHeight();
        loadIcon();
        loadBanner();
        loadSoundClick();
    }

    private static void calculateLineHeight() {
        final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        final Graphics2D g2d = img.createGraphics();
        g2d.setFont(textFont);
        final Rectangle2D bounds = textFont.getStringBounds("Ay", g2d.getFontRenderContext());
        lineHeight = (int) Math.ceil(bounds.getHeight()) + 4;
        g2d.dispose();
    }

    private static void loadIcon() {
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            final byte[] iconBytes = in.readAllBytes();

            final Image iconImage = DecoderAPI.decodeImage(iconBytes);
            if (iconImage == null || iconImage.frames().length == 0) return;

            final ByteBuffer iconBuffer = iconImage.frames()[0];
            iconBuffer.rewind();

            final ByteBuffer buffer = MemoryUtil.memAlloc(iconImage.width() * iconImage.height() * 4);
            for (int i = 0; i < iconImage.width() * iconImage.height(); i++) {
                final byte b = iconBuffer.get(), g = iconBuffer.get(), r = iconBuffer.get(), a = iconBuffer.get();
                buffer.put(r).put(g).put(b).put(a);
            }
            buffer.flip();

            final GLFWImage.Buffer icons = GLFWImage.malloc(1);
            icons.position(0).width(iconImage.width()).height(iconImage.height()).pixels(buffer);
            glfwSetWindowIcon(window, icons);

            icons.free();
            MemoryUtil.memFree(buffer);
        } catch (final Exception e) {
            System.err.println("Failed to load window icon: " + e.getMessage());
        }
    }

    private static void loadBanner() {
        try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
            final byte[] bannerBytes = in.readAllBytes();

            final Image bannerImage = DecoderAPI.decodeImage(bannerBytes);
            if (bannerImage == null || bannerImage.frames().length == 0) return;

            bannerWidth = bannerImage.width();
            bannerHeight = bannerImage.height();
            bannerTextureId = glGenTextures();

            glBindTexture(GL_TEXTURE_2D, bannerTextureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bannerWidth, bannerHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, bannerImage.frames()[0]);
        } catch (final Exception e) {
            System.err.println("Failed to load banner: " + e.getMessage());
        }
    }

    private static void loadSoundClick() {
        try (final InputStream in = IOTool.jarOpenFile("assets/duck.ogg")) {
            final byte[] soundBytes = in.readAllBytes();

            final ByteBuffer oggBuffer = MemoryUtil.memAlloc(soundBytes.length);
            oggBuffer.put(soundBytes).flip();

            final IntBuffer channelsBuffer = MemoryUtil.memAllocInt(1);
            final IntBuffer sampleRateBuffer = MemoryUtil.memAllocInt(1);
            final ShortBuffer pcmBuffer = STBVorbis.stb_vorbis_decode_memory(oggBuffer, channelsBuffer, sampleRateBuffer);

            MemoryUtil.memFree(oggBuffer);
            if (pcmBuffer == null) {
                MemoryUtil.memFree(channelsBuffer);
                MemoryUtil.memFree(sampleRateBuffer);
                return;
            }

            final int channels = channelsBuffer.get(0);
            final int sampleRate = sampleRateBuffer.get(0);
            MemoryUtil.memFree(channelsBuffer);
            MemoryUtil.memFree(sampleRateBuffer);

            soundBuffer = alGenBuffers();
            alBufferData(soundBuffer, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, pcmBuffer, sampleRate);
            MemoryUtil.memFree(pcmBuffer);

            soundSource = alGenSources();
            alSourcei(soundSource, AL_BUFFER, soundBuffer);
            alSourcef(soundSource, AL_GAIN, 0.2f);
        } catch (final Exception e) {
            System.err.println("Failed to load sound effect: " + e.getMessage());
        }
    }

    private static void playSelectionSound() {
        if (soundSource > 0) {
            alSourceStop(soundSource);
            alSourcePlay(soundSource);
        }
    }

    // ============================================================
    // SELECTOR INDEX UPDATES (with sound)
    // ============================================================

    private static void updateIndex(final IntSupplier getter, final IntConsumer setter, final int newIndex) {
        if (getter.getAsInt() != newIndex) {
            setter.accept(newIndex);
            playSelectionSound();
        }
    }

    // ============================================================
    // INPUT HANDLING
    // ============================================================

    private static void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        if (action != GLFW_RELEASE) return;

        switch (currentState) {
            case HOME_SELECTOR -> handleSelectorKeys(key, WaterMediaApp::getHomeSelectorCount,
                    () -> homeSelectorIndex, i -> homeSelectorIndex = i, WaterMediaApp::selectHomeOption,
                    () -> glfwSetWindowShouldClose(window, true));
            case MRL_SELECTOR -> handleSelectorKeys(key, () -> selectedGroup.uris().length + 1,
                    () -> mrlSelectorIndex, i -> mrlSelectorIndex = i, WaterMediaApp::selectMRL,
                    () -> { currentState = AppState.HOME_SELECTOR; selectedGroup = null; groupMRLs.clear(); });
            case SOURCE_SELECTOR -> handleSelectorKeys(key, () -> availableSources.length + 1,
                    () -> sourceSelectorIndex, i -> sourceSelectorIndex = i, WaterMediaApp::selectSource,
                    () -> { currentState = AppState.MRL_SELECTOR; availableSources = null; });
            case PLAYER_RUNNING -> handlePlayerKeys(key);
            case DIALOG_QUALITY -> handleSelectorKeys(key, () -> availableQualities.length,
                    () -> qualityDialogIndex, i -> qualityDialogIndex = i, WaterMediaApp::selectQuality,
                    () -> currentState = AppState.PLAYER_RUNNING);
            case DIALOG_FINISHED -> handleFinishedDialogKeys(key);
            case DIALOG_OPEN_MULTIMEDIA -> handleOpenMultimediaDialogKeys(key);
            case DIALOG_PLAYER_ERROR -> handlePlayerErrorDialogKeys(key);
            case CONSOLE -> handleConsoleKeys(key);
        }
    }

    private static void handleSelectorKeys(final int key, final IntSupplier totalCount, final IntSupplier indexGetter,
                                           final IntConsumer indexSetter, final Runnable onSelect, final Runnable onEscape) {
        final int total = totalCount.getAsInt();
        switch (key) {
            case GLFW_KEY_UP -> {
                int newIndex = indexGetter.getAsInt() - 1;
                if (newIndex < 0) newIndex = total - 1;
                updateIndex(indexGetter, indexSetter, newIndex);
            }
            case GLFW_KEY_DOWN -> {
                int newIndex = indexGetter.getAsInt() + 1;
                if (newIndex >= total) newIndex = 0;
                updateIndex(indexGetter, indexSetter, newIndex);
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> onSelect.run();
            case GLFW_KEY_ESCAPE -> onEscape.run();
        }
    }

    private static void handlePlayerKeys(final int key) {
        if (player == null) return;

        switch (key) {
            case GLFW_KEY_ESCAPE -> { escPressed = true; stopPlayerAndReturn(); }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> openQualityDialog();
            case GLFW_KEY_SPACE -> player.togglePlay();
            case GLFW_KEY_LEFT -> player.rewind();
            case GLFW_KEY_RIGHT -> player.foward();
            case GLFW_KEY_S -> player.stop();
            case GLFW_KEY_UP -> player.volume(player.volume() + 5);
            case GLFW_KEY_DOWN -> player.volume(player.volume() - 5);
            case GLFW_KEY_PERIOD -> player.nextFrame();
            case GLFW_KEY_COMMA -> player.previousFrame();
            case GLFW_KEY_N -> navigateSource(1);
            case GLFW_KEY_B -> navigateSource(-1);
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

    private static void handleFinishedDialogKeys(final int key) {
        switch (key) {
            case GLFW_KEY_LEFT -> updateIndex(() -> finishedDialogIndex, i -> finishedDialogIndex = i, 0);
            case GLFW_KEY_RIGHT -> updateIndex(() -> finishedDialogIndex, i -> finishedDialogIndex = i, 1);
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (finishedDialogIndex == 0) startPlayer();
                else returnToAppropriateMenu();
            }
            case GLFW_KEY_ESCAPE -> returnToAppropriateMenu();
        }
    }

    private static void handleOpenMultimediaDialogKeys(final int key) {
        switch (key) {
            case GLFW_KEY_SPACE -> { if (!customUrlText.isEmpty()) playCustomUrl(); }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> { if (!customUrlText.isEmpty()) addToCustomTests(); }
            case GLFW_KEY_ESCAPE -> { glfwSetWindowTitle(window, NAME); currentState = AppState.HOME_SELECTOR; }
            case GLFW_KEY_V -> {
                if (glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS ||
                        glfwGetKey(window, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS) {
                    pasteFromClipboard();
                    updateWindowTitleWithUrl();
                }
            }
        }
    }

    private static void handlePlayerErrorDialogKeys(final int key) {
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_ESCAPE) {
            closePlayerErrorDialog();
        }
    }

    private static void closePlayerErrorDialog() {
        playerErrorMessage = "";
        glfwSetWindowTitle(window, NAME);
        currentState = (previousState == AppState.DIALOG_OPEN_MULTIMEDIA || previousState == AppState.HOME_SELECTOR)
                ? AppState.HOME_SELECTOR : previousState;
    }

    // ============================================================
    // MOUSE HANDLING
    // ============================================================

    private static void processMouseHover() {
        for (final ClickableArea area: clickableAreas) {
            if (area.contains(mouseX, mouseY)) {
                switch (currentState) {
                    case HOME_SELECTOR -> updateIndex(() -> homeSelectorIndex, i -> homeSelectorIndex = i, area.index);
                    case MRL_SELECTOR -> updateIndex(() -> mrlSelectorIndex, i -> mrlSelectorIndex = i, area.index);
                    case SOURCE_SELECTOR -> updateIndex(() -> sourceSelectorIndex, i -> sourceSelectorIndex = i, area.index);
                    case DIALOG_QUALITY -> updateIndex(() -> qualityDialogIndex, i -> qualityDialogIndex = i, area.index);
                    case DIALOG_FINISHED -> updateIndex(() -> finishedDialogIndex, i -> finishedDialogIndex = i, area.index);
                }
                break;
            }
        }
    }

    private static void processMouseClick() {
        if (!mouseClicked) return;
        mouseClicked = false;

        for (final ClickableArea area: clickableAreas) {
            if (area.contains(mouseX, mouseY)) {
                switch (currentState) {
                    case HOME_SELECTOR -> { homeSelectorIndex = area.index; selectHomeOption(); }
                    case MRL_SELECTOR -> { mrlSelectorIndex = area.index; selectMRL(); }
                    case SOURCE_SELECTOR -> { sourceSelectorIndex = area.index; selectSource(); }
                    case DIALOG_QUALITY -> { qualityDialogIndex = area.index; selectQuality(); }
                    case DIALOG_FINISHED -> {
                        finishedDialogIndex = area.index;
                        if (finishedDialogIndex == 0) startPlayer();
                        else returnToAppropriateMenu();
                    }
                    case DIALOG_PLAYER_ERROR -> closePlayerErrorDialog();
                }
                break;
            }
        }
    }

    // ============================================================
    // SELECTION ACTIONS
    // ============================================================

    private static int getHomeSelectorCount() {
        return customTests.isEmpty() ? 6 : 7;
    }

    private static void selectHomeOption() {
        final boolean hasCustom = !customTests.isEmpty();
        final int customIdx = hasCustom ? 5 : -1;
        final int exitIdx = hasCustom ? 6 : 5;

        if (homeSelectorIndex == MENU_MULTIMEDIA_PLAY) openMultimediaDialog();
        else if (homeSelectorIndex == MENU_TOOLS_UPLOAD_LOGS) openUploadLogs();
        else if (homeSelectorIndex == MENU_TOOLS_CLEANUP) openCleanup();
        else if (homeSelectorIndex == MENU_PRESETS_PLATFORM) selectPresetGroup(0);
        else if (homeSelectorIndex == MENU_PRESETS_FORMAT) selectPresetGroup(1);
        else if (hasCustom && homeSelectorIndex == customIdx) selectCustomTestsGroup();
        else if (homeSelectorIndex == exitIdx) glfwSetWindowShouldClose(window, true);
    }

    private static void selectPresetGroup(final int groupIndex) {
        if (groupIndex >= uriGroups.length) return;
        openGroupSelector(uriGroups[groupIndex]);
    }

    private static void selectCustomTestsGroup() {
        if (customTests.isEmpty()) return;
        openGroupSelector(new URIGroup("Custom Tests", customTests.toArray(new TestURI[0])));
    }

    private static void openGroupSelector(final URIGroup group) {
        selectedGroup = group;
        mrlSelectorIndex = 0;
        groupMRLs.clear();
        for (final TestURI testUri: group.uris()) {
            groupMRLs.put(testUri.name(), MRL.get(testUri.uri()));
        }
        currentState = AppState.MRL_SELECTOR;
    }

    private static void openMultimediaDialog() {
        pasteFromClipboard();
        previousState = AppState.HOME_SELECTOR;
        currentState = AppState.DIALOG_OPEN_MULTIMEDIA;
    }

    // ============================================================
    // CONSOLE SYSTEM
    // ============================================================

    private static void openConsole(final String title, final Runnable onClose) {
        consoleLines.clear();
        consoleScroll = 0;
        consoleTitle = title;
        consoleWaitingForKey = false;
        consoleOnClose = onClose;
        currentState = AppState.CONSOLE;
    }

    private static void consolePrint(final String text) {
        consolePrint(text, COLOR_WHITE);
    }

    private static void consolePrint(final String text, final Color color) {
        consoleLines.add(new ConsoleLine(text, color));
        // Auto-scroll to bottom
        final int maxVisible = (windowHeight - 150) / (lineHeight - 4);
        if (consoleLines.size() > maxVisible) {
            consoleScroll = consoleLines.size() - maxVisible;
        }
    }

    private static void consoleSuccess(final String text) {
        consolePrint(text, COLOR_GREEN);
    }

    private static void consoleError(final String text) {
        consolePrint(text, COLOR_RED);
    }

    private static void consoleInfo(final String text) {
        consolePrint(text, COLOR_BLUE);
    }

    private static void consoleWaitForKey() {
        consolePrint("");
        consolePrint("Press any key to continue...", COLOR_YELLOW);
        consoleWaitingForKey = true;
    }

    private static void handleConsoleKeys(final int key) {
        if (consoleWaitingForKey) {
            consoleWaitingForKey = false;
            if (consoleOnClose != null) {
                consoleOnClose.run();
            } else {
                currentState = AppState.HOME_SELECTOR;
            }
            return;
        }

        final int maxVisible = (windowHeight - 150) / (lineHeight - 4);
        switch (key) {
            case GLFW_KEY_UP -> consoleScroll = Math.max(0, consoleScroll - 1);
            case GLFW_KEY_DOWN -> consoleScroll = Math.min(Math.max(0, consoleLines.size() - maxVisible), consoleScroll + 1);
            case GLFW_KEY_PAGE_UP -> consoleScroll = Math.max(0, consoleScroll - maxVisible);
            case GLFW_KEY_PAGE_DOWN -> consoleScroll = Math.min(Math.max(0, consoleLines.size() - maxVisible), consoleScroll + maxVisible);
            case GLFW_KEY_ESCAPE -> {
                if (consoleOnClose != null) consoleOnClose.run();
                else currentState = AppState.HOME_SELECTOR;
            }
        }
    }

    private static void renderConsole() {
        DrawTool.setupOrtho(windowWidth, windowHeight);

        // Background
        DrawTool.disableTextures();
        DrawTool.fill(0, 0, windowWidth, windowHeight, 0.05f, 0.05f, 0.1f, 1f);
        DrawTool.enableTextures();

        // Title bar
        DrawTool.disableTextures();
        DrawTool.fill(0, 0, windowWidth, 40, 0.1f, 0.1f, 0.2f, 1f);
        DrawTool.enableTextures();
        renderText(consoleTitle, MENU_PADDING, 10, COLOR_BLUE);

        // Console content
        final int startY = 50;
        final int lineH = lineHeight - 4;
        final int maxVisible = (windowHeight - 150) / lineH;

        for (int i = 0; i < maxVisible && (i + consoleScroll) < consoleLines.size(); i++) {
            final ConsoleLine line = consoleLines.get(i + consoleScroll);
            renderText(line.text, MENU_PADDING, startY + i * lineH, line.color);
        }

        // Scroll indicator
        if (consoleLines.size() > maxVisible) {
            final String scrollInfo = "Lines " + (consoleScroll + 1) + "-" + Math.min(consoleScroll + maxVisible, consoleLines.size()) + " of " + consoleLines.size();
            renderText(scrollInfo, windowWidth - calculateTextWidth(scrollInfo) - MENU_PADDING, 10, COLOR_GRAY);
        }

        DrawTool.restoreProjection();
    }

    // ============================================================
    // UPLOAD LOGS
    // ============================================================

    private static void openUploadLogs() {
        if (!mods) {
            showPlayerError("Upload logs is only available\nwhen running in Minecraft context.\n\nPlace this JAR in the mods folder.");
            return;
        }

        openConsole("Upload Log Files", () -> currentState = AppState.HOME_SELECTOR);

        ThreadTool.createStarted("UploadLogs", () -> {
            try {
                performUploadLogs();
            } catch (final Exception e) {
                consoleError("Unexpected error: " + e.getMessage());
                consoleWaitForKey();
            }
        });
    }

    private static void performUploadLogs() {
        consoleInfo("=== Upload Log Files ===");
        consolePrint("");

        final Path cwd = Path.of("").toAbsolutePath();
        final Path logsDir = cwd.getParent().resolve("logs");
        final Path crashDir = cwd.getParent().resolve("crash-reports");

        consolePrint("Working directory: " + cwd);
        consolePrint("Logs directory: " + logsDir);
        consolePrint("");

        // Find files
        final Path latestLog = logsDir.resolve("latest.log");
        final Path wmLog = logsDir.resolve("watermedia-app.log");
        final Path crashReport = findLatestCrashReport(crashDir);

        // Read files
        consoleInfo("--- Reading files ---");
        final String latestContent = readFileStatus(latestLog, "latest.log");
        final String wmContent = readFileStatus(wmLog, "watermedia-app.log");
        final String crashContent = crashReport != null ? readFileStatus(crashReport, crashReport.getFileName().toString()) : null;

        if (crashReport == null) {
            consolePrint("crash-reports: No crash reports found", COLOR_GRAY);
        }

        consolePrint("");

        // Upload to mclo.gs
        consoleInfo("--- Uploading to mclo.gs ---");
        String latestUrl = null, wmUrl = null, crashUrl = null;

        if (latestContent != null) {
            latestUrl = uploadToMclogs(latestContent, "latest.log");
        }
        if (wmContent != null) {
            wmUrl = uploadToMclogs(wmContent, "watermedia-app.log");
        }
        if (crashContent != null) {
            crashUrl = uploadToMclogs(crashContent, crashReport.getFileName().toString());
        }

        consolePrint("");

        // Generate issue template
        if (latestUrl != null || wmUrl != null) {
            consoleInfo("--- Generating issue report ---");
            final String issueText = generateIssueTemplate(latestUrl, wmUrl, crashUrl);

            // Copy to clipboard
            try {
                final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(issueText), null);
                consoleSuccess("Issue template copied to clipboard!");
            } catch (final Exception e) {
                consoleError("Failed to copy to clipboard: " + e.getMessage());
            }

            consolePrint("");
            consolePrint("Opening GitHub issues page...", COLOR_YELLOW);

            // Open browser
            try {
                Desktop.getDesktop().browse(URI.create("https://github.com/WaterMediaTeam/watermedia/issues/new"));
                consoleSuccess("Browser opened!");
            } catch (final Exception e) {
                consoleError("Failed to open browser: " + e.getMessage());
                consolePrint("Please manually go to: https://github.com/WaterMediaTeam/watermedia/issues/new");
            }
        } else {
            consoleError("No files were uploaded successfully.");
        }

        consoleWaitForKey();
    }

    private static Path findLatestCrashReport(final Path crashDir) {
        if (!Files.exists(crashDir)) return null;

        try (final var stream = Files.list(crashDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".txt"))
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (final Exception e) { return 0L; }
                    }))
                    .orElse(null);
        } catch (final Exception e) {
            return null;
        }
    }

    private static String readFileStatus(final Path path, final String name) {
        if (!Files.exists(path)) {
            consolePrint(name + ": NOT FOUND", COLOR_RED);
            return null;
        }

        try {
            final String content = Files.readString(path, StandardCharsets.UTF_8);
            consoleSuccess(name + ": Read OK (" + content.length() + " bytes)");
            return content;
        } catch (final Exception e) {
            consoleError(name + ": FAILED TO READ - " + e.getMessage());
            return null;
        }
    }

    private static String uploadToMclogs(final String content, final String name) {
        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mclo.gs/1/log"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("content=" + java.net.URLEncoder.encode(content, StandardCharsets.UTF_8)))
                    .build();

            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                final JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                if (json.get("success").getAsBoolean()) {
                    final String url = json.get("url").getAsString();
                    consoleSuccess(name + ": Uploaded -> " + url);
                    return url;
                }
            }
            consoleError(name + ": Upload failed (HTTP " + response.statusCode() + ")");
        } catch (final Exception e) {
            consoleError(name + ": Upload failed - " + e.getMessage());
        }
        return null;
    }

    private static String generateIssueTemplate(final String latestUrl, final String wmUrl, final String crashUrl) {
        final StringBuilder sb = new StringBuilder();
        sb.append("This is an automated issue report generated by WATERMeDIA: Multimedia API using the tooling to find and upload.\n\n");
        sb.append("## Files\n");
        sb.append("- Logs: ").append(latestUrl != null ? latestUrl : "N/A").append("\n");
        sb.append("- Crash-report: ").append(crashUrl != null ? crashUrl : "N/A").append("\n");
        sb.append("- WM Logs: ").append(wmUrl != null ? wmUrl : "N/A").append("\n\n");
        sb.append("## System Properties\n");
        sb.append("- OS: ").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version")).append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("- Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("- Java Home: ").append(System.getProperty("java.home")).append("\n");
        sb.append("- User Dir: ").append(System.getProperty("user.dir")).append("\n");
        sb.append("- FFmpeg Loaded: ").append(FFMediaPlayer.loaded()).append("\n");
        return sb.toString();
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    private static void openCleanup() {
        openConsole("Cleanup", () -> glfwSetWindowShouldClose(window, true));

        new Thread(() -> {
            try {
                performCleanup();
            } catch (final Exception e) {
                consoleError("Unexpected error: " + e.getMessage());
                consoleWaitForKey();
            }
        }, "Cleanup").start();
    }

    private static void performCleanup() {
        consoleInfo("=== Cleanup ===");
        consolePrint("");

        final Path wmPath = WaterMedia.cwd();
        final Path tmpDir = WaterMedia.tmp();

        consolePrint("WaterMedia path: " + wmPath);
        consolePrint("");

        // Cleanup tmp folder
        consoleInfo("--- Cleaning tmp folder ---");

        final var dirs = tmpDir.toFile().listFiles();
        if (dirs == null) {
            consolePrint("No tmp folder found", COLOR_GRAY);
            consolePrint("");
            consoleInfo("--- Cleanup complete ---");
            consolePrint("Application will close after this.");
            consoleWaitForKey();
            return;
        }

        final int tmpCount = IOTool.count(tmpDir.toFile());

        final int tmpDeleted = IOTool.delete(tmpDir.toFile());
        if (tmpDeleted > 0) {
            if (tmpDeleted == tmpCount) {
                consoleSuccess("Tmp folder cleaned successfully " + "(" + tmpDeleted + " items removed)");
            } else {
                consoleSuccess("Tmp folder deleted almost successfully " + "(" + tmpDeleted + " items removed - " + (tmpCount - tmpDeleted) + " items failed)");
            }
        } else {
            consoleError("Failed to delete tmp folder");
        }

        final int tmpDeleteScheduled = IOTool.deleteSchedule(tmpDir.toFile());
        if (tmpDeleteScheduled > 0) {
            consoleInfo("Additional " + tmpDeleteScheduled + " items scheduled for deletion on exit.");
        } else {
            consolePrint("No additional items scheduled for deletion.", COLOR_YELLOW);
        }

        consolePrint("");
        consoleInfo("--- Cleanup complete ---");
        consolePrint("Application will close after this.");
        consoleWaitForKey();
    }

    private static void pasteFromClipboard() {
        try {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                customUrlText = ((String) clipboard.getData(DataFlavor.stringFlavor)).trim();
            }
        } catch (final Exception e) {
            customUrlText = "";
        }
    }

    private static void updateWindowTitleWithUrl() {
        if (customUrlText == null || customUrlText.isEmpty()) {
            glfwSetWindowTitle(window, NAME + " - (clipboard empty)");
        } else {
            final String display = customUrlText.length() > 80 ? customUrlText.substring(0, 77) + "..." : customUrlText;
            glfwSetWindowTitle(window, NAME + " - " + display);
        }
    }

    private static void addToCustomTests() {
        if (customUrlText == null || customUrlText.isEmpty()) return;
        final String name = customUrlText.length() > 40 ? customUrlText.substring(0, 37) + "..." : customUrlText;
        customTests.add(new TestURI(name, customUrlText));
        glfwSetWindowTitle(window, NAME + " - Added to Custom Tests!");
        customUrlText = "";
        currentState = AppState.HOME_SELECTOR;
    }

    private static void playCustomUrl() {
        if (customUrlText == null || customUrlText.isEmpty()) return;

        try {
            selectedMRL = MRL.get(customUrlText);
            selectedMRLName = "Custom URL";
            selectedGroup = null;

            final long startTime = System.currentTimeMillis();
            while (!selectedMRL.ready() && !selectedMRL.error() && (System.currentTimeMillis() - startTime) < 10000) {
                Thread.sleep(100);
            }

            if (selectedMRL.error() || !selectedMRL.ready()) {
                showPlayerError("Failed to load URL: " + (selectedMRL.error() ? "Error" : "Timeout"));
                selectedMRL = null;
                return;
            }

            availableSources = selectedMRL.sources();
            if (availableSources == null || availableSources.length == 0) {
                showPlayerError("No sources available for this URL");
                selectedMRL = null;
                return;
            }

            sourceSelectorIndex = 0;
            selectedSource = availableSources[0];
            glfwSetWindowTitle(window, NAME);
            startPlayer();
        } catch (final Exception e) {
            showPlayerError("Invalid URL: " + e.getMessage());
            selectedMRL = null;
        }
    }

    private static void showPlayerError(final String message) {
        playerErrorMessage = message;
        previousState = currentState;
        currentState = AppState.DIALOG_PLAYER_ERROR;
    }

    private static void selectMRL() {
        if (selectedGroup == null) return;
        if (mrlSelectorIndex >= selectedGroup.uris().length) {
            currentState = AppState.HOME_SELECTOR;
            selectedGroup = null;
            groupMRLs.clear();
            return;
        }

        final TestURI testUri = selectedGroup.uris()[mrlSelectorIndex];
        selectedMRLName = testUri.name();
        selectedMRL = groupMRLs.get(selectedMRLName);

        if (selectedMRL == null || !selectedMRL.ready()) return;

        availableSources = selectedMRL.sources();
        if (availableSources == null || availableSources.length == 0) return;

        if (availableSources.length == 1) {
            sourceSelectorIndex = 0;
            selectedSource = availableSources[0];
            startPlayer();
        } else {
            sourceSelectorIndex = 0;
            currentState = AppState.SOURCE_SELECTOR;
        }
    }

    private static void selectSource() {
        if (availableSources == null) return;
        if (sourceSelectorIndex >= availableSources.length) {
            currentState = AppState.MRL_SELECTOR;
            availableSources = null;
            return;
        }
        selectedSource = availableSources[sourceSelectorIndex];
        startPlayer();
    }

    private static void selectQuality() {
        if (availableQualities == null || qualityDialogIndex >= availableQualities.length) return;
        selectedQuality = availableQualities[qualityDialogIndex];
        if (player != null) player.setQuality(selectedQuality);
        currentState = AppState.PLAYER_RUNNING;
    }

    // ============================================================
    // PLAYER CONTROL
    // ============================================================

    private static void startPlayer() {
        if (selectedMRL == null || selectedSource == null) return;

        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }

        player = selectedMRL.createPlayer(sourceSelectorIndex, Thread.currentThread(),
                WaterMediaApp::execute, null, null, true, true);

        if (player == null) {
            showPlayerError("WaterMedia failed to create media player.\nNo compatible player engine available.");
            return;
        }

        player.setQuality(selectedQuality);
        player.start();
        currentState = AppState.PLAYER_RUNNING;
        escPressed = false;
    }

    private static void stopPlayerAndReturn() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        returnToAppropriateMenu();
    }

    private static void returnToAppropriateMenu() {
        if (selectedMRL == null || selectedGroup == null) {
            glfwSetWindowTitle(window, NAME);
            currentState = AppState.HOME_SELECTOR;
        } else if (availableSources != null && availableSources.length > 1) {
            currentState = AppState.SOURCE_SELECTOR;
        } else {
            currentState = AppState.MRL_SELECTOR;
        }
        finishedReason = "";
        finishedWasError = false;
    }

    private static void navigateSource(final int delta) {
        if (availableSources == null || availableSources.length <= 1) return;
        final int newIndex = (sourceSelectorIndex + delta + availableSources.length) % availableSources.length;
        if (newIndex != sourceSelectorIndex) {
            sourceSelectorIndex = newIndex;
            selectedSource = availableSources[sourceSelectorIndex];
            startPlayer();
        }
    }

    private static void openQualityDialog() {
        if (selectedSource == null) return;
        final Set<MRL.Quality> qualities = selectedSource.availableQualities();
        if (qualities == null || qualities.isEmpty()) return;

        availableQualities = qualities.toArray(new MRL.Quality[0]);
        Arrays.sort(availableQualities, Comparator.comparingInt(q -> q.threshold));
        qualityDialogIndex = 0;

        for (int i = 0; i < availableQualities.length; i++) {
            if (availableQualities[i] == selectedQuality) {
                qualityDialogIndex = i;
                break;
            }
        }
        currentState = AppState.DIALOG_QUALITY;
    }

    private static void transitionToFinished(final String reason, final boolean isError) {
        if (escPressed) {
            escPressed = false;
            returnToAppropriateMenu();
            return;
        }

        finishedReason = reason;
        finishedWasError = isError;
        finishedDialogIndex = isError ? 1 : 0;

        if (player != null) {
            player.release();
            player = null;
        }
        currentState = AppState.DIALOG_FINISHED;
        playSelectionSound();
    }

    private static void seekToPercentage(final int percentage) {
        if (player == null || player.duration() <= 0) return;
        player.seek((player.duration() * percentage) / 100);
    }

    public static void execute(final Runnable command) {
        if (command == null) throw new IllegalArgumentException("Command cannot be null");
        executor.add(command);
    }

    // ============================================================
    // MAIN LOOP
    // ============================================================

    private static void mainLoop() {
        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {}, 0);

        glClearColor(0, 0, 0, 1);
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        while (!glfwWindowShouldClose(window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            clickableAreas = new ArrayList<>();

            switch (currentState) {
                case HOME_SELECTOR -> renderHomeSelector();
                case MRL_SELECTOR -> renderMrlSelector();
                case SOURCE_SELECTOR -> renderSourceSelector();
                case PLAYER_RUNNING -> renderPlayer();
                case DIALOG_QUALITY -> { renderPlayer(); renderQualityDialog(); }
                case DIALOG_FINISHED -> { renderBackgroundMenu(); renderFinishedDialog(); }
                case DIALOG_OPEN_MULTIMEDIA -> renderOpenMultimediaDialog();
                case DIALOG_PLAYER_ERROR -> { renderBackgroundForError(); renderPlayerErrorDialog(); }
                case CONSOLE -> renderConsole();
            }

            renderBottomBar();
            int level = 0;
            if (!FFMediaPlayer.loaded()) renderAlert("! FFMPEG UNAVAILABLE", level++);
            if (!mods) renderAlert("! NO MINECRAFT CONTEXT", level++);

            glfwSwapBuffers(window);
            glfwPollEvents();
            processMouseHover();
            processMouseClick();

            while (!executor.isEmpty()) executor.poll().run();
        }

        if (player != null) {
            player.stop();
            player.release();
        }
    }

    private static void renderBackgroundMenu() {
        if (availableSources != null && availableSources.length > 1) renderSourceSelector();
        else if (selectedGroup != null) renderMrlSelector();
        else renderHomeSelector();
    }

    private static void renderBackgroundForError() {
        if (previousState == AppState.DIALOG_OPEN_MULTIMEDIA) renderOpenMultimediaDialog();
        else renderBackgroundMenu();
    }

    // ============================================================
    // RENDERING - BOTTOM BAR (always visible)
    // ============================================================

    private static void renderBottomBar() {
        DrawTool.setupOrtho(windowWidth, windowHeight);

        // Seekbar
        final int seekbarY = windowHeight - 82;
        float progress = 1f;
        if (player != null && player.duration() > 0) {
            progress = Math.min(1f, Math.max(0f, (float) player.time() / player.duration()));
        }

        DrawTool.disableTextures();
        DrawTool.fill(0, seekbarY, windowWidth, 4, 0.15f, 0.15f, 0.15f, 1f);
        DrawTool.fill(0, seekbarY, windowWidth * progress, 4, 0.31f, 0.71f, 1f, 1f);
        DrawTool.enableTextures();

        // Instructions
        renderText(getInstructionsText(), MENU_PADDING, windowHeight - 60, COLOR_GRAY);

        DrawTool.restoreProjection();
    }

    private static String getInstructionsText() {
        return switch (currentState) {
            case HOME_SELECTOR -> "UP/DOWN: Navigate | ENTER: Select | ESC: Exit";
            case MRL_SELECTOR, SOURCE_SELECTOR -> "UP/DOWN: Navigate | ENTER: Select | ESC: Back";
            case PLAYER_RUNNING -> (availableSources != null && availableSources.length > 1)
                    ? "SPACE: Play/Pause | ESC: Menu | Arrows: Seek/Vol | ENTER: Quality | N/B: Source"
                    : "SPACE: Play/Pause | ESC: Menu | Arrows: Seek/Vol | ENTER: Quality | 0-9: Jump";
            case DIALOG_QUALITY -> "UP/DOWN: Navigate | ENTER: Select | ESC: Cancel";
            case DIALOG_FINISHED -> "LEFT/RIGHT: Select | ENTER: Confirm | ESC: Close";
            case DIALOG_OPEN_MULTIMEDIA -> "SPACE: Play | ENTER: Add to Tests | Ctrl+V: Refresh | ESC: Cancel";
            case DIALOG_PLAYER_ERROR -> "ENTER/ESC: Close";
            case CONSOLE -> consoleWaitingForKey ? "Press any key to continue..." : "UP/DOWN: Scroll | PgUp/PgDn: Fast Scroll | ESC: Close";
        };
    }

    // ============================================================
    // RENDERING - MENUS (modular)
    // ============================================================

    private static void renderHomeSelector() {
        DrawTool.setupOrtho(windowWidth, windowHeight);
        int y = renderBanner(MENU_PADDING) + 10;

        final boolean hasCustom = !customTests.isEmpty();
        final int customIdx = hasCustom ? 5 : -1;
        final int exitIdx = hasCustom ? 6 : 5;

        // Multimedia section
        y = renderMenuSection("Multimedia", y,
                new MenuOption("Play", homeSelectorIndex == MENU_MULTIMEDIA_PLAY));

        // Tools section
        y = renderMenuSection("Tools", y,
                new MenuOption("Upload your log files", homeSelectorIndex == MENU_TOOLS_UPLOAD_LOGS),
                new MenuOption("Cleanup", homeSelectorIndex == MENU_TOOLS_CLEANUP));

        // Presets section
        final List<MenuOption> presetOptions = new ArrayList<>();
        presetOptions.add(new MenuOption("Platform Tests", homeSelectorIndex == MENU_PRESETS_PLATFORM));
        presetOptions.add(new MenuOption("Format Tests", homeSelectorIndex == MENU_PRESETS_FORMAT));
        if (hasCustom) {
            presetOptions.add(new MenuOption("Custom Tests (" + customTests.size() + ")", homeSelectorIndex == customIdx));
        }
        y = renderMenuSection("Test URL Presets", y, presetOptions.toArray(new MenuOption[0]));

        // Exit (anchored to bottom)
        final int exitY = windowHeight - 82 - lineHeight - 15;
        renderMenuOption("[EXIT]", exitY, exitIdx, homeSelectorIndex == exitIdx, COLOR_RED);

        DrawTool.restoreProjection();
    }

    private static void renderMrlSelector() {
        if (selectedGroup == null) return;

        DrawTool.setupOrtho(windowWidth, windowHeight);
        int y = renderBanner(MENU_PADDING) + 10;

        renderText("Select Media - " + selectedGroup.name(), MENU_PADDING, y, COLOR_BLUE);
        y += lineHeight + 10;

        final TestURI[] uris = selectedGroup.uris();
        for (int i = 0; i < uris.length; i++) {
            final MRL mrl = groupMRLs.get(uris[i].name());
            final String status = getMRLStatusString(mrl);
            final int sourceCount = mrl != null && mrl.ready() ? mrl.sourceCount() : 0;
            final String name = padOrTruncate(uris[i].name(), 35);
            final String text = (i == mrlSelectorIndex ? "> " : "  ") + (i + 1) + ". " + name + " [" + sourceCount + "] [" + status + "]";
            final boolean isError = mrl != null && mrl.error();
            final Color color = i == mrlSelectorIndex ? (isError ? COLOR_RED : COLOR_YELLOW) : (isError ? COLOR_RED : COLOR_GRAY);

            renderText(text, MENU_PADDING, y, color);
            clickableAreas.add(new ClickableArea(MENU_PADDING, y, Math.max(calculateTextWidth(text), MENU_WIDTH), lineHeight, i));
            y += lineHeight;
        }

        y += 10;
        renderMenuOption("[BACK]", y, uris.length, mrlSelectorIndex == uris.length, COLOR_RED);

        DrawTool.restoreProjection();
    }

    private static void renderSourceSelector() {
        if (availableSources == null) return;

        DrawTool.setupOrtho(windowWidth, windowHeight);
        int y = renderBanner(MENU_PADDING) + 10;

        renderText("Select Source - " + selectedMRLName, MENU_PADDING, y, COLOR_BLUE);
        y += lineHeight + 10;

        for (int i = 0; i < availableSources.length; i++) {
            final MRL.Source src = availableSources[i];
            final MRL.Quality bestQuality = src.availableQualities().stream()
                    .max(Comparator.comparingInt(q -> q.threshold)).orElse(MRL.Quality.UNKNOWN);
            final String title = padOrTruncate(src.metadata() != null && src.metadata().title() != null ? src.metadata().title() : "Untitled", 35);
            final String text = (i == sourceSelectorIndex ? "> " : "  ") + (i + 1) + ". " + title + " [" + bestQuality.name() + "] [" + src.type().name() + "]";

            renderText(text, MENU_PADDING, y, i == sourceSelectorIndex ? COLOR_YELLOW : COLOR_GRAY);
            clickableAreas.add(new ClickableArea(MENU_PADDING, y, Math.max(calculateTextWidth(text), MENU_WIDTH), lineHeight, i));
            y += lineHeight;
        }

        y += 10;
        renderMenuOption("[BACK]", y, availableSources.length, sourceSelectorIndex == availableSources.length, COLOR_BLUE);

        DrawTool.restoreProjection();
    }

    private static int renderMenuSection(final String title, final int startY, final MenuOption... options) {
        renderText(title, MENU_PADDING, startY, COLOR_BLUE);
        int y = startY + lineHeight;
        int index = switch (title) {
            case "Multimedia" -> MENU_MULTIMEDIA_PLAY;
            case "Tools" -> MENU_TOOLS_UPLOAD_LOGS;
            case "Test URL Presets" -> MENU_PRESETS_PLATFORM;
            default -> 0;
        };

        for (final MenuOption opt : options) {
            final String text = (opt.selected ? "> " : "  ") + opt.text;
            final Color color = opt.selected ? opt.selectedColor : COLOR_GRAY;
            renderText(text, MENU_PADDING + 20, y, color);
            clickableAreas.add(new ClickableArea(MENU_PADDING, y, Math.max(calculateTextWidth(text) + 20, MENU_WIDTH), lineHeight, index++));
            y += lineHeight;
        }
        return y + 10;
    }

    private static void renderMenuOption(final String text, final int y, final int index, final boolean selected, final Color selectedColor) {
        final String displayText = (selected ? "> " : "  ") + text;
        renderText(displayText, MENU_PADDING, y, selected ? selectedColor : COLOR_GRAY);
        clickableAreas.add(new ClickableArea(MENU_PADDING, y, Math.max(calculateTextWidth(displayText), MENU_WIDTH), lineHeight, index));
    }

    // ============================================================
    // RENDERING - PLAYER
    // ============================================================

    private static void renderPlayer() {
        if (player == null) return;

        if (player.ended()) { transitionToFinished("ENDED", false); return; }
        if (player.error()) { transitionToFinished("ERROR", true); return; }
        if (player.stopped()) { transitionToFinished("STOPPED", false); return; }

        // Black background
        DrawTool.setupOrtho(windowWidth, windowHeight);
        DrawTool.disableTextures();
        DrawTool.fill(0, 0, windowWidth, windowHeight, 0, 0, 0, 1);
        DrawTool.enableTextures();
        DrawTool.restoreProjection();

        // Video with aspect ratio
        final int videoWidth = player.width();
        final int videoHeight = player.height();

        float renderWidth = windowWidth, renderHeight = windowHeight, offsetX = 0, offsetY = 0;

        if (videoWidth > 0 && videoHeight > 0) {
            final float videoAspect = (float) videoWidth / videoHeight;
            final float windowAspect = (float) windowWidth / windowHeight;

            if (videoAspect > windowAspect) {
                renderWidth = windowWidth;
                renderHeight = windowWidth / videoAspect;
                offsetY = (windowHeight - renderHeight) / 2;
            } else {
                renderHeight = windowHeight;
                renderWidth = windowHeight * videoAspect;
                offsetX = (windowWidth - renderWidth) / 2;
            }
        }

        DrawTool.bindTexture(player.texture());
        DrawTool.color(1, 1, 1, 1);
        DrawTool.blitNDC(
                (offsetX / windowWidth) * 2 - 1,
                (offsetY / windowHeight) * 2 - 1,
                ((offsetX + renderWidth) / windowWidth) * 2 - 1,
                ((offsetY + renderHeight) / windowHeight) * 2 - 1
        );

        renderPlayerOverlay();
    }

    private static void renderPlayerOverlay() {
        if (player == null) return;

        DrawTool.setupOrtho(windowWidth, windowHeight);
        glDisable(GL_DEPTH_TEST);

        // Fades
        DrawTool.disableTextures();
        DrawTool.fadeLeft(windowWidth, windowHeight, 380, 0.9f);
        DrawTool.fadeBottom(windowWidth, windowHeight, 120, 0.9f);
        DrawTool.enableTextures();

        // Debug info
        int y = 20;
        y = renderLabelValue("--- Debug Info ---", null, 15, y, COLOR_BLUE, null);
        y = renderLabelValue("Engine:", player.getClass().getSimpleName(), 15, y, COLOR_BLUE, COLOR_GRAY);
        y = renderLabelValue("Source:", (sourceSelectorIndex + 1) + "/" + (availableSources != null ? availableSources.length : 1), 15, y, COLOR_BLUE, COLOR_GRAY);
        y = renderLabelValue("Size:", player.width() + "x" + player.height(), 15, y, COLOR_BLUE, COLOR_GRAY);
        y = renderLabelValue("Status:", player.status().toString(), 15, y, COLOR_BLUE, COLOR_GRAY);

        final long duration = player.duration();
        final String timeValue = duration <= 0
                ? FORMAT.format(new Date(player.time()))
                : FORMAT.format(new Date(player.time())) + " / " + FORMAT.format(new Date(duration));
        y = renderLabelValue("Time:", timeValue, 15, y, COLOR_BLUE, COLOR_GRAY);
        y = renderLabelValue("Volume:", player.volume() + "%", 15, y, COLOR_BLUE, COLOR_GRAY);
        y = renderLabelValue("Quality:", selectedQuality.name(), 15, y, COLOR_BLUE, COLOR_GRAY);
        y = renderLabelValue("Speed:", String.format("%.2f", player.speed()) + "x", 15, y, COLOR_BLUE, COLOR_GRAY);
        y = renderLabelValue("Live:", player.liveSource() ? "Yes" : "No", 15, y, COLOR_BLUE, COLOR_GRAY);

        // Metadata
        y += 15;
        y = renderLabelValue("--- Metadata ---", null, 15, y, COLOR_BLUE, null);

        if (selectedSource != null && selectedSource.metadata() != null) {
            final MRL.Metadata meta = selectedSource.metadata();
            y = renderLabelValue("Title:", truncate(meta.title(), 35), 15, y, COLOR_BLUE, COLOR_GRAY);
            y = renderLabelValue("Author:", truncate(meta.author(), 35), 15, y, COLOR_BLUE, COLOR_GRAY);
            y = renderLabelValue("Duration:", meta.duration() > 0 ? FORMAT.format(new Date(meta.duration())) : "Unknown", 15, y, COLOR_BLUE, COLOR_GRAY);
            if (meta.publishedAt() != null) {
                y = renderLabelValue("Published:", truncate(meta.publishedAt().toString(), 30), 15, y, COLOR_BLUE, COLOR_GRAY);
            }
        } else {
            renderText("Unavailable", 15, y, COLOR_GRAY);
        }

        // Description
        if (selectedSource != null && selectedSource.metadata() != null) {
            final String desc = selectedSource.metadata().description();
            if (desc != null && !desc.isEmpty()) {
                int descY = windowHeight - 180;
                renderText("Description:", 20, descY, COLOR_BLUE);
                descY += lineHeight;
                for (int i = 0; i < Math.min(desc.length(), 400) && descY < windowHeight - 100; i += 100) {
                    renderText(desc.substring(i, Math.min(i + 100, desc.length())), 20, descY, COLOR_GRAY);
                    descY += lineHeight;
                }
            }
        }

        DrawTool.restoreProjection();
    }

    private static int renderLabelValue(final String label, final String value, final int x, final int y, final Color labelColor, final Color valueColor) {
        renderText(label, x, y, labelColor);
        if (value != null && valueColor != null) {
            renderText(" " + value, x + calculateTextWidth(label), y, valueColor);
        }
        return y + lineHeight;
    }

    private static String truncate(final String s, final int maxLen) {
        if (s == null || s.isEmpty()) return "Unknown";
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }

    private static String padOrTruncate(final String s, final int len) {
        if (s == null) return String.format("%-" + len + "s", "");
        return s.length() > len ? s.substring(0, len - 3) + "..." : String.format("%-" + len + "s", s);
    }

    // ============================================================
    // RENDERING - DIALOGS (unified)
    // ============================================================

    private static void renderDialog(final DialogConfig config, final Runnable contentRenderer) {
        DrawTool.setupOrtho(windowWidth, windowHeight);
        DrawTool.dialogBox(config.x, config.y, config.width, config.height, config.borderColor, DIALOG_BORDER);
        renderText(config.title, config.x + config.padding, config.y + config.padding, config.borderColor);
        contentRenderer.run();
        DrawTool.restoreProjection();
    }

    private static void renderQualityDialog() {
        if (availableQualities == null) return;

        final int dialogW = 350, dialogH = availableQualities.length * lineHeight + 80;
        final var config = new DialogConfig("Select Quality", COLOR_BLUE, dialogW, dialogH,
                windowWidth - dialogW - 30, windowHeight - dialogH - 30, 15);
        renderDialog(config, () -> {
            int y = config.y + config.padding + lineHeight + 15;
            for (int i = 0; i < availableQualities.length; i++) {
                final boolean selected = i == qualityDialogIndex;
                final String text = (selected ? "> " : "  ") + availableQualities[i].name() + " (" + availableQualities[i].threshold + "p)";
                renderText(text, config.x + 15, y, selected ? COLOR_BLUE : COLOR_GRAY);
                clickableAreas.add(new ClickableArea(config.x + 15, y, config.width - 30, lineHeight, i));
                y += lineHeight;
            }
        });
    }

    private static void renderFinishedDialog() {
        final int dialogW = 380, dialogH = 170, padding = 25;
        final var config = new DialogConfig(
                finishedWasError ? "Playback Error" : "Playback Finished",
                finishedWasError ? COLOR_RED : COLOR_BLUE,
                dialogW, dialogH,
                (windowWidth - dialogW) / 2, (windowHeight - dialogH) / 2,
                padding
        );

        renderDialog(config, () -> {
            int y = config.y + padding + lineHeight + 10;
            renderText("Reason: " + finishedReason, config.x + padding, y, COLOR_GRAY);
            y += lineHeight + 25;

            final String replayText = (finishedDialogIndex == 0 ? "> " : "  ") + (finishedWasError ? "Retry" : "Replay");
            final String closeText = (finishedDialogIndex == 1 ? "> " : "  ") + "Close";
            final int closeX = config.x + dialogW - padding - calculateTextWidth(closeText);

            renderText(replayText, config.x + padding, y, finishedDialogIndex == 0 ? COLOR_BLUE : COLOR_GRAY);
            clickableAreas.add(new ClickableArea(config.x + padding, y, calculateTextWidth(replayText), lineHeight, 0));

            renderText(closeText, closeX, y, finishedDialogIndex == 1 ? COLOR_BLUE : COLOR_GRAY);
            clickableAreas.add(new ClickableArea(closeX, y, calculateTextWidth(closeText), lineHeight, 1));
        });
    }

    private static void renderOpenMultimediaDialog() {
        renderHomeSelector();

        DrawTool.setupOrtho(windowWidth, windowHeight);

        final String displayUrl = customUrlText != null ? customUrlText : "";
        final int dialogW = Math.min(Math.max(calculateTextWidth(displayUrl.isEmpty() ? "(clipboard empty)" : displayUrl) + 80, 450), windowWidth - 100);
        final int dialogH = 150;
        final int dialogX = (windowWidth - dialogW) / 2;
        final int dialogY = (windowHeight - dialogH) / 2;

        DrawTool.dialogBox(dialogX, dialogY, dialogW, dialogH, COLOR_BLUE, DIALOG_BORDER);

        int y = dialogY + 25;
        renderText("Open Multimedia", dialogX + 20, y, COLOR_BLUE);
        y += lineHeight + 15;

        // Text box
        final int tbX = dialogX + 20, tbW = dialogW - 40, tbH = lineHeight + 10;
        DrawTool.disableTextures();
        DrawTool.fill(tbX, y, tbW, tbH, 0.1f, 0.1f, 0.1f, 1);
        DrawTool.rect(tbX, y, tbW, tbH, 0.31f, 0.71f, 1f, 0.5f, 1);
        DrawTool.enableTextures();

        final int maxChars = (tbW - 10) / 10;
        final String truncatedUrl = displayUrl.length() > maxChars ? displayUrl.substring(0, maxChars - 3) + "..." : displayUrl;
        renderText(truncatedUrl.isEmpty() ? "(clipboard empty)" : truncatedUrl, tbX + 5, y + 10, truncatedUrl.isEmpty() ? COLOR_GRAY : COLOR_WHITE);

        DrawTool.restoreProjection();
    }

    private static void renderPlayerErrorDialog() {
        DrawTool.setupOrtho(windowWidth, windowHeight);

        final String msg = playerErrorMessage != null ? playerErrorMessage : "Unknown error";
        final String[] lines = msg.split("\n");

        int maxWidth = calculateTextWidth("Player Error");
        for (final String line : lines) maxWidth = Math.max(maxWidth, calculateTextWidth(line));
        maxWidth = Math.max(maxWidth, calculateTextWidth("> OK"));

        final int dialogW = maxWidth + 60, dialogH = 80 + lines.length * lineHeight + lineHeight;
        final int dialogX = (windowWidth - dialogW) / 2, dialogY = (windowHeight - dialogH) / 2;

        DrawTool.dialogBox(dialogX, dialogY, dialogW, dialogH, COLOR_RED, DIALOG_BORDER);

        int y = dialogY + 25;
        renderText("Player Error", dialogX + 20, y, COLOR_RED);
        y += lineHeight + 15;

        for (final String line : lines) {
            renderText(line, dialogX + 20, y, COLOR_GRAY);
            y += lineHeight;
        }

        // OK selector (always selected)
        y += 15;
        final String okText = "> OK";
        final int okX = dialogX + (dialogW - calculateTextWidth(okText)) / 2;
        renderText(okText, okX, y, COLOR_BLUE);
        clickableAreas.add(new ClickableArea(okX, y, calculateTextWidth(okText), lineHeight, 0));

        DrawTool.restoreProjection();
    }

    // ============================================================
    // RENDERING - UTILITIES
    // ============================================================

    private static int renderBanner(final int startY) {
        if (bannerTextureId <= 0) return startY;

        final float scale = Math.min(1f, (float) Math.min(125, windowHeight - 200) / bannerHeight);
        final int renderH = (int) (bannerHeight * scale);
        final int renderW = (int) (bannerWidth * scale);

        DrawTool.bindTexture(bannerTextureId);
        DrawTool.color(1, 1, 1, 1);
        DrawTool.blit(MENU_PADDING, startY, renderW, renderH);

        final int lineY = startY + renderH + 15;
        DrawTool.disableTextures();
        DrawTool.lineH(0, lineY, windowWidth, 0.31f, 0.71f, 1f, 1f, 4);
        DrawTool.enableTextures();

        return lineY + 20;
    }

    private static void renderAlert(String text, int level) {
        DrawTool.setupOrtho(windowWidth, windowHeight);

        final int textWidth = calculateTextWidth(text) + 20;
        final int alertW = Math.max(350, textWidth), alertH = 35;
        final int alertX = windowWidth - alertW - 15, alertY = 15 + (35 * level) + (10 * level);

        DrawTool.disableTextures();
        DrawTool.fill(alertX, alertY, alertW, alertH, 1f, 0.5f, 0f, 0.9f);
        DrawTool.rect(alertX, alertY, alertW, alertH, 0.8f, 0.4f, 0f, 1f, 2);
        DrawTool.enableTextures();

        renderText(text, alertX + 10, alertY + 8, new Color(50, 25, 0));

        DrawTool.restoreProjection();
    }

    private static String getMRLStatusString(final MRL mrl) {
        if (mrl == null) return "NULL";
        if (mrl.error()) return "ERROR";
        if (mrl.ready()) return "READY";
        if (mrl.busy()) return "LOADING";
        return "UNKNOWN";
    }

    // ============================================================
    // TEXT RENDERING
    // ============================================================

    private static void renderText(final String text, final int x, final int y, final Color color) {
        if (text == null || text.isEmpty()) return;
        DrawTool.color(color);

        int currentX = x;
        for (final char c : text.toCharArray()) {
            final CharTexture ct = getCharTexture(c);
            if (ct == null) continue;
            DrawTool.bindTexture(ct.textureId);
            DrawTool.blit(currentX, y, ct.width, ct.height);
            currentX += ct.width;
        }
    }

    private static int calculateTextWidth(final String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (final char c : text.toCharArray()) {
            final CharTexture ct = getCharTexture(c);
            if (ct != null) width += ct.width;
        }
        return width;
    }

    private static CharTexture getCharTexture(final char c) {
        if (charTextureCache.containsKey(c)) return charTextureCache.get(c);

        final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g2d = img.createGraphics();
        g2d.setFont(textFont);
        final Rectangle2D bounds = textFont.getStringBounds(String.valueOf(c), g2d.getFontRenderContext());
        g2d.dispose();

        final int charW = (int) Math.ceil(bounds.getWidth());
        final int charH = (int) Math.ceil(bounds.getHeight());
        if (charW == 0 || charH == 0) return null;

        final BufferedImage charImage = new BufferedImage(charW, charH, BufferedImage.TYPE_INT_ARGB);
        g2d = charImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g2d.setFont(textFont);
        g2d.setColor(COLOR_WHITE);
        g2d.drawString(String.valueOf(c), -(int) bounds.getX(), -(int) bounds.getY());
        g2d.dispose();

        final int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);

        final int[] pixels = new int[charW * charH];
        charImage.getRGB(0, 0, charW, charH, pixels, 0, charW);

        final ByteBuffer buffer = MemoryUtil.memAlloc(charW * charH * 4);
        for (int i = 0; i < charH; i++) {
            for (int j = 0; j < charW; j++) {
                final int pixel = pixels[i * charW + j];
                buffer.put((byte) ((pixel >> 16) & 0xFF));
                buffer.put((byte) ((pixel >> 8) & 0xFF));
                buffer.put((byte) (pixel & 0xFF));
                buffer.put((byte) ((pixel >> 24) & 0xFF));
            }
        }
        buffer.flip();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, charW);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, charW, charH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        MemoryUtil.memFree(buffer);

        final CharTexture charTex = new CharTexture(textureId, charW, charH);
        charTextureCache.put(c, charTex);
        return charTex;
    }

    // ============================================================
    // APPLICATION STATE
    // ============================================================

    private enum AppState {
        HOME_SELECTOR, MRL_SELECTOR, SOURCE_SELECTOR, PLAYER_RUNNING,
        DIALOG_QUALITY, DIALOG_FINISHED, DIALOG_OPEN_MULTIMEDIA, DIALOG_PLAYER_ERROR,
        CONSOLE
    }
}