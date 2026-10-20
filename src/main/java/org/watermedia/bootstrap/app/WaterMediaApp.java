package org.watermedia.bootstrap.app;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.bootstrap.app.screen.*;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Entry point of the standalone app: window, render loop, engine hot swap, screens and the log upload and cache
 * cleanup actions.
 */
public class WaterMediaApp {
    private static final Marker IT = MarkerManager.getMarker(WaterMediaApp.class.getSimpleName());
    private static final List<Keybind> KEYS_ERROR = List.of(new Keybind("ENTER/ESC", "Close"));
    private static final List<Keybind> KEYS_EXIT = List.of(new Keybind("ENTER", "Exit"), new Keybind("ESC", "Cancel"));
    private static final AppContext ctx = new AppContext();
    private static final ScreenManager screens = new ScreenManager();

    // CLEARED BY THE RENDER THREAD ON EXIT OR A FAILED SWAP, READ BY THE GLOW-PULSE THREAD
    private static volatile boolean running = true;
    // WRITTEN BY THE GLFW ICONIFY CALLBACK, READ BY THE GLOW-PULSE THREAD
    private static volatile boolean iconified;
    private static boolean maximized;
    // PENDING HOT-SWAP TARGET (null = NONE), CONSUMED AT THE TOP OF THE MAIN LOOP, NEVER MID-FRAME OR IN A CALLBACK
    private static volatile RenderSystem.Engine swapTarget;
    // A FIELD BECAUSE THE SWAP REBUILDS IT FOR THE NEW WINDOW
    private static FrameLimiter frameLimiter;
    // LAST CURSOR POSITION A HOVER DISPATCH WAS ISSUED FOR — NaN SO THE FIRST FRAME ALWAYS DISPATCHES
    private static double lastMoveX = Double.NaN;
    private static double lastMoveY = Double.NaN;

    // WINDOW SHELL — THE WHOLE WINDOW RENDERS THROUGH THIS RETAINED TREE
    private static RootScreen root;
    private static Dialog errorDialog;
    private static Parent errorLines;
    private static String errorShownMsg;
    private static Dialog exitDialog;

    static {
        // LWJGL'S DEFAULT 64KB MemoryStack OVERFLOWS WHILE VULKAN ENUMERATES HUNDREDS OF DEVICE EXTENSIONS;
        // IT MUST GROW BEFORE THE FIRST stackPush
        Configuration.STACK_SIZE.set(1024);
        initLogging();
    }

    public static void start(final Runnable task) {
        // PHASE 1: SHOW THE WINDOW ASAP; THE APP SPEC REGISTERS FIRST SO ITS LOAD ON THE WATERCONFIG IO POOL
        // OVERLAPS THE SLOWER WINDOW AND ENGINE BOOT
        AppConfig.register();
        initWindow();
        ctx.text = new TextRenderer();
        ctx.text.margin(6);
        // THE UI SCALE NEEDS THE WINDOW'S MONITOR AND THE TEXT RENDERER; THE WINDOW IS STILL HIDDEN, SO THE
        // FIRST LOADING FRAME ALREADY USES THE PREFERENCES
        AppConfig.applyBoot(ctx);
        ctx.assets.load(ctx);
        initShell();
        glfwShowWindow(ctx.windowHandle);

        // PHASE 2: LOADING SCREEN, APP AUDIO, THEN WaterMedia.start() IN THE BACKGROUND; THE SCREEN POLLS THE
        // LOAD STATE EACH FRAME FOR ITS BOOT BAR
        final LoadingScreen loadingScreen = new LoadingScreen(ctx.text, ctx);
        loadingScreen.onEnter();
        root.mount(loadingScreen);
        renderLoadingFrame();
        ctx.sound.open();
        runLoadingPhase();

        // PHASE 3: EVERYTHING THAT NEEDS WATERMEDIA LOADED
        ctx.catalog.load();
        initScreens();
        task.run();
        mainLoop();
        cleanup();
    }

