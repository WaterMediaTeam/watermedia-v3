package org.watermedia.test.support;

import org.watermedia.WaterMedia;
import org.watermedia.api.media.players.FFMediaPlayer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lazily boots the full {@link WaterMedia} stack once for the whole test run so
 * native-backed players ({@code FFMediaPlayer}) have their FFmpeg binaries
 * extracted and loaded.
 *
 * <p>The boot is best-effort: when the platform binaries cannot be resolved
 * (typical on headless CI without the {@code org.watermedia:binaries} natives),
 * {@link #ffmpegAvailable()} returns {@code false} instead of throwing so the
 * dependent tests can {@code Assumptions.assumeTrue(...)} their way to a skip
 * rather than a failure.
 */
public final class MediaBootstrap {
    private static volatile boolean attempted;
    private static volatile boolean ffmpeg;

    private MediaBootstrap() {}

    /** Boots WaterMedia once (idempotent) and reports whether the FFmpeg engine came up. */
    public static synchronized boolean ffmpegAvailable() {
        if (attempted) return ffmpeg;
        attempted = true;
        try {
            final Path tmp = Files.createTempDirectory("wm-test");
            tmp.toFile().deleteOnExit();
            WaterMedia.start("WMTEST", tmp, Path.of("").toAbsolutePath(), true);
            ffmpeg = FFMediaPlayer.loaded();
        } catch (final Throwable t) {
            ffmpeg = false;
        }
        return ffmpeg;
    }
}
