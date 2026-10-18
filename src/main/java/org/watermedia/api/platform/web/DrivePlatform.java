package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public final class DrivePlatform implements Platform {
    public static final String NAME = "Google Drive";
    private static final Marker IT = MarkerManager.getMarker(DrivePlatform.class.getSimpleName());
    private static final String DOWNLOAD_URL = "https://drive.usercontent.google.com/download?id=%s&export=download&authuser=0&acknowledgeAbuse=true";
    private static final String API_URL = "https://www.googleapis.com/drive/v3/files/%s?alt=media&acknowledgeAbuse=true&key=%s";
    private static final String LUCK = new String(Base64.getDecoder().decode(new String(Base64.getDecoder().decode("UVVsNllWTjVRVTVpVFhkWGQyWnhiSE5QWWtoVlEwODFjbUZ6TjBGcmNXaE5UWEphUVZWWg=="), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    private static final Pattern HTML_PATTERN = Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]+)\"", Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final String[] HOSTS = { "drive.google.com" };
    private static final String FILE_PREFIX = "/file/d/";
    // A CONFIRMED DOWNLOAD URL CARRIES SESSION TOKENS (uuid, at) WHOSE LIFETIME GOOGLE DOES NOT PUBLISH
    private static final Duration CONFIRMED_TTL = Duration.ofMinutes(30);

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        // THE ID TRAVELS IN THE PATH OF A VIEWER LINK (/file/d/<id>/view) OR IN THE id PARAMETER OF /uc AND /open LINKS
        final String path = uri.getPath() == null ? "" : uri.getPath();
        String fileId = "";
        if (path.startsWith(FILE_PREFIX)) {
            final int end = path.indexOf('/', FILE_PREFIX.length());
            fileId = path.substring(FILE_PREFIX.length(), end < 0 ? path.length() : end);
        } else if (path.equals("/uc") || path.equals("/open")) {
            for (final String param: (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
                if (param.startsWith("id=")) fileId = param.substring(3);
            }
        }
        if (fileId.isEmpty()) throw PlatformException.unsupported(NAME, uri);
        LOGGER.debug(IT, "Google Drive resolving fileId '{}' from {}", fileId, uri);

        try {
            // TRY API APPROACH FIRST
            final URI apiUri = URI.create(String.format(API_URL, fileId, LUCK));
            try (final NetRequest req = NetRequest.create(apiUri).method("GET").accept(NetRequest.ACCEPT_ANY).send()) {
                if (req.statusCode() == HttpURLConnection.HTTP_OK) {
                    final MediaType type = MediaType.of(req.contentType());
                    LOGGER.debug(IT, "Google Drive resolved fileId '{}' via API (content-type {})", fileId, req.contentType());
                    final var entry = new Source(type, null, null,
                            HttpHeaders.defaults(uri),
                            List.of(new Quality(apiUri, 0, 0)),
                            null, null);
                    return new Resolution(null, entry);
                }
                LOGGER.debug(IT, "Google Drive API returned HTTP {} for fileId '{}', trying download fallback", req.statusCode(), fileId);
            } catch (final IOException e) {
                LOGGER.debug(IT, "Google Drive API approach failed for fileId '{}', trying download fallback", fileId, e);
            }

            // FALLBACK: DIRECT DOWNLOAD URL
            return this.getDownloadData(fileId, uri);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }

    private Resolution getDownloadData(final String fileId, final URI original) throws IOException {
        String url = String.format(DOWNLOAD_URL, fileId);

        try (final NetRequest req = NetRequest.create(URI.create(url)).method("GET").accept(NetRequest.ACCEPT_ANY).send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Download for fileId '" + fileId + "' returned HTTP " + req.statusCode() + " (" + original + ")");

            if (req.contentType() != null && req.contentType().startsWith("text/html")) {
                // LARGE/UNSCANNED FILES RETURN AN HTML "VIRUS SCAN" CONFIRMATION FORM INSTEAD OF THE BYTES;
                // SCRAPE ITS HIDDEN INPUTS AND REPLAY THEM AS QUERY PARAMS TO GET THE REAL DOWNLOAD
                final String html = req.text();
                final Matcher matcher = HTML_PATTERN.matcher(html);

                final Map<String, String> form = new HashMap<>();
                while (matcher.find()) {
                    form.put(matcher.group(1), matcher.group(2));
                }

                final String uuid = form.get("uuid");
                if (uuid == null)
                    throw new PlatformException(NAME, "Confirmation form has no 'uuid' field for fileId '" + fileId + "' (file may be private or quota-limited): " + original);

                final StringBuilder sb = new StringBuilder(url)
                        .append("&uuid=").append(uuid)
                        .append("&confirm=").append(form.getOrDefault("confirm", "t"));
                final String at = form.get("at"); // OPTIONAL ANTI-FORGERY TOKEN — OMIT WHEN ABSENT TO AVOID "at=null"
                if (at != null) sb.append("&at=").append(at);
                url = sb.toString();
                LOGGER.debug(IT, "Google Drive bypassed confirmation form for fileId '{}' (uuid present, at {})", fileId, at != null ? "present" : "absent");

                // THE ORIGINAL RESPONSE WAS THE HTML FORM, NOT THE FILE — RE-REQUEST THE REBUILT URL SO THE
                // THE Source CARRIES THE REAL FILE CONTENT-TYPE INSTEAD OF "text/html" (WHICH TYPES AS UNKNOWN)
                final URI confirmed = URI.create(url);
                try (final NetRequest confirm = NetRequest.create(confirmed).method("GET").accept(NetRequest.ACCEPT_ANY).send()) {
                    if (confirm.statusCode() != 200)
                        throw new PlatformException(NAME, "Confirmed download for fileId '" + fileId + "' returned HTTP " + confirm.statusCode() + " (" + original + ")");
                    final MediaType type = MediaType.of(confirm.contentType());
                    LOGGER.debug(IT, "Google Drive resolved fileId '{}' via confirmed download (content-type {})", fileId, confirm.contentType());
                    final var entry = new Source(type, null, null,
                            HttpHeaders.defaults(original),
                            List.of(new Quality(confirmed, 0, 0)),
                            null, null);
                    return new Resolution(Instant.now().plus(CONFIRMED_TTL), entry);
                }
            }

            final MediaType type = MediaType.of(req.contentType());
            LOGGER.debug(IT, "Google Drive resolved fileId '{}' via download (content-type {})", fileId, req.contentType());
            final var entry = new Source(type, null, null,
                    HttpHeaders.defaults(original),
                    List.of(new Quality(URI.create(url), 0, 0)),
                    null, null);
            return new Resolution(null, entry);
        }
    }
}
