package org.watermedia.binaries;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.VersionTool;

import java.nio.file.Path;
import java.util.Objects;

import static org.watermedia.binaries.WaterMediaBinaries.LOGGER;

class FFmpegBinaries {
    private static final Marker IT = MarkerManager.getMarker(FFmpegBinaries.class.getSimpleName());
    private static final String RESOURCE_PATH = "libs/ffmpeg-%s.zip";

    static boolean start(final Path baseDir) throws Exception {
        final String platform = IOTool.platformClassifier();
        final String resourcePath = String.format(RESOURCE_PATH, platform);

        // CHECK VERSION
        final var zipVersion = new VersionTool(IOTool.jarReadZip(FFmpegBinaries.class.getClassLoader().getResourceAsStream(resourcePath), IOTool.VERSION_FILE));
        final var currentVersion = new VersionTool(IOTool.read(baseDir.resolve(IOTool.VERSION_FILE).toFile()));

        LOGGER.info(IT, "FFMPEG in JAR | Version: {} - Path: {}", zipVersion, resourcePath);
        LOGGER.info(IT, "FFMPEG extracted | Version: {} - Path: {}", currentVersion, baseDir);
        // RE-EXTRACT WHEN NOTHING IS ON DISK, THE EXTRACTED BUILD IS OLDER, OR IT SHARES THE SAME
        // NUMERIC VERSION BUT A DIFFERENT BUILD QUALIFIER (E.G. LGPL -> GPL). VersionTool.compareTo
        // ONLY WEIGHS major.minor.revision, SO THE EXTRA-FIELD CHECK IS NEEDED TO CATCH SAME-VERSION SWAPS.
        final boolean stale = currentVersion.isZero()
                || !currentVersion.atLeast(zipVersion)
                || (currentVersion.compareTo(zipVersion) == 0 && !Objects.equals(currentVersion.extra, zipVersion.extra));
        if (!zipVersion.isZero() && stale) {
            LOGGER.info(IT, "Starting FFmpeg {} extraction for platform {}", zipVersion, platform);

            // CLEANUP OLD VERSION
            if (!cleanup(baseDir)) {
                return  false;
            }

            // EXTRACT NEW VERSION
            if (IOTool.jarExtractZip(FFmpegBinaries.class.getClassLoader().getResourceAsStream(resourcePath), baseDir.toFile())) {
                LOGGER.info(IT, "Successfully extracted FFmpeg {}", zipVersion);
            } else {
                LOGGER.error(IT, "Failed to extract FFmpeg {} for platform", zipVersion);
                return false;
            }
        } else {
            LOGGER.info(IT, "FFmpeg already up to date, skipping extraction");
        }

        // VERIFY EXTRACTION
        if (IOTool.count(baseDir.resolve(IOTool.VERSION_FILE).toFile()) != 1) {
            LOGGER.error(IT, "FFmpeg version file missing after extraction");
            return false;
        }

        return true;
    }

    static boolean cleanup(final Path baseDir) {
        final int count = IOTool.count(baseDir.toFile());
        if (count > 0) {
            LOGGER.info(IT, "Cleaning up {} of current FFmpeg files", count);
            final int deleted = IOTool.delete(baseDir.toFile());
            if (deleted == count) {
                LOGGER.info(IT, "Successfully deleted {} files", deleted);
            } else if (deleted > 0) {
                LOGGER.warn(IT, "Deleted {} out of {} files", deleted, count);
            } else {
                LOGGER.error(IT, "Failed to delete FFmpeg files");
                return false;
            }
        } else {
            LOGGER.info(IT, "No FFmpeg files to clean up");
        }
        return true;
    }
}