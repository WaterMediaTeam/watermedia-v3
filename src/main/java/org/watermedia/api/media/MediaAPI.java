package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.platform.*;
import org.watermedia.api.media.players.FFMediaPlayer;

import java.net.URI;
import java.util.LinkedList;

import static org.watermedia.WaterMedia.LOGGER;

public class MediaAPI {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());
    private static final LinkedList<IPlatform> PLATFORMS = new LinkedList<>() {
        @Override
        public void push(final IPlatform platform) {
            LOGGER.info(IT, "Registering {} platform support", platform.name());
            super.push(platform);
        }
    };

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
                    return new MRL.Source[0];
                }
            }
        }
        LOGGER.fatal(IT, "This line must not be reached", new IllegalStateException("Oh no!"));
        return null;
    }

    public static boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Media API refuses to load on server-side");
            return false;
        }

        // REGISTER PLATFORMS
        LOGGER.info(IT, "Registering supported platforms");
        PLATFORMS.push(new YoutubePlatform());
        PLATFORMS.push(new ImgurPlatform());
        PLATFORMS.push(new KickPlatform());
        PLATFORMS.push(new StreamablePlatform());
        PLATFORMS.push(new WaterPlatform());
        PLATFORMS.addLast(new DefaultPlatform()); // default, always returns something

        // LOAD ENGINES
        LOGGER.info(IT, "Starting media engines");
        FFMediaPlayer.load(instance);


        return true;
    }
}