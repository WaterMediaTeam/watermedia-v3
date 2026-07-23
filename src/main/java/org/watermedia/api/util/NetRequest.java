package org.watermedia.api.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
public final class NetRequest implements AutoCloseable {
    private static final Marker IT = MarkerManager.getMarker(NetRequest.class.getSimpleName());

    public static final String MIMETYPE_JSON = "application/json";
    public static final String MIMETYPE_TEXT = "text/plain";
    public static final String MIMETYPE_FORM = "application/x-www-form-urlencoded";
    public static final String MIMETYPE_OCTET_STREAM = "application/octet-stream";

    public static final String ACCEPT_ANY = "*/*";
    public static final String ACCEPT_MEDIA = "audio/*, video/*, application/vnd.apple.mpegurl, application/x-mpegURL, application/dash+xml";
    public static final String ACCEPT_JSON = "application/json";
    public static final String ACCEPT_JSON_ANY = "application/json, application/json5";
    private static volatile boolean MIME_INSTALLED;

    /**
     * Extends {@link URLConnection#getFileNameMap()} with {@link MediaType#mime(String)}'s
     * extension table, so platform code can rely on
     * {@code URLConnection.guessContentTypeFromName(...)} returning a sensible value.
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
                final String mapped = MediaType.mime(name.substring(dot + 1));
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
     * Fetches {@code uri} with a JSON GET request and binds the response body to {@code type}
     * through the shared Gson tooling ({@link JSONTool}). This is the common REST resolver for
     * platform handlers: one identical implementation instead of a private copy per handler.
     * Every failure — non-200 status, empty/non-JSON body, transport or parse error — surfaces
     * as a {@link PlatformException} tagged with {@code platform}, keeping the root cause.
     * Handlers needing extra headers, another method or a custom user agent should build their
     * own request via {@link #create(URI)} instead.
     *
     * @param platform the handler the request belongs to, used to tag failures
     * @param uri the API endpoint to request
     * @param type the class the JSON body binds to
     * @param <T> the bound type
     * @return the bound response, never {@code null}
     * @throws PlatformException when the request fails for any reason
     */
    public static <T> T fetchJson(final Class<? extends IPlatform> platform, final URI uri, final Class<T> type) throws PlatformException {
        try (final NetRequest req = create(uri).method("GET").accept(ACCEPT_JSON).send()) {
            if (req.statusCode() != 200)
                throw new PlatformException(platform, "API " + uri + " returned HTTP " + req.statusCode());
            final T data = req.json(type);
            if (data == null)
                throw new PlatformException(platform, "API " + uri + " returned an empty or non-JSON body");
            return data;
        } catch (final PlatformException e) {
            throw e; // ALREADY CARRIES THE PLATFORM CONTEXT
        } catch (final Exception e) {
            throw new PlatformException(platform, "Failed to fetch " + uri, e);
        }
    }

