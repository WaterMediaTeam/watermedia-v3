package org.watermedia.api.codecs.decoders.netpbm.decoders;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmDecoder;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PAMDecoder implements NetpbmDecoder {

    @Override
    public ImageData decode(final ByteBuffer data, final NetpbmHeader header) throws XCodecException {
        final int width = header.width;
        final int height = header.height;
        final int depth = header.depth;
        final int maxVal = header.maxVal;
        final String tupleType = header.tuplType != null ? header.tuplType : "";

        final ByteBuffer frame = ByteBuffer.allocateDirect(width * height * 4);
        frame.order(ByteOrder.nativeOrder());

        try {
            switch (tupleType) {
                case "BLACKANDWHITE" -> this.decodeBW(data, width, height, depth, maxVal, frame, false);
                case "GRAYSCALE" -> this.decodeGrayscale(data, width, height, depth, maxVal, frame);
                case "RGB" -> this.decodeColor(data, width, height, depth, maxVal, frame, false);
                case "RGB_ALPHA" -> this.decodeColor(data, width, height, depth, maxVal, frame, true);
                default -> throw new XCodecException("Unsupported PAM tuple type: " + tupleType);
            }
        } catch (final Exception e) {
            throw new XCodecException("Failed to decode PAM image", e);
        }

        frame.flip();
        return new ImageData(new ByteBuffer[] { frame }, width, height, ImageData.NO_DELAY, ImageData.NO_REPEAT);
    }
}
