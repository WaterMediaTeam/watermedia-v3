package org.watermedia.api.codecs;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
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

public class CodecsAPI extends WaterMediaAPI {
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
        IOException lastFailure = null;
        for (final ImageCodec imageCodec: IMAGE_CODECS) {
            if (imageCodec.supported(buffer)) {
                try {
                    return imageCodec.decode(buffer);
                } catch (final IOException e) {
                    // Try the next codec — a header match doesn't guarantee successful decode.
                    LOGGER.debug(IT, "{} declined to decode: {}", imageCodec.name(), e.getMessage());
                    lastFailure = e;
                }
            }
        }
        if (lastFailure != null) {
            LOGGER.error(IT, "All matching decoders failed: {}", lastFailure.getMessage(), lastFailure);
        } else {
            LOGGER.warn(IT, "No decoder found for buffer");
        }
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

    private List<ImageCodec> pendingCodecs;

    @Override
    public String name() {
        return CodecsAPI.class.getSimpleName();
    }

    @Override
    public void load(final WaterMedia instance) {
        this.pendingCodecs = new ArrayList<>();
        if (instance.clientSide) {
            this.pendingCodecs.add(new PNG());
            this.pendingCodecs.add(new JPEG());
            this.pendingCodecs.add(new GIF());
            this.pendingCodecs.add(new WEBP());
            this.pendingCodecs.add(new NETPBM());
        }
        this.steps = this.pendingCodecs.size();
        this.step = 0;
        this.stepName = "";
    }

    @Override
    public boolean start(final WaterMedia instance) {
        if (!instance.clientSide) {
            LOGGER.warn(IT, "Codecs API refuses to load on server-side");
            return false;
        }

        for (final ImageCodec codec : this.pendingCodecs) {
            this.step++;
            this.stepName = codec.getClass().getSimpleName();
            register(codec);
        }
        this.pendingCodecs = null;
        return true;
    }

    @Override
    public void release(final WaterMedia instance) {
        IMAGE_CODECS.clear();
        this.pendingCodecs = null;
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
    }
}
