package org.watermedia.api.decode;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.decode.formats.gif.GIF;
import org.watermedia.api.decode.formats.jpeg.JPEG;
import org.watermedia.api.decode.formats.png.PNG;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class DecoderAPI {
    private static final Marker IT = MarkerManager.getMarker(DecoderAPI.class.getSimpleName());
    private static final List<Decoder> DECODERS = new ArrayList<>();

    /**
     * @see #decodeImage(ByteBuffer)
     * @param data buffer data array
     * @return a {@link Image} instance with a raw decoded image, the resulting buffers are in BGRA
     */
    public static Image decodeImage(final byte[] data) {
        return decodeImage(ByteBuffer.wrap(data));
    }

    /**
     * @see #decodeImage(ByteBuffer)
     * @param buffer buffer data
     * @return a {@link Image} instance with a raw decoded image, the resulting buffers are in BGRA
     */
    public static Image decodeImage(final ByteBuffer buffer) {
        for (final Decoder decoder: DECODERS) {
            if (decoder.supported(buffer)) {
                try {
                    return decoder.decode(buffer);
                } catch (final IOException e) {
                    // Log the exception or handle it as needed
                    LOGGER.error(IT, "Decoding failed: {}",e.getMessage(), e);
                }
            }
        }
        LOGGER.warn(IT, "No decoder founded");
        return null;
    }

    public static void register(Decoder decoder) {
        if (decoder == null) {
            LOGGER.error(IT, "Ignoring register of null Decoder");
            return;
        }
        if (!decoder.test()) {
            LOGGER.error(IT, "Failed to register decoder {}, self-test failed", decoder.name());
            return;
        }

        LOGGER.info(IT, "Registering {}", decoder.getClass().getSimpleName());
        DECODERS.add(decoder);
    }

    public static boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Decoder API refuses to load on server-side");
            return false;
        }

        register(new PNG());
        register(new JPEG());
        register(new GIF());
        return true;
    }
}
