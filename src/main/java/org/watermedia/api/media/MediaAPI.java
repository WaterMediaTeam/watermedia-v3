package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.AWTEngine;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.engines.HeadlessGFXEngine;
import org.watermedia.api.media.engines.JFXEngine;
import org.watermedia.api.media.engines.JSEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.engines.VKEngine;
import org.watermedia.api.media.engines.vk.VKContext;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.api.util.MediaType;

import java.io.File;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.watermedia.WaterMedia.LOGGER;

public final class MediaAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());

    /**
     * Gets or creates an MRL for the given URI string.
     * If cached and not expired, returns immediately; otherwise starts async loading.
     * <p>
     * A string naming an existing file is resolved through the filesystem — relative
     * paths resolve against the current working directory, so the same string can
     * point to different media depending on launch location. Anything else is parsed
     * as a URI. This method never throws on malformed input: it returns a non-cached
     * MRL born in {@link MRL.Status#ERROR} carrying the parse failure as
     * {@link MRL#exception()}, consistent with every other resolution failure.
     *
     * @param uri the media URI or file path
     * @return the MRL instance (may still be loading, or in ERROR for malformed input)
     */
    public static MRL mrl(final String uri) {
        final File f = new File(uri);
        if (f.exists()) return MRL.get(f.getAbsoluteFile().toURI());
        try {
            return MRL.get(URI.create(uri));
        } catch (final IllegalArgumentException e) {
            // MALFORMED INPUT SURFACES AS Status.ERROR LIKE EVERY OTHER RESOLUTION FAILURE, NEVER A THROW
            return MRL.error(uri, e);
        }
    }

    /**
     * Gets or creates an MRL for the given URI.
     * If cached and not expired, returns immediately.
     * Otherwise, starts async loading via the platform API.
     *
     * @param uri the media URI
     * @return the MRL instance (may still be loading)
     */
    public static MRL mrl(final URI uri) {
        return MRL.get(uri);
    }

    /**
     * Preloads multiple URIs in parallel.
     * Useful for prefetching playlists or a bunch of well-known URLs.
     *
     * @param uri the media URIs
     * @return all MRL instances created/existing, in the same order as {@code uri}
     */
    public static MRL[] preload(final URI... uri) {
        return MRL.preload(uri);
    }

    public static MediaPlayer createPlayer(final MRL mrl, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        return createPlayer(mrl, 0, gfx, sfx);
    }

    /**
     * Creates a media player for the given MRL source, or {@code null} when no player can be built.
     * <p>
     * {@code null} covers two distinct situations, logged distinctly: the source isn't ready yet
     * (MRL still {@link MRL.Status#FETCHING} — retry next tick) or a permanent failure (failed MRL,
     * invalid index, missing backend, or a construction crash). Check {@link MRL#status()} to tell
     * them apart. {@link Error}s always propagate.
     *
     * @param mrl the resolved (or resolving) MRL
     * @param sourceIndex index of the source to play
     * @param gfx supplier of the video sink, invoked at most once
     * @param sfx supplier of the audio sink, invoked at most once
     * @return the player, or {@code null} when not ready or on failure
     */
    public static MediaPlayer createPlayer(final MRL mrl, final int sourceIndex, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        final MRL.Source source = mrl.source(sourceIndex);
        if (source == null) {
            // STILL FETCHING IS A NORMAL TRANSIENT STATE — DON'T SPAM WARNINGS FOR IT
            final MRL.Status status = mrl.status();
            if (status == MRL.Status.FETCHING) {
                LOGGER.debug(IT, "Source {} not ready yet (still fetching): {}", sourceIndex, mrl.uri);
            } else {
                LOGGER.warn(IT, "Cannot create player: source {} unavailable (status {}) for {}", sourceIndex, status, mrl.uri);
            }
            return null;
        }

        if (source.type() == MediaType.UNKNOWN) {
            LOGGER.warn(IT, "Creating a media player for an unknown media type: {}", source);
        }

        // MATERIALIZE ENGINES OUTSIDE THE PLAYER CTOR SO A THROW CAN'T LEAK GL/AL STATE ON RETRIES
        GFXEngine gfxEngine = null;
        SFXEngine sfxEngine = null;
        try {
            if (source.type() == MediaType.IMAGE) {
                LOGGER.debug(IT, "Creating TxMediaPlayer for image: {}", source);
                gfxEngine = gfx.get();
                return new TxMediaPlayer(mrl, sourceIndex, gfxEngine);
            }

            if (FFMediaPlayer.loaded()) {
                LOGGER.debug(IT, "Creating FFMediaPlayer for: {}", source);
                gfxEngine = gfx.get();
                sfxEngine = sfx.get();
                return new FFMediaPlayer(mrl, sourceIndex, gfxEngine, sfxEngine);
            }

            LOGGER.error(IT, "No media backend available for: {}", mrl.uri);
            return null;
        } catch (final Exception e) { // EXCEPTIONS ONLY — Errors (OOM, LINKAGE) MUST PROPAGATE
            try { if (gfxEngine != null) gfxEngine.release(); } catch (final Exception cleanup) { LOGGER.warn(IT, "Failed to release GFX engine after construction failure", cleanup); }
            try { if (sfxEngine != null) sfxEngine.release(); } catch (final Exception cleanup) { LOGGER.warn(IT, "Failed to release SFX engine after construction failure", cleanup); }
            LOGGER.error(IT, "Player construction failed for: {}", mrl.uri, e);
            return null;
        }
    }

    // ==========================================================================
    // ENGINE FACTORIES — THE SINGLE PUBLIC PATH TO BUILD VIDEO/AUDIO SINKS.
    // THE CLIENT-SIDE CHECK IS ENFORCED BY THE SEALED GFXEngine/SFXEngine BASE CONSTRUCTORS
    // (HeadlessGFXEngine EXCEPTED), SO NO CONSTRUCTION CAN BYPASS IT.
    // ==========================================================================

    /**
     * Creates an OpenGL video engine bound to a render thread and the executor dispatching onto it.
     * <p>
     * {@code renderEx} must run its tasks on {@code renderThread} (the thread owning the GL
     * context) and be pumped every render frame. Passing both {@code null} builds a
     * no-thread-contract engine whose GL calls run synchronously on the calling thread — which
     * then must own the context.
     * @param renderThread thread owning the GL context, or null for the no-thread-contract mode
     * @param renderEx     executor dispatching onto {@code renderThread}; required when it is non-null
     */
    public static GLEngine glEngine(final Thread renderThread, final Executor renderEx) {
        return new GLEngine(renderThread, renderEx);
    }

    /**
     * Creates a Vulkan video engine over the consumer's borrowed {@link VKContext}.
     * Every object the context exposes must outlive the engine.
     */
    public static VKEngine vkEngine(final VKContext context) {
        return new VKEngine(context);
    }

    /**
     * Creates a JavaFX software video engine; bind its {@code image()} to an {@code ImageView}.
     * @param onFrame hook run on the upload thread after each frame is published, or null
     */
    public static JFXEngine jfxEngine(final Runnable onFrame) {
        return new JFXEngine(onFrame);
    }

    /**
     * Creates an AWT/Swing software video engine; paint its {@code image()} from a component.
     * @param onFrame hook run on the upload thread after each frame is published, or null
     */
    public static AWTEngine awtEngine(final Runnable onFrame) {
        return new AWTEngine(onFrame);
    }

    /**
     * Creates a headless capture engine — the only engine allowed on a server-side environment.
     * @param preload whether the engine reports frame-texture preloading support
     */
    public static HeadlessGFXEngine headlessEngine(final boolean preload) {
        return new HeadlessGFXEngine(preload);
    }

    /** Creates an OpenAL audio engine with the default buffer pool ({@value ALEngine#DEFAULT_BUFFER_COUNT} buffers). */
    public static ALEngine alEngine() {
        return new ALEngine(ALEngine.DEFAULT_BUFFER_COUNT);
    }

    /** Creates an OpenAL audio engine with an explicit buffer pool depth. */
    public static ALEngine alEngine(final int buffers) {
        return new ALEngine(buffers);
    }

    /** Creates a Java Sound audio engine with the default line depth ({@value JSEngine#DEFAULT_BUFFER_MS} ms). */
    public static JSEngine jsEngine() {
        return new JSEngine(JSEngine.DEFAULT_BUFFER_MS);
    }

    /** Creates a Java Sound audio engine with an explicit line buffer depth in milliseconds. */
    public static JSEngine jsEngine(final int bufferMs) {
        return new JSEngine(bufferMs);
    }

    @Override
    public String name() {
        return MediaAPI.class.getSimpleName();
    }

    @Override
    public void load(final WaterMedia instance) {
        super.load(instance);
        this.steps = instance.clientSide ? 2 : 0; // CACHE + FFMPEG
    }

    @Override
    public boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Skipping media API start on server-side");
            return false;
        }

        this.step++;
        this.stepName = "CACHE";
        LOGGER.info(IT, "Starting media network cache");
        try {
            NetworkCache.start(instance.tmp.resolve("cache"));
        } catch (final Exception e) {
            LOGGER.warn(IT, "Failed to initialize media network cache", e);
        }

        this.step++;
        this.stepName = "FFMPEG";
        LOGGER.info(IT, "Starting media engines");
        if (!FFMediaPlayer.load(instance)) {
            LOGGER.error(IT, "Failed to load FFMediaPlayer engine");
        }

        return true;
    }

    @Override
    public void release(final WaterMedia instance) {
        NetworkCache.release();
        super.release(instance);
    }
}
