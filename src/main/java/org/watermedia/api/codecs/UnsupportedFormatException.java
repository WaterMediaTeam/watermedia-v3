package org.watermedia.api.codecs;

import java.nio.ByteBuffer;

/**
 * Thrown by {@link CodecsAPI#decodeImage(ByteBuffer)} when the leading bytes
 * of the stream don't match any supported image format.
 */
public final class UnsupportedFormatException extends XCodecException {
    public UnsupportedFormatException(final String message) {
        super(message);
    }

    public UnsupportedFormatException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
