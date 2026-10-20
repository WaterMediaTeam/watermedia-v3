package org.watermedia.bootstrap.app;

import org.watermedia.WaterMedia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * State for the cleanup cache dialog, plus the scan of the media cache it cleans.
 */
public final class CleanupState {
    public volatile boolean visible;
    public volatile boolean working;
    public volatile boolean done;
    public volatile boolean error;
    public volatile int stage = 1;
    public volatile int fileCount;
    public volatile String sizeLabel = "0 B";
    public volatile String state = "READY";
    public volatile int progress;

    /** Returns the dialog to its first stage, before a new scan. */
    public void reset() {
        this.working = false;
        this.done = false;
        this.error = false;
        this.stage = 1;
        this.state = "SCAN";
        this.fileCount = 0;
        this.sizeLabel = "0 B";
        this.progress = 0;
    }

    /** Returns the media cache folder the dialog cleans. */
    public static Path dir() {
        return WaterMedia.tmp().resolve("cache");
    }

    /** Counts the files and bytes in the media cache, as {@code {files, bytes}}. */
    public static long[] scan() throws IOException {
        final long[] stats = new long[2];
        final Path cache = dir();
        if (!Files.exists(cache)) return stats;
        try (final Stream<Path> files = Files.walk(cache)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                stats[0]++;
                try { stats[1] += Files.size(path); }
                catch (final IOException ignored) {} // A FILE DELETED DURING THE WALK STILL COUNTS, WITHOUT ITS SIZE
            });
        } catch (final UncheckedIOException failure) {
            throw failure.getCause();
        }
        return stats;
    }
}
