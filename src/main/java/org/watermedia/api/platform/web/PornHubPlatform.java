package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public final class PornHubPlatform implements Platform {
    public static final String NAME = "PornHub";
    private static final Marker IT = MarkerManager.getMarker(PornHubPlatform.class.getSimpleName());
    private static final Pattern FLASHVARS_PATTERN = Pattern.compile("\\bvar\\s+flashvars_\\d+\\s*=\\s*(\\{[\\s\\S]*?\\});");

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws Exception {
        // VALIDATE THE HOST WITH A DOT BOUNDARY (pornhub.com OR *.pornhub.com) SO LOOKALIKES LIKE
        // "notpornhub.com" ARE REJECTED, AND REQUIRE A viewkey QUERY PARAM (e.g. ?viewkey=6a159015e4470).
        // A NULL HOST (e.g. file:// URIs) IS NOT OURS — GUARD IT INSTEAD OF NPEing THE RESOLUTION CHAIN.
        final String raw = uri.getHost();
        final String host = raw == null ? null : raw.toLowerCase(Locale.ROOT);
        if (host == null || !(host.equals("pornhub.com") || host.endsWith(".pornhub.com"))
                || !DataTool.contains(uri.getQuery(), "viewkey="))
            return null;

        if (!WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(NAME, "Mature content is disabled in config (this platform serves mature content exclusively)");

        final String viewkey = DataTool.parseQuery(uri.getQuery()).get("viewkey");

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for viewkey '" + viewkey + "' returned HTTP " + req.statusCode());

            final String html = req.text();
            final Matcher matcher = FLASHVARS_PATTERN.matcher(html);
            if (!matcher.find())
                throw new PlatformException(NAME, "flashvars block not found for viewkey '" + viewkey + "' (geo-blocked, removed, or markup changed)");

            final FlashVars flashVars = JSONTool.parse(matcher.group(1), FlashVars.class);

            if (flashVars == null || flashVars.mediaDefinitions == null || flashVars.mediaDefinitions.length == 0)
                throw new PlatformException(NAME, "flashvars carry no media definitions for viewkey '" + viewkey + "'");

            final List<Quality> variants = new ArrayList<>(flashVars.mediaDefinitions.length);
            int skipped = 0;

            for (final MediaDefinition def: flashVars.mediaDefinitions) {
                if (def.videoUrl == null || def.videoUrl.isEmpty()) { skipped++; continue; }
                // A LINK POINTING BACK TO pornhub.com IS A QUALITY-SELECTOR (E.G. 4K) ENDPOINT, NOT A CDN FILE
                if (def.videoUrl.contains("pornhub.com")) {
                    LOGGER.debug(IT, "PornHub skipping non-CDN rendition {}p ({})", def.height, def.format);
                    skipped++;
                    continue;
                }
                variants.add(new Quality(new URI(def.videoUrl), def.width, def.height));
            }

            if (variants.isEmpty())
                throw new PlatformException(NAME, "Viewkey '" + viewkey + "' exposed " + flashVars.mediaDefinitions.length
                        + " media definition(s) but none were playable CDN links (" + skipped + " skipped)");

            URI thumbnail = null;
            Metadata metadata = null;
            if (flashVars.title != null) {
                thumbnail = flashVars.thumbnail != null ? URI.create(flashVars.thumbnail) : null;
                metadata = new Metadata(flashVars.title, null, null, flashVars.duration * 1_000L, null);
            } else {
                LOGGER.warn(IT, "PornHub viewkey '{}' has no video_title; emitting source without metadata", viewkey);
            }

            LOGGER.info(IT, "PornHub resolved viewkey '{}' with {} CDN variant(s) ({} skipped)", viewkey, variants.size(), skipped);
            final var entry = new Source(MediaType.VIDEO, thumbnail, metadata,
                    HttpHeaders.defaults(uri),
                    variants,
                    null, null);
            return new Resolution(null, entry);
        }
    }

    private record FlashVars(
            @SerializedName("video_title") String title,
            @SerializedName("image_url") String thumbnail,
            @SerializedName("video_duration") int duration,
            MediaDefinition[] mediaDefinitions
    ) {}

    private record MediaDefinition(
            int width,
            int height,
            boolean defaultQuality,
            String format,
            String videoUrl
    ) {}
}
