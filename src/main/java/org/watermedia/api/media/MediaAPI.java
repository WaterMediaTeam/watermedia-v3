package org.watermedia.api.media;

import java.io.File;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
// import org.bytedeco.ffmpeg.avutil.LogCallback; // NATIVE LOG ROUTING, DISABLED IN startFFmpeg
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.BytePointer;
import org.watermedia.api.util.MediaType;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.FFTool;
import org.watermedia.tools.IOTool;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.bootstrap.WaterMediaModule;

import static org.watermedia.WaterMedia.LOGGER;

/** Media facade: resolves MRLs, creates players and engines, and reports the FFmpeg state. */
public final class MediaAPI {
    private MediaAPI() {}
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());

    // NATIVE STATE IS PUBLISHED ONCE, WHEN THE MODULE LOADS
    private static volatile boolean ffmpegLoaded;
    private static volatile Throwable ffmpegFailure;
    // private static LogCallback nativeLog; // KEEPS THE NATIVE LOG THUNK REACHABLE WHILE FFMPEG MAY CALL IT
    private static volatile boolean vulkanHwDecode; // BUILD+DRIVER CAN CREATE A VULKAN HW-DECODE DEVICE (PROBED AT BOOT)

    /**
     * Gets or creates an MRL for the given URI string.
     * If cached and not expired, returns immediately; otherwise starts async loading.
     * <p>
     * A string naming an existing file is resolved through the filesystem — relative paths
     * resolve against the working directory WaterMedia started with (the same root as
     * {@code water://local}), never the launch directory. Anything else is parsed as a URI.
     * This method never throws on malformed or relative input: it returns a non-cached MRL born
     * in {@link MRL.Status#ERROR} carrying the failure as {@link MRL#exception()}, consistent
     * with every other resolution failure.
     *
     * @param uri the media URI or file path
     * @return the MRL instance (may still be loading, or in ERROR for malformed input)
     * @throws IllegalArgumentException when {@code uri} is null
     * @throws IllegalStateException before WaterMedia loads, and on dedicated servers
     */
    public static MRL mrl(final String uri) {
        if (uri == null) throw new IllegalArgumentException("URI cannot be null");
        try {
            final Path file = WaterMedia.cwd().resolve(uri);
            if (Files.isRegularFile(file)) return MRL.get(file.toAbsolutePath().normalize().toUri());
        } catch (final InvalidPathException ignored) {
            // URLS CARRY CHARACTERS A PATH CANNOT (":" ON WINDOWS): NOT A FILE
        }
        try {
            return MRL.get(URI.create(uri));
        } catch (final IllegalArgumentException e) {
            // MALFORMED INPUT USES THE SAME ERROR STATUS AS RESOLUTION FAILURES.
            return MRL.error(uri, e);
        }
    }

    /**
     * Gets or creates an MRL for the given URI.
     * If cached and not expired, returns immediately.
     * Otherwise, starts async loading via the platform API. A relative URI cannot be resolved
     * and yields a non-cached MRL born in {@link MRL.Status#ERROR}.
     *
     * @param uri the media URI
     * @return the MRL instance (may still be loading)
     * @throws IllegalArgumentException when {@code uri} is null
     * @throws IllegalStateException    before WaterMedia loads, and on dedicated servers
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

    /** Creates a plain {@link ServerMediaPlayer}: a clock without media or engines, on either side; broadcasting, if any, stays manual. */
    public static ServerMediaPlayer serverPlayer() {
        return new ServerMediaPlayer();
    }

    /**
     * Creates a broadcasting {@link ServerMediaPlayer} authority: the whole server side of the
     * sync protocol runs automatically over the given byte carrier. Feed received upstream
     * payloads through {@link ServerMediaPlayer#sync(ByteBuffer)}.
     * @param bridge downstream byte carrier that broadcasts to every follower
     * @param capabilities capabilities granted to the followers
     * @return the authority instance
     */
    public static ServerMediaPlayer serverPlayer(final Bridge bridge, final Config.Capability... capabilities) {
        if (capabilities == null) throw new IllegalArgumentException("Capabilities cannot be null");
        return new ServerMediaPlayer(bridge, capabilities);
    }

    /**
     * Creates a headless {@link ServerMediaPlayer} follower: a wall clock driven by a remote authority
     * through the given carrier, useful as a media-less mirror (HUDs, logic-side replicas) and for
     * exercising the protocol without a media backend. Feed received payloads through
     * {@link ServerMediaPlayer#sync(ByteBuffer)}.
     * @param bridge upstream byte carrier towards the authority
     * @return the follower instance
     */
    public static ServerMediaPlayer follower(final Bridge bridge) {
        return ServerMediaPlayer.follower(bridge);
    }

    /**
     * Creates a media player for the FIRST MRL source, or {@code null} when no player can be built.
     * <p>
     * {@code null} covers two distinct situations, logged distinctly: the source isn't ready yet
     * (MRL still {@link MRL.Status#FETCHING} — retry next tick) or a permanent failure (failed MRL,
     * invalid index, missing backend, or a construction crash). Check {@link MRL#status()} to tell
     * them apart. {@link Error}s always propagate.
     *
     * @param mrl the resolved MRL
     * @param gfx supplier of the video sink, invoked at most once
     * @param sfx supplier of the audio sink, invoked at most once
     * @return the player, or {@code null} when not ready or on failure
     */
    public static MediaPlayer player(final MRL mrl, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        return player(mrl, 0, gfx, sfx, null);
    }

    /**
     * Creates a media player for the FIRST MRL source that follows a {@link ServerMediaPlayer} authority
     * through the given bridge, or {@code null} when no player can be built.
     * @param mrl the resolved MRL
     * @param gfx supplier of the video sink, invoked at most once
     * @param sfx supplier of the audio sink, invoked at most once
     * @param bridge upstream byte carrier towards the authority, or {@code null} for a plain player
     * @return the player, or {@code null} when not ready or on failure
     * @see #player(MRL, int, Supplier, Supplier)
     */
    public static MediaPlayer player(final MRL mrl, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx, final Bridge bridge) {
        return player(mrl, 0, gfx, sfx, bridge);
    }

    /**
     * Creates a media player for the given MRL source, or {@code null} when no player can be built.
     * <p>
     * {@code null} covers two distinct situations, logged distinctly: the source isn't ready yet
     * (MRL still {@link MRL.Status#FETCHING} — retry next tick) or a permanent failure (failed MRL,
     * invalid index, missing backend, or a construction crash). Check {@link MRL#status()} to tell
     * them apart. {@link Error}s always propagate.
     *
     * @param mrl the resolved MRL
     * @param sourceIndex index of the source to play
     * @param gfx supplier of the video sink, invoked at most once
     * @param sfx supplier of the audio sink, invoked at most once
     * @return the player, or {@code null} when not ready or on failure
     */
    public static MediaPlayer player(final MRL mrl, final int sourceIndex, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        return player(mrl, sourceIndex, gfx, sfx, null);
    }

    /**
     * Creates a media player for the given MRL source that follows a {@link ServerMediaPlayer} authority
     * through the given bridge, or {@code null} when no player can be built.
     * @param mrl the resolved MRL
     * @param sourceIndex index of the source to play
     * @param gfx supplier of the video sink, invoked at most once
     * @param sfx supplier of the audio sink, invoked at most once
     * @param bridge upstream byte carrier towards the authority, or {@code null} for a plain player
     * @return the player, or {@code null} when not ready or on failure
     * @throws IllegalArgumentException when {@code mrl}, {@code gfx} or {@code sfx} is null
     * @see #player(MRL, int, Supplier, Supplier)
     */
    public static MediaPlayer player(final MRL mrl, final int sourceIndex, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx, final Bridge bridge) {
        if (mrl == null || gfx == null || sfx == null) throw new IllegalArgumentException("The MRL and both engine suppliers are required");
        final MRL.Source source = mrl.source(sourceIndex);
        if (source == null) {
            // STILL FETCHING IS A NORMAL TRANSIENT STATE — DON'T SPAM WARNINGS FOR IT
            final MRL.Status status = mrl.status();
            if (status == MRL.Status.FETCHING) {
                LOGGER.debug(IT, "Source {} not ready yet (still fetching): {}", sourceIndex, mrl.uri());
            } else {
                LOGGER.warn(IT, "Cannot create player: source {} unavailable (status {}) for {}", sourceIndex, status, mrl.uri());
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
                return new TxMediaPlayer(mrl, sourceIndex, gfxEngine, bridge);
            }

            if (ffmpegLoaded) {
                LOGGER.debug(IT, "Creating FFMediaPlayer for: {}", source);
                gfxEngine = gfx.get();
                sfxEngine = sfx.get();
                return new FFMediaPlayer(mrl, sourceIndex, gfxEngine, sfxEngine, bridge);
            }

            LOGGER.error(IT, "No media backend available for: {}", mrl.uri());
            return null;
        } catch (final Exception e) { // VM AND LINKAGE ERRORS MUST PROPAGATE.
            try { if (gfxEngine != null) gfxEngine.release(); } catch (final Exception cleanup) { LOGGER.warn(IT, "Failed to release GFX engine after construction failure", cleanup); }
            try { if (sfxEngine != null) sfxEngine.release(); } catch (final Exception cleanup) { LOGGER.warn(IT, "Failed to release SFX engine after construction failure", cleanup); }
            LOGGER.error(IT, "Player construction failed for: {}", mrl.uri(), e);
            return null;
        }
    }

    // ENGINE AND MEDIA PLAYER CONSTRUCTORS ENFORCE CLIENT ACCESS; ONLY ServerMediaPlayer EXISTS ON A DEDICATED SERVER.

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
     * @throws IllegalStateException when JavaFX 13 or newer is not on the class path
     */
    public static JFXEngine jfxEngine(final Runnable onFrame) {
        // JFXEngine LINKS JAVAFX TYPES WHEN IT LOADS AND NEEDS PixelBuffer (JAVAFX 13+), SO A MISSING TOOLKIT FAILS HERE
        try { Class.forName("javafx.scene.image.PixelBuffer", false, MediaAPI.class.getClassLoader()); }
        catch (final ClassNotFoundException | LinkageError e) { throw new IllegalStateException("JavaFX 13 or newer is not on the class path", e); }
        return new JFXEngine(onFrame);
    }

    /**
     * Creates an AWT/Swing software video engine; paint its {@code image()} from a component.
     * @param onFrame hook run on the upload thread after each frame is published, or null
     */
    public static AWTEngine awtEngine(final Runnable onFrame) {
        return new AWTEngine(onFrame);
    }

    /** Creates a headless capture engine that records uploads in memory, for tests and image processing without a GPU. */
    public static HeadlessGFXEngine headlessEngine() {
        return new HeadlessGFXEngine();
    }

    /** Creates a headless capture engine; {@code preload} makes it report frame-texture preloading support. */
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

    /** Creates an OpenAL engine; spatial mode negotiates mono and accepts host sound-thread updates. */
    public static ALEngine alEngine(final boolean spatial) {
        return new ALEngine(ALEngine.DEFAULT_BUFFER_COUNT, spatial);
    }

    /** Creates an OpenAL engine with explicit buffer depth and optional mono spatial playback. */
    public static ALEngine alEngine(final int buffers, final boolean spatial) {
        return new ALEngine(buffers, spatial);
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

    /** Returns whether the FFmpeg engine initialized successfully at boot. */
    public static boolean ffmpegLoaded() {
        return ffmpegLoaded;
    }

    /** Returns whether the FFmpeg engine failed to initialize at boot. */
    public static boolean ffmpegError() {
        return ffmpegFailure != null;
    }

    /**
     * Whether this FFmpeg build and the GPU/driver can create a Vulkan hardware-decode device (probed
     * once at boot). Even when {@code true}, frames are still decoded in software and host-imported,
     * because the shipped FFmpeg JNI does not expose {@code AVVkFrame}/{@code AVVulkanDeviceContext}
     * for a true GPU-to-GPU import — so Vulkan decode would only add a GPU-to-RAM download here.
     * @return {@code true} when Vulkan hardware decode is supported and available on this system
     */
    public static boolean vulkanDecode() {
        return vulkanHwDecode;
    }

    /** Internal module: media resolution, the network cache and native playback; only clients run it. */
    public static final class Module extends WaterMediaModule {
        public Module(final WaterMedia watermedia) { super(watermedia); }

        @Override
        public void start() {
            // A DEDICATED SERVER PLAYS NOTHING: THE RESOLVER, THE CACHE AND THE NATIVES STAY OFF
            if (!this.watermedia.clientSide) return;
            MRL.start();
            try {
                NetworkCache.start(this.watermedia.tmp.resolve("cache"));
            } catch (final Exception failure) { // PLAYBACK WORKS WITHOUT THE DISK CACHE
                LOGGER.error(IT, "The media cache did not start; playback continues without it", failure);
            }
            // THE NATIVES COME FROM THE BINARIES MODULE; WITHOUT ITS JAR THERE IS NOTHING TO LOAD
            if (MediaAPI.class.getClassLoader().getResource("org/watermedia/binaries/WaterMediaBinaries.class") == null) {
                LOGGER.warn(IT, "FFmpeg is unavailable: WaterMedia Binaries is not installed");
                return;
            }
            startFFmpeg(); // LOGS ITS OWN FAILURE AND LEAVES ffmpegError() SET
        }
    }

    // BOOTS THE BUNDLED FFMPEG NATIVES: POINTS JAVACPP AT THE EXTRACTED BINARIES, LOGS THE BUILD
    // BANNER AND PROBES HARDWARE ACCELERATION. DOES NOTHING WHEN DISABLED BY CONFIG
    private static void startFFmpeg() {
        LOGGER.info(IT, "Starting FFMPEG...");
        if (WaterMediaConfig.media.ffmpeg.disable) {
            LOGGER.warn(IT, "FFMPEG startup was cancelled, user settings disables it");
            return;
        }

        try {
            final Path installed = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID);
            final var customPath = WaterMediaConfig.media.ffmpeg.customPath;
            final String configPath = customPath != null && !customPath.toString().isBlank() ? customPath.toAbsolutePath().toString() : null;
            if (installed == null && configPath == null) throw new IllegalStateException("Verified FFmpeg binaries are unavailable");
            final String paths = installed == null ? configPath : configPath == null
                    ? installed.toString() : installed + File.pathSeparator + configPath;

            System.setProperty("org.bytedeco.javacpp.platform.preloadpath", paths);
            System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

            final String currentLibPath = System.getProperty("java.library.path");
            if (currentLibPath == null || currentLibPath.isEmpty()) {
                System.setProperty("java.library.path", paths);
            } else if (!currentLibPath.contains(paths)) {
                System.setProperty("java.library.path", paths + File.pathSeparator + currentLibPath);
            }

            LOGGER.info(IT, "Configured JavaCPP bindings with: {}", paths);

            LOGGER.info(IT, "=== FFMPEG Build Info ==="); // TODO: parse this
            LOGGER.info(IT, "• avformat: {}", avformat.avformat_version());
            LOGGER.info(IT, "• avcodec:  {}", avcodec.avcodec_version());
            LOGGER.info(IT, "• avutil:   {}", avutil.avutil_version());
            LOGGER.info(IT, "• swscale:  {}", swscale.swscale_version());
            LOGGER.info(IT, "• swresample: {}", swresample.swresample_version());

            // NATIVE LOG ROUTING, DISABLED: JVM CRASHES WERE REPORTED ON MACOS AND LINUX DEPENDING ON THE CALLBACK
            // SIGNATURE. VERIFIED ONLY ON WINDOWS; RE-ENABLE WITH FFmpegLogRoutingTest AFTER TESTING THOSE HOSTS
            // ROUTES NATIVE FFMPEG LOGS THROUGH THE REDACTING PROJECT LOGGER. setLogCallback INSTALLS av_log_set_callback
            // WITH JavaCPP'S C TRAMPOLINE, WHICH FORMATS THE va_list NATIVELY SO IT NEVER CROSSES INTO JAVA
            // if (nativeLog == null) nativeLog = new LogCallback() {
            //     // FFMPEG EMITS A LINE IN FRAGMENTS PER THREAD; IT IS LOGGED ONCE ITS NEWLINE ARRIVES
            //     private final ThreadLocal<StringBuilder> lines = ThreadLocal.withInitial(StringBuilder::new);
            //
            //     @Override
            //     public void call(final int level, final BytePointer message) {
            //         // NOTHING MAY THROW BACK INTO THE NATIVE FFMPEG FRAMES THAT CALLED THIS
            //         try {
            //             final StringBuilder line = this.lines.get().append(message.getString(StandardCharsets.UTF_8));
            //             if (line.isEmpty() || line.charAt(line.length() - 1) != '\n') return;
            //             final String text = line.toString().strip();
            //             line.setLength(0);
            //             final int severity = level & 0xFF;
            //             if (text.isEmpty()) return;
            //             if (severity <= AV_LOG_ERROR) LOGGER.error(IT, "FFmpeg: {}", text);
            //             else if (severity <= AV_LOG_WARNING) LOGGER.warn(IT, "FFmpeg: {}", text);
            //             else LOGGER.debug(IT, "FFmpeg: {}", text);
            //         } catch (final Throwable ignored) {}
            //     }
            // };
            // setLogCallback(nativeLog);

            try {
                final BytePointer config = avformat.avformat_configuration();
                LOGGER.info(IT, "Configuration: {}", FFTool.string(config, "unavailable"));
            } catch (final Exception e) {
                LOGGER.warn(IT, "Configuration: unavailable", e);
            }

            LOGGER.info(IT, "Hardware Acceleration:");
            int hwType = avutil.AV_HWDEVICE_TYPE_NONE;
            int hwCount = 0;
            boolean vulkanInBuild = false;
            do {
                hwType = avutil.av_hwdevice_iterate_types(hwType);
                if (hwType == avutil.AV_HWDEVICE_TYPE_NONE) break;

                final BytePointer hwName = avutil.av_hwdevice_get_type_name(hwType);
                final String hwNameStr = FFTool.string(hwName, null);
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

            // PROBE DEVICE AVAILABILITY FOR DIAGNOSTICS; ZERO-COPY GPU IMPORT REQUIRES MISSING JNI TYPES.
            boolean vulkanDecode = false;
            if (vulkanInBuild) {
                try {
                    final AVBufferRef ref = new AVBufferRef();
                    if (avutil.av_hwdevice_ctx_create(ref, avutil.AV_HWDEVICE_TYPE_VULKAN, (String) null, null, 0) >= 0) {
                        avutil.av_buffer_unref(ref);
                        vulkanDecode = true;
                    }
                } catch (final Exception | LinkageError e) {
                    LOGGER.debug(IT, "Vulkan hardware decoding unavailable", e); // NO VULKAN DEVICE: THE PROBE STAYS NEGATIVE
                }
            }
            vulkanHwDecode = vulkanDecode;
            LOGGER.info(IT, "Vulkan hardware decode: {}", vulkanDecode
                    ? "available (zero-copy GPU import needs AVVkFrame JNI, absent here — software decode + host-import is used)"
                    : "unavailable");

            FFMediaPlayer.startTLS();
            final BytePointer license = avformat.avformat_license();
            LOGGER.info(IT, "FFMPEG started, running version {} under {}", avformat.avformat_version(), FFTool.string(license, "unknown"));
            IOTool.closeQuietly(license);
            ffmpegLoaded = true;
        } catch (final Exception | LinkageError t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
            vulkanHwDecode = false;
            ffmpegFailure = t;
        }
    }
}
