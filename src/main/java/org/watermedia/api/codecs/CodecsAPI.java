package org.watermedia.api.codecs;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.decoders.GIF;
import org.watermedia.api.codecs.decoders.JPEG;
import org.watermedia.api.codecs.decoders.NETPBM;
import org.watermedia.api.codecs.decoders.PNG;
import org.watermedia.api.codecs.decoders.WEBP;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class CodecsAPI {
    private static final Marker IT = MarkerManager.getMarker(CodecsAPI.class.getSimpleName());
    private static final List<ImageCodec> IMAGE_CODECS = new ArrayList<>();

    /**
     * @see #decodeImage(ByteBuffer)
     * @param data buffer data array
     * @return a {@link ImageData} instance with a raw decoded image, the resulting buffers are in BGRA
     */
    public static ImageData decodeImage(final byte[] data) {
        return decodeImage(ByteBuffer.wrap(data));
    }

    /**
     * @see #decodeImage(ByteBuffer)
     * @param buffer buffer data
     * @return a {@link ImageData} instance with a raw decoded image, the resulting buffers are in BGRA
     */
    public static ImageData decodeImage(final ByteBuffer buffer) {
        for (final ImageCodec imageCodec: IMAGE_CODECS) {
            if (imageCodec.supported(buffer)) {
                try {
                    return imageCodec.decode(buffer);
                } catch (final IOException e) {
                    // Log the exception or handle it as needed
                    LOGGER.error(IT, "Decoding failed: {}",e.getMessage(), e);
                }
            }
        }
        LOGGER.warn(IT, "No decoder founded");
        return null;
    }

    public static void register(ImageCodec imageCodec) {
        if (imageCodec == null) {
            LOGGER.error(IT, "Ignoring register of null Decoder");
            return;
        }

        LOGGER.info(IT, "Registering {}", imageCodec.getClass().getSimpleName());
        IMAGE_CODECS.add(imageCodec);
    }

    public static boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Decoder API refuses to load on server-side");
            return false;
        }

        register(new PNG());
        register(new JPEG());
        register(new GIF());
        register(new WEBP());
        register(new NETPBM());
        return true;
    }
}
