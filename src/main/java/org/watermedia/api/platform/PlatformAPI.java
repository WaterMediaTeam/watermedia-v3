package org.watermedia.api.platform;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.media.MRL;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.platform.web.*;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Platform API. Owns the {@link IPlatform} registry and exposes a way to
 * fetch a URI's raw media data — direct links, dimensions, metadata — without
 * going through {@link MRL}.
 * <p>
 * Platforms return {@link PlatformData} (their own structure); {@link MRL} and other
 * consumers build their domain types (e.g. {@code MRL.Source}) from that data.
 */
public class PlatformAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(PlatformAPI.class.getSimpleName());
    // CopyOnWriteArrayList: registration is rare, iteration (from MRL loader threads)
    // is hot and must not throw ConcurrentModificationException.
    static final CopyOnWriteArrayList<IPlatform> PLATFORMS = new CopyOnWriteArrayList<>();

    /**
     * Finds the first registered {@link IPlatform} that handles the given URI
     * and returns its raw {@link PlatformData}. Use this when you only need the direct
     * links plus width/height/metadata, and don't want the {@link MRL} cache,
     * the async {@code Source} model, or the player lifecycle.
     * <p>
     * Iteration order is reverse-registration: the most recently {@link #register(IPlatform)
     * registered} platform is checked first, so app-registered overrides win over the
     * built-in handlers shipped by WaterMedia.
     *
     * @param uri the media URI
     * @return raw platform data, or {@code null} if no registered platform validated the URI
     * @throws IOException whatever the matching platform throws while resolving
     */
    public static PlatformData fetch(final URI uri) throws IOException {
        // Reverse iteration: last registered wins (lets apps override built-ins).
        for (int i = PLATFORMS.size() - 1; i >= 0; i--) {
            final IPlatform platform = PLATFORMS.get(i);
            if (platform.validate(uri)) {
                LOGGER.debug(IT, "Fetching data from {} for {}", platform.name(), uri);
                try {
                    return platform.getData(uri);
                } catch (final IOException e) {
                    throw e;
                } catch (final Exception e) {
                    throw new IOException("Platform " + platform.name() + " failed to resolve " + uri, e);
                }
            }
        }
        return null;
    }

    /**
     * Registers a new platform handler. The newly registered handler is checked
     * <i>before</i> any previously registered handler in {@link #fetch(URI)} —
     * apps can override built-in handlers by registering a more specific one.
     */
    public static void register(final IPlatform platform) {
        LOGGER.info(IT, "• Registered {} platform", platform.name());
        PLATFORMS.add(platform);
    }

    private List<IPlatform> pendingPlatforms;

    @Override
    public String name() {
        return PlatformAPI.class.getSimpleName();
    }

    @Override
    public void load(final WaterMedia instance) {
        this.pendingPlatforms = new ArrayList<>();
        if (instance.clientSide) {
            this.pendingPlatforms.add(new WaterPlatform());
            this.pendingPlatforms.add(new ImgurPlatform());
            this.pendingPlatforms.add(new KickPlatform());
            this.pendingPlatforms.add(new StreamablePlatform());
            this.pendingPlatforms.add(new PornHubPlatform());
            this.pendingPlatforms.add(new LightshotPlatform());
            this.pendingPlatforms.add(new TwitchPlatform());
            this.pendingPlatforms.add(new TwitterPlatform());
            this.pendingPlatforms.add(new BlueskyPlatform());
            this.pendingPlatforms.add(new BiliBiliPlatform());
            this.pendingPlatforms.add(new DrivePlatform());
            this.pendingPlatforms.add(new DropboxPlatform());
            this.pendingPlatforms.add(new MediaFirePlatform());
            this.pendingPlatforms.add(new SendvidPlatform());
            this.pendingPlatforms.add(new OdyseePlatform());
            this.pendingPlatforms.add(new VidLiiPlatform());
            this.pendingPlatforms.add(new TikTokPlatform());
            this.pendingPlatforms.add(new DTubePlatform());
        }
        this.steps = this.pendingPlatforms.size();
        this.step = 0;
        this.stepName = "";
    }

    @Override
    public boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Platform API refuses to load on server-side");
            return false;
        }

        LOGGER.info(IT, "Registering supported platforms");
        for (final IPlatform platform : this.pendingPlatforms) {
            this.step++;
            this.stepName = platform.getClass().getSimpleName();
            register(platform);
            ThreadTool.sleep(50);
        }
        this.pendingPlatforms = null;
        return true;
    }

    @Override
    public void release(final WaterMedia instance) {
        PLATFORMS.clear();
        this.pendingPlatforms = null;
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
    }
}
