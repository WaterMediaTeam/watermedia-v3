package org.watermedia.api.codecs;

import java.io.IOException;

public class XCodecException extends IOException {
    public XCodecException() {
        super();
    }

    public XCodecException(String message) {
        super(message);
    }

    public XCodecException(String message, Throwable cause) {
        super(message, cause);
    }

    public XCodecException(Throwable cause) {
        super(cause);
    }
}
