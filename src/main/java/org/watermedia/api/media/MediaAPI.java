package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.global.avformat;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.binding.internal.libvlc_instance_t;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.watermedia.WaterMedia.LOGGER;

public class MediaAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());
    private static boolean CLIENT_SIDE;

    protected static libvlc_instance_t VLC_INSTANCE;
    protected static boolean FFMPEG_LOADED;

    static libvlc_instance_t getVlcInstance() {
        WaterMedia.checkIsClientSideOrThrow(MediaAPI.class);
        return VLC_INSTANCE;
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
        if (VideoLan4J.load()) {
            VLC_INSTANCE = VideoLan4J.createInstance("--no-quiet", "--verbose", "--file-logging", "--logfile=/logs/vlc.log", "--vout=direct3d11");
            LOGGER.info(IT, "Created new LibVLC instance");
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
        if (VLC_INSTANCE == null && !FFMPEG_LOADED) {
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
        if (VLC_INSTANCE != null) {
            VideoLan4J.releaseInstance(VLC_INSTANCE);
        }
        if (FFMPEG_LOADED) {
            avformat.avformat_network_deinit();
        }
    }
}