package org.watermedia.api.media.platform;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    public Result getSources(final URI uri) throws Exception {
        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET", "text/html");
        try {
            NetTool.validateHTTP200(conn.getResponseCode(), uri);

            final String html;
            try (final InputStream in = conn.getInputStream()) {
                html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            final Matcher matcher = FLASHVARS_PATTERN.matcher(html);
            if (!matcher.find()) throw new IllegalStateException("No flashvars found on page");

            final String json = matcher.group(1);

            final FlashVars flashVars = GSON.fromJson(json, FlashVars.class);

            if (flashVars.mediaDefinitions == null || flashVars.mediaDefinitions.length == 0)
                throw new IllegalStateException("No media definitions found");

            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.VIDEO);

            for (final MediaDefinition def: flashVars.mediaDefinitions) {
                if (def.videoUrl == null || def.videoUrl.isEmpty()) continue;
                if (def.videoUrl.contains("pornhub.com")) continue; // NOT A CDN LINK MEANS 4K VIDEO, CANNOT BE OBTAINED


                sourceBuilder.quality(MRL.Quality.of(def.width, def.height), new URI(def.videoUrl));
            }

            if (flashVars.video_title != null) {
                sourceBuilder.metadata(new MRL.Metadata(
                        flashVars.video_title,
                        null,
                        flashVars.image_url != null ? URI.create(flashVars.image_url) : null,
                        null,
                        flashVars.video_duration * 1000L,
                        null
                ));
            }

            return new Result(null, sourceBuilder.build());
        } finally {
            conn.disconnect();
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
