package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VidLiiPlatform implements IPlatform {
    public static final String NAME = "VidLii";
    private static final Pattern VIDEO_SRC_PATTERN = Pattern.compile("<video\\s+[^>]*src=\"([^\"]+)\"");

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.equals("vidlii.com") || host.equals("www.vidlii.com"));
    }

    @Override
    public Result getSources(final URI uri) throws Exception {
        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET", "text/html");
        try {
            NetTool.validateHTTP200(conn.getResponseCode(), uri);
            final String html = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            final Matcher matcher = VIDEO_SRC_PATTERN.matcher(html);
            if (!matcher.find()) {
                throw new IllegalStateException("No <video> element found in VidLii page: " + uri);
            }

            final String videoPath = matcher.group(1);
            final URI videoUri = new URI("https://www.vidlii.com" + videoPath);

            return new Result(null, new MRL.Source(MRL.MediaType.VIDEO, videoUri));
        } finally {
            conn.disconnect();
        }
    }
}
