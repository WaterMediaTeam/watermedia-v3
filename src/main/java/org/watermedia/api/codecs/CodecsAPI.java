package org.watermedia.api.codecs;

import org.watermedia.WaterMedia;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public class CodecsAPI {
    public static final byte[] PNG_SIGNATURE = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    public static AudioReader openAudio(InputStream in) {
        return null; // TODO
    }

    public static ImageWriter writeImage(OutputStream ou, Class<? extends ImageWriter> format) {
        return null; // TODO
    }

    public static ImageReader openImage(InputStream in) {
        return null; // TODO
    }


    public static void start(WaterMedia instance) {
        Objects.requireNonNull(instance, "WaterMedia cannot be null");

        // REGISTER READERS

        // REGISTER WRITTERS
    }

}
