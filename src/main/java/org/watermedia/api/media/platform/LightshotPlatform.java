package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LightshotPlatform implements IPlatform {
    private static final Pattern IMG_PATTERN = Pattern.compile("<img[^>]*class=\"no-click screenshot-image\"[^>]*src=\"(https://[^\"]+)\"");

    @Override
    public String name() { return "Lightshot"; }

    @Override
    public boolean validate(final URI uri) {
        return "prnt.sc".equalsIgnoreCase(uri.getHost());
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

            final Matcher matcher = IMG_PATTERN.matcher(html);
            if (!matcher.find()) throw new IOException("No screenshot image found on page");

            return new Result(null, new MRL.Source(MRL.MediaType.IMAGE, new URI(matcher.group(1))));
        } finally {
            conn.disconnect();
        }
    }
}
