package org.watermedia.api.media.platform;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.media.MRL;

import java.net.URI;
import java.time.Instant;

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
     * @return delivery with available sources and expiration instant, null on fail
     */
    Result getSources(URI uri) throws Exception;


    /**
     * Result of the platform source finder (PSF)
     * @param expires Expiration instant of this result, null means it never expires
     * @param sources Array of all available sources
     */
    record Result(Instant expires, MRL.Source... sources) {
        public int size() {
            return this.sources.length;
        }
    }
}
