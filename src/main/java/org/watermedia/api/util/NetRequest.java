package org.watermedia.api.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Builder-style HTTP/FTP/file:// request executor.
 * <p>
 * Use {@link #create(URI)} to configure headers, method and body, then call
 * {@link Builder#send()} to obtain a connected {@code NetRequest}.
 * <p>
 * Redirects (3xx) are followed manually up to {@link WaterMediaConfig.Network#maxRedirects}.
 * Cross-protocol redirects (http ↔ https) ARE followed — Java's {@link HttpURLConnection}
 * refuses to follow them automatically (it returns 302 even when {@code followRedirects}
 * is enabled), so this implementation walks them by hand to keep that switch transparent
 * to callers.
 */
public class NetRequest implements AutoCloseable {
    private static final Marker IT = MarkerManager.getMarker(NetRequest.class.getSimpleName());
    private static final Gson GSON = new Gson();

    public static final String MIMETYPE_JSON = "application/json";
    public static final String MIMETYPE_TEXT = "text/plain";
    public static final String MIMETYPE_FORM = "application/x-www-form-urlencoded";
    public static final String MIMETYPE_OCTET_STREAM = "application/octet-stream";

    public static final String ACCEPT_ANY = "*/*";
    public static final String ACCEPT_MEDIA = "audio/*, video/*, application/vnd.apple.mpegurl, application/x-mpegURL, application/dash+xml";
    public static final String ACCEPT_JSON = "application/json";
    public static final String ACCEPT_JSON_ANY = "application/json, application/json5";
    /**
     * Extra MIME type mappings registered into {@link URLConnection#getFileNameMap()}.
     * Java's bundled {@code content-types.properties} is missing a lot of formats that
     * WaterMedia consumes (codecs API + FFmpeg). When {@link URLConnection} cannot
     * resolve a file name we fall back to this table so platform code can rely on
     * {@code URLConnection.guessContentTypeFromName(...)} returning a sensible value.
     */
    private static final Map<String, String> EXTRA_MIME_TYPES = DataTool.arrayMapper(new String[] {
            // IMAGE: CODECS DECODED BY WATERMEDIA'S CODECS API
            "apng", "image/apng",
            "webp", "image/webp",
            "jfif", "image/jpeg",
            "pbm",  "image/x-portable-bitmap",
            "pgm",  "image/x-portable-graymap",
            "ppm",  "image/x-portable-pixmap",
            "pam",  "image/x-portable-arbitrarymap",
            // IMAGE: NOT SUPPORTED YET
            "bmp",  "image/bmp",
            "tif",  "image/tiff",
            "tiff", "image/tiff",
            "ico",  "image/x-icon",
            "avif", "image/avif",
            "heic", "image/heic",
            "heif", "image/heif",

            // VIDEO
            "mp4",  "video/mp4",
            "m4v",  "video/x-m4v",
            "mkv",  "video/x-matroska",
            "webm", "video/webm",
            "mov",  "video/quicktime",
            "avi",  "video/x-msvideo",
            "wmv",  "video/x-ms-wmv",
            "flv",  "video/x-flv",
            "f4v",  "video/x-f4v",
            "ts",   "video/mp2t",
            "mts",  "video/mp2t",
            "m2ts", "video/mp2t",
            "mpg",  "video/mpeg",
            "mpeg", "video/mpeg",
            "3gp",  "video/3gpp",
            "3g2",  "video/3gpp2",
            "ogv",  "video/ogg",
            "asf",  "video/x-ms-asf",
            "vob",  "video/dvd",
            "rm",   "application/vnd.rn-realmedia",
            "rmvb", "application/vnd.rn-realmedia-vbr",
            "m3u8", "application/vnd.apple.mpegurl",
            "mpd",  "application/dash+xml",

            // AUDIO
            "mp3",  "audio/mpeg",
            "aac",  "audio/aac",
            "m4a",  "audio/mp4",
            "ogg",  "audio/ogg",
            "oga",  "audio/ogg",
            "opus", "audio/opus",
            "flac", "audio/flac",
            "wav",  "audio/wav",
            "wma",  "audio/x-ms-wma",
            "ac3",  "audio/ac3",
            "dts",  "audio/vnd.dts",
            "amr",  "audio/amr",
            "aiff", "audio/aiff",
            "aif",  "audio/aiff",
            "au",   "audio/basic",

            // SUBTITLES
            "vtt",  "text/vtt",
            "srt",  "application/x-subrip",
            "ass",  "text/x-ssa",
            "ssa",  "text/x-ssa",
    });

    private static volatile boolean MIME_INSTALLED;

    /**
     * Extends {@link URLConnection#getFileNameMap()} with {@link #EXTRA_MIME_TYPES}.
     * Idempotent: subsequent calls are no-ops. Invoked once during NetworkAPI startup.
     */
    public static synchronized void installExtraMimeTypes() {
        if (MIME_INSTALLED) return;
        final FileNameMap base = URLConnection.getFileNameMap();
        URLConnection.setFileNameMap(fileName -> {
            final String ct = base.getContentTypeFor(fileName);
            if (ct != null) return ct;

            final String name = fileName.toLowerCase(Locale.ROOT);
            final int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                final String ext = name.substring(dot + 1);
                final String mapped = EXTRA_MIME_TYPES.get(ext);
                if (mapped != null) return mapped;
            }

            LOGGER.warn(IT, "Unknown file type for {}", fileName);
            return null;
        });
        MIME_INSTALLED = true;
    }

    /**
     * User-Agent presets. {@link #WATERMEDIA} identifies our APIs; {@link #GENERIC} mimics a
     * desktop browser to bypass servers that filter non-browser clients.
     */
    public enum UserAgent {
        WATERMEDIA(WaterMedia.USER_AGENT),
        GENERIC("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

        private final String value;
        UserAgent(final String value) { this.value = value; }
        public String value() { return this.value; }
    }

    private final URI uri;
    private final URLConnection connection;
    private final int statusCode;
    private final RequestHeaders requestHeaders;
    private final RequestHeaders responseHeaders;

    private NetRequest(final URI uri, final URLConnection connection, final int statusCode, final RequestHeaders requestHeaders) {
        this.uri = uri;
        this.connection = connection;
        this.statusCode = statusCode;
        this.requestHeaders = requestHeaders;
        this.responseHeaders = RequestHeaders.fromResponse(connection);
    }

    public static Builder create(final URI uri) {
        return new Builder(uri);
    }

    public static Builder create(final String uri) {
        return new Builder(URI.create(uri));
    }

    /**
     * The URI that produced this response, after any followed redirects.
     */
    public URI uri() { return this.uri; }

    /**
     * HTTP response code. Defaults to {@link HttpURLConnection#HTTP_OK} (200) for non-HTTP
     * protocols (file://, ftp://) so callers can use a single {@code statusCode != 200}
     * check without special-casing the protocol.
     */
    public int statusCode() { return this.statusCode; }

    public String contentType() { return this.connection.getContentType(); }

    public long contentLength() { return this.connection.getContentLengthLong(); }

    /**
     * Shortcut for {@code responseHeaders().get(name)}.
     */
    public String header(final String name) { return this.responseHeaders.get(name); }

    /**
     * Headers actually sent on the wire — including the User-Agent / Accept / Content-Type /
     * Referer set via the builder convenience methods. Use {@link RequestHeaders#toRawString()}
     * to get an FFmpeg-ready blob.
     */
    public RequestHeaders requestHeaders() { return this.requestHeaders; }

    /**
     * Headers received from the server, snapshotted at construction time.
     */
    public RequestHeaders responseHeaders() { return this.responseHeaders; }

    /**
     * Raw response stream. Caller must close it (or {@link #close()} the request).
     */
    public InputStream getInputStream() throws IOException {
        return this.connection.getInputStream();
    }

    /**
     * Raw response stream wrapped by {@code wrapper}. Lets the caller pick the decoration
     * (e.g. {@code BufferedInputStream::new}, {@code GZIPInputStream::new}) and recovers the
     * concrete type without an external cast.
     * <pre>{@code
     * BufferedInputStream bis = req.getInputStream(BufferedInputStream::new);
     * }</pre>
     */
    public <T extends InputStream> T getInputStream(final Function<InputStream, T> wrapper) throws IOException {
        return wrapper.apply(this.getInputStream());
    }

    /**
     * Reads the entire response as a UTF-8 string. Throws if the body exceeds
     * {@link WaterMediaConfig.Network#maxTextBytes}.
     */
    public String readAllAsString() throws IOException {
        try (final InputStream is = this.getInputStream()) {
            return readBounded(is, WaterMediaConfig.network.maxTextBytes);
        }
    }

    /**
     * Parses the response body as a generic {@link JsonElement}. Returns {@code null} when
     * the {@code content-type} does not contain {@code application/json}. Honors
     * {@link WaterMediaConfig.Network#maxTextBytes}.
     */
    public JsonElement json() throws IOException {
        final String body = this.readJsonBody();
        if (body == null) return null;
        try {
            return GSON.fromJson(body, JsonElement.class);
        } catch (final JsonSyntaxException e) {
            throw new IOException("Malformed JSON in response from " + this.uri, e);
        }
    }

    /**
     * Parses the response body as JSON and binds it to {@code type} via Gson. Returns
     * {@code null} when the {@code content-type} does not contain {@code application/json}.
     * Honors {@link WaterMediaConfig.Network#maxTextBytes}.
     */
    public <T> T json(final Class<T> type) throws IOException {
        final String body = this.readJsonBody();
        if (body == null) return null;
        try {
            return GSON.fromJson(body, type);
        } catch (final JsonSyntaxException e) {
            throw new IOException("Malformed JSON in response from " + this.uri, e);
        }
    }

    private String readJsonBody() throws IOException {
        final String ct = this.contentType();
        if (ct == null || !ct.toLowerCase().contains(MIMETYPE_JSON)) return null;
        try (final InputStream is = this.getInputStream()) {
            return readBounded(is, WaterMediaConfig.network.maxTextBytes);
        }
    }

    @Override
    public void close() {
        if (this.connection instanceof final HttpURLConnection http) http.disconnect();
    }

    private static String readBounded(final InputStream is, final int max) throws IOException {
        return new String(IOTool.readLimited(is, max, -1L), StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private final URI uri;
        private String method = "GET";
        private UserAgent userAgent = UserAgent.WATERMEDIA;
        private final RequestHeaders headers = new RequestHeaders();
        private byte[] body;
        private int connectTimeout = WaterMediaConfig.network.requestTimeoutMs;
        private int readTimeout = WaterMediaConfig.network.requestTimeoutMs;
        private int maxRedirects = WaterMediaConfig.network.maxRedirects;
        private boolean accept$set;
        private boolean referer$set;

        Builder(final URI uri) {
            this.uri = Objects.requireNonNull(uri, "uri cannot be null");
            this.headers.set("Accept", "*/*");
        }

        public Builder method(final String method) { this.method = method.toUpperCase(); return this; }
        public Builder contentType(final String contentType) { this.headers.set("Content-Type", contentType); return this; }
        public Builder accept(final String accept) { this.headers.set("Accept", accept); this.accept$set = true; return this; }
        public Builder referer(final String referer) { this.headers.set("Referer", referer); this.referer$set = true; return this; }
        public Builder userAgent(final UserAgent userAgent) { this.userAgent = Objects.requireNonNull(userAgent); return this; }
        public Builder header(final String name, final String value) { this.headers.set(name, value); return this; }
        public Builder addHeader(final String name, final String value) { this.headers.add(name, value); return this; }
        public Builder body(final byte[] body) { this.body = body; return this; }
        public Builder body(final String body) { this.body = body == null ? null : body.getBytes(StandardCharsets.UTF_8); return this; }
        public Builder connectTimeout(final int ms) { this.connectTimeout = ms; return this; }
        public Builder readTimeout(final int ms) { this.readTimeout = ms; return this; }
        public Builder maxRedirects(final int n) { this.maxRedirects = n; return this; }

        /**
         * Snapshot of the headers configured so far — useful when a caller needs the same
         * header set for an out-of-band consumer (e.g. FFmpeg) before {@link #send()}.
         * The User-Agent and the auto-derived Referer are baked in.
         */
        public RequestHeaders headers() {
            return this.materializeHeaders(this.uri);
        }

        /**
         * Opens the connection, writes the body if any, and follows redirects manually —
         * including cross-protocol switches that {@link HttpURLConnection} silently refuses.
         * Throws {@link IOException} when the redirect chain exceeds {@link #maxRedirects},
         * logging the full hop trace before giving up.
         */
        public NetRequest send() throws IOException {
            URI current = this.uri;
            final List<URI> trace = new ArrayList<>();
            trace.add(current);

            while (true) {
                final RequestHeaders effective = this.materializeHeaders(current);
                final URLConnection conn = this.openConnection(current, effective);

                if (!(conn instanceof final HttpURLConnection http)) {
                    return new NetRequest(current, conn, HttpURLConnection.HTTP_OK, effective);
                }

                final int code = http.getResponseCode();
                if (!isRedirect(code)) {
                    return new NetRequest(current, http, code, effective);
                }

                if (trace.size() - 1 >= this.maxRedirects) {
                    http.disconnect();
                    final StringBuilder chain = new StringBuilder();
                    for (int i = 0; i < trace.size(); i++) {
                        if (i > 0) chain.append("\n  -> ");
                        chain.append('[').append(i).append("] ").append(trace.get(i));
                    }
                    LOGGER.error(IT, "tooManyRedirects: hit cap of {} redirects for {}\n  {}", this.maxRedirects, this.uri, chain);
                    throw new IOException("tooManyRedirects: " + this.maxRedirects + " hops exceeded for " + this.uri);
                }

                final String location = http.getHeaderField("Location");
                if (location == null || location.isEmpty()) {
                    return new NetRequest(current, http, code, effective);
                }

                final URI next;
                try {
                    next = current.resolve(new URI(location));
                } catch (final URISyntaxException e) {
                    return new NetRequest(current, http, code, effective);
                }

                http.disconnect();
                current = next;
                trace.add(current);
            }
        }

        private RequestHeaders materializeHeaders(final URI target) {
            final RequestHeaders out = new RequestHeaders(this.headers);
            out.set("User-Agent", this.userAgent.value);
            if (!this.accept$set && !out.has("Accept")) out.set("Accept", "*/*");
            if (!this.referer$set && !out.has("Referer")) {
                final String host = target.getHost();
                if (host != null) out.set("Referer", target.getScheme() + "://" + host);
            }
            return out;
        }

        private URLConnection openConnection(final URI target, final RequestHeaders effective) throws IOException {
            final URL url = target.toURL();
            final URLConnection conn = url.openConnection();
            conn.setConnectTimeout(this.connectTimeout);
            conn.setReadTimeout(this.readTimeout);

            if (conn instanceof final HttpURLConnection http) {
                http.setInstanceFollowRedirects(false); // MANUAL REDIRECTS ALLOW CROSS-PROTOCOL SWITCHES
                http.setRequestMethod(this.method);
                effective.writeTo(http);

                if (this.body != null && this.body.length > 0) {
                    http.setDoOutput(true);
                    http.setFixedLengthStreamingMode(this.body.length);
                    try (final OutputStream os = http.getOutputStream()) {
                        os.write(this.body);
                    }
                }
            }
            // FTP AND FILE URLS FALL THROUGH; HTTP-ONLY HEADERS AND METHOD DO NOT APPLY.
            return conn;
        }

        private static boolean isRedirect(final int code) {
            return code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308;
        }
    }
}
