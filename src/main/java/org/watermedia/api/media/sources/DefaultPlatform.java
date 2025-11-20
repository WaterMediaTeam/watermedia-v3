package org.watermedia.api.media.sources;

import org.watermedia.api.media.MRL;

import java.net.URI;
import java.util.Map;

public class DefaultPlatform implements IPlatform {
    @Override
    public String name() {
        return "Default";
    }

    @Override
    public boolean validate(URI uri) {
        return true;
    }

    @Override
    public MRL[] getSources(URI uri) {
        return new MRL[] {
                new MRL(MRL.MediaType.UNKNOWN, Map.of(MRL.MediaQuality.HIGHEST, uri))
        };
    }
}
