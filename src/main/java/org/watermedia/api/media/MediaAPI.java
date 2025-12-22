package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.platform.*;
import org.watermedia.api.media.players.FFMediaPlayer;

import java.net.URI;
import java.util.LinkedList;

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
        final boolean ffmpegLoaded = FFMediaPlayer.load(instance);

        // CHECK IF NEITHER OF THEM ARE LOADED
        if (!ffmpegLoaded) {
            LOGGER.fatal(IT, "FFMPEG are unable to be started in your environment, please report this issue to the WaterMedia's authors");
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
        // TODO: test must run a FFPROBE test
    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void release(final WaterMedia instance) {
    }
}