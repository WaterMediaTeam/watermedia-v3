package org.watermedia.bootstrap.app.render;

import org.watermedia.WaterMedia;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.bootstrap.app.render.opengl.OpenGLRenderBackend;
import org.watermedia.bootstrap.app.render.vulkan.VulkanRenderBackend;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;

/**
 * Static facade for the active UI render engine. It also owns the engine lifecycle tied to the
 * window — choosing OpenGL vs Vulkan, applying the matching GLFW hints, attaching to the created
 * window and supplying the media graphics engine — so the rest of the app never branches on the
 * backend.
 */
public final class RenderSystem {

    /** The render backends the app can run on. */
    public enum Engine { OPENGL, VULKAN }

    private static final String ENGINE_PROP = "watermedia.engine";
    // A GL DEBUG CONTEXT ADDS PER-CALL DRIVER VALIDATION OVERHEAD — OFF BY DEFAULT, OPT IN WITH -Dwatermedia.gldebug=true
    public static final boolean GL_DEBUG = Boolean.getBoolean("watermedia.gldebug");
    // THE ENGINE CHOICE IS PERSISTED IN THE SAME FILE THE AppBootstrap LAUNCHER READS ON THE NEXT LAUNCH
    // (IT RELAUNCHES THE CHILD JVM WITH -Dwatermedia.engine FROM THIS FILE). KEEP IN SYNC WITH
    // AppBootstrap.ENGINE_FILE = <java.io.tmpdir>/watermedia/libs/engine.cfg.
    private static final Path ENGINE_PREF_FILE =
            Path.of(System.getProperty("java.io.tmpdir"), "watermedia", "libs", "engine.cfg");
    // GLOBAL UI SCALE PREFERENCE — PERSISTED NEXT TO engine.cfg AS EITHER "auto" OR A FLOAT FACTOR
    private static final Path UI_SCALE_PREF_FILE =
            Path.of(System.getProperty("java.io.tmpdir"), "watermedia", "libs", "uiscale.cfg");

    private static Engine kind = Engine.OPENGL;
    private static RenderEngine engine;
    // GLOBAL UI SCALE (PHYSICAL PX PER LOGICAL PX) — REMEMBERED HERE SO ENGINES ATTACHED LATER INHERIT IT
    private static volatile float uiScale = 1f;

    private RenderSystem() {
    }

    /** The engine the user picked in Settings for the next launch, or {@code null} if unset/unreadable. */
    public static Engine enginePreference() {
        try {
            if (Files.exists(ENGINE_PREF_FILE)) {
                final String value = Files.readString(ENGINE_PREF_FILE).trim();
                if ("vulkan".equalsIgnoreCase(value)) return Engine.VULKAN;
                if ("opengl".equalsIgnoreCase(value)) return Engine.OPENGL;
            }
        } catch (final Exception ignored) {
        }
        return null;
    }

    /** Persists the engine to use on the next launch (written from the Settings screen). */
    public static void saveEnginePreference(final Engine choice) {
        try {
            final Path parent = ENGINE_PREF_FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(ENGINE_PREF_FILE, choice == Engine.VULKAN ? "vulkan" : "opengl");
        } catch (final Exception e) {
            WaterMedia.LOGGER.warn("Failed to save the render engine preference", e);
        }
    }

    /** The persisted UI scale preference: {@code "auto"} or a float factor; {@code "auto"} when unset/unreadable. */
    public static String uiScalePreference() {
        try {
            if (Files.exists(UI_SCALE_PREF_FILE)) {
                final String value = Files.readString(UI_SCALE_PREF_FILE).trim();
                if (!value.isEmpty()) return value;
            }
        } catch (final Exception ignored) {
        }
        return "auto";
    }

    /** Persists the UI scale preference ({@code "auto"} or a float factor), written from the Settings screen. */
    public static void saveUiScalePreference(final String value) {
        try {
            final Path parent = UI_SCALE_PREF_FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(UI_SCALE_PREF_FILE, value == null || value.isBlank() ? "auto" : value.trim());
        } catch (final Exception e) {
            WaterMedia.LOGGER.warn("Failed to save the UI scale preference", e);
        }
    }

