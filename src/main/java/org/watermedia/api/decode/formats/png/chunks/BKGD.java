package org.watermedia.api.decode.formats.png.chunks;

public record BKGD(int gray, int red, int green, int blue, byte index) {
    public BKGD(int gray) {
        this(gray, -1, -1, -1, (byte) 0x00);
    }

    public BKGD(int red, int green, int blue) {
        this(-1, red, green, blue, (byte) 0x00);
    }

    public BKGD(byte index) {
        this(-1, -1, -1, -1, (byte) 0x00);
    }
}
