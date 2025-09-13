package org.watermedia.api.decode.formats.png.chunks;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// https://www.w3.org/TR/png/#fcTL-chunk
public record FCTL(int seq, int width, int height, int xOffset, int yOffset, short delay, short delay_den, byte dispose, byte blend) {

    public static final int SIGNATURE = 0x66_63_54_4c; // Frame control chunk

    public static FCTL read(ByteBuffer buffer) {
        // CHUNK header: length (4 bytes), type (4 bytes)
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for FCTL: " + Integer.toHexString(type));
        }

        ByteOrder order = buffer.order();
        return new FCTL(
                buffer.getInt(),
                buffer.getInt(),
                buffer.getInt(),
                buffer.getInt(),
                buffer.getInt(),
                buffer.getShort(),
                buffer.getShort(),
                buffer.get(),
                buffer.get()
        );
    }

    public static FCTL convert(CHUNK chunk, ByteOrder order) {
        byte[] data = chunk.data();
        return new FCTL(
                DataTool.toInt(data[0], data[1], data[2], data[3], order),
                DataTool.toInt(data[4], data[5], data[6], data[7], order),
                DataTool.toInt(data[8], data[9], data[10], data[11], order),
                DataTool.toInt(data[12], data[13], data[14], data[15], order),
                DataTool.toInt(data[16], data[17], data[18], data[19], order),
                DataTool.toShort(data[20], data[21], order),
                DataTool.toShort(data[22], data[23], order),
                data[24],
                data[25]
        );
    }
}
