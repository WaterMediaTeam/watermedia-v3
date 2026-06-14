package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

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
    private static final Marker IT = MarkerManager.getMarker(DrivePlatform.class.getSimpleName());
    private static final String DOWNLOAD_URL = "https://drive.usercontent.google.com/download?id=%s&export=download&authuser=0&acknowledgeAbuse=true";
    private static final String API_URL = "https://www.googleapis.com/drive/v3/files/%s?alt=media&acknowledgeAbuse=true&key=%s";
    private static final String LUCK = new String(Base64.getDecoder().decode(new String(Base64.getDecoder().decode("UVVsNllWTjVRVTVpVFhkWGQyWnhiSE5QWWtoVlEwODFjbUZ6TjBGcmNXaE5UWEphUVZWWg=="), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    private static final Pattern HTML_PATTERN = Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]+)\"", Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final String[] HOSTS = { "drive.google.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS) || uri.getPath() == null || !uri.getPath().startsWith("/file/d/"))
            return null;

        final String fileId = extractFileId(uri.getPath());
        LOGGER.debug(IT, "Google Drive resolving fileId '{}' from {}", fileId, uri);

        // TRY API APPROACH FIRST
        final URI apiUri = new URI(String.format(API_URL, fileId, LUCK));
        try (final NetRequest req = NetRequest.create(apiUri).method("GET").accept("*/*").send()) {
            if (req.statusCode() == HttpURLConnection.HTTP_OK) {
                final MediaType type = MediaType.of(req.contentType());
                LOGGER.debug(IT, "Google Drive resolved fileId '{}' via API (content-type {})", fileId, req.contentType());
                final var entry = new DataSource(type, null, null,
                        RequestHeaders.defaults(uri),
                        new DataQuality[] {new DataQuality(apiUri, 0, 0)},
                        null, null);
                return new PlatformData(null, entry);
            }
            LOGGER.debug(IT, "Google Drive API returned HTTP {} for fileId '{}', trying download fallback", req.statusCode(), fileId);
        } catch (final Exception e) {
            LOGGER.debug(IT, "Google Drive API approach failed for fileId '{}', trying download fallback", fileId, e);
        }

        // FALLBACK: DIRECT DOWNLOAD URL
        return this.getDataFromDownload(fileId, uri);
    }

    private PlatformData getDataFromDownload(final String fileId, final URI original) throws Exception {
        String url = String.format(DOWNLOAD_URL, fileId);

        try (final NetRequest req = NetRequest.create(URI.create(url)).method("GET").accept("*/*").send()) {
            if (req.statusCode() != 200) throw new PlatformException(DrivePlatform.class, "Download for fileId '" + fileId + "' returned HTTP " + req.statusCode() + " (" + original + ")");

            if (req.contentType() != null && req.contentType().startsWith("text/html")) {
                // LARGE/UNSCANNED FILES RETURN AN HTML "VIRUS SCAN" CONFIRMATION FORM INSTEAD OF THE BYTES;
                // SCRAPE ITS HIDDEN INPUTS AND REPLAY THEM AS QUERY PARAMS TO GET THE REAL DOWNLOAD
                final String html = req.readAllAsString();
                final Matcher matcher = HTML_PATTERN.matcher(html);

                final Map<String, String> form = new HashMap<>();
                while (matcher.find()) {
                    form.put(matcher.group(1), matcher.group(2));
                }

                final String uuid = form.get("uuid");
                if (uuid == null)
                    throw new PlatformException(DrivePlatform.class, "Confirmation form has no 'uuid' field for fileId '" + fileId + "' (file may be private or quota-limited): " + original);

                final StringBuilder sb = new StringBuilder(url)
                        .append("&uuid=").append(uuid)
                        .append("&confirm=").append(form.getOrDefault("confirm", "t"));
                final String at = form.get("at"); // OPTIONAL ANTI-FORGERY TOKEN — OMIT WHEN ABSENT TO AVOID "at=null"
                if (at != null) sb.append("&at=").append(at);
                url = sb.toString();
                LOGGER.debug(IT, "Google Drive bypassed confirmation form for fileId '{}' (uuid present, at {})", fileId, at != null ? "present" : "absent");
            }

            final MediaType type = MediaType.of(req.contentType());
            LOGGER.debug(IT, "Google Drive resolved fileId '{}' via download (content-type {})", fileId, req.contentType());
            final var entry = new DataSource(type, null, null,
                    RequestHeaders.defaults(original),
                    new DataQuality[] {new DataQuality(new URI(url), 0, 0)},
                    null, null);
            return new PlatformData(null, entry);
        }
    }

    private static String extractFileId(final String path) {
        final int start = path.indexOf("/file/d/") + 8;
        int end = path.indexOf('/', start);
        if (end == -1) end = path.length();
        return path.substring(start, end);
    }
}
