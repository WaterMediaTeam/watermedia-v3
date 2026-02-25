package org.watermedia.bootstrap.app;

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
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.decode.DecoderAPI;
import org.watermedia.api.decode.Image;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.bootstrap.app.screen.*;
import org.watermedia.bootstrap.app.ui.Colors;
import org.watermedia.bootstrap.app.ui.Dialog;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.DrawTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.InputStream;
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
import java.util.Comparator;
import java.util.Date;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * WATERMeDIA Test Application.
 */
public class WaterMediaApp {

    private final AppContext ctx = new AppContext();
    private final ScreenManager screens = new ScreenManager();
    private final Dialog errorDialog = new Dialog();

    private HomeScreen homeScreen;
    private ConsoleScreen consoleScreen;

    private boolean running = true;

    static {
        initLogging();
    }

    public static void main(final String... args) {
        new WaterMediaApp().run();
    }

    private void run() {
        WaterMedia.start("Java Test", null, null, true);
        this.ctx.uriGroups = AppContext.GSON.fromJson(IOTool.jarRead("uris.json"), AppContext.URIGroup[].class);

        this.init();
        glfwShowWindow(this.ctx.windowHandle);
        this.mainLoop();
        this.cleanup();
    }

    // ========================================
    // INITIALIZATION
    // ========================================

