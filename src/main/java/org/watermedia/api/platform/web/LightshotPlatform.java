package org.watermedia.api.platform.web;

import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
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
    public PlatformData getData(final URI uri) throws Exception {
        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);

            final String html = req.readAllAsString();
            final Matcher matcher = IMG_PATTERN.matcher(html);
            if (!matcher.find()) throw new IOException("No screenshot image found on page");

            final var entry = new DataSource(MediaType.IMAGE, null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(new URI(matcher.group(1)), 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
