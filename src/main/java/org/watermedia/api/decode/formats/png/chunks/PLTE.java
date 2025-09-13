package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

// https://www.w3.org/TR/png/#11PLTE
public record PLTE(int color1, int color2, int color3) {
    public static final int SIGNATURE = 0x50_4C_54_45;

    public static PLTE read(ByteBuffer buffer) {
        int[] colors = new int[3];

        int i = 0;
        byte[] b = new byte[3];
        while (i < 3) {
            buffer.get(b);
            colors[i] = ((b[0] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[2] & 0xFF);
            i++;
        }

        return new PLTE(colors[0], colors[1], colors[2]);
    }
}