package org.watermedia.api.platform.internal;

import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public final class WaterPlatform implements IPlatform {
    public static final String NAME = "WaterMedia Internal";
    public static final String HOST_LOCAL = "local";
    public static final String HOST_REMOTE = "remote";
    public static final String HOST_GLOBAL = "global";

    public static final String GLOBAL_SERVER = "https://watermedia.srrapero720.me/";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!"water".equals(uri.getScheme())) return null;

        final String resolved = toHttpURL(uri);
        final URI resolvedUri = new URI(resolved);
        final var entry = new DataSource(MediaType.UNKNOWN, null, null,
                RequestHeaders.defaults(uri),
                List.of(new DataQuality(resolvedUri, 0, 0)),
                null, null);
        return new PlatformData(null, entry);
    }

    public static String toHttpURL(final URI u) throws IOException {
        final String host = u.getHost();
        String path = u.getPath();

        if (host == null) throw new PlatformException(WaterPlatform.class, "water:// URL requires a host (local, remote, global)");

        return switch (host) {
            case HOST_LOCAL -> {
                if (path.startsWith("/")) path = path.substring(1);
                final Path file = WaterMedia.cwd().resolve(path);
                yield file.toUri().toString();
            }
            case HOST_REMOTE -> {
                // VALIDATE THE CONFIG UP FRONT: A NULL/BLANK OR NON-ABSOLUTE remoteHost WOULD OTHERWISE
                // YIELD A SCHEME-LESS URI THAT ONLY FAILS MUCH LATER, FAR FROM THE ACTUAL CAUSE
                String base = WaterMediaConfig.network.remoteHost;
                if (base == null || base.isBlank() || !(base.startsWith("http://") || base.startsWith("https://")))
                    throw new PlatformException(WaterPlatform.class, "network.remoteHost is not configured (expected an absolute http(s) URL): " + base);
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                yield base + path;
            }
            case HOST_GLOBAL -> {
                if (path.startsWith("/")) path = path.substring(1);
                yield GLOBAL_SERVER + path;
            }
            default -> throw new PlatformException(WaterPlatform.class, "Unknown water:// host: " + host + " (expected: local, remote, global)");
        };
    }
}
