package org.watermedia.test.docs;

import org.watermedia.WaterMedia;
import org.watermedia.WaterMedia.BootStatus;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.HeadlessGFXEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.engines.SFXEngine.SpatialAudio;
import org.watermedia.api.media.engines.vk.VKContext;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.api.media.players.sync.Bridge;
import org.watermedia.api.media.players.sync.Config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Compiled counterparts of the public API guide's integration examples. */
public final class ApiGuideExample {
    private ApiGuideExample() {}

    public static BootStatus start(final Path temporaryDirectory, final Path workingDirectory) {
        WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
        return WaterMedia.status();
    }

    public static MRL refresh(MRL mrl) {
        mrl = mrl.reload();
        return mrl;
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
        final MediaPlayer player = MediaAPI.createPlayer(mrl, 0, graphics, audio);
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
        player.maxSize(1280, 720);
    }

    public static ServerMediaPlayer authority(final Bridge bridge) {
        final ServerMediaPlayer authority = MediaAPI.createPlayer(bridge, Config.Capability.LOCKSTEP, Config.Capability.CONTROLS);
        authority.start();
        return authority;
    }

    public static MediaPlayer follower(final MRL mrl, final Supplier<GFXEngine> graphics,
                                       final Supplier<SFXEngine> audio, final Bridge bridge) {
        return MediaAPI.createPlayer(mrl, graphics, audio, bridge);
    }

    public static long capture(final ByteBuffer encoded) throws IOException {
        final HeadlessGFXEngine graphics = MediaAPI.headlessEngine(false);
        try (final ImageReader reader = CodecsAPI.decodeImage(encoded)) {
            graphics.format(reader.pixelFormat(), reader.width(), reader.height());
            while (reader.hasNext()) {
                reader.next();
                final ByteBuffer[] planes = new ByteBuffer[reader.planeCount()];
                final int[] strides = new int[planes.length];
                for (int plane = 0; plane < planes.length; plane++) {
                    planes[plane] = reader.plane(plane);
                    strides[plane] = reader.planeStride(plane);
                }
                graphics.upload(planes, strides);
            }
            return graphics.uploadCount();
        } finally {
            graphics.release();
        }
    }
}
