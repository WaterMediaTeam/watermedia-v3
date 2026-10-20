package org.watermedia.bootstrap.app;

import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.Control;
import me.srrapero720.waterconfig.api.IConfigField;
import me.srrapero720.waterconfig.api.annotations.Comment;
import me.srrapero720.waterconfig.api.annotations.Spec;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.ThreadTool;

/**
 * WaterConfig spec of the standalone app preferences, saved as {@code config/app.properties}. The launcher reads the
 * same file before WaterConfig exists, to relaunch the app on the saved engine.
 */
@Spec(value = AppConfig.ID, format = WaterConfig.FORMAT_PROPERTIES)
@Comment("WATERMeDIA standalone app preferences")
public class AppConfig {
    private static final Marker IT = MarkerManager.getMarker(AppConfig.class.getSimpleName());
    /** Spec identifier and base name of the {@code app.properties} file. */
    public static final String ID = "app";
    /** Key of {@link #engine}, shared with the launcher. */
    public static final String ENGINE = "engine";
    private static final long LOAD_TIMEOUT_MS = 2_000;

    @Spec.Field(value = ENGINE, control = Control.SEGMENTED)
    @Comment("Render engine. Applies live")
    public static RenderSystem.Engine engine = RenderSystem.Engine.OPENGL;

    @Spec.Field(control = Control.DROPDOWN)
    @Comment("Interface size: AUTO follows the monitor; NORMAL, LARGE, LARGER and LARGEST are 100%, 125%, 150% and 200%")
    public static UiScale scale = UiScale.AUTO;

    @Spec.Field
    @Comment("CRT overlay with scanlines and neon bands. Keyboard shortcut: C")
    public static boolean crt = true;

    @Spec.Field
    @Comment("Tick sound on menu and settings navigation")
    public static boolean sound = true;

    // null WHEN REGISTRATION FAILED: THE APP THEN RUNS ON THE FIELD DEFAULTS
    private static volatile ConfigSpec spec;

    /** Returns the registered spec, or {@code null} when registration failed. */
    public static ConfigSpec spec() {
        return spec;
    }

    /** Registers the spec; WaterConfig loads the file on its IO pool while the window boots. */
    public static void register() {
        try {
            WaterConfig.init();
            spec = WaterConfig.register(AppConfig.class);
        } catch (final Throwable t) {
            WaterMedia.LOGGER.warn(IT, "Failed to register the app config spec", t);
        }
    }

    /** Waits up to two seconds for the load started by {@link #register()}. */
    public static void await() {
        final ConfigSpec s = spec;
        if (s == null) return;
        final long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        while (!s.isLoaded() && System.currentTimeMillis() < deadline) ThreadTool.sleep(5);
    }

    /** Waits for the load, keeps {@link #engine} on the engine that runs and applies every value before the first frame. */
    public static void applyBoot(final AppContext ctx) {
        await();
        // A VULKAN FALLBACK OR A -Dwatermedia.engine OVERRIDE BECOMES THE SAVED CHOICE, SO THE SETTINGS ROW SHOWS WHAT RUNS
        if (engine != RenderSystem.engineKind()) engine(RenderSystem.engineKind());
        apply(ctx);
    }

    /** Applies the current values to the running app; the settings screen calls it after every edit of this spec. */
    public static void apply(final AppContext ctx) {
        ctx.crt = crt;
        ctx.selectionSoundEnabled = sound;
        WaterMediaApp.reapplyUiScale();
        if (engine != RenderSystem.engineKind()) WaterMediaApp.requestEngineSwap(engine);
    }

    /** Saves the CRT overlay state toggled by the keyboard shortcut. */
    public static void crt(final boolean on) {
        crt = on;
        dirty("crt");
    }

    /** Saves the engine the app runs on after a fallback. */
    public static void engine(final RenderSystem.Engine running) {
        engine = running;
        dirty(ENGINE);
    }

    // THE WATERCONFIG WORKER SAVES A SPEC ONCE ONE OF ITS FIELDS IS MARKED DIRTY
    private static void dirty(final String key) {
        final ConfigSpec s = spec;
        if (s == null) return;
        final IConfigField<?, ?> field = s.findField(key);
        if (field != null) s.markDirty(field);
    }
}
