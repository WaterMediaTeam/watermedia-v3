package org.watermedia.api.decode.formats.png.chunks;

import java.nio.ByteBuffer;

// https://www.w3.org/TR/png/#11tRNS
public record TRNS(int gray, int red, int green, int blue, byte[] alphaPerPalette) {
    public static final int SIGNATURE = 0x74_52_4E_53;

    public TRNS(int red, int green, int blue) {
        this(-1, red, green, blue, new byte[0]);
    }

    public TRNS(int gray) {
        this(gray, -1, -1, -1, new byte[0]);
    }

    public TRNS(byte[] alphaPerPalette) {
        this(-1, -1, -1, -1, alphaPerPalette);
    }

    public static TRNS read(int colorType, int size, ByteBuffer buffer) {
        return null;
    }
}
