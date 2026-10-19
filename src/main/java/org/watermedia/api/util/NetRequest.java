package org.watermedia.api.util;

import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

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
 * to callers. A redirect to any other scheme ({@code file:}, {@code jar:}, {@code ftp:}) fails,
 * so a server can never point a request at local resources. Credentials and request bodies stay
 * with the original origin; a body is resent to another origin only when set with
 * {@code crossOrigin}, otherwise that redirect fails.
 * <p>
 * A {@code NetRequest} is not thread-safe: send it and read its response on one thread.
 */
public final class NetRequest implements AutoCloseable {
    private static final Marker IT = MarkerManager.getMarker(NetRequest.class.getSimpleName());

    /** Content type of a JSON body. */
    public static final String MIMETYPE_JSON = "application/json";
    /** Content type of an HTML form body. */
    public static final String MIMETYPE_FORM = "application/x-www-form-urlencoded";
    /** Content type of an opaque binary body. */
    public static final String MIMETYPE_OCTET_STREAM = "application/octet-stream";

    /** Accept value taking any response. */
    public static final String ACCEPT_ANY = "*/*";
    /** Accept value for playable media, images and the HLS and DASH manifests that describe them. */
    public static final String ACCEPT_MEDIA = "audio/*, video/*, image/*, application/vnd.apple.mpegurl, application/x-mpegurl, application/dash+xml, application/ogg, */*;q=0.8";
    /** Accept value for a JSON response. */
    public static final String ACCEPT_JSON = "application/json";

    // A TRICKLING SERVER CAN KEEP EVERY READ UNDER THE SOCKET TIMEOUT, SO A TEXT BODY GETS THIS MANY READ TIMEOUTS IN TOTAL
    private static final int TEXT_DEADLINE_READ_TIMEOUTS = 4;

