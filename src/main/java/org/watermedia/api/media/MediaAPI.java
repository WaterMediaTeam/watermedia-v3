package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.platform.DefaultPlatform;
import org.watermedia.api.media.platform.IPlatform;
import org.watermedia.api.media.platform.StreamablePlatform;
import org.watermedia.api.media.platform.WaterPlatform;
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

    public static MRL[] getSources(final URI uri) {
        LOGGER.info("Loading source for {}", uri);
        if (PLATFORMS.isEmpty()) {
            LOGGER.error("SOMEHOW PLATFORMS ARE EMPTY");
        }
        for (final IPlatform platform: PLATFORMS) {
            LOGGER.info("Checking {}", platform.name());
            if (platform.validate(uri)) {
                try {
                    LOGGER.info("Using source {}", platform.name());
                    final MRL[] source = platform.getSources(uri);
                    if (source == null) continue;
                    return source;
                } catch (final Throwable t) {
                    LOGGER.error("Platform {} threw an exception while validating URI: {}", platform.name(), uri, t);
                }
            }
        }
        throw new IllegalStateException("This exception should not be threw");
    }

    public static MediaPlayer getMediaPlayer(final URI uri, final Thread thread, final Executor renderThreadEx, final GLEngine glEngine, final ALEngine alEngine, final boolean video, final boolean audio) {
        WaterMedia.checkIsClientSideOrThrow(MediaAPI.class);

        try {
            final URLConnection conn = uri.toURL().openConnection();
            if (conn instanceof final HttpURLConnection http) {
                NetTool.validateHTTP200(http.getResponseCode(), uri);
            }
            final String[] type = conn.getContentType().split("/");
            if (type[0].equals("image")) {
                return new TxMediaPlayer(uri, thread, renderThreadEx, glEngine, video);
            } else {
                if (VLMediaPlayer.loaded()) {
                    LOGGER.debug(IT, "Creating LibVLC MediaPlayer for URI: {}", uri);
                    return new VLMediaPlayer(uri, thread, renderThreadEx, glEngine, alEngine, video, audio);
                } else if (FFMediaPlayer.loaded()) {
                    LOGGER.debug(IT, "Creating FFMPEG MediaPlayer for URI: {}", uri);
                    return new FFMediaPlayer(uri, thread, renderThreadEx, glEngine, alEngine, video, audio);
                } else {
                    LOGGER.error(IT, "Neither LibVLC nor FFMPEG are loaded, cannot create MediaPlayer for URI: {}", uri);
                }
            }
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to create MediaPlayer for URI: {}", uri, t);
        }
        return null;
    }

    @Override
    public boolean start(final WaterMedia instance) throws Exception {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Detected server-side environment, lockdown mode enabled");
            return false;
        }

        // REGISTER PLATFORMS
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