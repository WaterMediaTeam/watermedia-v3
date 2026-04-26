package org.watermedia.bootstrap.app;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
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
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * WATERMeDIA Test Application.
 */
public class WaterMediaApp {
    private static final AppContext ctx = new AppContext();
    private static final ScreenManager screens = new ScreenManager();
    private static final Dialog errorDialog = new Dialog();

    private static ConsoleScreen consoleScreen;

    private static boolean running = true;

    static { initLogging(); }

    public static void start(final Runnable task) {
        WaterMedia.start("WaterMediaApp", null, null, true);
        ctx.uriGroups = AppContext.GSON.fromJson(IOTool.jarRead("uris.json"), AppContext.URIGroup[].class);
        if (!WaterMedia.LOGGER.isDebugEnabled()) {
            for (int i = 0; i < ctx.uriGroups.length; i++) {
                final AppContext.URIGroup group = ctx.uriGroups[i];
                final AppContext.TestURI[] filtered = Arrays.stream(group.uris()).filter(u -> !u.debug()).toArray(AppContext.TestURI[]::new);
                if (filtered.length != group.uris().length) {
                    ctx.uriGroups[i] = new AppContext.URIGroup(group.name(), filtered);
                }
            }
        }

        init();
        glfwShowWindow(ctx.windowHandle);
        task.run();
        mainLoop();
        cleanup();
    }

    public static void log(final String message) {
        WaterMedia.LOGGER.info(MarkerManager.getMarker("ROOT"), message);
    }

    // INITIALIZATION
    private static void init() {
        initWindow();
        initAudio();
        initResources();
        initScreens();
        initErrorDialog();
    }

