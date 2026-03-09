package org.watermedia.api.codecs;

import java.io.IOException;

public class XCodecExeption extends IOException {
    public XCodecExeption(final Class<?> codec, final String message) {
        super("Exception in: " + codec.getSimpleName() + " - " + message);
    }

  public XCodecExeption(final Class<?> codec, final String message, final Throwable cause) {
    super("Exception in: " + codec.getSimpleName() + " - " + message,  cause);
  }
}