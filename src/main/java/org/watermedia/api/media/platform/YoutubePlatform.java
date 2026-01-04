package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;

import java.net.URI;

public class YoutubePlatform implements IPlatform {

    @Override
    public String name() {
        return "Youtube";
    }

    @Override
    public boolean validate(URI uri) {
        final var host = uri.getHost();
        return host != null && (host.endsWith("youtube.com") || host.endsWith("youtu.be"));
    }

    @Override
    public MRL.Source[] getSources(URI uri) throws Exception {
        throw new UnsupportedOperationException("YoutubePlatform.getSources is not implemented yet");
    }
}
