package org.watermedia.api.codecs.readers;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.gif.ColorTable;
import org.watermedia.api.codecs.common.gif.GraphicExtension;
import org.watermedia.api.codecs.common.gif.ImageDescriptor;
import org.watermedia.api.codecs.common.gif.ScreenDescriptor;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
public final class GIFReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(GIFReader.class.getSimpleName());

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    // SECURITY CAPS
    private static final int MAX_DIM = 16384;                       // 16K PER AXIS (1:1); CHEAP PRE-FILTER FOR THE AREA CAP
    // TOTAL-PIXEL CAP (64 MPX, E.G. 8192x8192). THE PER-AXIS CAP ONLY KEEPS width*height*4 INSIDE INT
    // RANGE, IT DOES NOT BOUND COST: A 7-BYTE 16K x 16K SCREEN DESCRIPTOR BUYS 1 GiB OF CANVAS PLUS
    // 1 GiB OF DIRECT BUFFER PLUS 1 GiB OF DISPOSAL-3 SNAPSHOT BEFORE ANY FRAME BYTE IS PARSED
    private static final int MAX_PIXELS = 1 << 26;
    // A FRAME COSTS A FULL-CANVAS COMPOSITE AND COPY REGARDLESS OF ITS DESCRIPTOR SIZE, YET IS ONLY
    // 12 FILE BYTES. BOUNDED PER PASS — reset() STARTS A NEW PASS SO LOOPING PLAYBACK IS UNAFFECTED
    private static final int MAX_FRAMES = 4096;
    // METADATA RECORDS ARE RETAINED FOR THE READER'S LIFETIME, SO THEIR COUNT IS CAPPED TOO
    private static final int MAX_EXTENSIONS = 512;
    private static final int MAX_COMMENTS = 512;
    private static final int MAX_SUBBLOCK = Integer.MAX_VALUE - 8;  // JAVA ARRAY-SIZE SAFE UPPER BOUND

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
    private int comments;
    // HIGHEST STREAM OFFSET WHOSE METADATA BLOCK IS ALREADY RECORDED. reset() REPLAYS THE SAME BYTES,
    // SO WITHOUT THIS EVERY LOOP RE-APPENDS EVERY MID-STREAM EXTENSION AND THE HEAP CLIMBS FOREVER
    private int metaMark;

    // CANVAS STATE
    private final int[] canvas;            // CURRENT COMPOSITED CANVAS
    private int[] restoreFrame;            // SAVED CANVAS FOR DISPOSAL METHOD 3
    private final ByteBuffer directOut;    // BGRA OUTPUT BUFFER (REUSED)
    private final IntBuffer directOutInts;
    private final int backgroundColor;

    // STREAMING STATE
    private int pendingIntroducer = -1;    // 1-BYTE LOOK-AHEAD CONSUMED BY CONSTRUCTOR
    private boolean done;
    private boolean nextReady;
    private int frames;                    // FRAMES YIELDED IN THE CURRENT PASS
    private final ImageData.Scan scan;
    private byte[] subBlockBuffer = new byte[4096];
    private byte[] lzwIndexScratch = new byte[0];
    private final short[] lzwPrefix = new short[MAX_STACK_SIZE];
    private final byte[] lzwSuffix = new byte[MAX_STACK_SIZE];
    private final byte[] lzwPixelStack = new byte[STACK_BUFFER_SIZE];
    private final byte[] descriptorScratch = new byte[9];
    private final byte[] gceScratch = new byte[6];

    // FRAME-CONTROL BETWEEN CONSECUTIVE FRAMES
    private GraphicExtension currentGce;
    private GraphicExtension previousGce;
    private ImageDescriptor previousId;

    // RESET SNAPSHOT — STREAM STATE RIGHT AFTER CONSTRUCTION (FRAME 0 BOUNDARY)
    private final int resetPos;
    private final int resetIntroducer;
    private final boolean resetDone;
    private final GraphicExtension resetGce;

    public GIFReader(final ByteBuffer data) throws XCodecException {
        super(data);
        this.data.order(LE);
        this.scan = scan(this.data.duplicate().order(LE));

        // LOGICAL SCREEN DESCRIPTOR (7 bytes)
        final byte[] lsdBytes = readExactly(this.data, ScreenDescriptor.SIGNATURE_SIZE);
        this.lsd = ScreenDescriptor.read(ByteBuffer.wrap(lsdBytes).order(LE));
        // CAP THE CANVAS BEFORE ANYTHING IS ALLOCATED. THE PER-AXIS TEST IS THE CHEAP PRE-FILTER THAT
        // KEEPS width*height*4 INSIDE INT RANGE; THE AREA TEST IS THE ACTUAL BUDGET, IN LONG MATH
        // BECAUSE TWO CAPPED AXES STILL MULTIPLY TO 268 MPX
        if (this.lsd.width() > MAX_DIM || this.lsd.height() > MAX_DIM
                || (long) this.lsd.width() * this.lsd.height() > MAX_PIXELS)
            throw new XCodecException("GIF canvas too big: " + this.lsd.width() + "x" + this.lsd.height()
                    + " (max " + MAX_DIM + " per axis, " + MAX_PIXELS + " pixels)");

        // GLOBAL COLOR TABLE (optional)
        if (this.lsd.globalColorTableFlag()) {
            final int gctSize = 1 << (this.lsd.globalColorTableSize() + 1);
            if (this.data.remaining() < gctSize * 3) {
                throw new XCodecException("Unexpected EOF in global color table");
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

        // PRE-IMAGE EXTENSIONS (LOOP COUNT, INITIAL GCE, ETC.) UNTIL THE FIRST NON-EXTENSION INTRODUCER
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

        // SNAPSHOT THE FRAME-0 BOUNDARY SO reset() CAN REPLAY WITHOUT RE-PARSING THE HEADER
        this.resetPos = this.data.position();
        this.resetIntroducer = this.pendingIntroducer;
        this.resetDone = this.done;
        this.resetGce = this.currentGce;
    }

    @Override public int width() { return this.lsd.width(); }
    @Override public int height() { return this.lsd.height(); }
    @Override public PixelFormat pixelFormat() { return PixelFormat.BGRA; }
    @Override public ImageData.Scan scan() { return this.scan; }
    @Override public boolean variableFrameRate() { return this.scan.frameCount() > 1; }
    @Override public ImageMetadata metadata() { return this.metadata.empty() ? ImageMetadata.EMPTY : this.metadata; }

    @Override
    public boolean hasNext() throws XCodecException {
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
                // COUNTED ONCE PER FRAME: THE nextReady SHORT-CIRCUIT ABOVE ABSORBS REPEATED hasNext() CALLS
                if (++this.frames > MAX_FRAMES)
                    throw new XCodecException("GIF frame count exceeds limit: " + this.frames + " (max " + MAX_FRAMES + ")");
                this.pendingIntroducer = b;
                this.nextReady = true;
                return true;
            }
            if (b == EXTENSION_INTRODUCER) {
                this.processExtension();
                continue;
            }
            // UNKNOWN BYTE: SKIP AND CONTINUE (MATCHES LEGACY BEHAVIOR)
        }
    }

    @Override
    public ByteBuffer next() throws XCodecException {
        if (!this.hasNext()) throw new XCodecException("No more GIF frames");
        this.pendingIntroducer = -1;
        this.nextReady = false;
        this.decodeFrame();
        this.currentFrame = this.directOut;
        return this.directOut;
    }

    @Override
    public boolean reset() {
        // CANVAS AND restoreFrame NEED NO CLEARING: FRAME 0 FULLY REFILLS THE CANVAS AND
        // DISPOSAL-3 SNAPSHOTS ARE ALWAYS SAVED BEFORE BEING RESTORED WITHIN THE SAME PASS.
        // METADATA NEEDS NO CLEARING EITHER: metaMark MAKES RE-PARSED BLOCKS A NO-OP (SEE readAppExtension)
        this.data.position(this.resetPos);
        this.pendingIntroducer = this.resetIntroducer;
        this.done = this.resetDone;
        this.nextReady = false;
        this.currentGce = this.resetGce;
        this.previousGce = null;
        this.previousId = null;
        this.currentDelay = 0L;
        this.frames = 0; // THE FRAME CAP BOUNDS ONE PASS, NOT THE LIFETIME OF A FOREVER-LOOPING READER
        return true;
    }

    // ----- FRAME DECODE -----

    private void decodeFrame() throws XCodecException {
        final ImageDescriptor id = this.clampOrReject(this.readImageDescriptor());

        ColorTable activeColorTable = this.globalColorTable;
        if (id.localColorTableFlag()) {
            final int lctSize = id.getLocalColorTableSize();
            if (this.data.remaining() < lctSize * 3) {
                throw new XCodecException("Unexpected EOF in local color table");
            }
            activeColorTable = ColorTable.read(lctSize, this.data);
        }
        if (activeColorTable == null) {
            throw new XCodecException("No color table available for image frame.");
        }

        // LZW: 1 byte min code size + sub-blocks
        final int lzwMinCodeSize = readUnsignedOrEnd(this.data);
        if (lzwMinCodeSize < 0) throw new XCodecException("EOF before LZW min code size");
        if (lzwMinCodeSize < MIN_LZW_CODE_SIZE || lzwMinCodeSize > MAX_LZW_CODE_SIZE) {
            throw new XCodecException("Invalid LZW minimum code size: " + lzwMinCodeSize);
        }
        final int lzwDataLength = this.readSubBlocks();

        final int expectedIndices = id.width() * id.height();
        final byte[] indices = this.decompress(id, lzwMinCodeSize, this.subBlockBuffer, lzwDataLength, expectedIndices);

        // FIRST FRAME: INITIALIZE CANVAS; SUBSEQUENT FRAMES: DISPOSE THEN COMPOSITE
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

    // ENSURE THE FRAME FITS INSIDE THE CANVAS. AN OVERSIZED DESCRIPTOR OTHERWISE OVERFLOWS
    // width*height INT MATH (expectedIndices) INTO A NEGATIVE/HUGE LZW ALLOCATION. WITH
    // decoders.gif.clampImageDesc THE FRAME IS SHRUNK TO THE CANVAS (BEST-EFFORT); OTHERWISE IT FAILS.
    private ImageDescriptor clampOrReject(final ImageDescriptor id) throws XCodecException {
        final int cw = this.lsd.width();
        final int ch = this.lsd.height();
        if ((long) id.left() + id.width() <= cw && (long) id.top() + id.height() <= ch) {
            return id; // FITS
        }
        if (!WaterMediaConfig.decoders.gif.clampImageDesc) {
            throw new XCodecException("GIF frame exceeds canvas: " + id.width() + "x" + id.height()
                    + " at " + id.left() + "," + id.top() + " (canvas " + cw + "x" + ch + ")");
        }
        final int w = Math.min(id.width(), cw - id.left());
        final int h = Math.min(id.height(), ch - id.top());
        if (w <= 0 || h <= 0) throw new XCodecException("GIF frame fully outside canvas");
        LOGGER.warn(IT, "Clamping GIF frame {}x{} at {},{} to {}x{} (canvas {}x{})",
                id.width(), id.height(), id.left(), id.top(), w, h, cw, ch);
        return new ImageDescriptor(id.left(), id.top(), w, h,
                id.localColorTableFlag(), id.interlacedFlag(), id.sortFlag(), id.localColorTableSize());
    }

    private ImageDescriptor readImageDescriptor() throws XCodecException {
        readExactly(this.data, this.descriptorScratch, 0, this.descriptorScratch.length);
        final byte[] data = this.descriptorScratch;
        final int left = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        final int top = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
        final int width = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
        final int height = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);
        final int packed = data[8] & 0xFF;
        final ImageDescriptor id = new ImageDescriptor(
                left,
                top,
                width,
                height,
                (packed & 0x80) != 0,
                (packed & 0x40) != 0,
                (packed & 0x20) != 0,
                packed & 0x07
        );
        id.validate(); // CANONICAL CONSTRUCTOR CAN'T THROW; REJECT MALFORMED FIELDS HERE AS XCodecException
        return id;
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

        // FAST PATH: IMAGE DESCRIPTOR COVERS THE WHOLE CANVAS EXACTLY — NO PER-PIXEL CLIPPING
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

        // GENERAL PATH: HOIST THE CLIP RECT ONCE INSTEAD OF PER-PIXEL CHECKS
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
        // PARTIAL HORIZONTAL CLIP: SKIP LEADING/TRAILING PIXELS THAT FALL OUTSIDE THE CANVAS
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
            // RESTORE TO BACKGROUND FOR THE PREVIOUS FRAME'S RECT — BULK-FILL EACH CLIPPED ROW
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
            // DRAIN THE PENDING STACK FIRST — THIS IS THE MOST COMMON HOT PATH
            while (top > 0) {
                output[pi++] = pixelStack[--top];
                if (--pixelsLeft == 0) break outer;
            }

            // READ ENOUGH BITS FOR THE NEXT CODE
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
                // THE FIRST CODE AFTER A CLEAR MUST BE A LITERAL. code == available (clearCode + 2) PASSES THE
                // BOUNDS CHECK ABOVE AND WOULD READ A DICTIONARY SLOT THIS FRAME NEVER DEFINED — THE TABLE IS
                // LONG-LIVED, SO THAT SLOT STILL HOLDS THE PREVIOUS FRAME'S DATA. REJECT INSTEAD OF LEAKING IT
                if (code >= clearCode) throw new XCodecException("LZW first code references undefined entry: " + code);
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
        // TRUNCATED LZW DATA: ZERO-FILL THE UNWRITTEN TAIL SO MISSING PIXELS DECODE AS INDEX 0 INSTEAD
        // OF STALE INDICES LEFT IN THE REUSED SCRATCH BY AN EARLIER (POSSIBLY LARGER) FRAME
        if (pi < expectedSize) Arrays.fill(output, pi, expectedSize, (byte) 0);
        return output;
    }

    // ----- EXTENSION HANDLING -----

    private void processExtension() throws XCodecException {
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

    private void readCommentExtension() throws XCodecException {
        final int length = this.readSubBlocks();
        if (this.data.position() <= this.metaMark) return; // ALREADY RECORDED BY AN EARLIER PASS OVER THESE BYTES
        this.metaMark = this.data.position();
        if (++this.comments > MAX_COMMENTS)
            throw new XCodecException("Too many GIF comment extensions: " + this.comments + " (max " + MAX_COMMENTS + ")");
        final String comment = new String(this.subBlockBuffer, 0, length, StandardCharsets.ISO_8859_1);
        this.metadata.comment(comment);
        this.metadata.put(CodecsAPI.GIF_METAKEY_COMMENT, comment);
    }

    private void readGce() throws XCodecException {
        // GCE body: 1 (block size, must be 4) + 1 packed + 2 delay + 1 trans index + 1 terminator = 6 bytes
        readExactly(this.data, this.gceScratch, 0, this.gceScratch.length);
        // BLOCK SIZE AND TERMINATOR ARE FIXED BY THE SPEC AND scan() ALREADY ABORTS ON A WRONG SIZE.
        // WITHOUT THESE TWO CHECKS THE PRE-PASS AND THE DECODER DISAGREE ON THE FRAME COUNT OF THE
        // SAME FILE, WHICH TURNS ANY SIZE GATE COMPUTED FROM scan() INTO A BYPASS
        if ((this.gceScratch[0] & 0xFF) != 4)
            throw new XCodecException("Invalid GCE block size: " + (this.gceScratch[0] & 0xFF) + " (must be 4)");
        if (this.gceScratch[5] != 0)
            throw new XCodecException("Invalid GCE block terminator: " + (this.gceScratch[5] & 0xFF) + " (must be 0)");
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

    private int readAppExtension() throws XCodecException {
        final int blockSize = readUnsignedOrEnd(this.data);
        if (blockSize < 0) { this.done = true; return -1; }
        final byte[] header = readExactly(this.data, blockSize);
        final int dataLength = this.readSubBlocks();

        // RECORD THE BLOCK ONLY THE FIRST TIME ITS STREAM OFFSET IS REACHED: reset() REPLAYS THE SAME
        // BYTES ON EVERY ANIMATION LOOP, SO AN UNGUARDED add() IS A MONOTONIC HEAP CLIMB WITH NO CEILING
        if (this.data.position() > this.metaMark) {
            this.metaMark = this.data.position();
            if (this.extensions.size() >= MAX_EXTENSIONS)
                throw new XCodecException("Too many GIF application extensions: " + (this.extensions.size() + 1)
                        + " (max " + MAX_EXTENSIONS + ")");
            this.extensions.add(new GifExtension(new String(header, StandardCharsets.ISO_8859_1).trim(),
                    Arrays.copyOf(this.subBlockBuffer, dataLength)));
            // PUBLISH AN UNMODIFIABLE VIEW SO A CONSUMER CANNOT MUTATE THE READER'S INTERNAL LIST
            this.metadata.put(CodecsAPI.GIF_METAKEY_APPLICATION_EXTENSION, Collections.unmodifiableList(this.extensions));
        }

        if (blockSize != 11) return -1;
        final long id = readBE(header, 0, 8);
        final int auth = (int) readBE(header, 8, 3);
        if (id == NETSCAPE_EXT_ID && auth == NETSCAPE_AUTH_CODE) {
            // CONCATENATED SUB-BLOCK DATA: 1 BYTE SUB-ID (1), 2 BYTES LOOP COUNT
            if (dataLength >= 3 && (this.subBlockBuffer[0] & 0xFF) == 1) {
                final int lo = this.subBlockBuffer[1] & 0xFF;
                final int hi = this.subBlockBuffer[2] & 0xFF;
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

    // ----- SUB-BLOCK I/O -----

    private int readSubBlocks() throws XCodecException {
        int totalSize = 0;
        while (true) {
            final int size = readUnsignedOrEnd(this.data);
            if (size < 0) throw new XCodecException("EOF in sub-block");
            if (size == 0) break;
            this.ensureSubBlockCapacity(totalSize + size);
            readExactly(this.data, this.subBlockBuffer, totalSize, size);
            totalSize += size;
        }
        return totalSize;
    }

    private void ensureSubBlockCapacity(final int minCapacity) throws XCodecException {
        if (this.subBlockBuffer.length >= minCapacity) return;
        if (minCapacity < 0 || minCapacity > MAX_SUBBLOCK) {
            throw new XCodecException("GIF sub-block data too large: " + minCapacity);
        }
        int next = this.subBlockBuffer.length;
        // DOUBLING CAN OVERFLOW PAST Integer.MAX_VALUE INTO A NEGATIVE SIZE; FALL BACK TO THE EXACT NEED
        while (next < minCapacity) {
            next <<= 1;
            if (next < 0 || next > MAX_SUBBLOCK) { next = minCapacity; break; }
        }
        this.subBlockBuffer = Arrays.copyOf(this.subBlockBuffer, next);
    }

    private void skipSubBlocks() throws XCodecException {
        while (true) {
            final int size = readUnsignedOrEnd(this.data);
            if (size < 0) { this.done = true; return; }
            if (size == 0) return;
            this.skipBytes(size);
        }
    }

    private void skipBytes(final int n) throws XCodecException {
        if (n < 0 || this.data.remaining() < n) throw new XCodecException("EOF skipping GIF bytes");
        this.data.position(this.data.position() + n);
    }

    private static ImageData.Scan scan(final ByteBuffer source) {
        final ByteBuffer buffer = source.slice().order(LE);
        final List<Long> delays = new ArrayList<>();
        int loopCount = ImageData.NO_REPEAT;
        long currentDelay = DEFAULT_FRAME_DELAY;

        if (buffer.remaining() < ScreenDescriptor.SIGNATURE_SIZE) return ImageData.Scan.EMPTY;

        final int packed = buffer.get(buffer.position() + 4) & 0xFF;
        final boolean gctFlag = (packed & 0x80) != 0;
        final int gctSizeBits = packed & 0x07;
        buffer.position(buffer.position() + ScreenDescriptor.SIGNATURE_SIZE);
        if (gctFlag) {
            final int bytes = 3 * (1 << (gctSizeBits + 1));
            if (buffer.remaining() < bytes) return ImageData.Scan.EMPTY;
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
                    loopCount = scanExtension(buffer, loopCount);
                } else {
                    scanSkipSubBlocks(buffer);
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
                scanSkipSubBlocks(buffer);
                delays.add(currentDelay);
                currentDelay = DEFAULT_FRAME_DELAY;
            } else {
                break;
            }
        }

        if (delays.size() <= 1) return ImageData.Scan.EMPTY;
        final long[] delayArray = new long[delays.size()];
        long total = 0L;
        for (int i = 0; i < delayArray.length; i++) {
            delayArray[i] = delays.get(i);
            total += delayArray[i];
        }
        return new ImageData.Scan(delayArray.length, delayArray, total, loopCount);
    }

    private static int scanExtension(final ByteBuffer buffer, final int fallbackLoopCount) {
        if (!buffer.hasRemaining()) return fallbackLoopCount;
        final int blockSize = buffer.get() & 0xFF;
        if (buffer.remaining() < blockSize) return fallbackLoopCount;
        final byte[] header = new byte[blockSize];
        buffer.get(header);

        int loopCount = fallbackLoopCount;
        // PEEK THE LOOP COUNT WITHOUT CONSUMING (remaining >= 5 KEEPS THE ABSOLUTE READS IN BOUNDS).
        // ONLY scanSkipSubBlocks MAY WALK THE CHAIN, EXACTLY AS readSubBlocks DOES: CONSUMING THE
        // SUB-BLOCK HEADER HERE DESYNCS THE PRE-PASS FROM THE DECODER WHENEVER THE DECLARED SUB-BLOCK
        // SIZE IS NOT 3, AND TWO PARSERS AT DIFFERENT OFFSETS DISAGREE ON THE FRAME COUNT
        if (blockSize == 11 && buffer.remaining() >= 5
                && readBE(header, 0, 8) == NETSCAPE_EXT_ID && (int) readBE(header, 8, 3) == NETSCAPE_AUTH_CODE) {
            // CONCATENATED SUB-BLOCK DATA: 1 BYTE SIZE (3), 1 BYTE SUB-ID (1), 2 BYTES LOOP COUNT
            final int p = buffer.position();
            if ((buffer.get(p) & 0xFF) == 3 && (buffer.get(p + 1) & 0xFF) == 1) {
                final int lc = ((buffer.get(p + 3) & 0xFF) << 8) | (buffer.get(p + 2) & 0xFF);
                loopCount = lc == 0 ? ImageData.REPEAT_FOREVER : lc;
            }
        }
        scanSkipSubBlocks(buffer);
        return loopCount;
    }

    private static void scanSkipSubBlocks(final ByteBuffer buffer) {
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

    private static byte[] readExactly(final ByteBuffer buffer, final int n) throws XCodecException {
        final byte[] buf = new byte[n];
        readExactly(buffer, buf, 0, n);
        return buf;
    }

    private static void readExactly(final ByteBuffer buffer, final byte[] dst, final int off, final int len) throws XCodecException {
        if (buffer.remaining() < len) throw new XCodecException("Unexpected EOF (" + buffer.remaining() + "/" + len + ")");
        buffer.get(dst, off, len);
    }

    private static long readBE(final byte[] data, final int off, final int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (data[off + i] & 0xFFL);
        return v;
    }

    public record GifExtension(String identifier, byte[] data) {}
}
