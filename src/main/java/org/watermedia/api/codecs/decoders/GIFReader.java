package org.watermedia.api.codecs.decoders;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.gif.ColorTable;
import org.watermedia.api.codecs.common.gif.GraphicExtension;
import org.watermedia.api.codecs.common.gif.ImageDescriptor;
import org.watermedia.api.codecs.common.gif.ScreenDescriptor;
import org.watermedia.api.util.ColorSpace;

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
 * Streaming GIF87a / GIF89a reader.
 *
 * <p>Receives a {@link ByteBuffer} positioned immediately after the 6-byte signature
 * ({@code GIF87a} or {@code GIF89a}); the signature is consumed by {@code CodecsAPI}. The
 * constructor reads the Logical Screen Descriptor + optional Global Color Table, plus any
 * pre-image extensions (so loop count from a Netscape application extension is known up front).
 * Each {@link #next()} parses one Image Descriptor + LZW data block and composes it onto
 * the canvas.
 */
public class GIFReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(GIFReader.class.getSimpleName());

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    // BLOCK INTRODUCERS
    private static final int IMAGE_SEPARATOR = 0x2C;
    private static final int EXTENSION_INTRODUCER = 0x21;
    private static final int TRAILER = 0x3B;
    private static final int APPLICATION_EXTENSION_LABEL = 0xFF;
    private static final int COMMENT_EXTENSION_LABEL = 0xFE;

    // LZW
    private static final int MIN_LZW_CODE_SIZE = 2;
    private static final int MAX_LZW_CODE_SIZE = 8;
    private static final int MAX_STACK_SIZE = 4096;
    private static final int STACK_BUFFER_SIZE = 4097;

    // INTERLACED RENDERING
    private static final int[] PASS_STARTS = {0, 4, 2, 1};
    private static final int[] PASS_INCREMENTS = {8, 8, 4, 2};

    // ANIMATION
    private static final long DEFAULT_FRAME_DELAY = 10L;
    private static final int DELAY_TIME_MULTIPLIER = 10;

    private static final int OPAQUE_BLACK = 0xFF000000;

    // NETSCAPE
    private static final long NETSCAPE_EXT_ID = 0x4E45545343415045L; // "NETSCAPE"
    private static final int NETSCAPE_AUTH_CODE = 0x20322E30;        // " 2.0"

    // PARSED HEADER STATE
    private final ScreenDescriptor lsd;
    private final ColorTable globalColorTable;
    private final ImageMetadata metadata = new ImageMetadata();
    private final List<GifExtension> extensions = new ArrayList<>();

    // CANVAS STATE
    private final int[] canvas;            // current composited canvas
    private int[] restoreFrame;            // saved canvas for disposal method 3
    private final ByteBuffer directOut;    // BGRA output buffer (reused)
    private final IntBuffer directOutInts;
    private final int backgroundColor;

    // STREAMING STATE
    private int pendingIntroducer = -1;    // 1-byte look-ahead consumed by constructor
    private boolean done;
    private boolean nextReady;
    private final ImageData.Scan scan;
    private byte[] subBlockBuffer = new byte[4096];
    private byte[] lzwIndexScratch = new byte[0];
    private final short[] lzwPrefix = new short[MAX_STACK_SIZE];
    private final byte[] lzwSuffix = new byte[MAX_STACK_SIZE];
    private final byte[] lzwPixelStack = new byte[STACK_BUFFER_SIZE];
    private final byte[] descriptorScratch = new byte[9];
    private final byte[] gceScratch = new byte[6];

    // Frame-control between consecutive frames
    private GraphicExtension currentGce;
    private GraphicExtension previousGce;
    private ImageDescriptor previousId;

    public GIFReader(final ByteBuffer data) throws IOException {
        super(data);
        this.data.order(LE);
        this.scan = scan(this.data.duplicate().order(LE));

        // LOGICAL SCREEN DESCRIPTOR (7 bytes)
        final byte[] lsdBytes = readExactly(this.data, ScreenDescriptor.SIGNATURE_SIZE);
        this.lsd = ScreenDescriptor.read(ByteBuffer.wrap(lsdBytes).order(LE));

        // GLOBAL COLOR TABLE (optional)
        if (this.lsd.globalColorTableFlag()) {
            final int gctSize = 1 << (this.lsd.globalColorTableSize() + 1);
            if (this.data.remaining() < gctSize * 3) {
                throw new EOFException("Unexpected EOF in global color table");
            }
            this.globalColorTable = ColorTable.read(gctSize, this.data);
        } else {
            this.globalColorTable = null;
        }

        // ALLOCATE CANVAS / OUTPUT
        this.canvas = new int[this.lsd.width() * this.lsd.height()];
        this.directOut = ByteBuffer.allocateDirect(this.lsd.width() * this.lsd.height() * 4).order(LE);
        this.directOutInts = this.directOut.asIntBuffer();
        this.backgroundColor = (this.globalColorTable != null
                && this.lsd.backgroundColorIndex() < this.globalColorTable.colors().length)
                ? this.globalColorTable.colors()[this.lsd.backgroundColorIndex()]
                : OPAQUE_BLACK;

        // PRE-IMAGE EXTENSIONS (loop count, initial GCE, etc.) until first introducer that isn't an extension.
        while (true) {
            final int b = readUnsignedOrEnd(this.data);
            if (b < 0) { this.done = true; break; }
            if (b == EXTENSION_INTRODUCER) {
                this.processExtension();
            } else {
                this.pendingIntroducer = b;
                break;
            }
        }
    }

    @Override public int width() { return this.lsd.width(); }
    @Override public int height() { return this.lsd.height(); }
    @Override public ColorSpace pixelFormat() { return ColorSpace.BGRA; }
    @Override public ImageData.Scan scan() { return this.scan; }
    @Override public boolean variableFrameRate() { return this.scan.frameCount() > 1; }
    @Override public ImageMetadata metadata() { return this.metadata.empty() ? ImageMetadata.EMPTY : this.metadata; }

    @Override
    public boolean hasNext() throws IOException {
        if (this.done) return false;
        if (this.nextReady) return true;

        while (true) {
            final int b;
            if (this.pendingIntroducer != -1) {
                b = this.pendingIntroducer;
                this.pendingIntroducer = -1;
            } else {
                b = readUnsignedOrEnd(this.data);
                if (b < 0) { this.done = true; return false; }
            }

            if (b == TRAILER) { this.done = true; return false; }
            if (b == IMAGE_SEPARATOR) {
                this.pendingIntroducer = b;
                this.nextReady = true;
                return true;
            }
            if (b == EXTENSION_INTRODUCER) {
                this.processExtension();
                continue;
            }
            // Unknown byte: skip and continue (matches legacy behavior).
        }
    }

    @Override
    public ByteBuffer next() throws IOException {
        if (!this.hasNext()) throw new EOFException("No more GIF frames");
        this.pendingIntroducer = -1;
        this.nextReady = false;
        this.decodeFrame();
        this.currentFrame = this.directOut;
        return this.directOut;
    }

    // ----- FRAME DECODE -----

    private void decodeFrame() throws IOException {
        final ImageDescriptor id = this.readImageDescriptor();

        ColorTable activeColorTable = this.globalColorTable;
        if (id.localColorTableFlag()) {
            final int lctSize = id.getLocalColorTableSize();
            if (this.data.remaining() < lctSize * 3) {
                throw new EOFException("Unexpected EOF in local color table");
            }
            activeColorTable = ColorTable.read(lctSize, this.data);
        }
        if (activeColorTable == null) {
            throw new XCodecException("No color table available for image frame.");
        }

        // LZW: 1 byte min code size + sub-blocks
        final int lzwMinCodeSize = readUnsignedOrEnd(this.data);
        if (lzwMinCodeSize < 0) throw new EOFException("EOF before LZW min code size");
        if (lzwMinCodeSize < MIN_LZW_CODE_SIZE || lzwMinCodeSize > MAX_LZW_CODE_SIZE) {
            throw new XCodecException("Invalid LZW minimum code size: " + lzwMinCodeSize);
        }
        final int lzwDataLength = this.readSubBlocks();

        final int expectedIndices = id.width() * id.height();
        final byte[] indices = this.decompress(id, lzwMinCodeSize, this.subBlockBuffer, lzwDataLength, expectedIndices);

        // FIRST FRAME: initialize canvas; subsequent frames: dispose then composite.
        if (this.previousId == null) {
            final int initBg = (this.currentGce != null && this.currentGce.transparentColorFlag())
                    ? 0x00000000 : this.backgroundColor;
            Arrays.fill(this.canvas, initBg);
        } else {
            final int disposalBg = (this.previousGce != null && this.previousGce.transparentColorFlag())
                    ? 0x00000000 : this.backgroundColor;
            this.applyDisposal(this.previousGce, disposalBg, this.previousId);
        }

        if (this.currentGce != null && this.currentGce.disposalMethod() == 3) {
            if (this.restoreFrame == null) this.restoreFrame = new int[this.canvas.length];
            System.arraycopy(this.canvas, 0, this.restoreFrame, 0, this.canvas.length);
        }

        this.renderImage(indices, expectedIndices, this.canvas, id, this.lsd, activeColorTable, this.currentGce);

        this.currentDelay = this.scan.frameCount() <= 1 ? 0L
                : (this.currentGce != null && this.currentGce.delayTime() > 0)
                ? (long) this.currentGce.delayTime() * DELAY_TIME_MULTIPLIER : DEFAULT_FRAME_DELAY;

        this.previousGce = this.currentGce;
        this.previousId = id;
        this.currentGce = null;

        // COPY TO DIRECT BUFFER (BGRA layout — canvas already has 0xFFrrggbb / 0x00...)
        this.directOut.clear();
        this.directOutInts.clear();
        this.directOutInts.put(this.canvas);
        this.directOut.position(0).limit(this.canvas.length * 4);
    }

    private ImageDescriptor readImageDescriptor() throws IOException {
        readExactly(this.data, this.descriptorScratch, 0, this.descriptorScratch.length);
        final byte[] data = this.descriptorScratch;
        final int left = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        final int top = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
        final int width = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
        final int height = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);
        final int packed = data[8] & 0xFF;
        return new ImageDescriptor(
                left,
                top,
                width,
                height,
                (packed & 0x80) != 0,
                (packed & 0x40) != 0,
                (packed & 0x20) != 0,
                packed & 0x07
        );
    }

    private void renderImage(final byte[] indexes, final int pixelCount, final int[] canvas, final ImageDescriptor id,
                             final ScreenDescriptor lsd, final ColorTable colorTable, final GraphicExtension gce) {
        final int transparentIndex = (gce != null && gce.transparentColorFlag()) ? gce.transparentColorIndex() : -1;
        final int[] colors = colorTable.colors();
        final int colorLen = colors.length;
        final int canvasWidth = lsd.width();
        final int canvasHeight = lsd.height();
        final int idLeft = id.left();
        final int idTop = id.top();
        final int idWidth = id.width();
        final int idHeight = id.height();

        // Fast path: image descriptor covers the whole canvas exactly — no per-pixel clipping.
        if (!id.interlacedFlag()
                && idLeft == 0 && idTop == 0
                && idWidth == canvasWidth && idHeight == canvasHeight) {
            if (transparentIndex < 0) {
                for (int i = 0; i < pixelCount; i++) {
                    final int ci = indexes[i] & 0xFF;
                    if (ci < colorLen) canvas[i] = colors[ci];
                }
            } else {
                for (int i = 0; i < pixelCount; i++) {
                    final int ci = indexes[i] & 0xFF;
                    if (ci == transparentIndex) continue;
                    if (ci < colorLen) canvas[i] = colors[ci];
                }
            }
            return;
        }

        // General path: hoist clip rect once instead of per-pixel checks.
        final int clipXStart = Math.max(0, idLeft);
        final int clipYStart = Math.max(0, idTop);
        final int clipXEnd = Math.min(canvasWidth, idLeft + idWidth);
        final int clipYEnd = Math.min(canvasHeight, idTop + idHeight);
        final boolean rowFullyVisible = (idLeft >= 0) && (idLeft + idWidth <= canvasWidth);

        if (id.interlacedFlag()) {
            int srcIdx = 0;
            for (int pass = 0; pass < PASS_STARTS.length; pass++) {
                final int passStart = PASS_STARTS[pass];
                final int passInc = PASS_INCREMENTS[pass];
                for (int y = passStart; y < idHeight; y += passInc) {
                    srcIdx = this.blitRow(indexes, srcIdx, idWidth, idLeft, idTop + y,
                            canvas, canvasWidth, clipXStart, clipXEnd, clipYStart, clipYEnd,
                            rowFullyVisible, colors, colorLen, transparentIndex);
                    if (srcIdx >= pixelCount) return;
                }
            }
        } else {
            int srcIdx = 0;
            for (int y = 0; y < idHeight; y++) {
                srcIdx = this.blitRow(indexes, srcIdx, idWidth, idLeft, idTop + y,
                        canvas, canvasWidth, clipXStart, clipXEnd, clipYStart, clipYEnd,
                        rowFullyVisible, colors, colorLen, transparentIndex);
                if (srcIdx >= pixelCount) return;
            }
        }
    }

    private int blitRow(final byte[] indexes, int srcIdx, final int idWidth,
                        final int idLeft, final int canvasY,
                        final int[] canvas, final int canvasWidth,
                        final int clipXStart, final int clipXEnd,
                        final int clipYStart, final int clipYEnd,
                        final boolean rowFullyVisible,
                        final int[] colors, final int colorLen,
                        final int transparentIndex) {
        if (canvasY < clipYStart || canvasY >= clipYEnd) {
            return srcIdx + idWidth;
        }
        final int rowOff = canvasY * canvasWidth;
        if (rowFullyVisible) {
            if (transparentIndex < 0) {
                for (int x = 0; x < idWidth; x++) {
                    final int ci = indexes[srcIdx++] & 0xFF;
                    if (ci < colorLen) canvas[rowOff + idLeft + x] = colors[ci];
                }
            } else {
                for (int x = 0; x < idWidth; x++) {
                    final int ci = indexes[srcIdx++] & 0xFF;
                    if (ci == transparentIndex) continue;
                    if (ci < colorLen) canvas[rowOff + idLeft + x] = colors[ci];
                }
            }
            return srcIdx;
        }
        // Partial horizontal clip: skip leading/trailing pixels that fall outside the canvas.
        for (int x = 0; x < idWidth; x++) {
            final int canvasX = idLeft + x;
            if (canvasX < clipXStart || canvasX >= clipXEnd) { srcIdx++; continue; }
            final int ci = indexes[srcIdx++] & 0xFF;
            if (ci == transparentIndex) continue;
            if (ci < colorLen) canvas[rowOff + canvasX] = colors[ci];
        }
        return srcIdx;
    }

    private void applyDisposal(final GraphicExtension gce, final int background, final ImageDescriptor id) {
        final int disposal = gce != null ? gce.disposalMethod() : 0;
        if (disposal == 2) {
            // RESTORE TO BACKGROUND for the previous frame's rect — bulk-fill each clipped row.
            final int canvasWidth = this.lsd.width();
            final int canvasHeight = this.lsd.height();
            final int xStart = Math.max(0, id.left());
            final int yStart = Math.max(0, id.top());
            final int xEnd = Math.min(canvasWidth, id.left() + id.width());
            final int yEnd = Math.min(canvasHeight, id.top() + id.height());
            if (xStart >= xEnd || yStart >= yEnd) return;
            for (int y = yStart; y < yEnd; y++) {
                final int rowOff = y * canvasWidth;
                Arrays.fill(this.canvas, rowOff + xStart, rowOff + xEnd, background);
            }
        } else if (disposal == 3 && this.restoreFrame != null) {
            System.arraycopy(this.restoreFrame, 0, this.canvas, 0, this.canvas.length);
        }
    }

    private byte[] decompress(final ImageDescriptor id, final int lzwMinCodeSize, final byte[] data,
                             final int dataLength, final int expectedSize) throws XCodecException {
        final int clearCode = 1 << lzwMinCodeSize;
        final int endOfInfoCode = clearCode + 1;
        if (this.lzwIndexScratch.length < expectedSize) this.lzwIndexScratch = new byte[expectedSize];
        final byte[] output = this.lzwIndexScratch;
        final short[] prefix = this.lzwPrefix;
        final byte[] suffix = this.lzwSuffix;
        final byte[] pixelStack = this.lzwPixelStack;

        for (int i = 0; i < clearCode; i++) {
            prefix[i] = 0;
            suffix[i] = (byte) i;
        }

        int codeSize = lzwMinCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = clearCode + 2;

        int datum = 0;
        int bits = 0;
        int oldCode = -1;
        int first = 0;
        int top = 0;
        int pi = 0;
        int dataPos = 0;
        int pixelsLeft = expectedSize;

        outer:
        while (pixelsLeft > 0) {
            // Drain pending stack first — this is the most common hot path.
            while (top > 0) {
                output[pi++] = pixelStack[--top];
                if (--pixelsLeft == 0) break outer;
            }

            // Read enough bits for the next code.
            while (bits < codeSize) {
                if (dataPos >= dataLength) break outer;
                datum += (data[dataPos++] & 0xFF) << bits;
                bits += 8;
            }
            int code = datum & codeMask;
            datum >>>= codeSize;
            bits -= codeSize;

            if (code > available || code == endOfInfoCode) break;
            if (code == clearCode) {
                codeSize = lzwMinCodeSize + 1;
                codeMask = (1 << codeSize) - 1;
                available = clearCode + 2;
                oldCode = -1;
                continue;
            }
            if (oldCode == -1) {
                pixelStack[top++] = suffix[code];
                oldCode = code;
                first = code;
                continue;
            }
            final int inCode = code;
            if (code == available) {
                pixelStack[top++] = (byte) first;
                code = oldCode;
            }
            while (code > clearCode) {
                if (top >= MAX_STACK_SIZE) throw new XCodecException("LZW stack overflow");
                pixelStack[top++] = suffix[code];
                code = prefix[code];
            }
            first = suffix[code] & 0xFF;
            if (available >= MAX_STACK_SIZE) {
                pixelStack[top++] = (byte) first;
            } else {
                pixelStack[top++] = (byte) first;
                prefix[available] = (short) oldCode;
                suffix[available] = (byte) first;
                available++;
                if (((available & codeMask) == 0) && (available < MAX_STACK_SIZE)) {
                    codeSize++;
                    codeMask += available;
                }
            }
            oldCode = inCode;
        }
        return output;
    }

    // ----- EXTENSION HANDLING -----

    private void processExtension() throws IOException {
        final int label = readUnsignedOrEnd(this.data);
        if (label < 0) { this.done = true; return; }
        switch (label) {
            case GraphicExtension.GCE_LABEL -> this.readGce();
            case APPLICATION_EXTENSION_LABEL -> {
                final int lc = this.readAppExtension();
                if (lc >= 0) this.metadata.put(CodecsAPI.GIF_METAKEY_LOOP_COUNT, lc);
            }
            case COMMENT_EXTENSION_LABEL -> this.readCommentExtension();
            default -> this.skipSubBlocks();
        }
    }

    private void readCommentExtension() throws IOException {
        final int length = this.readSubBlocks();
        final String comment = new String(this.subBlockBuffer, 0, length, StandardCharsets.ISO_8859_1);
        this.metadata.comment(comment);
        this.metadata.put(CodecsAPI.GIF_METAKEY_COMMENT, comment);
    }

    private void readGce() throws IOException {
        // GCE body: 1 (block size, must be 4) + 1 packed + 2 delay + 1 trans index + 1 terminator = 6 bytes
        readExactly(this.data, this.gceScratch, 0, this.gceScratch.length);
        final int packed = this.gceScratch[1] & 0xFF;
        final int delayTime = (this.gceScratch[2] & 0xFF) | ((this.gceScratch[3] & 0xFF) << 8);
        this.currentGce = new GraphicExtension(
                (packed & 0b00011100) >> 2,
                (packed & 0b00000010) != 0,
                (packed & 0b00000001) != 0,
                delayTime,
                this.gceScratch[4] & 0xFF
        );
    }

    private int readAppExtension() throws IOException {
        final int blockSize = readUnsignedOrEnd(this.data);
        if (blockSize < 0) { this.done = true; return -1; }
        final byte[] header = readExactly(this.data, blockSize);
        final int dataLength = this.readSubBlocks();
        final byte[] extensionData = Arrays.copyOf(this.subBlockBuffer, dataLength);
        this.storeApplicationExtension(header, extensionData);

        if (blockSize != 11) return -1;
        final long id = readBE(header, 0, 8);
        final int auth = (int) readBE(header, 8, 3);
        if (id == NETSCAPE_EXT_ID && auth == NETSCAPE_AUTH_CODE) {
            // Concatenated sub-block data: 1 byte sub-id (1), 2 bytes loop count.
            if (extensionData.length >= 3 && (extensionData[0] & 0xFF) == 1) {
                final int lo = extensionData[1] & 0xFF;
                final int hi = extensionData[2] & 0xFF;
                int lc = (hi << 8) | lo;
                if (lc == 0) lc = ImageData.REPEAT_FOREVER;
                LOGGER.debug(IT, "Netscape 2.0 extension with loop count: {}", lc);
                return lc;
            }
        } else {
            LOGGER.debug(IT, "Unknown application extension: ID={} AUTH={}",
                    Long.toHexString(id), Integer.toHexString(auth));
        }
        return -1;
    }

    private void storeApplicationExtension(final byte[] header, final byte[] data) {
        final String id = new String(header, StandardCharsets.ISO_8859_1).trim();
        this.extensions.add(new GifExtension(id, data));
        this.metadata.put(CodecsAPI.GIF_METAKEY_APPLICATION_EXTENSION, this.extensions);
    }

    // ----- SUB-BLOCK I/O -----

    private int readSubBlocks() throws IOException {
        int totalSize = 0;
        while (true) {
            final int size = readUnsignedOrEnd(this.data);
            if (size < 0) throw new EOFException("EOF in sub-block");
            if (size == 0) break;
            this.ensureSubBlockCapacity(totalSize + size);
            readExactly(this.data, this.subBlockBuffer, totalSize, size);
            totalSize += size;
        }
        return totalSize;
    }

    private void ensureSubBlockCapacity(final int minCapacity) {
        if (this.subBlockBuffer.length >= minCapacity) return;
        int next = this.subBlockBuffer.length;
        while (next < minCapacity) next <<= 1;
        this.subBlockBuffer = Arrays.copyOf(this.subBlockBuffer, next);
    }

    private void skipSubBlocks() throws IOException {
        while (true) {
            final int size = readUnsignedOrEnd(this.data);
            if (size < 0) { this.done = true; return; }
            if (size == 0) return;
            this.skipBytes(size);
        }
    }

    private void skipBytes(final int n) throws IOException {
        if (n < 0 || this.data.remaining() < n) throw new EOFException("EOF skipping GIF bytes");
        this.data.position(this.data.position() + n);
    }

    private static ImageData.Scan scan(final ByteBuffer source) {
        final ByteBuffer buffer = source.slice().order(LE);
        final List<Long> delays = new ArrayList<>();
        int loopCount = ImageData.NO_REPEAT;
        long currentDelay = DEFAULT_FRAME_DELAY;

        if (buffer.remaining() < ScreenDescriptor.SIGNATURE_SIZE) return ImageData.Scan.STATIC;

        final int packed = buffer.get(buffer.position() + 4) & 0xFF;
        final boolean gctFlag = (packed & 0x80) != 0;
        final int gctSizeBits = packed & 0x07;
        buffer.position(buffer.position() + ScreenDescriptor.SIGNATURE_SIZE);
        if (gctFlag) {
            final int bytes = 3 * (1 << (gctSizeBits + 1));
            if (buffer.remaining() < bytes) return ImageData.Scan.STATIC;
            buffer.position(buffer.position() + bytes);
        }

        while (buffer.hasRemaining()) {
            final int introducer = buffer.get() & 0xFF;
            if (introducer == TRAILER) break;
            if (introducer == EXTENSION_INTRODUCER) {
                if (!buffer.hasRemaining()) break;
                final int label = buffer.get() & 0xFF;
                if (label == GraphicExtension.GCE_LABEL) {
                    if (buffer.remaining() < 6) break;
                    final int blockSize = buffer.get(buffer.position()) & 0xFF;
                    if (blockSize != 4) break;
                    final int delayLow = buffer.get(buffer.position() + 2) & 0xFF;
                    final int delayHigh = buffer.get(buffer.position() + 3) & 0xFF;
                    final int delayCs = (delayHigh << 8) | delayLow;
                    currentDelay = delayCs > 0 ? (long) delayCs * DELAY_TIME_MULTIPLIER : DEFAULT_FRAME_DELAY;
                    buffer.position(buffer.position() + 6);
                } else if (label == APPLICATION_EXTENSION_LABEL) {
                    loopCount = scan$extension(buffer, loopCount);
                } else {
                    scan$skipSubBlocks(buffer);
                }
            } else if (introducer == IMAGE_SEPARATOR) {
                if (buffer.remaining() < 9) break;
                final int idPacked = buffer.get(buffer.position() + 8) & 0xFF;
                final boolean lctFlag = (idPacked & 0x80) != 0;
                final int lctSizeBits = idPacked & 0x07;
                buffer.position(buffer.position() + 9);
                if (lctFlag) {
                    final int bytes = 3 * (1 << (lctSizeBits + 1));
                    if (buffer.remaining() < bytes) break;
                    buffer.position(buffer.position() + bytes);
                }
                if (!buffer.hasRemaining()) break;
                buffer.position(buffer.position() + 1); // LZW minimum code size
                scan$skipSubBlocks(buffer);
                delays.add(currentDelay);
                currentDelay = DEFAULT_FRAME_DELAY;
            } else {
                break;
            }
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

    private static int scan$extension(final ByteBuffer buffer, final int fallbackLoopCount) {
        if (!buffer.hasRemaining()) return fallbackLoopCount;
        final int blockSize = buffer.get() & 0xFF;
        if (buffer.remaining() < blockSize) return fallbackLoopCount;
        final byte[] header = new byte[blockSize];
        buffer.get(header);

        int loopCount = fallbackLoopCount;
        if (blockSize == 11) {
            final long id = readBE(header, 0, 8);
            final int auth = (int) readBE(header, 8, 3);
            if (id == NETSCAPE_EXT_ID && auth == NETSCAPE_AUTH_CODE && buffer.remaining() >= 5) {
                final int sbSize = buffer.get() & 0xFF;
                final int sbId = buffer.get() & 0xFF;
                if (sbSize == 3 && sbId == 1 && buffer.remaining() >= 3) {
                    final int lo = buffer.get() & 0xFF;
                    final int hi = buffer.get() & 0xFF;
                    int lc = (hi << 8) | lo;
                    loopCount = lc == 0 ? ImageData.REPEAT_FOREVER : lc;
                }
            }
        }
        scan$skipSubBlocks(buffer);
        return loopCount;
    }

    private static void scan$skipSubBlocks(final ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            final int size = buffer.get() & 0xFF;
            if (size == 0) return;
            if (buffer.remaining() < size) {
                buffer.position(buffer.limit());
                return;
            }
            buffer.position(buffer.position() + size);
        }
    }

    // ----- STATIC HELPERS -----

    private static int readUnsignedOrEnd(final ByteBuffer buffer) {
        return buffer.hasRemaining() ? buffer.get() & 0xFF : -1;
    }

    private static byte[] readExactly(final ByteBuffer buffer, final int n) throws IOException {
        final byte[] buf = new byte[n];
        readExactly(buffer, buf, 0, n);
        return buf;
    }

    private static void readExactly(final ByteBuffer buffer, final byte[] dst, final int off, final int len) throws IOException {
        if (buffer.remaining() < len) throw new EOFException("Unexpected EOF (" + buffer.remaining() + "/" + len + ")");
        buffer.get(dst, off, len);
    }

    private static long readBE(final byte[] data, final int off, final int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (data[off + i] & 0xFFL);
        return v;
    }

    public record GifExtension(String identifier, byte[] data) {}
}
