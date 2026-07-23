package org.watermedia.api.codecs;

import java.io.IOException;

/** Sealed root of the codec exception hierarchy, thrown on malformed or unsupported image data. */
public sealed class XCodecException extends IOException permits UnsupportedFormatException {
    public XCodecException() {
        super();
    }

    public XCodecException(final String message) {
        super(message);
    }

    public XCodecException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public XCodecException(final Throwable cause) {
        super(cause);
    }
}
