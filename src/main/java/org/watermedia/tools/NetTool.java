package org.watermedia.tools;

import org.watermedia.WaterMedia;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;

import static java.net.HttpURLConnection.*;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_LENGTH_REQUIRED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

public class NetTool {

    public static HttpURLConnection connectToHTTP(final URI uri, final String method, final String accept) throws IOException {
        final URL url = uri.toURL();
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
        conn.setRequestProperty("Accept", accept);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    public static URLConnection connectToURI(final URI uri) throws IOException {
        try {
            final URL url = uri.toURL();
            final URLConnection conn = url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
            return conn;
        } catch (final MalformedURLException e) {
            return null;
        }
    }

    public static boolean validateHTTP200(final int code, final URI uri) throws IOException {
        switch (code) {
            case HTTP_OK, HTTP_NOT_MODIFIED, -1 -> {
                return true; // DO NOTHING
            }
            case HTTP_MOVED_TEMP, HTTP_MOVED_PERM -> {
                return false; // REDIRECTION SHOULD BE HANDLED OUTSIDE
            }
            case HTTP_REQ_TOO_LONG -> throw new IOException("Request take too long to:" + uri);
            case HTTP_BAD_REQUEST -> throw new IOException("Bad request to: " + uri);
            case HTTP_UNAUTHORIZED -> throw new IOException("Unauthorized access to: " + uri);
            case HTTP_FORBIDDEN -> throw new IOException("Forbidden access to: " + uri);
            case HTTP_NOT_FOUND -> throw new IOException("Media not found: " + uri);
            case HTTP_LENGTH_REQUIRED -> throw new IOException("Length required for: " + uri);
            case HTTP_INTERNAL_ERROR -> throw new IOException("Internal server error for: " + uri);
            case HTTP_UNAVAILABLE -> throw new IOException("Service unavailable for: " + uri);
            case HTTP_GATEWAY_TIMEOUT -> throw new IOException("Gateway timeout for: " + uri);
            default -> throw new IOException("Unexpected response code: " + code);
        }
    }

    public static class Request implements AutoCloseable {
        private final URL url;
        private final String method;
        private final String body;
        private final URLConnection connection;

        public Request(final URL url, final String method, final String body) throws IOException {
            this.url = url;
            this.method = method;
            this.body = body;
            this.connection = url.openConnection();
        }

        public URL getUrl() {
            return this.url;
        }

        public String getMethod() {
            return this.method;
        }

        public String getBody() {
            return this.body;
        }

        public String getContentType() {
            return this.connection.getContentType();
        }

        public int getResponseCode() throws IOException {
            if (this.connection instanceof final HttpURLConnection http) {
                return http.getResponseCode();
            } else {
                return -1;
            }
        }

        public InputStream getInputStream() throws IOException {
            return this.connection.getInputStream();
        }

        @Override
        public void close() {
            if (this.connection instanceof final HttpURLConnection http) {
                http.disconnect();
            }
        }
    }
}