    private static void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);

        ctx.windowHandle = glfwCreateWindow(1280, 720, AppContext.APP_NAME, NULL, NULL);
        if (ctx.windowHandle == NULL) throw new RuntimeException("Failed to create the GLFW window");

        // CALLBACKS
        glfwSetKeyCallback(ctx.windowHandle, WaterMediaApp::handleKeyInput);
        glfwSetCursorPosCallback(ctx.windowHandle, (w, x, y) -> {
            ctx.mouseX = x;
            ctx.mouseY = y;
        });
        glfwSetMouseButtonCallback(ctx.windowHandle, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE) ctx.mouseClicked = true;
        });
        glfwSetScrollCallback(ctx.windowHandle, (w, xOffset, yOffset) -> {
            if (!ctx.hasError()) {
                screens.handleScroll(yOffset);
            }
        });
        glfwSetWindowSizeCallback(ctx.windowHandle, (w, width, height) -> {
            ctx.windowWidth = width;
            ctx.windowHeight = height;
            glViewport(0, 0, width, height);
        });

        // CENTER WINDOW
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1);
            final IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(ctx.windowHandle, pWidth, pHeight);
            ctx.windowWidth = pWidth.get(0);
            ctx.windowHeight = pHeight.get(0);

            final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(ctx.windowHandle,
                    (vidmode.width() - ctx.windowWidth) / 2,
                    (vidmode.height() - ctx.windowHeight) / 2);
        }

        glfwMakeContextCurrent(ctx.windowHandle);
        glfwSwapInterval(1);
        GL.createCapabilities();
        DrawTool.init();
    }

    private static void initAudio() {
        final long device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0L) throw new IllegalStateException("Failed to open a new Audio Device");
        final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(context);
        ALC.createCapabilities(device);
        AL.createCapabilities(ALC.createCapabilities(device));

        loadSoundClick();
    }

    private static void initResources() {
        ctx.text = new TextRenderer();
        ctx.text.margin(6);
        loadIcon();
        loadBanner();
    }

    private static void initScreens() {
        final HomeScreen homeScreen = new HomeScreen(ctx.text, ctx, WaterMediaApp::navigateAction);
        consoleScreen = new ConsoleScreen(ctx.text, ctx, WaterMediaApp::navigateAction);

        screens.register("home", homeScreen);
        screens.register("mrl", new MRLSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("source", new SourceSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("player", new PlayerScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("multimedia", new OpenMultimediaScreen(ctx.text, ctx, WaterMediaApp::navigateAction, homeScreen));
        screens.register("console", consoleScreen);

        screens.navigate("home");
    }

    private static void initErrorDialog() {
        errorDialog.minWidth(400)
                .minHeight(120)
                .onSelectionChanged(ctx::playSelectionSound);
    }

    private static void navigateAction(final HomeScreen.Action action) {
        if (action == null) {
            screens.backToHome();
            return;
        }

        switch (action) {
            case EXIT -> running = false;

            case BACK -> screens.backToHome();

            case OPEN_MULTIMEDIA -> {
                // CHECK FFMPEG AVAILABILITY FIRST
                if (!FFMediaPlayer.loaded()) {
                    ctx.showError("Feature Unavailable",
                            "FFmpeg is not loaded.\nMedia playback is not available.\n\nCheck the alerts for more information.",
                            null);
                    return;
                }
                screens.navigate("multimedia");
            }

            case MRL_SELECTOR -> screens.navigate("mrl");

            case SOURCE_SELECTOR -> screens.navigate("source");

            case PLAYER -> {
                // CHECK FFMPEG AVAILABILITY FIRST
                if (!FFMediaPlayer.loaded()) {
                    ctx.showError("Feature Unavailable",
                            "FFmpeg is not loaded.\nMedia playback is not available.\n\nCheck the alerts for more information.",
                            null);
                    return;
                }
                screens.navigate("player");
            }

            case UPLOAD_LOGS -> {
                // CHECK MINECRAFT CONTEXT FIRST
                if (!AppContext.IN_MODS) {
                    ctx.showError("Feature Unavailable",
                            "Upload logs is only available when\nrunning in Minecraft context.\n\nPlace this JAR in the mods folder\nand run Minecraft.",
                            null);
                    return;
                }
                consoleScreen.open("Upload Log Files", screens::backToHome);
                screens.navigate("console");
                ThreadTool.createStarted("UploadLogs", WaterMediaApp::performUploadLogs);
            }

            case CLEANUP -> {
                consoleScreen.open("Cleanup", () -> running = false);
                screens.navigate("console");
                ThreadTool.createStarted("Cleanup", WaterMediaApp::performCleanup);
            }
        }
    }

    // MAIN LOOP
    private static void mainLoop() {
        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {
        }, 0);

        glClearColor(0, 0, 0, 1);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        while (running && !glfwWindowShouldClose(ctx.windowHandle)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            screens.render(ctx.windowWidth, ctx.windowHeight);

            // RENDER GLOBAL ERROR DIALOG ON TOP IF PRESENT
            if (ctx.hasError()) {
                renderErrorDialog();
            }

            renderBottomBar();

            glfwSwapBuffers(ctx.windowHandle);
            glfwPollEvents();

            // HANDLE INPUT — ERROR DIALOG TAKES PRIORITY
            if (ctx.hasError()) {
                errorDialog.handleHover(ctx.mouseX, ctx.mouseY);
                if (ctx.mouseClicked) {
                    ctx.mouseClicked = false;
                    errorDialog.handleClick(ctx.mouseX, ctx.mouseY);
                }
            } else {
                screens.handleMouseMove(ctx.mouseX, ctx.mouseY);
                if (ctx.mouseClicked) {
                    ctx.mouseClicked = false;
                    screens.handleMouseClick(ctx.mouseX, ctx.mouseY);
                }
            }

            ctx.processExecutor();
        }

        ctx.releasePlayer();
    }

    private static void renderErrorDialog() {
        errorDialog.title(ctx.globalErrorTitle)
                .borderColor(Colors.RED)
                .clearContent()
                .clearButtons();

        // SPLIT MESSAGE INTO LINES
        for (final String line : ctx.globalErrorMessage.split("\n")) {
            errorDialog.addLine(line);
        }

        errorDialog.addButton("OK", ctx::clearError);
        errorDialog.centerIn(ctx.text, ctx.windowWidth, ctx.windowHeight);
        errorDialog.show();
        errorDialog.render(ctx.text, ctx.windowWidth, ctx.windowHeight);
    }

    private static void renderBottomBar() {
        DrawTool.setupOrtho(ctx.windowWidth, ctx.windowHeight);

        // SEEKBAR
        final int seekbarY = ctx.windowHeight - 82;
        float progress = 1f;
        if (ctx.player != null && ctx.player.duration() > 0) {
            progress = Math.min(1f, Math.max(0f, (float) ctx.player.time() / ctx.player.duration()));
        }

        DrawTool.disableTextures();
        DrawTool.fill(0, seekbarY, ctx.windowWidth, 4, 0.15f, 0.15f, 0.15f, 1f);
        DrawTool.fill(0, seekbarY, ctx.windowWidth * progress, 4, 0.31f, 0.71f, 1f, 1f);
        DrawTool.enableTextures();

        // INSTRUCTIONS — SHOW ERROR DIALOG INSTRUCTIONS IF ERROR IS PRESENT
        final String instructions = ctx.hasError() ? "ENTER/ESC: Close" : screens.currentInstructions();
        ctx.text.render(instructions, AppContext.PADDING, ctx.windowHeight - 60, Colors.GRAY);

        DrawTool.restoreProjection();
    }

    // INPUT HANDLING
    private static void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        if (action != GLFW_RELEASE) return;

        // ERROR DIALOG TAKES PRIORITY
        if (ctx.hasError()) {
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_ESCAPE) {
                ctx.clearError();
            }
            return;
        }

        screens.handleKey(key, action);
    }

    // RESOURCE LOADING
    private static void loadIcon() {
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            final byte[] iconBytes = in.readAllBytes();
            final ImageData iconImageData = CodecsAPI.decodeImage(iconBytes);
            if (iconImageData == null || iconImageData.frames().length == 0) return;

            final ByteBuffer iconBuffer = iconImageData.frames()[0];
            iconBuffer.rewind();

            final ByteBuffer buffer = MemoryUtil.memAlloc(iconImageData.width() * iconImageData.height() * 4);
            for (int i = 0; i < iconImageData.width() * iconImageData.height(); i++) {
                final byte b = iconBuffer.get();
                final byte g = iconBuffer.get();
                final byte r = iconBuffer.get();
                final byte a = iconBuffer.get();
                buffer.put(r).put(g).put(b).put(a);
            }
            buffer.flip();

            final GLFWImage.Buffer icons = GLFWImage.malloc(1);
            icons.position(0).width(iconImageData.width()).height(iconImageData.height()).pixels(buffer);
            glfwSetWindowIcon(ctx.windowHandle, icons);

            icons.free();
            MemoryUtil.memFree(buffer);
        } catch (final Exception e) {
            System.err.println("Failed to load window icon: " + e.getMessage());
        }
    }

    private static void loadBanner() {
        try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
            final byte[] bannerBytes = in.readAllBytes();
            final ImageData bannerImageData = CodecsAPI.decodeImage(bannerBytes);
            if (bannerImageData == null || bannerImageData.frames().length == 0) return;

            ctx.bannerWidth = bannerImageData.width();
            ctx.bannerHeight = bannerImageData.height();
            ctx.bannerTextureId = glGenTextures();

            glBindTexture(GL_TEXTURE_2D, ctx.bannerTextureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ctx.bannerWidth, ctx.bannerHeight,
                    0, GL_BGRA, GL_UNSIGNED_BYTE, bannerImageData.frames()[0]);
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

            ctx.soundBuffer = alGenBuffers();
            alBufferData(ctx.soundBuffer, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, pcmBuffer, sampleRate);
            MemoryUtil.memFree(pcmBuffer);

            ctx.soundSource = alGenSources();
            alSourcei(ctx.soundSource, AL_BUFFER, ctx.soundBuffer);
            alSourcef(ctx.soundSource, AL_GAIN, 0.2f);
        } catch (final Exception e) {
            System.err.println("Failed to load sound effect: " + e.getMessage());
        }
    }

    // CONSOLE OPERATIONS
    private static void performUploadLogs() {
        try {
            consoleScreen.info("=== Upload Log Files ===");
            consoleScreen.print("");

            final Path cwd = Path.of("").toAbsolutePath();
            final Path logsDir = cwd.getParent().resolve("logs");
            final Path crashDir = cwd.getParent().resolve("crash-reports");

            consoleScreen.print("Working directory: " + cwd);
            consoleScreen.print("Logs directory: " + logsDir);
            consoleScreen.print("");

            final Path latestLog = logsDir.resolve("latest.log");
            final Path wmLog = logsDir.resolve("watermedia-app.log");
            final Path crashReport = findLatestCrashReport(crashDir);

            consoleScreen.info("--- Reading files ---");
            final String latestContent = readFileStatus(latestLog, "latest.log");
            final String wmContent = readFileStatus(wmLog, "watermedia-app.log");
            final String crashContent = crashReport != null ? readFileStatus(crashReport, crashReport.getFileName().toString()) : null;

            if (crashReport == null) {
                consoleScreen.print("crash-reports: No crash reports found", Colors.GRAY);
            }

            consoleScreen.print("");
            consoleScreen.info("--- Uploading to mclo.gs ---");

            final String latestUrl = latestContent != null ? uploadToMclogs(latestContent, "latest.log") : null;
            final String wmUrl = wmContent != null ? uploadToMclogs(wmContent, "watermedia-app.log") : null;
            final String crashUrl = crashContent != null ? uploadToMclogs(crashContent, crashReport.getFileName().toString()) : null;

            consoleScreen.print("");

            if (latestUrl != null || wmUrl != null) {
                consoleScreen.info("--- Generating issue report ---");
                final String issueText = generateIssueTemplate(latestUrl, wmUrl, crashUrl);

                try {
                    final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    clipboard.setContents(new StringSelection(issueText), null);
                    consoleScreen.success("Issue template copied to clipboard!");
                } catch (final Exception e) {
                    consoleScreen.error("Failed to copy to clipboard: " + e.getMessage());
                }

                consoleScreen.print("");
                consoleScreen.print("Opening GitHub issues page...", Colors.YELLOW);

                try {
                    Desktop.getDesktop().browse(URI.create("https://github.com/WaterMediaTeam/watermedia/issues/new"));
                    consoleScreen.success("Browser opened!");
                    consoleScreen.success("Please open a github issue and paste the content of your clipboard");
                } catch (final Exception e) {
                    consoleScreen.error("Failed to open browser: " + e.getMessage());
                    consoleScreen.print("Please manually go to: https://github.com/WaterMediaTeam/watermedia/issues/new");
                }
            } else {
                consoleScreen.error("No files were uploaded successfully.");
            }

            consoleScreen.waitForKey();
        } catch (final Exception e) {
            consoleScreen.error("Unexpected error: " + e.getMessage());
            consoleScreen.waitForKey();
        }
    }

    private static void performCleanup() {
        try {
            consoleScreen.info("=== Cleanup ===");
            consoleScreen.print("");

            final Path wmPath = WaterMedia.cwd();
            final Path tmpDir = WaterMedia.tmp();

            consoleScreen.print("WaterMedia path: " + wmPath);
            consoleScreen.print("");
            consoleScreen.info("--- Cleaning tmp folder ---");

            final var dirs = tmpDir.toFile().listFiles();
            if (dirs == null) {
                consoleScreen.print("No tmp folder found", Colors.GRAY);
                consoleScreen.print("");
                consoleScreen.info("--- Cleanup complete ---");
                consoleScreen.print("Application will close after this.");
                consoleScreen.waitForKey();
                return;
            }

            final int tmpCount = IOTool.count(tmpDir.toFile());
            final int tmpDeleted = IOTool.delete(tmpDir.toFile());

            if (tmpDeleted > 0) {
                if (tmpDeleted == tmpCount) {
                    consoleScreen.success("Tmp folder cleaned successfully (" + tmpDeleted + " items removed)");
                } else {
                    consoleScreen.success("Tmp folder deleted almost successfully (" + tmpDeleted + " items removed - " + (tmpCount - tmpDeleted) + " items failed)");
                }
            } else {
                consoleScreen.error("Failed to delete tmp folder");
            }

            final int tmpDeleteScheduled = IOTool.deleteSchedule(tmpDir.toFile());
            if (tmpDeleteScheduled > 0) {
                consoleScreen.info("Additional " + tmpDeleteScheduled + " items scheduled for deletion on exit.");
            } else {
                consoleScreen.print("No additional items scheduled for deletion.", Colors.YELLOW);
            }

            consoleScreen.print("");
            consoleScreen.info("--- Cleanup complete ---");
            consoleScreen.print("Application will close after this.");
            consoleScreen.waitForKey();
        } catch (final Exception e) {
            consoleScreen.error("Unexpected error: " + e.getMessage());
            consoleScreen.waitForKey();
        }
    }

    private static Path findLatestCrashReport(final Path crashDir) {
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

    private static String readFileStatus(final Path path, final String name) {
        if (!Files.exists(path)) {
            consoleScreen.print(name + ": NOT FOUND", Colors.RED);
            return null;
        }
        try {
            final String content = Files.readString(path, StandardCharsets.UTF_8);
            consoleScreen.success(name + ": Read OK (" + content.length() + " bytes)");
            return content;
        } catch (final Exception e) {
            consoleScreen.error(name + ": FAILED TO READ - " + e.getMessage());
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
                final JsonObject json = AppContext.GSON.fromJson(response.body(), JsonObject.class);
                if (json.get("success").getAsBoolean()) {
                    final String url = json.get("url").getAsString();
                    consoleScreen.success(name + ": Uploaded -> " + url);
                    return url;
                }
            }
            consoleScreen.error(name + ": Upload failed (HTTP " + response.statusCode() + ")");
        } catch (final Exception e) {
            consoleScreen.error(name + ": Upload failed - " + e.getMessage());
        }
        return null;
    }

    private static String generateIssueTemplate(final String latestUrl, final String wmUrl, final String crashUrl) {
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

    // CLEANUP
    private static void cleanup() {
        DrawTool.cleanup();
        glfwFreeCallbacks(ctx.windowHandle);
        glfwDestroyWindow(ctx.windowHandle);
        glfwTerminate();
        glfwSetErrorCallback(null).close();
        System.exit(0);
    }

    // LOGGING
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
