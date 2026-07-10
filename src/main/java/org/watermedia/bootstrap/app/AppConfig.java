package org.watermedia.bootstrap.app;

import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.Control;
import me.srrapero720.waterconfig.api.IConfigField;
import me.srrapero720.waterconfig.api.annotations.Comment;
import me.srrapero720.waterconfig.api.annotations.Spec;
import org.watermedia.WaterMedia;
import org.watermedia.tools.ThreadTool;

/**
 * Annotated WaterConfig spec for the bootstrap shell settings, persisted through WaterConfig's native
 * mechanism as {@code config/watermedia-app.toml} — the same directory and format family as the core
 * {@code watermedia.toml} spec. The settings screen reflects this spec through the exact same path it
 * uses for the instance config.
 *
 * <p>The render engine and the UI scale are deliberately NOT part of this spec: their stores
 * ({@code engine.cfg} / {@code uiscale.cfg}) are read by the {@code AppBootstrap} launcher JVM and by
 * the early window boot before WaterConfig ever loads, so those two remain runtime settings backed by
 * their own files. The settings screen still lists them inside this spec's {@code engines} section,
 * next to the audio engine field. Keeping a second copy here would desync the two stores.</p>
 *
 * <p>Values load off-thread on the WaterConfig IO pool; {@link #applyBoot(AppContext)} waits (bounded)
 * for the load and dumps them into the live {@link AppContext} state, and the settings screen re-dumps
 * them on every edit so changes apply live.</p>
 */
@Spec(value = AppConfig.ID, format = WaterConfig.FORMAT_TOML)
@Comment("WATERMeDIA standalone app shell settings")
public class AppConfig {

    /** Spec identifier — also the config file base name ({@code watermedia-app.toml}). */
    public static final String ID = "watermedia-app";

    @Spec.Field
    @Comment("Global CRT overlay (scanlines + neon bands). Keyboard shortcut: C")
    public static boolean crtOverlay = true;

    @Spec.Field
    @Comment("UI tick sound used by menu and settings navigation")
    public static boolean selectionSound = true;

    @Spec.Field
    @Comment("Engine backends: render, interface scale and audio")
    public static final Engines engines = new Engines();

    @Spec(value = "engines", disableStatic = true)
    public static final class Engines {
        @Spec.Field(control = Control.SEGMENTED)
        @Comment("Audio backend used when opening a player: OpenAL or the native Java Sound engine")
        @Comment("Applies to the next media you open")
        public AudioEngine audio = AudioEngine.OPENAL;
    }

    // REGISTERED SPEC — null WHEN REGISTRATION FAILED (THE APP THEN RUNS ON THE FIELD DEFAULTS)
    private static volatile ConfigSpec spec;

    /**
     * The registered WaterConfig spec backing this class, or {@code null} when registration failed.
     * @return the spec, possibly {@code null}
     */
    public static ConfigSpec spec() {
        return spec;
    }

    /**
     * Registers this class as a WaterConfig spec. The file load/create runs asynchronously on the
     * WaterConfig IO pool, so calling this early overlaps the disk IO with the window boot. Safe to
     * call only from the app JVM (never from the launcher's early phase). A failure is logged and
     * leaves the spec unregistered — the app keeps running on the field defaults.
     */
    public static void register() {
        try {
            WaterConfig.init();
            spec = WaterConfig.register(AppConfig.class);
        } catch (final Throwable t) {
            WaterMedia.LOGGER.warn("Failed to register the app shell config spec", t);
        }
    }

    /**
     * Waits (bounded) for the async spec load kicked off by {@link #register()} and then dumps the
     * loaded values into the live app state via {@link #apply(AppContext)}. Call once at boot, before
     * the first frame, so the shell starts with the persisted CRT/sound/audio choices.
     * @param ctx the app context receiving the values
     */
    public static void applyBoot(final AppContext ctx) {
        final ConfigSpec s = spec;
        if (s != null) {
            final long deadline = System.currentTimeMillis() + 2000;
            while (!s.isLoaded() && System.currentTimeMillis() < deadline) {
                ThreadTool.sleep(5);
            }
        }
        apply(ctx);
    }

    /**
     * Dumps the current spec values into the live app state ({@code ctx.crt},
     * {@code ctx.selectionSoundEnabled}, {@code ctx.audioEngine}). The settings screen calls this after
     * every edit of the shell spec so changes apply live, exactly like the old runtime settings did.
     * @param ctx the app context receiving the values
     */
    public static void apply(final AppContext ctx) {
        ctx.crt = crtOverlay;
        ctx.selectionSoundEnabled = selectionSound;
        ctx.audioEngine = engines.audio == null ? AudioEngine.OPENAL : engines.audio;
    }

    /**
     * Syncs the CRT overlay field from the global keyboard toggle and marks the spec dirty so the
     * WaterConfig worker persists it. A no-op (beyond the field write) when the spec is unavailable.
     * @param on the new CRT overlay state
     */
    public static void crt(final boolean on) {
        crtOverlay = on;
        final ConfigSpec s = spec;
        if (s == null) return;
        final IConfigField<?, ?> field = s.findField("crtOverlay");
        if (field != null) s.markDirty(field);
    }
}
