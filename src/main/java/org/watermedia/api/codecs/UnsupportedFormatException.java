package org.watermedia.api.codecs;

/**
 * Thrown by {@link CodecsAPI#decodeImage(java.nio.ByteBuffer)} when the leading bytes
 * of the stream don't match any supported image format.
 */
public class UnsupportedFormatException extends XCodecException {
    public UnsupportedFormatException(final String message) {
        super(message);
    }

    public UnsupportedFormatException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
