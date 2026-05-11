package org.watermedia.api.platform.web;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PornHubPlatform implements IPlatform {
    private static final Pattern FLASHVARS_PATTERN = Pattern.compile("\\bvar\\s+flashvars_\\d+\\s*=\\s*(\\{[\\s\\S]*?\\});");
    private static final Gson GSON = new Gson();

    @Override
    public String name() { return "PornHub"; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        final String path = uri.getPath();
        return host != null && host.endsWith("pornhub.com") && path != null && path.startsWith("/view_video.php");
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);

            final String html = req.readAllAsString();
            final Matcher matcher = FLASHVARS_PATTERN.matcher(html);
            if (!matcher.find()) throw new IllegalStateException("No flashvars found on page");

            final FlashVars flashVars = GSON.fromJson(matcher.group(1), FlashVars.class);

            if (flashVars.mediaDefinitions == null || flashVars.mediaDefinitions.length == 0)
                throw new IllegalStateException("No media definitions found");

            final List<DataQuality> variants = new ArrayList<>();

            for (final MediaDefinition def: flashVars.mediaDefinitions) {
                if (def.videoUrl == null || def.videoUrl.isEmpty()) continue;
                if (def.videoUrl.contains("pornhub.com")) continue; // NOT A CDN LINK MEANS 4K VIDEO, CANNOT BE OBTAINED

                variants.add(new DataQuality(new URI(def.videoUrl), def.width, def.height));
            }

            URI thumbnail = null;
            Metadata metadata = null;
            if (flashVars.video_title != null) {
                thumbnail = flashVars.image_url != null ? URI.create(flashVars.image_url) : null;
                metadata = new Metadata(flashVars.video_title, null, null, flashVars.video_duration * 1000L, null);
            }

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
