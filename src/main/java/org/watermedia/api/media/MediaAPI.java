package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.platform.*;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.media.players.VLMediaPlayer;
import org.watermedia.tools.NetTool;
import org.watermedia.videolan4j.VideoLan4J;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.util.LinkedList;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public class MediaAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());
    private static final LinkedList<IPlatform> PLATFORMS = new LinkedList<>();

    static MRL.Source[] getSources(final URI uri) {
        LOGGER.info("Fetching sources for {}", uri);

        for (final IPlatform platform: PLATFORMS) {
            LOGGER.debug("Checking {}", platform.name());
            if (platform.validate(uri)) {
                try {
                    LOGGER.debug("Using source {}", platform.name());
                    return platform.getSources(uri);
                } catch (final Throwable t) {
                    LOGGER.error("Failed to open source {} for the {}", uri, platform.name(), t);
                }
            }
        }
        LOGGER.fatal(IT, "This line must not be reached", new IllegalStateException("Oh no!"));
        return null;
    }

    @Override
    public boolean start(final WaterMedia instance) throws Exception {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Detected server-side environment, lockdown mode enabled");
            return false;
        }

        // REGISTER PLATFORMS
        PLATFORMS.push(new KickPlatform());
        PLATFORMS.push(new StreamablePlatform());
        PLATFORMS.push(new WaterPlatform());
        PLATFORMS.addLast(new DefaultPlatform()); // default, always returns something

        // LOAD PLATFORMS
        final boolean libVlcLoaded = VLMediaPlayer.load(instance);
        final boolean ffmpegLoaded = FFMediaPlayer.load(instance);

        // CHECK IF NEITHER OF THEM ARE LOADED
        if (!libVlcLoaded && !ffmpegLoaded) {
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
        if (VideoLan4J.isDiscovered()) {
            // TODO: add a "release default instance"
            VideoLan4J.releaseInstance(VideoLan4J.getDefaultInstance());
        }
    }
}