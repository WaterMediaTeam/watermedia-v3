package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class DrivePlatform implements IPlatform {
    public static final String NAME = "Google Drive";
    private static final String DOWNLOAD_URL = "https://drive.usercontent.google.com/download?id=%s&export=download&authuser=0&acknowledgeAbuse=true";
    private static final String API_URL = "https://www.googleapis.com/drive/v3/files/%s?alt=media&acknowledgeAbuse=true&key=%s";
    private static final String LUCK = new String(Base64.getDecoder().decode(new String(Base64.getDecoder().decode("UVVsNllWTjVRVTVpVFhkWGQyWnhiSE5QWWtoVlEwODFjbUZ6TjBGcmNXaE5UWEphUVZWWg=="), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    private static final Pattern HTML_PATTERN = Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]+)\"", Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && host.equals("drive.google.com") && uri.getPath().startsWith("/file/d/");
    }

    @Override
    public Result getSources(final URI uri) throws Exception {
        final String fileId = extractFileId(uri.getPath());

        // TRY API APPROACH FIRST
        final URI apiUri = new URI(String.format(API_URL, fileId, LUCK));
        try {
            final HttpURLConnection conn = NetTool.connectToHTTP(apiUri, "GET", "*/*");
            try {
                final int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.of(conn.getContentType())).uri(apiUri);
                    return new Result(null, sourceBuilder.build());
                }
            } finally {
                conn.disconnect();
            }
        } catch (final Exception e) {
            LOGGER.debug(IT, "API approach failed for {}, trying download fallback", fileId, e);
        }

        // FALLBACK: DIRECT DOWNLOAD URL
        return getSourcesFromDownload(fileId, uri);
    }

    private Result getSourcesFromDownload(final String fileId, final URI original) throws Exception {
        String url = String.format(DOWNLOAD_URL, fileId);
        final HttpURLConnection conn = NetTool.connectToHTTP(URI.create(url), "GET", "*/*");

        try {
            NetTool.validateHTTP200(conn.getResponseCode(), original);

            if (conn.getContentType().startsWith("text/html")) {
                try (final InputStream in = conn.getInputStream()) {
                    final String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    final Matcher matcher = HTML_PATTERN.matcher(html);

                    final Map<String, String> form = new HashMap<>();
                    while (matcher.find()) {
                        form.put(matcher.group(1), matcher.group(2));
                    }

                    if (!form.containsKey("uuid"))
                        throw new IllegalStateException("Google Drive confirmation form not found for: " + original);

                    url += "&uuid=" + form.get("uuid") + "&at=" + form.get("at") + "&confirm=t";
                }
            }

            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.of(conn.getContentType())).uri(new URI(url));
            return new Result(null, sourceBuilder.build());
        } finally {
            conn.disconnect();
        }
    }

    private static String extractFileId(final String path) {
        final int start = path.indexOf("/file/d/") + 8;
        int end = path.indexOf('/', start);
        if (end == -1) end = path.length();
        return path.substring(start, end);
    }
}
