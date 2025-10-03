package org.watermedia.api.decode;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ServiceLoader;

import static org.watermedia.WaterMedia.LOGGER;

public class DecoderAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(DecoderAPI.class.getSimpleName());
    private static ServiceLoader<Decoder> SERVICE;

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
        for (final Decoder decoder: SERVICE) {
            if (decoder.supported(buffer)) {
                try {
                    return decoder.decode(buffer);
                } catch (IOException e) {
                    // Log the exception or handle it as needed
                    LOGGER.error(IT, "Decoding failed: {}",e.getMessage(), e);
                }
            }
        }
        LOGGER.warn(IT, "No decoder founded");
        return null;
    }

    @Override
    public boolean start(WaterMedia instance) throws Exception {
        LOGGER.info(IT, "Loading image decoders");
        SERVICE = ServiceLoader.load(Decoder.class);
        for (final Decoder decoder: SERVICE) {
            LOGGER.info(IT, "Testing {}", decoder.getClass().getSimpleName());
            if (!decoder.test()) {
                LOGGER.debug(IT, "Decoder {} fail test", decoder.getClass().getSimpleName());
            }
        }
        LOGGER.info(IT, "All decoders loaded");
        return true;
    }

    @Override
    public boolean onlyClient() {
        LOGGER.warn(IT, "DecoderAPI is not client-side only, but is recommended to be used only in client-side");
        return false;
    }

    @Override
    public void test() {

    }

    @Override
    public Priority priority() {
        return Priority.NORMAL;
    }

    @Override
    public void release(WaterMedia instance) {
        SERVICE = null;
    }
}
