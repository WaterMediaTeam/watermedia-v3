package org.watermedia;

import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.DownscaleFilter;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.media.GFXEngine;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MediaPlayer;
import org.watermedia.api.media.SFXEngine;
import org.watermedia.api.media.SFXEngine.SpatialAudio;
import org.watermedia.api.media.ServerMediaPlayer;
import org.watermedia.api.media.Sync;
import org.watermedia.api.media.VKContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Compiled counterparts of the public API guide's integration examples. */
public final class ApiGuideExample {
    private ApiGuideExample() {}

    public static boolean start(final Path temporaryDirectory, final Path workingDirectory) {
        WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
        return WaterMedia.loaded();
    }

    public static MRL refresh(final MRL mrl) {
        return mrl.reload();
    }

    public static GFXEngine graphics(final String type, final Thread renderThread, final Executor executor,
                                     final VKContext context, final Runnable onFrame) {
        return switch (type) {
            case "GL" -> MediaAPI.glEngine(renderThread, executor);
            case "VK" -> MediaAPI.vkEngine(context);
            case "AWT" -> MediaAPI.awtEngine(onFrame);
            case "JFX" -> MediaAPI.jfxEngine(onFrame);
            case "HEADLESS" -> MediaAPI.headlessEngine(false);
            default -> throw new IllegalArgumentException("Unknown graphics engine");
        };
    }

    public static SFXEngine audio(final String type) {
        return switch (type) {
            case "AL" -> MediaAPI.alEngine();
            case "SPATIAL" -> MediaAPI.alEngine(true);
            case "JS" -> MediaAPI.jsEngine();
            default -> throw new IllegalArgumentException("Unknown audio engine");
        };
    }

    public static boolean position(final MediaPlayer player, final double x, final double y, final double z) {
        return player.spatialAudio(new SpatialAudio(x, y, z, 8.0f, 64.0f, 1.0f, false, null));
    }

    public static MediaPlayer play(final MRL mrl, final Supplier<GFXEngine> graphics, final Supplier<SFXEngine> audio) {
        if (mrl.status() != MRL.Status.LOADED) throw new IllegalStateException("Media has not loaded");
        final MediaPlayer player = MediaAPI.player(mrl, 0, graphics, audio);
        if (player == null) throw new IllegalStateException("Source is unavailable or its backend failed");
        try {
            if (!player.start()) throw new IllegalStateException("Player refused to start");
            return player;
        } catch (final RuntimeException | Error failure) {
            player.release();
            throw failure;
        }
    }

    public static void controls(final MediaPlayer player) {
        player.pause(true);
        player.pause(false);
        player.seek(15_000);
        player.volume(50);
        player.mute(true);
        player.speed(1.25f);
        player.repeat(true);
        player.scale(1280, 720);
    }

    public static ServerMediaPlayer clock(final long durationMs, final Consumer<Sync> transport) {
        final ServerMediaPlayer clock = MediaAPI.serverPlayer();
        clock.syncDuration(durationMs);
        clock.start();
        // THE HOST SENDS A SNAPSHOT AGAIN WHENEVER clock.revision() MOVES
        transport.accept(clock.snapshot());
        return clock;
    }

    public static void follow(final MediaPlayer player, final Sync snapshot) {
        player.tolerance(500L);
        player.sync(snapshot);
    }

    public static long decode(final ByteBuffer encoded) throws IOException {
        long frames = 0L;
        try (final ImageReader reader = CodecsAPI.reader(encoded)) {
            reader.addFilter(new DownscaleFilter(0.5f));
            while (reader.hasNext()) {
                final ByteBuffer pixels = reader.next();
                if (!pixels.hasRemaining() || reader.frameWidth() > reader.width() || reader.frameHeight() > reader.height())
                    throw new IOException("Filtered frame is empty or larger than its source");
                frames++;
            }
        }
        return frames;
    }
}
