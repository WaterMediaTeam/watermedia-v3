package org.watermedia.api.decode.formats.png.chunks;

// https://www.w3.org/TR/png/#11IEND
public record IEND() {
    public static final int SIGNATURE = 0x49_45_4E_44;
}