    private static void runLoadingPhase() {
        // THE LOADER'S FAILURE; loader.join() PUBLISHES THE WRITE, SO IT NEEDS NO VOLATILE
        final Throwable[] failure = new Throwable[1];
        final Thread loader = ThreadTool.thread("WaterMediaApp-Init", () -> {
            try {
                // PERSISTENT DATA GOES IN THE WORKING DIRECTORY NEXT TO config/ AND logs/; A null TEMP KEEPS THE
                // MEDIA CACHE IN THE SYSTEM TEMP DIR
                WaterMedia.start("WaterMediaApp", null, Path.of("").toAbsolutePath(), true);
            } catch (final Throwable t) {
                failure[0] = t;
            }
        });

        RenderSystem.configureFrameState();

        final FrameLimiter loadingLimiter = FrameLimiter.forWindow(ctx.windowHandle);
        while (loader.isAlive() && running && !glfwWindowShouldClose(ctx.windowHandle)) {
            loadingLimiter.syncBeforeFrame();
            renderLoadingFrame();
        }

        try {
            loader.join();
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        ctx.backendsLoading = false;
        renderLoadingFrame();

        final Throwable t = failure[0];
        if (t != null) {
            if (t instanceof final RuntimeException re) throw re;
            if (t instanceof final Error err) throw err;
            throw new RuntimeException(t);
        }
    }

    private static void renderLoadingFrame() {
        RenderSystem.beginFrame();
        syncShell();
        // PASS LOGICAL DIMS: THE UI TREE LIVES IN LOGICAL PX AND SCALES TO PHYSICAL THROUGH THE ORTHO;
        // THE PHYSICAL VIEWPORT/SWAPCHAIN STAYS FULL-SIZE (OWNED BY THE BACKEND)
        root.render(ctx.logicalWidth(), ctx.logicalHeight(), false);
        RenderSystem.present();
        glfwPollEvents();
        // INPUT RUNS DURING BOOT TOO, SO THE TITLEBAR AND THE EXIT DIALOG WORK WHILE LOADING
        handleFrameInput();
    }

    public static void log(final String message) {
        WaterMedia.LOGGER.info(IT, message);
    }

    // INITIALIZATION
    private static void initWindow() {
        GLFWErrorCallback.create((code, description) ->
                WaterMedia.LOGGER.error(IT, "GLFW error 0x{}: {}", Integer.toHexString(code), GLFWErrorCallback.getDescription(description))).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        // THE LAUNCHER FORWARDS THE SAVED ENGINE; A LAUNCH WITHOUT IT READS THE SPEC, WHOSE LOAD MAY STILL BE RUNNING
        final String forwarded = System.getProperty(AppBootstrap.ENGINE_PROP);
        if (forwarded == null) AppConfig.await();
        RenderSystem.chooseEngine(forwarded == null ? AppConfig.engine
                : forwarded.equalsIgnoreCase("vulkan") ? RenderSystem.Engine.VULKAN : RenderSystem.Engine.OPENGL);
        openWindow();
    }

    // A FAILED VULKAN ATTACH LEAVES THE ENGINE ON OPENGL, WHICH NEEDS A WINDOW WITH A GL CONTEXT, SO THE WINDOW IS
    // REBUILT ONCE FOR IT
    private static void openWindow() {
        createWindow();
        if (RenderSystem.attach(ctx.windowHandle)) return;
        destroyWindow();
        createWindow();
        RenderSystem.attach(ctx.windowHandle);
    }

    private static void destroyWindow() {
        WinFrame.uninstall();
        glfwFreeCallbacks(ctx.windowHandle);
        glfwDestroyWindow(ctx.windowHandle);
        ctx.windowHandle = NULL;
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
        // WINDOWS ONLY: SUBCLASS THE WIN32 WINDOW SO THE BORDERLESS FRAME GETS NATIVE SNAP/RESIZE/
        // CAPTION BEHAVIOR. EVERY WINDOW REBUILD PASSES THROUGH HERE, SO THE HOT-SWAP RE-INSTALLS TOO.
        WinFrame.install(ctx.windowHandle, ctx);
    }

    private static void installCallbacks() {
        glfwSetKeyCallback(ctx.windowHandle, WaterMediaApp::handleKeyInput);
        glfwSetCharCallback(ctx.windowHandle, WaterMediaApp::handleCharInput);
        glfwSetCursorPosCallback(ctx.windowHandle, (w, x, y) -> {
            // RAW = PHYSICAL CURSOR (TITLEBAR WINDOW DRAG); mouseX/Y = LOGICAL (THE UI TREE LIVES IN
            // LOGICAL PX, SO EVERY HIT-TEST DIVIDES BY THE UI SCALE EXACTLY ONCE, HERE)
            ctx.mouseRawX = x;
            ctx.mouseRawY = y;
            final float scale = ctx.uiScale;
            ctx.mouseX = x / scale;
            ctx.mouseY = y / scale;
            ctx.requestRender();
        });
        glfwSetMouseButtonCallback(ctx.windowHandle, (w, button, action, mods) -> {
            if (button != GLFW_MOUSE_BUTTON_LEFT) return;
            ctx.requestRender();
            if (action == GLFW_PRESS) {
                ctx.mouseDown = true;
                ctx.mousePressed = true;
            } else if (action == GLFW_RELEASE) {
                ctx.mouseDown = false;
                ctx.mouseClicked = true;
            }
        });
        glfwSetScrollCallback(ctx.windowHandle, (w, xOffset, yOffset) -> {
            // THE TREE RESOLVES THE TARGET TOPMOST-FIRST; A VISIBLE GLOBAL DIALOG SWALLOWS THE SCROLL
            root.scroll(ctx.mouseX, ctx.mouseY, yOffset);
            ctx.requestRender();
        });
        glfwSetWindowSizeCallback(ctx.windowHandle, (w, width, height) -> {
            ctx.windowWidth = width;
            ctx.windowHeight = height;
            RenderSystem.viewport(width, height);
            ctx.requestRender();
        });
        glfwSetWindowIconifyCallback(ctx.windowHandle, (w, minimized) -> {
            iconified = minimized;
            ctx.requestRender();
        });
        glfwSetWindowMaximizeCallback(ctx.windowHandle, (w, isMaximized) -> {
            maximized = isMaximized;
            ctx.windowMaximized = maximized;
            // WINDOWS: A RESTORE RECLAMPS A FLOATING WINDOW LARGER THAN THE WORK AREA (E.G. AFTER AN RDP MONITOR
            // SHRINK); DEFERRED BECAUSE THIS CALLBACK RUNS INSIDE THE WIN32 MESSAGE DISPATCH
            if (!isMaximized && WinFrame.ACTIVE) ctx.execute(() -> WinFrame.reclamp(ctx));
            ctx.requestRender();
        });
    }

    private static void centerWindow() {
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

    // GLOBAL UI SCALE — RESOLUTION AND LIVE APPLICATION
    // ==========================================================================

    /** Applies {@link AppConfig#scale}; 0 recomputes the factor from the window's monitor. */
    public static void reapplyUiScale() {
        final float factor = AppConfig.scaleFactor(AppConfig.scale);
        final float s = factor == 0f ? computeAutoUiScale() : factor;
        // THE TEXT ATLAS REGENERATES LAZILY AT THE NEW PHYSICAL SIZE AND THE NEXT FRAME RELAYOUTS THE TREE
        ctx.uiScale = s;
        RenderSystem.uiScale(s);
        if (ctx.text != null) ctx.text.setUiScale(s);
        ctx.requestRender();
    }

    // AUTO FACTOR: THE LARGER OF THE MONITOR CONTENT SCALE AND height/1080, SNAPPED LIKE A SAVED FACTOR; THE
    // RESOLUTION TERM CATCHES HIGH-RES PANELS THE OS REPORTS AT 100%
    private static float computeAutoUiScale() {
        final float contentScale;
        try (final MemoryStack stack = stackPush()) {
            final FloatBuffer sx = stack.mallocFloat(1);
            final FloatBuffer sy = stack.mallocFloat(1);
            glfwGetWindowContentScale(ctx.windowHandle, sx, sy);
            contentScale = Math.max(sx.get(0), sy.get(0));
        }
        final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        final float heuristic = vidmode != null ? vidmode.height() / 1080f : 1f;
        return AppConfig.scaleFactor(Math.max(contentScale, heuristic));
    }

    // WINDOW SHELL — ROOT TREE, GLOBAL DIALOGS AND THE NAVIGATION BINDINGS THE SHELL NEEDS
    private static void initShell() {
        root = new RootScreen(ctx, ctx.text, WaterMediaApp::footerKeybinds);
        root.titleBar().close(() -> {
            ctx.exitConfirm = true;
            ctx.requestRender();
        });

        // GLOBAL ERROR DIALOG — CONTENT AND VISIBILITY BOUND TO ctx.error EVERY FRAME IN syncShell
        errorLines = Parent.column().spacing(6);
        errorDialog = new Dialog()
                .accent(AppTheme.RED)
                .title("ERROR")
                .content(errorLines)
                .actions(new Button("OK").subText("ENTER").icon("check").accent(AppTheme.RED)
                        .onClick(b -> ctx.clearError()))
                .onDismiss(ctx::clearError)
                .onPrimary(ctx::clearError)
                .panelWidth(600)
                .visible(false);

        // EXIT-CONFIRM DIALOG — BOUND TO ctx.exitConfirm; CONFIRMING ENDS THE MAIN LOOP
        exitDialog = new Dialog()
                .accent(AppTheme.RED)
                .title("EXIT WATERMEDIA")
                .content(Parent.column().spacing(8)
                        .add(new Text("CONFIRM EXIT").bold(true).scale(AppTheme.TEXT_BUTTON))
                        .add(new Text("Press ENTER to close the app or ESC to return.")
                                .scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT)))
                .actions(
                        new Button("CANCEL").subText("ESC").icon("x").accent(AppTheme.TEXT_SOFT)
                                .onClick(b -> dismissExit()),
                        new Button("EXIT").subText("ENTER").icon("exit").accent(AppTheme.RED)
                                .onClick(b -> running = false))
                .onDismiss(WaterMediaApp::dismissExit)
                .onPrimary(() -> running = false)
                .panelWidth(520)
                .visible(false);
        root.overlay().add(errorDialog).add(exitDialog);

        screens.root(root);
        // THE PLAYER OWNS A CUSTOM WINDOW TITLE; EVERY OTHER SCREEN RESTORES THE DEFAULT
        screens.onNavigate((previous, next) -> root.titleBar().title("player".equals(screens.currentName())
                ? "WATERMeDIA - Now Playing" : AppContext.APP_NAME));
    }

    private static void dismissExit() {
        ctx.exitConfirm = false;
        ctx.requestRender();
    }

    // FOOTER SHORTCUTS: A VISIBLE GLOBAL DIALOG OVERRIDES THE ACTIVE SCREEN'S HINTS
    private static List<Keybind> footerKeybinds() {
        if (ctx.hasError()) return KEYS_ERROR;
        if (ctx.exitConfirm) return KEYS_EXIT;
        return screens.currentKeybinds();
    }

    // PER-FRAME SHELL BINDINGS: GLOBAL DIALOG VISIBILITY AND CONTENT
    private static void syncShell() {
        final boolean hasError = ctx.hasError();
        if (hasError) {
            errorDialog.title(ctx.error.title == null ? "ERROR" : ctx.error.title.toUpperCase());
            final String message = ctx.error.message == null ? "" : ctx.error.message;
            if (!message.equals(errorShownMsg)) {
                errorShownMsg = message;
                errorLines.clear();
                for (final String line: message.split("\n")) {
                    errorLines.add(new Text(line).scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT));
                }
            }
        }
        errorDialog.visible(hasError);
        exitDialog.visible(ctx.exitConfirm);
    }

