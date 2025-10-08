package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.global.avformat;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.engine.ALManager;
import org.watermedia.api.media.engine.GLManager;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.media.players.VLMediaPlayer;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.NetTool;
import org.watermedia.videolan4j.VideoLan4J;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public class MediaAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());

    protected static boolean FFMPEG_LOADED;

    public static MediaPlayer getMediaPlayer(URI uri, Thread thread, Executor renderThreadEx, GLManager glManager, ALManager alManager, boolean video, boolean audio) {
        WaterMedia.checkIsClientSideOrThrow(MediaAPI.class);

        try {
            final URLConnection conn = uri.toURL().openConnection();
            if (conn instanceof HttpURLConnection http) {
                NetTool.validateHTTP200(http.getResponseCode(), uri);
            }
            final String[] type = conn.getContentType().split("/");
            if (type[0].equals("image")) {
                return new TxMediaPlayer(uri, thread, renderThreadEx, glManager, video);
            } else {
                if (VideoLan4J.isDiscovered()) {
                    LOGGER.debug(IT, "Creating LibVLC MediaPlayer for URI: {}", uri);
                    return new VLMediaPlayer(uri, thread, renderThreadEx, glManager, alManager, video, audio);
                } else if (FFMPEG_LOADED) {
                    LOGGER.debug(IT, "Creating FFMPEG MediaPlayer for URI: {}", uri);
                    return new FFMediaPlayer(uri, thread, renderThreadEx, glManager, alManager, video, audio);
                } else {
                    LOGGER.error(IT, "Neither LibVLC nor FFMPEG are loaded, cannot create MediaPlayer for URI: {}", uri);
                }
            }
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to create MediaPlayer for URI: {}", uri, t);
        }
        return null;
    }

    @Override
    public boolean start(final WaterMedia instance) throws Exception {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Detected server-side environment, lockdown mode enabled");
            return false;
        }

        // EXTRACT BINARIES
        LOGGER.info(IT, "Starting WaterMedia Binaries extraction...");
        WaterMediaBinaries.start(instance.name, instance.tmp, instance.cwd, true);

        // START LibVLC
        LOGGER.info(IT, "Starting LibVLC...");
        if (VideoLan4J.load("--no-quiet", "--verbose", "--file-logging", "--logfile=/logs/vlc.log")) {
            LOGGER.info(IT, "Created new LibVLC instance");
            VideoLan4J.setBufferAllocator(MemoryUtil::memAlignedAlloc);
            VideoLan4J.setBufferDeallocator(MemoryUtil::memAlignedFree);
            LOGGER.info(IT, "Overrided LibVLC buffer allocator/deallocator");
            LOGGER.info(IT, "LibVLC started, running version {} under {}", VideoLan4J.getLibVersion(), null);
        } else {
            LOGGER.error(IT, "Failed to load LibVLC");
        }

        // START FFMPEG
        try {
            LOGGER.info(IT, "Starting FFMPEG...");

            // Configure JavaCPP to use our custom FFmpeg binaries
            final Path ffmpegPath = WaterMediaBinaries.getBinaryPath(WaterMediaBinaries.FFMPEG_ID);

            if (ffmpegPath != null && Files.exists(ffmpegPath)) {
                // Set JavaCPP properties for custom binary path
                final String pathStr = ffmpegPath.toAbsolutePath().toString();

                // Set the library path for JavaCPP
                System.setProperty("org.bytedeco.javacpp.platform.preloadpath", pathStr);
                System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");

                // Add to java.library.path
                final String currentLibPath = System.getProperty("java.library.path");
                if (currentLibPath == null || currentLibPath.isEmpty()) {
                    System.setProperty("java.library.path", pathStr);
                } else if (!currentLibPath.contains(pathStr)) {
                    System.setProperty("java.library.path", pathStr + java.io.File.pathSeparator + currentLibPath);
                }

                LOGGER.info(IT, "Configured JavaCPP with custom FFmpeg path: {}", pathStr);
            } else {
                LOGGER.warn(IT, "FFmpeg binaries path not found, using JavaCPP defaults");
            }

            // Initialize FFmpeg
            avformat.avformat_network_init();
            FFMPEG_LOADED = true;
            LOGGER.info(IT, "FFMPEG started, running version {} under {}", avformat.avformat_version(), avformat.avformat_license());
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to load FFMPEG", t);
        }

        // CHECK IF NEITHER OF THEM ARE LOADED
        if (VideoLan4J.isDiscovered() && !FFMPEG_LOADED) {
            LOGGER.fatal(IT, "LibVLC and FFMPEG are unable to be started in your environment, please report this issue to the WaterMedia's authors");
            return false;
        }

        return true;
    }

    @Override
    public boolean onlyClient() {
        return true;
    }

    @Override
    public void test() {
        LOGGER.info(IT, "Testing LibVLC...");
        // TODO: test must check IF VLC LOADS and if VLC can be opened playing a basic audio file
        if (VideoLan4J.load()) {
            final var instance = VideoLan4J.createInstance("--no-quiet", "--verbose", "--file-logging", "--logfile=/logs/vlc.log", "--vout=direct3d11");
            VideoLan4J.releaseInstance(instance);
        }

        // TODO: test must run a FFPROBE test
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void release(final WaterMedia instance) {
        if (FFMPEG_LOADED) {
            avformat.avformat_network_deinit();
        }
    }
}