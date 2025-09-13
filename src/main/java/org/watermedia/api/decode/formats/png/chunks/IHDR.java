package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

// https://www.w3.org/TR/png/#11IHDR
public record IHDR(int width, int height, int depth, int colorType, int compression, int filter, int interlace) {
    public static final int SIGNATURE = 0x49_48_44_52;

    public static IHDR read(ByteBuffer buffer) {

        return new IHDR(
                buffer.getInt(),
                buffer.getInt(),
                buffer.get(),
                buffer.get(),
                buffer.get(),
                buffer.get(),
                buffer.get()
        );
    }
}
