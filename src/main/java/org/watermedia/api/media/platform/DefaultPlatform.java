package org.watermedia.api.media.platform;

import org.watermedia.api.media.MRL;

import java.net.URI;

public class DefaultPlatform implements IPlatform {
    @Override
    public String name() {
        return "Default";
    }

    @Override
    public boolean validate(URI uri) {
        return true; // DEFAULT
    }

    @Override
    public MRL.Source[] getSources(final URI uri) {
        final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.UNKNOWN);
        sourceBuilder.quality(MRL.Quality.UNKNOWN, uri);

        return new MRL.Source[] {
                sourceBuilder.build()
        };
    }
}
