package org.watermedia;

import org.watermedia.WaterMedia.BootStatus;
import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BootstrapProbe {
    public static void main(final String[] arguments) throws Exception {
        final String scenario = arguments[0];
        final Path root = Path.of("").toAbsolutePath();
        WaterConfig.setPath(root.resolve("config"));
        WaterMediaConfig.network.enableServer = false;
        WaterMediaConfig.network.forceEnableServer = false;
        WaterMediaConfig.network.timeout = 1000;
        WaterMediaConfig.media.ffmpeg.disable = !scenario.equals("native") && !scenario.equals("native-failure") && !scenario.equals("tls");
        if (scenario.equals("tls")) {
            TLSLifecycleProbe.run(root);
            System.out.println("PROBE_OK " + scenario);
            return;
        }
        final boolean client = !scenario.equals("server");
        if (scenario.equals("native-failure")) {
            Files.createDirectories(root.resolve("first"));
            Files.writeString(root.resolve("first/ffmpeg"), "untrusted native base");
            try {
                WaterMedia.start("PROBE", root.resolve("first"), root, true);
                throw new AssertionError("Startup accepted a failed installed binaries module");
            } catch (final IllegalStateException expected) {
                if (WaterMedia.started() || WaterMedia.status().state() != BootStatus.State.FAILED)
                    throw new AssertionError(WaterMedia.status());
                for (final BootStatus.Module module: WaterMedia.status().modules()) {
                    if (module.id() == BootStatus.Id.NETWORK || module.id() == BootStatus.Id.PLATFORMS || module.id() == BootStatus.Id.MEDIA) {
                        if (module.outcome() != BootStatus.Outcome.BLOCKED) throw new AssertionError(module);
                    }
                }
                if (WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID) != null)
                    throw new AssertionError("Failed installation published a native path");
            } finally {
                WaterMedia.stop();
            }
            System.out.println("PROBE_OK " + scenario);
            return;
        }
        WaterMedia.start("PROBE", root.resolve("first"), root, client);
        if (!WaterMedia.started() || WaterMedia.status().state() != BootStatus.State.READY)
            throw new AssertionError(WaterMedia.status());

        if (!client) {
            if (WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID) != null)
                throw new AssertionError("Server initialized native paths");
            for (final BootStatus.Module module: WaterMedia.status().modules()) {
                if (module.id() == BootStatus.Id.MEDIA || module.id() == BootStatus.Id.PLATFORMS || module.id() == BootStatus.Id.BINARIES) {
                    if (module.outcome() != BootStatus.Outcome.SKIPPED) throw new AssertionError(module);
                }
            }
            if (Files.exists(root.resolve("first/ffmpeg"))) throw new AssertionError("Server extracted client natives");
            final ServerMediaPlayer player = new ServerMediaPlayer();
            try {
                WaterMedia.stop();
                throw new AssertionError("Shutdown accepted an open player");
            } catch (final IllegalStateException expected) {
                player.release();
            }
        } else if (scenario.equals("native")) {
            if (!MediaAPI.ffmpegLoaded()) throw new AssertionError("Required FFmpeg did not load");
            final Path installed = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID);
            if (installed == null || !Files.isRegularFile(installed.resolve("version.cfg")))
                throw new AssertionError("Native module did not publish its verified installation");
            WaterMedia.stop();
            if (WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID) != null)
                throw new AssertionError("Native paths survived their owning session");
            WaterMediaConfig.media.ffmpeg.disable = true;
            WaterMedia.start("PROBE", root.resolve("second"), root, true);
            if (MediaAPI.ffmpegLoaded() || MediaAPI.ffmpegError() || MediaAPI.vulkanDecode())
                throw new AssertionError("Stale native state after disabled restart");
            if (Files.exists(root.resolve("second/ffmpeg"))) throw new AssertionError("Disabled FFmpeg was extracted");
            if (!root.resolve("second/yt-dlp").equals(WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID)))
                throw new AssertionError("Executable paths did not follow the new session");
        } else {
            final CountDownLatch entered = new CountDownLatch(1);
            try (final LocalHttp origin = LocalHttp.start("/slow.png", exchange -> {
                entered.countDown();
                try { Thread.sleep(1500); } catch (final InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                try { LocalHttp.respond(exchange, "image/png", new byte[] {1, 2, 3}, 0); } catch (final IOException ignored) { exchange.close(); }
            })) {
                final MRL previous = MediaAPI.mrl(origin.uri("/slow.png"));
                if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Request never started");
                Thread.currentThread().interrupt();
                try {
                    if (previous.await(10_000) || !Thread.currentThread().isInterrupted())
                        throw new AssertionError("MRL wait ignored cancellation");
                } finally {
                    Thread.interrupted();
                }
                WaterMedia.stop();
                if (previous.status() != MRL.Status.FORGOTTEN) throw new AssertionError("Old MRL was not invalidated");
                WaterMedia.start("PROBE", root.resolve("second"), root, true);
                if (MediaAPI.mrl(origin.uri("/slow.png")) == previous) throw new AssertionError("Old MRL was reused");
                if (previous.status() != MRL.Status.FORGOTTEN || !previous.sources().isEmpty())
                    throw new AssertionError("Old request published after shutdown");
            }
        }
        WaterMedia.stop();
        if (WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID) != null)
            throw new AssertionError("Executable paths survived shutdown");
        if (WaterMedia.status().state() != BootStatus.State.STOPPED) throw new AssertionError(WaterMedia.status());
        System.out.println("PROBE_OK " + scenario);
    }
}
