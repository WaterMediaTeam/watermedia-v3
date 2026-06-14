package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class VidLiiPlatform implements IPlatform {
    public static final String NAME = "VidLii";
    private static final Marker IT = MarkerManager.getMarker(VidLiiPlatform.class.getSimpleName());
    private static final String ORIGIN = "https://www.vidlii.com";
    private static final Pattern VIDEO_SRC_PATTERN = Pattern.compile("<video\\s+[^>]*src=\"([^\"]+)\"");
    private static final String[] HOSTS = { "vidlii.com", "www.vidlii.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS)) return null;

        try (final NetRequest req = NetRequest.create(uri).method("GET").accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(VidLiiPlatform.class, "Page request for " + uri + " returned HTTP " + req.statusCode());
            final String html = req.readAllAsString();

            final Matcher matcher = VIDEO_SRC_PATTERN.matcher(html);
            if (!matcher.find())
                throw new PlatformException(VidLiiPlatform.class, "<video> source not found in page (removed, private, or markup changed): " + uri);

            // VIDLII EMITS A ROOT-RELATIVE PATH, BUT TOLERATE AN ABSOLUTE URL IN CASE THE MARKUP CHANGES
            final String videoPath = matcher.group(1);
            final URI videoUri = videoPath.startsWith("http") ? new URI(videoPath) : new URI(ORIGIN + videoPath);
            LOGGER.debug(IT, "VidLii resolved video source {} for {}", videoUri, uri);

            final var entry = new DataSource(MediaType.VIDEO, null, null,
                    RequestHeaders.defaults(uri),
                    new DataQuality[] {new DataQuality(videoUri, 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }
}
