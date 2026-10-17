package org.watermedia.api.codecs.common.png;

// https://www.w3.org/TR/png/#11IEND
public record IEND() implements IChunk {
    public static final int SIGNATURE = 0x49_45_4E_44;

    @Override
    public byte[] toBytes() {
        return new byte[0];
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
