package org.watermedia.api.network;

import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Path;

/**
 * URLStreamHandler for the {@code water://} protocol.
 * Converts water:// URIs into actual connections:
 * <ul>
 *     <li>{@code water://local/<path>} - file in CWD (same as {@code new File("")})</li>
 *     <li>{@code water://remote/<id>} - remote server specified in config, requires token</li>
 *     <li>{@code water://global/<id>} - fixed global read-only server</li>
 * </ul>
 */
public class WaterStreamHandler extends URLStreamHandler {
    public static final String HOST_LOCAL = "local";
    public static final String HOST_REMOTE = "remote";
    public static final String HOST_GLOBAL = "global";

    // FIXED WORLDWIDE READ-ONLY SERVER (DOWNLOAD ONLY, NO UPLOADS)
    public static final String GLOBAL_SERVER = "https://watermedia.srrapero720.me/";

    @Override
    protected URLConnection openConnection(final URL u) throws IOException {
        final String host = u.getHost();
        final String path = u.getPath();

        if (host == null) throw new IOException("water:// URL requires a host (local, remote, global)");

        return switch (host) {
            case HOST_LOCAL -> openLocal(path);
            case HOST_REMOTE -> openRemote(path);
            case HOST_GLOBAL -> openGlobal(path);
            default -> throw new IOException("Unknown water:// host: " + host + " (expected: local, remote, global)");
        };
    }

    public static String toHttpURL(final URI u) throws IOException {
        final String host = u.getHost();
        String path = u.getPath();

        if (host == null) throw new IOException("water:// URL requires a host (local, remote, global)");

        return switch (host) {
            case HOST_LOCAL -> {
                if (path.startsWith("/")) path = path.substring(1);
                final Path file = WaterMedia.cwd().resolve(path);
                yield file.toUri().toURL().toString();
            }
            case HOST_REMOTE -> {
                String base = WaterMediaConfig.network.remoteHost;
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                yield base + path;
            }
            case HOST_GLOBAL -> {
                if (path.startsWith("/")) path = path.substring(1);
                yield GLOBAL_SERVER + path;
            }
            default -> throw new IOException("Unknown water:// host: " + host + " (expected: local, remote, global)");
        };
    }

    // WATER://LOCAL/SOME/PATH/FILE.PNG → FILE IN CWD
    private static URLConnection openLocal(String path) throws IOException {
        if (path.startsWith("/")) path = path.substring(1);
        final Path file = WaterMedia.cwd().resolve(path);
        return file.toUri().toURL().openConnection();
    }

    // WATER://REMOTE/ABC123 → HTTP(S)://CONFIGURED-SERVER/ABC123
    private static URLConnection openRemote(final String path) throws IOException {
        String base = WaterMediaConfig.network.remoteHost;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        final URL remoteURL = new URL(base + path);
        final URLConnection conn = remoteURL.openConnection();
        conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
        conn.setRequestProperty(NetworkAPI.X_WATERMEDIA_TOKEN, WaterMediaConfig.network.token);
        return conn;
    }

    // WATER://GLOBAL/ABC123 → HTTP(S)://GLOBAL-SERVER/ABC123 (READ-ONLY)
    private static URLConnection openGlobal(final String path) throws IOException {
        final URL globalURL = new URL(GLOBAL_SERVER + path);
        final URLConnection conn = globalURL.openConnection();
        conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
        return conn;
    }
}
