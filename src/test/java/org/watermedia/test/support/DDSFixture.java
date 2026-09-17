package org.watermedia.test.support;

import org.watermedia.api.codecs.CodecsAPI;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds DDS byte streams for container and BC reader tests. */
public final class DDSFixture {
    private static final int HEADER_BYTES = 148;
    private static final int FOOTER_MAGIC = 0x574D5443;

    private DDSFixture() {}

    public static byte[] write(final int width, final int height, final String codec, final int arraySize) {
        final int format = switch (codec) {
            case CodecsAPI.CODEC_BC1 -> 71;
            case CodecsAPI.CODEC_BC3 -> 77;
            case CodecsAPI.CODEC_BC7 -> 98;
            default -> throw new IllegalArgumentException("Unsupported BC fixture: " + codec);
        };
        final int blockBytes = format == 71 ? 8 : 16;
        final int frameBytes = Math.toIntExact((((long) width + 3) / 4) * (((long) height + 3) / 4) * blockBytes);
        // WRITE THE DDS AND DX10 PREFIX IN LITTLE-ENDIAN CONTAINER ORDER.
        final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x20534444);
        header.putInt(124);
        header.putInt(0x1 | 0x2 | 0x4 | 0x1000 | 0x80000);
        header.putInt(height);
        header.putInt(width);
        header.putInt(frameBytes);
        header.putInt(0);
        header.putInt(1);
        header.position(header.position() + 44);
        header.putInt(32);
        header.putInt(0x4);
        header.putInt(0x30315844);
        header.position(header.position() + 20);
        header.putInt(0x1000);
        header.position(header.position() + 16);
        header.putInt(format);
        header.putInt(3);
        header.putInt(0);
        header.putInt(arraySize);
        header.putInt(0);
        return header.array();
    }

    public static byte[] writeFooter(final long... delays) {
        final ByteBuffer footer = ByteBuffer.allocate(12 + delays.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        footer.putInt(FOOTER_MAGIC).putInt(1).putInt(delays.length);
        for (final long delay: delays) footer.putLong(delay);
        return footer.array();
    }
}
