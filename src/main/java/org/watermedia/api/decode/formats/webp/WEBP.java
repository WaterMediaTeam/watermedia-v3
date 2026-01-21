package org.watermedia.api.decode.formats.webp;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.decode.Decoder;
import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.formats.webp.common.BitReader;
import org.watermedia.api.decode.formats.webp.lossless.VP8LDecoder;
import org.watermedia.api.decode.formats.webp.lossy.VP8LossyDecoder;
import org.watermedia.api.decode.formats.webp.riff.RiffChunk;
import org.watermedia.api.decode.formats.webp.riff.RiffParser;
import org.watermedia.api.decode.formats.webp.riff.WebPInfo;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class WEBP extends Decoder {
    private static final Marker IT = MarkerManager.getMarker(WEBP.class.getSimpleName());

    @Override
    public boolean supported(final ByteBuffer buffer) {
        LOGGER.debug(IT, "Checking if buffer is WebP format");
        final boolean isWebP = RiffParser.isWebP(buffer);
        LOGGER.debug(IT, "WebP format check result: {}", isWebP);
        return isWebP;
    }

    @Override
    public Image decode(ByteBuffer buffer) throws DecoderException {
        LOGGER.debug(IT, "decode() called with buffer of size: {}", buffer.remaining());
        final WebPInfo info = RiffParser.parse(buffer);
        if (info == null) {
            throw new DecoderException("Failed to parse WebP");
        }
        LOGGER.debug(IT, "Parsed WebP info - width: {}, height: {}, hasAnimation: {}, hasAlpha: {}",
                info.width(), info.height(), info.hasAnimation(), info.alphaChunk() != null);

        buffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);

        if (info.hasAnimation()) {
            LOGGER.debug(IT, "Decoding animated WebP");
            return this.decodeAnimation(buffer, info);
        } else {
            LOGGER.debug(IT, "Decoding static WebP");
            LOGGER.debug(IT, "decodeStatic() called - dimensions: {}x{}", info.width(), info.height());
            // USE EFFICIENT PATH - DECODE DIRECTLY TO BGRA BYTEBUFFER
            final ByteBuffer frame = this.decodeFrameToBgra(buffer, info.bitstreamChunk(), info.alphaChunk(), info.width(), info.height());
            LOGGER.debug(IT, "decodeStatic complete - created ByteBuffer of {} bytes", frame.capacity());

            return new Image(new ByteBuffer[]{frame}, info.width(), info.height(), new long[]{0L}, 1L, Image.NO_REPEAT
            );
        }
    }

    @Override
    public boolean test() {
        return true;
    }

    private Image decodeAnimation(final ByteBuffer buffer, final WebPInfo info) throws DecoderException {
        LOGGER.debug(IT, "decodeAnimation() called - canvas: {}x{}", info.width(), info.height());
        final List<RiffParser.AnimFrame> animFrames = RiffParser.parseFrames(buffer, info);

        if (animFrames.isEmpty()) {
            LOGGER.debug(IT, "No animation frames found!");
            throw new DecoderException("No animation frames found");
        }
        LOGGER.debug(IT, "Parsed {} animation frames", animFrames.size());

        // READ ANIM CHUNK FOR LOOP COUNT
        int loopCount = 0;
        if (info.animChunk() != null) {
            final int animOffset = info.animChunk().dataOffset();
            // SKIP BACKGROUND COLOR (4 BYTES)
            loopCount = buffer.getShort(animOffset + 4) & 0xFFFF;
            LOGGER.debug(IT, "Animation loop count: {}", loopCount);
        }

        final int canvasWidth = info.width();
        final int canvasHeight = info.height();
        LOGGER.debug(IT, "Canvas dimensions: {}x{}", canvasWidth, canvasHeight);

        final ByteBuffer[] frames = new ByteBuffer[animFrames.size()];
        final long[] delays = new long[animFrames.size()];

        // CANVAS FOR COMPOSITING
        final int[] canvas = new int[canvasWidth * canvasHeight];
        LOGGER.debug(IT, "Canvas allocated - size: {}", canvas.length);

        for (int i = 0; i < animFrames.size(); i++) {
            final RiffParser.AnimFrame af = animFrames.get(i);
            LOGGER.debug(IT, "Processing frame {} of {} - x: {}, y: {}, size: {}x{}, duration: {}ms, blend: {}, dispose: {}",
                    i + 1, animFrames.size(), af.x(), af.y(), af.width(), af.height(), af.duration(), af.blend(), af.dispose());

            // DECODE FRAME DATA (CONTAINS VP8/VP8L + OPTIONAL ALPH)
            final int[] frameBgra = this.decodeAnimFrameToBgra(buffer, af);
            LOGGER.debug(IT, "Frame {} decoded ({} pixels)", i, frameBgra.length);

            // COMPOSITE ONTO CANVAS
            this.composite(canvas, canvasWidth, canvasHeight, frameBgra, af);
            LOGGER.debug(IT, "Frame {} composited onto canvas", i);

            // WRAP CANVAS CLONE AS BYTEBUFFER (ZERO REORDERING - INT LAYOUT IS ALREADY BGRA)
            frames[i] = DataTool.bgraToBuffer(canvas.clone());
            delays[i] = af.duration();

            // DISPOSE IF NEEDED
            if (af.dispose()) {
                // CLEAR FRAME REGION TO TRANSPARENT
                this.clearRegion(canvas, canvasWidth, af.x(), af.y(), af.width(), af.height());
                LOGGER.debug(IT, "Cleared frame {} region for disposal", i);
            }
        }

        final int repeat = (loopCount == 0) ? Image.REPEAT_FOREVER : loopCount;
        LOGGER.debug(IT, "Animation decoding complete - {} frames, repeat count: {}", animFrames.size(), repeat);

        return new Image(frames, canvasWidth, canvasHeight, delays, repeat);
    }

    // DECODE FRAME DIRECTLY TO BGRA BYTEBUFFER (EFFICIENT PATH - NO INTERMEDIATE ARGB ARRAY FOR PURE FORMATS)
    private ByteBuffer decodeFrameToBgra(final ByteBuffer buffer, final RiffChunk bitstreamChunk,
                                          final RiffChunk alphaChunk, final int width, final int height) throws DecoderException {
        LOGGER.debug(IT, "decodeFrameToBgra() called - dimensions: {}x{}, bitstreamChunk: {}, alphaChunk: {}",
                width, height, bitstreamChunk != null, alphaChunk != null);
        if (bitstreamChunk == null) {
            LOGGER.debug(IT, "No bitstream chunk found!");
            throw new DecoderException("No bitstream chunk");
        }

        if (bitstreamChunk.fourCC() == RiffChunk.VP8L) {
            // LOSSLESS - DECODE DIRECTLY TO BGRA
            LOGGER.debug(IT, "Detected VP8L (lossless) format - chunk size: {}", bitstreamChunk.size());
            final ByteBuffer vp8lData = this.sliceChunk(buffer, bitstreamChunk);
            // SKIP VP8L SIGNATURE (1 BYTE) AND HEADER (4 BYTES)
            vp8lData.position(5);
            LOGGER.debug(IT, "VP8L data prepared, remaining bytes: {}", vp8lData.remaining());
            final BitReader reader = new BitReader(vp8lData);
            final ByteBuffer result = VP8LDecoder.decodeToBgra(reader, width, height);
            LOGGER.debug(IT, "VP8L decoding complete - {} bytes", result.capacity());
            return result;
        } else if (alphaChunk == null) {
            // LOSSY WITHOUT ALPHA - DECODE DIRECTLY TO BGRA (MOST EFFICIENT PATH)
            LOGGER.debug(IT, "Detected VP8 (lossy) format without alpha - chunk size: {}", bitstreamChunk.size());
            final ByteBuffer vp8Data = this.sliceChunk(buffer, bitstreamChunk);
            LOGGER.debug(IT, "VP8 data prepared, remaining bytes: {}", vp8Data.remaining());
            final ByteBuffer result = VP8LossyDecoder.decodeToBgra(vp8Data, width, height);
            LOGGER.debug(IT, "VP8 decoding complete - {} bytes", result.capacity());
            return result;
        } else {
            // LOSSY WITH ALPHA - DECODE TO BGRA THEN APPLY ALPHA IN-PLACE
            LOGGER.debug(IT, "Detected VP8 (lossy) format with alpha - chunk size: {}", bitstreamChunk.size());
            final ByteBuffer vp8Data = this.sliceChunk(buffer, bitstreamChunk);
            LOGGER.debug(IT, "VP8 data prepared, remaining bytes: {}", vp8Data.remaining());
            final ByteBuffer result = VP8LossyDecoder.decodeToBgra(vp8Data, width, height);
            LOGGER.debug(IT, "VP8 decoding complete - {} bytes", result.capacity());

            // APPLY ALPHA IN-PLACE ON THE BGRA BUFFER (NO EXTRA ALLOCATION)
            LOGGER.debug(IT, "Alpha channel present - chunk size: {}", alphaChunk.size());
            final ByteBuffer alphaData = this.sliceChunk(buffer, alphaChunk);
            final byte[] alpha = AlphaDecoder.decode(alphaData, width, height);
            LOGGER.debug(IT, "Alpha decoded - {} bytes", alpha.length);
            AlphaDecoder.applyAlpha(result, alpha);
            LOGGER.debug(IT, "Alpha applied in-place on BGRA buffer");
            return result;
        }
    }

    // DECODE FRAME TO ARGB INT ARRAY (USED FOR ANIMATION COMPOSITING)
    private int[] decodeFrameToBgraArray(final ByteBuffer buffer, final RiffChunk bitstreamChunk,
                                     final RiffChunk alphaChunk, final int width, final int height) throws DecoderException {
        LOGGER.debug(IT, "decodeFrameToBgraArray() called - dimensions: {}x{}, bitstreamChunk: {}, alphaChunk: {}",
                width, height, bitstreamChunk != null, alphaChunk != null);
        if (bitstreamChunk == null) {
            LOGGER.debug(IT, "No bitstream chunk found!");
            throw new DecoderException("No bitstream chunk");
        }

        final int[] argb;

        if (bitstreamChunk.fourCC() == RiffChunk.VP8L) {
            // LOSSLESS
            LOGGER.debug(IT, "Detected VP8L (lossless) format - chunk size: {}", bitstreamChunk.size());
            final ByteBuffer vp8lData = this.sliceChunk(buffer, bitstreamChunk);
            // SKIP VP8L SIGNATURE (1 BYTE) AND HEADER (4 BYTES)
            vp8lData.position(5);
            LOGGER.debug(IT, "VP8L data prepared, remaining bytes: {}", vp8lData.remaining());
            final BitReader reader = new BitReader(vp8lData);
            argb = VP8LDecoder.decode(reader, width, height);
            LOGGER.debug(IT, "VP8L decoding complete - decoded {} pixels", argb.length);
        } else {
            // LOSSY
            LOGGER.debug(IT, "Detected VP8 (lossy) format - chunk size: {}", bitstreamChunk.size());
            final ByteBuffer vp8Data = this.sliceChunk(buffer, bitstreamChunk);
            LOGGER.debug(IT, "VP8 data prepared, remaining bytes: {}", vp8Data.remaining());
            argb = VP8LossyDecoder.decode(vp8Data, width, height);
            LOGGER.debug(IT, "VP8 decoding complete - decoded {} pixels", argb.length);

            // APPLY ALPHA IF PRESENT
            if (alphaChunk != null) {
                LOGGER.debug(IT, "Alpha channel present - chunk size: {}", alphaChunk.size());
                final ByteBuffer alphaData = this.sliceChunk(buffer, alphaChunk);
                final byte[] alpha = AlphaDecoder.decode(alphaData, width, height);
                LOGGER.debug(IT, "Alpha decoded - {} bytes", alpha.length);
                AlphaDecoder.applyAlpha(argb, alpha);
                LOGGER.debug(IT, "Alpha channel applied");
            }
        }

        LOGGER.debug(IT, "decodeFrameToBgraArray complete");
        return argb;
    }

    private int[] decodeAnimFrameToBgra(final ByteBuffer buffer, final RiffParser.AnimFrame af) throws DecoderException {
        LOGGER.debug(IT, "decodeAnimFrameToBgra() called - frame offset: {}, data size: {}", af.dataOffset(), af.dataSize());
        // ANMF DATA CONTAINS SUB-CHUNKS (ALPH + VP8/VP8L)
        // Create a slice of just the frame data
        ByteBuffer frameData = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        frameData.position(af.dataOffset());
        frameData.limit(af.dataOffset() + af.dataSize());
        frameData = frameData.slice().order(ByteOrder.LITTLE_ENDIAN);
        LOGGER.debug(IT, "Frame data buffer created - size: {}, remaining: {}", af.dataSize(), frameData.remaining());

        RiffChunk alphaChunk = null;
        RiffChunk bitstreamChunk = null;

        // Parse chunks within the frame data (offsets are relative to frameData, starting at 0)
        int offset = 0;
        int chunksFound = 0;
        while (offset + 8 <= frameData.limit()) {
            final int fourCC = frameData.getInt(offset);
            final int size = frameData.getInt(offset + 4);
            LOGGER.debug(IT, "Found chunk at offset {} - fourCC: 0x{}, size: {}", offset, Integer.toHexString(fourCC), size);

            // dataOffset is relative to frameData (starts at offset + 8)
            final RiffChunk chunk = new RiffChunk(fourCC, size, offset + 8);

            if (fourCC == RiffChunk.ALPH) {
                alphaChunk = chunk;
                LOGGER.debug(IT, "Alpha chunk found at offset {}", offset);
            } else if (fourCC == RiffChunk.VP8 || fourCC == RiffChunk.VP8L) {
                bitstreamChunk = chunk;
                LOGGER.debug(IT, "Bitstream chunk found - format: {}", fourCC == RiffChunk.VP8 ? "VP8 (lossy)" : "VP8L (lossless)");
            }

            offset += 8 + ((size + 1) & ~1);
            chunksFound++;
        }
        LOGGER.debug(IT, "Frame parsing complete - chunks found: {}, alpha: {}, bitstream: {}",
                chunksFound, alphaChunk != null, bitstreamChunk != null);

        // NOW DECODE USING FRAMEDATA BUFFER (CHUNKS HAVE OFFSETS RELATIVE TO IT)
        return this.decodeFrameToBgraArray(frameData, bitstreamChunk, alphaChunk, af.width(), af.height());
    }

    private void composite(final int[] canvas, final int canvasWidth, final int canvasHeight,
                           final int[] frame, final RiffParser.AnimFrame af) {
        LOGGER.debug(IT, "Compositing frame - canvas: {}x{}, frame rect: ({},{}) {}x{}, blend: {}",
                canvasWidth, canvasHeight, af.x(), af.y(), af.width(), af.height(), af.blend());
        int pixelsComposited = 0;
        int pixelsSkipped = 0;
        for (int y = 0; y < af.height(); y++) {
            final int canvasY = af.y() + y;
            if (canvasY < 0 || canvasY >= canvasHeight) {
                pixelsSkipped += af.width();
                continue;
            }

            for (int x = 0; x < af.width(); x++) {
                final int canvasX = af.x() + x;
                if (canvasX < 0 || canvasX >= canvasWidth) {
                    pixelsSkipped++;
                    continue;
                }

                final int canvasPos = canvasY * canvasWidth + canvasX;
                final int framePos = y * af.width() + x;
                final int srcBgra = frame[framePos];

                if (af.blend()) {
                    // ALPHA BLENDING
                    canvas[canvasPos] = this.alphaBlend(canvas[canvasPos], srcBgra);
                } else {
                    // DIRECT REPLACE
                    canvas[canvasPos] = srcBgra;
                }
                pixelsComposited++;
            }
        }
        LOGGER.debug(IT, "Compositing complete - composited: {}, skipped: {}", pixelsComposited, pixelsSkipped);
    }

    private int alphaBlend(final int dst, final int src) {
        final int srcA = (src >> 24) & 0xFF;
        if (srcA == 255) {
            return src;
        }
        if (srcA == 0) {
            return dst;
        }

        final int dstA = (dst >> 24) & 0xFF;
        final int srcR = (src >> 16) & 0xFF;
        final int srcG = (src >> 8) & 0xFF;
        final int srcB = src & 0xFF;
        final int dstR = (dst >> 16) & 0xFF;
        final int dstG = (dst >> 8) & 0xFF;
        final int dstB = dst & 0xFF;

        final int outA = srcA + (dstA * (255 - srcA) / 255);
        if (outA == 0) {
            LOGGER.debug(IT, "alphaBlend - result fully transparent");
            return 0;
        }

        final int outR = (srcR * srcA + dstR * dstA * (255 - srcA) / 255) / outA;
        final int outG = (srcG * srcA + dstG * dstA * (255 - srcA) / 255) / outA;
        final int outB = (srcB * srcA + dstB * dstA * (255 - srcA) / 255) / outA;

        final int result = (outA << 24) | (outR << 16) | (outG << 8) | outB;
        LOGGER.debug(IT, "alphaBlend - srcA: {}, dstA: {}, outA: {}, RGBA: ({},{},{},{})",
                srcA, dstA, outA, outR, outG, outB);
        return result;
    }

    private void clearRegion(final int[] canvas, final int canvasWidth, final int x, final int y, final int w, final int h) {
        LOGGER.debug(IT, "Clearing region - position: ({},{}), size: {}x{}", x, y, w, h);
        int pixelsCleared = 0;
        int pixelsOutOfBounds = 0;
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                final int pos = (y + dy) * canvasWidth + (x + dx);
                if (pos >= 0 && pos < canvas.length) {
                    canvas[pos] = 0;
                    pixelsCleared++;
                } else {
                    pixelsOutOfBounds++;
                }
            }
        }
        LOGGER.debug(IT, "Region cleared - cleared: {}, out-of-bounds: {}", pixelsCleared, pixelsOutOfBounds);
    }

    private ByteBuffer sliceChunk(final ByteBuffer buffer, final RiffChunk chunk) {
        LOGGER.debug(IT, "sliceChunk called - offset: {}, size: {}", chunk.dataOffset(), chunk.size());
        final ByteBuffer slice = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        slice.position(chunk.dataOffset());
        slice.limit(chunk.dataOffset() + chunk.size());
        final ByteBuffer result = slice.slice().order(ByteOrder.LITTLE_ENDIAN);
        LOGGER.debug(IT, "sliceChunk complete - sliced buffer capacity: {}", result.capacity());
        return result;
    }

}