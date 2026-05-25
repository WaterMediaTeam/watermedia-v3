package org.watermedia.api.codecs.decoders;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.webp.common.AlphaDecoder;
import org.watermedia.api.codecs.decoders.webp.common.BitReader;
import org.watermedia.api.codecs.decoders.webp.lossless.VP8LDecoder;
import org.watermedia.api.codecs.decoders.webp.lossy.VP8LossyDecoder;
import org.watermedia.api.codecs.decoders.webp.riff.RiffChunk;
import org.watermedia.api.util.PixelFormat;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Streaming WebP reader.
 *
 * <p>Receives a {@link ByteBuffer} positioned past the 12-byte {@code RIFF????WEBP} preamble.
 * The constructor reads the first chunk to identify the file shape:
 * <ul>
 *   <li><b>Simple lossy</b> ({@code VP8 }): the chunk contains the whole bitstream — single frame.</li>
 *   <li><b>Simple lossless</b> ({@code VP8L}): single frame.</li>
 *   <li><b>Extended</b> ({@code VP8X}): reads the 10-byte VP8X data, then reads chunks until enough
 *       has been collected for the first frame (or {@code ANIM} for animated WebPs).</li>
 * </ul>
 *
 * <p>For animated WebPs, each {@link #next()} reads one {@code ANMF} chunk, decodes its
 * sub-chunks (optional {@code ALPH} + {@code VP8 } / {@code VP8L}), and composites onto the canvas.
 */
public class WEBPReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(WEBPReader.class.getSimpleName());
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    private final int canvasWidth;
    private final int canvasHeight;
    private final boolean animated;
    private final boolean hasAlpha;
    private final ImageData.Scan scan;
    private final ImageMetadata metadata = new ImageMetadata();

    // STATIC PATH: buffered bitstream chunks for one frame
    private byte[] vp8Data;       // VP8 or VP8L body
    private int bitstreamFourCC;
    private byte[] alphData;      // ALPH body, or null
    private boolean staticDelivered;

    // ANIMATED PATH: canvas
    private int[] canvas;
    private ByteBuffer directOut;
    private IntBuffer directOutInts;
    private byte[] anmfScratch = new byte[0];
    private ChunkHdr pendingChunk;

    // OUTPUT PIXEL FORMAT RESOLVED IN CONSTRUCTOR. NATIVE PATH FOR STATIC LOSSY-WITHOUT-ALPHA IS
    // YUV420P (VP8 PRODUCES THESE PLANES NATURALLY); EVERYTHING ELSE STAYS BGRA BECAUSE
    // LOSSLESS DECODE, ALPHA HANDLING, AND ANIMATED COMPOSITING ARE ALL ALREADY BGRA-NATIVE.
    // FOR YUV MODE WE ALLOCATE A SINGLE PACKED BUFFER [Y | U | V] WITH TIGHT ROW STRIDES SO POOL
    // SIZING REMAINS A SIMPLE FUNCTION OF (FORMAT, W, H). PER-PLANE VIEWS ARE HANDED OUT THROUGH
    // plane(idx) BY SLICING WITH OFFSET/LIMIT.
    private final PixelFormat outputFormat;
    private final int yPlaneSize;
    private final int uvPlaneSize;
    private final int chromaWidth;
    private final int chromaHeight;
    private ByteBuffer yuvOut;

    private boolean done;

    public WEBPReader(final ByteBuffer data) throws IOException {
        this(data, null);
    }

    public WEBPReader(final ByteBuffer data, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);
        this.data.order(LE);
        this.scan = scan(this.data.duplicate().order(LE));

        // FIRST CHUNK: VP8 / VP8L / VP8X
        final ChunkHdr first = readChunkHdr(this.data);
        switch (first.fourCC) {
            case RiffChunk.VP8 -> {
                this.vp8Data = readPaddedChunkBody(this.data, first.size);
                this.bitstreamFourCC = RiffChunk.VP8;
                int[] dim = parseVP8Dims(this.vp8Data);
                this.canvasWidth = dim[0];
                this.canvasHeight = dim[1];
                this.animated = false;
                this.hasAlpha = false;
            }
            case RiffChunk.VP8L -> {
                this.vp8Data = readPaddedChunkBody(this.data, first.size);
                this.bitstreamFourCC = RiffChunk.VP8L;
                int[] dim = parseVP8LDims(this.vp8Data);
                this.canvasWidth = dim[0];
                this.canvasHeight = dim[1];
                this.hasAlpha = dim[2] != 0;
                this.animated = false;
            }
            case RiffChunk.VP8X -> {
                if (first.size < 10) throw new XCodecException("VP8X chunk too small: " + first.size);
                final byte[] vp8xBody = readPaddedChunkBody(this.data, first.size);
                final int flags = vp8xBody[0] & 0xFF;
                this.hasAlpha = (flags & 0x10) != 0;
                this.animated = (flags & 0x02) != 0;
                final int w0 = vp8xBody[4] & 0xFF, w1 = vp8xBody[5] & 0xFF, w2 = vp8xBody[6] & 0xFF;
                final int h0 = vp8xBody[7] & 0xFF, h1 = vp8xBody[8] & 0xFF, h2 = vp8xBody[9] & 0xFF;
                this.canvasWidth = (w0 | (w1 << 8) | (w2 << 16)) + 1;
                this.canvasHeight = (h0 | (h1 << 8) | (h2 << 16)) + 1;

                if (this.animated) {
                    // Read chunks until ANIM. Then ANMFs are read lazily in next().
                    while (true) {
                        final ChunkHdr c = readChunkHdrOrEnd(this.data);
                        if (c == null) { this.done = true; break; }
                        if (c.fourCC == RiffChunk.ANIM) {
                            readPaddedChunkBody(this.data, c.size); // consume; loop count already captured by scan()
                            break;
                        }
                        if (!this.readMetadataChunk(this.data, c)) skipBytes(this.data, paddedSize(c.size));
                    }
                    this.canvas = new int[this.canvasWidth * this.canvasHeight];
                } else {
                    // Static extended: read chunks until we have VP8/VP8L (and optional ALPH).
                    while (true) {
                        final ChunkHdr c = readChunkHdrOrEnd(this.data);
                        if (c == null) break;
                        if (c.fourCC == RiffChunk.VP8 || c.fourCC == RiffChunk.VP8L) {
                            this.bitstreamFourCC = c.fourCC;
                            this.vp8Data = readPaddedChunkBody(this.data, c.size);
                        } else if (c.fourCC == RiffChunk.ALPH) {
                            this.alphData = readPaddedChunkBody(this.data, c.size);
                        } else if (!this.readMetadataChunk(this.data, c)) {
                            skipBytes(this.data, paddedSize(c.size));
                        }
                    }
                    if (this.vp8Data == null) {
                        throw new XCodecException("Truncated extended WEBP (no bitstream chunk)");
                    }
                }
            }
            default -> throw new XCodecException("Unknown WebP first chunk: " + RiffChunk.fourCCString(first.fourCC));
        }

        this.outputFormat = resolveOutputFormat(requestedFormat, this.animated, this.bitstreamFourCC, this.hasAlpha, this.alphData);

        if (this.outputFormat == PixelFormat.YUV420P) {
            this.chromaWidth = (this.canvasWidth + 1) >> 1;
            this.chromaHeight = (this.canvasHeight + 1) >> 1;
            this.yPlaneSize = this.canvasWidth * this.canvasHeight;
            this.uvPlaneSize = this.chromaWidth * this.chromaHeight;
            this.yuvOut = ByteBuffer.allocateDirect(this.yPlaneSize + 2 * this.uvPlaneSize).order(LE);
        } else {
            this.chromaWidth = 0;
            this.chromaHeight = 0;
            this.yPlaneSize = 0;
            this.uvPlaneSize = 0;
            this.directOut = ByteBuffer.allocateDirect(this.canvasWidth * this.canvasHeight * 4).order(LE);
            this.directOutInts = this.directOut.asIntBuffer();
        }
    }

    // CHOOSE THE BUFFER LAYOUT WE'LL DELIVER. ANIMATED FRAMES AND ANY EXPLICIT BGRA REQUEST FORCE
    // BGRA; THE ONLY SHORT-CIRCUIT TO NATIVE YUV IS A STATIC LOSSY (VP8) FRAME WITH NO ALPHA.
    private static PixelFormat resolveOutputFormat(final PixelFormat requested, final boolean animated,
                                                  final int bitstreamFourCC, final boolean hasAlpha,
                                                  final byte[] alphData) {
        if (requested == PixelFormat.BGRA) return PixelFormat.BGRA;
        if (animated) return PixelFormat.BGRA;
        if (bitstreamFourCC != RiffChunk.VP8) return PixelFormat.BGRA;
        if (hasAlpha || alphData != null) return PixelFormat.BGRA;
        return PixelFormat.YUV420P;
    }

    @Override public int width() { return this.canvasWidth; }
    @Override public int height() { return this.canvasHeight; }
    @Override public PixelFormat pixelFormat() { return this.outputFormat; }
    @Override public ImageData.Scan scan() { return this.scan; }
    @Override public boolean variableFrameRate() { return this.animated; }
    @Override public ImageMetadata metadata() { return this.metadata.empty() ? ImageMetadata.EMPTY : this.metadata; }

    @Override
    public int planeCount() {
        return this.outputFormat == PixelFormat.YUV420P ? 3 : 1;
    }

    @Override
    public ByteBuffer plane(final int index) {
        if (this.outputFormat != PixelFormat.YUV420P) {
            if (index != 0) throw new IndexOutOfBoundsException("plane " + index);
            return this.currentFrame;
        }
        final int offset;
        final int size;
        switch (index) {
            case 0 -> { offset = 0; size = this.yPlaneSize; }
            case 1 -> { offset = this.yPlaneSize; size = this.uvPlaneSize; }
            case 2 -> { offset = this.yPlaneSize + this.uvPlaneSize; size = this.uvPlaneSize; }
            default -> throw new IndexOutOfBoundsException("plane " + index);
        }
        final ByteBuffer view = this.yuvOut.duplicate().order(LE);
        view.position(offset).limit(offset + size);
        return view.slice().order(LE);
    }

    @Override
    public int planeStride(final int index) {
        if (this.outputFormat != PixelFormat.YUV420P) {
            if (index != 0) throw new IndexOutOfBoundsException("plane " + index);
            return 0;
        }
        return switch (index) {
            case 0 -> this.canvasWidth;
            case 1, 2 -> this.chromaWidth;
            default -> throw new IndexOutOfBoundsException("plane " + index);
        };
    }

    @Override
    public boolean hasNext() throws IOException {
        if (this.done) return false;

        if (!this.animated) {
            return !this.staticDelivered;
        }

        // Animated: locate next ANMF, skipping unrelated chunks (EXIF/XMP/etc.).
        if (this.pendingChunk != null) return true;
        while (true) {
            final ChunkHdr c = readChunkHdrOrEnd(this.data);
            if (c == null) { this.done = true; return false; }
            if (c.fourCC == RiffChunk.ANMF) {
                this.pendingChunk = c;
                return true;
            }
            if (!this.readMetadataChunk(this.data, c)) skipBytes(this.data, paddedSize(c.size));
        }
    }

    @Override
    public ByteBuffer next() throws IOException {
        if (!this.hasNext()) throw new EOFException("No more WEBP frames");

        if (!this.animated) {
            this.staticDelivered = true;
            this.decodeStaticFrame();
            this.currentDelay = 0L;
            this.currentFrame = this.currentFrame != null ? this.currentFrame : this.directOut;
            return this.currentFrame;
        }

        final ChunkHdr c = this.pendingChunk;
        this.pendingChunk = null;
        final byte[] anmf = this.readPaddedChunkBodyIntoScratch(this.data, c.size);
        this.decodeAnimFrame(anmf, c.size);
        this.currentFrame = this.directOut;
        return this.directOut;
    }

    // ----- DECODE -----

    private void decodeStaticFrame() throws IOException {
        if (this.outputFormat == PixelFormat.YUV420P) {
            this.decodeStaticFrameYuv();
            return;
        }
        this.currentFrame = this.decodeBitstreamToBgra(this.vp8Data, this.bitstreamFourCC, this.alphData, this.canvasWidth, this.canvasHeight);
        this.currentFrame.position(0).limit(this.canvasWidth * this.canvasHeight * 4);
    }

    private void decodeStaticFrameYuv() throws XCodecException {
        final ByteBuffer vp8Body = wrapSlice(this.vp8Data, 0, this.vp8Data.length);
        final VP8LossyDecoder.Yuv420P yuv = VP8LossyDecoder.decodeToYuv(vp8Body, this.canvasWidth, this.canvasHeight);

        this.yuvOut.clear();
        // Y plane: copy [width] bytes per row, repacking from mb-aligned stride to tight stride.
        copyPlaneRows(yuv.y(), yuv.yStride(), this.yuvOut, 0, this.canvasWidth, this.canvasWidth, this.canvasHeight);
        // U plane.
        final int uOffset = this.yPlaneSize;
        copyPlaneRows(yuv.u(), yuv.uvStride(), this.yuvOut, uOffset, this.chromaWidth, this.chromaWidth, this.chromaHeight);
        // V plane.
        final int vOffset = this.yPlaneSize + this.uvPlaneSize;
        copyPlaneRows(yuv.v(), yuv.uvStride(), this.yuvOut, vOffset, this.chromaWidth, this.chromaWidth, this.chromaHeight);

        this.yuvOut.position(0).limit(this.yPlaneSize + 2 * this.uvPlaneSize);
        this.currentFrame = this.yuvOut;
    }

    // COPY PLANE ROW-BY-ROW FROM A MACROBLOCK-ALIGNED byte[] SOURCE INTO A TIGHTLY PACKED REGION
    // OF THE DESTINATION DIRECT BUFFER. SOURCE STRIDE MAY EXCEED VISUAL WIDTH (VP8 PADS TO MB
    // BOUNDARIES); DESTINATION ROWS ARE WRITTEN BACK-TO-BACK.
    private static void copyPlaneRows(final byte[] src, final int srcStride, final ByteBuffer dst,
                                       final int dstOffset, final int dstStride,
                                       final int copyWidth, final int rows) {
        for (int row = 0; row < rows; row++) {
            dst.position(dstOffset + row * dstStride);
            dst.put(src, row * srcStride, copyWidth);
        }
    }

    private void decodeAnimFrame(final byte[] anmf, final int anmfLength) throws IOException {
        if (anmfLength < 16) throw new XCodecException("Truncated ANMF chunk");

        // 16-byte ANMF header
        final int frameX = (anmf[0] & 0xFF) | ((anmf[1] & 0xFF) << 8) | ((anmf[2] & 0xFF) << 16);
        final int frameY = (anmf[3] & 0xFF) | ((anmf[4] & 0xFF) << 8) | ((anmf[5] & 0xFF) << 16);
        final int frameW = ((anmf[6] & 0xFF) | ((anmf[7] & 0xFF) << 8) | ((anmf[8] & 0xFF) << 16)) + 1;
        final int frameH = ((anmf[9] & 0xFF) | ((anmf[10] & 0xFF) << 8) | ((anmf[11] & 0xFF) << 16)) + 1;
        final int duration = (anmf[12] & 0xFF) | ((anmf[13] & 0xFF) << 8) | ((anmf[14] & 0xFF) << 16);
        final int flags = anmf[15] & 0xFF;
        final boolean blend = (flags & 0x02) == 0;
        final boolean dispose = (flags & 0x01) == 1;

        // Parse sub-chunks inside ANMF body
        int subVp8Off = -1;
        int subVp8Len = 0;
        int subVp8FourCC = 0;
        int subAlphOff = -1;
        int subAlphLen = 0;
        int off = 16;
        while (off + 8 <= anmfLength) {
            final int fcc = (anmf[off] & 0xFF) | ((anmf[off + 1] & 0xFF) << 8) | ((anmf[off + 2] & 0xFF) << 16) | ((anmf[off + 3] & 0xFF) << 24);
            final int sz = (anmf[off + 4] & 0xFF) | ((anmf[off + 5] & 0xFF) << 8) | ((anmf[off + 6] & 0xFF) << 16) | ((anmf[off + 7] & 0xFF) << 24);
            final int body = off + 8;
            if (body + sz > anmfLength) break;
            if (fcc == RiffChunk.VP8 || fcc == RiffChunk.VP8L) {
                subVp8FourCC = fcc;
                subVp8Off = body;
                subVp8Len = sz;
            } else if (fcc == RiffChunk.ALPH) {
                subAlphOff = body;
                subAlphLen = sz;
            }
            off = body + paddedSize(sz);
        }
        if (subVp8Off < 0 || subVp8Len <= 0) throw new XCodecException("ANMF without VP8/VP8L sub-chunk");

        final int[] framePixels = this.decodeBitstreamToArgb(
                anmf, subVp8Off, subVp8Len, subVp8FourCC,
                anmf, subAlphOff, subAlphLen,
                frameW, frameH
        );

        // Composite onto canvas (frameX/Y are stored as halved per WEBP spec; original code multiplies by 2).
        this.composite(this.canvas, this.canvasWidth, this.canvasHeight, framePixels, frameX * 2, frameY * 2, frameW, frameH, blend);

        // Convert canvas to BGRA in directOut
        this.directOut.clear();
        this.directOutInts.clear();
        this.directOutInts.put(this.canvas);
        this.directOut.position(0).limit(this.canvas.length * 4);

        this.currentDelay = duration;

        // Dispose for next frame
        if (dispose) {
            this.clearRegion(this.canvas, this.canvasWidth, frameX * 2, frameY * 2, frameW, frameH);
        }
    }

    private ByteBuffer decodeBitstreamToBgra(final byte[] vp8Body, final int vp8FourCC,
                                              final byte[] alphBody, final int w, final int h) throws XCodecException {
        return this.decodeBitstreamToBgra(
                vp8Body, 0, vp8Body.length, vp8FourCC,
                alphBody, 0, alphBody != null ? alphBody.length : 0,
                w, h
        );
    }

    private ByteBuffer decodeBitstreamToBgra(final byte[] vp8Data, final int vp8Off, final int vp8Len, final int vp8FourCC,
                                             final byte[] alphData, final int alphOff, final int alphLen,
                                             final int w, final int h) throws XCodecException {
        final ByteBuffer vp8Body = wrapSlice(vp8Data, vp8Off, vp8Len);
        if (vp8FourCC == RiffChunk.VP8L) {
            final ByteBuffer vp8lData = vp8Body.duplicate().order(LE);
            vp8lData.position(5); // skip 1-byte signature + 4 bytes header
            final BitReader reader = new BitReader(vp8lData);
            return VP8LDecoder.decodeToBgra(reader, w, h);
        }
        if (alphData == null || alphLen <= 0) {
            return VP8LossyDecoder.decodeToBgra(vp8Body, w, h);
        }
        final ByteBuffer result = VP8LossyDecoder.decodeToBgra(vp8Body, w, h);
        final byte[] alpha = AlphaDecoder.decode(wrapSlice(alphData, alphOff, alphLen), w, h);
        AlphaDecoder.applyAlpha(result, alpha);
        return result;
    }

    private int[] decodeBitstreamToArgb(final byte[] vp8Data, final int vp8Off, final int vp8Len, final int vp8FourCC,
                                         final byte[] alphData, final int alphOff, final int alphLen,
                                         final int w, final int h) throws XCodecException {
        final ByteBuffer vp8Body = wrapSlice(vp8Data, vp8Off, vp8Len);
        if (vp8FourCC == RiffChunk.VP8L) {
            final ByteBuffer vp8lData = vp8Body.duplicate().order(LE);
            vp8lData.position(5);
            final BitReader reader = new BitReader(vp8lData);
            return VP8LDecoder.decode(reader, w, h);
        }
        final int[] argb = VP8LossyDecoder.decode(vp8Body, w, h);
        if (alphData != null && alphLen > 0) {
            final byte[] alpha = AlphaDecoder.decode(wrapSlice(alphData, alphOff, alphLen), w, h);
            AlphaDecoder.applyAlpha(argb, alpha);
        }
        return argb;
    }

    private static ByteBuffer wrapSlice(final byte[] data, final int offset, final int len) {
        return ByteBuffer.wrap(data, offset, len).slice().order(LE);
    }

    private void composite(final int[] canvas, final int cw, final int ch,
                           final int[] frame, final int x, final int y, final int fw, final int fh, final boolean blend) {
        final int x0 = Math.max(0, x);
        final int y0 = Math.max(0, y);
        final int x1 = Math.min(cw, x + fw);
        final int y1 = Math.min(ch, y + fh);
        for (int cy = y0; cy < y1; cy++) {
            final int srcRow = (cy - y) * fw;
            final int dstRow = cy * cw;
            for (int cx = x0; cx < x1; cx++) {
                final int src = frame[srcRow + (cx - x)];
                final int idx = dstRow + cx;
                canvas[idx] = blend ? this.alphaBlend(canvas[idx], src) : src;
            }
        }
    }

    private int alphaBlend(final int dst, final int src) {
        final int srcA = (src >> 24) & 0xFF;
        if (srcA == 255) return src;
        if (srcA == 0) return dst;
        final int dstA = (dst >> 24) & 0xFF;
        final int srcR = (src >> 16) & 0xFF, srcG = (src >> 8) & 0xFF, srcB = src & 0xFF;
        final int dstR = (dst >> 16) & 0xFF, dstG = (dst >> 8) & 0xFF, dstB = dst & 0xFF;
        final int outA = srcA + (dstA * (255 - srcA) / 255);
        if (outA == 0) return 0;
        final int outR = (srcR * srcA + dstR * dstA * (255 - srcA) / 255) / outA;
        final int outG = (srcG * srcA + dstG * dstA * (255 - srcA) / 255) / outA;
        final int outB = (srcB * srcA + dstB * dstA * (255 - srcA) / 255) / outA;
        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    private void clearRegion(final int[] canvas, final int cw, final int x, final int y, final int w, final int h) {
        final int x0 = Math.max(0, x);
        final int y0 = Math.max(0, y);
        final int x1 = Math.min(cw, x + w);
        final int y1 = Math.min(this.canvasHeight, y + h);
        for (int cy = y0; cy < y1; cy++) {
            Arrays.fill(canvas, cy * cw + x0, cy * cw + x1, 0);
        }
    }

    private static ImageData.Scan scan(final ByteBuffer source) {
        final ByteBuffer buffer = source.slice().order(LE);
        if (buffer.remaining() < 8) return ImageData.Scan.STATIC;

        final int firstFourCC = buffer.getInt();
        final int firstSize = buffer.getInt();
        if (firstFourCC != RiffChunk.VP8X) return ImageData.Scan.STATIC;
        if (firstSize < 10 || buffer.remaining() < firstSize) return ImageData.Scan.STATIC;

        final int vp8xPos = buffer.position();
        final boolean animated = (buffer.get(vp8xPos) & 0x02) != 0;
        buffer.position(vp8xPos + paddedSize(firstSize));
        if (!animated) return ImageData.Scan.STATIC;

        final List<Long> delays = new ArrayList<>();
        int loopCount = ImageData.NO_REPEAT;
        while (buffer.remaining() >= 8) {
            final int fourCC = buffer.getInt();
            final int size = buffer.getInt();
            if (size < 0 || buffer.remaining() < size) break;
            final int dataPos = buffer.position();
            if (fourCC == RiffChunk.ANIM && size >= 6) {
                final int lc = (buffer.get(dataPos + 4) & 0xFF) | ((buffer.get(dataPos + 5) & 0xFF) << 8);
                loopCount = lc == 0 ? ImageData.REPEAT_FOREVER : lc;
            } else if (fourCC == RiffChunk.ANMF && size >= 16) {
                final int duration = (buffer.get(dataPos + 12) & 0xFF)
                        | ((buffer.get(dataPos + 13) & 0xFF) << 8)
                        | ((buffer.get(dataPos + 14) & 0xFF) << 16);
                delays.add((long) duration);
            }
            final int next = dataPos + paddedSize(size);
            buffer.position(Math.min(next, buffer.limit()));
        }

        if (delays.size() <= 1) return ImageData.Scan.STATIC;
        final long[] delayArray = new long[delays.size()];
        long total = 0L;
        for (int i = 0; i < delayArray.length; i++) {
            delayArray[i] = delays.get(i);
            total += delayArray[i];
        }
        return new ImageData.Scan(delayArray.length, delayArray, total, loopCount);
    }

    // ----- DIMENSION PARSERS -----

    private static int[] parseVP8Dims(final byte[] data) throws XCodecException {
        if (data.length < 10) throw new XCodecException("VP8 chunk too small");
        final int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF, b2 = data[2] & 0xFF;
        final int frameTag = b0 | (b1 << 8) | (b2 << 16);
        if ((frameTag & 1) != 0) throw new XCodecException("VP8 must start with key frame");
        final int sync0 = data[3] & 0xFF, sync1 = data[4] & 0xFF, sync2 = data[5] & 0xFF;
        if (sync0 != 0x9D || sync1 != 0x01 || sync2 != 0x2A) throw new XCodecException("Invalid VP8 start code");
        final int widthScale = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);
        final int heightScale = (data[8] & 0xFF) | ((data[9] & 0xFF) << 8);
        return new int[] { widthScale & 0x3FFF, heightScale & 0x3FFF };
    }

    private static int[] parseVP8LDims(final byte[] data) throws XCodecException {
        if (data.length < 5) throw new XCodecException("VP8L chunk too small");
        if ((data[0] & 0xFF) != 0x2F) throw new XCodecException("Invalid VP8L signature");
        final int bits = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8) | ((data[3] & 0xFF) << 16) | ((data[4] & 0xFF) << 24);
        final int w = (bits & 0x3FFF) + 1;
        final int h = ((bits >> 14) & 0x3FFF) + 1;
        final int alphaUsed = (bits >> 28) & 1;
        final int version = (bits >> 29) & 7;
        if (version != 0) throw new XCodecException("Unsupported VP8L version: " + version);
        return new int[] { w, h, alphaUsed };
    }

    // ----- CHUNK / STREAM HELPERS -----

    private record ChunkHdr(int fourCC, int size) {}

    private static ChunkHdr readChunkHdr(final ByteBuffer in) throws IOException {
        final int fcc = readIntLE(in);
        final int sz = readIntLE(in);
        return new ChunkHdr(fcc, sz);
    }

    private static ChunkHdr readChunkHdrOrEnd(final ByteBuffer in) throws IOException {
        if (!in.hasRemaining()) return null;
        if (in.remaining() < 8) throw new EOFException("Truncated WEBP chunk header");
        final int fcc = in.getInt();
        final int sz = in.getInt();
        return new ChunkHdr(fcc, sz);
    }

    private static int readIntLE(final ByteBuffer in) throws IOException {
        if (in.remaining() < 4) throw new EOFException("Truncated WEBP int");
        return in.getInt();
    }

    private static byte[] readPaddedChunkBody(final ByteBuffer in, final int size) throws IOException {
        if (size < 0) throw new XCodecException("Negative WEBP chunk size: " + size);
        if (in.remaining() < size) throw new EOFException("Truncated WEBP chunk body");
        final byte[] body = new byte[size];
        in.get(body);
        if ((size & 1) == 1) {
            if (!in.hasRemaining()) LOGGER.debug(IT, "WEBP padding byte missing at EOF");
            else in.get();
        }
        return body;
    }

    private byte[] readPaddedChunkBodyIntoScratch(final ByteBuffer in, final int size) throws IOException {
        if (size < 0) throw new XCodecException("Negative WEBP chunk size: " + size);
        if (in.remaining() < size) throw new EOFException("Truncated WEBP chunk body");
        if (this.anmfScratch.length < size) this.anmfScratch = new byte[size];
        in.get(this.anmfScratch, 0, size);
        if ((size & 1) == 1) {
            if (!in.hasRemaining()) LOGGER.debug(IT, "WEBP padding byte missing at EOF");
            else in.get();
        }
        return this.anmfScratch;
    }

    private static int paddedSize(final int size) {
        return (size + 1) & ~1;
    }

    private boolean readMetadataChunk(final ByteBuffer in, final ChunkHdr c) throws IOException {
        if (c.fourCC == RiffChunk.ICCP) {
            this.metadata.put(CodecsAPI.WEBP_METAKEY_ICC_PROFILE, readPaddedChunkBody(in, c.size));
            return true;
        }
        if (c.fourCC == RiffChunk.EXIF) {
            this.metadata.put(CodecsAPI.WEBP_METAKEY_EXIF, readPaddedChunkBody(in, c.size));
            return true;
        }
        if (c.fourCC == RiffChunk.XMP) {
            final byte[] xmp = readPaddedChunkBody(in, c.size);
            this.metadata.put(CodecsAPI.WEBP_METAKEY_XMP, xmp);
            this.readXmpCommonFields(xmp);
            return true;
        }
        return false;
    }

    private void readXmpCommonFields(final byte[] xmp) {
        if (xmp == null || xmp.length == 0) return;
        final String xml = new String(xmp, StandardCharsets.UTF_8);
        this.metadata.title(firstXmlText(xml, "dc:title", "rdf:li"));
        this.metadata.description(firstXmlText(xml, "dc:description", "rdf:li"));
        this.metadata.copyright(firstXmlText(xml, "dc:rights", "rdf:li"));
        this.metadata.comment(firstXmlText(xml, "xmp:Label", null));
        this.metadata.creationTime(firstXmlText(xml, "xmp:CreateDate", null));
        this.metadata.software(firstXmlText(xml, "xmp:CreatorTool", null));
        for (final String author: xmlTexts(xml, "dc:creator", "rdf:li")) {
            this.metadata.author(author);
        }
    }

    private static String firstXmlText(final String xml, final String outerTag, final String innerTag) {
        final List<String> values = xmlTexts(xml, outerTag, innerTag);
        return values.isEmpty() ? null : values.get(0);
    }

    private static List<String> xmlTexts(final String xml, final String outerTag, final String innerTag) {
        final List<String> values = new ArrayList<>();
        final String outer = extractXmlContent(xml, outerTag);
        if (outer == null) return values;
        if (innerTag == null) {
            final String clean = cleanXmlText(outer);
            if (clean != null) values.add(clean);
            return values;
        }
        int pos = 0;
        while (true) {
            final String value = extractXmlContent(outer, innerTag, pos);
            if (value == null) break;
            final String clean = cleanXmlText(value);
            if (clean != null) values.add(clean);
            final int next = outer.indexOf("</" + innerTag + ">", pos);
            if (next < 0) break;
            pos = next + innerTag.length() + 3;
        }
        return values;
    }

    private static String extractXmlContent(final String xml, final String tag) {
        return extractXmlContent(xml, tag, 0);
    }

    private static String extractXmlContent(final String xml, final String tag, final int start) {
        final String openPrefix = "<" + tag;
        final int openStart = xml.indexOf(openPrefix, start);
        if (openStart < 0) return null;
        final int openEnd = xml.indexOf('>', openStart);
        if (openEnd < 0) return null;
        final String close = "</" + tag + ">";
        final int closeStart = xml.indexOf(close, openEnd + 1);
        if (closeStart < 0) return null;
        return xml.substring(openEnd + 1, closeStart);
    }

    private static String cleanXmlText(final String value) {
        if (value == null) return null;
        final String clean = value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .trim();
        return clean.isEmpty() ? null : clean;
    }

    private static void skipBytes(final ByteBuffer in, final int n) throws IOException {
        if (n < 0 || in.remaining() < n) throw new EOFException("EOF skipping WEBP bytes");
        in.position(in.position() + n);
    }

}
