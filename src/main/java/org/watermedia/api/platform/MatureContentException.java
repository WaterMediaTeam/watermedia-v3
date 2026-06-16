package org.watermedia.api.platform;

public class MatureContentException extends PlatformException {
    public MatureContentException(final Class<? extends IPlatform> platform, final String message) {
        super(platform, message);
    }

    public MatureContentException(final Class<? extends IPlatform> platform, final String message, final Throwable cause) {
        super(platform, message, cause);
    }
}
