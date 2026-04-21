package org.watermedia.api.codecs.decoders.netpbm.decoders;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmDecoder;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PGMDecoder implements NetpbmDecoder {

    @Override
    public ImageData decode(final ByteBuffer data, final NetpbmHeader header) throws XCodecException {
        final int width = header.width;
        final int height = header.height;
        final int maxVal = header.maxVal;

        final ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
        frame.order(ByteOrder.nativeOrder());

        try {
            this.decodeGrayscale(data, width, height, 1, maxVal, frame);
        } catch (final Exception e) {
            throw new XCodecException("Failed to decode PGM image", e);
        }

        frame.flip();

        return new ImageData(new ByteBuffer[] { frame }, header.width, header.height, ImageData.NO_DELAY, ImageData.NO_REPEAT);
    }
}
