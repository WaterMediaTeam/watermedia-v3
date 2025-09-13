package org.watermedia.api.decode.formats.png.chunks;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// https://www.w3.org/TR/png/#acTL-chunk
public record ACTL(int frameCount, int loopCount) {
    public static final int SIGNATURE = 0x61_63_54_4C; // Animation Control Chunk

    public static ACTL read(ByteBuffer buffer) {
        // CHUNK header: length (4 bytes), type (4 bytes)
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for ACTL: " + Integer.toHexString(type));
        }

        final int frameCount = buffer.getInt();
        final int loopCount = buffer.getInt();

        return new ACTL(frameCount, loopCount);
    }

    public static ACTL convert(CHUNK chunk, ByteOrder order) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for ACTL: " + Integer.toHexString(chunk.type()));
        }
        byte[] data = chunk.data();

        int frameCount = DataTool.toInt(data[0], data[1], data[2], data[3], order);
        int loopCount = DataTool.toInt(data[4], data[5], data[6], data[7], order);

        return new ACTL(frameCount, loopCount);
    }
}
