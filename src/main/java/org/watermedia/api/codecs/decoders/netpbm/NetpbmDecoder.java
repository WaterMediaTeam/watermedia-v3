package org.watermedia.api.codecs.decoders.netpbm;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageData;

import java.nio.ByteBuffer;

public interface NetpbmDecoder {
    ImageData decode(ByteBuffer data, NetpbmHeader header) throws XCodecException;

    default int readSample(final ByteBuffer data, final int maxVal) {
        return maxVal < 256 ? (data.get() & 0xFF) : (data.getShort() & 0xFFFF);
    }

    default void decodeBW(final ByteBuffer data, final int width, final int height, final int depth, final int maxVal, final ByteBuffer frame, final boolean flip) {
        if (maxVal != 1) throw new IllegalArgumentException("black and white images must have maxVal of 1");

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int value = this.readSample(data, maxVal); // FIRST SAMPLE

                // CONSUME REMAINING PLANES IF DEPTH > 1
                for (int d = 1; d < depth; d++) this.readSample(data, maxVal);

                final int v = (value == (flip ? 1 : 0)) ? 0 : 255;
                frame.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
            }
        }
    }

    default void decodeGrayscale(final ByteBuffer data, final int width, final int height, final int depth, final int maxVal, final ByteBuffer frame) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = this.readSample(data, maxVal);
                // SKIP EXTRA PLANES IF ANY
                for (int d = 1; d < depth; d++) this.readSample(data, maxVal);
                gray = (gray * 255) / maxVal;
                frame.put((byte) gray).put((byte) gray).put((byte) gray).put((byte) 255);
            }
        }
    }

    default void decodeColor(final ByteBuffer data, final int width, final int height, final int depth, final int maxVal, final ByteBuffer frame, final boolean hasAlpha) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = this.readSample(data, maxVal);
                int g = (depth >= 2) ? this.readSample(data, maxVal) : 0;
                int b = (depth >= 3) ? this.readSample(data, maxVal) : 0;
                int a = hasAlpha && (depth >= 4) ? this.readSample(data, maxVal) : 255;

                // SKIP EXTRA PLANES
                for (int d = (hasAlpha ? 4: 3); d < depth; d++) this.readSample(data, maxVal);

                r = (r * 255) / maxVal;
                g = (g * 255) / maxVal;
                b = (b * 255) / maxVal;
                a = (a * 255) / maxVal;

                frame.put((byte) b).put((byte) g).put((byte) r).put((byte) 255).put((byte) a);
            }
        }
    }
}