    /** Resolves the engine from {@code -Dwatermedia.engine}, downgrading to OpenGL when Vulkan is unsupported. */
    public static void chooseEngine() {
        kind = "vulkan".equalsIgnoreCase(System.getProperty(ENGINE_PROP, "opengl")) && glfwVulkanSupported()
                ? Engine.VULKAN : Engine.OPENGL;
    }

    /** Applies the GLFW window hints the chosen engine needs. Call right after {@code glfwDefaultWindowHints()}. */
    public static void applyWindowHints() {
        if (kind == Engine.VULKAN) {
            // NO GL CONTEXT — THE VULKAN BACKEND PRESENTS THROUGH ITS OWN SURFACE/SWAPCHAIN
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        } else {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GL_DEBUG ? GLFW_TRUE : GLFW_FALSE);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        }
    }

    /**
     * Creates the backend for the chosen engine, binds it to the window and initializes it. Returns
     * false when Vulkan was requested but failed — the engine is already switched to OpenGL, so the
     * caller should rebuild the window with OpenGL hints and call this again.
     */
    public static boolean attach(final long window) {
        if (kind == Engine.VULKAN) {
            VulkanRenderBackend vk = null;
            try {
                vk = new VulkanRenderBackend(window);
                engine = new RenderEngine(vk);
                engine.uiScale(uiScale);
                engine.init();
                engine.configureFrameState();
                WaterMedia.LOGGER.info("Render engine: Vulkan");
                return true;
            } catch (final Throwable t) {
                WaterMedia.LOGGER.error("Vulkan engine init failed; falling back to OpenGL", t);
                // DESTROY THE PARTIALLY BUILT BACKEND: THE CALLER REBUILDS THE GLFW WINDOW NEXT, AND
                // ITS VkSurfaceKHR MUST DIE BEFORE THE WINDOW — OTHERWISE THE FALLBACK CAN CRASH IN
                // THE DRIVER AND THE DEVICE/INSTANCE LEAK FOR THE PROCESS LIFETIME.
                if (vk != null) {
                    try {
                        vk.cleanup();
                    } catch (final Throwable ct) {
                        WaterMedia.LOGGER.error("Vulkan backend cleanup after failed init also failed", ct);
                    }
                }
                engine = null;
                kind = Engine.OPENGL;
                return false;
            }
        }
        final OpenGLRenderBackend gl = new OpenGLRenderBackend(window);
        engine = new RenderEngine(gl);
        engine.uiScale(uiScale);
        gl.attachContext(); // GL CONTEXT MUST BE CURRENT BEFORE init() COMPILES SHADERS
        engine.init();
        engine.configureFrameState();
        WaterMedia.LOGGER.info("Render engine: OpenGL");
        return true;
    }

    /** The active engine kind. */
    public static Engine engineKind() { return kind; }

    /** Display name of the active engine ("Vulkan"/"OpenGL"). */
    public static String engineName() { return kind == Engine.VULKAN ? "Vulkan" : "OpenGL"; }

    /** Whether Vulkan is the active engine. */
    public static boolean vulkanActive() { return kind == Engine.VULKAN; }

    /** Whether a Vulkan loader is present (the engine is active, or Vulkan is selectable on this machine). */
    public static boolean vulkanAvailable() { return kind == Engine.VULKAN || glfwVulkanSupported(); }

    /** Name of the active GPU/renderer, captured at engine init (safe to read off the render thread). */
    public static String deviceName() { return engine != null ? engine.deviceName() : "Unknown"; }

    /** Short active-engine label with its max API version, e.g. {@code "GL 4.6"} or {@code "VK 1.3"}. */
    public static String engineVersionLabel() {
        final String prefix = kind == Engine.VULKAN ? "VK" : "GL";
        final String v = engine != null ? engine.deviceVersion() : "";
        return v == null || v.isBlank() ? prefix : prefix + " " + v;
    }

    /** Builds the media graphics engine ({@code GLEngine}/{@code VKEngine}) for the active backend. */
    public static Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) {
        return engine.mediaEngineSupplier(renderThread, renderExecutor);
    }

    public static RenderEngine engine() {
        return engine;
    }

    public static void setEngine(final RenderEngine nextEngine) {
        if (nextEngine == null) throw new IllegalArgumentException("Render engine cannot be null");
        engine = nextEngine;
        nextEngine.uiScale(uiScale);
    }

    /**
     * Sets the global UI scale (physical pixels per logical pixel) on the active render engine and
     * remembers it for engines attached later. The engine applies it to scissor rectangles and
     * rasterized line widths only; geometry scales through the ortho projection and the viewport
     * stays physical. NOTE: this facade does not reach the app-owned text renderer — the caller
     * must also push the same value into {@code TextRenderer#setUiScale(float)}.
     */
    public static void uiScale(final float scale) {
        uiScale = scale > 0f ? scale : 1f;
        if (engine != null) engine.uiScale(uiScale);
    }

    /** The global UI scale (physical pixels per logical pixel). */
    public static float uiScale() {
        return uiScale;
    }

    public static void init() { engine.init(); }

    // NULLS THE ENGINE SO WINDOW-CREATION MESSAGES BETWEEN A TEARDOWN AND THE NEXT attach() (ENGINE
    // HOT-SWAP) CANNOT REACH A DEAD BACKEND WHOSE CONTEXT DIED WITH THE OLD WINDOW
    public static void cleanup() {
        final RenderEngine current = engine;
        engine = null;
        if (current != null) current.cleanup();
    }

    public static void flush() { engine.flush(); }
    public static void configureFrameState() { engine.configureFrameState(); }
    public static void clear(final float r, final float g, final float b, final float a) { engine.clear(r, g, b, a); }

    // NULL-SAFE: THE GLFW SIZE CALLBACK FIRES SYNCHRONOUSLY DURING WINDOW CREATION (WinFrame'S
    // SWP_FRAMECHANGED TRIGGERS WM_SIZE) BEFORE attach() BUILT THE ENGINE; GL SETS ITS INITIAL VIEWPORT
    // FROM THE FRAMEBUFFER AT CONTEXT CREATION AND THE SWAP PATH RE-CALLS viewport() AFTER attach()
    public static void viewport(final int width, final int height) {
        final RenderEngine current = engine;
        if (current != null) current.viewport(width, height);
    }
    public static void disableDepthTest() { engine.disableDepthTest(); }
    public static int createTexture(final int width, final int height, final ByteBuffer rgba) { return engine.createTexture(width, height, rgba); }
    public static TextureHandle createTextureHandle(final int width, final int height, final ByteBuffer rgba) { return engine.createTextureHandle(width, height, rgba); }
    public static void deleteTexture(final TextureHandle texture) { engine.deleteTexture(texture); }
    public static void setupOrtho(final int width, final int height) { engine.setupOrtho(width, height); }
    public static void restoreProjection() { engine.restoreProjection(); }
    public static void color(final Color c) { engine.color(c); }
    public static void color(final float r, final float g, final float b, final float a) { engine.color(r, g, b, a); }
    public static void color(final float r, final float g, final float b) { engine.color(r, g, b); }
    public static void bindTexture(final int textureId) { engine.bindTexture(textureId); }
    public static void bindMediaTexture(final long handle) { engine.bindMediaTexture(handle); }
    public static void beginFrame() { engine.beginFrame(); }
    public static void present() { engine.present(); }
    public static void clip(final int x, final int y, final int width, final int height, final int canvasHeight) { engine.clip(x, y, width, height, canvasHeight); }
    public static void clearClip() { engine.clearClip(); }
    public static void lineWidth(final float width) { engine.lineWidth(width); }
    public static void fill(final float x, final float y, final float w, final float h) { engine.fill(x, y, w, h); }
    public static void fill(final float x, final float y, final float w, final float h, final Color c) { engine.fill(x, y, w, h, c); }
    public static void fill(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a) { engine.fill(x, y, w, h, r, g, b, a); }
    public static void fillGradientH(final float x, final float y, final float w, final float h, final float r1, final float g1, final float b1, final float a1, final float r2, final float g2, final float b2, final float a2) { engine.fillGradientH(x, y, w, h, r1, g1, b1, a1, r2, g2, b2, a2); }
    public static void fillGradientV(final float x, final float y, final float w, final float h, final float r1, final float g1, final float b1, final float a1, final float r2, final float g2, final float b2, final float a2) { engine.fillGradientV(x, y, w, h, r1, g1, b1, a1, r2, g2, b2, a2); }
    public static void fillTriangle(final float x1, final float y1, final float x2, final float y2, final float x3, final float y3, final float r, final float g, final float b, final float a) { engine.fillTriangle(x1, y1, x2, y2, x3, y3, r, g, b, a); }
    public static void fillRoundedTriangle(final float x1, final float y1, final float x2, final float y2, final float x3, final float y3, final float radius, final float r, final float g, final float b, final float a) { engine.fillRoundedTriangle(x1, y1, x2, y2, x3, y3, radius, r, g, b, a); }
    public static void fillCircle(final float cx, final float cy, final float radius, final float r, final float g, final float b, final float a) { engine.fillCircle(cx, cy, radius, r, g, b, a); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius) { engine.fillRounded(x, y, w, h, radius); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius, final float r, final float g, final float b, final float a) { engine.fillRounded(x, y, w, h, radius, r, g, b, a); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius, final Color c) { engine.fillRounded(x, y, w, h, radius, c); }
    public static void rect(final float x, final float y, final float w, final float h) { engine.rect(x, y, w, h); }
    public static void rect(final float x, final float y, final float w, final float h, final Color c, final float lineWidth) { engine.rect(x, y, w, h, c, lineWidth); }
    public static void rect(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a, final float lineWidth) { engine.rect(x, y, w, h, r, g, b, a, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final float r, final float g, final float b, final float a, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, r, g, b, a, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final Color c, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, c, lineWidth); }
    public static void lineH(final float x, final float y, final float length) { engine.lineH(x, y, length); }
    public static void lineH(final float x, final float y, final float length, final Color c, final float lineWidth) { engine.lineH(x, y, length, c, lineWidth); }
    public static void lineH(final float x, final float y, final float length, final float r, final float g, final float b, final float a, final float lineWidth) { engine.lineH(x, y, length, r, g, b, a, lineWidth); }
    public static void lineV(final float x, final float y, final float length, final Color c, final float lineWidth) { engine.lineV(x, y, length, c, lineWidth); }
    public static void lineV(final float x, final float y, final float length) { engine.lineV(x, y, length); }
    public static void line(final float x1, final float y1, final float x2, final float y2) { engine.line(x1, y1, x2, y2); }
    public static void lineStrip(final float[] points) { engine.lineStrip(points); }
    public static void lineLoop(final float[] points) { engine.lineLoop(points); }
    public static void blit(final float x, final float y, final float w, final float h) { engine.blit(x, y, w, h); }
    public static void blit(final float x, final float y, final float w, final float h, final float u0, final float v0, final float u1, final float v1) { engine.blit(x, y, w, h, u0, v0, u1, v1); }
    public static void blitNDC(final float x1, final float y1, final float x2, final float y2) { engine.blitNDC(x1, y1, x2, y2); }
    public static void blitNDC(final float x1, final float y1, final float x2, final float y2, final float u0, final float v0, final float u1, final float v1) { engine.blitNDC(x1, y1, x2, y2, u0, v0, u1, v1); }
    public static void dialogBox(final float x, final float y, final float w, final float h, final Color borderColor, final float borderWidth) { engine.dialogBox(x, y, w, h, borderColor, borderWidth); }
    public static void dialogBox(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a, final float borderWidth) { engine.dialogBox(x, y, w, h, r, g, b, a, borderWidth); }
    public static void fadeLeft(final float width, final float height, final float fadeWidth, final float alpha) { engine.fadeLeft(width, height, fadeWidth, alpha); }
    public static void fadeBottom(final float width, final float height, final float fadeHeight, final float alpha) { engine.fadeBottom(width, height, fadeHeight, alpha); }
    public static void glowRect(final float x, final float y, final float w, final float h, final float radius, final Color glow, final float alpha) { engine.glowRect(x, y, w, h, radius, glow, alpha); }
    public static void shadowRect(final float x, final float y, final float w, final float h, final float radius, final float alpha) { engine.shadowRect(x, y, w, h, radius, alpha); }
}
