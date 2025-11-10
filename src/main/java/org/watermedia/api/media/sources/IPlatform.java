package org.watermedia.api.media.sources;

import org.watermedia.api.media.MRL;

import java.net.URI;
import java.util.Map;

public interface IPlatform {

    /**
     * Provides the former platform name
     */
    String name();

    /**
     * Checks if the URI can be provided of sources using this platform
     * @param uri uri to validate
     * @return if this platform can provide of sources, false otherwise
     */
    boolean validate(URI uri);

    /**
     * Gives all the available sources and its potential qualities.
     * @param uri
     * @return available sources, null if nothing was found
     */
    MRL[] getSources(URI uri);
}
