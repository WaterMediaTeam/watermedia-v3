package org.watermedia.api.codecs.common.png;

public record IDAT(byte[] data) implements IChunk {
    public static final int SIGNATURE = 0x49_44_41_54;

    @Override
    public byte[] toBytes() {
        return this.data.clone();
    }

    @Override
    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.data);
    }
}
