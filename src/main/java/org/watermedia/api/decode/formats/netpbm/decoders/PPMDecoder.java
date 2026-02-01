package org.watermedia.api.decode.formats.netpbm.decoders;

import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.formats.netpbm.NetpbmDecoder;
import org.watermedia.api.decode.formats.netpbm.NetpbmHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PPMDecoder implements NetpbmDecoder {

    @Override
    public Image decode(final ByteBuffer data, final NetpbmHeader header) throws DecoderException {
        final int width = header.width;
        final int height = header.height;
        final int maxVal = header.maxVal;

        final ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
        frame.order(ByteOrder.nativeOrder());

        try {
            this.decodeColor(data, width, height, 3, maxVal, frame, false);
        } catch (final Exception e) {
            throw new DecoderException("Failed to decode PPM image", e);
        }

        frame.flip();

        return new Image(new ByteBuffer[] { frame }, header.width, header.height, Image.NO_DELAY, Image.NO_REPEAT);
    }
}
