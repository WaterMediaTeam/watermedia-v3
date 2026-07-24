package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.BytePointer;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.WaterMediaModule;
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
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.IOTool;

import java.io.File;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.watermedia.WaterMedia.LOGGER;

public final class MediaAPI extends WaterMediaModule {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());
    private static final String STEP_CACHE = "CACHE";
    private static final String STEP_FFMPEG = "FFMPEG";

    // FFMPEG ENGINE STATE — SET ONCE AT BOOT BY start(), POLLED FROM UI/PLAYER THREADS
    private static volatile boolean FFMPEG_LOADED;
    private static volatile boolean FFMPEG_ERROR;
    private static volatile boolean VULKAN_DECODE; // BUILD+DRIVER CAN CREATE A VULKAN HW-DECODE DEVICE (PROBED AT BOOT)

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

            if (FFMPEG_LOADED) {
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

    // ==========================================================================
    // FFMPEG ENGINE STATE
    // ==========================================================================

    /** @return {@code true} once the FFmpeg engine initialized successfully at boot */
    public static boolean ffmpegLoaded() {
        return FFMPEG_LOADED;
    }

    /** @return {@code true} if the FFmpeg engine failed to initialize at boot */
    public static boolean ffmpegError() {
        return FFMPEG_ERROR;
    }

    /**
     * Whether this FFmpeg build and the GPU/driver can create a Vulkan hardware-decode device (probed
     * once at boot). Even when {@code true}, frames are still decoded in software and host-imported,
     * because the shipped FFmpeg JNI does not expose {@code AVVkFrame}/{@code AVVulkanDeviceContext}
     * for a true GPU-to-GPU import — so Vulkan decode would only add a GPU-to-RAM download here.
     * @return {@code true} when Vulkan hardware decode is supported and available on this system
     */
    public static boolean vulkanDecode() {
        return VULKAN_DECODE;
    }

    @Override
    public String name() {
        return MediaAPI.class.getSimpleName();
    }

    @Override
    protected void load(final WaterMedia instance) {
        super.load(instance);
        this.steps = instance.clientSide ? 2 : 0; // CACHE + FFMPEG
    }

    @Override
    protected boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Skipping media API start on server-side");
            return false;
        }

        this.step++;
        this.stepName = STEP_CACHE;
        LOGGER.info(IT, "Starting media network cache");
        try {
            NetworkCache.start(instance.tmp.resolve("cache"));
        } catch (final Exception e) {
            LOGGER.warn(IT, "Failed to initialize media network cache", e);
            this.failures.add(STEP_CACHE);
        }

        this.step++;
        this.stepName = STEP_FFMPEG;
        LOGGER.info(IT, "Starting media engines");
        // A DISABLED-BY-CONFIG FFMPEG IS A SKIP; ONLY A REAL LOAD CRASH COUNTS AS A SAFE FAILURE
        if (!startFFmpeg() && FFMPEG_ERROR) {
            this.failures.add(STEP_FFMPEG);
        }

        return true;
    }

    @Override
    protected void release(final WaterMedia instance) {
        NetworkCache.release();
        super.release(instance);
    }

    // BOOTS THE BUNDLED FFMPEG NATIVES: POINTS JAVACPP AT THE EXTRACTED BINARIES, LOGS THE BUILD
    // BANNER AND PROBES HARDWARE ACCELERATION. RETURNS false WHEN DISABLED BY CONFIG OR ON FAILURE.
    private static boolean startFFmpeg() {
        LOGGER.info(IT, "Starting FFMPEG...");
        if (WaterMediaConfig.media.ffmpeg.disable) {
            LOGGER.warn(IT, "FFMPEG startup was cancelled, user settings disables it");
            return false;
        }

        try {
            final String ffmpegPath = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID).toAbsolutePath().toString();
            final String configPath = WaterMediaConfig.media.ffmpeg.customPath != null ? WaterMediaConfig.media.ffmpeg.customPath.toAbsolutePath().toString() : null;
            final String paths = configPath != null ? ffmpegPath + File.pathSeparator + configPath : ffmpegPath;

            System.setProperty("org.bytedeco.javacpp.platform.preloadpath", paths);
            System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

            final String currentLibPath = System.getProperty("java.library.path");
            if (currentLibPath == null || currentLibPath.isEmpty()) {
                System.setProperty("java.library.path", ffmpegPath);
            } else if (!currentLibPath.contains(ffmpegPath)) {
                System.setProperty("java.library.path", ffmpegPath + File.pathSeparator + currentLibPath);
            }

            LOGGER.info(IT, "Configured JavaCPP bindings with: {}", paths);

            LOGGER.info(IT, "=== FFMPEG Build Info ===");
            LOGGER.info(IT, "• avformat: {}", avformat.avformat_version());
            LOGGER.info(IT, "• avcodec:  {}", avcodec.avcodec_version());
            LOGGER.info(IT, "• avutil:   {}", avutil.avutil_version());
            LOGGER.info(IT, "• swscale:  {}", swscale.swscale_version());
            LOGGER.info(IT, "• swresample: {}", swresample.swresample_version());

            try {
                final BytePointer config = avformat.avformat_configuration();
                LOGGER.info(IT, "Configuration: {}", text(config, "unavailable"));
            } catch (final Exception e) {
                LOGGER.warn(IT, "Configuration: unavailable");
            }

            LOGGER.info(IT, "Hardware Acceleration:");
            int hwType = avutil.AV_HWDEVICE_TYPE_NONE;
            int hwCount = 0;
            boolean vulkanInBuild = false;
            do {
                hwType = avutil.av_hwdevice_iterate_types(hwType);
                if (hwType == avutil.AV_HWDEVICE_TYPE_NONE) break;

                final BytePointer hwName = avutil.av_hwdevice_get_type_name(hwType);
                final String hwNameStr = text(hwName, null);
                if (hwNameStr != null) {
                    LOGGER.info(IT, "• {}", hwNameStr);
                    if ("vulkan".equals(hwNameStr)) vulkanInBuild = true;
                    hwCount++;
                }
                IOTool.closeQuietly(hwName);
            } while (true);

            if (hwCount == 0) {
                LOGGER.info(IT, "  (none available)");
            }

            // PROBE VULKAN HARDWARE DECODE (BUILD + DRIVER) BY CREATING A VULKAN HW DEVICE. NOTE: TRUE
            // GPU->GPU ZERO-COPY ALSO NEEDS THE AVVkFrame / AVVulkanDeviceContext JNI TYPES, WHICH THIS
            // BYTEDECO BUILD DOES NOT EXPOSE — SO EVEN WHEN AVAILABLE IT IS NOT PREFERRED (IT WOULD ADD A
            // GPU->RAM DOWNLOAD OVER THE SOFTWARE DECODE + HOST-IMPORT PATH). DIAGNOSTICS ONLY.
            boolean vulkanDecode = false;
            if (vulkanInBuild) {
                try {
                    final AVBufferRef ref = new AVBufferRef();
                    if (avutil.av_hwdevice_ctx_create(ref, avutil.AV_HWDEVICE_TYPE_VULKAN, (String) null, null, 0) >= 0) {
                        avutil.av_buffer_unref(ref);
                        vulkanDecode = true;
                    }
                } catch (final Throwable t) {
                    // NO VULKAN DEVICE — LEAVE THE PROBE NEGATIVE
                }
            }
            VULKAN_DECODE = vulkanDecode;
            LOGGER.info(IT, "Vulkan hardware decode: {}", vulkanDecode
                    ? "available (zero-copy GPU import needs AVVkFrame JNI, absent here — software decode + host-import is used)"
                    : "unavailable");

            final BytePointer license = avformat.avformat_license();
            LOGGER.info(IT, "FFMPEG started, running version {} under {}", avformat.avformat_version(), text(license, "unknown"));
            IOTool.closeQuietly(license);
            return FFMPEG_LOADED = true;
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
            FFMPEG_ERROR = true;
            return false;
        }
    }

    // NULL-SAFE BytePointer -> String FOR THE FFMPEG BOOT BANNER LOGS
    private static String text(final BytePointer p, final String orElse) {
        return p == null || p.isNull() ? orElse : p.getString();
    }
}
