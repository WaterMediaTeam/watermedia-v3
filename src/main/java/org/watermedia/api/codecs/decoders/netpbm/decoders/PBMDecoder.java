package org.watermedia.api.codecs.decoders.netpbm.decoders;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmDecoder;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PBMDecoder implements NetpbmDecoder {

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
            for (int y = 0; y < header.height; y++) {
                int packed = 0;
                for (int x = 0; x < header.width; x++) {
                    if ((x & 7) == 0) packed = data.get() & 0xFF;
                    final int bit = (packed >> (7 - (x & 7))) & 1;
                    final int v = bit == 1 ? 0 : 255;
                    frame.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
                }
            }
        } catch (final Exception e) {
            throw new XCodecException("Failed to decode PBM image", e);
        }
        frame.flip();
    }
}
