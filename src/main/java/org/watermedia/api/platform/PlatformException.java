package org.watermedia.api.platform;

import java.io.IOException;

public class PlatformException extends IOException {
    public PlatformException(final Class<? extends IPlatform> platform, final String message) {
        super(platform.getSimpleName() + ": " + message);
    }

    public PlatformException(final Class<? extends IPlatform> platform, final String message, Throwable cause) {
        super(platform.getSimpleName() + ": " + message, cause);
    }
}
