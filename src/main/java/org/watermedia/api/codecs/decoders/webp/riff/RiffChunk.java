package org.watermedia.api.codecs.decoders.webp.riff;

public record RiffChunk(int fourCC, int size, int dataOffset) {

    // FOURCC CONSTANTS
    public static final int RIFF = 0x46464952; // 'RIFF'
    public static final int WEBP = 0x50424557; // 'WEBP'
    public static final int VP8 = 0x20385056; // 'VP8 '
    public static final int VP8L = 0x4C385056; // 'VP8L'
    public static final int VP8X = 0x58385056; // 'VP8X'
    public static final int ANIM = 0x4D494E41; // 'ANIM'
    public static final int ANMF = 0x464D4E41; // 'ANMF'
    public static final int ALPH = 0x48504C41; // 'ALPH'
    public static final int ICCP = 0x50434349; // 'ICCP'
    public static final int EXIF = 0x46495845; // 'EXIF'
    public static final int XMP = 0x20504D58; // 'XMP '

    public static String fourCCString(final int fourCC) {
        return new String(new char[]{
                (char) (fourCC & 0xFF),
                (char) ((fourCC >> 8) & 0xFF),
                (char) ((fourCC >> 16) & 0xFF),
                (char) ((fourCC >> 24) & 0xFF)
        });
    }

    // CHUNK SIZE INCLUDES PADDING TO EVEN BOUNDARY
    public int paddedSize() {
        return (this.size + 1) & ~1;
    }

    // OFFSET TO NEXT CHUNK
    public int nextOffset() {
        return this.dataOffset + this.paddedSize();
    }
}
