package org.watermedia.api.codecs.readers;

import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.util.PixelFormat;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * ByteBuffer-backed JPEG reader.
 *
 * <p>The reader receives a buffer positioned immediately after the SOI marker ({@code FF D8}).
 * It parses JPEG interchange streams directly and decodes 8-bit Huffman-coded baseline and
 * progressive images into the native JPEG sample layout unless BGRA output is requested.
 */
public class JPEGReader extends ImageReader {

    private static final int SOF0 = 0xC0;
    private static final int SOF2 = 0xC2;
    private static final int DHT = 0xC4;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int DQT = 0xDB;
    private static final int DRI = 0xDD;
    private static final int COM = 0xFE;

    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10,
            17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34,
            27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36,
            29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46,
            53, 60, 61, 54, 47, 55, 62, 63
    };

    // INTEGER IDCT CONSTANTS (libjpeg "islow" style, 13-bit fixed-point)
    private static final int CONST_BITS = 13;
    private static final int PASS1_BITS = 2;
    private static final int PASS1_SHIFT = CONST_BITS - PASS1_BITS;
    private static final int PASS1_ROUND = 1 << (PASS1_SHIFT - 1);
    private static final int PASS2_SHIFT = CONST_BITS + PASS1_BITS + 3;
    private static final int PASS2_ROUND = 1 << (PASS2_SHIFT - 1);
    private static final int FIX_0_298631336 = 2446;
    private static final int FIX_0_390180644 = 3196;
    private static final int FIX_0_541196100 = 4433;
    private static final int FIX_0_765366865 = 6270;
    private static final int FIX_0_899976223 = 7373;
    private static final int FIX_1_175875602 = 9633;
    private static final int FIX_1_501321110 = 12299;
    private static final int FIX_1_847759065 = 15137;
    private static final int FIX_1_961570560 = 16069;
    private static final int FIX_2_053119869 = 16819;
    private static final int FIX_2_562915447 = 20995;
    private static final int FIX_3_072711026 = 25172;

    // PRECOMPUTED YCbCr -> RGB CONTRIBUTIONS (BT.601, libjpeg jdcolor.c LAYOUT, ABSORB -128 OFFSET).
    // R/B TABLES STORE THE FINAL PER-PIXEL INTEGER CONTRIBUTION; G TABLES STORE 16-BIT FIXED-POINT,
    // SUMMED THEN SHIFTED. ROUNDING BIAS LIVES IN CB_TO_G SO THE SUM ROUNDS-TO-NEAREST.
    private static final int[] CR_TO_R = new int[256];
    private static final int[] CB_TO_B = new int[256];
    private static final int[] CR_TO_G = new int[256];
    private static final int[] CB_TO_G = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            final int v = i - 128;
            CR_TO_R[i] = (int) Math.round(1.402 * v);
            CB_TO_B[i] = (int) Math.round(1.772 * v);
            CR_TO_G[i] = (int) Math.round(-0.71414 * v * 65536.0);
            CB_TO_G[i] = (int) Math.round(-0.34414 * v * 65536.0) + (1 << 15);
        }
    }

    private final int[][] quantTables = new int[4][];
    private final HuffmanTable[][] huffmanTables = new HuffmanTable[2][4];

    private Component[] components;
    private int width;
    private int height;
    private int maxH;
    private int maxV;
    private int mcusX;
    private int mcusY;
    private int restartInterval;
    private boolean progressive;
    private PixelFormat nativeFormat;
    private PixelFormat outputFormat;
    private ByteBuffer directOut;
    private int[] planeOffsets = { 0 };
    private int[] planeSizes;
    private int[] planeStrides = { 0 };
    private boolean delivered;
    private int eobRun;

    public JPEGReader(final ByteBuffer data, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);
        this.parse();
        this.buildFrame();
    }

    @Override public int width() { return this.width; }
    @Override public int height() { return this.height; }
    @Override public PixelFormat pixelFormat() { return this.outputFormat; }
    @Override public ImageData.Scan scan() { return ImageData.Scan.EMPTY; }
    @Override public boolean variableFrameRate() { return false; }

    @Override
    public int planeCount() {
        return this.outputFormat == PixelFormat.BGRA || this.outputFormat == PixelFormat.GRAY ? 1 : 3;
    }

    @Override
    public ByteBuffer plane(final int index) {
        if (index < 0 || index >= this.planeCount()) throw new IndexOutOfBoundsException("plane " + index);
        final ByteBuffer view = this.directOut.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        view.position(this.planeOffsets[index]).limit(this.planeOffsets[index] + this.planeSizes[index]);
        return view.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public int planeStride(final int index) {
        if (index < 0 || index >= this.planeCount()) throw new IndexOutOfBoundsException("plane " + index);
        return this.planeStrides[index];
    }

    @Override
    public boolean hasNext() {
        return !this.delivered;
    }

    @Override
    public ByteBuffer next() throws IOException {
        if (this.delivered) throw new EOFException("No more JPEG frames");
        this.delivered = true;
        this.currentDelay = 0L;
        this.currentFrame = this.directOut;
        this.directOut.position(0).limit(this.directOut.capacity());
        return this.directOut;
    }

    private void parse() throws IOException {
        int marker = -1;
        while (this.data.hasRemaining()) {
            if (marker < 0) marker = readMarker(this.data);
            switch (marker) {
                case SOI -> throw new XCodecException("Nested JPEG SOI marker");
                case EOI -> {
                    return;
                }
                case SOF0, SOF2 -> this.readFrame(marker);
                case DQT -> this.readQuantTables();
                case DHT -> this.readHuffmanTables();
                case DRI -> this.readRestartInterval();
                case SOS -> {
                    final Scan scan = this.readScanHeader();
                    final EntropyReader entropy = new EntropyReader(this.data);
                    this.decodeScan(scan, entropy);
                    marker = entropy.finishScan();
                    continue;
                }
                default -> {
                    if (isApp(marker) || marker == COM || hasSegmentLength(marker)) this.skipSegment();
                    else if (!isRestart(marker)) throw new XCodecException("Unsupported JPEG marker FF" + hex(marker));
                }
            }
            marker = -1;
        }
    }

    private void readFrame(final int marker) throws IOException {
        final int length = this.readSegmentLength();
        if (length < 8) throw new XCodecException("Truncated JPEG frame header");
        final int end = this.checkedSegmentEnd(length);
        final int precision = readU8(this.data);
        if (precision != 8) throw new XCodecException("Unsupported JPEG precision: " + precision);

        this.height = readU16(this.data);
        this.width = readU16(this.data);
        if (this.width <= 0 || this.height <= 0) throw new XCodecException("Invalid JPEG dimensions");

        final int count = readU8(this.data);
        if (count != 1 && count != 3) throw new XCodecException("Unsupported JPEG component count: " + count);

        this.progressive = marker == SOF2;
        this.components = new Component[count];
        this.maxH = 1;
        this.maxV = 1;
        for (int i = 0; i < count; i++) {
            final int id = readU8(this.data);
            final int sampling = readU8(this.data);
            final int h = sampling >>> 4;
            final int v = sampling & 0x0F;
            final int table = readU8(this.data);
            if (h <= 0 || v <= 0 || h > 4 || v > 4) {
                throw new XCodecException("Unsupported JPEG sampling factor " + h + "x" + v);
            }
            if (table >= this.quantTables.length) throw new XCodecException("Invalid JPEG quantization table " + table);
            this.components[i] = new Component(id, h, v, table);
            this.maxH = Math.max(this.maxH, h);
            this.maxV = Math.max(this.maxV, v);
        }

        this.mcusX = ceilDiv(this.width, this.maxH * 8);
        this.mcusY = ceilDiv(this.height, this.maxV * 8);
        for (final Component component: this.components) {
            // MCU-PADDED GRID: USED FOR STORAGE STRIDE AND INTERLEAVED (MCU) SCAN ITERATION
            component.blocksX = this.mcusX * component.h;
            component.blocksY = this.mcusY * component.v;
            // ACTUAL COMPONENT GRID: USED FOR NON-INTERLEAVED (SINGLE-COMPONENT) SCAN ITERATION.
            // PADDING BLOCKS PAST THESE BOUNDS ARE NOT CODED IN NON-INTERLEAVED SCANS (T.81 A.2.2).
            component.widthInBlocks = ceilDiv(this.width * component.h, this.maxH * 8);
            component.heightInBlocks = ceilDiv(this.height * component.v, this.maxV * 8);
            component.coefficients = new int[component.blocksX * component.blocksY * 64];
        }
        this.data.position(end);
    }

    private void readQuantTables() throws IOException {
        final int length = this.readSegmentLength();
        int remaining = length - 2;
        while (remaining > 0) {
            final int info = readU8(this.data);
            remaining--;
            final int precision = info >>> 4;
            final int table = info & 0x0F;
            if (table >= this.quantTables.length) throw new XCodecException("Invalid JPEG quantization table " + table);
            final int bytes = precision == 0 ? 64 : precision == 1 ? 128 : -1;
            if (bytes < 0 || remaining < bytes) throw new XCodecException("Invalid JPEG quantization table precision");

            final int[] values = new int[64];
            for (int i = 0; i < 64; i++) {
                values[ZIGZAG[i]] = precision == 0 ? readU8(this.data) : readU16(this.data);
            }
            this.quantTables[table] = values;
            remaining -= bytes;
        }
        if (remaining != 0) throw new XCodecException("Malformed JPEG quantization segment");
    }

    private void readHuffmanTables() throws IOException {
        final int length = this.readSegmentLength();
        int remaining = length - 2;
        while (remaining > 0) {
            final int info = readU8(this.data);
            remaining--;
            final int type = info >>> 4;
            final int table = info & 0x0F;
            if (type > 1 || table >= 4) throw new XCodecException("Invalid JPEG Huffman table");

            final int[] counts = new int[17];
            int symbols = 0;
            for (int i = 1; i <= 16; i++) {
                counts[i] = readU8(this.data);
                symbols += counts[i];
            }
            remaining -= 16;
            if (remaining < symbols) throw new XCodecException("Truncated JPEG Huffman table");
            final int[] values = new int[symbols];
            for (int i = 0; i < symbols; i++) values[i] = readU8(this.data);
            remaining -= symbols;
            this.huffmanTables[type][table] = new HuffmanTable(counts, values);
        }
        if (remaining != 0) throw new XCodecException("Malformed JPEG Huffman segment");
    }

    private void readRestartInterval() throws IOException {
        final int length = this.readSegmentLength();
        if (length != 4) throw new XCodecException("Invalid JPEG DRI segment length");
        this.restartInterval = readU16(this.data);
    }

    private Scan readScanHeader() throws IOException {
        if (this.components == null) throw new XCodecException("JPEG scan before frame header");
        final int length = this.readSegmentLength();
        final int end = this.checkedSegmentEnd(length);
        final int count = readU8(this.data);
        if (count <= 0 || count > this.components.length) throw new XCodecException("Invalid JPEG scan component count");

        final Component[] scanComponents = new Component[count];
        for (int i = 0; i < count; i++) {
            final Component component = this.component(readU8(this.data));
            final int tables = readU8(this.data);
            component.dcTable = tables >>> 4;
            component.acTable = tables & 0x0F;
            scanComponents[i] = component;
        }
        final int ss = readU8(this.data);
        final int se = readU8(this.data);
        final int approximation = readU8(this.data);
        final int ah = approximation >>> 4;
        final int al = approximation & 0x0F;
        if (this.data.position() != end) throw new XCodecException("Malformed JPEG scan header");
        return new Scan(scanComponents, ss, se, ah, al);
    }

    private void decodeScan(final Scan scan, final EntropyReader entropy) throws IOException {
        this.eobRun = 0;
        if (!this.progressive) {
            this.decodeSequentialScan(scan, entropy);
            return;
        }
        if (scan.ss == 0 && scan.se == 0) {
            if (scan.ah == 0) this.decodeDcFirst(scan, entropy);
            else this.decodeDcRefine(scan, entropy);
            return;
        }
        if (scan.components.length != 1) throw new XCodecException("Progressive AC scan must contain one component");
        if (scan.ss < 1 || scan.se > 63 || scan.ss > scan.se) throw new XCodecException("Invalid progressive AC range");
        if (scan.ah == 0) this.decodeAcFirst(scan, entropy);
        else this.decodeAcRefine(scan, entropy);
    }

    private void decodeSequentialScan(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        if (scan.components.length == 1) {
            final Component component = scan.components[0];
            this.forEachBlock(component, entropy, block -> this.decodeSequentialBlock(component, entropy, block));
            return;
        }
        this.forEachMcu(scan, entropy, (component, block) -> this.decodeSequentialBlock(component, entropy, block));
    }

    private void decodeDcFirst(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        if (scan.components.length == 1) {
            final Component component = scan.components[0];
            this.forEachBlock(component, entropy, block -> this.decodeDcFirstBlock(component, entropy, block, scan.al));
            return;
        }
        this.forEachMcu(scan, entropy, (component, block) -> this.decodeDcFirstBlock(component, entropy, block, scan.al));
    }

    private void decodeDcRefine(final Scan scan, final EntropyReader entropy) throws IOException {
        if (scan.components.length == 1) {
            final Component component = scan.components[0];
            this.forEachBlock(component, entropy, block -> component.coefficients[block] |= entropy.readBit() << scan.al);
            return;
        }
        this.forEachMcu(scan, entropy, (component, block) -> component.coefficients[block] |= entropy.readBit() << scan.al);
    }

    private void decodeAcFirst(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        final Component component = scan.components[0];
        this.forEachBlock(component, entropy, block -> this.decodeAcFirstBlock(component, entropy, block, scan.ss, scan.se, scan.al));
    }

    private void decodeAcRefine(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        final Component component = scan.components[0];
        this.forEachBlock(component, entropy, block -> this.decodeAcRefineBlock(component, entropy, block, scan.ss, scan.se, scan.al));
    }

    private void decodeSequentialBlock(final Component component, final EntropyReader entropy, final int block) throws IOException {
        this.decodeDcFirstBlock(component, entropy, block, 0);
        this.decodeAcFirstBlock(component, entropy, block, 1, 63, 0);
    }

    private void decodeDcFirstBlock(final Component component, final EntropyReader entropy, final int block, final int shift) throws IOException {
        final HuffmanTable table = this.huffmanTables[0][component.dcTable];
        final int bits = table.decode(entropy);
        component.dc += entropy.receiveExtend(bits);
        component.coefficients[block] = component.dc << shift;
    }

    private void decodeAcFirstBlock(final Component component, final EntropyReader entropy, final int block,
                                    final int start, final int end, final int shift) throws IOException {
        if (this.eobRun > 0) {
            this.eobRun--;
            return;
        }

        final HuffmanTable table = this.huffmanTables[1][component.acTable];
        int k = start;
        while (k <= end) {
            final int symbol;
            try {
                symbol = table.decode(entropy);
            } catch (final XCodecException e) {
                throw new XCodecException("Invalid JPEG AC Huffman code for component " +
                        component.id + " block " + (block / 64) + " range " + start + "-" + end +
                        " shift " + shift, e);
            }
            final int run = symbol >>> 4;
            final int bits = symbol & 0x0F;
            if (bits == 0) {
                if (run == 15) {
                    k += 16;
                    continue;
                }
                this.eobRun = (1 << run) + entropy.readBits(run) - 1;
                return;
            }
            k += run;
            if (k > end) throw new XCodecException("JPEG AC run exceeds spectral selection");
            component.coefficients[block + ZIGZAG[k]] = entropy.receiveExtend(bits) << shift;
            k++;
        }
    }

    private void decodeAcRefineBlock(final Component component, final EntropyReader entropy, final int block,
                                     final int start, final int end, final int shift) throws IOException {
        final int bit = 1 << shift;
        if (this.eobRun > 0) {
            this.refineNonZero(component.coefficients, block, start, end, bit, entropy);
            this.eobRun--;
            return;
        }

        final HuffmanTable table = this.huffmanTables[1][component.acTable];
        int k = start;
        while (k <= end) {
            final int symbol;
            try {
                symbol = table.decode(entropy);
            } catch (final XCodecException e) {
                throw new XCodecException("Invalid JPEG AC refinement Huffman code for component " +
                        component.id + " block " + (block / 64) + " range " + start + "-" + end +
                        " shift " + shift, e);
            }
            int run = symbol >>> 4;
            final int bits = symbol & 0x0F;
            final int value;
            if (bits == 0) {
                if (run < 15) {
                    this.eobRun = (1 << run) + entropy.readBits(run);
                    this.refineNonZero(component.coefficients, block, k, end, bit, entropy);
                    this.eobRun--;
                    return;
                }
                value = 0;
                run = 15;
            } else if (bits == 1) {
                value = entropy.readBit() == 1 ? bit : -bit;
            } else {
                throw new XCodecException("Invalid JPEG progressive refinement symbol");
            }

            while (k <= end) {
                final int index = block + ZIGZAG[k];
                final int coefficient = component.coefficients[index];
                if (coefficient != 0) {
                    this.refineCoefficient(component.coefficients, index, bit, entropy);
                } else {
                    if (run == 0) break;
                    run--;
                }
                k++;
            }
            if (value != 0) {
                if (k > end) throw new XCodecException("JPEG refinement run exceeds spectral selection");
                component.coefficients[block + ZIGZAG[k]] = value;
            }
            k++;
        }
    }

    private void refineNonZero(final int[] coefficients, final int block, final int start,
                               final int end, final int bit, final EntropyReader entropy) throws IOException {
        for (int k = start; k <= end; k++) {
            final int index = block + ZIGZAG[k];
            if (coefficients[index] != 0) this.refineCoefficient(coefficients, index, bit, entropy);
        }
    }

    private void refineCoefficient(final int[] coefficients, final int index, final int bit,
                                   final EntropyReader entropy) throws IOException {
        final int value = coefficients[index];
        final int correction = entropy.readBit();
        if ((Math.abs(value) & bit) == 0 && correction == 1) {
            coefficients[index] += value > 0 ? bit : -bit;
        }
    }

    private void forEachMcu(final Scan scan, final EntropyReader entropy, final BlockConsumer consumer) throws IOException {
        int restartCount = 0;
        int mcu = 0;
        final int total = this.mcusX * this.mcusY;
        for (int my = 0; my < this.mcusY; my++) {
            for (int mx = 0; mx < this.mcusX; mx++) {
                for (final Component component: scan.components) {
                    for (int y = 0; y < component.v; y++) {
                        for (int x = 0; x < component.h; x++) {
                            final int blockX = mx * component.h + x;
                            final int blockY = my * component.v + y;
                            consumer.accept(component, component.blockOffset(blockX, blockY));
                        }
                    }
                }
                mcu++;
                restartCount = this.advanceRestart(entropy, restartCount, mcu < total);
            }
        }
    }

    // NON-INTERLEAVED ITERATION: WALKS THE COMPONENT'S OWN BLOCK GRID (NOT THE MCU-PADDED ONE).
    // blockOffset() STILL USES THE MCU-PADDED blocksX STRIDE SO COEFFICIENTS LAND IN THE SHARED BUFFER.
    private void forEachBlock(final Component component, final EntropyReader entropy, final SingleBlockConsumer consumer) throws IOException {
        int restartCount = 0;
        int blockIndex = 0;
        final int total = component.widthInBlocks * component.heightInBlocks;
        for (int y = 0; y < component.heightInBlocks; y++) {
            for (int x = 0; x < component.widthInBlocks; x++) {
                consumer.accept(component.blockOffset(x, y));
                blockIndex++;
                restartCount = this.advanceRestart(entropy, restartCount, blockIndex < total);
            }
        }
    }

    private int advanceRestart(final EntropyReader entropy, final int count, final boolean moreData) throws IOException {
        if (this.restartInterval <= 0 || !moreData) return count;
        final int next = count + 1;
        if (next < this.restartInterval) return next;
        entropy.consumeRestart();
        this.resetPredictors();
        this.eobRun = 0;
        return 0;
    }

    private void resetPredictors() {
        if (this.components == null) return;
        for (final Component component: this.components) component.dc = 0;
    }

    private void validateTables(final Scan scan) throws XCodecException {
        for (final Component component: scan.components) {
            if (this.quantTables[component.quantTable] == null) {
                throw new XCodecException("Missing JPEG quantization table " + component.quantTable);
            }
            if (this.huffmanTables[0][component.dcTable] == null) {
                throw new XCodecException("Missing JPEG DC Huffman table " + component.dcTable);
            }
            if ((scan.se > 0 || !this.progressive) && this.huffmanTables[1][component.acTable] == null) {
                throw new XCodecException("Missing JPEG AC Huffman table " + component.acTable);
            }
        }
    }

    private void buildFrame() throws IOException {
        if (this.components == null) throw new XCodecException("JPEG frame header not found");
        for (final Component component: this.components) this.buildComponent(component);

        this.nativeFormat = this.resolveNativeFormat();
        this.outputFormat = this.requestedFormat == PixelFormat.BGRA ? PixelFormat.BGRA : this.nativeFormat;
        if (this.outputFormat == PixelFormat.BGRA) {
            this.directOut = ByteBuffer.allocateDirect(this.width * this.height * 4).order(ByteOrder.LITTLE_ENDIAN);
            this.planeOffsets = new int[] { 0 };
            this.planeSizes = new int[] { this.width * this.height * 4 };
            this.planeStrides = new int[] { 0 };
            if (this.components.length == 1) this.writeGrayscaleBgra();
            else this.writeColorBgra();
        } else {
            this.writeNative();
        }
        this.directOut.flip();
    }

    private PixelFormat resolveNativeFormat() throws XCodecException {
        if (this.components.length == 1) return PixelFormat.GRAY;
        final Component y = this.components[0];
        final Component cb = this.components[1];
        final Component cr = this.components[2];
        if (cb.h != cr.h || cb.v != cr.v) throw new XCodecException("Unsupported JPEG chroma sampling");
        if (y.h == cb.h && y.v == cb.v) return PixelFormat.YUV444P;
        if (y.h == cb.h * 2 && y.v == cb.v) return PixelFormat.YUV422P;
        if (y.h == cb.h * 2 && y.v == cb.v * 2) return PixelFormat.YUV420P;
        throw new XCodecException("Unsupported JPEG chroma sampling");
    }

    private void buildComponent(final Component component) throws XCodecException {
        final int[] quant = this.quantTables[component.quantTable];
        if (quant == null) throw new XCodecException("Missing JPEG quantization table " + component.quantTable);

        final int sampleWidth = component.blocksX * 8;
        final int sampleHeight = component.blocksY * 8;
        component.samples = new byte[sampleWidth * sampleHeight];
        final int[] tmp = new int[64];
        for (int by = 0; by < component.blocksY; by++) {
            for (int bx = 0; bx < component.blocksX; bx++) {
                final int block = component.blockOffset(bx, by);
                final int dst = by * 8 * sampleWidth + bx * 8;
                idct(component.coefficients, block, quant, tmp, component.samples, dst, sampleWidth);
            }
        }
    }

    // INTEGER IDCT (libjpeg "islow"): ROW PASS THEN COLUMN PASS WITH INLINED DEQUANTIZATION.
    // ROW PASS WRITES TO 64-INT SCRATCH; COLUMN PASS LEVEL-SHIFTS BY 128, CLAMPS, AND WRITES BYTES DIRECTLY.
    private static void idct(final int[] coef, final int block, final int[] quant,
                             final int[] tmp, final byte[] dst, final int dstOff, final int dstStride) {
        // FAST PATH: ENTIRE BLOCK IS DC ONLY (FLAT 8x8)
        if (isDcOnly(coef, block)) {
            final int dc = clamp(((coef[block] * quant[0] + 4) >> 3) + 128);
            final byte b = (byte) dc;
            for (int y = 0; y < 8; y++) {
                final int row = dstOff + y * dstStride;
                dst[row]     = b; dst[row + 1] = b; dst[row + 2] = b; dst[row + 3] = b;
                dst[row + 4] = b; dst[row + 5] = b; dst[row + 6] = b; dst[row + 7] = b;
            }
            return;
        }

        // ROW PASS
        for (int row = 0; row < 8; row++) {
            final int b = block + row * 8;
            final int q = row * 8;

            // FAST ROW PATH: ALL AC IN ROW ARE ZERO -> CONSTANT ROW
            if (coef[b + 1] == 0 && coef[b + 2] == 0 && coef[b + 3] == 0 &&
                coef[b + 4] == 0 && coef[b + 5] == 0 && coef[b + 6] == 0 && coef[b + 7] == 0) {
                final int dcval = (coef[b] * quant[q]) << PASS1_BITS;
                final int o = row * 8;
                tmp[o]     = dcval; tmp[o + 1] = dcval; tmp[o + 2] = dcval; tmp[o + 3] = dcval;
                tmp[o + 4] = dcval; tmp[o + 5] = dcval; tmp[o + 6] = dcval; tmp[o + 7] = dcval;
                continue;
            }

            int z2 = coef[b + 2] * quant[q + 2];
            int z3 = coef[b + 6] * quant[q + 6];
            int z1 = (z2 + z3) * FIX_0_541196100;
            int tmp2 = z1 + z3 * (-FIX_1_847759065);
            int tmp3 = z1 + z2 * FIX_0_765366865;

            z2 = coef[b] * quant[q];
            z3 = coef[b + 4] * quant[q + 4];
            int tmp0 = (z2 + z3) << CONST_BITS;
            int tmp1 = (z2 - z3) << CONST_BITS;

            final int tmp10 = tmp0 + tmp3;
            final int tmp13 = tmp0 - tmp3;
            final int tmp11 = tmp1 + tmp2;
            final int tmp12 = tmp1 - tmp2;

            tmp0 = coef[b + 7] * quant[q + 7];
            tmp1 = coef[b + 5] * quant[q + 5];
            tmp2 = coef[b + 3] * quant[q + 3];
            tmp3 = coef[b + 1] * quant[q + 1];

            z1 = tmp0 + tmp3;
            z2 = tmp1 + tmp2;
            z3 = tmp0 + tmp2;
            int z4 = tmp1 + tmp3;
            final int z5 = (z3 + z4) * FIX_1_175875602;

            tmp0 *= FIX_0_298631336;
            tmp1 *= FIX_2_053119869;
            tmp2 *= FIX_3_072711026;
            tmp3 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 *= -FIX_1_961570560;
            z4 *= -FIX_0_390180644;

            z3 += z5;
            z4 += z5;

            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            final int o = row * 8;
            tmp[o]     = (tmp10 + tmp3 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 7] = (tmp10 - tmp3 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 1] = (tmp11 + tmp2 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 6] = (tmp11 - tmp2 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 2] = (tmp12 + tmp1 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 5] = (tmp12 - tmp1 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 3] = (tmp13 + tmp0 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 4] = (tmp13 - tmp0 + PASS1_ROUND) >> PASS1_SHIFT;
        }

        // COLUMN PASS: WRITES INTO dst[] APPLYING LEVEL SHIFT +128 AND CLAMP TO 0..255
        for (int col = 0; col < 8; col++) {
            int z2 = tmp[2 * 8 + col];
            int z3 = tmp[6 * 8 + col];
            int z1 = (z2 + z3) * FIX_0_541196100;
            int tmp2 = z1 + z3 * (-FIX_1_847759065);
            int tmp3 = z1 + z2 * FIX_0_765366865;

            z2 = tmp[col];
            z3 = tmp[4 * 8 + col];
            int tmp0 = (z2 + z3) << CONST_BITS;
            int tmp1 = (z2 - z3) << CONST_BITS;

            final int tmp10 = tmp0 + tmp3;
            final int tmp13 = tmp0 - tmp3;
            final int tmp11 = tmp1 + tmp2;
            final int tmp12 = tmp1 - tmp2;

            tmp0 = tmp[7 * 8 + col];
            tmp1 = tmp[5 * 8 + col];
            tmp2 = tmp[3 * 8 + col];
            tmp3 = tmp[8 + col];

            z1 = tmp0 + tmp3;
            z2 = tmp1 + tmp2;
            z3 = tmp0 + tmp2;
            int z4 = tmp1 + tmp3;
            final int z5 = (z3 + z4) * FIX_1_175875602;

            tmp0 *= FIX_0_298631336;
            tmp1 *= FIX_2_053119869;
            tmp2 *= FIX_3_072711026;
            tmp3 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 *= -FIX_1_961570560;
            z4 *= -FIX_0_390180644;

            z3 += z5;
            z4 += z5;

            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            final int base = dstOff + col;
            dst[base]                 = (byte) clamp(((tmp10 + tmp3 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 7] = (byte) clamp(((tmp10 - tmp3 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride]     = (byte) clamp(((tmp11 + tmp2 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 6] = (byte) clamp(((tmp11 - tmp2 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 2] = (byte) clamp(((tmp12 + tmp1 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 5] = (byte) clamp(((tmp12 - tmp1 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 3] = (byte) clamp(((tmp13 + tmp0 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 4] = (byte) clamp(((tmp13 - tmp0 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
        }
    }

    private static boolean isDcOnly(final int[] coef, final int block) {
        for (int i = 1; i < 64; i++) {
            if (coef[block + i] != 0) return false;
        }
        return true;
    }

    private void writeNative() throws XCodecException {
        if (this.outputFormat == PixelFormat.GRAY) {
            this.directOut = ByteBuffer.allocateDirect(this.width * this.height).order(ByteOrder.LITTLE_ENDIAN);
            this.planeOffsets = new int[] { 0 };
            this.planeSizes = new int[] { this.width * this.height };
            this.planeStrides = new int[] { this.width };
            this.writePlane(this.components[0], this.width, this.height);
            return;
        }

        final Component y = this.components[0];
        final Component cb = this.components[1];
        final Component cr = this.components[2];
        final int chromaWidth = ceilDiv(this.width * cb.h, this.maxH);
        final int chromaHeight = ceilDiv(this.height * cb.v, this.maxV);
        final int ySize = this.width * this.height;
        final int chromaSize = chromaWidth * chromaHeight;

        this.directOut = ByteBuffer.allocateDirect(ySize + chromaSize * 2).order(ByteOrder.LITTLE_ENDIAN);
        this.planeOffsets = new int[] { 0, ySize, ySize + chromaSize };
        this.planeSizes = new int[] { ySize, chromaSize, chromaSize };
        this.planeStrides = new int[] { this.width, chromaWidth, chromaWidth };

        this.writePlane(y, this.width, this.height);
        this.writePlane(cb, chromaWidth, chromaHeight);
        this.writePlane(cr, chromaWidth, chromaHeight);
    }

    private void writePlane(final Component component, final int width, final int height) {
        final byte[] src = component.samples;
        final int srcStride = component.blocksX * 8;
        // FAST PATH: NO PADDING IN SAMPLE BUFFER -> SINGLE BULK COPY
        if (srcStride == width) {
            this.directOut.put(src, 0, width * height);
            return;
        }
        // ROW-BY-ROW BULK COPY (CHEAPER THAN PUT-BY-BYTE)
        for (int y = 0; y < height; y++) {
            this.directOut.put(src, y * srcStride, width);
        }
    }

    private void writeGrayscaleBgra() {
        final Component yComponent = this.components[0];
        final byte[] src = yComponent.samples;
        final int stride = yComponent.blocksX * 8;
        final ByteBuffer out = this.directOut;
        for (int y = 0; y < this.height; y++) {
            final int row = y * stride;
            for (int x = 0; x < this.width; x++) {
                final int g = src[row + x] & 0xFF;
                // BGRA LITTLE-ENDIAN: BYTE 0=B, 1=G, 2=R, 3=A
                out.putInt(0xFF000000 | (g << 16) | (g << 8) | g);
            }
        }
    }

    private void writeColorBgra() {
        final Component yc = this.components[0];
        final Component cbc = this.components[1];
        final Component crc = this.components[2];
        final byte[] ySrc = yc.samples;
        final byte[] cbSrc = cbc.samples;
        final byte[] crSrc = crc.samples;
        final int yStride = yc.blocksX * 8;
        final int cbStride = cbc.blocksX * 8;
        final int crStride = crc.blocksX * 8;

        // PRECOMPUTE PER-DESTINATION-COLUMN CHROMA SAMPLE INDICES (REMOVES MUL/DIV/MIN PER PIXEL)
        final int[] cbX = new int[this.width];
        final int[] crX = new int[this.width];
        final int cbSampleW = cbc.blocksX * 8;
        final int crSampleW = crc.blocksX * 8;
        for (int x = 0; x < this.width; x++) {
            cbX[x] = Math.min(cbSampleW - 1, (x * cbc.h) / this.maxH);
            crX[x] = Math.min(crSampleW - 1, (x * crc.h) / this.maxH);
        }

        final ByteBuffer out = this.directOut;
        final int cbSampleH = cbc.blocksY * 8;
        final int crSampleH = crc.blocksY * 8;
        for (int y = 0; y < this.height; y++) {
            final int yRow = y * yStride;
            final int cbRow = Math.min(cbSampleH - 1, (y * cbc.v) / this.maxV) * cbStride;
            final int crRow = Math.min(crSampleH - 1, (y * crc.v) / this.maxV) * crStride;
            for (int x = 0; x < this.width; x++) {
                final int yy = ySrc[yRow + x] & 0xFF;
                final int cb = cbSrc[cbRow + cbX[x]] & 0xFF;
                final int cr = crSrc[crRow + crX[x]] & 0xFF;
                final int r = clamp(yy + CR_TO_R[cr]);
                final int g = clamp(yy + ((CB_TO_G[cb] + CR_TO_G[cr]) >> 16));
                final int b = clamp(yy + CB_TO_B[cb]);
                out.putInt(0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
    }

    private Component component(final int id) throws XCodecException {
        for (final Component component: this.components) {
            if (component.id == id) return component;
        }
        throw new XCodecException("JPEG scan references unknown component " + id);
    }

    private void skipSegment() throws IOException {
        final int length = this.readSegmentLength();
        skip(this.data, length - 2);
    }

    private int readSegmentLength() throws IOException {
        final int length = readU16(this.data);
        if (length < 2) throw new XCodecException("Invalid JPEG segment length " + length);
        if (this.data.remaining() < length - 2) throw new EOFException("Truncated JPEG segment");
        return length;
    }

    private int checkedSegmentEnd(final int length) throws IOException {
        final int end = this.data.position() + length - 2;
        if (end > this.data.limit()) throw new EOFException("Truncated JPEG segment");
        return end;
    }

    private static int readMarker(final ByteBuffer input) throws IOException {
        while (input.hasRemaining()) {
            if (readU8(input) != 0xFF) continue;
            int marker;
            do {
                if (!input.hasRemaining()) throw new EOFException("Truncated JPEG marker");
                marker = readU8(input);
            } while (marker == 0xFF);
            if (marker == 0x00) continue;
            return marker;
        }
        throw new EOFException("JPEG marker not found");
    }

    private static boolean hasSegmentLength(final int marker) {
        return marker >= 0xC0 && marker <= 0xFE && marker != SOI && marker != EOI && !isRestart(marker);
    }

    private static boolean isApp(final int marker) {
        return marker >= 0xE0 && marker <= 0xEF;
    }

    private static boolean isRestart(final int marker) {
        return marker >= 0xD0 && marker <= 0xD7;
    }

    private static int readU8(final ByteBuffer input) throws IOException {
        if (!input.hasRemaining()) throw new EOFException("Unexpected JPEG EOF");
        return input.get() & 0xFF;
    }

    private static int readU16(final ByteBuffer input) throws IOException {
        return (readU8(input) << 8) | readU8(input);
    }

    private static void skip(final ByteBuffer input, final int bytes) throws IOException {
        if (bytes < 0 || input.remaining() < bytes) throw new EOFException("Unexpected JPEG EOF");
        input.position(input.position() + bytes);
    }

    private static int ceilDiv(final int value, final int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int clamp(final int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static String hex(final int value) {
        return Integer.toHexString(value).toUpperCase();
    }

    @FunctionalInterface
    private interface BlockConsumer {
        void accept(Component component, int block) throws IOException;
    }

    @FunctionalInterface
    private interface SingleBlockConsumer {
        void accept(int block) throws IOException;
    }

    private record Scan(Component[] components, int ss, int se, int ah, int al) {}

    private static final class Component {
        final int id;
        final int h;
        final int v;
        final int quantTable;
        int dcTable;
        int acTable;
        int blocksX;
        int blocksY;
        int widthInBlocks;
        int heightInBlocks;
        int dc;
        int[] coefficients;
        byte[] samples;

        Component(final int id, final int h, final int v, final int quantTable) {
            this.id = id;
            this.h = h;
            this.v = v;
            this.quantTable = quantTable;
        }

        int blockOffset(final int x, final int y) {
            return (y * this.blocksX + x) * 64;
        }
    }

    private static final int LUT_BITS = 8;
    private static final int LUT_SIZE = 1 << LUT_BITS;

    private static final class HuffmanTable {
        private final int[] maxCode = new int[18];
        private final int[] valueOffset = new int[17];
        private final int[] values;
        // PER-PREFIX FAST LOOKUP: ENTRY 0 = NO MATCH (CODE LONGER THAN LUT_BITS),
        // OTHERWISE (LENGTH << 8) | SYMBOL.  LENGTH IN 1..LUT_BITS, SYMBOL IN 0..255.
        private final int[] lut = new int[LUT_SIZE];

        HuffmanTable(final int[] counts, final int[] values) {
            this.values = values;
            Arrays.fill(this.maxCode, -1);
            int code = 0;
            int index = 0;
            for (int length = 1; length <= 16; length++) {
                final int count = counts[length];
                if (count > 0) {
                    this.maxCode[length] = code + count - 1;
                    this.valueOffset[length] = index - code;
                    // FILL LOOKUP TABLE FOR CODES THAT FIT IN LUT_BITS
                    if (length <= LUT_BITS) {
                        for (int n = 0; n < count; n++) {
                            final int sym = values[index + n] & 0xFF;
                            final int entry = (length << 8) | sym;
                            final int prefix = (code + n) << (LUT_BITS - length);
                            final int suffixes = 1 << (LUT_BITS - length);
                            for (int s = 0; s < suffixes; s++) this.lut[prefix | s] = entry;
                        }
                    }
                    index += count;
                    code += count;
                }
                code <<= 1;
            }
            this.maxCode[17] = Integer.MAX_VALUE;
        }

        int decode(final EntropyReader r) throws IOException {
            if (r.bitCount < LUT_BITS) r.refill();
            if (r.bitCount >= LUT_BITS) {
                final int peek = (int) (r.bitBuf >>> (r.bitCount - LUT_BITS)) & (LUT_SIZE - 1);
                final int entry = this.lut[peek];
                if (entry != 0) {
                    r.bitCount -= entry >>> 8;
                    return entry & 0xFF;
                }
            }
            // SLOW PATH: WALK BIT-BY-BIT (FOR CODES > LUT_BITS OR LOW BUFFER STATE NEAR EOF)
            int code = 0;
            for (int length = 1; length <= 16; length++) {
                code = (code << 1) | r.readBit();
                if (code <= this.maxCode[length]) return this.values[this.valueOffset[length] + code];
            }
            throw new XCodecException("Invalid JPEG Huffman code");
        }
    }

    private static final class EntropyReader {
        private final ByteBuffer input;
        long bitBuf;
        int bitCount;
        private int pendingMarker = -1;

        EntropyReader(final ByteBuffer input) {
            this.input = input;
        }

        // FAST REFILL: ACCUMULATES UP TO 32 BITS INTO bitBuf. STOPS AT TRUNCATION OR EMBEDDED MARKER.
        void refill() throws IOException {
            while (this.bitCount <= 24) {
                if (this.pendingMarker >= 0 || !this.input.hasRemaining()) return;
                final int v = this.input.get() & 0xFF;
                if (v == 0xFF) {
                    int marker;
                    do {
                        if (!this.input.hasRemaining()) throw new EOFException("Truncated JPEG entropy stream");
                        marker = this.input.get() & 0xFF;
                    } while (marker == 0xFF);
                    if (marker != 0x00) {
                        this.pendingMarker = marker;
                        return;
                    }
                    // STUFFED 0xFF00 -> EMIT REAL 0xFF
                    this.bitBuf = (this.bitBuf << 8) | 0xFF;
                } else {
                    this.bitBuf = (this.bitBuf << 8) | v;
                }
                this.bitCount += 8;
            }
        }

        int readBit() throws IOException {
            if (this.bitCount == 0) {
                this.refill();
                if (this.bitCount == 0) throw new EOFException("Unexpected JPEG marker FF" + hex(this.pendingMarker) + " in entropy stream");
            }
            this.bitCount--;
            return (int) (this.bitBuf >>> this.bitCount) & 1;
        }

        int readBits(final int count) throws IOException {
            if (count == 0) return 0;
            if (this.bitCount < count) {
                this.refill();
                if (this.bitCount < count) throw new EOFException("Unexpected JPEG marker FF" + hex(this.pendingMarker) + " in entropy stream");
            }
            this.bitCount -= count;
            return (int) (this.bitBuf >>> this.bitCount) & ((1 << count) - 1);
        }

        int receiveExtend(final int count) throws IOException {
            if (count == 0) return 0;
            final int value = this.readBits(count);
            final int threshold = 1 << (count - 1);
            return value < threshold ? value - ((1 << count) - 1) : value;
        }

        int finishScan() throws IOException {
            this.bitCount = 0;
            this.bitBuf = 0L;
            if (this.pendingMarker >= 0) {
                final int marker = this.pendingMarker;
                this.pendingMarker = -1;
                return marker;
            }
            while (this.input.hasRemaining()) {
                final int value = readU8(this.input);
                if (value != 0xFF) continue;
                int marker;
                do {
                    if (!this.input.hasRemaining()) return EOI;
                    marker = readU8(this.input);
                } while (marker == 0xFF);
                if (marker == 0x00 || isRestart(marker)) continue;
                return marker;
            }
            return EOI;
        }

        void consumeRestart() throws IOException {
            this.bitCount = 0;
            this.bitBuf = 0L;
            int marker = -1;
            if (this.pendingMarker >= 0) {
                marker = this.pendingMarker;
                this.pendingMarker = -1;
            } else {
                while (this.input.hasRemaining()) {
                    final int value = readU8(this.input);
                    if (value != 0xFF) continue;
                    do {
                        if (!this.input.hasRemaining()) throw new EOFException("Truncated JPEG restart marker");
                        marker = readU8(this.input);
                    } while (marker == 0xFF);
                    break;
                }
            }
            if (!isRestart(marker)) throw new XCodecException("Expected JPEG restart marker");
        }
    }
}
