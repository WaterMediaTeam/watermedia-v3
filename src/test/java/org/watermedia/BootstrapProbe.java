package org.watermedia;

import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.WaterMedia.Status;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.ServerMediaPlayer;
import org.watermedia.api.media.TLSLifecycleProbe;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises one WaterMedia load per process: the load is final, so every scenario gets its own JVM. */
public final class BootstrapProbe {
    public static void main(final String[] arguments) throws Exception {
        final String scenario = arguments[0];
        final Path root = Path.of("").toAbsolutePath();
        WaterConfig.setPath(root.resolve("config"));
        WaterMediaConfig.network.server = WaterMediaConfig.Network.Server.OFF;
        WaterMediaConfig.network.timeout = 1000;
        WaterMediaConfig.media.ffmpeg.disable = !scenario.equals("native") && !scenario.equals("native-failure") && !scenario.startsWith("tls");
        switch (scenario) {
            case "tls" -> TLSLifecycleProbe.run(root, false);
            case "tls-empty" -> TLSLifecycleProbe.run(root, true);
            case "native-failure" -> nativeFailure(root);
            case "optional-failure" -> optionalFailure(root);
            case "server" -> server(root);
            case "native" -> nativeLoad(root);
            case "client" -> client(root);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
        System.out.println("PROBE_OK " + scenario);
    }

    // AN INSTALLED BINARIES MODULE THAT CANNOT PROVISION ITS NATIVES ENDS THE LOAD BEFORE THE MODULES BEHIND IT
    private static void nativeFailure(final Path root) throws Exception {
        Files.createDirectories(root.resolve("temp"));
        Files.writeString(root.resolve("temp/ffmpeg"), "untrusted native base");
        try {
            WaterMedia.start("PROBE", root.resolve("temp"), root, true);
            throw new AssertionError("Startup accepted a failed installed binaries module");
        } catch (final IllegalStateException expected) {
            final Status status = WaterMedia.status();
            if (WaterMedia.loaded() || status.state() != Status.State.FAILED || status.loading() != WaterMediaBinaries.class)
                throw new AssertionError(status);
            if (status.failures().size() != 1 || status.failures().get(0).module() != WaterMediaBinaries.class
                    || status.failures().get(0).cause() != expected.getCause())
                throw new AssertionError("The essential failure was not listed with its cause: " + status);
            if (status.done() != 1 || status.total() != 4) throw new AssertionError("Progress did not stop at the failed module: " + status);
        }
        if (WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID) != null) throw new AssertionError("Failed installation published a native path");
        try {
            MediaAPI.mrl("https://example.invalid/clip.mp4");
            throw new AssertionError("A failed load resolved media");
        } catch (final IllegalStateException expected) {}
        requireFinal(root);
    }

    // A MODULE THE LIBRARY CAN LIVE WITHOUT LISTS ITS FAILURE AND THE LOAD STILL COMPLETES
    private static void optionalFailure(final Path root) throws Exception {
        // THE FILE SERVER BINDS THE LOOPBACK ADDRESS; A WILDCARD SOCKET WOULD NOT CONFLICT WITH IT ON EVERY OS
        try (final ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            WaterMediaConfig.network.server = WaterMediaConfig.Network.Server.ALWAYS;
            WaterMediaConfig.network.serverPort = occupied.getLocalPort();
            WaterMedia.start("PROBE", root.resolve("temp"), root, true);
        }
        final Status status = WaterMedia.status();
        if (!WaterMedia.loaded() || status.failures().size() != 1 || status.failures().get(0).module() != NetworkAPI.Module.class)
            throw new AssertionError("An occupied port did not surface as an optional failure: " + status);
        if (status.done() != status.total() || status.loading() != null) throw new AssertionError(status);
        // THE REST OF THE MODULE WORKS: AN UPLOAD IS ACCEPTED WITHOUT AN ILLEGAL STATE AND FAILS ON ITS OWN
        // (ServiceLifecycleProbe CHECKS THE MESSAGE A MISCONFIGURED REMOTE FAILS IT WITH)
        WaterMediaConfig.network.remoteUrl = "remote.invalid/upload";
        final NetworkAPI.UploadStatus upload = NetworkAPI.upload(Files.writeString(root.resolve("upload.bin"), "payload").toFile());
        if (!PlayerWait.awaitCondition(upload::failed, 5_000) || upload.completed()) throw new AssertionError("The misconfigured upload did not fail");
        requireFinal(root);
    }

