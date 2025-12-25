package org.watermedia.api.decode;

import java.io.IOException;

public class DecoderException extends IOException {
    public DecoderException() {
        super();
    }

    public DecoderException(String message) {
        super(message);
    }

    public DecoderException(String message, Throwable cause) {
        super(message, cause);
    }

    public DecoderException(Throwable cause) {
        super(cause);
    }
}
