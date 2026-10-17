package org.watermedia.api.platform;

import java.io.IOException;

/** Failure of a platform to resolve a URI it claimed; the message is prefixed with the platform name. */
public class PlatformException extends IOException {
    /** Creates a failure of the platform called {@code platform} described by {@code message}. */
    public PlatformException(final String platform, final String message) {
        super(platform + ": " + message);
    }

    /** Creates a failure of the platform called {@code platform} described by {@code message} and caused by {@code cause}. */
    public PlatformException(final String platform, final String message, final Throwable cause) {
        super(platform + ": " + message, cause);
    }
}
