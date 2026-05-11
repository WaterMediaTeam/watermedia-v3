package org.watermedia.api.platform.web;

import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
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
    public PlatformData getData(final URI uri) throws Exception {
        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);
            final String html = req.readAllAsString();

            final Matcher matcher = VIDEO_SRC_PATTERN.matcher(html);
            if (!matcher.find()) {
                throw new IllegalStateException("No <video> element found in VidLii page: " + uri);
            }

            final String videoPath = matcher.group(1);
            final URI videoUri = new URI("https://www.vidlii.com" + videoPath);

            final var entry = new DataSource(MediaType.VIDEO, null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(videoUri, 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
