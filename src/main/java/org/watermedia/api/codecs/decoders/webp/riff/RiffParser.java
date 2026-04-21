package org.watermedia.api.codecs.decoders.webp.riff;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class RiffParser {

    private RiffParser() {
    }

    // CHECK IF BUFFER STARTS WITH VALID WEBP HEADER
    public static boolean isWebP(final ByteBuffer buffer) {
        if (buffer.remaining() < 12) return false;
        final int pos = buffer.position();
        try {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            final int riff = buffer.getInt(pos);
            final int webp = buffer.getInt(pos + 8);
            return riff == RiffChunk.RIFF && webp == RiffChunk.WEBP;
        } catch (final Exception e) {
            return false;
        }
    }

    // RETURNS NULL IF NOT A VALID WEBP (FOR supported() CHECK)
    // THROWS DecoderException IF WEBP IS CORRUPTED
    public static WebPInfo parse(ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 12) return null;

        buffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);

        // READ RIFF HEADER: 'RIFF' + size + 'WEBP'
        final int riff = buffer.getInt();
        if (riff != RiffChunk.RIFF) return null;

        final int fileSize = buffer.getInt();
        final int webp = buffer.getInt();
        if (webp != RiffChunk.WEBP) return null;

        // AT THIS POINT WE KNOW IT'S A WEBP - ANY FAILURE IS CORRUPTION
        if (buffer.remaining() < 8) {
            throw new XCodecException("Truncated WebP: missing chunk header");
        }

        final RiffChunk firstChunk = readChunk(buffer, 12);

        return switch (firstChunk.fourCC()) {
            case RiffChunk.VP8 -> parseLossy(buffer, firstChunk, fileSize);
            case RiffChunk.VP8L -> parseLossless(buffer, firstChunk, fileSize);
            case RiffChunk.VP8X -> parseExtended(buffer, firstChunk, fileSize);
            default ->
                    throw new XCodecException("Unknown WebP format: " + RiffChunk.fourCCString(firstChunk.fourCC()));
        };
    }

    private static RiffChunk readChunk(final ByteBuffer buffer, final int offset) throws XCodecException {
        if (offset + 8 > buffer.limit()) {
            throw new XCodecException("Truncated WebP: chunk header at offset " + offset);
        }
        final int fourCC = buffer.getInt(offset);
        final int size = buffer.getInt(offset + 4);
        return new RiffChunk(fourCC, size, offset + 8);
    }

    private static WebPInfo parseLossy(final ByteBuffer buffer, final RiffChunk vp8Chunk, final int fileSize) throws XCodecException {
        // VP8 FRAME HEADER CONTAINS DIMENSIONS
        // UNCOMPRESSED CHUNK: 3 bytes tag + 7 bytes key frame header
        final int dataOffset = vp8Chunk.dataOffset();
        if (vp8Chunk.size() < 10) {
            throw new XCodecException("VP8 chunk too small: " + vp8Chunk.size() + " bytes");
        }

        // READ FRAME TAG (3 BYTES)
        final int b0 = buffer.get(dataOffset) & 0xFF;
        final int b1 = buffer.get(dataOffset + 1) & 0xFF;
        final int b2 = buffer.get(dataOffset + 2) & 0xFF;

        final int frameTag = b0 | (b1 << 8) | (b2 << 16);
        final boolean isKeyFrame = (frameTag & 1) == 0;

        if (!isKeyFrame) {
            throw new XCodecException("VP8 must start with key frame");
        }

        // KEY FRAME: CHECK START CODE 0x9D 0x01 0x2A
        final int sync0 = buffer.get(dataOffset + 3) & 0xFF;
        final int sync1 = buffer.get(dataOffset + 4) & 0xFF;
        final int sync2 = buffer.get(dataOffset + 5) & 0xFF;

        if (sync0 != 0x9D || sync1 != 0x01 || sync2 != 0x2A) {
            throw new XCodecException("Invalid VP8 start code");
        }

        // READ DIMENSIONS (LITTLE ENDIAN)
        final int widthScale = buffer.getShort(dataOffset + 6) & 0xFFFF;
        final int heightScale = buffer.getShort(dataOffset + 8) & 0xFFFF;

        final int width = widthScale & 0x3FFF;
        final int height = heightScale & 0x3FFF;

        return WebPInfo.lossy(width, height, vp8Chunk, fileSize);
    }

    private static WebPInfo parseLossless(final ByteBuffer buffer, final RiffChunk vp8lChunk, final int fileSize) throws XCodecException {
        // VP8L HEADER: 1 byte signature (0x2F) + 4 bytes with dimensions
        final int dataOffset = vp8lChunk.dataOffset();
        if (vp8lChunk.size() < 5) {
            throw new XCodecException("VP8L chunk too small: " + vp8lChunk.size() + " bytes");
        }

        final int signature = buffer.get(dataOffset) & 0xFF;
        if (signature != 0x2F) {
            throw new XCodecException("Invalid VP8L signature: 0x" + Integer.toHexString(signature));
        }

        // READ 32 BITS: width(14) + height(14) + alpha_hint(1) + version(3)
        final int bits = buffer.getInt(dataOffset + 1);
        final int width = (bits & 0x3FFF) + 1;
        final int height = ((bits >> 14) & 0x3FFF) + 1;
        final boolean alphaUsed = ((bits >> 28) & 1) == 1;
        final int version = (bits >> 29) & 7;

        if (version != 0) {
            throw new XCodecException("Unsupported VP8L version: " + version);
        }

        return WebPInfo.lossless(width, height, alphaUsed, vp8lChunk, fileSize);
    }

    private static WebPInfo parseExtended(final ByteBuffer buffer, final RiffChunk vp8xChunk, final int fileSize) throws XCodecException {
        // VP8X CHUNK: 10 bytes
        // FLAGS(1) + RESERVED(3) + CANVAS_WIDTH(3) + CANVAS_HEIGHT(3)
        final int dataOffset = vp8xChunk.dataOffset();
        if (vp8xChunk.size() < 10) {
            throw new XCodecException("VP8X chunk too small: " + vp8xChunk.size() + " bytes");
        }

        final int flags = buffer.get(dataOffset) & 0xFF;

        // FLAGS: Rsv(2) | ICC(1) | Alpha(1) | Exif(1) | XMP(1) | Anim(1) | Rsv(1)
        final boolean hasICC = (flags & 0x20) != 0;
        final boolean hasAlpha = (flags & 0x10) != 0;
        final boolean hasExif = (flags & 0x08) != 0;
        final boolean hasXMP = (flags & 0x04) != 0;
        final boolean hasAnimation = (flags & 0x02) != 0;

        // CANVAS SIZE (24-BIT LITTLE ENDIAN, MINUS ONE ENCODED)
        final int w0 = buffer.get(dataOffset + 4) & 0xFF;
        final int w1 = buffer.get(dataOffset + 5) & 0xFF;
        final int w2 = buffer.get(dataOffset + 6) & 0xFF;
        final int width = (w0 | (w1 << 8) | (w2 << 16)) + 1;

        final int h0 = buffer.get(dataOffset + 7) & 0xFF;
        final int h1 = buffer.get(dataOffset + 8) & 0xFF;
        final int h2 = buffer.get(dataOffset + 9) & 0xFF;
        final int height = (h0 | (h1 << 8) | (h2 << 16)) + 1;

        // SCAN FOR REQUIRED CHUNKS
        RiffChunk bitstreamChunk = null;
        RiffChunk alphaChunk = null;
        RiffChunk animChunk = null;

        int offset = vp8xChunk.nextOffset();
        while (offset + 8 <= buffer.limit()) {
            final RiffChunk chunk;
            try {
                chunk = readChunk(buffer, offset);
            } catch (final XCodecException e) {
                break; // END OF CHUNKS
            }

            switch (chunk.fourCC()) {
                case RiffChunk.VP8, RiffChunk.VP8L -> {
                    if (bitstreamChunk == null) bitstreamChunk = chunk;
                }
                case RiffChunk.ALPH -> {
                    if (alphaChunk == null) alphaChunk = chunk;
                }
                case RiffChunk.ANIM -> {
                    if (animChunk == null) animChunk = chunk;
                }
                // SKIP ICCP, EXIF, XMP, ANMF FOR NOW
            }

            offset = chunk.nextOffset();
        }

        // FOR NON-ANIMATED IMAGES, BITSTREAM IS REQUIRED
        if (bitstreamChunk == null && !hasAnimation) {
            throw new XCodecException("Missing VP8/VP8L bitstream in extended WebP");
        }

        return new WebPInfo(
                WebPInfo.Type.EXTENDED,
                width, height,
                hasAlpha, hasAnimation, hasICC, hasExif, hasXMP,
                bitstreamChunk, alphaChunk, animChunk,
                fileSize
        );
    }

    // PARSE ANIMATION FRAMES FROM EXTENDED FORMAT
    public static java.util.List<AnimFrame> parseFrames(ByteBuffer buffer, final WebPInfo info) {
        if (!info.hasAnimation() || info.animChunk() == null) {
            return java.util.List.of();
        }

        buffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        final java.util.List<AnimFrame> frames = new java.util.ArrayList<>();

        // READ ANIM CHUNK: background_color(4) + loop_count(2)
        final int animOffset = info.animChunk().dataOffset();
        final int bgColor = buffer.getInt(animOffset);
        final int loopCount = buffer.getShort(animOffset + 4) & 0xFFFF;

        // SCAN FOR ANMF CHUNKS
        int offset = info.animChunk().nextOffset();
        while (offset + 8 <= buffer.limit()) {
            final RiffChunk chunk;
            try {
                chunk = readChunk(buffer, offset);
            } catch (final XCodecException e) {
                break; // END OF CHUNKS
            }

            if (chunk.fourCC() == RiffChunk.ANMF) {
                final AnimFrame frame = parseAnimFrame(buffer, chunk);
                frames.add(frame);
            }

            offset = chunk.nextOffset();
        }

        return frames;
    }

    private static AnimFrame parseAnimFrame(final ByteBuffer buffer, final RiffChunk anmfChunk) {
        // ANMF: frameX(3) + frameY(3) + width(3) + height(3) + duration(3) + flags(1) + data...
        final int off = anmfChunk.dataOffset();

        final int frameX = (buffer.get(off) & 0xFF) | ((buffer.get(off + 1) & 0xFF) << 8) | ((buffer.get(off + 2) & 0xFF) << 16);
        final int frameY = (buffer.get(off + 3) & 0xFF) | ((buffer.get(off + 4) & 0xFF) << 8) | ((buffer.get(off + 5) & 0xFF) << 16);
        final int frameW = ((buffer.get(off + 6) & 0xFF) | ((buffer.get(off + 7) & 0xFF) << 8) | ((buffer.get(off + 8) & 0xFF) << 16)) + 1;
        final int frameH = ((buffer.get(off + 9) & 0xFF) | ((buffer.get(off + 10) & 0xFF) << 8) | ((buffer.get(off + 11) & 0xFF) << 16)) + 1;
        final int duration = (buffer.get(off + 12) & 0xFF) | ((buffer.get(off + 13) & 0xFF) << 8) | ((buffer.get(off + 14) & 0xFF) << 16);
        final int flags = buffer.get(off + 15) & 0xFF;

        final boolean blend = (flags & 0x02) == 0;  // 0 = alpha blend, 1 = no blend
        final boolean dispose = (flags & 0x01) == 1; // 1 = dispose to background

        // FRAME DATA STARTS AT OFFSET + 16
        final int dataOffset = off + 16;
        final int dataSize = anmfChunk.size() - 16;

        return new AnimFrame(frameX * 2, frameY * 2, frameW, frameH, duration, blend, dispose, dataOffset, dataSize);
    }

    public record AnimFrame(
            int x, int y, int width, int height,
            int duration,
            boolean blend, boolean dispose,
            int dataOffset, int dataSize
    ) {
    }
}
