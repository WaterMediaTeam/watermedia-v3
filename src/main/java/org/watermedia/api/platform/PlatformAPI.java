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
     * @return raw platform data, or {@code null} if no registered platform handled the URI
     * @throws PlatformException whatever the matching platform throws while resolving
     */
    public static PlatformData fetch(final URI uri) throws PlatformException {
        for (int i = PLATFORMS.size() - 1; i >= 0; i--) {
            final IPlatform platform = PLATFORMS.get(i);
            try {
                final PlatformData data = platform.getData(uri);
                if (data != null) {
                    LOGGER.debug(IT, "Fetched data from {} for {}", platform.name(), uri);
                    return data;
                }
            } catch (final PlatformException e) { // ALREADY DISPLAYABLE — RETHROW UNTOUCHED
                throw e;
            } catch (final IOException e) { // NETWORK/IO FAILURE TALKING TO THE PLATFORM — DISPLAYABLE, KEEP ITS DETAIL
                throw new PlatformException(platform.getClass(), "I/O failure resolving " + uri + " (" + e.getMessage() + ")", e);
            } catch (final Throwable e) { // BUG IN THE HANDLER — SURFACE THE CAUSE INLINE, KEEP THE TRACE AS THE CAUSE
                throw new PlatformException(platform.getClass(), "Unexpected error resolving " + uri + " (" + e + ")", e);
            }
        }
        return null; // NOTHING FOUND
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
        for (final IPlatform platform: this.pendingPlatforms) {
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
