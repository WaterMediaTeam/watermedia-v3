package org.watermedia.api.codecs.decoders;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.png.ACTL;
import org.watermedia.api.codecs.common.png.BKGD;
import org.watermedia.api.codecs.common.png.CHRM;
import org.watermedia.api.codecs.common.png.CHUNK;
import org.watermedia.api.codecs.common.png.CICP;
import org.watermedia.api.codecs.common.png.CLLI;
import org.watermedia.api.codecs.common.png.ColorType;
import org.watermedia.api.codecs.common.png.EXIF;
import org.watermedia.api.codecs.common.png.FCTL;
import org.watermedia.api.codecs.common.png.FDAT;
import org.watermedia.api.codecs.common.png.GAMA;
import org.watermedia.api.codecs.common.png.HIST;
import org.watermedia.api.codecs.common.png.ICCP;
import org.watermedia.api.codecs.common.png.IDAT;
import org.watermedia.api.codecs.common.png.IEND;
import org.watermedia.api.codecs.common.png.IHDR;
import org.watermedia.api.codecs.common.png.ITXT;
import org.watermedia.api.codecs.common.png.MDCV;
import org.watermedia.api.codecs.common.png.PHYS;
import org.watermedia.api.codecs.common.png.PLTE;
import org.watermedia.api.codecs.common.png.SBIT;
import org.watermedia.api.codecs.common.png.SPLT;
import org.watermedia.api.codecs.common.png.SRGB;
import org.watermedia.api.codecs.common.png.TEXT;
import org.watermedia.api.codecs.common.png.TIME;
import org.watermedia.api.codecs.common.png.TRNS;
import org.watermedia.api.codecs.common.png.ZTXT;
import org.watermedia.api.util.ColorSpace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Streaming PNG / APNG reader following PNG Specification Third Edition (W3C Recommendation 24 June 2025).
 *
 * <p>Receives a {@link ByteBuffer} positioned immediately after the 8-byte PNG signature
 * (the signature is consumed by {@code CodecsAPI} before construction). The constructor reads
 * chunks until the first {@code IDAT} or {@code fcTL}, decoding header / palette / animation
 * control / metadata. Each {@link #next()} pulls exactly the chunks for the next frame.
 *
 * @see <a href="https://www.w3.org/TR/png-3/">PNG Specification Third Edition</a>
 */
public class PNGReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(PNGReader.class.getSimpleName());

    // FILTER TYPES (FILTER METHOD 0)
    private static final int FILTER_NONE = 0;
    private static final int FILTER_SUB = 1;
    private static final int FILTER_UP = 2;
    private static final int FILTER_AVERAGE = 3;
    private static final int FILTER_PAETH = 4;

    // ADAM7 INTERLACING PASS PARAMETERS
    private static final int[] ADAM7_X_START = {0, 4, 0, 2, 0, 1, 0};
    private static final int[] ADAM7_Y_START = {0, 0, 4, 0, 2, 0, 1};
    private static final int[] ADAM7_X_STEP = {8, 8, 4, 4, 2, 2, 1};
    private static final int[] ADAM7_Y_STEP = {8, 8, 8, 4, 4, 2, 2};

    // GAMMA
    private static final float SRGB_GAMMA = 2.2f;
    private static final float DEFAULT_GAMMA = 2.2f;
    private static final float DISPLAY_GAMMA = 2.2f;

    // PARSED STATE
    private IHDR ihdr;
    private PLTE plte;
    private TRNS trns;
    private BKGD bkgd;
    private ACTL actl;
    private GAMA gamma;
    private SRGB srgb;
    private CICP cicp;
    private final ImageMetadata metadata = new ImageMetadata();
    private final Map<String, List<String>> texts = new HashMap<>();
    private final Map<String, List<String>> compressedTexts = new HashMap<>();
    private final Map<String, List<String>> internationalTexts = new HashMap<>();
    private final Map<String, List<byte[]>> ancillaryChunks = new HashMap<>();
    private float[] gammaLUT;

    // STREAMING STATE
    private CHUNK pendingChunk;     // next chunk to consume at start of next()
    private boolean done;            // true once IEND seen or EOF reached
    private int framesDelivered;
    private final ImageData.Scan scan;

    // CANVAS / OUTPUT
    private int[][] outputBuffer;    // composited canvas (ARGB)
    private int[][] previousBuffer;  // lazily saved canvas for DISPOSE_OP_PREVIOUS
    private int[][] frameBuffer;     // reusable APNG frame decode target
    private ByteBuffer directOut;    // BGRA result, reused
    private IntBuffer directOutInts;
    private byte[] passCurrentRow = new byte[0];
    private byte[] passPreviousRow = new byte[0];
    private final Inflater inflater = new Inflater();
    private boolean inflaterClosed;

    public PNGReader(final ByteBuffer data) throws IOException {
        super(data);
        this.data.order(ByteOrder.BIG_ENDIAN);
        this.scan = scan(this.data.duplicate().order(ByteOrder.BIG_ENDIAN));

        this.parseUntilFirstFrame();
        if (this.ihdr == null) throw new XCodecException("Missing IHDR chunk");

        this.gammaLUT = buildGammaLUT(this.gamma, this.srgb, this.cicp);
        this.outputBuffer = new int[this.ihdr.height()][this.ihdr.width()];
        this.directOut = ByteBuffer.allocateDirect(this.ihdr.width() * this.ihdr.height() * 4).order(ByteOrder.LITTLE_ENDIAN);
        this.directOutInts = this.directOut.asIntBuffer();

        // INIT CANVAS WITH BKGD OR TRANSPARENT
        if (this.bkgd != null) {
            this.fillBuffer(this.outputBuffer, this.ihdr.width(), this.ihdr.height(), this.bkgdToARGB(this.bkgd, this.ihdr.depth(), this.plte));
        } else {
            this.clearBuffer(this.outputBuffer, this.ihdr.width(), this.ihdr.height());
        }
    }

    @Override public int width() { return this.ihdr.width(); }
    @Override public int height() { return this.ihdr.height(); }
    @Override public ColorSpace pixelFormat() { return ColorSpace.BGRA; }
    @Override public ImageData.Scan scan() { return this.scan; }
    @Override public ImageMetadata metadata() { return this.metadata.empty() ? ImageMetadata.EMPTY : this.metadata; }
    @Override public boolean variableFrameRate() { return this.scan.frameCount() > 1; }

    @Override
    public void close() {
        if (this.inflaterClosed) return;
        this.inflater.end();
        this.inflaterClosed = true;
    }

    @Override
    public boolean hasNext() {
        return !this.done && this.pendingChunk != null;
    }

    @Override
    public ByteBuffer next() throws IOException {
        if (!this.hasNext()) throw new java.io.EOFException("No more PNG frames");

        FCTL fctl = null;
        final List<ChunkSlice> compressed = new ArrayList<>();

        // PROCESS THE INITIAL PENDING CHUNK
        CHUNK c = this.pendingChunk;
        this.pendingChunk = null;
        if (c.type() == FCTL.SIGNATURE) {
            fctl = FCTL.convert(c, ByteOrder.BIG_ENDIAN);
        } else if (c.type() == IDAT.SIGNATURE) {
            compressed.add(new ChunkSlice(c.data(), 0, c.data().length));
        } else {
            // shouldn't reach here (parseUntilFirstFrame only stops on IDAT/fcTL)
            this.done = true;
            throw new XCodecException("Unexpected PNG frame boundary: " + c.typeName());
        }

        // READ CHUNKS UNTIL NEXT FRAME BOUNDARY (next fcTL) OR IEND / EOF
        while (true) {
            try {
                c = CHUNK.read(this.data);
            } catch (final java.io.EOFException eof) {
                this.done = true;
                break;
            }
            if (c.corrupted() && WaterMediaConfig.decoders.pngFailOnCorruptedData) {
                throw new XCodecException("Chunk CRC mismatch on " + c.typeName() + " - data corrupted");
            }

            final int t = c.type();
            if (t == IDAT.SIGNATURE) {
                compressed.add(new ChunkSlice(c.data(), 0, c.data().length));
            } else if (t == FDAT.SIGNATURE) {
                final byte[] data = c.data();
                if (data.length < 4) throw new XCodecException("Truncated fdAT chunk");
                compressed.add(new ChunkSlice(data, 4, data.length - 4));
            } else if (t == FCTL.SIGNATURE) {
                // Start of next frame.
                this.pendingChunk = c;
                break;
            } else if (t == IEND.SIGNATURE) {
                this.done = true;
                break;
            } else {
                // mid-stream metadata or unknown ancillary - dispatch through ancillary handler
                this.handleAncillary(c);
            }
        }

        if (compressed.isEmpty()) {
            // No data for this "frame" (rare). Skip and try next.
            this.done = true;
            throw new XCodecException("PNG frame without compressed data");
        }

        final IHDR frameIhdr = (fctl != null)
                ? new IHDR(fctl.width(), fctl.height(), this.ihdr.depth(), this.ihdr.colorType(),
                this.ihdr.compression(), this.ihdr.filter(), this.ihdr.interlace())
                : this.ihdr;

        // DECODE
        final byte[] decompressed = this.inflate(compressed, frameIhdr);

        final int[][] framePixels = this.decodeData(
                decompressed,
                frameIhdr,
                fctl != null ? this.frameBuffer() : this.outputBuffer,
                this.plte,
                this.trns
        );

        if (this.gammaLUT != null) applyGammaCorrection(framePixels, this.gammaLUT);
        if (this.bkgd != null) this.flattenAlpha(framePixels, this.bkgd, this.ihdr.depth(), this.plte);

        if (fctl != null) {
            // APNG: composite onto canvas with blend/dispose
            if ((fctl.dispose() & 0xFF) == FCTL.DISPOSE_OP_PREVIOUS) {
                this.copyBuffer(this.outputBuffer, this.previousBuffer(), this.ihdr.width(), this.ihdr.height());
            }
            this.applyBlendOp(this.outputBuffer, framePixels, fctl);
            this.currentDelay = fctl.delayMillis();
            this.toBGRAInto(this.outputBuffer, this.ihdr.width(), this.ihdr.height(), this.directOut);
            this.applyDispose(this.outputBuffer, this.previousBuffer, fctl, this.bkgd, this.ihdr.depth(), this.plte, this.ihdr.width(), this.ihdr.height());
        } else {
            this.currentDelay = 0L;
            this.toBGRAInto(this.outputBuffer, this.ihdr.width(), this.ihdr.height(), this.directOut);
        }

        this.framesDelivered++;
        this.currentFrame = this.directOut;
        return this.directOut;
    }

    // PARSE CHUNKS UNTIL FIRST IDAT/fcTL (POPULATES pendingChunk OR SETS done)
    private void parseUntilFirstFrame() throws IOException {
        while (true) {
            final CHUNK c;
            try {
                c = CHUNK.read(this.data);
            } catch (final java.io.EOFException eof) {
                this.done = true;
                return;
            }
            if (c.corrupted() && WaterMediaConfig.decoders.pngFailOnCorruptedData) {
                throw new XCodecException("Chunk CRC mismatch on " + c.typeName() + " - data corrupted");
            }

            final int t = c.type();
            if (t == IHDR.SIGNATURE) {
                this.ihdr = IHDR.convert(c, ByteOrder.BIG_ENDIAN);
                this.validateIHDR(this.ihdr);
            } else if (t == PLTE.SIGNATURE) {
                this.plte = PLTE.convert(c);
            } else if (t == TRNS.SIGNATURE) {
                if (this.ihdr == null) throw new XCodecException("tRNS before IHDR");
                this.trns = TRNS.convert(c, this.ihdr.colorType());
            } else if (t == BKGD.SIGNATURE) {
                if (WaterMediaConfig.decoders.pngUseBKGDChunk) {
                    if (this.ihdr == null) throw new XCodecException("bKGD before IHDR");
                    this.bkgd = BKGD.convert(c, this.ihdr.colorType(), this.ihdr.depth());
                }
            } else if (t == ACTL.SIGNATURE) {
                this.actl = ACTL.convert(c, ByteOrder.BIG_ENDIAN);
            } else if (t == FCTL.SIGNATURE || t == IDAT.SIGNATURE) {
                this.pendingChunk = c;
                return;
            } else if (t == IEND.SIGNATURE) {
                this.done = true;
                return;
            } else {
                this.handleAncillary(c);
            }
        }
    }

    private void handleAncillary(final CHUNK c) throws IOException {
        final int t = c.type();
        if (t == GAMA.SIGNATURE) {
            this.gamma = GAMA.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_GAMMA, this.gamma);
        }
        else if (t == CHRM.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_CHROMATICITIES, CHRM.convert(c));
        else if (t == SRGB.SIGNATURE) {
            this.srgb = SRGB.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_SRGB, this.srgb);
        }
        else if (t == ICCP.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_ICC_PROFILE, ICCP.convert(c));
        else if (t == SBIT.SIGNATURE) {
            if (this.ihdr == null) throw new XCodecException("sBIT before IHDR");
            this.metadata.put(CodecsAPI.PNG_METAKEY_SIGNIFICANT_BITS, SBIT.convert(c, this.ihdr.colorType()));
        }
        else if (t == CICP.SIGNATURE) {
            this.cicp = CICP.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_CICP, this.cicp);
        }
        else if (t == MDCV.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_MASTERING_DISPLAY_COLOR_VOLUME, MDCV.convert(c));
        else if (t == CLLI.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_CONTENT_LIGHT_LEVEL, CLLI.convert(c));
        else if (t == TEXT.SIGNATURE) {
            final TEXT text = TEXT.convert(c);
            this.storePngText(this.texts, CodecsAPI.PNG_METAKEY_TEXT, text.keyword(), text.text());
        }
        else if (t == ZTXT.SIGNATURE) {
            final ZTXT text = ZTXT.convert(c);
            this.storePngText(this.compressedTexts, CodecsAPI.PNG_METAKEY_COMPRESSED_TEXT, text.keyword(), text.getText());
        }
        else if (t == ITXT.SIGNATURE) {
            final ITXT text = ITXT.convert(c);
            this.storePngText(this.internationalTexts, CodecsAPI.PNG_METAKEY_INTERNATIONAL_TEXT, text.keyword(), text.getText());
        }
        else if (t == PHYS.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_PHYSICAL_PIXEL_DIMENSIONS, PHYS.convert(c));
        else if (t == HIST.SIGNATURE) {
            if (this.plte == null) throw new XCodecException("hIST before PLTE");
            this.metadata.put(CodecsAPI.PNG_METAKEY_HISTOGRAM, HIST.convert(c, this.plte.size()));
        }
        else if (t == SPLT.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_SUGGESTED_PALETTES, SPLT.convert(c));
        else if (t == EXIF.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_EXIF, EXIF.convert(c));
        else if (t == TIME.SIGNATURE) this.metadata.put(CodecsAPI.PNG_METAKEY_LAST_MODIFIED_TIME, TIME.convert(c));
        else {
            // UNKNOWN: ignore if ancillary (lowercase first letter), throw if critical
            final int firstByte = (t >> 24) & 0xFF;
            if ((firstByte & 0x20) == 0) {
                throw new XCodecException("Unknown critical chunk: " + c.typeName());
            }
            this.ancillaryChunks.computeIfAbsent(c.typeName(), ignored -> new ArrayList<>()).add(c.data().clone());
            this.metadata.put(CodecsAPI.PNG_METAKEY_ANCILLARY_CHUNK, this.ancillaryChunks);
        }
    }

    // ----- DECODE HELPERS (preserved from legacy PNG decoder) -----

    private void storePngText(final Map<String, List<String>> target, final String key, final String keyword, final String value) {
        if (keyword == null || keyword.isBlank() || value == null || value.isBlank()) return;
        target.computeIfAbsent(keyword, ignored -> new ArrayList<>()).add(value);
        this.metadata.put(key, target);
        switch (keyword) {
            case TEXT.KEYWORD_TITLE -> this.metadata.title(value);
            case TEXT.KEYWORD_AUTHOR -> this.metadata.author(value);
            case TEXT.KEYWORD_DESCRIPTION -> this.metadata.description(value);
            case TEXT.KEYWORD_COPYRIGHT -> this.metadata.copyright(value);
            case TEXT.KEYWORD_COMMENT -> this.metadata.comment(value);
            case TEXT.KEYWORD_CREATION_TIME -> this.metadata.creationTime(value);
            case TEXT.KEYWORD_SOFTWARE -> this.metadata.software(value);
            case TEXT.KEYWORD_SOURCE -> this.metadata.source(value);
        }
    }

    private static float[] buildGammaLUT(final GAMA gamma, final SRGB srgb, final CICP cicp) {
        float fileGamma;
        if (srgb != null) {
            fileGamma = SRGB_GAMMA;
        } else if (cicp != null) {
            if (cicp.isSRGB()) fileGamma = SRGB_GAMMA;
            else if (cicp.isHDR()) return null;
            else fileGamma = DEFAULT_GAMMA;
        } else if (gamma != null) {
            fileGamma = gamma.gammaValue();
        } else {
            return null;
        }
        if (Math.abs(fileGamma - DISPLAY_GAMMA) < 0.01f) return null;

        final float[] lut = new float[256];
        final float exponent = fileGamma / DISPLAY_GAMMA;
        for (int i = 0; i < 256; i++) lut[i] = (float) Math.pow(i / 255.0, exponent);
        return lut;
    }

    private static void applyGammaCorrection(final int[][] pixels, final float[] lut) {
        for (int y = 0; y < pixels.length; y++) {
            for (int x = 0; x < pixels[y].length; x++) {
                final int argb = pixels[y][x];
                final int a = (argb >> 24) & 0xFF;
                final int r = (argb >> 16) & 0xFF;
                final int g = (argb >> 8) & 0xFF;
                final int b = argb & 0xFF;
                pixels[y][x] = (a << 24) | (Math.round(lut[r] * 255) << 16) | (Math.round(lut[g] * 255) << 8) | Math.round(lut[b] * 255);
            }
        }
    }

    private void flattenAlpha(final int[][] pixels, final BKGD bkgd, final int depth, final PLTE plte) {
        final int bgColor = this.bkgdToARGB(bkgd, depth, plte);
        final int bgR = (bgColor >> 16) & 0xFF;
        final int bgG = (bgColor >> 8) & 0xFF;
        final int bgB = bgColor & 0xFF;

        for (int y = 0; y < pixels.length; y++) {
            for (int x = 0; x < pixels[y].length; x++) {
                final int argb = pixels[y][x];
                final int alpha = (argb >> 24) & 0xFF;
                if (alpha == 255) continue;
                if (alpha == 0) {
                    pixels[y][x] = 0xFF000000 | (bgR << 16) | (bgG << 8) | bgB;
                    continue;
                }
                final int srcR = (argb >> 16) & 0xFF;
                final int srcG = (argb >> 8) & 0xFF;
                final int srcB = argb & 0xFF;
                final int outR = ((srcR * alpha) + (bgR * (255 - alpha))) / 255;
                final int outG = ((srcG * alpha) + (bgG * (255 - alpha))) / 255;
                final int outB = ((srcB * alpha) + (bgB * (255 - alpha))) / 255;
                pixels[y][x] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
            }
        }
    }

    private int bkgdToARGB(final BKGD bkgd, final int depth, final PLTE plte) {
        if (bkgd.isIndexed() && plte != null) return 0xFF000000 | plte.getColor(bkgd.paletteIndex());
        return bkgd.toRGB8(depth);
    }

    private void fillBuffer(final int[][] buffer, final int width, final int height, final int color) {
        for (int y = 0; y < height; y++) Arrays.fill(buffer[y], 0, width, color);
    }

    private void clearBuffer(final int[][] buffer, final int width, final int height) {
        for (int y = 0; y < height; y++) Arrays.fill(buffer[y], 0, width, 0);
    }

    private void copyBuffer(final int[][] src, final int[][] dst, final int width, final int height) {
        for (int y = 0; y < height; y++) System.arraycopy(src[y], 0, dst[y], 0, width);
    }

    private void validateIHDR(final IHDR ihdr) throws XCodecException {
        if (ihdr.width() <= 0 || ihdr.height() <= 0) {
            throw new XCodecException("Invalid image dimensions: " + ihdr.width() + "x" + ihdr.height());
        }
        final ColorType colorType = ColorType.of(ihdr.colorType());
        final int depth = ihdr.depth();
        switch (colorType) {
            case GREYSCALE -> {
                if (depth != 1 && depth != 2 && depth != 4 && depth != 8 && depth != 16)
                    throw new XCodecException("Invalid bit depth " + depth + " for greyscale");
            }
            case TRUECOLOR, GREYSCALE_ALPHA, TRUECOLOR_ALPHA -> {
                if (depth != 8 && depth != 16)
                    throw new XCodecException("Invalid bit depth " + depth + " for color type " + colorType);
            }
            case INDEXED -> {
                if (depth != 1 && depth != 2 && depth != 4 && depth != 8)
                    throw new XCodecException("Invalid bit depth " + depth + " for indexed-color");
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new XCodecException("Forbidden color type: " + ihdr.colorType());
        }
        if (ihdr.compression() != 0) throw new XCodecException("Unknown compression method: " + ihdr.compression());
        if (ihdr.filter() != 0) throw new XCodecException("Unknown filter method: " + ihdr.filter());
        if (ihdr.interlace() != 0 && ihdr.interlace() != 1) throw new XCodecException("Unknown interlace method: " + ihdr.interlace());
    }

    private byte[] inflate(final List<ChunkSlice> compressed, final IHDR ihdr) throws IOException {
        final Inflater inflater = this.inflater;
        inflater.reset();
        byte[] output = new byte[Math.max(32, this.expectedInflatedBytes(ihdr))];
        int outputSize = 0;
        try {
            for (final ChunkSlice chunk: compressed) {
                if (chunk.length() <= 0) continue;
                inflater.setInput(chunk.data(), chunk.offset(), chunk.length());
                while (true) {
                    if (outputSize == output.length) output = growInflateOutput(output);
                    final int len = inflater.inflate(output, outputSize, output.length - outputSize);
                    if (len > 0) {
                        outputSize += len;
                        continue;
                    }
                    if (inflater.finished()) return outputSize == output.length ? output : Arrays.copyOf(output, outputSize);
                    if (inflater.needsDictionary()) throw new XCodecException("PNG uses unsupported compression dictionary");
                    if (inflater.needsInput()) break;
                    throw new XCodecException("Invalid compressed data stream");
                }
            }
            while (true) {
                if (outputSize == output.length) output = growInflateOutput(output);
                final int len = inflater.inflate(output, outputSize, output.length - outputSize);
                if (len > 0) {
                    outputSize += len;
                    continue;
                }
                if (inflater.finished()) return outputSize == output.length ? output : Arrays.copyOf(output, outputSize);
                if (inflater.needsDictionary()) throw new XCodecException("PNG uses unsupported compression dictionary");
                if (inflater.needsInput()) throw new XCodecException("Incomplete compressed data");
                throw new XCodecException("Invalid compressed data stream");
            }
        } catch (final DataFormatException e) {
            throw new XCodecException("Invalid compressed data: " + e.getMessage());
        }
    }

    private int expectedInflatedBytes(final IHDR ihdr) {
        if (ihdr.interlace() == 0) {
            return (this.scanlineBytes(ihdr.width(), ihdr) + 1) * ihdr.height();
        }
        int total = 0;
        for (int pass = 0; pass < 7; pass++) {
            final int pw = this.passDimension(ihdr.width(), ADAM7_X_START[pass], ADAM7_X_STEP[pass]);
            final int ph = this.passDimension(ihdr.height(), ADAM7_Y_START[pass], ADAM7_Y_STEP[pass]);
            if (pw > 0 && ph > 0) total += (this.scanlineBytes(pw, ihdr) + 1) * ph;
        }
        return total;
    }

    private static byte[] growInflateOutput(final byte[] output) {
        return Arrays.copyOf(output, output.length + Math.max(8192, output.length >> 1));
    }

    private int[][] decodeData(final byte[] data, final IHDR ihdr, final int[][] pixels, final PLTE plte, final TRNS trns) throws IOException {
        final int width = ihdr.width();
        final int height = ihdr.height();
        final int bpp = ihdr.bytesPerPixel();

        if (ihdr.interlace() == 0) {
            this.decodePass(data, 0, pixels, 0, 0, 1, 1, width, height, ihdr, plte, trns, bpp);
        } else {
            int dataOffset = 0;
            for (int pass = 0; pass < 7; pass++) {
                final int pw = this.passDimension(width, ADAM7_X_START[pass], ADAM7_X_STEP[pass]);
                final int ph = this.passDimension(height, ADAM7_Y_START[pass], ADAM7_Y_STEP[pass]);
                if (pw > 0 && ph > 0) {
                    dataOffset = this.decodePass(data, dataOffset, pixels,
                            ADAM7_X_START[pass], ADAM7_Y_START[pass],
                            ADAM7_X_STEP[pass], ADAM7_Y_STEP[pass],
                            pw, ph, ihdr, plte, trns, bpp);
                }
            }
        }
        return pixels;
    }

    private int passDimension(final int dim, final int start, final int step) {
        if (start >= dim) return 0;
        return (dim - start + step - 1) / step;
    }

    private int decodePass(final byte[] data, final int dataOffset, final int[][] pixels,
                           final int xStart, final int yStart, final int xStep, final int yStep,
                           final int passWidth, final int passHeight,
                           final IHDR ihdr, final PLTE plte, final TRNS trns, final int bpp) throws IOException {
        final int scanlineBytes = this.scanlineBytes(passWidth, ihdr);
        this.ensurePassRowCapacity(scanlineBytes);
        byte[] currentRow = this.passCurrentRow;
        byte[] previousRow = this.passPreviousRow;
        Arrays.fill(previousRow, 0, scanlineBytes, (byte) 0);
        int offset = dataOffset;

        for (int passY = 0; passY < passHeight; passY++) {
            if (offset >= data.length) throw new XCodecException("Unexpected end of image data");
            final int filterType = data[offset++] & 0xFF;
            if (offset + scanlineBytes > data.length) throw new XCodecException("Unexpected end of image data");
            System.arraycopy(data, offset, currentRow, 0, scanlineBytes);
            offset += scanlineBytes;

            this.unfilterRow(currentRow, previousRow, filterType, bpp);

            final int imageY = yStart + passY * yStep;
            this.decodeRowPixels(currentRow, pixels, imageY, xStart, xStep, passWidth, ihdr, plte, trns);

            final byte[] tmp = previousRow;
            previousRow = currentRow;
            currentRow = tmp;
        }
        this.passCurrentRow = currentRow;
        this.passPreviousRow = previousRow;
        return offset;
    }

    private void ensurePassRowCapacity(final int scanlineBytes) {
        if (this.passCurrentRow.length < scanlineBytes) this.passCurrentRow = new byte[scanlineBytes];
        if (this.passPreviousRow.length < scanlineBytes) this.passPreviousRow = new byte[scanlineBytes];
    }

    private int scanlineBytes(final int width, final IHDR ihdr) {
        final int depth = ihdr.depth();
        final ColorType ct = ColorType.of(ihdr.colorType());
        final int samples = switch (ct) {
            case GREYSCALE, INDEXED -> 1;
            case TRUECOLOR -> 3;
            case GREYSCALE_ALPHA -> 2;
            case TRUECOLOR_ALPHA -> 4;
            case FORBIDDEN_1, FORBIDDEN_5 -> 1;
        };
        final int bitsPerPixel = samples * depth;
        return (width * bitsPerPixel + 7) / 8;
    }

    private void unfilterRow(final byte[] currentRow, final byte[] previousRow, final int filterType, final int bpp) throws IOException {
        final int length = currentRow.length;
        switch (filterType) {
            case FILTER_NONE -> {}
            case FILTER_SUB -> {
                for (int i = bpp; i < length; i++) {
                    final int a = currentRow[i - bpp] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + a);
                }
            }
            case FILTER_UP -> {
                for (int i = 0; i < length; i++) {
                    final int b = previousRow[i] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + b);
                }
            }
            case FILTER_AVERAGE -> {
                for (int i = 0; i < length; i++) {
                    final int a = (i >= bpp) ? (currentRow[i - bpp] & 0xFF) : 0;
                    final int b = previousRow[i] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + ((a + b) >> 1));
                }
            }
            case FILTER_PAETH -> {
                for (int i = 0; i < length; i++) {
                    final int a = (i >= bpp) ? (currentRow[i - bpp] & 0xFF) : 0;
                    final int b = previousRow[i] & 0xFF;
                    final int c = (i >= bpp) ? (previousRow[i - bpp] & 0xFF) : 0;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + this.paethPredictor(a, b, c));
                }
            }
            default -> throw new XCodecException("Unknown filter type: " + filterType);
        }
    }

    private int paethPredictor(final int a, final int b, final int c) {
        final int p = a + b - c;
        final int pa = Math.abs(p - a);
        final int pb = Math.abs(p - b);
        final int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    private void decodeRowPixels(final byte[] row, final int[][] pixels, final int y, final int xStart, final int xStep,
                                 final int passWidth, final IHDR ihdr, final PLTE plte, final TRNS trns) throws IOException {
        final int depth = ihdr.depth();
        final ColorType ct = ColorType.of(ihdr.colorType());
        final int imageWidth = ihdr.width();
        int bitOffset = 0;

        for (int passX = 0; passX < passWidth; passX++) {
            final int x = xStart + passX * xStep;
            if (x >= imageWidth) break;

            pixels[y][x] = switch (ct) {
                case GREYSCALE -> {
                    int gray = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    gray = this.scaleTo8Bit(gray, depth);
                    int alpha = 255;
                    if (trns != null && gray == trns.gray()) alpha = 0;
                    yield (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                }
                case TRUECOLOR -> {
                    int r = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    int g = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    int b = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    r = this.scaleTo8Bit(r, depth);
                    g = this.scaleTo8Bit(g, depth);
                    b = this.scaleTo8Bit(b, depth);
                    int alpha = 255;
                    if (trns != null && r == this.scaleTo8Bit(trns.red(), depth)
                            && g == this.scaleTo8Bit(trns.green(), depth)
                            && b == this.scaleTo8Bit(trns.blue(), depth)) alpha = 0;
                    yield (alpha << 24) | (r << 16) | (g << 8) | b;
                }
                case INDEXED -> {
                    final int index = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    if (plte == null) throw new XCodecException("Indexed-color image without PLTE");
                    final int rgb = plte.getColor(index);
                    final int alpha = (trns != null) ? trns.getAlpha(index) : 255;
                    yield (alpha << 24) | rgb;
                }
                case GREYSCALE_ALPHA -> {
                    int gray = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    int alpha = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    gray = this.scaleTo8Bit(gray, depth);
                    alpha = this.scaleTo8Bit(alpha, depth);
                    yield (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                }
                case TRUECOLOR_ALPHA -> {
                    int r = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    int g = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    int b = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    int alpha = this.extractSample(row, bitOffset, depth); bitOffset += depth;
                    r = this.scaleTo8Bit(r, depth);
                    g = this.scaleTo8Bit(g, depth);
                    b = this.scaleTo8Bit(b, depth);
                    alpha = this.scaleTo8Bit(alpha, depth);
                    yield (alpha << 24) | (r << 16) | (g << 8) | b;
                }
                case FORBIDDEN_1, FORBIDDEN_5 -> throw new XCodecException("Forbidden color type: " + ihdr.colorType());
            };
        }
    }

    private int extractSample(final byte[] row, final int bitOffset, final int depth) {
        final int byteOffset = bitOffset / 8;
        if (depth >= 8) {
            if (depth == 8) return row[byteOffset] & 0xFF;
            return ((row[byteOffset] & 0xFF) << 8) | (row[byteOffset + 1] & 0xFF);
        }
        final int bitInByte = 8 - (bitOffset % 8) - depth;
        final int mask = (1 << depth) - 1;
        return (row[byteOffset] >> bitInByte) & mask;
    }

    private int scaleTo8Bit(final int value, final int depth) {
        if (depth == 8) return value;
        if (depth == 16) return value >> 8;
        if (depth == 1) return value * 255;
        if (depth == 2) return (value * 255) / 3;
        if (depth == 4) return (value * 255) / 15;
        return value;
    }

    private void toBGRAInto(final int[][] pixels, final int width, final int height, final ByteBuffer dst) {
        dst.clear();
        this.directOutInts.clear();
        for (int y = 0; y < height; y++) {
            this.directOutInts.put(pixels[y], 0, width);
        }
        dst.position(0).limit(width * height * 4);
    }

    private void applyDispose(final int[][] outputBuffer, final int[][] previousBuffer, final FCTL fctl,
                              final BKGD bkgd, final int depth, final PLTE plte, final int canvasWidth, final int canvasHeight) {
        final int disposeOp = fctl.dispose() & 0xFF;
        final int xOff = fctl.xOffset();
        final int yOff = fctl.yOffset();
        final int fw = fctl.width();
        final int fh = fctl.height();

        switch (disposeOp) {
            case FCTL.DISPOSE_OP_NONE -> {}
            case FCTL.DISPOSE_OP_BACKGROUND -> {
                final int bg = (bkgd != null) ? this.bkgdToARGB(bkgd, depth, plte) : 0x00000000;
                for (int y = yOff; y < yOff + fh && y < canvasHeight; y++) {
                    for (int x = xOff; x < xOff + fw && x < canvasWidth; x++) {
                        outputBuffer[y][x] = bg;
                    }
                }
            }
            case FCTL.DISPOSE_OP_PREVIOUS -> {
                if (previousBuffer == null) return;
                for (int y = yOff; y < yOff + fh && y < canvasHeight; y++) {
                    for (int x = xOff; x < xOff + fw && x < canvasWidth; x++) {
                        outputBuffer[y][x] = previousBuffer[y][x];
                    }
                }
            }
        }
    }

    private int[][] frameBuffer() {
        if (this.frameBuffer == null) {
            this.frameBuffer = new int[this.ihdr.height()][this.ihdr.width()];
        }
        return this.frameBuffer;
    }

    private int[][] previousBuffer() {
        if (this.previousBuffer == null) {
            this.previousBuffer = new int[this.ihdr.height()][this.ihdr.width()];
        }
        return this.previousBuffer;
    }

    private void applyBlendOp(final int[][] outputBuffer, final int[][] framePixels, final FCTL fctl) {
        final int blendOp = fctl.blend() & 0xFF;
        final int xOff = fctl.xOffset();
        final int yOff = fctl.yOffset();
        final int fw = fctl.width();
        final int fh = fctl.height();

        for (int y = 0; y < fh; y++) {
            final int outY = yOff + y;
            if (outY >= outputBuffer.length) break;
            for (int x = 0; x < fw; x++) {
                final int outX = xOff + x;
                if (outX >= outputBuffer[0].length) break;
                final int srcARGB = framePixels[y][x];
                switch (blendOp) {
                    case FCTL.BLEND_OP_SOURCE -> outputBuffer[outY][outX] = srcARGB;
                    case FCTL.BLEND_OP_OVER -> outputBuffer[outY][outX] = this.alphaComposite(srcARGB, outputBuffer[outY][outX]);
                    default -> {
                        outputBuffer[outY][outX] = srcARGB;
                        LOGGER.warn(IT, "Unknown blend operation: {}", blendOp);
                    }
                }
            }
        }
    }

    private int alphaComposite(final int srcARGB, final int dstARGB) {
        final int srcA = (srcARGB >> 24) & 0xFF;
        final int srcR = (srcARGB >> 16) & 0xFF;
        final int srcG = (srcARGB >> 8) & 0xFF;
        final int srcB = srcARGB & 0xFF;
        final int dstA = (dstARGB >> 24) & 0xFF;
        final int dstR = (dstARGB >> 16) & 0xFF;
        final int dstG = (dstARGB >> 8) & 0xFF;
        final int dstB = dstARGB & 0xFF;
        final int srcRA = srcR * srcA, srcGA = srcG * srcA, srcBA = srcB * srcA;
        final int dstRA = dstR * dstA, dstGA = dstG * dstA, dstBA = dstB * dstA;
        final int outA = srcA + ((dstA * (255 - srcA)) / 255);
        if (outA == 0) return 0;
        int outR = (srcRA + ((dstRA * (255 - srcA)) / 255)) / outA;
        int outG = (srcGA + ((dstGA * (255 - srcA)) / 255)) / outA;
        int outB = (srcBA + ((dstBA * (255 - srcA)) / 255)) / outA;
        outR = Math.min(255, Math.max(0, outR));
        outG = Math.min(255, Math.max(0, outG));
        outB = Math.min(255, Math.max(0, outB));
        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    private static ImageData.Scan scan(final ByteBuffer source) {
        final ByteBuffer buffer = source.slice().order(ByteOrder.BIG_ENDIAN);
        final List<Long> delays = new ArrayList<>();
        int declaredFrameCount = 0;
        int declaredLoopCount = ImageData.NO_REPEAT;
        long total = 0L;

        while (buffer.remaining() >= 12) {
            final int length = buffer.getInt();
            final int type = buffer.getInt();
            if (length < 0 || buffer.remaining() < length + 4) break;

            final int dataPos = buffer.position();
            if (type == ACTL.SIGNATURE && length >= ACTL.LENGTH) {
                declaredFrameCount = buffer.getInt(dataPos);
                final int loop = buffer.getInt(dataPos + 4);
                declaredLoopCount = (loop == 0) ? ImageData.REPEAT_FOREVER : loop;
            } else if (type == FCTL.SIGNATURE && length == FCTL.LENGTH) {
                final int delayNum = ((buffer.get(dataPos + 20) & 0xFF) << 8) | (buffer.get(dataPos + 21) & 0xFF);
                int delayDen = ((buffer.get(dataPos + 22) & 0xFF) << 8) | (buffer.get(dataPos + 23) & 0xFF);
                if (delayDen == 0) delayDen = 100;
                final long delay = ((long) delayNum * 1000L) / delayDen;
                delays.add(delay);
                total += delay;
            }

            buffer.position(dataPos + length + 4);
            if (type == IEND.SIGNATURE) break;
        }

        final int frameCount = declaredFrameCount > 0 ? declaredFrameCount : delays.size();
        if (frameCount <= 1 || delays.isEmpty()) return ImageData.Scan.STATIC;

        final long[] delayArray = new long[delays.size()];
        for (int i = 0; i < delayArray.length; i++) delayArray[i] = delays.get(i);
        return new ImageData.Scan(frameCount, delayArray, total, declaredLoopCount);
    }

    private record ChunkSlice(byte[] data, int offset, int length) {}
}
