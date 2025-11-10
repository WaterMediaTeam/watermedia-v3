package org.watermedia.api.media.sources;

import org.watermedia.api.media.MRL;

import java.net.URI;

public class WaterPlatform implements IPlatform {
    @Override
    public String name() {
        return "WaterMedia Platform";
    }

    @Override
    public boolean validate(URI uri) {
        return "water".equals(uri.getScheme());
    }

    @Override
    public MRL[] getSources(URI uri) {
        // TODO: support for "local" which is the runtime path and "online" which is the current server (if has one)
        return new MRL[0];
    }
}
