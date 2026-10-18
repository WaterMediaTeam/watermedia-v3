package org.watermedia.api.util;

import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
 * to callers. Credentials and request bodies stay with the original origin; a body is resent to
 * another origin only when set with {@code crossOrigin}, otherwise that redirect fails.
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
    /** Accept value for playable media and the HLS and DASH manifests that describe it. */
    public static final String ACCEPT_MEDIA = "audio/*, video/*, application/vnd.apple.mpegurl, application/x-mpegURL, application/dash+xml";
    /** Accept value for a JSON response. */
    public static final String ACCEPT_JSON = "application/json";
    private static volatile boolean mimeInstalled;

    /**
     * Extends {@link URLConnection#getFileNameMap()} with {@link MediaType#mime(String)}'s
     * extension table, so platform code can rely on
     * {@code URLConnection.guessContentTypeFromName(...)} returning a sensible value.
     * Idempotent: subsequent calls are no-ops. Invoked once during NetworkAPI startup.
     */
    public static synchronized void installExtraMimeTypes() {
        if (mimeInstalled) return;
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
        mimeInstalled = true;
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
    private final HttpHeaders responseHeaders;

    private NetRequest(final URI uri, final URLConnection connection, final int statusCode) {
        this.uri = uri;
        this.connection = connection;
        this.statusCode = statusCode;
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
    public static <T> T fetchJson(final String platform, final URI uri, final Class<T> type) throws PlatformException {
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
     * Convenience overload of {@link #fetchJson(String, URI, Class)} taking the endpoint as a string.
     *
     * @param platform the handler the request belongs to, used to tag failures
     * @param uri the API endpoint to request
     * @param type the class the JSON body binds to
     * @param <T> the bound type
     * @return the bound response, never {@code null}
     * @throws PlatformException when the request fails for any reason
     */
    public static <T> T fetchJson(final String platform, final String uri, final Class<T> type) throws PlatformException {
        final URI target;
        try { target = URI.create(uri); }
        catch (final IllegalArgumentException malformed) { throw new PlatformException(platform, "Malformed API URL " + uri, malformed); }
        return fetchJson(platform, target, type);
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
     * Raw response stream. Caller must close it (or {@link #close()} the request).
     */
    public InputStream inputStream() throws IOException {
        return this.connection.getInputStream();
    }

    /**
     * Reads the entire response as a UTF-8 string. Throws if the body exceeds
     * {@link WaterMediaConfig.Network#maxTextBytes}.
     */
    public String text() throws IOException {
        try (final InputStream is = this.inputStream()) {
            return new String(IOTool.readLimited(is, WaterMediaConfig.network.maxTextBytes, -1L), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses the response body as JSON and binds it to {@code type} through {@link JSONTool}.
     * Returns {@code null} when the {@code content-type} does not contain
     * {@code application/json}. Honors {@link WaterMediaConfig.Network#maxTextBytes}.
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
        if (ct == null || !ct.toLowerCase(Locale.ROOT).contains(MIMETYPE_JSON)) return null;
        try (final InputStream is = this.inputStream()) {
            return new String(IOTool.readLimited(is, WaterMediaConfig.network.maxTextBytes, -1L), StandardCharsets.UTF_8);
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

    public static final class Builder {
        private final URI uri;
        private String method = "GET";
        private UserAgent userAgent = UserAgent.WATERMEDIA;
        private final HttpHeaders headers = new HttpHeaders();
        private byte[] body;
        private boolean crossOrigin;
        private int connectTimeout = WaterMediaConfig.network.timeout;
        private int readTimeout = WaterMediaConfig.network.timeout;
        private int maxRedirects = WaterMediaConfig.network.maxRedirects;

        Builder(final URI uri) {
            this.uri = Objects.requireNonNull(uri, "uri cannot be null");
            this.headers.set("Accept", ACCEPT_ANY);
        }

        /** Sets the HTTP method, uppercased; the connection validates it on {@link #send()}. */
        public Builder method(final String method) { this.method = method.toUpperCase(Locale.ROOT); return this; }
        /** Replaces the {@code Content-Type} header. */
        public Builder contentType(final String contentType) { this.headers.set("Content-Type", contentType); return this; }
        /** Replaces the {@code Accept} header. */
        public Builder accept(final String accept) { this.headers.set("Accept", accept); return this; }
        /** Replaces the {@code Referer} header, overriding the derived default. */
        public Builder referer(final String referer) { this.headers.set("Referer", referer); return this; }
        /** Selects the User-Agent preset. */
        public Builder userAgent(final UserAgent userAgent) { this.userAgent = Objects.requireNonNull(userAgent); return this; }
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
        /** Sets the read timeout in milliseconds; zero waits forever and negatives fail on {@link #send()}. */
        public Builder readTimeout(final int ms) { this.readTimeout = ms; return this; }
        /** Sets how many redirects {@link #send()} may follow; zero or less rejects the first one. */
        public Builder maxRedirects(final int n) { this.maxRedirects = n; return this; }

        /**
         * Opens the connection, writes the body if any, and follows redirects manually,
         * including cross-protocol switches that {@link HttpURLConnection} silently refuses.
         * @throws IOException when the transfer fails, the method is rejected or the redirect chain exceeds {@link #maxRedirects}
         */
        public NetRequest send() throws IOException {
            URI current = this.uri;
            // A 303 SWITCHES ONLY THIS SEND TO GET; THE BUILDER KEEPS ITS METHOD AND BODY FOR REUSE
            String method = this.method;
            byte[] body = this.body;
            final List<URI> trace = new ArrayList<>();
            trace.add(current);

            while (true) {
                final HttpHeaders effective = this.materializeHeaders(current);
                final URLConnection conn = this.openConnection(current, effective, method, body);

                if (!(conn instanceof final HttpURLConnection http)) {
                    // FORCE A REAL CONNECT FOR FILE:// AND FTP:// SO MISSING RESOURCES FAIL FAST.
                    // URLConnection.getContentType() ONLY GUESSES FROM THE FILENAME AND NEVER
                    // TOUCHES THE UNDERLYING RESOURCE — WITHOUT THIS CALL A NONEXISTENT file://
                    // URI WOULD SILENTLY LOOK LIKE A VALID MEDIA RESPONSE TO CALLERS LIKE MRL.
                    conn.connect();
                    return new NetRequest(current, conn, HttpURLConnection.HTTP_OK);
                }

                final int code = http.getResponseCode();
                if (!isRedirect(code)) {
                    return new NetRequest(current, http, code);
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
                    return new NetRequest(current, http, code);
                }

                final URI next;
                try {
                    next = current.resolve(new URI(location));
                } catch (final URISyntaxException e) {
                    return new NetRequest(current, http, code);
                }

                http.disconnect();

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
            out.set("User-Agent", this.userAgent.value);
            // ACCEPT IS ALWAYS SEEDED IN THE CTOR; ONLY DERIVE A REFERER WHEN THE CALLER DID NOT SET ONE
            if (!out.has("Referer")) {
                final String host = target.getHost();
                if (host != null) out.set("Referer", target.getScheme() + "://" + host);
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
