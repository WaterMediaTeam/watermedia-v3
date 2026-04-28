package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.net.HttpURLConnection;
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
    public Result getSources(final URI uri) throws Exception {
        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET", "text/html");

        try {
            NetTool.validateHTTP200(conn.getResponseCode(), uri);

            final String html = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.UNKNOWN).uri(downloadUri);
            return new Result(null, sourceBuilder.build());
        } finally {
            conn.disconnect();
        }
    }
}
