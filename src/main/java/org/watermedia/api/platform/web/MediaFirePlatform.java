package org.watermedia.api.platform.web;

import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaFirePlatform implements IPlatform {
    public static final String NAME = "MediaFire";
    private static final Pattern PATTERN_POPSOK = Pattern.compile("<a\\s+class=\"input\\s+popsok\"\\s+aria-label=\"Download\\s+file\"\\s+href=\"([^\"]+)\"[^>]*>");
    private static final Pattern PATTERN_DATA_SCRAMBLED = Pattern.compile("data-scrambled-url=\"([^\"]+)\"");

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        final String path = uri.getPath();
        return host != null && path != null && host.equals("www.mediafire.com") && path.startsWith("/file/");
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);

            final String html = req.readAllAsString();
            final Matcher popsokMatcher = PATTERN_POPSOK.matcher(html);
            final Matcher dataScrambledMatcher = PATTERN_DATA_SCRAMBLED.matcher(html);

            final URI downloadUri;
            if (popsokMatcher.find()) {
                downloadUri = new URI(popsokMatcher.group(1));
            } else if (dataScrambledMatcher.find()) {
                final byte[] decoded = Base64.getDecoder().decode(dataScrambledMatcher.group(1));
                downloadUri = new URI(new String(decoded, StandardCharsets.UTF_8));
            } else {
                throw new IllegalStateException("No download link found in MediaFire page: " + uri);
            }

            final var entry = new DataSource(MediaType.UNKNOWN, null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(downloadUri, 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
