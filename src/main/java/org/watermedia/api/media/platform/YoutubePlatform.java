package org.watermedia.api.media.platform;

import java.net.URI;

public class YoutubePlatform implements IPlatform {
    public static final String NAME = "YouTube";

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final var host = uri.getHost();
        return host != null && (host.endsWith("youtube.com") || host.endsWith("youtu.be"));
    }

    @Override
    public Result getSources(final URI uri) throws Exception {
        throw new UnsupportedOperationException("YoutubePlatform.getSources is not implemented yet");
    }
}