    /**
     * Convenience overload of {@link #fetchJson(Class, URI, Class)} taking the endpoint as a string.
     *
     * @param platform the handler the request belongs to, used to tag failures
     * @param url the API endpoint to request
     * @param type the class the JSON body binds to
     * @param <T> the bound type
     * @return the bound response, never {@code null}
     * @throws PlatformException when the request fails for any reason
     */
    public static <T> T fetchJson(final Class<? extends IPlatform> platform, final String url, final Class<T> type) throws PlatformException {
        return fetchJson(platform, URI.create(url), type);
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
    public InputStream inputStream() throws IOException {
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
    public <T extends InputStream> T inputStream(final Function<InputStream, T> wrapper) throws IOException {
        return wrapper.apply(this.inputStream());
    }

    /**
     * Streams the response body into {@code dest}, replacing any existing file. Intended for
     * binary payloads (release archives, executables) that must land on disk instead of in memory,
     * so it is not bounded by {@link WaterMediaConfig.Network#maxTextSize}. Fails on a non-200
     * status. When the server advertises a {@code Content-Length} the written size is verified
     * against it; on any failure or size mismatch the partial file is deleted so a retry
     * re-downloads instead of trusting a truncated file.
     *
     * @param dest the file to write the body into; its parent directory must exist
     * @throws IOException on a non-200 status, a transport or write failure, or a truncated body
     */
    public void download(final Path dest) throws IOException {
        if (this.statusCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Download failed (HTTP " + this.statusCode + "): " + this.uri);
        }
        try {
            try (final InputStream in = this.inputStream(); final OutputStream out = Files.newOutputStream(dest)) {
                in.transferTo(out);
            }
            final long expected = this.contentLength(); // -1 WHEN CHUNKED / UNKNOWN — NOTHING TO CHECK AGAINST
            if (expected >= 0) {
                final long actual = Files.size(dest);
                if (actual != expected) {
                    throw new IOException("Truncated download (" + actual + "/" + expected + " bytes): " + this.uri);
                }
            }
        } catch (final IOException e) {
            try {
                Files.deleteIfExists(dest);
            } catch (final IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Reads the entire response as a UTF-8 string. Throws if the body exceeds
     * {@link WaterMediaConfig.Network#maxTextSize}.
     */
    public String readAllAsString() throws IOException {
        try (final InputStream is = this.inputStream()) {
            return new String(IOTool.readLimited(is, WaterMediaConfig.network.maxTextSize, -1L), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses the response body as a generic {@link JsonElement} through {@link JSONTool}.
     * Returns {@code null} when the {@code content-type} does not contain
     * {@code application/json}. Honors {@link WaterMediaConfig.Network#maxTextSize}.
     */
    public JsonElement json() throws IOException {
        return this.json(JsonElement.class);
    }

    /**
     * Parses the response body as JSON and binds it to {@code type} through {@link JSONTool}.
     * Returns {@code null} when the {@code content-type} does not contain
     * {@code application/json}. Honors {@link WaterMediaConfig.Network#maxTextSize}.
     */
    public <T> T json(final Class<T> type) throws IOException {
        final String body = this.readJsonBody();
        if (body == null) return null;
        try {
            return JSONTool.parse(body, type);
        } catch (final JsonSyntaxException e) {
            throw new IOException("Malformed JSON in response from " + this.uri, e);
        }
    }

    private String readJsonBody() throws IOException {
        final String ct = this.contentType();
        if (ct == null || !ct.toLowerCase().contains(MIMETYPE_JSON)) return null;
        try (final InputStream is = this.inputStream()) {
            return new String(IOTool.readLimited(is, WaterMediaConfig.network.maxTextSize, -1L), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        if (this.connection instanceof final HttpURLConnection http) http.disconnect();
    }

    public static final class Builder {
        private final URI uri;
        private String method = "GET";
        private UserAgent userAgent = UserAgent.WATERMEDIA;
        private final RequestHeaders headers = new RequestHeaders();
        private byte[] body;
        private int connectTimeout = WaterMediaConfig.network.timeout;
        private int readTimeout = WaterMediaConfig.network.timeout;
        private int maxRedirects = WaterMediaConfig.network.maxRedirects;

        Builder(final URI uri) {
            this.uri = Objects.requireNonNull(uri, "uri cannot be null");
            this.headers.set("Accept", "*/*");
        }

        public Builder method(final String method) { this.method = method.toUpperCase(); return this; }
        public Builder contentType(final String contentType) { this.headers.set("Content-Type", contentType); return this; }
        public Builder accept(final String accept) { this.headers.set("Accept", accept); return this; }
        public Builder referer(final String referer) { this.headers.set("Referer", referer); return this; }
        public Builder userAgent(final UserAgent userAgent) { this.userAgent = Objects.requireNonNull(userAgent); return this; }
        public Builder header(final String name, final String value) { this.headers.set(name, value); return this; }
        public Builder addHeader(final String name, final String value) { this.headers.add(name, value); return this; }

        /**
         * Bulk-applies {@code headers}: the first occurrence of each name replaces any builder
         * default (like {@link #header}), later occurrences append (like {@link #addHeader}),
         * preserving multi-valued headers. A {@code null} or empty bag is a no-op.
         */
        public Builder headers(final RequestHeaders headers) {
            this.headers.merge(headers);
            return this;
        }
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
                    // FORCE A REAL CONNECT FOR FILE:// AND FTP:// SO MISSING RESOURCES FAIL FAST.
                    // URLConnection.getContentType() ONLY GUESSES FROM THE FILENAME AND NEVER
                    // TOUCHES THE UNDERLYING RESOURCE — WITHOUT THIS CALL A NONEXISTENT file://
                    // URI WOULD SILENTLY LOOK LIKE A VALID MEDIA RESPONSE TO CALLERS LIKE MRL.
                    conn.connect();
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
                    LOGGER.error(IT, "Failed to follow redirects for {}: hit cap of {} redirects\n  {}", this.uri, this.maxRedirects, chain);
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

                // RFC 9110: A 303 SEE OTHER MUST BE RE-REQUESTED AS GET WITHOUT THE ORIGINAL BODY
                // THIS IS STUPID
                if (code == HttpURLConnection.HTTP_SEE_OTHER) {
                    this.method = "GET";
                    this.body = null;
                }

                current = next;
                trace.add(current);
            }
        }

        private RequestHeaders materializeHeaders(final URI target) {
            final RequestHeaders out = new RequestHeaders(this.headers);
            out.set("User-Agent", this.userAgent.value);
            // ACCEPT IS ALWAYS SEEDED IN THE CTOR; ONLY DERIVE A REFERER WHEN THE CALLER DID NOT SET ONE
            if (!out.has("Referer")) {
                final String host = target.getHost();
                if (host != null) out.set("Referer", target.getScheme() + "://" + host);
            }
            // DO NOT LEAK THE CREDENTIALS ACROSS ORIGIN, I AM SEEING SCRIPTKIDS ABUSING THIS
            if (!Objects.equals(this.uri.getHost(), target.getHost())) {
                out.removeAll("Authorization");
                out.removeAll("X-WaterMedia-Token");
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