    // A DEDICATED SERVER LOADS ONLY THE SHARED MODULES AND NEVER TOUCHES THE CLIENT NATIVES
    private static void server(final Path root) {
        WaterMedia.start("PROBE", root.resolve("temp"), root, false);
        final Status status = WaterMedia.status();
        if (!WaterMedia.loaded() || status.total() != 2 || status.done() != 2 || !status.failures().isEmpty()) throw new AssertionError(status);
        if (WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID) != null || WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID) != null)
            throw new AssertionError("Server initialized native paths");
        if (Files.exists(root.resolve("temp/ffmpeg"))) throw new AssertionError("Server extracted client natives");
        try {
            MediaAPI.mrl("https://example.invalid/clip.mp4");
            throw new AssertionError("A server resolved media");
        } catch (final IllegalStateException expected) {}
        final ServerMediaPlayer player = MediaAPI.serverPlayer();
        player.release();
        requireFinal(root);
    }

    private static void nativeLoad(final Path root) {
        WaterMedia.start("PROBE", root.resolve("temp"), root, true);
        if (!WaterMedia.loaded() || !WaterMedia.status().failures().isEmpty()) throw new AssertionError(WaterMedia.status());
        if (!MediaAPI.ffmpegLoaded()) throw new AssertionError("Required FFmpeg did not load");
        final Path installed = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID);
        if (installed == null || !Files.isRegularFile(installed.resolve("version.cfg")))
            throw new AssertionError("Native module did not publish its verified installation");
        if (!root.resolve("temp/yt-dlp").equals(WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID)))
            throw new AssertionError("Executable paths were not published");
        requireFinal(root);
    }

    // DISABLED FFMPEG PUBLISHES NO NATIVE STATE, AND A CANCELLED WAIT NEVER CANCELS THE LOAD BEHIND IT
    private static void client(final Path root) throws Exception {
        WaterMedia.start("PROBE", root.resolve("temp"), root, true);
        if (!WaterMedia.loaded() || WaterMedia.status().total() != 4 || !WaterMedia.status().failures().isEmpty())
            throw new AssertionError(WaterMedia.status());
        if (MediaAPI.ffmpegLoaded() || MediaAPI.ffmpegError() || MediaAPI.vulkanDecode()) throw new AssertionError("Disabled FFmpeg published native state");
        if (Files.exists(root.resolve("temp/ffmpeg"))) throw new AssertionError("Disabled FFmpeg was extracted");
        if (!root.resolve("temp/yt-dlp").equals(WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID)))
            throw new AssertionError("Executable paths were not published");
        // THE ORIGIN ANSWERS ONLY AFTER THE INTERRUPTED WAIT GAVE UP, SO THE LOAD MUST OUTLIVE THAT WAIT AND STILL SUCCEED
        WaterMediaConfig.network.timeout = 5_000;
        try (final Origin origin = Origin.of(new byte[] { 1, 2, 3 }, "image/png")) {
            origin.hold();
            final URI slow = origin.uri(".png");
            final MRL previous = MediaAPI.mrl(slow);
            if (!origin.awaitEntered(5_000L)) throw new AssertionError("Request never started");
            Thread.currentThread().interrupt();
            try {
                if (previous.await(10_000) || !Thread.currentThread().isInterrupted())
                    throw new AssertionError("MRL wait ignored cancellation");
            } finally {
                Thread.interrupted();
            }
            if (MediaAPI.mrl(slow) != previous) throw new AssertionError("The cached MRL was replaced");
            origin.release();
            if (!previous.await(10_000) || previous.status() != MRL.Status.LOADED) throw new AssertionError("The interrupted wait cancelled the load: " + previous.status());
        }
        requireFinal(root);
    }

    // THE LOAD HAPPENS ONCE PER PROCESS, WHATEVER STATE IT LEFT
    private static void requireFinal(final Path root) {
        try {
            WaterMedia.start("PROBE", root.resolve("temp"), root, true);
            throw new AssertionError("WaterMedia accepted a second start");
        } catch (final IllegalStateException expected) {}
    }
}
