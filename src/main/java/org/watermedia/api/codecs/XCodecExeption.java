package org.watermedia.api.codecs;

public class XCodecExeption extends Exception {
    public XCodecExeption(final Class<?> codec, final String message) {
        super("Exception in: " + codec.getSimpleName() + " - " + message);
    }

  public XCodecExeption(final Class<?> codec, final String message, final Throwable cause) {
    super("Exception in: " + codec.getSimpleName() + " - " + message,  cause);
  }
}