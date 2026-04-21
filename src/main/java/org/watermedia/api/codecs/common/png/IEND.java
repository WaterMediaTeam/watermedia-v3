package org.watermedia.api.codecs.common.png;

// https://www.w3.org/TR/png/#11IEND
public record IEND() {
    public static final int SIGNATURE = 0x49_45_4E_44;

    public byte[] toBytes() {
        return new byte[0];
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