    private static void initScreens() {
        final HomeScreen homeScreen = new HomeScreen(ctx.text, ctx, WaterMediaApp::navigateAction);

        screens.register("home", homeScreen);
        screens.register("mrl", new MRLSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("regions", new RegionSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("player", new PlayerScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("multimedia", new OpenMultimediaScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
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
            case EXIT -> ctx.exitConfirm = true;

            case BACK -> screens.backToHome();

            case OPEN_MULTIMEDIA -> {
                if (!MediaAPI.ffmpegLoaded()) {
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
                if (!MediaAPI.ffmpegLoaded()) {
                    ctx.showError("Feature Unavailable",
                            "FFmpeg is not loaded.\nMedia playback is not available.\n\nCheck the alerts for more information.",
                            null);
                    return;
                }
                screens.navigate("player");
            }

            case UPLOAD_LOGS -> ctx.upload.step();

            case CLEANUP -> {
                if (!ctx.cleanup.visible) {
                    // SCANNED ON A WORKER: CleanupState.scan() WALKS THE WHOLE MEDIA CACHE, POSSIBLY THOUSANDS OF FILES
                    ctx.cleanup.reset();
                    ctx.cleanup.visible = true;
                    ctx.cleanup.working = true;
                    ThreadTool.thread("WaterMediaApp-CacheScan", WaterMediaApp::scanCleanupCache);
                    return;
                }
                if (ctx.cleanup.working) {
                    ctx.cleanup.visible = true;
                    return;
                }
                if (ctx.cleanup.stage <= 1) {
                    if (ctx.cleanup.fileCount <= 0) return;
                    ctx.cleanup.working = true;
                    ThreadTool.thread("WaterMediaApp-CacheCleanup", WaterMediaApp::cleanupCache);
                } else {
                    // DIALOG ALREADY HIDDEN — REFRESH THE COUNTS FOR THE NEXT OPEN OFF THE RENDER THREAD
                    ctx.cleanup.visible = false;
                    ThreadTool.thread("WaterMediaApp-CacheScan", WaterMediaApp::scanCleanupCache);
                }
            }
        }
    }

    // MAIN LOOP; THE LOADING PHASE ALREADY CONFIGURED THE FRAME STATE
    private static void mainLoop() {
        frameLimiter = FrameLimiter.forWindow(ctx.windowHandle);
        ctx.requestRender();
        // THE GLOW HEARTBEAT MOVES ABOUT 1% EVERY 250 ms, SO 4 REDRAWS A SECOND KEEP IT SMOOTH ON ON-DEMAND SCREENS;
        // A MINIMIZED WINDOW SHOWS NOTHING AND IS LEFT IDLE
        ThreadTool.thread("WaterMedia-GlowPulse", () -> {
            while (running) {
                if (!iconified) ctx.requestRender();
                ThreadTool.sleep(250);
            }
        });
        while (running && !glfwWindowShouldClose(ctx.windowHandle)) {
            // ENGINE HOT-SWAP RUNS AT THE TOP OF THE LOOP ON THE RENDER THREAD — NEVER MID-FRAME
            if (swapTarget != null) performEngineSwap();
            // CONTINUOUS SCREENS (VIDEO), A VISIBLE MOVING CRT BAND AND POINTER DRAGS PACE TO THE REFRESH RATE, THEN
            // POLL INPUT AFTER THE PACE SLEEP SO A SLIDER SHOWS THE CURSOR AS OF RENDER TIME, NOT A FRAME EARLIER
            final boolean continuous = root.continuous();
            final boolean paced = continuous || (ctx.crt && root.live() && !iconified) || root.dragging();
            if (paced) {
                frameLimiter.syncBeforeFrame();
                glfwPollEvents();
            } else if (ctx.renderRequested()) {
                glfwPollEvents();
            } else {
                glfwWaitEventsTimeout(frameLimiter.idleTimeoutSeconds());
            }

            handleFrameInput();
            ctx.processExecutor();
            AppConfig.poll(ctx);

            // ON-DEMAND FRAMES REPAINT THE TREE ONLY WHEN SOMETHING ASKED FOR ONE; A STILL DRAG-HOLD FALLS THROUGH
            // HERE TOO AND SKIPS THE REDUNDANT REPAINT UNTIL THE POINTER MOVES AGAIN
            final FramePlan plan = FramePlan.of(continuous, ctx.consumeRenderRequest(), ctx.crt, root.live(), !iconified,
                    RenderSystem.cacheValid());
            if (plan == FramePlan.IDLE) continue;

            RenderSystem.beginFrame();
            // LOGICAL DIMS — THE TREE LIVES IN LOGICAL PX AND SCALES TO PHYSICAL THROUGH THE ORTHO; A SWAPCHAIN THAT
            // beginFrame() REBUILT TOOK THE LAYERS WITH IT, SO THAT COMPOSITE REPAINTS THE TREE INSTEAD
            final boolean composite = plan == FramePlan.COMPOSITE && RenderSystem.cacheValid();
            if (!composite) syncShell();
            if (composite) root.composite(ctx.logicalWidth(), ctx.logicalHeight());
            else root.render(ctx.logicalWidth(), ctx.logicalHeight(), plan != FramePlan.DIRECT);
            RenderSystem.present();
        }

        ctx.media.releasePlayer();
    }

    /** Queues a live render engine hot swap for the next main-loop iteration; safe to call from callbacks. */
    public static void requestEngineSwap(final RenderSystem.Engine target) {
        // WITHOUT A VULKAN LOADER THE SWAP WOULD TEAR THE WINDOW DOWN ONLY TO REBUILD IT ON OPENGL
        if (target == RenderSystem.Engine.VULKAN && !RenderSystem.vulkanAvailable()) {
            WaterMedia.LOGGER.warn(IT, "Vulkan is unavailable on this system; staying on OpenGL");
            ctx.showError("Vulkan unavailable", "Vulkan is not available on this system.\nThe app keeps running on OpenGL.", null);
            return;
        }
        swapTarget = target;
        ctx.requestRender();
    }

    // REBUILDS THE WINDOW AND ENGINE IN PLACE, KEEPING THE JVM, THE MEDIA API, THE ctx EXECUTOR AND THE GLOW
    // PULSE; RUNS ONLY FROM THE TOP OF mainLoop()
    private static void performEngineSwap() {
        final RenderSystem.Engine target = swapTarget;
        swapTarget = null;
        if (target == null || target == RenderSystem.engineKind()) return;

        // CAPTURE THE CURRENT WINDOW PLACEMENT SO THE REBUILT WINDOW LANDS EXACTLY WHERE IT WAS
        final boolean wasMaximized = maximized;
        int px = 0, py = 0, pw = ctx.windowWidth, ph = ctx.windowHeight;
        if (!wasMaximized) {
            try (final MemoryStack stack = stackPush()) {
                final IntBuffer bx = stack.mallocInt(1);
                final IntBuffer by = stack.mallocInt(1);
                final IntBuffer bw = stack.mallocInt(1);
                final IntBuffer bh = stack.mallocInt(1);
                glfwGetWindowPos(ctx.windowHandle, bx, by);
                glfwGetWindowSize(ctx.windowHandle, bw, bh);
                px = bx.get(0);
                py = by.get(0);
                pw = bw.get(0);
                ph = bh.get(0);
            }
        }

        WaterMedia.LOGGER.info(IT, "Hot-swapping render engine {} -> {}", RenderSystem.engineName(), target);
        try {
            // 1) DRAIN ALL MEDIA — SAME ORDER AS cleanup(): RELEASE PLAYERS/THUMBNAILS, THEN JOIN THE
            //    BACKGROUND RELEASE QUEUE SO NOTHING TOUCHES THE DEVICE AFTER IT IS DESTROYED
            screens.releaseMediaAll();
            ctx.media.releasePlayer();
            ctx.awaitPlayerRelease();

            // 2) DROP GPU CACHES BOUND TO THE OUTGOING CONTEXT — THEY REBUILD LAZILY (TEXT) OR ARE RECREATED BELOW
            ctx.text.reset();
            ctx.assets.dispose();

            // 3) TEAR DOWN THE OUTGOING ENGINE AND ITS WINDOW (MIRRORS cleanup() MINUS glfwTerminate)
            RenderSystem.cleanup();
            destroyWindow();

            // 4) SELECT THE TARGET ENGINE AND REBUILD THE WINDOW WITH ITS HINTS (MIRRORS initWindow())
            RenderSystem.chooseEngine(target);
            openWindow();

            restoreWindowPlacement(wasMaximized, px, py, pw, ph);

            // RE-APPLY UI SCALE (MIRRORS BOOT), RE-CREATE ASSET TEXTURES + WINDOW ICON, SHOW, RE-PACE THE LIMITER
            reapplyUiScale();
            ctx.assets.load(ctx);
            glfwShowWindow(ctx.windowHandle);
            frameLimiter = FrameLimiter.forWindow(ctx.windowHandle);

            // A FAILED VULKAN ATTACH RUNS THIS LAUNCH ON OPENGL; THE SAVED PREFERENCE KEEPS WHAT THE USER PICKED
            if (target == RenderSystem.Engine.VULKAN && RenderSystem.engineKind() != RenderSystem.Engine.VULKAN) {
                ctx.showError("Vulkan unavailable",
                        "Vulkan could not be initialized on this system.\nThe app is running on OpenGL.", null);
            }
            WaterMedia.LOGGER.info(IT, "Render engine hot-swap complete: {}", RenderSystem.engineName());
        } catch (final Throwable t) {
            WaterMedia.LOGGER.error(IT, "Render engine hot-swap failed; attempting OpenGL recovery", t);
            if (!recoverToOpenGL(wasMaximized, px, py, pw, ph)) {
                WaterMedia.LOGGER.error(IT, "OpenGL recovery after a failed hot-swap also failed; shutting down");
                running = false; // LET THE MAIN LOOP EXIT SO cleanup() TEARS DOWN CLEANLY
            }
        } finally {
            ctx.requestRender();
        }
    }

    // createWindow() ALWAYS BUILDS MAXIMIZED, SO A WINDOWED PLACEMENT IS RESTORED HERE; THE SIZE IS READ BACK
    // BECAUSE THE RESIZE CALLBACK MAY NOT HAVE FIRED YET
    private static void restoreWindowPlacement(final boolean wasMaximized, final int px, final int py, final int pw, final int ph) {
        if (!wasMaximized) {
            glfwRestoreWindow(ctx.windowHandle);
            glfwSetWindowSize(ctx.windowHandle, pw, ph);
            glfwSetWindowPos(ctx.windowHandle, px, py);
            maximized = false;
            ctx.windowMaximized = false;
        }
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer wBuf = stack.mallocInt(1);
            final IntBuffer hBuf = stack.mallocInt(1);
            glfwGetWindowSize(ctx.windowHandle, wBuf, hBuf);
            ctx.windowWidth = wBuf.get(0);
            ctx.windowHeight = hBuf.get(0);
        }
        RenderSystem.viewport(ctx.windowWidth, ctx.windowHeight);
    }

    // LAST-RESORT RECOVERY AFTER A FAILED HOT-SWAP: FORCE OPENGL AND REBUILD A FRESH WINDOW/ENGINE/ASSETS FROM
    // WHATEVER STATE THE SWAP LEFT BEHIND. RETURNS true WHEN THE APP IS RENDERABLE AGAIN, false WHEN IT MUST EXIT.
    private static boolean recoverToOpenGL(final boolean wasMaximized, final int px, final int py, final int pw, final int ph) {
        try {
            // A ZEROED HANDLE MEANS THE SWAP ALREADY DESTROYED THE WINDOW
            if (ctx.windowHandle != NULL) destroyWindow();
            RenderSystem.chooseEngine(RenderSystem.Engine.OPENGL);
            openWindow();
            restoreWindowPlacement(wasMaximized, px, py, pw, ph);
            reapplyUiScale();
            ctx.assets.load(ctx);
            glfwShowWindow(ctx.windowHandle);
            frameLimiter = FrameLimiter.forWindow(ctx.windowHandle);
            ctx.showError("Engine switch failed",
                    "The render engine could not be switched.\nThe app recovered on OpenGL.", null);
            return true;
        } catch (final Throwable t) {
            WaterMedia.LOGGER.error(IT, "OpenGL recovery failed", t);
            return false;
        }
    }

    private static void handleFrameInput() {
        if (ctx.mousePressed) {
            ctx.mousePressed = false;
            // GLOBAL DIALOGS ARE TRULY MODAL — A PRESS MUST NOT START A DRAG ON CONTENT BENEATH THE SCRIM
            if (!ctx.hasError() && !ctx.exitConfirm) {
                root.press(ctx.mouseX, ctx.mouseY);
            }
        }
        // HOVER DISPATCHES ONLY ON REAL CURSOR MOVEMENT, SO A PARKED CURSOR NEVER OVERRIDES KEYBOARD NAVIGATION
        if (ctx.mouseX != lastMoveX || ctx.mouseY != lastMoveY) {
            lastMoveX = ctx.mouseX;
            lastMoveY = ctx.mouseY;
            root.hover(ctx.mouseX, ctx.mouseY);
            root.drag(ctx.mouseX, ctx.mouseY);
        }
        if (ctx.mouseClicked) {
            ctx.mouseClicked = false;
            // RELEASE ENDS ANY CAPTURE FIRST; THE SHELL SUPPRESSES THE CLICK WHEN IT ENDED A REAL DRAG
            root.release(ctx.mouseX, ctx.mouseY);
            root.click(ctx.mouseX, ctx.mouseY);
        }
    }

    // INPUT HANDLING
    private static void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        ctx.requestRender();
        ctx.ctrlDown = (mods & GLFW_MOD_CONTROL) != 0
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;

        // CRT TOGGLE, EXCEPT IN A FOCUSED TEXT FIELD WHERE THE C IS TYPED TEXT
        if (action == GLFW_RELEASE && key == GLFW_KEY_C && !root.textInputFocused()) {
            ctx.crt = !ctx.crt;
            AppConfig.crt(ctx.crt);
            ctx.requestRender();
            return;
        }

        // THE TREE RESOLVES PRIORITY TOPMOST-FIRST — A VISIBLE GLOBAL DIALOG CONSUMES ESC/ENTER ITSELF
        root.key(key, action);
    }

    private static void handleCharInput(final long window, final int codepoint) {
        ctx.requestRender();
        root.character(codepoint);
    }

    // BACKGROUND OPERATIONS
    // RUNS ON A WORKER THREAD (SEE navigateAction) — THE FULL-CACHE WALK MUST NOT BLOCK THE RENDER LOOP
    private static void scanCleanupCache() {
        try {
            ctx.cleanup.stage = 1;
            ctx.cleanup.done = false;
            ctx.cleanup.error = false;
            ctx.cleanup.progress = 0;

            final long[] stats = CleanupState.scan();
            ctx.cleanup.fileCount = (int) Math.min(Integer.MAX_VALUE, stats[0]);
            ctx.cleanup.sizeLabel = MathUtil.displayBytes(stats[1]);
            ctx.cleanup.state = stats[0] > 0 ? "FOUND" : "EMPTY";
        } catch (final IOException e) {
            ctx.cleanup.error = true;
            ctx.cleanup.state = "ERROR";
        } finally {
            ctx.cleanup.working = false;
            ctx.requestRender();
        }
    }

    private static void cleanupCache() {
        try {
            ctx.cleanup.stage = 2;
            ctx.cleanup.state = "CLEANING";
            ctx.cleanup.error = false;
            ctx.cleanup.done = false;
            ctx.cleanup.progress = 12;
            ctx.requestRender();

            final Path cache = CleanupState.dir();
            if (Files.exists(cache)) {
                IOTool.deleteTree(cache);
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

    // CLEANUP
    private static void cleanup() {
        // FINISH ANY BACKGROUND PLAYER RELEASE BEFORE TEARING DOWN THE RENDER DEVICE, OR A VULKAN
        // RELEASE COULD DESTROY IMAGES ON AN ALREADY-DESTROYED DEVICE.
        ctx.awaitPlayerRelease();
        RenderSystem.cleanup();
        destroyWindow();
        glfwTerminate();
        final GLFWErrorCallback errorCallback = glfwSetErrorCallback(null);
        if (errorCallback != null) errorCallback.close();
        System.exit(0);
    }

    // LOGGING
    private static void initLogging() {
        final String filename = "logs/watermedia-app.log";
        final File logfile = new File(filename);
        // NO ':' OR SPACES IN THE STAMP: WINDOWS REJECTS THEM IN FILENAMES AND THE ROTATION WOULD FAIL
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        final boolean rotationFailed = logfile.exists() && !logfile.renameTo(new File("logs/watermedia-app-" + stamp + ".log"));

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
        if (rotationFailed) WaterMedia.LOGGER.warn(IT, "Could not rotate {}; continuing in append mode", filename);
    }
}
