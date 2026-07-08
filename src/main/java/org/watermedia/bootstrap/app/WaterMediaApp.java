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
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.bootstrap.app.screen.*;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.view.Button;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * WATERMeDIA Test Application.
 */
public class WaterMediaApp {
    private static final AppContext ctx = new AppContext();
    private static final ScreenManager screens = new ScreenManager();

    private static boolean running = true;
    private static boolean maximized;
    private static boolean draggingTitlebar;
    private static boolean exitConfirmVisible;
    private static int dragOffsetX;
    private static int dragOffsetY;
    // LAST CURSOR POSITION A HOVER DISPATCH WAS ISSUED FOR — NaN SO THE FIRST FRAME ALWAYS DISPATCHES
    private static double lastMoveX = Double.NaN;
    private static double lastMoveY = Double.NaN;
    private static Dimension exitConfirmCancelBounds = Dimension.ZERO;
    private static Dimension exitConfirmExitBounds = Dimension.ZERO;
    private static Dimension exitConfirmCloseBounds = Dimension.ZERO;
    private static Dimension errorDialogActionBounds = Dimension.ZERO;

    static {
        // LWJGL'S DEFAULT 64KB PER-THREAD MemoryStack OVERFLOWS WHEN VULKAN ENUMERATES A GPU'S
        // (HUNDREDS OF) DEVICE EXTENSIONS DURING VkInstance INIT. RAISE IT BEFORE ANY MemoryStack
        // IS CREATED (THE FIRST stackPush HAPPENS LATER, IN initWindow → centerWindow).
        Configuration.STACK_SIZE.set(1024);
        initLogging();
    }

    public static void start(final Runnable task) {
        // PHASE 1 — get the window on screen ASAP. Window creation, GL setup,
        // text renderer, and icon/banner decoding (via ImageIO so they don't
        // need CodecsAPI yet). Audio is deferred to phase 3 so AppBootstrap's
        // countdown end → visible window has the shortest possible gap.
        initWindow();
        ctx.text = new TextRenderer();
        ctx.text.margin(6);
        loadIcon();
        loadDuckFrames();
        loadBanner();
        glfwShowWindow(ctx.windowHandle);

        // PHASE 2 — show the loading splash, initialize the app-side audio
        // output, then run WaterMedia.start() in the background. The splash
        // polls WaterMedia progress each frame and renders the boot stack.
        final LoadingScreen loadingScreen = new LoadingScreen(ctx.text, ctx);
        renderLoadingFrame(loadingScreen);
        initAudio();
        runLoadingPhase(loadingScreen);

        // PHASE 3 — final init that depends on WaterMedia (or that we delayed
        // to keep phase 1 fast).
        ctx.uriGroups = AppContext.GSON.fromJson(IOTool.jarRead("uris.json"), AppContext.URIGroup[].class);
        loadIptvCatalog();
        if (!WaterMedia.LOGGER.isDebugEnabled()) {
            for (int i = 0; i < ctx.uriGroups.length; i++) {
                final AppContext.URIGroup group = ctx.uriGroups[i];
                final AppContext.TestURI[] filtered = Arrays.stream(group.uris()).filter(u -> !u.debug()).toArray(AppContext.TestURI[]::new);
                if (filtered.length != group.uris().length) {
                    ctx.uriGroups[i] = new AppContext.URIGroup(group.name(), filtered);
                }
            }
        }
        initScreens();
        task.run();
        mainLoop();
        cleanup();
    }

    private static void loadIptvCatalog() {
        try {
            final AppContext.IptvCatalog catalog = AppContext.GSON.fromJson(IOTool.jarRead("iptv.json"), AppContext.IptvCatalog.class);
            ctx.iptvChannels = catalog == null || catalog.channels() == null ? new AppContext.IptvChannel[0] : catalog.channels();
        } catch (final RuntimeException e) {
            ctx.iptvChannels = new AppContext.IptvChannel[0];
            WaterMedia.LOGGER.warn("Failed to load IPTV catalog", e);
        }
    }

    private static void runLoadingPhase(final LoadingScreen loadingScreen) {
        // SINGLE-SLOT HOLDER FOR THE LOADER THREAD'S FAILURE — loader.join() BELOW ESTABLISHES THE
        // HAPPENS-BEFORE THAT MAKES THE WRITE VISIBLE HERE, SO NO Atomic*/volatile IS NEEDED.
        final Throwable[] failure = new Throwable[1];
        final Thread loader = ThreadTool.createStarted("WaterMediaApp-Init", () -> {
            try {
                WaterMedia.start("WaterMediaApp", null, null, true);
            } catch (final Throwable t) {
                failure[0] = t;
            }
        });

        RenderSystem.configureFrameState();

        final FrameLimiter loadingLimiter = FrameLimiter.forWindow(ctx.windowHandle);
        while (loader.isAlive() && running && !glfwWindowShouldClose(ctx.windowHandle)) {
            loadingLimiter.syncBeforeFrame();
            renderLoadingFrame(loadingScreen);
        }

        try {
            loader.join();
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        ctx.backendsLoading = false;
        renderLoadingFrame(loadingScreen);

        final Throwable t = failure[0];
        if (t != null) {
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException(t);
        }
    }

    private static void renderLoadingFrame(final LoadingScreen loadingScreen) {
        RenderSystem.beginFrame();
        RenderSystem.clear(0.04f, 0.06f, 0.12f, 1f);
        loadingScreen.render(ctx.windowWidth, ctx.windowHeight);
        RenderSystem.present();
        glfwPollEvents();
        ctx.mouseClicked = false; // discard input collected during loading
    }

    public static void log(final String message) {
        WaterMedia.LOGGER.info(MarkerManager.getMarker("ROOT"), message);
    }

    // INITIALIZATION
    private static void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        // THE RENDER LAYER OWNS THE ENGINE CHOICE (FROM -Dwatermedia.engine) AND THE BACKEND LIFECYCLE.
        // THE APP ONLY CREATES THE WINDOW AND, IF VULKAN FAILS TO ATTACH, REBUILDS IT FOR OPENGL.
        // APPLY THE SETTINGS-PERSISTED ENGINE CHOICE UNLESS AN EXPLICIT -Dwatermedia.engine OVERRIDES IT.
        if (System.getProperty("watermedia.engine") == null) {
            final RenderSystem.Engine pref = RenderSystem.enginePreference();
            if (pref != null) System.setProperty("watermedia.engine", pref == RenderSystem.Engine.VULKAN ? "vulkan" : "opengl");
        }
        RenderSystem.chooseEngine();
        createWindow();
        if (!RenderSystem.attach(ctx.windowHandle)) {
            glfwFreeCallbacks(ctx.windowHandle);
            glfwDestroyWindow(ctx.windowHandle);
            createWindow(); // ENGINE IS NOW OPENGL; REBUILD WITH A GL CONTEXT AND ATTACH AGAIN
            RenderSystem.attach(ctx.windowHandle);
        }
    }

