package org.watermedia.api.platform.internal;

import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public class WaterPlatform implements IPlatform {
    public static final String HOST_LOCAL = "local";
    public static final String HOST_REMOTE = "remote";
    public static final String HOST_GLOBAL = "global";

    public static final String GLOBAL_SERVER = "https://watermedia.srrapero720.me/";

    @Override
    public String name() {
        return "WaterMedia Internal";
    }

    @Override
    public boolean validate(final URI uri) {
        return "water".equals(uri.getScheme());
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final String resolved = toHttpURL(uri);
        final URI resolvedUri = new URI(resolved);
        final var entry = new DataSource(MediaType.UNKNOWN, null, null,
                RequestHeaders.defaults(uri),
                new DataQuality[] {new DataQuality(resolvedUri, 0, 0)},
                null, null);
        return new PlatformData(null, entry);
    }

    public static String toHttpURL(final URI u) throws IOException {
        final String host = u.getHost();
        String path = u.getPath();

        if (host == null) throw new IOException("water:// URL requires a host (local, remote, global)");

        return switch (host) {
            case HOST_LOCAL -> {
                if (path.startsWith("/")) path = path.substring(1);
                final Path file = WaterMedia.cwd().resolve(path);
                yield file.toUri().toString();
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
}
