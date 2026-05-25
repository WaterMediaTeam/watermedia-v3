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

    private static final double[] IDCT_SCALE = {
            1.0 / Math.sqrt(2.0), 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0
    };
    private static final double[][] IDCT_COS = new double[8][8];

    static {
        for (int x = 0; x < 8; x++) {
            for (int u = 0; u < 8; u++) {
                IDCT_COS[x][u] = Math.cos(((2 * x + 1) * u * Math.PI) / 16.0);
            }
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

    public JPEGReader(final ByteBuffer data) throws IOException {
        this(data, null);
    }

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
        final int length = readSegmentLength();
        if (length < 8) throw new XCodecException("Truncated JPEG frame header");
        final int end = checkedSegmentEnd(length);
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
            component.blocksX = this.mcusX * component.h;
            component.blocksY = this.mcusY * component.v;
            component.coefficients = new int[component.blocksX * component.blocksY * 64];
        }
        this.data.position(end);
    }

    private void readQuantTables() throws IOException {
        final int length = readSegmentLength();
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
        final int length = readSegmentLength();
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
        final int length = readSegmentLength();
        if (length != 4) throw new XCodecException("Invalid JPEG DRI segment length");
        this.restartInterval = readU16(this.data);
    }

    private Scan readScanHeader() throws IOException {
        if (this.components == null) throw new XCodecException("JPEG scan before frame header");
        final int length = readSegmentLength();
        final int end = checkedSegmentEnd(length);
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

    private void forEachBlock(final Component component, final EntropyReader entropy, final SingleBlockConsumer consumer) throws IOException {
        int restartCount = 0;
        int blockIndex = 0;
        final int total = component.blocksX * component.blocksY;
        for (int y = 0; y < component.blocksY; y++) {
            for (int x = 0; x < component.blocksX; x++) {
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
        final double[] temp = new double[64];
        final int[] out = new int[64];
        for (int by = 0; by < component.blocksY; by++) {
            for (int bx = 0; bx < component.blocksX; bx++) {
                final int block = component.blockOffset(bx, by);
                this.idct(component.coefficients, block, quant, temp, out);
                for (int y = 0; y < 8; y++) {
                    final int dst = (by * 8 + y) * sampleWidth + bx * 8;
                    for (int x = 0; x < 8; x++) component.samples[dst + x] = (byte) out[y * 8 + x];
                }
            }
        }
    }

    private void idct(final int[] coefficients, final int block, final int[] quant,
                      final double[] temp, final int[] out) {
        boolean dcOnly = true;
        for (int i = 1; i < 64; i++) {
            if (coefficients[block + i] != 0) {
                dcOnly = false;
                break;
            }
        }
        if (dcOnly) {
            Arrays.fill(out, clamp((int) Math.round((coefficients[block] * quant[0]) / 8.0 + 128.0)));
            return;
        }

        for (int v = 0; v < 8; v++) {
            final int row = v * 8;
            for (int x = 0; x < 8; x++) {
                double sum = 0.0;
                for (int u = 0; u < 8; u++) {
                    sum += IDCT_SCALE[u] * coefficients[block + row + u] * quant[row + u] * IDCT_COS[x][u];
                }
                temp[row + x] = sum;
            }
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                double sum = 0.0;
                for (int v = 0; v < 8; v++) {
                    sum += IDCT_SCALE[v] * temp[v * 8 + x] * IDCT_COS[y][v];
                }
                out[y * 8 + x] = clamp((int) Math.round(sum * 0.25 + 128.0));
            }
        }
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
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                this.directOut.put((byte) component.sampleRaw(x, y));
            }
        }
    }

    private void writeGrayscaleBgra() {
        final Component yComponent = this.components[0];
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                final int gray = yComponent.sample(x, y, this.maxH, this.maxV);
                this.directOut.put((byte) gray);
                this.directOut.put((byte) gray);
                this.directOut.put((byte) gray);
                this.directOut.put((byte) 0xFF);
            }
        }
    }

    private void writeColorBgra() {
        final Component yComponent = this.components[0];
        final Component cbComponent = this.components[1];
        final Component crComponent = this.components[2];
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                final int yy = yComponent.sample(x, y, this.maxH, this.maxV);
                final int cb = cbComponent.sample(x, y, this.maxH, this.maxV) - 128;
                final int cr = crComponent.sample(x, y, this.maxH, this.maxV) - 128;
                final int r = clamp((int) Math.round(yy + 1.402 * cr));
                final int g = clamp((int) Math.round(yy - 0.34414 * cb - 0.71414 * cr));
                final int b = clamp((int) Math.round(yy + 1.772 * cb));
                this.directOut.put((byte) b);
                this.directOut.put((byte) g);
                this.directOut.put((byte) r);
                this.directOut.put((byte) 0xFF);
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
        final int length = readSegmentLength();
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

        int sample(final int x, final int y, final int maxH, final int maxV) {
            final int sampleWidth = this.blocksX * 8;
            final int sampleHeight = this.blocksY * 8;
            final int sx = Math.min(sampleWidth - 1, (x * this.h) / maxH);
            final int sy = Math.min(sampleHeight - 1, (y * this.v) / maxV);
            return this.samples[sy * sampleWidth + sx] & 0xFF;
        }

        int sampleRaw(final int x, final int y) {
            final int sampleWidth = this.blocksX * 8;
            final int sampleHeight = this.blocksY * 8;
            final int sx = Math.min(sampleWidth - 1, x);
            final int sy = Math.min(sampleHeight - 1, y);
            return this.samples[sy * sampleWidth + sx] & 0xFF;
        }
    }

    private static final class HuffmanTable {
        private final int[] minCode = new int[17];
        private final int[] maxCode = new int[18];
        private final int[] valueOffset = new int[17];
        private final int[] values;

        HuffmanTable(final int[] counts, final int[] values) {
            this.values = values;
            Arrays.fill(this.maxCode, -1);
            int code = 0;
            int index = 0;
            for (int length = 1; length <= 16; length++) {
                final int count = counts[length];
                if (count > 0) {
                    this.minCode[length] = code;
                    this.maxCode[length] = code + count - 1;
                    this.valueOffset[length] = index - code;
                    index += count;
                    code += count;
                }
                code <<= 1;
            }
            this.maxCode[17] = Integer.MAX_VALUE;
        }

        int decode(final EntropyReader reader) throws IOException {
            int code = 0;
            for (int length = 1; length <= 16; length++) {
                code = (code << 1) | reader.readBit();
                if (code <= this.maxCode[length]) return this.values[this.valueOffset[length] + code];
            }
            throw new XCodecException("Invalid JPEG Huffman code");
        }
    }

    private static final class EntropyReader {
        private final ByteBuffer input;
        private int bits;
        private int bitCount;
        private int pendingMarker = -1;

        EntropyReader(final ByteBuffer input) {
            this.input = input;
        }

        int readBit() throws IOException {
            if (this.bitCount == 0) {
                this.bits = this.readEntropyByte();
                this.bitCount = 8;
            }
            this.bitCount--;
            return (this.bits >>> this.bitCount) & 1;
        }

        int readBits(final int count) throws IOException {
            int value = 0;
            for (int i = 0; i < count; i++) value = (value << 1) | this.readBit();
            return value;
        }

        int receiveExtend(final int count) throws IOException {
            if (count == 0) return 0;
            final int value = this.readBits(count);
            final int threshold = 1 << (count - 1);
            return value < threshold ? value - ((1 << count) - 1) : value;
        }

        int finishScan() throws IOException {
            this.bitCount = 0;
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

        private int readEntropyByte() throws IOException {
            if (this.pendingMarker >= 0) throw new EOFException("Unexpected JPEG marker in entropy stream");
            while (this.input.hasRemaining()) {
                final int value = readU8(this.input);
                if (value != 0xFF) return value;

                int marker;
                do {
                    if (!this.input.hasRemaining()) throw new EOFException("Truncated JPEG entropy stream");
                    marker = readU8(this.input);
                } while (marker == 0xFF);

                if (marker == 0x00) return 0xFF;
                this.pendingMarker = marker;
                throw new EOFException("Unexpected JPEG marker FF" + hex(marker) + " in entropy stream");
            }
            throw new EOFException("Truncated JPEG entropy stream");
        }
    }
}
