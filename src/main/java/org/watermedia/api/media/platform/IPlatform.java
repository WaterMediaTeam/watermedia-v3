package org.watermedia.api.media.platform;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.media.MRL;

import java.net.URI;

public interface IPlatform {
    Marker IT = MarkerManager.getMarker("IPlatform");

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
    MRL.Source[] getSources(URI uri) throws Exception;
}
