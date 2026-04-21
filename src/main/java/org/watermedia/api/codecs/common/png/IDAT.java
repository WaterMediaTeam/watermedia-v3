package org.watermedia.api.codecs.common.png;

public record IDAT(byte[] data) {
    public static final int SIGNATURE = 0x49_44_41_54;

    public byte[] toBytes() {
        return this.data.clone();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.data);
    }
}
