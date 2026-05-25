package org.watermedia.api.codecs.readers.netpbm.decoders;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.readers.netpbm.NetpbmDecoder;
import org.watermedia.api.codecs.readers.netpbm.NetpbmHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PGMDecoder implements NetpbmDecoder {

    @Override
    public ImageData decode(final ByteBuffer data, final NetpbmHeader header) throws XCodecException {
        final int width = header.width;
        final int height = header.height;

        final ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
        frame.order(ByteOrder.nativeOrder());
        this.decodeInto(data, header, frame);

        return new ImageData(new ByteBuffer[] { frame }, header.width, header.height, ImageData.NO_DELAY, ImageData.NO_REPEAT);
    }

    @Override
    public void decodeInto(final ByteBuffer data, final NetpbmHeader header, final ByteBuffer frame) throws XCodecException {
        frame.clear();
        try {
            this.decodeGrayscale(data, header.width, header.height, 1, header.maxVal, frame);
        } catch (final Exception e) {
            throw new XCodecException("Failed to decode PGM image", e);
        }
        frame.flip();
    }
}
