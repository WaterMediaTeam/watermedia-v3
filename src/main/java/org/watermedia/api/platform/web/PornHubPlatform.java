package org.watermedia.api.platform.web;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class PornHubPlatform implements IPlatform {
    public static final String NAME = "PornHub";
    private static final Marker IT = MarkerManager.getMarker(PornHubPlatform.class.getSimpleName());
    private static final Pattern FLASHVARS_PATTERN = Pattern.compile("\\bvar\\s+flashvars_\\d+\\s*=\\s*(\\{[\\s\\S]*?\\});");
    private static final Gson GSON = new Gson();
    private static final String[] HOSTS = { "pornhub.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // VALIDATE THE HOST AND REQUIRE A viewkey QUERY PARAM (e.g. ?viewkey=6a159015e4470)
        if (!DataTool.endsWith(uri.getHost(), HOSTS)
                || !DataTool.contains(uri.getQuery(), "viewkey="))
            return null;

        if (!WaterMediaConfig.media.platforms.allowMatureContent)
            throw new MatureContentException(PornHubPlatform.class, "Mature content is disabled in config (this platform serves mature content exclusively)");

        final String viewkey = DataTool.parseQuery(uri.getQuery()).get("viewkey");

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(PornHubPlatform.class, "Page request for viewkey '" + viewkey + "' returned HTTP " + req.statusCode());

            final String html = req.readAllAsString();
            final Matcher matcher = FLASHVARS_PATTERN.matcher(html);
            if (!matcher.find())
                throw new PlatformException(PornHubPlatform.class, "flashvars block not found for viewkey '" + viewkey + "' (geo-blocked, removed, or markup changed)");

            final FlashVars flashVars = GSON.fromJson(matcher.group(1), FlashVars.class);

            if (flashVars == null || flashVars.mediaDefinitions == null || flashVars.mediaDefinitions.length == 0)
                throw new PlatformException(PornHubPlatform.class, "flashvars carry no media definitions for viewkey '" + viewkey + "'");

            final List<DataQuality> variants = new ArrayList<>(flashVars.mediaDefinitions.length);
            int skipped = 0;

            for (final MediaDefinition def: flashVars.mediaDefinitions) {
                if (def.videoUrl == null || def.videoUrl.isEmpty()) { skipped++; continue; }
                // A LINK POINTING BACK TO pornhub.com IS A QUALITY-SELECTOR (E.G. 4K) ENDPOINT, NOT A CDN FILE
                if (def.videoUrl.contains("pornhub.com")) {
                    LOGGER.debug(IT, "PornHub skipping non-CDN rendition {}p ({})", def.height, def.format);
                    skipped++;
                    continue;
                }
                variants.add(new DataQuality(new URI(def.videoUrl), def.width, def.height));
            }

            if (variants.isEmpty())
                throw new PlatformException(PornHubPlatform.class, "Viewkey '" + viewkey + "' exposed " + flashVars.mediaDefinitions.length
                        + " media definition(s) but none were playable CDN links (" + skipped + " skipped)");

            URI thumbnail = null;
            Metadata metadata = null;
            if (flashVars.video_title != null) {
                thumbnail = flashVars.image_url != null ? URI.create(flashVars.image_url) : null;
                metadata = new Metadata(flashVars.video_title, null, null, flashVars.video_duration * 1000L, null);
            } else {
                LOGGER.warn(IT, "PornHub viewkey '{}' has no video_title; emitting source without metadata", viewkey);
            }

            LOGGER.info(IT, "PornHub resolved viewkey '{}' with {} CDN variant(s) ({} skipped)", viewkey, variants.size(), skipped);
            final var entry = new DataSource(MediaType.VIDEO, thumbnail, metadata,
                    RequestHeaders.defaults(uri),
                    variants.toArray(DataQuality[]::new),
                    null, null);
            return new PlatformData(null, entry);
        }
    }

    private record FlashVars(
            String video_title,
            String image_url,
            int video_duration,
            MediaDefinition[] mediaDefinitions
    ) {}

    private record MediaDefinition(
            int width,
            int height,
            @SerializedName("defaultQuality") boolean isDefault,
            String format,
            String videoUrl
    ) {}
}
