package org.watermedia.binaries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class WaterMediaBinaries {
    private static final Marker IT = MarkerManager.getMarker(WaterMediaBinaries.class.getSimpleName());
    public static final String ID = "watermedia_binaries";
    public static final String NAME = "WaterMedia: Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    // yt-dlp AND rustypipe-botguard ARE DOWNLOADED LAZILY ON FIRST USE (NOT EXTRACTED AT STARTUP LIKE
    // FFMPEG); ONLY THEIR CACHE DIRECTORIES ARE REGISTERED HERE FOR YtDlpBinary/BotGuardBinary TO RESOLVE.
    public static final String YTDLP_ID = "yt-dlp";
    public static final String BOTGUARD_ID = "botguard";
    public static final Logger LOGGER = LogManager.getLogger(ID);
    // CONCURRENT: WRITTEN ONCE AT BOOT BY start(), READ LATER FROM MEDIA-RESOLUTION THREADS VIA
    // pathOf()/binaryDir() (THE LAZILY-DOWNLOADED yt-dlp/botguard BINARIES RESOLVE OFF-THREAD)
    private static final Map<String, Path> BINARY_PATHS = new ConcurrentHashMap<>();

    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        final var baseDir = tmp != null ? tmp : cwd;
        if (baseDir == null) {
            LOGGER.error(IT, "Failed to start WaterMedia Binaries, no valid base directory (tmp or cwd)");
            return;
        }

        // RESOLVING PATHS
        LOGGER.info(IT, "Resolving binaries paths");
        BINARY_PATHS.put(FFMPEG_ID, baseDir.resolve(FFMPEG_ID));
        BINARY_PATHS.put(YTDLP_ID, baseDir.resolve(YTDLP_ID));
        BINARY_PATHS.put(BOTGUARD_ID, baseDir.resolve(BOTGUARD_ID));

        // STARTING BINARIES
        LOGGER.info(IT, "Starting binaries extraction in path: {}", baseDir.toAbsolutePath());
        if (clientSide) execute(FFMPEG_ID, () -> FFmpegBinaries.start(baseDir.resolve(FFMPEG_ID)));
    }

    public static synchronized void cleanup() {
        if (BINARY_PATHS.isEmpty()) {
            LOGGER.warn(IT, "Binaries paths not initialized, skipping cleanup");
            return;
        }

        LOGGER.info(IT, "Starting binaries cleanup");
        execute(FFMPEG_ID, () -> FFmpegBinaries.cleanup(BINARY_PATHS.get(FFMPEG_ID)));
    }

    public static Path pathOf(String id) {
        return BINARY_PATHS.get(id);
    }

    // RESOLVES A REGISTERED BINARY CACHE DIRECTORY, FAILING CLEANLY IF start() HAS NOT RUN YET (THE
    // LAZILY-DOWNLOADED BINARIES NEED THE BASE DIR THAT start() PUBLISHES). USED BY YtDlpBinary/BotGuardBinary.
    static Path binaryDir(final String id) throws IOException {
        final Path dir = BINARY_PATHS.get(id);
        if (dir == null) {
            throw new IOException("WaterMedia Binaries is not initialized; cannot resolve '" + id + "'");
        }
        return dir;
    }

    private static void execute(String id, Callable<Boolean> task) {
        try {
            task.call();
        } catch (final Exception e) {
            LOGGER.error(IT, "Exception occurred for {}", id, e);
        }
    }
}