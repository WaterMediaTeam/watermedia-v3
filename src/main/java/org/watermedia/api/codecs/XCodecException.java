package org.watermedia.api.codecs;

import java.io.IOException;

public class XCodecException extends IOException {
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
