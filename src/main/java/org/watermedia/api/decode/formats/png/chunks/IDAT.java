package org.watermedia.api.decode.formats.png.chunks;

public record IDAT(byte[] data) {
    public static final int SIGNATURE = 0x49_44_41_54;
}
