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
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.bootstrap.app.screen.*;
import org.watermedia.bootstrap.app.ui.AppChrome;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Dimension;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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
    private static Dimension exitConfirmCancelBounds = Dimension.ZERO;
    private static Dimension exitConfirmExitBounds = Dimension.ZERO;
    private static Dimension exitConfirmCloseBounds = Dimension.ZERO;
    private static Dimension errorDialogActionBounds = Dimension.ZERO;

    static { initLogging(); }

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

    private static void runLoadingPhase(final LoadingScreen loadingScreen) {
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final Thread loader = ThreadTool.createStarted("WaterMedia-Init", () -> {
            try {
                WaterMedia.start("WaterMediaApp", null, null, true);
            } catch (final Throwable t) {
                failure.set(t);
            }
        });

        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {
        }, 0);
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

        final Throwable t = failure.get();
        if (t != null) {
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException(t);
        }
    }

    private static void renderLoadingFrame(final LoadingScreen loadingScreen) {
        RenderSystem.clear(0.04f, 0.06f, 0.12f, 1f);
        loadingScreen.render(ctx.windowWidth, ctx.windowHeight);
        glfwSwapBuffers(ctx.windowHandle);
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

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);

        ctx.windowHandle = glfwCreateWindow(1280, 720, AppContext.APP_NAME, NULL, NULL);
        if (ctx.windowHandle == NULL) throw new RuntimeException("Failed to create the GLFW window");
        maximized = true;
        ctx.windowMaximized = true;

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
                if (ctx.mouseY < AppChrome.TITLEBAR_H && !AppChrome.isTitlebarControl(ctx.mouseX, ctx.windowWidth)) {
                    final boolean restored = restoreForTitlebarDrag();
                    draggingTitlebar = true;
                    if (!restored) {
                        dragOffsetX = (int) ctx.mouseX;
                        dragOffsetY = (int) ctx.mouseY;
                    }
                }
            } else if (action == GLFW_RELEASE) {
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

        // CENTER WINDOW
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1);
            final IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(ctx.windowHandle, pWidth, pHeight);
            ctx.windowWidth = pWidth.get(0);
            ctx.windowHeight = pHeight.get(0);

            if (!maximized) {
                final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
                glfwSetWindowPos(ctx.windowHandle,
                        (vidmode.width() - ctx.windowWidth) / 2,
                        (vidmode.height() - ctx.windowHeight) / 2);
            }
        }

        glfwMakeContextCurrent(ctx.windowHandle);
        glfwSwapInterval(1);
        GL.createCapabilities();
        RenderSystem.init();
        RenderSystem.configureFrameState();
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
            ALC.createCapabilities(device);
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
                    ctx.uploadDialogVisible = false;
                    return;
                }
                if (!ctx.uploadDialogVisible) {
                    openUploadLogsDialog();
                    scanUploadLogFiles();
                    return;
                }
                if (ctx.uploadDialogWorking) {
                    ctx.uploadDialogVisible = true;
                    return;
                }
                if (ctx.uploadDialogStage <= 1) {
                    if (!hasUploadCandidate()) return;
                    ctx.uploadDialogWorking = true;
                    ThreadTool.createStarted("UploadLogs", WaterMediaApp::performUploadLogs);
                } else if (ctx.uploadDialogStage == 2) {
                    if (!hasUploadedLog()) return;
                    ThreadTool.createStarted("UploadLogsIssue", WaterMediaApp::generateUploadIssueReport);
                } else {
                    ThreadTool.createStarted("UploadLogsOpenGithub", WaterMediaApp::openUploadIssuePage);
                }
            }

            case CLEANUP -> {
                if (!ctx.cleanupDialogVisible) {
                    openCleanupDialog();
                    scanCleanupCache();
                    return;
                }
                if (ctx.cleanupDialogWorking) {
                    ctx.cleanupDialogVisible = true;
                    return;
                }
                if (ctx.cleanupDialogStage <= 1) {
                    if (ctx.cleanupFileCount <= 0) return;
                    ctx.cleanupDialogWorking = true;
                    ThreadTool.createStarted("CleanupCache", WaterMediaApp::performCacheCleanup);
                } else {
                    ctx.cleanupDialogVisible = false;
                    scanCleanupCache();
                }
            }
        }
    }

    // MAIN LOOP — GL state was already configured during the loading phase.
    private static void mainLoop() {
        final FrameLimiter frameLimiter = FrameLimiter.forWindow(ctx.windowHandle);
        ctx.requestRender();
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

            RenderSystem.flush();
            glfwSwapBuffers(ctx.windowHandle);
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
            screens.handleMouseMove(ctx.mouseX, ctx.mouseY);
            if (ctx.mouseClicked) {
                ctx.mouseClicked = false;
                screens.handleMouseClick(ctx.mouseX, ctx.mouseY);
            }
        }
    }

    private static void renderErrorDialog() {
        renderInfoDialog(ctx.globalErrorTitle == null ? "ERROR" : ctx.globalErrorTitle.toUpperCase(),
                ctx.globalErrorMessage == null ? "" : ctx.globalErrorMessage,
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
        ctx.text.render("EXIT WATERMEDIA", x + 22, y + Math.max(0, (titleH - ctx.text.glyphHeight(0.66f)) / 2f), AppTheme.RED, 0.66f);
        exitConfirmCloseBounds = new Dimension(x + dialogW - 48, y + 14, 30, 30);
        AppChrome.dialogCloseButton(exitConfirmCloseBounds, exitConfirmCloseBounds.contains(ctx.mouseX, ctx.mouseY));
        PixelIcon.draw("warn", x + 28, y + 88, 26, AppTheme.RED);
        ctx.text.render("CONFIRM EXIT", x + 68, y + 84, AppTheme.TEXT, 0.72f);
        ctx.text.render("Press ENTER to close the app or ESC to return.", x + 68, y + 116, AppTheme.TEXT_SOFT, 0.56f);
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
        ctx.text.render(title, x + 22, y + Math.max(0, (titleH - ctx.text.glyphHeight(0.66f)) / 2f), accent, 0.66f);
        PixelIcon.draw("warn", x + 28, y + 84, 28, accent);
        int lineY = y + 86;
        for (final String line : lines) {
            ctx.text.render(line, x + 72, lineY, AppTheme.TEXT_SOFT, 0.58f);
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
        RenderSystem.fill(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                hover ? AppTheme.alpha(accent, 52) : AppTheme.BG_2);
        RenderSystem.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), accent, 1.2f);
        PixelIcon.draw(icon, bounds.x() + 12, bounds.y() + (bounds.height() - 13) / 2, 13, accent);
        ctx.text.render(label, bounds.x() + 32,
                bounds.y() + Math.max(0, (bounds.height() - ctx.text.glyphHeight(0.60f)) / 2f),
                accent, 0.60f);
        final float keyScale = 0.48f;
        final int keyW = ctx.text.width(hotkey, keyScale) + 12;
        final int keyH = 20;
        final int keyX = bounds.right() - keyW - 8;
        final int keyY = bounds.y() + (bounds.height() - keyH) / 2;
        RenderSystem.rect(keyX, keyY, keyW, keyH, AppTheme.STROKE, 1f);
        ctx.text.render(hotkey, keyX + 6, keyY + Math.max(0, (keyH - ctx.text.glyphHeight(keyScale)) / 2f),
                AppTheme.TEXT_FAINT, keyScale);
    }

    private static void renderBottomBar() {
        final String instructions = exitConfirmVisible ? "ENTER: Exit | ESC: Cancel"
                : ctx.hasError() ? "ENTER/ESC: Close"
                : screens.currentInstructions() + " | C: CRT " + (AppChrome.crtEnabled() ? "ON" : "OFF");
        AppChrome.footer(ctx.text, ctx, ctx.windowWidth, ctx.windowHeight, instructions, -1f);
    }

    private static void openUploadLogsDialog() {
        ctx.uploadDialogVisible = true;
        ctx.uploadDialogWorking = false;
        ctx.uploadDialogDone = false;
        ctx.uploadDialogError = false;
        ctx.uploadUploadsDone = false;
        ctx.uploadIssueCopied = false;
        ctx.uploadIssueOpened = false;
        ctx.uploadDialogStage = 1;
        ctx.uploadDialogStatus = "SCAN";
        ctx.uploadIssueUrl = "github.com/watermedia/issues/new";
        ctx.uploadDialogFiles.clear();
    }

    private static void openCleanupDialog() {
        ctx.cleanupDialogVisible = true;
        ctx.cleanupDialogWorking = false;
        ctx.cleanupDialogDone = false;
        ctx.cleanupDialogError = false;
        ctx.cleanupDialogStage = 1;
        ctx.cleanupDialogState = "SCAN";
        ctx.cleanupFileCount = 0;
        ctx.cleanupSizeLabel = "0 B";
        ctx.cleanupProgress = 0;
    }

    // INPUT HANDLING
    private static void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        ctx.requestRender();
        ctx.ctrlDown = (mods & GLFW_MOD_CONTROL) != 0
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;

        if (action == GLFW_RELEASE && key == GLFW_KEY_C) {
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
        try (final InputStream in = openFirstResource("pack.png", "icon.png")) {
            if (in == null) return;
            final BufferedImage img = ImageIO.read(in);
            if (img == null) return;

            final int w = img.getWidth(), h = img.getHeight();
            final ByteBuffer buffer = argbToRgbaBuffer(img);

            final GLFWImage.Buffer icons = GLFWImage.malloc(1);
            icons.position(0).width(w).height(h).pixels(buffer);
            glfwSetWindowIcon(ctx.windowHandle, icons);

            icons.free();
            MemoryUtil.memFree(buffer);

            final ByteBuffer textureBuffer = argbToRgbaBuffer(img);
            ctx.iconWidth = w;
            ctx.iconHeight = h;
            ctx.iconTextureId = RenderSystem.createTexture(w, h, textureBuffer);
            MemoryUtil.memFree(textureBuffer);

            final TextureData glow = createGlowTexture(img, new Color(110, 168, 255), 12, 0.72f);
            ctx.iconGlowTextureId = glow.textureId();
            ctx.iconGlowWidth = glow.width();
            ctx.iconGlowHeight = glow.height();
        } catch (final Exception e) {
            System.err.println("Failed to load window icon: " + e.getMessage());
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

        ctx.duckFrameTextureIds = frames.stream().mapToInt(Integer::intValue).toArray();
        ctx.duckFrameWidth = frameWidth;
        ctx.duckFrameHeight = frameHeight;
    }

    private static InputStream openFirstResource(final String... names) {
        for (final String name: names) {
            try {
                return IOTool.jarOpenFile(name);
            } catch (final Exception ignored) {
            }
        }
        return null;
    }

    private static void loadBanner() {
        try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
            final BufferedImage img = ImageIO.read(in);
            if (img == null) return;

            ctx.bannerWidth = img.getWidth();
            ctx.bannerHeight = img.getHeight();

            final ByteBuffer buffer = argbToRgbaBuffer(img);
            ctx.bannerTextureId = RenderSystem.createTexture(ctx.bannerWidth, ctx.bannerHeight, buffer);
            MemoryUtil.memFree(buffer);

            final TextureData glow = createGlowTexture(img, new Color(110, 168, 255), 48, 0.8f);
            ctx.bannerGlowTextureId = glow.textureId();
            ctx.bannerGlowWidth = glow.width();
            ctx.bannerGlowHeight = glow.height();
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
        ctx.uploadDialogStage = 1;
        ctx.uploadDialogStatus = "SCAN";
        ctx.uploadDialogError = false;
        ctx.uploadDialogDone = false;
        ctx.uploadDialogFiles.clear();

        final Path baseDir = uploadBaseDir();
        final Path logsDir = baseDir.resolve("logs");
        final Path crashDir = baseDir.resolve("crash-reports");
        final Path crashReport = findLatestCrashReport(crashDir);

        addScannedUploadFile("latest.log", logsDir.resolve("latest.log"));
        addScannedUploadFile(crashReport != null ? crashReport.getFileName().toString() : "crash-reports", crashReport != null ? crashReport : crashDir);
        addScannedUploadFile("watermedia-app.log", logsDir.resolve("watermedia-app.log"));
        ctx.requestRender();
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
                    ctx.uploadDialogError = true;
                } else {
                    entry.state = "READ OK";
                }
            } catch (final IOException e) {
                entry.present = false;
                entry.valid = false;
                entry.state = "READ ERROR";
                entry.sizeLabel = "-";
                ctx.uploadDialogError = true;
            }
        } else {
            entry.present = false;
            entry.valid = false;
            entry.state = "NOT FOUND";
            entry.sizeLabel = "-";
        }
        ctx.uploadDialogFiles.add(entry);
    }

    private static boolean exceedsLineLimit(final Path path, final int maxLines) throws IOException {
        try (final var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.limit(maxLines + 1L).count() > maxLines;
        }
    }

    private static void performUploadLogs() {
        try {
            ctx.uploadDialogStage = 2;
            setUploadStatus("UPLOAD");
            ctx.uploadDialogError = false;
            ctx.uploadDialogDone = false;
            ctx.uploadUploadsDone = false;

            boolean anyUploaded = false;
            for (final AppContext.UploadFileEntry entry: ctx.uploadDialogFiles) {
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
                ctx.uploadUploadsDone = !ctx.uploadDialogError && uploadsComplete();
            } else {
                setUploadStatus("ERROR");
                ctx.uploadDialogError = true;
            }
        } catch (final Exception e) {
            setUploadStatus("ERROR");
            ctx.uploadDialogError = true;
        } finally {
            ctx.uploadDialogWorking = false;
            ctx.uploadDialogDone = false;
            ctx.requestRender();
        }
    }

    private static void generateUploadIssueReport() {
        try {
            ctx.uploadDialogWorking = true;
            ctx.uploadDialogStage = 3;
            ctx.uploadDialogStatus = "SUCCESS";
            ctx.uploadDialogError = false;

            final String issueText = generateIssueTemplate(
                    uploadedUrl("latest.log"),
                    uploadedUrl("watermedia-app.log"),
                    firstUploadedCrashUrl()
            );
            ctx.uploadIssueUrl = buildGithubIssueUrl(issueText);

            try {
                final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(issueText), null);
                ctx.uploadIssueCopied = true;
            } catch (final Exception e) {
                ctx.uploadDialogError = true;
                ctx.uploadDialogStatus = "ERROR";
            }

            openUploadIssuePage();
            ctx.uploadDialogDone = !ctx.uploadDialogError;
        } finally {
            ctx.uploadDialogWorking = false;
            ctx.requestRender();
        }
    }

    private static void openUploadIssuePage() {
        try {
            final String url = ctx.uploadIssueUrl != null && ctx.uploadIssueUrl.startsWith("https://")
                    ? ctx.uploadIssueUrl
                    : buildGithubIssueUrl("");
            Desktop.getDesktop().browse(URI.create(url));
            ctx.uploadIssueOpened = true;
        } catch (final Exception e) {
            ctx.uploadDialogError = true;
            ctx.uploadDialogStatus = "ERROR";
        }
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
        ctx.uploadDialogStatus = status;
        ctx.requestRender();
    }

    private static boolean hasUploadCandidate() {
        for (final AppContext.UploadFileEntry entry: ctx.uploadDialogFiles) {
            if (isUploadable(entry)) return true;
        }
        return false;
    }

    private static boolean hasUploadedLog() {
        for (final AppContext.UploadFileEntry entry: ctx.uploadDialogFiles) {
            if (entry.uploaded) return true;
        }
        return false;
    }

    private static boolean uploadsComplete() {
        boolean hadUploadable = false;
        for (final AppContext.UploadFileEntry entry: ctx.uploadDialogFiles) {
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
            return Files.readString(entry.path, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            entry.state = "READ ERROR";
            entry.progress = 0;
            ctx.uploadDialogError = true;
            return null;
        }
    }

    private static String uploadedUrl(final String name) {
        for (final AppContext.UploadFileEntry entry: ctx.uploadDialogFiles) {
            if (entry.name.equalsIgnoreCase(name) && entry.uploaded) return entry.url;
        }
        return null;
    }

    private static String firstUploadedCrashUrl() {
        for (final AppContext.UploadFileEntry entry: ctx.uploadDialogFiles) {
            if (!entry.name.equalsIgnoreCase("latest.log") && !entry.name.equalsIgnoreCase("watermedia-app.log") && entry.uploaded) {
                return entry.url;
            }
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
        ctx.cleanupDialogStage = 1;
        ctx.cleanupDialogDone = false;
        ctx.cleanupDialogError = false;
        ctx.cleanupProgress = 0;

        final long[] stats = cleanupCacheStats();
        ctx.cleanupFileCount = (int) Math.min(Integer.MAX_VALUE, stats[0]);
        ctx.cleanupSizeLabel = formatBytes(stats[1]);
        if (!ctx.cleanupDialogError) {
            ctx.cleanupDialogState = stats[0] > 0 ? "FOUND" : "EMPTY";
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
            ctx.cleanupDialogError = true;
            ctx.cleanupDialogState = "ERROR";
        }
        return stats;
    }

    private static Path cleanupCacheDir() {
        return WaterMedia.tmp().resolve("cache");
    }

    private static void performCacheCleanup() {
        try {
            ctx.cleanupDialogStage = 2;
            ctx.cleanupDialogState = "CLEANING";
            ctx.cleanupDialogError = false;
            ctx.cleanupDialogDone = false;
            ctx.cleanupProgress = 12;
            ctx.requestRender();

            final Path cache = cleanupCacheDir();
            if (Files.exists(cache)) {
                IOTool.delete(cache.toFile());
            }
            Files.createDirectories(cache);

            ctx.cleanupProgress = 100;
            ctx.cleanupDialogState = "CLEANED";
            ctx.cleanupDialogDone = true;
        } catch (final Exception e) {
            ctx.cleanupProgress = 0;
            ctx.cleanupDialogState = "ERROR";
            ctx.cleanupDialogError = true;
        } finally {
            ctx.cleanupDialogWorking = false;
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
            ctx.uploadDialogError = true;
        } catch (final Exception e) {
            entry.progress = 0;
            entry.state = "FAILED";
            ctx.uploadDialogError = true;
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
        if (logfile.exists() && !logfile.renameTo(new File("logs/watermedia-app-" + new Date() + ".log"))) {
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