    private static void createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);
        RenderSystem.applyWindowHints();

        ctx.windowHandle = glfwCreateWindow(1280, 720, AppContext.APP_NAME, NULL, NULL);
        if (ctx.windowHandle == NULL) throw new RuntimeException("Failed to create the GLFW window");
        maximized = true;
        ctx.windowMaximized = true;

        installCallbacks();
        centerWindow();
    }

    private static void installCallbacks() {
        // CALLBACKS
        glfwSetKeyCallback(ctx.windowHandle, WaterMediaApp::handleKeyInput);
        glfwSetCharCallback(ctx.windowHandle, WaterMediaApp::handleCharInput);
        glfwSetCursorPosCallback(ctx.windowHandle, (w, x, y) -> {
            ctx.mouseX = x;
            ctx.mouseY = y;
            ctx.requestRender();
            if (draggingTitlebar) {
                try (final MemoryStack stack = stackPush()) {
                    final IntBuffer wx = stack.mallocInt(1);
                    final IntBuffer wy = stack.mallocInt(1);
                    glfwGetWindowPos(ctx.windowHandle, wx, wy);
                    glfwSetWindowPos(ctx.windowHandle,
                            wx.get(0) + (int) x - dragOffsetX,
                            wy.get(0) + (int) y - dragOffsetY);
                }
            }
        });
        glfwSetMouseButtonCallback(ctx.windowHandle, (w, button, action, mods) -> {
            if (button != GLFW_MOUSE_BUTTON_LEFT) return;
            ctx.requestRender();
            if (action == GLFW_PRESS) {
                ctx.mouseDown = true;
                if (ctx.mouseY < AppChrome.TITLEBAR_H && !AppChrome.isTitlebarControl(ctx.mouseX, ctx.windowWidth)) {
                    final boolean restored = restoreForTitlebarDrag();
                    draggingTitlebar = true;
                    if (!restored) {
                        dragOffsetX = (int) ctx.mouseX;
                        dragOffsetY = (int) ctx.mouseY;
                    }
                } else if (ctx.mouseY >= AppChrome.TITLEBAR_H) {
                    ctx.mousePressed = true;
                }
            } else if (action == GLFW_RELEASE) {
                ctx.mouseDown = false;
                draggingTitlebar = false;
                ctx.mouseClicked = true;
            }
        });
        glfwSetScrollCallback(ctx.windowHandle, (w, xOffset, yOffset) -> {
            if (!ctx.hasError()) {
                screens.handleScroll(yOffset);
            }
            ctx.requestRender();
        });
        glfwSetWindowSizeCallback(ctx.windowHandle, (w, width, height) -> {
            ctx.windowWidth = width;
            ctx.windowHeight = height;
            RenderSystem.viewport(width, height);
            ctx.requestRender();
        });
        glfwSetWindowMaximizeCallback(ctx.windowHandle, (w, isMaximized) -> {
            maximized = isMaximized;
            ctx.windowMaximized = maximized;
            ctx.requestRender();
        });
    }

    private static void centerWindow() {
        // CENTER WINDOW
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1);
            final IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(ctx.windowHandle, pWidth, pHeight);
            ctx.windowWidth = pWidth.get(0);
            ctx.windowHeight = pHeight.get(0);

            if (!maximized) {
                final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
                if (vidmode != null) {
                    glfwSetWindowPos(ctx.windowHandle,
                            (vidmode.width() - ctx.windowWidth) / 2,
                            (vidmode.height() - ctx.windowHeight) / 2);
                }
            }
        }
    }

    private static boolean restoreForTitlebarDrag() {
        if (!maximized) return false;
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer winX = stack.mallocInt(1);
            final IntBuffer winY = stack.mallocInt(1);
            glfwGetWindowPos(ctx.windowHandle, winX, winY);

            final double globalCursorX = winX.get(0) + ctx.mouseX;
            final double globalCursorY = winY.get(0) + ctx.mouseY;
            final double xRatio = ctx.windowWidth <= 0 ? 0.5d : Math.max(0d, Math.min(1d, ctx.mouseX / ctx.windowWidth));

            glfwRestoreWindow(ctx.windowHandle);
            maximized = false;
            ctx.windowMaximized = false;

            final IntBuffer restoredW = stack.mallocInt(1);
            final IntBuffer restoredH = stack.mallocInt(1);
            glfwGetWindowSize(ctx.windowHandle, restoredW, restoredH);
            final int newW = restoredW.get(0);
            final int newH = restoredH.get(0);
            ctx.windowWidth = newW;
            ctx.windowHeight = newH;
            RenderSystem.viewport(newW, newH);

            dragOffsetX = Math.max(0, Math.min(newW - 1, (int) Math.round(newW * xRatio)));
            dragOffsetY = (int) Math.max(0, Math.min(AppChrome.TITLEBAR_H - 1, ctx.mouseY));
            glfwSetWindowPos(ctx.windowHandle, (int) Math.round(globalCursorX - dragOffsetX), (int) Math.round(globalCursorY - dragOffsetY));
            ctx.requestRender();
            return true;
        }
    }

    private static void initAudio() {
        ctx.audioReady = false;
        ctx.audioError = false;
        try {
            final long device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0L) throw new IllegalStateException("Failed to open a new Audio Device");
            final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(ALC.createCapabilities(device));

            loadSoundClick();
            ctx.audioReady = true;
        } catch (final RuntimeException e) {
            ctx.audioError = true;
            throw e;
        }
    }

    private static void initScreens() {
        final HomeScreen homeScreen = new HomeScreen(ctx.text, ctx, WaterMediaApp::navigateAction);

        screens.register("home", homeScreen);
        screens.register("mrl", new MRLSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("regions", new RegionSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("player", new PlayerScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("multimedia", new OpenMultimediaScreen(ctx.text, ctx, WaterMediaApp::navigateAction, homeScreen));
        screens.register("settings", new SettingsScreen(ctx.text, ctx, WaterMediaApp::navigateAction));

        screens.navigate("home");
    }

    private static void navigateAction(final HomeScreen.Action action) {
        ctx.requestRender();
        if (action == null) {
            screens.backToHome();
            return;
        }

        switch (action) {
            case EXIT -> exitConfirmVisible = true;

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

            case REGION_SELECTOR -> screens.navigate("regions");

            case SETTINGS -> {
                if (WaterMedia.LOGGER.isDebugEnabled()) {
                    screens.navigate("settings");
                }
            }

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
                if (!AppContext.IN_MODS) {
                    ctx.upload.visible = false;
                    return;
                }
                if (!ctx.upload.visible) {
                    openUploadLogsDialog();
                    scanUploadLogFiles();
                    return;
                }
                if (ctx.upload.working) {
                    ctx.upload.visible = true;
                    return;
                }
                if (ctx.upload.stage <= 1) {
                    if (!hasUploadCandidate()) return;
                    ctx.upload.working = true;
                    ThreadTool.createStarted("WaterMediaApp-UploadLogs", WaterMediaApp::uploadLogs);
                } else if (ctx.upload.stage == 2) {
                    if (!hasUploadedLog()) return;
                    ThreadTool.createStarted("WaterMediaApp-UploadIssueReport", WaterMediaApp::uploadIssueReport);
                } else {
                    if (ctx.upload.repoUrl == null || ctx.upload.repoUrl.isBlank()) return;
                    ThreadTool.createStarted("WaterMediaApp-OpenRepoPage", WaterMediaApp::openRepoPage);
                }
            }

            case CLEANUP -> {
                if (!ctx.cleanup.visible) {
                    openCleanupDialog();
                    scanCleanupCache();
                    return;
                }
                if (ctx.cleanup.working) {
                    ctx.cleanup.visible = true;
                    return;
                }
                if (ctx.cleanup.stage <= 1) {
                    if (ctx.cleanup.fileCount <= 0) return;
                    ctx.cleanup.working = true;
                    ThreadTool.createStarted("WaterMediaApp-CacheCleanup", WaterMediaApp::cleanupCache);
                } else {
                    ctx.cleanup.visible = false;
                    scanCleanupCache();
                }
            }
        }
    }

    // MAIN LOOP — GL state was already configured during the loading phase.
    private static void mainLoop() {
        final FrameLimiter frameLimiter = FrameLimiter.forWindow(ctx.windowHandle);
        ctx.requestRender();
        // DRIVE THE SLOW GLOW HEARTBEAT ON RENDER-ON-DEMAND SCREENS: NUDGE A REDRAW ~15x/s SO THE PULSE
        // ADVANCES. CONTINUOUS SCREENS (VIDEO / CRT) ALREADY REPAINT FASTER; requestRender COALESCES.
        ThreadTool.createStarted("WaterMedia-GlowPulse", () -> {
            while (running) {
                ctx.requestRender();
                ThreadTool.sleep(66);
            }
        });
        while (running && !glfwWindowShouldClose(ctx.windowHandle)) {
            final boolean continuous = screens.wantsContinuousRender();
            if (continuous) {
                frameLimiter.syncBeforeFrame();
                glfwPollEvents();
            } else if (!ctx.renderRequested()) {
                glfwWaitEventsTimeout(frameLimiter.idleTimeoutSeconds());
            } else {
                glfwPollEvents();
            }

            handleFrameInput();
            ctx.processExecutor();

            if (!continuous && !ctx.consumeRenderRequest()) {
                continue;
            }
            if (!continuous) {
                frameLimiter.syncBeforeFrame();
            }

            RenderSystem.beginFrame();
            RenderSystem.clear(0.04f, 0.06f, 0.12f, 1f);

            screens.render(ctx.windowWidth, ctx.windowHeight);

            // RENDER GLOBAL ERROR DIALOG ON TOP IF PRESENT
            if (ctx.hasError()) {
                renderErrorDialog();
            }
            if (exitConfirmVisible) {
                renderExitConfirmDialog();
            }

            renderBottomBar();

            RenderSystem.present();
        }

        ctx.releasePlayer();
    }

    private static void handleFrameInput() {
        if (exitConfirmVisible) {
            if (ctx.mouseClicked) {
                ctx.mouseClicked = false;
                if (exitConfirmExitBounds.contains(ctx.mouseX, ctx.mouseY)) {
                    running = false;
                } else if (exitConfirmCancelBounds.contains(ctx.mouseX, ctx.mouseY) || exitConfirmCloseBounds.contains(ctx.mouseX, ctx.mouseY)) {
                    exitConfirmVisible = false;
                }
            }
        } else if (ctx.mouseClicked && AppChrome.handleTitlebarClick(ctx.mouseX, ctx.mouseY, ctx.windowWidth,
                () -> glfwIconifyWindow(ctx.windowHandle),
                () -> {
                    if (maximized) {
                        glfwRestoreWindow(ctx.windowHandle);
                        maximized = false;
                    } else {
                        glfwMaximizeWindow(ctx.windowHandle);
                        maximized = true;
                    }
                    ctx.windowMaximized = maximized;
                },
                () -> running = false)) {
            ctx.mouseClicked = false;
        } else if (ctx.hasError()) {
            if (ctx.mouseClicked) {
                ctx.mouseClicked = false;
                if (errorDialogActionBounds.contains(ctx.mouseX, ctx.mouseY)) {
                    ctx.clearError();
                }
            }
        } else {
            if (ctx.mousePressed) {
                ctx.mousePressed = false;
                screens.handleMousePress(ctx.mouseX, ctx.mouseY);
            }
            // ONLY DISPATCH HOVER ON REAL CURSOR MOVEMENT — A STATIONARY CURSOR PARKED OVER A HIT BOX MUST
            // NOT RE-ASSERT HOVER SELECTION EVERY FRAME AND SILENTLY OVERRIDE KEYBOARD NAVIGATION (H-01).
            if (ctx.mouseX != lastMoveX || ctx.mouseY != lastMoveY) {
                lastMoveX = ctx.mouseX;
                lastMoveY = ctx.mouseY;
                screens.handleMouseMove(ctx.mouseX, ctx.mouseY);
            }
            if (ctx.mouseClicked) {
                ctx.mouseClicked = false;
                screens.handleMouseRelease(ctx.mouseX, ctx.mouseY);
                screens.handleMouseClick(ctx.mouseX, ctx.mouseY);
            }
        }
    }

    private static void renderErrorDialog() {
        renderInfoDialog(ctx.error.title == null ? "ERROR" : ctx.error.title.toUpperCase(),
                ctx.error.message == null ? "" : ctx.error.message,
                "OK", "ENTER", AppTheme.RED);
    }

    private static void renderExitConfirmDialog() {
        final int dialogW = Math.min(560, ctx.windowWidth - 64);
        final int dialogH = 230;
        final Dimension dialog = Dimension.centered(ctx.windowWidth, ctx.windowHeight, dialogW, dialogH);
        final int x = dialog.x();
        final int y = dialog.y();
        final int titleH = 58;
        RenderSystem.setupOrtho(ctx.windowWidth, ctx.windowHeight);
        RenderSystem.fill(0, 0, ctx.windowWidth, ctx.windowHeight, 0f, 0f, 0f, 0.58f);
        RenderSystem.shadowRect(x, y, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(x, y, dialogW, dialogH, 0f, AppTheme.RED, 0.25f);
        RenderSystem.fill(x, y, dialogW, dialogH, AppTheme.alpha(AppTheme.BG_1, 248));
        RenderSystem.rect(x, y, dialogW, dialogH, AppTheme.RED, 1.5f);
        RenderSystem.fill(x, y, dialogW, titleH, AppTheme.alpha(AppTheme.BG_2, 244));
        RenderSystem.lineH(x, y + titleH, dialogW, AppTheme.STROKE_BRIGHT, 1f);
        ctx.text.renderBold("EXIT WATERMEDIA", x + 22, y + Math.max(0, (titleH - ctx.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f), AppTheme.RED, AppTheme.TEXT_BUTTON);
        exitConfirmCloseBounds = new Dimension(x + dialogW - 48, y + 14, 30, 30);
        AppChrome.dialogCloseButton(exitConfirmCloseBounds, exitConfirmCloseBounds.contains(ctx.mouseX, ctx.mouseY));
        PixelIcon.draw("warn", x + 28, y + 88, 26, AppTheme.RED);
        ctx.text.renderBold("CONFIRM EXIT", x + 68, y + 84, AppTheme.TEXT, AppTheme.TEXT_BUTTON);
        ctx.text.render("Press ENTER to close the app or ESC to return.", x + 68, y + 116, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        exitConfirmCancelBounds = new Dimension(x + 24, y + dialogH - 58, 150, 36);
        exitConfirmExitBounds = new Dimension(x + dialogW - 174, y + dialogH - 58, 150, 36);
        renderDialogAction(exitConfirmCancelBounds, "CANCEL", "ESC", "x", AppTheme.TEXT_SOFT);
        renderDialogAction(exitConfirmExitBounds, "EXIT", "ENTER", "x", AppTheme.RED);
        RenderSystem.restoreProjection();
    }

    private static void renderInfoDialog(final String title, final String message, final String buttonLabel,
                                         final String hotkey, final Color accent) {
        final int dialogW = Math.min(640, ctx.windowWidth - 64);
        final String[] lines = message.split("\n");
        final int dialogH = Math.min(Math.max(220, 134 + lines.length * 26), ctx.windowHeight - 72);
        final Dimension dialog = Dimension.centered(ctx.windowWidth, ctx.windowHeight, dialogW, dialogH);
        final int x = dialog.x();
        final int y = dialog.y();
        final int titleH = 58;
        RenderSystem.setupOrtho(ctx.windowWidth, ctx.windowHeight);
        RenderSystem.fill(0, 0, ctx.windowWidth, ctx.windowHeight, 0f, 0f, 0f, 0.58f);
        RenderSystem.shadowRect(x, y, dialogW, dialogH, 0f, 0.55f);
        RenderSystem.glowRect(x, y, dialogW, dialogH, 0f, accent, 0.25f);
        RenderSystem.fill(x, y, dialogW, dialogH, AppTheme.alpha(AppTheme.BG_1, 248));
        RenderSystem.rect(x, y, dialogW, dialogH, accent, 1.5f);
        RenderSystem.fill(x, y, dialogW, titleH, AppTheme.alpha(AppTheme.BG_2, 244));
        RenderSystem.lineH(x, y + titleH, dialogW, AppTheme.STROKE_BRIGHT, 1f);
        ctx.text.renderBold(title, x + 22, y + Math.max(0, (titleH - ctx.text.glyphHeightBold(AppTheme.TEXT_BUTTON)) / 2f), accent, AppTheme.TEXT_BUTTON);
        PixelIcon.draw("warn", x + 28, y + 84, 28, accent);
        int lineY = y + 86;
        for (final String line : lines) {
            ctx.text.render(line, x + 72, lineY, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
            lineY += 26;
        }
        final Dimension ok = new Dimension(x + dialogW - 174, y + dialogH - 58, 150, 36);
        errorDialogActionBounds = ok;
        renderDialogAction(ok, buttonLabel, hotkey, "check", accent);
        RenderSystem.restoreProjection();
    }

    private static void renderDialogAction(final Dimension bounds, final String label, final String hotkey,
                                           final String icon, final Color accent) {
        final boolean hover = bounds.contains(ctx.mouseX, ctx.mouseY);
        Button.render(ctx.text, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                label, hotkey, icon, 12, accent, accent, false, hover, true);
    }

    private static void renderBottomBar() {
        ctx.configStatus.visible = "settings".equals(screens.currentName());
        final String instructions = exitConfirmVisible ? "ENTER: Exit | ESC: Cancel"
                : ctx.hasError() ? "ENTER/ESC: Close"
                : screens.currentInstructions() + " | C: CRT " + (AppChrome.crtEnabled() ? "ON" : "OFF");
        AppChrome.footer(ctx.text, ctx, ctx.windowWidth, ctx.windowHeight, instructions, -1f);
    }

    private static void openUploadLogsDialog() {
        ctx.upload.visible = true;
        ctx.upload.working = false;
        ctx.upload.done = false;
        ctx.upload.error = false;
        ctx.upload.uploadsDone = false;
        ctx.upload.issueCopied = false;
        ctx.upload.issueOpened = false;
        ctx.upload.stage = 1;
        ctx.upload.status = "SCAN";
        ctx.upload.issueUrl = "github.com/watermedia/issues/new";
        ctx.upload.repoUrl = null;
        ctx.upload.files.clear();
    }

    private static void openCleanupDialog() {
        ctx.cleanup.visible = true;
        ctx.cleanup.working = false;
        ctx.cleanup.done = false;
        ctx.cleanup.error = false;
        ctx.cleanup.stage = 1;
        ctx.cleanup.state = "SCAN";
        ctx.cleanup.fileCount = 0;
        ctx.cleanup.sizeLabel = "0 B";
        ctx.cleanup.progress = 0;
    }

    // INPUT HANDLING
    private static void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        ctx.requestRender();
        ctx.ctrlDown = (mods & GLFW_MOD_CONTROL) != 0
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;

        // GLOBAL CRT TOGGLE — BUT NOT WHILE A TEXT FIELD IS FOCUSED, OR TYPING A 'c' WOULD BOTH INSERT THE
        // CHARACTER (VIA THE CHAR CALLBACK) AND FLIP THE CRT OVERLAY (M-02).
        if (action == GLFW_RELEASE && key == GLFW_KEY_C && !screens.textInputFocused()) {
            AppChrome.toggleCrt();
            return;
        }

        if (exitConfirmVisible) {
            if (action == GLFW_RELEASE) {
                if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                    running = false;
                } else if (key == GLFW_KEY_ESCAPE) {
                    exitConfirmVisible = false;
                }
            }
            return;
        }

        // ERROR DIALOG TAKES PRIORITY
        if (ctx.hasError()) {
            if (action == GLFW_RELEASE && (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER || key == GLFW_KEY_ESCAPE)) {
                ctx.clearError();
            }
            return;
        }

        screens.handleKey(key, action);
    }

    private static void handleCharInput(final long window, final int codepoint) {
        ctx.requestRender();
        if (ctx.hasError()) return;
        screens.handleChar(codepoint);
    }

    // RESOURCE LOADING
    // Icon and banner are decoded with ImageIO so they're available before
    // CodecsAPI loads — this lets the loading splash render the banner.
    private static void loadIcon() {
        // WINDOW/TASKBAR ICON — THE OS SCALES IT DOWN SMALL, SO USE THE DEDICATED icon.png
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            if (in != null) {
                final BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    final int w = img.getWidth(), h = img.getHeight();
                    final ByteBuffer buffer = argbToRgbaBuffer(img);

                    final GLFWImage.Buffer icons = GLFWImage.malloc(1);
                    icons.position(0).width(w).height(h).pixels(buffer);
                    glfwSetWindowIcon(ctx.windowHandle, icons);

                    icons.free();
                    MemoryUtil.memFree(buffer);
                }
            }
        } catch (final Exception e) {
            System.err.println("Failed to load window icon: " + e.getMessage());
        }

        // ON-SCREEN LOGO (TITLE BAR, LOADING SPLASH, HOME HERO) — RENDERED LARGE, SO USE THE HIGHER-RES pack.png
        try (final InputStream in = IOTool.jarOpenFile("pack.png")) {
            if (in == null) return;
            final BufferedImage img = ImageIO.read(in);
            if (img == null) return;

            final int w = img.getWidth(), h = img.getHeight();
            final ByteBuffer textureBuffer = argbToRgbaBuffer(img);
            ctx.assets.iconWidth = w;
            ctx.assets.iconHeight = h;
            ctx.assets.iconId = RenderSystem.createTexture(w, h, textureBuffer);
            MemoryUtil.memFree(textureBuffer);

            final TextureData glow = createGlowTexture(img, new Color(110, 168, 255), 12, 0.72f);
            ctx.assets.iconGlowId = glow.textureId();
            ctx.assets.iconGlowWidth = glow.width();
            ctx.assets.iconGlowHeight = glow.height();
        } catch (final Exception e) {
            System.err.println("Failed to load logo texture: " + e.getMessage());
        }
    }

    private static void loadDuckFrames() {
        final java.util.ArrayList<Integer> frames = new java.util.ArrayList<>();
        int frameWidth = 0;
        int frameHeight = 0;

        for (int i = 0; ; i++) {
            final String resource = String.format("assets/duck/%02d.png", i);
            final InputStream stream = IOTool.jarOpenFile(resource);
            if (stream == null) break;

            try (stream) {
                final BufferedImage img = ImageIO.read(stream);
                if (img == null) continue;
                if (frameWidth <= 0 || frameHeight <= 0) {
                    frameWidth = img.getWidth();
                    frameHeight = img.getHeight();
                }

                final ByteBuffer buffer = argbToRgbaBuffer(img);
                frames.add(RenderSystem.createTexture(img.getWidth(), img.getHeight(), buffer));
                MemoryUtil.memFree(buffer);
            } catch (final Exception e) {
                System.err.println("Failed to load duck frame " + resource + ": " + e.getMessage());
            }
        }

        ctx.assets.duckFrameIds = frames.stream().mapToInt(Integer::intValue).toArray();
        ctx.assets.duckFrameWidth = frameWidth;
        ctx.assets.duckFrameHeight = frameHeight;
    }

    private static void loadBanner() {
        try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
            final BufferedImage img = ImageIO.read(in);
            if (img == null) return;

            ctx.assets.bannerWidth = img.getWidth();
            ctx.assets.bannerHeight = img.getHeight();

            final ByteBuffer buffer = argbToRgbaBuffer(img);
            ctx.assets.bannerId = RenderSystem.createTexture(ctx.assets.bannerWidth, ctx.assets.bannerHeight, buffer);
            MemoryUtil.memFree(buffer);

            final TextureData glow = createGlowTexture(img, new Color(110, 168, 255), 48, 0.8f);
            ctx.assets.bannerGlowId = glow.textureId();
            ctx.assets.bannerGlowWidth = glow.width();
            ctx.assets.bannerGlowHeight = glow.height();
        } catch (final Exception e) {
            System.err.println("Failed to load banner: " + e.getMessage());
        }
    }

    private static TextureData createGlowTexture(final BufferedImage source, final Color color,
                                                 final int radius, final float strength) {
        final BufferedImage glow = createAlphaGlow(source, color, radius, strength);
        final ByteBuffer buffer = argbToRgbaBuffer(glow);
        final int textureId = RenderSystem.createTexture(glow.getWidth(), glow.getHeight(), buffer);
        MemoryUtil.memFree(buffer);
        return new TextureData(textureId, glow.getWidth(), glow.getHeight());
    }

    private static BufferedImage createAlphaGlow(final BufferedImage source, final Color color,
                                                final int radius, final float strength) {
        final int pad = Math.max(1, radius * 3);
        final int w = source.getWidth() + pad * 2;
        final int h = source.getHeight() + pad * 2;
        int[] alpha = new int[w * h];

        // EXTRAE SOLO LA SILUETA ALFA PARA QUE EL GLOW RESPETE PNGS TRANSPARENTES.
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                alpha[(y + pad) * w + x + pad] = (source.getRGB(x, y) >>> 24) & 0xFF;
            }
        }

        for (int i = 0; i < 3; i++) {
            alpha = boxBlur(alpha, w, h, radius);
        }

        final int[] pixels = new int[w * h];
        final int rgb = color.getRGB() & 0x00FFFFFF;
        for (int i = 0; i < alpha.length; i++) {
            final int a = Math.min(255, Math.round(alpha[i] * strength));
            pixels[i] = (a << 24) | rgb;
        }

        final BufferedImage glow = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        glow.setRGB(0, 0, w, h, pixels, 0, w);
        return glow;
    }

    private static int[] boxBlur(final int[] source, final int w, final int h, final int radius) {
        final int[] horizontal = new int[w * h];
        final int[] output = new int[w * h];
        final int window = radius * 2 + 1;

        // DOS PASADAS CON VENTANA DESLIZANTE: O(W*H) EN VEZ DE O(W*H*R).
        for (int y = 0; y < h; y++) {
            int sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += source[y * w + clamp(x, 0, w - 1)];
            }
            for (int x = 0; x < w; x++) {
                horizontal[y * w + x] = sum / window;
                final int removeX = clamp(x - radius, 0, w - 1);
                final int addX = clamp(x + radius + 1, 0, w - 1);
                sum += source[y * w + addX] - source[y * w + removeX];
            }
        }

        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += horizontal[clamp(y, 0, h - 1) * w + x];
            }
            for (int y = 0; y < h; y++) {
                output[y * w + x] = sum / window;
                final int removeY = clamp(y - radius, 0, h - 1);
                final int addY = clamp(y + radius + 1, 0, h - 1);
                sum += horizontal[addY * w + x] - horizontal[removeY * w + x];
            }
        }
        return output;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ByteBuffer argbToRgbaBuffer(final BufferedImage img) {
        final int w = img.getWidth(), h = img.getHeight();
        final int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        final ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
        for (final int p: argb) {
            buffer.put((byte) ((p >> 16) & 0xFF)); // R
            buffer.put((byte) ((p >> 8) & 0xFF));  // G
            buffer.put((byte) (p & 0xFF));         // B
            buffer.put((byte) ((p >> 24) & 0xFF)); // A
        }
        buffer.flip();
        return buffer;
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

    private record TextureData(int textureId, int width, int height) {
    }

    // BACKGROUND OPERATIONS
    private static void scanUploadLogFiles() {
        ctx.upload.stage = 1;
        ctx.upload.status = "SCAN";
        ctx.upload.error = false;
        ctx.upload.done = false;
        ctx.upload.files.clear();

        final Path baseDir = uploadBaseDir();
        final Path logsDir = baseDir.resolve("logs");
        final Path crashDir = baseDir.resolve("crash-reports");
        final Path crashReport = findLatestCrashReport(crashDir);
        // JVM FATAL ERROR LOGS (hs_err_pid<pid>.log) LAND IN THE PROCESS WORKING DIR BY DEFAULT
        final Path hsErr = findLatestHsErr(baseDir);

        addScannedUploadFile("latest.log", logsDir.resolve("latest.log"));
        addScannedUploadFile(crashReport != null ? crashReport.getFileName().toString() : "crash-reports", crashReport != null ? crashReport : crashDir);
        addScannedUploadFile("watermedia-app.log", logsDir.resolve("watermedia-app.log"));
        addScannedUploadFile(hsErr != null ? hsErr.getFileName().toString() : "hs_err_pid.log", hsErr != null ? hsErr : baseDir.resolve("hs_err_pid.log"));
        scanSuspectMods();
        ctx.requestRender();
    }

    private static void scanSuspectMods() {
        ctx.upload.suspectModIds.clear();
        if (!AppContext.IN_MODS) return;
        // IN_MODS MEANS THE WORKING DIR IS THE MODS FOLDER — A CANDIDATE COUNTS ONLY IF A JAR WHOSE
        // FILENAME CONTAINS ITS ID IS PRESENT (id "fancymenu" MATCHES "FancyMenu-Forge-1.20.1-x.y.z.jar").
        final Path modsDir = Path.of("").toAbsolutePath();
        try (final var stream = Files.list(modsDir)) {
            final List<String> jars = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(n -> n.endsWith(".jar"))
                    .toList();
            for (final AppContext.SuspectMod mod: AppContext.SUSPECT_MODS) {
                for (final String jar: jars) {
                    if (jar.contains(mod.id())) {
                        ctx.upload.suspectModIds.add(mod.id());
                        break;
                    }
                }
            }
        } catch (final IOException ignored) {
        }
    }

    private static void addScannedUploadFile(final String name, final Path path) {
        final AppContext.UploadFileEntry entry = new AppContext.UploadFileEntry(name, path);
        if (path != null && Files.isRegularFile(path)) {
            try {
                entry.present = true;
                entry.valid = true;
                final long size = Files.size(path);
                entry.sizeLabel = formatBytes(size);
                if (size > 10L * 1024L * 1024L || exceedsLineLimit(path, 25_000)) {
                    entry.valid = false;
                    entry.state = "INVALID";
                    ctx.upload.error = true;
                } else {
                    entry.state = "READ OK";
                }
            } catch (final IOException e) {
                entry.present = false;
                entry.valid = false;
                entry.state = "READ ERROR";
                entry.sizeLabel = "-";
                ctx.upload.error = true;
            }
        } else {
            entry.present = false;
            entry.valid = false;
            entry.state = "NOT FOUND";
            entry.sizeLabel = "-";
        }
        ctx.upload.files.add(entry);
    }

    private static boolean exceedsLineLimit(final Path path, final int maxLines) throws IOException {
        try (final BufferedReader reader = lenientReader(path)) {
            int lines = 0;
            while (reader.readLine() != null) {
                if (++lines > maxLines) return true;
            }
            return false;
        }
    }

    // hs_err DUMPS AND SOME LOGS CARRY NON-UTF-8 BYTES; REPLACE THEM INSTEAD OF FAILING THE WHOLE READ.
    private static BufferedReader lenientReader(final Path path) throws IOException {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder));
    }

    private static void uploadLogs() {
        try {
            ctx.upload.stage = 2;
            setUploadStatus("UPLOAD");
            ctx.upload.error = false;
            ctx.upload.done = false;
            ctx.upload.uploadsDone = false;

            boolean anyUploaded = false;
            for (final AppContext.UploadFileEntry entry: ctx.upload.files) {
                if (!isUploadable(entry)) continue;
                entry.state = "UPLOADING";
                entry.progress = 18;
                final String content = readUploadContent(entry);
                if (content == null) continue;
                entry.progress = 64;
                final String url = uploadToMclogs(content, entry);
                anyUploaded |= url != null;
            }

            if (anyUploaded) {
                setUploadStatus("REPORT READY");
                ctx.upload.uploadsDone = !ctx.upload.error && uploadsComplete();
            } else {
                setUploadStatus("ERROR");
                ctx.upload.error = true;
            }
        } catch (final Exception e) {
            setUploadStatus("ERROR");
            ctx.upload.error = true;
        } finally {
            ctx.upload.working = false;
            ctx.upload.done = false;
            ctx.requestRender();
        }
    }

    private static void uploadIssueReport() {
        try {
            ctx.upload.working = true;
            ctx.upload.stage = 3;
            ctx.upload.status = "SUCCESS";
            ctx.upload.error = false;

            final String issueText = generateIssueTemplate(
                    uploadedUrl("latest.log"),
                    uploadedUrl("watermedia-app.log"),
                    firstUploadedCrashUrl(),
                    uploadedHsErrUrl()
            );
            ctx.upload.issueUrl = buildGithubIssueUrl(issueText);
            // DEFAULT SUBMIT TARGET IS WATERMEDIA; THE STAGE-3 SCREEN LETS THE USER PICK A SUSPECTED MOD INSTEAD.
            ctx.upload.repoUrl = ctx.upload.issueUrl;

            try {
                final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(issueText), null);
                ctx.upload.issueCopied = true;
            } catch (final Exception e) {
                ctx.upload.error = true;
                ctx.upload.status = "ERROR";
            }

            // DO NOT OPEN THE BROWSER AUTOMATICALLY — THE USER CHOOSES WHICH ISSUE TRACKER TO SUBMIT TO.
            ctx.upload.done = !ctx.upload.error;
        } finally {
            ctx.upload.working = false;
            ctx.requestRender();
        }
    }

    private static void openRepoPage() {
        try {
            final String target = ctx.upload.repoUrl;
            final String url = target != null && target.startsWith("http") ? target : buildGithubIssueUrl("");
            Desktop.getDesktop().browse(URI.create(url));
            ctx.upload.issueOpened = true;
        } catch (final Exception e) {
            ctx.upload.error = true;
            ctx.upload.status = "ERROR";
        }
        ctx.requestRender();
    }

    private static String buildGithubIssueUrl(final String body) {
        return "https://github.com/WaterMediaTeam/watermedia/issues/new"
                + "?title=" + java.net.URLEncoder.encode("WATERMeDIA Generated Issue", StandardCharsets.UTF_8)
                + "&body=" + java.net.URLEncoder.encode(body == null ? "" : body, StandardCharsets.UTF_8);
    }

    private static Path uploadBaseDir() {
        final Path cwd = Path.of("").toAbsolutePath();
        return AppContext.IN_MODS && cwd.getParent() != null ? cwd.getParent() : cwd;
    }

    private static void setUploadStatus(final String status) {
        ctx.upload.status = status;
        ctx.requestRender();
    }

    private static boolean hasUploadCandidate() {
        for (final AppContext.UploadFileEntry entry: ctx.upload.files) {
            if (isUploadable(entry)) return true;
        }
        return false;
    }

    private static boolean hasUploadedLog() {
        for (final AppContext.UploadFileEntry entry: ctx.upload.files) {
            if (entry.uploaded) return true;
        }
        return false;
    }

    private static boolean uploadsComplete() {
        boolean hadUploadable = false;
        for (final AppContext.UploadFileEntry entry: ctx.upload.files) {
            if (!entry.present || !entry.valid) continue;
            hadUploadable = true;
            if (!entry.uploaded) return false;
        }
        return hadUploadable;
    }

    private static boolean isUploadable(final AppContext.UploadFileEntry entry) {
        return entry.present && entry.valid && !entry.uploaded && !"FAILED".equals(entry.state) && !"READ ERROR".equals(entry.state);
    }

    private static String readUploadContent(final AppContext.UploadFileEntry entry) {
        try {
            // REDACT SESSION/ACCESS TOKENS AND OTHER SECRETS BEFORE THEY EVER LEAVE THE MACHINE.
            // hs_err DUMPS AND LAUNCH LOGS OFTEN CARRY THE FULL --accessToken IN THE JVM COMMAND LINE.
            final StringBuilder sb = new StringBuilder();
            try (final BufferedReader reader = lenientReader(entry.path)) {
                final char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            }
            return sanitizeUpload(sb.toString());
        } catch (final Exception e) {
            entry.state = "READ ERROR";
            entry.progress = 0;
            ctx.upload.error = true;
            return null;
        }
    }

    // SECRET REDACTION — MASK APPLIED IN PLACE OF ANY TOKEN-LIKE VALUE
    private static final String SECRET_MASK = "********";
    // MINECRAFT/MSA ACCESS TOKENS ARE JWTS (eyJ<header>.<payload>.<signature>)
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}");
    // LAUNCH ARGS THAT CARRY THE SESSION/ACCESS TOKEN: --accessToken <v>, --session <v> (token:<token>:<uuid>)
    private static final Pattern LAUNCH_ARG = Pattern.compile("(?i)(--(?:accessToken|session)\\s+)(\\S+)");
    // BARE LEGACY SESSION STRING token:<accessToken>:<profileId>
    private static final Pattern SESSION_TRIPLE = Pattern.compile("(?i)\\btoken:[^\\s:]+(?::[0-9a-fA-F-]+)?");
    // Authorization: Bearer <token> / Authorization: <token>
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)(\\S+)");
    // SENSITIVE KEY = VALUE / KEY: VALUE / "key":"value"
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)(\"?\\b(?:access[_-]?token|session[_-]?token|sessionId|auth[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|secret|password|passwd|pwd|api[_-]?key|apikey)\\b\"?\\s*[:=]\\s*\"?)([^\\s\"',&}]+)");

    private static String sanitizeUpload(final String content) {
        if (content == null || content.isEmpty()) return content;
        String out = JWT.matcher(content).replaceAll(SECRET_MASK);
        out = LAUNCH_ARG.matcher(out).replaceAll("$1" + SECRET_MASK);
        out = AUTH_HEADER.matcher(out).replaceAll("$1" + SECRET_MASK);
        out = SECRET_KV.matcher(out).replaceAll("$1" + SECRET_MASK);
        out = SESSION_TRIPLE.matcher(out).replaceAll("token:" + SECRET_MASK);
        return out;
    }

    private static String uploadedUrl(final String name) {
        for (final AppContext.UploadFileEntry entry: ctx.upload.files) {
            if (entry.name.equalsIgnoreCase(name) && entry.uploaded) return entry.url;
        }
        return null;
    }

    private static String firstUploadedCrashUrl() {
        for (final AppContext.UploadFileEntry entry: ctx.upload.files) {
            if (!entry.name.equalsIgnoreCase("latest.log") && !entry.name.equalsIgnoreCase("watermedia-app.log")
                    && !entry.name.startsWith("hs_err_pid") && entry.uploaded) {
                return entry.url;
            }
        }
        return null;
    }

    private static String uploadedHsErrUrl() {
        for (final AppContext.UploadFileEntry entry: ctx.upload.files) {
            if (entry.name.startsWith("hs_err_pid") && entry.uploaded) return entry.url;
        }
        return null;
    }

    private static String formatBytes(final long bytes) {
        if (bytes < 1024L) return bytes + " B";
        final double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        return String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    private static void scanCleanupCache() {
        ctx.cleanup.stage = 1;
        ctx.cleanup.done = false;
        ctx.cleanup.error = false;
        ctx.cleanup.progress = 0;

        final long[] stats = cleanupCacheStats();
        ctx.cleanup.fileCount = (int) Math.min(Integer.MAX_VALUE, stats[0]);
        ctx.cleanup.sizeLabel = formatBytes(stats[1]);
        if (!ctx.cleanup.error) {
            ctx.cleanup.state = stats[0] > 0 ? "FOUND" : "EMPTY";
        }
        ctx.requestRender();
    }

    private static long[] cleanupCacheStats() {
        final Path cache = cleanupCacheDir();
        final long[] stats = new long[2];
        if (!Files.exists(cache)) return stats;
        try (final var stream = Files.walk(cache)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                stats[0]++;
                try {
                    stats[1] += Files.size(path);
                } catch (final IOException ignored) {
                }
            });
        } catch (final IOException e) {
            ctx.cleanup.error = true;
            ctx.cleanup.state = "ERROR";
        }
        return stats;
    }

    private static Path cleanupCacheDir() {
        return WaterMedia.tmp().resolve("cache");
    }

    private static void cleanupCache() {
        try {
            ctx.cleanup.stage = 2;
            ctx.cleanup.state = "CLEANING";
            ctx.cleanup.error = false;
            ctx.cleanup.done = false;
            ctx.cleanup.progress = 12;
            ctx.requestRender();

            final Path cache = cleanupCacheDir();
            if (Files.exists(cache)) {
                IOTool.delete(cache.toFile());
            }
            Files.createDirectories(cache);

            ctx.cleanup.progress = 100;
            ctx.cleanup.state = "CLEANED";
            ctx.cleanup.done = true;
        } catch (final Exception e) {
            ctx.cleanup.progress = 0;
            ctx.cleanup.state = "ERROR";
            ctx.cleanup.error = true;
        } finally {
            ctx.cleanup.working = false;
            ctx.requestRender();
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

    private static Path findLatestHsErr(final Path baseDir) {
        if (!Files.isDirectory(baseDir)) return null;
        try (final var stream = Files.list(baseDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("hs_err_pid"))
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

    private static String uploadToMclogs(final String content, final AppContext.UploadFileEntry entry) {
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
                    entry.url = url;
                    entry.uploaded = true;
                    entry.progress = 100;
                    entry.state = "UPLOADED";
                    return url;
                }
            }
            entry.progress = 0;
            entry.state = "FAILED";
            ctx.upload.error = true;
        } catch (final Exception e) {
            entry.progress = 0;
            entry.state = "FAILED";
            ctx.upload.error = true;
        }
        return null;
    }

    private static String generateIssueTemplate(final String latestUrl, final String wmUrl, final String crashUrl, final String hsErrUrl) {
        final Runtime rt = Runtime.getRuntime();
        return "This is an automated issue report generated by WATERMeDIA: Multimedia API.\n\n" +
                "## Alerts\n" +
                alertsSection() + "\n" +
                "## Files\n" +
                "- Logs: " + (latestUrl != null ? latestUrl : "N/A") + "\n" +
                "- Crash-report: " + (crashUrl != null ? crashUrl : "N/A") + "\n" +
                "- JVM crash (hs_err): " + (hsErrUrl != null ? hsErrUrl : "N/A") + "\n" +
                "- WM Logs: " + (wmUrl != null ? wmUrl : "N/A") + "\n\n" +
                "## Environment\n" +
                "- WaterMedia: " + WaterMedia.VERSION + " (" + RenderSystem.engineVersionLabel() + ")\n" +
                "- Binaries: " + binariesVersion() + "\n" +
                "- FFmpeg: " + ffmpegVersion() + "\n" +
                "- FFmpeg HW accel: " + ffmpegHwAccel() + "\n" +
                "- FFmpeg codecs (SW): " + ffmpegSoftwareCodecs() + "\n" +
                "- Extensions: " + detectedExtensions() + "\n\n" +
                "## System Properties\n" +
                "- OS: " + osInfo() + "\n" +
                "- CPU: " + cpuInfo() + "\n" +
                "- GPU: " + gpuInfo() + "\n" +
                "- RAM (JVM max): " + rt.maxMemory() / 1024 / 1024 + " MB\n" +
                "- RAM (system): " + systemRam() + "\n" +
                "- Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n" +
                "- Java Home: " + System.getProperty("java.home") + "\n" +
                "- FFmpeg Path: " + ffmpegPath() + "\n" +
                "- FFmpeg Loaded: " + FFMediaPlayer.loaded() + "\n" +
                "- User Dir: " + System.getProperty("user.dir") + "\n";
    }

    // ENVIRONMENT / DIAGNOSTICS HELPERS FOR THE ISSUE TEMPLATE

    private static String binariesVersion() {
        // BINARIES SHIP AS THEIR OWN ARTIFACT; READ ITS JAR MANIFEST Implementation-Version WHEN NOT SHADED.
        try {
            final Package pkg = WaterMediaBinaries.class.getPackage();
            final String v = pkg == null ? null : pkg.getImplementationVersion();
            if (v != null && !v.isBlank()) return v;
        } catch (final Throwable ignored) {
        }
        return "unknown";
    }

    private static String ffmpegVersion() {
        if (!FFMediaPlayer.loaded()) return "not loaded";
        try {
            return "avutil " + avVersion(avutil.avutil_version())
                    + ", avcodec " + avVersion(avcodec.avcodec_version())
                    + ", avformat " + avVersion(avformat.avformat_version());
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String avVersion(final int packed) {
        // FFMPEG PACKS LIBRARY VERSIONS AS (major << 16) | (minor << 8) | micro
        return ((packed >> 16) & 0xFF) + "." + ((packed >> 8) & 0xFF) + "." + (packed & 0xFF);
    }

    private static String ffmpegHwAccel() {
        // WATERMEDIA'S OWN IMAGE CODECS ARE PURE-JAVA (ALWAYS PRESENT), SO THE USEFUL, VARIABLE SIGNAL IS
        // WHICH FFMPEG HARDWARE ACCELERATIONS THE PLATFORM BUILD/GPU EXPOSES (dxva2/d3d11va/cuda/qsv/vaapi...).
        if (!FFMediaPlayer.loaded()) return "not loaded";
        try {
            final StringBuilder sb = new StringBuilder();
            int type = avutil.AV_HWDEVICE_TYPE_NONE;
            while ((type = avutil.av_hwdevice_iterate_types(type)) != avutil.AV_HWDEVICE_TYPE_NONE) {
                final BytePointer name = avutil.av_hwdevice_get_type_name(type);
                if (name == null) continue;
                final String s = name.getString();
                if (s != null && !s.isBlank()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(s);
                }
            }
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String ffmpegSoftwareCodecs() {
        // SOFTWARE (NON-HW) DECODERS COMPILED INTO THIS BUILD. UNLIKE WATERMEDIA'S PURE-JAVA IMAGE CODECS,
        // THIS SET CAN DIFFER BY OS/ARCH (e.g. AN EXTERNAL-LIB DECODER PRESENT ON x64 BUT NOT ARM), SO IT IS
        // WORTH CAPTURING TO VALIDATE WHETHER A CODEC IS SIMPLY MISSING FROM THE PLATFORM BUILD.
        if (!FFMediaPlayer.loaded()) return "not loaded";
        try {
            final TreeSet<String> names = new TreeSet<>();
            try (final PointerPointer<Pointer> opaque = new PointerPointer<>(1L)) {
                opaque.put(0L, (Pointer) null);
                AVCodec c;
                while ((c = avcodec.av_codec_iterate(opaque)) != null) {
                    if (avcodec.av_codec_is_decoder(c) == 0) continue;
                    final int type = c.type();
                    if (type != avutil.AVMEDIA_TYPE_VIDEO && type != avutil.AVMEDIA_TYPE_AUDIO) continue;
                    if ((c.capabilities() & avcodec.AV_CODEC_CAP_HARDWARE) != 0) continue;
                    final BytePointer name = c.name();
                    if (name == null) continue;
                    final String s = name.getString();
                    if (s != null && !s.isBlank()) names.add(s);
                }
            }
            return names.isEmpty() ? "none" : String.join(", ", names);
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String alertsSection() {
        final List<String> alerts = new ArrayList<>();
        final String nvidia = nvidiaThreadedOptimizationAlert();
        if (nvidia != null) alerts.add(nvidia);
        if (FFMediaPlayer.loadError()) {
            alerts.add("FFmpeg failed to load — media playback is unavailable. See the attached logs for the native load error.");
        }
        if (alerts.isEmpty()) return "- None detected\n";
        final StringBuilder sb = new StringBuilder();
        for (final String a: alerts) sb.append("- ").append(a).append('\n');
        return sb.toString();
    }

    private static String nvidiaThreadedOptimizationAlert() {
        // "Threaded Optimization" IS AN OPENGL DRIVER SETTING KNOWN TO CRASH/STUTTER LWJGL APPS. THERE IS NO
        // PURE-JAVA API TO READ IT (NVAPI IS NATIVE), SO WE INFER THE RUNTIME STATE FROM THE DEDICATED GL WORKER
        // THREAD THE DRIVER SPAWNS INSIDE nvoglv64.dll WHEN IT IS ACTIVE. WINDOWS + OPENGL + NVIDIA ONLY.
        try {
            if (RenderSystem.engineKind() != RenderSystem.Engine.OPENGL) return null;
            final String os = System.getProperty("os.name");
            if (os == null || !os.toLowerCase(Locale.ROOT).contains("win")) return null;
            final String gpu = RenderSystem.deviceName();
            if (gpu == null || !gpu.toLowerCase(Locale.ROOT).contains("nvidia")) return null;

            final long pid = ProcessHandle.current().pid();
            final String out = runCommand(4000, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "$p=Get-Process -Id " + pid + "; $m=$p.Modules|?{$_.ModuleName -ieq 'nvoglv64.dll'}|select -First 1; "
                            + "if($m){$b=[int64]$m.BaseAddress;$e=$b+$m.ModuleMemorySize;"
                            + "($p.Threads|?{$a=[int64]$_.StartAddress;$a -ge $b -and $a -lt $e}).Count}else{'nomodule'}");
            if (out == null) return null;
            final int workers = parseIntOr(out.trim(), -1);
            if (workers > 0) {
                return "NVIDIA \"Threaded Optimization\" appears ENABLED (" + workers + " GL worker thread(s) inside nvoglv64.dll). "
                        + "It is a known cause of OpenGL crashes/stutter with LWJGL — turn it Off in "
                        + "NVIDIA Control Panel > Manage 3D Settings > Threaded Optimization.";
            }
        } catch (final Throwable ignored) {
        }
        return null;
    }

    private static String detectedExtensions() {
        // ENUMERATE THE BOOTSTRAP EXTENSION SPI PROVIDERS BY CLASS — provider.type() DOES NOT INSTANTIATE THEM.
        try {
            final StringBuilder sb = new StringBuilder();
            for (final var provider: ServiceLoader.load(AppBootstrap.Extension.class).stream().toList()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(provider.type().getSimpleName());
            }
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String osInfo() {
        final String name = System.getProperty("os.name");
        final String base = name + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")";
        if (name != null && name.toLowerCase(Locale.ROOT).contains("win")) {
            final String build = windowsBuild();
            if (build != null) return base + " [Build " + build + "]";
        }
        return base;
    }

    private static String windowsBuild() {
        // os.version ON WINDOWS IS ONLY "10.0" — THE REAL BUILD NUMBER COMES FROM "cmd /c ver".
        // MATCH THE DOTTED VERSION ITSELF, NOT THE WORD "Version" (cmd IS LOCALIZED, e.g. "Versión").
        final String out = runCommand(2500, "cmd", "/c", "ver");
        if (out == null) return null;
        final var m = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)").matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private static String cpuInfo() {
        // COMMERCIAL NAME (e.g. "AMD Ryzen 7 2700X") THEN THE RAW IDENTIFIER IN PARENTHESES, THEN CORE/THREAD COUNTS.
        final int logical = Runtime.getRuntime().availableProcessors();
        final String identifier = firstNonBlank(System.getenv("PROCESSOR_IDENTIFIER"), System.getProperty("os.arch"), "unknown").trim();
        String name = null;
        int cores = -1;
        int threads = logical;

        final String os = System.getProperty("os.name");
        if (os != null && os.toLowerCase(Locale.ROOT).contains("win")) {
            final String[] cpu = queryWindowsCpu();
            if (cpu != null) {
                if (!cpu[0].isBlank()) name = cleanCpuName(cpu[0]);
                cores = parseIntOr(cpu[1], -1);
                threads = parseIntOr(cpu[2], logical);
            }
        }

        final String counts = cores > 0 ? cores + " cores / " + threads + " threads" : threads + " threads";
        return (name != null && !name.isBlank() ? name + " (" + identifier + ")" : identifier) + " - " + counts;
    }

    private static String cleanCpuName(String name) {
        // STRIP MARKETING FLUFF: "AMD Ryzen 7 2700X Eight-Core Processor" -> "AMD Ryzen 7 2700X",
        // "Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz" -> "Intel Xeon E5-2680 v4".
        name = name.replaceAll("\\((?:R|r|TM|tm)\\)", "");   // (R) / (TM) MARKS
        name = name.replaceAll("(?i)\\s*@.*$", "");          // "@ 3.60GHz" CLOCK TAIL
        name = name.replaceAll("(?i)\\s+\\w+-Core Processor\\b", ""); // "Eight-Core Processor" / "16-Core Processor"
        name = name.replaceAll("(?i)\\bCPU\\b", "");         // STANDALONE "CPU" TOKEN (INTEL PUTS IT BEFORE THE MODEL)
        name = name.replaceAll("(?i)\\s+Processor\\b", "");  // TRAILING "Processor"
        return name.replaceAll("\\s+", " ").trim();
    }

    private static String[] queryWindowsCpu() {
        // {commercialName, physicalCores, logicalThreads} FROM Win32_Processor, OR null ON FAILURE.
        final String out = runCommand(4000, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "$p=@(Get-CimInstance Win32_Processor)[0]; '{0}|{1}|{2}' -f $p.Name,$p.NumberOfCores,$p.NumberOfLogicalProcessors");
        if (out == null) return null;
        for (final String raw: out.split("\\R")) {
            final String[] parts = raw.trim().split("\\|", -1);
            if (parts.length >= 3 && !parts[0].isBlank()) {
                return new String[]{parts[0].trim(), parts[1].trim(), parts[2].trim()};
            }
        }
        return null;
    }

    private static String runCommand(final long timeoutMs, final String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            try (final InputStream in = p.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (final Throwable t) {
            if (p != null) p.destroyForcibly();
            return null;
        }
    }

    private static String firstNonBlank(final String... values) {
        for (final String v: values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static int parseIntOr(final String s, final int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (final Exception e) {
            return fallback;
        }
    }

    private static String gpuInfo() {
        try {
            final String gpu = RenderSystem.deviceName();
            return gpu != null && !gpu.isBlank() ? gpu : "unknown";
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    private static String systemRam() {
        try {
            final java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize() / 1024 / 1024 + " MB";
            }
        } catch (final Throwable ignored) {
        }
        return "unknown";
    }

    private static String ffmpegPath() {
        try {
            final Path p = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID);
            return p != null ? p.toAbsolutePath().toString() : "N/A";
        } catch (final Throwable t) {
            return "N/A";
        }
    }

    // CLEANUP
    private static void cleanup() {
        // FINISH ANY BACKGROUND PLAYER RELEASE BEFORE TEARING DOWN THE RENDER DEVICE, OR A VULKAN
        // RELEASE COULD DESTROY IMAGES ON AN ALREADY-DESTROYED DEVICE.
        ctx.awaitPlayerRelease();
        RenderSystem.cleanup();
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
        // FILESYSTEM-SAFE STAMP — new Date().toString() EMITS ':' AND SPACES, ILLEGAL IN WINDOWS FILENAMES,
        // SO renameTo() SILENTLY FAILED EVERY LAUNCH AND THE LOG NEVER ROTATED (GREW UNBOUNDED) (M-01).
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        if (logfile.exists() && !logfile.renameTo(new File("logs/watermedia-app-" + stamp + ".log"))) {
            System.err.println("Failed to rotate log file");
        }

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.WARN);

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
