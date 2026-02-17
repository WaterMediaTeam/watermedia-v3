package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.platform.*;
import org.watermedia.api.media.players.FFMediaPlayer;

import java.io.File;
import java.net.FileNameMap;
import java.net.URI;
import java.net.URLConnection;
import java.util.LinkedList;

import static org.watermedia.WaterMedia.LOGGER;

public class MediaAPI {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());
    static final LinkedList<IPlatform> PLATFORMS = new LinkedList<>() {
        @Override
        public void push(final IPlatform platform) {
            LOGGER.info(IT, "Registering {} platform support", platform.name());
            // ENSURE UNIQUE
            if (this.contains(platform)) throw new IllegalStateException("Platform already registered: " + platform.name());
            // ENSURE DEFAULT IS LAST
            if (platform.getClass() == DefaultPlatform.class) {
                this.addLast(platform);
                return;
            }
            // ENSURE DEFAULT IS ALWAYS LAST
            final IPlatform last = !this.isEmpty() && this.getLast().getClass() == DefaultPlatform.class ? this.getLast() : null;
            super.push(platform);
            if (last != null) super.push(last);
        }
    };

    /**
     * Gets or creates an MRL for the given URI.
     * If cached and not expired, returns immediately.
     * Otherwise, starts async loading via IPlatform.
     *
     * @param uri the media URI
     * @return the MRL instance (may still be loading)
     */
    public static MRL getMRL(final String uri) {
        final File f = new File(uri);
        return MRL.get(f.exists() ? f.getAbsoluteFile().toURI() : URI.create(uri));
    }

    public static void registerPlatform(IPlatform platform) {
        PLATFORMS.push(platform);
    }

    public static boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Media API refuses to load on server-side");
            return false;
        }

        final FileNameMap map = URLConnection.getFileNameMap();
        URLConnection.setFileNameMap(fileName -> {
            final String contentType = map.getContentTypeFor(fileName);
            if (contentType != null)
                return contentType;

            //Add custom types here
            if (fileName.endsWith(".pam"))
                return "image/x-portable-arbitrarymap";

            LOGGER.warn("Unknown file type for {}", fileName);
            return null;
        });

        // REGISTER PLATFORMS
        LOGGER.info(IT, "Registering supported platforms");
        registerPlatform(new YoutubePlatform());
        registerPlatform(new ImgurPlatform());
        registerPlatform(new KickPlatform());
        registerPlatform(new StreamablePlatform());
//        registerPlatform(new WaterPlatform());
        registerPlatform(new DefaultPlatform()); // default, always returns something

        // LOAD ENGINES
        LOGGER.info(IT, "Starting media engines");
        FFMediaPlayer.load(instance);

        return true;
    }
}