    /** Splits the raw query into an unmodifiable map decoded once: a malformed pair stays raw, a repeated key keeps its last value, a bare key maps to "". */
    public static Map<String, String> query(final URI uri) {
        final String query = uri == null ? null : uri.getRawQuery();
        if (query == null || query.isEmpty()) return Map.of();
        final Map<String, String> result = new HashMap<>();
        for (final String param: query.split("&")) {
            if (param.isEmpty()) continue;
            final int eq = param.indexOf('=');
            final String key = eq < 0 ? param : param.substring(0, eq);
            final String value = eq < 0 ? "" : param.substring(eq + 1);
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (final IllegalArgumentException malformed) {
                result.put(key, value);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Content type for a file name: WaterMedia's own extension table first, then the JDK's, else
     * {@link #MIMETYPE_OCTET_STREAM}. A local lookup, so no global JDK state is ever replaced.
     */
    public static String contentTypeOf(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        final String known = dot < 0 || dot == fileName.length() - 1 ? null : MediaType.mime(fileName.substring(dot + 1));
        if (known != null) return known;
        final String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : MIMETYPE_OCTET_STREAM;
    }

    /**
     * User-Agent presets. {@link #WATERMEDIA} identifies our APIs; {@link #GENERIC} mimics a
     * desktop browser to bypass servers that filter non-browser clients.
     */
    public enum UserAgent {
        WATERMEDIA(WaterMedia.USER_AGENT),
        GENERIC(browser());

        private final String value;
        UserAgent(final String value) { this.value = value; }
        /** Returns the {@code User-Agent} header value of this preset. */
        public String value() { return this.value; }

        // CHROME SHIPS A MAJOR EVERY TWO WEEKS SINCE 153 (2026-09-08) AND A FROZEN NUMBER BECOMES A BOT SIGNAL WITHIN
        // MONTHS, SO THE MAJOR IS PROJECTED FROM THAT RELEASE EACH TIME THE CLASS LOADS, ONE BEHIND TO NEVER RUN AHEAD
        private static String browser() {
            final long steps = ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 8), LocalDate.now()) / 14;
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + (152 + Math.max(0L, steps)) + ".0.0.0 Safari/537.36";
        }
    }

    private final URI uri;
    private final URLConnection connection;
    private final int statusCode;
    private final long maxBytes;
    private final long deadlineNs;
    private final HttpHeaders responseHeaders;
    private boolean consumed;

    private NetRequest(final URI uri, final URLConnection connection, final int statusCode, final long maxBytes, final long deadlineNs) {
        this.uri = uri;
        this.connection = connection;
        this.statusCode = statusCode;
        this.maxBytes = maxBytes;
        this.deadlineNs = deadlineNs;
        this.responseHeaders = HttpHeaders.fromResponse(connection);
    }

    /** Creates a request builder with default headers and configured network limits. */
    public static Builder create(final URI uri) {
        return new Builder(uri);
    }

    /** Creates a request builder for URI text; invalid syntax throws {@link IllegalArgumentException}. */
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

    /** Returns the reported content type, or null when unavailable. */
    public String contentType() { return this.connection.getContentType(); }

    /** Returns the reported content length in bytes, or -1 when unknown. */
    public long contentLength() { return this.connection.getContentLengthLong(); }

    /**
     * Shortcut for {@code responseHeaders().value(name)}.
     */
    public String header(final String name) { return this.responseHeaders.value(name); }

    /**
     * Headers received from the server, snapshotted at construction time.
     */
    public HttpHeaders responseHeaders() { return this.responseHeaders; }

    /**
     * Raw response stream; the caller must close it (or {@link #close()} the request). An HTTP
     * status of 400 or more yields the error body instead of throwing, or an empty stream when the
     * server sent none. The body can be read once, through this, {@link #text()} or {@link #json(Class)}.
     *
     * @return the response body stream
     * @throws IOException when the connection cannot provide the body
     * @throws IllegalStateException when the body was already read
     */
    public InputStream inputStream() throws IOException {
        if (this.consumed) throw new IllegalStateException("The response body was already read");
        this.consumed = true;
        if (this.statusCode >= 400 && this.connection instanceof final HttpURLConnection http) {
            final InputStream error = http.getErrorStream();
            return error != null ? error : InputStream.nullInputStream();
        }
        return this.connection.getInputStream();
    }

    /**
     * Reads the entire response as a UTF-8 string, the error body of a failed status included.
     * Throws when the body exceeds the builder's {@code maxBytes} (else {@link WaterMediaConfig.Network#maxTextBytes})
     * or outlives the builder's {@code deadline} (else four read timeouts; a read timeout of zero sets no deadline).
     *
     * @return the response body text
     * @throws IOException when the transfer fails or exceeds its size or time budget
     * @throws IllegalStateException when the body was already read
     */
    public String text() throws IOException {
        try (final InputStream in = this.inputStream()) {
            final long limit = this.maxBytes > 0 ? this.maxBytes : WaterMediaConfig.network.maxTextBytes;
            final long deadlineMs = this.deadlineNs > 0 ? Math.max(1L, (this.deadlineNs - System.nanoTime()) / 1_000_000L)
                    : (long) TEXT_DEADLINE_READ_TIMEOUTS * this.connection.getReadTimeout();
            return new String(IOTool.readLimited(in, limit, this.contentLength(), deadlineMs), StandardCharsets.UTF_8);
        }
    }

    /**
     * Streams a 200 body into a new file within the size and time budgets of the builder and verifies its SHA-256;
     * the file is deleted again on any failure.
     *
     * @param destination the file to create; it must not exist
     * @param expectedSha256 the publisher-provided lowercase or uppercase hex digest
     * @throws IOException when the status is not 200, the budget is exceeded, the file exists or the digest differs
     * @throws IllegalStateException when the body was already read
     */
    public void download(final Path destination, final String expectedSha256) throws IOException {
        final String digest = IOTool.requireSha256(expectedSha256);
        if (this.statusCode != HttpURLConnection.HTTP_OK) throw new IOException("Download returned HTTP " + this.statusCode + ": " + this.uri);
        final long limit = this.maxBytes > 0 ? this.maxBytes : Long.MAX_VALUE;
        final long expected = this.contentLength();
        if (expected > limit) throw new IOException("Download exceeds its size limit: " + this.uri);
        boolean created = false;
        try (final InputStream in = this.inputStream()) {
            try (final OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                created = true;
                final byte[] buffer = new byte[IOTool.BUFFER_SIZE];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > limit || (this.deadlineNs > 0 && System.nanoTime() > this.deadlineNs))
                        throw new IOException("Download budget exceeded: " + this.uri);
                    out.write(buffer, 0, count);
                }
                if (expected >= 0 && total != expected) throw new IOException("Truncated download: " + this.uri);
            }
            IOTool.verifySha256(destination, digest);
        } catch (final IOException failure) {
            if (created) {
                try { Files.deleteIfExists(destination); }
                catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /**
     * Parses the response body as JSON and binds it to {@code type} through {@link JSONTool}, with
     * the size and time budget of {@link #text()}. Accepts {@code application/json},
     * {@code text/json} and any {@code +json} subtype such as {@code application/vnd.api+json}.
     *
     * @param type the class the JSON body binds to
     * @param <T> the bound type
     * @return the bound body, or {@code null} for another content type (the body stays unread) or an empty body
     * @throws IOException when the transfer fails or the body is malformed JSON
     * @throws IllegalStateException when the body was already read
     */
    public <T> T json(final Class<T> type) throws IOException {
        if (this.consumed) throw new IllegalStateException("The response body was already read");
        final String contentType = this.contentType();
        final String mime = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!mime.equals(MIMETYPE_JSON) && !mime.equals("text/json") && !mime.endsWith("+json")) return null;
        final String body = this.text();
        try {
            return JSONTool.parse(body, type);
        } catch (final JsonSyntaxException e) {
            throw new IOException("Malformed JSON in response from " + this.uri, e);
        }
    }

    @Override
    public void close() {
        if (this.connection instanceof final HttpURLConnection http) {
            http.disconnect();
            return;
        }
        // FILE/FTP CONNECTIONS OPEN THEIR STREAM AT connect() — RELEASE IT OR THE HANDLE LIVES UNTIL GC
        try {
            this.connection.getInputStream().close();
        } catch (final IOException ignored) {}
    }

    /**
     * Request configuration. A builder is not thread-safe: configure and send it on one thread,
     * though one builder may send several sequential requests.
     */
    public static final class Builder {
        private final URI uri;
        private String method = "GET";
        private final HttpHeaders headers = new HttpHeaders();
        private byte[] body;
        private boolean crossOrigin;
        private int connectTimeout = WaterMediaConfig.network.timeout;
        private int readTimeout = WaterMediaConfig.network.timeout;
        private int maxRedirects = WaterMediaConfig.network.maxRedirects;
        private boolean httpsOnly;
        private long maxBytes = -1L;
        private long deadlineMs;

        Builder(final URI uri) {
            if (uri == null) throw new IllegalArgumentException("URI cannot be null");
            this.uri = uri;
            // DEFAULTS LIVE IN THE BAG, SO header(), headers() AND THE PRESETS REPLACE THEM IN CALL ORDER
            this.headers.set("Accept", ACCEPT_ANY).set("User-Agent", UserAgent.WATERMEDIA.value);
        }

        /** Sets the HTTP method, uppercased; the connection validates it on {@link #send()}. */
        public Builder method(final String method) { this.method = method.toUpperCase(Locale.ROOT); return this; }
        /** Replaces the {@code Content-Type} header. */
        public Builder contentType(final String contentType) { this.headers.set("Content-Type", contentType); return this; }
        /** Replaces the {@code Accept} header. */
        public Builder accept(final String accept) { this.headers.set("Accept", accept); return this; }
        /** Replaces the {@code Referer} header, overriding the derived default. */
        public Builder referer(final String referer) { this.headers.set("Referer", referer); return this; }
        /** Replaces the {@code User-Agent} header with a preset; the last writer of that header wins. */
        public Builder userAgent(final UserAgent userAgent) { this.headers.set("User-Agent", userAgent.value); return this; }
        /** Replaces every value of a header; names must be HTTP tokens and values free of control characters. */
        public Builder header(final String name, final String value) { this.headers.set(name, value); return this; }

        /**
         * Bulk-applies {@code headers}: the first occurrence of each name replaces any builder
         * default (like {@link #header}), later occurrences append, preserving multi-valued
         * headers. A {@code null} or empty bag is a no-op.
         */
        public Builder headers(final HttpHeaders headers) {
            this.headers.merge(headers);
            return this;
        }

        /** Sets the body without copying, or clears it with null; redirects resend it only to the original origin. */
        public Builder body(final byte[] body) { return this.body(body, false); }
        /** Sets the body as UTF-8 text, or clears it with null; redirects resend it only to the original origin. */
        public Builder body(final String body) { return this.body(body, false); }
        /** Sets the body without copying, or clears it with null; {@code crossOrigin} lets redirects resend it to other origins. */
        public Builder body(final byte[] body, final boolean crossOrigin) { this.body = body; this.crossOrigin = crossOrigin; return this; }
        /** Sets the body as UTF-8 text, or clears it with null; {@code crossOrigin} lets redirects resend it to other origins. */
        public Builder body(final String body, final boolean crossOrigin) { return this.body(body == null ? null : body.getBytes(StandardCharsets.UTF_8), crossOrigin); }
        /** Sets the connect timeout in milliseconds; zero waits forever and negatives fail on {@link #send()}. */
        public Builder connectTimeout(final int ms) { this.connectTimeout = ms; return this; }
        /** Sets the read timeout in milliseconds, which also sizes the total text deadline; zero waits forever and negatives fail on {@link #send()}. */
        public Builder readTimeout(final int ms) { this.readTimeout = ms; return this; }
        /** Sets how many redirects {@link #send()} may follow; zero or less rejects the first one. */
        public Builder maxRedirects(final int n) { this.maxRedirects = n; return this; }
        /** Requires https without credentials on the request and on every redirect hop. */
        public Builder httpsOnly() { this.httpsOnly = true; return this; }
        /** Caps the body {@link NetRequest#text()}, {@link NetRequest#json(Class)} and {@link NetRequest#download(Path, String)} accept. */
        public Builder maxBytes(final long bytes) {
            if (bytes < 1) throw new IllegalArgumentException("The byte limit must be positive");
            this.maxBytes = bytes;
            return this;
        }
        /** Sets a total deadline in milliseconds covering the redirect chain and the body transfer. */
        public Builder deadline(final long ms) {
            if (ms < 1) throw new IllegalArgumentException("The deadline must be positive");
            this.deadlineMs = ms;
            return this;
        }

        /**
         * Opens the connection, writes the body if any, and follows redirects manually,
         * including cross-protocol switches that {@link HttpURLConnection} silently refuses.
         * @throws IOException when the transfer fails, the method is rejected, a redirect leaves http and https
         *                     or the redirect chain exceeds {@link #maxRedirects}
         */
        public NetRequest send() throws IOException {
            URI current = this.uri;
            // A 303 SWITCHES ONLY THIS SEND TO GET; THE BUILDER KEEPS ITS METHOD AND BODY FOR REUSE
            String method = this.method;
            byte[] body = this.body;
            final List<URI> trace = new ArrayList<>();
            trace.add(current);
            final long deadline = this.deadlineMs > 0 ? System.nanoTime() + this.deadlineMs * 1_000_000L : 0L;

            while (true) {
                // EVERY HOP IS CHECKED, SO A REDIRECT CAN NEITHER DOWNGRADE A VERIFIED TRANSFER NOR OUTLIVE ITS DEADLINE
                if (this.httpsOnly && (!"https".equalsIgnoreCase(current.getScheme()) || current.getHost() == null || current.getUserInfo() != null))
                    throw new IOException("HTTPS without credentials is required: " + current);
                if (deadline > 0 && System.nanoTime() > deadline) throw new IOException("Transfer deadline exceeded: " + this.uri);
                final HttpHeaders effective = this.materializeHeaders(current);
                final URLConnection conn = this.openConnection(current, effective, method, body);

                if (!(conn instanceof final HttpURLConnection http)) {
                    // FORCE A REAL CONNECT FOR FILE:// AND FTP:// SO MISSING RESOURCES FAIL FAST.
                    // URLConnection.getContentType() ONLY GUESSES FROM THE FILENAME AND NEVER
                    // TOUCHES THE UNDERLYING RESOURCE — WITHOUT THIS CALL A NONEXISTENT file://
                    // URI WOULD SILENTLY LOOK LIKE A VALID MEDIA RESPONSE TO CALLERS LIKE MRL.
                    conn.connect();
                    return new NetRequest(current, conn, HttpURLConnection.HTTP_OK, this.maxBytes, deadline);
                }

                final int code = http.getResponseCode();
                if (!isRedirect(code)) {
                    return new NetRequest(current, http, code, this.maxBytes, deadline);
                }

                if (trace.size() - 1 >= this.maxRedirects) {
                    http.disconnect();
                    // THE HOPS ARE OFTEN SIGNED CDN LINKS, SO THEY ONLY REACH THE REDACTING LOGGER, NEVER THE EXCEPTION
                    final StringBuilder chain = new StringBuilder();
                    for (int i = 0; i < trace.size(); i++) {
                        if (i > 0) chain.append("\n  -> ");
                        chain.append('[').append(i).append("] ").append(trace.get(i));
                    }
                    LOGGER.debug(IT, "Redirect chain of {} exceeded {} hops:\n  {}", this.uri, this.maxRedirects, chain);
                    throw new IOException("Too many redirects (" + this.maxRedirects + " hops) for " + this.uri);
                }

                final String location = http.getHeaderField("Location");
                if (location == null || location.isEmpty()) {
                    return new NetRequest(current, http, code, this.maxBytes, deadline);
                }

                final URI next;
                try {
                    next = current.resolve(new URI(location));
                } catch (final URISyntaxException e) {
                    return new NetRequest(current, http, code, this.maxBytes, deadline);
                }

                http.disconnect();

                // ONLY THE INITIAL REQUEST MAY BE LOCAL: A SERVER-CHOSEN HOP OUTSIDE HTTP(S) WOULD READ LOCAL FILES OR ARCHIVES
                if (!IOTool.web(next))
                    throw new IOException("Redirect to unsupported scheme: " + next.getScheme() + " (from " + this.uri + ")");

                // RFC 9110: A 303 SEE OTHER MUST BE RE-REQUESTED AS GET WITHOUT THE ORIGINAL BODY
                if (code == HttpURLConnection.HTTP_SEE_OTHER) {
                    method = "GET";
                    body = null;
                } else if (body != null && body.length > 0 && !this.crossOrigin && !sameOrigin(this.uri, next)) {
                    throw new IOException("Redirect to another origin would resend the request body: " + next);
                }

                current = next;
                trace.add(current);
            }
        }

        private HttpHeaders materializeHeaders(final URI target) {
            final HttpHeaders out = new HttpHeaders(this.headers);
            // ACCEPT AND USER-AGENT ARE ALWAYS SEEDED IN THE CTOR; ONLY DERIVE A REFERER WHEN THE CALLER DID NOT SET ONE
            if (!out.has("Referer")) {
                final String referer = HttpHeaders.referer(target);
                if (referer != null) out.set("Referer", referer);
            }
            if (!sameOrigin(this.uri, target)) {
                out.removeAll("Authorization");
                out.removeAll("Proxy-Authorization");
                out.removeAll("Cookie");
                out.removeAll("Cookie2");
                out.removeAll(NetworkAPI.X_WATERMEDIA_TOKEN);
            }
            return out;
        }

        private URLConnection openConnection(final URI target, final HttpHeaders effective, final String method, final byte[] body) throws IOException {
            final URL url = target.toURL();
            final URLConnection conn = url.openConnection();
            conn.setConnectTimeout(this.connectTimeout);
            conn.setReadTimeout(this.readTimeout);

            if (conn instanceof final HttpURLConnection http) {
                http.setInstanceFollowRedirects(false); // MANUAL REDIRECTS ALLOW CROSS-PROTOCOL SWITCHES
                http.setRequestMethod(method);
                effective.writeTo(http);

                if (body != null && body.length > 0) {
                    http.setDoOutput(true);
                    http.setFixedLengthStreamingMode(body.length);
                    try (final OutputStream os = http.getOutputStream()) {
                        os.write(body);
                    }
                }
            }
            // FTP AND FILE URLS FALL THROUGH; HTTP-ONLY HEADERS AND METHOD DO NOT APPLY.
            return conn;
        }

        // CREDENTIALS AND BODIES BELONG TO AN ORIGIN: SCHEME, HOST AND EFFECTIVE PORT MUST ALL MATCH
        private static boolean sameOrigin(final URI origin, final URI target) {
            final int originPort = origin.getPort() != -1 ? origin.getPort() : ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80);
            final int targetPort = target.getPort() != -1 ? target.getPort() : ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80);
            return origin.getScheme() != null && origin.getScheme().equalsIgnoreCase(target.getScheme())
                    && origin.getHost() != null && origin.getHost().equalsIgnoreCase(target.getHost()) && originPort == targetPort;
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
