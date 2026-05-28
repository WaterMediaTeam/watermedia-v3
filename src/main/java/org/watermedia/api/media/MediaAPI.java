package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.TxMediaPlayer;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.api.util.MediaType;

import java.io.File;
import java.net.URI;
import java.util.function.Supplier;

import static org.watermedia.WaterMedia.LOGGER;

public class MediaAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(MediaAPI.class.getSimpleName());

    /**
     * Gets or creates an MRL for the given URI.
     * If cached and not expired, returns immediately.
     * Otherwise, starts async loading via the platform API.
     *
     * @param uri the media URI
     * @return the MRL instance (may still be loading)
     */
    public static MRL getMRL(final String uri) {
        final File f = new File(uri);
        return MRL.getMRL(f.exists() ? f.getAbsoluteFile().toURI() : URI.create(uri));
    }

    public static MRL getMRL(final URI uri) {
        return MRL.getMRL(uri);
    }

    public static MediaPlayer createPlayer(final MRL mrl, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        return createPlayer(mrl, 0, gfx, sfx);
    }

    public static MediaPlayer createPlayer(final MRL mrl, final int sourceIndex, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        final MRL.Source source = mrl.source(sourceIndex);
        if (source == null) {
            LOGGER.warn(IT, "Cannot create player: uri {} not available for {}", sourceIndex, mrl.uri);
            return null;
        }

        try {
            if (source.type() == MediaType.UNKNOWN) {
                LOGGER.warn(IT, "Trying to create a media player for the unknow media type url {}", source);
            }

            if (source.type() == MediaType.IMAGE) {
                LOGGER.debug(IT, "Creating TxMediaPlayer for image: {}", source);
                return new TxMediaPlayer(mrl, sourceIndex, gfx.get());
            }

            if (FFMediaPlayer.loaded()) {
                LOGGER.debug(IT, "Creating FFMediaPlayer for: {}", source);
                return new FFMediaPlayer(mrl, sourceIndex, gfx.get(), sfx.get());
            }

            LOGGER.error(IT, "No media backend available for: {}", mrl.uri);
        } catch (final Throwable t) {
            LOGGER.error(IT, "Failed to create player for: {}", mrl.uri, t);
        }
        return null;
    }

    @Override
    public String name() {
        return MediaAPI.class.getSimpleName();
    }

    @Override
    public void load(final WaterMedia instance) {
        this.steps = instance.clientSide ? 2 : 0; // CACHE + FFMPEG
        this.step = 0;
        this.stepName = "";
    }

    @Override
    public boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Media API refuses to load on server-side");
            return false;
        }

        this.step++;
        this.stepName = "CACHE";
        LOGGER.info(IT, "Starting media network cache");
        try {
            NetworkCache.start(instance.tmp.resolve("cache"));
        } catch (final Exception e) {
            LOGGER.warn(IT, "Failed to initialize media network cache", e);
        }

        this.step++;
        this.stepName = "FFMPEG";
        LOGGER.info(IT, "Starting media engines");
        if (!FFMediaPlayer.load(instance)) {
            LOGGER.error(IT, "Failed to load FFMediaPlayer engine");
        }

        return true;
    }

    @Override
    public void release(final WaterMedia instance) {
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
        NetworkCache.release();
    }
}