    private void init() {
        this.initWindow();
        this.initAudio();
        this.initResources();
        this.initScreens();
        this.initErrorDialog();
    }

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);

        this.ctx.windowHandle = glfwCreateWindow(1280, 720, AppContext.APP_NAME, NULL, NULL);
        if (this.ctx.windowHandle == NULL) throw new RuntimeException("Failed to create the GLFW window");

        // Callbacks
        glfwSetKeyCallback(this.ctx.windowHandle, this::handleKeyInput);
        glfwSetCursorPosCallback(this.ctx.windowHandle, (w, x, y) -> {
            this.ctx.mouseX = x;
            this.ctx.mouseY = y;
        });
        glfwSetMouseButtonCallback(this.ctx.windowHandle, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE) this.ctx.mouseClicked = true;
        });
        glfwSetScrollCallback(this.ctx.windowHandle, (w, xOffset, yOffset) -> {
            if (!this.ctx.hasError()) {
                this.screens.handleScroll(yOffset);
            }
        });
        glfwSetWindowSizeCallback(this.ctx.windowHandle, (w, width, height) -> {
            this.ctx.windowWidth = width;
            this.ctx.windowHeight = height;
            glViewport(0, 0, width, height);
        });

        // Center window
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1);
            final IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(this.ctx.windowHandle, pWidth, pHeight);
            this.ctx.windowWidth = pWidth.get(0);
            this.ctx.windowHeight = pHeight.get(0);

            final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(this.ctx.windowHandle,
                    (vidmode.width() - this.ctx.windowWidth) / 2,
                    (vidmode.height() - this.ctx.windowHeight) / 2);
        }

        glfwMakeContextCurrent(this.ctx.windowHandle);
        glfwSwapInterval(1);
        GL.createCapabilities();
    }

    private void initAudio() {
        final long device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) throw new IllegalStateException("Failed to open a new Audio Device");
        final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(context);
        ALC.createCapabilities(device);
        AL.createCapabilities(ALC.createCapabilities(device));

        this.loadSoundClick();
    }

    private void initResources() {
        this.ctx.text = new TextRenderer();
        this.ctx.text.margin(6);
        this.loadIcon();
        this.loadBanner();
    }

    private void initScreens() {
        this.homeScreen = new HomeScreen(this.ctx.text, this.ctx, this::navigateAction);
        this.consoleScreen = new ConsoleScreen(this.ctx.text, this.ctx, this::navigateAction);

        this.screens.register("home", this.homeScreen);
        this.screens.register("mrl", new MRLSelectorScreen(this.ctx.text, this.ctx, this::navigateAction));
        this.screens.register("source", new SourceSelectorScreen(this.ctx.text, this.ctx, this::navigateAction));
        this.screens.register("player", new PlayerScreen(this.ctx.text, this.ctx, this::navigateAction));
        this.screens.register("multimedia", new OpenMultimediaScreen(this.ctx.text, this.ctx, this::navigateAction, this.homeScreen));
        this.screens.register("console", this.consoleScreen);

        this.screens.navigate("home");
    }

    private void initErrorDialog() {
        this.errorDialog.minWidth(400)
                .minHeight(120)
                .onSelectionChanged(this.ctx::playSelectionSound);
    }

    private void navigateAction(final HomeScreen.Action action) {
        if (action == null) {
            this.screens.backToHome();
            return;
        }

        switch (action) {
            case EXIT -> this.running = false;

            case BACK -> this.screens.backToHome();

            case OPEN_MULTIMEDIA -> {
                // Check FFmpeg availability first
                if (!FFMediaPlayer.loaded()) {
                    this.ctx.showError("Feature Unavailable",
                            "FFmpeg is not loaded.\nMedia playback is not available.\n\nCheck the alerts for more information.",
                            null);
                    return;
                }
                this.screens.navigate("multimedia");
            }

            case MRL_SELECTOR -> this.screens.navigate("mrl");

            case SOURCE_SELECTOR -> this.screens.navigate("source");

            case PLAYER -> {
                // Check FFmpeg availability first
                if (!FFMediaPlayer.loaded()) {
                    this.ctx.showError("Feature Unavailable",
                            "FFmpeg is not loaded.\nMedia playback is not available.\n\nCheck the alerts for more information.",
                            null);
                    return;
                }
                this.screens.navigate("player");
            }

            case UPLOAD_LOGS -> {
                // Check Minecraft context first
                if (!AppContext.IN_MODS) {
                    this.ctx.showError("Feature Unavailable",
                            "Upload logs is only available when\nrunning in Minecraft context.\n\nPlace this JAR in the mods folder\nand run Minecraft.",
                            null);
                    return;
                }
                this.consoleScreen.open("Upload Log Files", this.screens::backToHome);
                this.screens.navigate("console");
                ThreadTool.createStarted("UploadLogs", this::performUploadLogs);
            }

            case CLEANUP -> {
                this.consoleScreen.open("Cleanup", () -> this.running = false);
                this.screens.navigate("console");
                ThreadTool.createStarted("Cleanup", this::performCleanup);
            }
        }
    }

    // ========================================
    // MAIN LOOP
    // ========================================

    private void mainLoop() {
        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {
        }, 0);

        glClearColor(0, 0, 0, 1);
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        while (this.running && !glfwWindowShouldClose(this.ctx.windowHandle)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            this.screens.render(this.ctx.windowWidth, this.ctx.windowHeight);

            // Render global error dialog on top if present
            if (this.ctx.hasError()) {
                this.renderErrorDialog();
            }

            this.renderBottomBar();

            glfwSwapBuffers(this.ctx.windowHandle);
            glfwPollEvents();

            // Handle input - error dialog takes priority
            if (this.ctx.hasError()) {
                this.errorDialog.handleHover(this.ctx.mouseX, this.ctx.mouseY);
                if (this.ctx.mouseClicked) {
                    this.ctx.mouseClicked = false;
                    this.errorDialog.handleClick(this.ctx.mouseX, this.ctx.mouseY);
                }
            } else {
                this.screens.handleMouseMove(this.ctx.mouseX, this.ctx.mouseY);
                if (this.ctx.mouseClicked) {
                    this.ctx.mouseClicked = false;
                    this.screens.handleMouseClick(this.ctx.mouseX, this.ctx.mouseY);
                }
            }

            this.ctx.processExecutor();
        }

        this.ctx.releasePlayer();
    }

    private void renderErrorDialog() {
        this.errorDialog.title(this.ctx.globalErrorTitle)
                .borderColor(Colors.RED)
                .clearContent()
                .clearButtons();

        // Split message into lines
        for (final String line : this.ctx.globalErrorMessage.split("\n")) {
            this.errorDialog.addLine(line);
        }

        this.errorDialog.addButton("OK", this.ctx::clearError);
        this.errorDialog.centerIn(this.ctx.text, this.ctx.windowWidth, this.ctx.windowHeight);
        this.errorDialog.show();
        this.errorDialog.render(this.ctx.text, this.ctx.windowWidth, this.ctx.windowHeight);
    }

    private void renderBottomBar() {
        DrawTool.setupOrtho(this.ctx.windowWidth, this.ctx.windowHeight);

        // Seekbar
        final int seekbarY = this.ctx.windowHeight - 82;
        float progress = 1f;
        if (this.ctx.player != null && this.ctx.player.duration() > 0) {
            progress = Math.min(1f, Math.max(0f, (float) this.ctx.player.time() / this.ctx.player.duration()));
        }

        DrawTool.disableTextures();
        DrawTool.fill(0, seekbarY, this.ctx.windowWidth, 4, 0.15f, 0.15f, 0.15f, 1f);
        DrawTool.fill(0, seekbarY, this.ctx.windowWidth * progress, 4, 0.31f, 0.71f, 1f, 1f);
        DrawTool.enableTextures();

        // Instructions - show error dialog instructions if error is present
        final String instructions = this.ctx.hasError() ? "ENTER/ESC: Close" : this.screens.currentInstructions();
        this.ctx.text.render(instructions, AppContext.PADDING, this.ctx.windowHeight - 60, Colors.GRAY);

        DrawTool.restoreProjection();
    }

    // ========================================
    // INPUT HANDLING
    // ========================================

    private void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        if (action != GLFW_RELEASE) return;

        // Error dialog takes priority
        if (this.ctx.hasError()) {
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_ESCAPE) {
                this.ctx.clearError();
            }
            return;
        }

        this.screens.handleKey(key, action);
    }

    // ========================================
    // RESOURCE LOADING
    // ========================================

    private void loadIcon() {
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            final byte[] iconBytes = in.readAllBytes();
            final Image iconImage = DecoderAPI.decodeImage(iconBytes);
            if (iconImage == null || iconImage.frames().length == 0) return;

            final ByteBuffer iconBuffer = iconImage.frames()[0];
            iconBuffer.rewind();

            final ByteBuffer buffer = MemoryUtil.memAlloc(iconImage.width() * iconImage.height() * 4);
            for (int i = 0; i < iconImage.width() * iconImage.height(); i++) {
                final byte b = iconBuffer.get();
                final byte g = iconBuffer.get();
                final byte r = iconBuffer.get();
                final byte a = iconBuffer.get();
                buffer.put(r).put(g).put(b).put(a);
            }
            buffer.flip();

            final GLFWImage.Buffer icons = GLFWImage.malloc(1);
            icons.position(0).width(iconImage.width()).height(iconImage.height()).pixels(buffer);
            glfwSetWindowIcon(this.ctx.windowHandle, icons);

            icons.free();
            MemoryUtil.memFree(buffer);
        } catch (final Exception e) {
            System.err.println("Failed to load window icon: " + e.getMessage());
        }
    }

    private void loadBanner() {
        try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
            final byte[] bannerBytes = in.readAllBytes();
            final Image bannerImage = DecoderAPI.decodeImage(bannerBytes);
            if (bannerImage == null || bannerImage.frames().length == 0) return;

            this.ctx.bannerWidth = bannerImage.width();
            this.ctx.bannerHeight = bannerImage.height();
            this.ctx.bannerTextureId = glGenTextures();

            glBindTexture(GL_TEXTURE_2D, this.ctx.bannerTextureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, this.ctx.bannerWidth, this.ctx.bannerHeight,
                    0, GL_BGRA, GL_UNSIGNED_BYTE, bannerImage.frames()[0]);
        } catch (final Exception e) {
            System.err.println("Failed to load banner: " + e.getMessage());
        }
    }

    private void loadSoundClick() {
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

            this.ctx.soundBuffer = alGenBuffers();
            alBufferData(this.ctx.soundBuffer, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, pcmBuffer, sampleRate);
            MemoryUtil.memFree(pcmBuffer);

            this.ctx.soundSource = alGenSources();
            alSourcei(this.ctx.soundSource, AL_BUFFER, this.ctx.soundBuffer);
            alSourcef(this.ctx.soundSource, AL_GAIN, 0.2f);
        } catch (final Exception e) {
            System.err.println("Failed to load sound effect: " + e.getMessage());
        }
    }

    // ========================================
    // CONSOLE OPERATIONS
    // ========================================

    private void performUploadLogs() {
        try {
            this.consoleScreen.info("=== Upload Log Files ===");
            this.consoleScreen.print("");

            final Path cwd = Path.of("").toAbsolutePath();
            final Path logsDir = cwd.getParent().resolve("logs");
            final Path crashDir = cwd.getParent().resolve("crash-reports");

            this.consoleScreen.print("Working directory: " + cwd);
            this.consoleScreen.print("Logs directory: " + logsDir);
            this.consoleScreen.print("");

            final Path latestLog = logsDir.resolve("latest.log");
            final Path wmLog = logsDir.resolve("watermedia-app.log");
            final Path crashReport = this.findLatestCrashReport(crashDir);

            this.consoleScreen.info("--- Reading files ---");
            final String latestContent = this.readFileStatus(latestLog, "latest.log");
            final String wmContent = this.readFileStatus(wmLog, "watermedia-app.log");
            final String crashContent = crashReport != null ? this.readFileStatus(crashReport, crashReport.getFileName().toString()) : null;

            if (crashReport == null) {
                this.consoleScreen.print("crash-reports: No crash reports found", Colors.GRAY);
            }

            this.consoleScreen.print("");
            this.consoleScreen.info("--- Uploading to mclo.gs ---");

            final String latestUrl = latestContent != null ? this.uploadToMclogs(latestContent, "latest.log") : null;
            final String wmUrl = wmContent != null ? this.uploadToMclogs(wmContent, "watermedia-app.log") : null;
            final String crashUrl = crashContent != null ? this.uploadToMclogs(crashContent, crashReport.getFileName().toString()) : null;

            this.consoleScreen.print("");

            if (latestUrl != null || wmUrl != null) {
                this.consoleScreen.info("--- Generating issue report ---");
                final String issueText = this.generateIssueTemplate(latestUrl, wmUrl, crashUrl);

                try {
                    final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    clipboard.setContents(new StringSelection(issueText), null);
                    this.consoleScreen.success("Issue template copied to clipboard!");
                } catch (final Exception e) {
                    this.consoleScreen.error("Failed to copy to clipboard: " + e.getMessage());
                }

                this.consoleScreen.print("");
                this.consoleScreen.print("Opening GitHub issues page...", Colors.YELLOW);

                try {
                    Desktop.getDesktop().browse(URI.create("https://github.com/WaterMediaTeam/watermedia/issues/new"));
                    this.consoleScreen.success("Browser opened!");
                    this.consoleScreen.success("Please open a github issue and paste the content of your clipboard");
                } catch (final Exception e) {
                    this.consoleScreen.error("Failed to open browser: " + e.getMessage());
                    this.consoleScreen.print("Please manually go to: https://github.com/WaterMediaTeam/watermedia/issues/new");
                }
            } else {
                this.consoleScreen.error("No files were uploaded successfully.");
            }

            this.consoleScreen.waitForKey();
        } catch (final Exception e) {
            this.consoleScreen.error("Unexpected error: " + e.getMessage());
            this.consoleScreen.waitForKey();
        }
    }

    private void performCleanup() {
        try {
            this.consoleScreen.info("=== Cleanup ===");
            this.consoleScreen.print("");

            final Path wmPath = WaterMedia.cwd();
            final Path tmpDir = WaterMedia.tmp();

            this.consoleScreen.print("WaterMedia path: " + wmPath);
            this.consoleScreen.print("");
            this.consoleScreen.info("--- Cleaning tmp folder ---");

            final var dirs = tmpDir.toFile().listFiles();
            if (dirs == null) {
                this.consoleScreen.print("No tmp folder found", Colors.GRAY);
                this.consoleScreen.print("");
                this.consoleScreen.info("--- Cleanup complete ---");
                this.consoleScreen.print("Application will close after this.");
                this.consoleScreen.waitForKey();
                return;
            }

            final int tmpCount = IOTool.count(tmpDir.toFile());
            final int tmpDeleted = IOTool.delete(tmpDir.toFile());

            if (tmpDeleted > 0) {
                if (tmpDeleted == tmpCount) {
                    this.consoleScreen.success("Tmp folder cleaned successfully (" + tmpDeleted + " items removed)");
                } else {
                    this.consoleScreen.success("Tmp folder deleted almost successfully (" + tmpDeleted + " items removed - " + (tmpCount - tmpDeleted) + " items failed)");
                }
            } else {
                this.consoleScreen.error("Failed to delete tmp folder");
            }

            final int tmpDeleteScheduled = IOTool.deleteSchedule(tmpDir.toFile());
            if (tmpDeleteScheduled > 0) {
                this.consoleScreen.info("Additional " + tmpDeleteScheduled + " items scheduled for deletion on exit.");
            } else {
                this.consoleScreen.print("No additional items scheduled for deletion.", Colors.YELLOW);
            }

            this.consoleScreen.print("");
            this.consoleScreen.info("--- Cleanup complete ---");
            this.consoleScreen.print("Application will close after this.");
            this.consoleScreen.waitForKey();
        } catch (final Exception e) {
            this.consoleScreen.error("Unexpected error: " + e.getMessage());
            this.consoleScreen.waitForKey();
        }
    }

    private Path findLatestCrashReport(final Path crashDir) {
        if (!Files.exists(crashDir)) return null;
        try (final var stream = Files.list(crashDir)) {
            return stream.filter(p -> p.toString().endsWith(".txt"))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (final Exception e) {
                            return 0L;
                        }
                    })).orElse(null);
        } catch (final Exception e) {
            return null;
        }
    }

    private String readFileStatus(final Path path, final String name) {
        if (!Files.exists(path)) {
            this.consoleScreen.print(name + ": NOT FOUND", Colors.RED);
            return null;
        }
        try {
            final String content = Files.readString(path, StandardCharsets.UTF_8);
            this.consoleScreen.success(name + ": Read OK (" + content.length() + " bytes)");
            return content;
        } catch (final Exception e) {
            this.consoleScreen.error(name + ": FAILED TO READ - " + e.getMessage());
            return null;
        }
    }

    private String uploadToMclogs(final String content, final String name) {
        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mclo.gs/1/log"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("content=" + java.net.URLEncoder.encode(content, StandardCharsets.UTF_8)))
                    .build();

            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                final JsonObject json = AppContext.GSON.fromJson(response.body(), JsonObject.class);
                if (json.get("success").getAsBoolean()) {
                    final String url = json.get("url").getAsString();
                    this.consoleScreen.success(name + ": Uploaded -> " + url);
                    return url;
                }
            }
            this.consoleScreen.error(name + ": Upload failed (HTTP " + response.statusCode() + ")");
        } catch (final Exception e) {
            this.consoleScreen.error(name + ": Upload failed - " + e.getMessage());
        }
        return null;
    }

    private String generateIssueTemplate(final String latestUrl, final String wmUrl, final String crashUrl) {
        return "This is an automated issue report generated by WATERMeDIA: Multimedia API.\n\n" +
                "## Files\n" +
                "- Logs: " + (latestUrl != null ? latestUrl : "N/A") + "\n" +
                "- Crash-report: " + (crashUrl != null ? crashUrl : "N/A") + "\n" +
                "- WM Logs: " + (wmUrl != null ? wmUrl : "N/A") + "\n\n" +
                "## System Properties\n" +
                "- OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")\n" +
                "- Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n" +
                "- Java Home: " + System.getProperty("java.home") + "\n" +
                "- User Dir: " + System.getProperty("user.dir") + "\n" +
                "- FFmpeg Loaded: " + FFMediaPlayer.loaded() + "\n";
    }

    // ========================================
    // CLEANUP
    // ========================================

    private void cleanup() {
        glfwFreeCallbacks(this.ctx.windowHandle);
        glfwDestroyWindow(this.ctx.windowHandle);
        glfwTerminate();
        glfwSetErrorCallback(null).close();
        System.exit(0);
    }

    // ========================================
    // LOGGING
    // ========================================

    private static void initLogging() {
        final String filename = "logs/watermedia-app.log";
        final File logfile = new File(filename);
        if (logfile.exists() && !logfile.renameTo(new File("logs/watermedia-app-" + new Date() + ".log"))) {
            System.err.println("Failed to rotate log file");
        }

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.DEBUG);

        final AppenderComponentBuilder console = builder.newAppender("Console", "Console")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        console.add(builder.newLayout("PatternLayout")
                .addAttribute("pattern", "%highlight{[%d{HH:mm:ss}] [%t/%level] [%logger/%marker]: %msg%n}"));

        final AppenderComponentBuilder file = builder.newAppender("File", "File")
                .addAttribute("fileName", filename)
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
}
