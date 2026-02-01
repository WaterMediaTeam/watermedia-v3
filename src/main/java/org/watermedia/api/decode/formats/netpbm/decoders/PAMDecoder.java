package org.watermedia.api.decode.formats.netpbm.decoders;

import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.formats.netpbm.NetpbmDecoder;
import org.watermedia.api.decode.formats.netpbm.NetpbmHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PAMDecoder implements NetpbmDecoder {

    @Override
    public Image decode(final ByteBuffer data, final NetpbmHeader header) throws DecoderException {
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
                default -> throw new DecoderException("Unsupported PAM tuple type: " + tupleType);
            }
        } catch (final Exception e) {
            throw new DecoderException("Failed to decode PAM image", e);
        }

        frame.flip();
        return new Image(new ByteBuffer[] { frame }, width, height, Image.NO_DELAY, Image.NO_REPEAT);
    }
}
