package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class MediaFirePlatform implements IPlatform {
    public static final String NAME = "MediaFire";
    private static final Marker IT = MarkerManager.getMarker(MediaFirePlatform.class.getSimpleName());
    private static final Pattern PATTERN_POPSOK = Pattern.compile("<a\\s+class=\"input\\s+popsok\"\\s+aria-label=\"Download\\s+file\"\\s+href=\"([^\"]+)\"[^>]*>");
    private static final Pattern PATTERN_DATA_SCRAMBLED = Pattern.compile("data-scrambled-url=\"([^\"]+)\"");
    private static final String[] HOSTS = { "www.mediafire.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final String path = uri.getPath();
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS) || path == null || !path.startsWith("/file/"))
            return null;

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(MediaFirePlatform.class, "Page request for " + uri + " returned HTTP " + req.statusCode());

            final String html = req.readAllAsString();
            final Matcher popsokMatcher = PATTERN_POPSOK.matcher(html);
            final Matcher dataScrambledMatcher = PATTERN_DATA_SCRAMBLED.matcher(html);

            // MEDIAFIRE EXPOSES THE DIRECT LINK EITHER AS A PLAIN "popsok" ANCHOR OR, WHEN OBFUSCATED,
            // AS A BASE64 "data-scrambled-url" ATTRIBUTE — TRY THE PLAIN ONE FIRST
            final URI downloadUri;
            if (popsokMatcher.find()) {
                downloadUri = new URI(popsokMatcher.group(1));
                LOGGER.debug(IT, "MediaFire resolved direct link via popsok anchor: {}", downloadUri);
            } else if (dataScrambledMatcher.find()) {
                final byte[] decoded = Base64.getDecoder().decode(dataScrambledMatcher.group(1));
                downloadUri = new URI(new String(decoded, StandardCharsets.UTF_8));
                LOGGER.debug(IT, "MediaFire resolved direct link via scrambled attribute: {}", downloadUri);
            } else {
                throw new PlatformException(MediaFirePlatform.class, "Download link not found in page (private, removed, or markup changed): " + uri);
            }

            final var entry = new DataSource(MediaType.UNKNOWN, null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(downloadUri, 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
