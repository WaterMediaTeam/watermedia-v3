package org.watermedia.api.decode.formats.png;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.decode.Decoder;
import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.formats.png.chunks.*;
import org.watermedia.tools.DataTool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.watermedia.WaterMedia.LOGGER;


/**
 * PNG and APNG decoder following the PNG Specification Third Edition (W3C Recommendation 24 June 2025)
 * Supports:
 * - Greyscale (color type 0): 1, 2, 4, 8, 16 bit depth
 * - Truecolor (color type 2): 8, 16 bit depth
 * - Indexed-color (color type 3): 1, 2, 4, 8 bit depth
 * - Greyscale with alpha (color type 4): 8, 16 bit depth
 * - Truecolor with alpha (color type 6): 8, 16 bit depth
 * - Interlacing: None (0) and Adam7 (1)
 * - Filter types: None, Sub, Up, Average, Paeth
 * - APNG animation frames
 *
 * @see <a href="https://www.w3.org/TR/png-3/">PNG Specification Third Edition</a>
 */
public class PNG extends Decoder {
    private static final Marker IT = MarkerManager.getMarker(PNG.class.getSimpleName());
    // PNG FILE SIGNATURE: 0x89 P N G CR LF SUB LF
    static final byte[] PNG_SIGNATURE = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    // FILTER TYPES (FILTER METHOD 0)
    private static final int FILTER_NONE = 0;
    private static final int FILTER_SUB = 1;
    private static final int FILTER_UP = 2;
    private static final int FILTER_AVERAGE = 3;
    private static final int FILTER_PAETH = 4;

    // ADAM7 INTERLACING PASS PARAMETERS
    // STARTING COLUMN FOR EACH PASS
    private static final int[] ADAM7_X_START = {0, 4, 0, 2, 0, 1, 0};
    // STARTING ROW FOR EACH PASS
    private static final int[] ADAM7_Y_START = {0, 0, 4, 0, 2, 0, 1};
    // COLUMN INCREMENT FOR EACH PASS
    private static final int[] ADAM7_X_STEP = {8, 8, 4, 4, 2, 2, 1};
    // ROW INCREMENT FOR EACH PASS
    private static final int[] ADAM7_Y_STEP = {8, 8, 8, 4, 4, 2, 2};

    public PNG() {}

    /**
     * Checks if the buffer starts with the PNG signature
     * @param buffer The buffer to check
     * @return true if PNG signature matches, false otherwise
     */
    @Override
    public boolean supported(final ByteBuffer buffer) {
        buffer.mark();
        for (final byte value : PNG_SIGNATURE) {
            if (!buffer.hasRemaining() || buffer.get() != value) {
                buffer.reset();
                return false;
            }
        }
        return true; // SIGNATURE MATCHED, POSITION AT END OF SIGNATURE
    }

    /**
     * Decodes a PNG or APNG image from the buffer
     * @param buffer The buffer containing PNG data (positioned after signature)
     * @return Decoded Image with BGRA pixel data
     */
    @Override
    public Image decode(final ByteBuffer buffer) throws IOException {
        buffer.order(ByteOrder.BIG_ENDIAN); // PNG USES NETWORK BYTE ORDER (BIG ENDIAN)

        // PARSE ALL CHUNKS
        IHDR ihdr = null;
        PLTE plte = null;
        TRNS trns = null;
        BKGD bkgd = null;
        ACTL actl = null;
        final List<FCTL> frameControls = new ArrayList<>();
        final List<byte[]> idatChunks = new ArrayList<>();
        final List<List<byte[]>> fdatChunks = new ArrayList<>();

        while (buffer.hasRemaining()) {
            final CHUNK chunk = CHUNK.read(buffer);

            // CHECK CRC INTEGRITY
            if (chunk.corrupted()) {
                throw new DecoderException("Chunk CRC mismatch on chunk " + chunk.type() + " - data corrupted");
            }

            switch (chunk.type()) {
                case IHDR.SIGNATURE -> {
                    ihdr = IHDR.convert(chunk, buffer.order());
                    this.validateIHDR(ihdr);
                }

                case PLTE.SIGNATURE -> {
                    plte = PLTE.convert(chunk);
                }

                case TRNS.SIGNATURE -> {
                    if (ihdr == null) throw new DecoderException("tRNS before IHDR");
                    trns = TRNS.convert(chunk, ihdr.colorType());
                }

                case BKGD.SIGNATURE -> {
                    // ONLY PARSE IF CONFIG ENABLES BKGD USAGE
                    if (WaterMediaConfig.pngDecoder$useBKGDChunk) {
                        if (ihdr == null) throw new DecoderException("bKGD before IHDR");
                        bkgd = BKGD.convert(chunk, ihdr.colorType(), ihdr.depth());
                    }
                }

                case ACTL.SIGNATURE -> {
                    actl = ACTL.convert(chunk, buffer.order());
                }

                case FCTL.SIGNATURE -> {
                    final FCTL fctl = FCTL.convert(chunk, buffer.order());
                    frameControls.add(fctl);
                    // FCTL BEFORE IDAT MEANS STATIC IMAGE IS FIRST FRAME
                    fdatChunks.add(new ArrayList<>()); // PLACEHOLDER FOR IDAT DATA
                }

                case IDAT.SIGNATURE -> {
                    idatChunks.add(chunk.data());
                    // IF FCTL WAS BEFORE IDAT, IDAT DATA GOES TO FIRST FRAME
                    if (!frameControls.isEmpty() && fdatChunks.size() == 1) {
                        fdatChunks.get(0).add(chunk.data());
                    }
                }

                case FDAT.SIGNATURE -> {
                    if (fdatChunks.isEmpty() || fdatChunks.size() <= 1) {
                        fdatChunks.add(new ArrayList<>());
                    }
                    // FDAT DATA STARTS AFTER 4-BYTE SEQUENCE NUMBER
                    final byte[] data = chunk.data();
                    final byte[] frameData = new byte[data.length - 4];
                    System.arraycopy(data, 4, frameData, 0, frameData.length);
                    fdatChunks.get(fdatChunks.size() - 1).add(frameData);
                }

                case IEND.SIGNATURE -> {
                    // END OF PNG STREAM
                    break;
                }

                default -> {
                    // UNKNOWN CHUNK - IGNORE IF ANCILLARY (LOWERCASE FIRST LETTER)
                    final int firstByte = (chunk.type() >> 24) & 0xFF;
                    if ((firstByte & 0x20) == 0) {
                        // CRITICAL CHUNK - CANNOT IGNORE
                        throw new DecoderException("Unknown critical chunk: " + this.chunkTypeToString(chunk.type()));
                    }
                }
            }
        }

        if (ihdr == null) {
            throw new DecoderException("Missing IHDR chunk");
        }
        if (idatChunks.isEmpty()) {
            throw new DecoderException("Missing IDAT chunk");
        }

        // DECODE STATIC IMAGE
        final byte[] compressedData = this.jointChunk(idatChunks);
        final byte[] decompressedData = this.inflate(compressedData);
        final int[][] staticImage = this.decodeData(decompressedData, ihdr, plte, trns);

        // FLATTEN ALPHA IF BKGD IS ENABLED AND PRESENT
        if (bkgd != null) {
            this.flattenAlpha(staticImage, bkgd, ihdr.depth(), plte);
        }

        // CONVERT TO BGRA FORMAT
        final ByteBuffer staticPixels = this.toBGRA(staticImage, ihdr.width(), ihdr.height());

        // IF APNG, DECODE ANIMATION FRAMES
        if (actl != null && actl.frameCount() > 0 && !frameControls.isEmpty()) {
            final int frameCount = Math.min(actl.frameCount(), frameControls.size());
            final ByteBuffer[] frameBuffers = new ByteBuffer[frameCount];
            final long[] delays = new long[frameCount];

            final int[][] outputBuffer = new int[ihdr.height()][ihdr.width()];
            final int[][] previousBuffer = new int[ihdr.height()][ihdr.width()];

            // INITIALIZE OUTPUT BUFFER TO FULLY TRANSPARENT BLACK (OR BKGD COLOR)
            if (bkgd != null) {
                final int bgColor = this.bkgdToARGB(bkgd, ihdr.depth(), plte);
                this.fillBuffer(outputBuffer, ihdr.width(), ihdr.height(), bgColor);
            } else {
                this.clearBuffer(outputBuffer, ihdr.width(), ihdr.height());
            }

            for (int i = 0; i < frameCount; i++) {
                final FCTL fctl = frameControls.get(i);
                final int[][] framePixels;

                // SAVE CURRENT STATE FOR DISPOSE_OP_PREVIOUS
                this.copyBuffer(outputBuffer, previousBuffer, ihdr.width(), ihdr.height());

                // FIRST FRAME MAY USE IDAT DATA
                if (i == 0 && !fdatChunks.isEmpty() && !fdatChunks.get(0).isEmpty() && fdatChunks.get(0).equals(idatChunks)) {
                    framePixels = staticImage;
                } else if (i == 0 && !fdatChunks.isEmpty() && !fdatChunks.get(0).isEmpty()) {
                    // FIRST FRAME USES IDAT
                    framePixels = staticImage;
                } else if (i < fdatChunks.size() && !fdatChunks.get(i).isEmpty()) {
                    final byte[] frameCompressed = this.jointChunk(fdatChunks.get(i));
                    final byte[] frameDecompressed = this.inflate(frameCompressed);
                    final IHDR frameIhdr = new IHDR(fctl.width(), fctl.height(), ihdr.depth(),
                            ihdr.colorType(), ihdr.compression(), ihdr.filter(), ihdr.interlace());
                    framePixels = this.decodeData(frameDecompressed, frameIhdr, plte, trns);

                    // FLATTEN ALPHA IF BKGD IS ENABLED
                    if (bkgd != null) {
                        this.flattenAlpha(framePixels, bkgd, ihdr.depth(), plte);
                    }
                } else {
                    // USE STATIC IMAGE AS FALLBACK
                    framePixels = staticImage;
                }

                // APPLY BLEND OPERATION
                this.applyBlendOp(outputBuffer, framePixels, fctl);
                delays[i] = fctl.delayMillis();

                // CREATE FRAME BUFFER
                frameBuffers[i] = this.toBGRA(outputBuffer, ihdr.width(), ihdr.height());

                // APPLY DISPOSE OPERATION FOR NEXT FRAME
                this.applyDispose(outputBuffer, previousBuffer, fctl, bkgd, ihdr.depth(), plte, ihdr.width(), ihdr.height());
            }

            // DETERMINE REPEAT COUNT
            final int repeat = actl.loopCount() == 0 ? Image.REPEAT_FOREVER : actl.loopCount();

            return new Image(frameBuffers, ihdr.width(), ihdr.height(), delays, repeat);
        }

        // STATIC PNG - SINGLE FRAME WITH NO ANIMATION
        return new Image(new ByteBuffer[] { staticPixels }, ihdr.width(), ihdr.height(), new long[] { 0L }, 1L, Image.NO_REPEAT);
    }

    /**
     * Flattens alpha channel by compositing against bKGD color
     */
    private void flattenAlpha(final int[][] pixels, final BKGD bkgd, final int depth, final PLTE plte) {
        final int bgColor = this.bkgdToARGB(bkgd, depth, plte);
        final int bgR = (bgColor >> 16) & 0xFF;
        final int bgG = (bgColor >> 8) & 0xFF;
        final int bgB = bgColor & 0xFF;

        for (int y = 0; y < pixels.length; y++) {
            for (int x = 0; x < pixels[y].length; x++) {
                final int argb = pixels[y][x];
                final int alpha = (argb >> 24) & 0xFF;

                if (alpha == 255) continue; // FULLY OPAQUE, NO CHANGE
                if (alpha == 0) {
                    // FULLY TRANSPARENT, USE BG COLOR
                    pixels[y][x] = 0xFF000000 | (bgR << 16) | (bgG << 8) | bgB;
                    continue;
                }

                // ALPHA BLEND AGAINST BACKGROUND
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

    /**
     * Converts bKGD to ARGB color value
     */
    private int bkgdToARGB(final BKGD bkgd, final int depth, final PLTE plte) {
        if (bkgd.isIndexed() && plte != null) {
            final int rgb = plte.getColor(bkgd.paletteIndex());
            return 0xFF000000 | plte.getColor(bkgd.paletteIndex());
        } else if (bkgd.isGreyscale()) {
            final int gray = this.scaleTo8Bit(bkgd.gray(), depth);
            return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        } else if (bkgd.isTruecolor()) {
            final int r = this.scaleTo8Bit(bkgd.red(), depth);
            final int g = this.scaleTo8Bit(bkgd.green(), depth);
            final int b = this.scaleTo8Bit(bkgd.blue(), depth);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return 0xFF000000; // DEFAULT BLACK
    }

    /**
     * Fills buffer with a solid color
     */
    private void fillBuffer(final int[][] buffer, final int width, final int height, final int color) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer[y][x] = color;
            }
        }
    }

    /**
     * Clears a buffer to fully transparent black
     */
    private void clearBuffer(final int[][] buffer, final int width, final int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer[y][x] = 0x00000000;
            }
        }
    }

    /**
     * Copies source buffer to destination buffer
     */
    private void copyBuffer(final int[][] src, final int[][] dst, final int width, final int height) {
        for (int y = 0; y < height; y++) {
            System.arraycopy(src[y], 0, dst[y], 0, width);
        }
    }

    /**
     * Validates IHDR parameters according to PNG spec
     */
    private void validateIHDR(final IHDR ihdr) throws DecoderException {
        if (ihdr.width() <= 0 || ihdr.height() <= 0) {
            throw new DecoderException("Invalid image dimensions: " + ihdr.width() + "x" + ihdr.height());
        }

        final int colorType = ihdr.colorType();
        final int depth = ihdr.depth();

        // VALIDATE COLOR TYPE AND BIT DEPTH COMBINATIONS
        switch (colorType) {
            case 0 -> { // GREYSCALE
                if (depth != 1 && depth != 2 && depth != 4 && depth != 8 && depth != 16) {
                    throw new DecoderException("Invalid bit depth " + depth + " for greyscale");
                }
            }
            case 2, 4, 6 -> { // TRUECOLOR, GREYSCALE+ALPHA, TRUECOLOR+ALPHA
                if (depth != 8 && depth != 16) {
                    throw new DecoderException("Invalid bit depth " + depth + " for color type " + colorType);
                }
            }
            case 3 -> { // INDEXED-COLOR
                if (depth != 1 && depth != 2 && depth != 4 && depth != 8) {
                    throw new DecoderException("Invalid bit depth " + depth + " for indexed-color");
                }
            }
            default -> throw new DecoderException("Unknown color type: " + colorType);
        }

        if (ihdr.compression() != 0) {
            throw new DecoderException("Unknown compression method: " + ihdr.compression());
        }
        if (ihdr.filter() != 0) {
            throw new DecoderException("Unknown filter method: " + ihdr.filter());
        }
        if (ihdr.interlace() != 0 && ihdr.interlace() != 1) {
            throw new DecoderException("Unknown interlace method: " + ihdr.interlace());
        }
    }

    /**
     * Concatenates multiple chunk data arrays into one
     */
    private byte[] jointChunk(final List<byte[]> chunks) {
        int totalLength = 0;
        for (final byte[] chunk : chunks) {
            totalLength += chunk.length;
        }

        final byte[] result = new byte[totalLength];
        int offset = 0;
        for (final byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    /**
     * Decompresses zlib-compressed data using Inflater
     */
    private byte[] inflate(final byte[] compressed) throws IOException {
        final Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];

        try {
            while (!inflater.finished()) {
                final int length = inflater.inflate(buffer);
                if (length == 0 && inflater.needsInput()) {
                    throw new DecoderException("Incomplete compressed data");
                }
                output.write(buffer, 0, length);
            }
        } catch (final DataFormatException e) {
            throw new DecoderException("Invalid compressed data: " + e.getMessage());
        } finally {
            inflater.end();
        }

        return output.toByteArray();
    }

    /**
     * Decodes decompressed image data into ARGB pixel array
     * Handles interlacing and filtering
     */
    private int[][] decodeData(final byte[] data, final IHDR ihdr, final PLTE plte, final TRNS trns) throws IOException {
        final int width = ihdr.width();
        final int height = ihdr.height();
        final int[][] pixels = new int[height][width];

        // CALCULATE BYTES PER PIXEL (FOR FILTERING)
        final int bpp = this.bytesPerPixel(ihdr);

        if (ihdr.interlace() == 0) {
            // NO INTERLACING
            this.decodePass(data, 0, pixels, 0, 0, 1, 1, width, height, ihdr, plte, trns, bpp);
        } else {
            // ADAM7 INTERLACING
            int dataOffset = 0;
            for (int pass = 0; pass < 7; pass++) {
                final int passWidth = this.passDimension(width, ADAM7_X_START[pass], ADAM7_X_STEP[pass]);
                final int passHeight = this.passDimension(height, ADAM7_Y_START[pass], ADAM7_Y_STEP[pass]);

                if (passWidth > 0 && passHeight > 0) {
                    dataOffset = this.decodePass(data, dataOffset, pixels,
                            ADAM7_X_START[pass], ADAM7_Y_START[pass],
                            ADAM7_X_STEP[pass], ADAM7_Y_STEP[pass],
                            passWidth, passHeight, ihdr, plte, trns, bpp);
                }
            }
        }

        return pixels;
    }

    /**
     * Calculates the dimension (width or height) for an interlace pass
     */
    private int passDimension(final int dim, final int start, final int step) {
        if (start >= dim) return 0;
        return (dim - start + step - 1) / step;
    }

    /**
     * Calculates bytes per pixel for filtering purposes
     */
    private int bytesPerPixel(final IHDR ihdr) {
        final int depth = ihdr.depth();
        final int colorType = ihdr.colorType();

        final int samplesPerPixel = switch (colorType) {
            case 0 -> 1;       // GREYSCALE
            case 2 -> 3;       // TRUECOLOR
            case 3 -> 1;       // INDEXED
            case 4 -> 2;       // GREYSCALE + ALPHA
            case 6 -> 4;       // TRUECOLOR + ALPHA
            default -> 1;
        };

        final int bitsPerPixel = samplesPerPixel * depth;
        return Math.max(1, bitsPerPixel / 8);
    }

    /**
     * Decodes a single pass of image data (or entire image for non-interlaced)
     */
    private int decodePass(final byte[] data, final int dataOffset, final int[][] pixels,
                           final int xStart, final int yStart, final int xStep, final int yStep,
                           final int passWidth, final int passHeight,
                           final IHDR ihdr, final PLTE plte, final TRNS trns, final int bpp) throws IOException {

        final int scanlineBytes = this.scanlineBytes(passWidth, ihdr);

        byte[] currentRow = new byte[scanlineBytes];
        byte[] previousRow = new byte[scanlineBytes];

        int offset = dataOffset;

        for (int passY = 0; passY < passHeight; passY++) {
            // READ FILTER TYPE BYTE
            if (offset >= data.length) {
                throw new DecoderException("Unexpected end of image data");
            }
            final int filterType = data[offset++] & 0xFF;

            // READ SCANLINE DATA
            if (offset + scanlineBytes > data.length) {
                throw new DecoderException("Unexpected end of image data");
            }
            System.arraycopy(data, offset, currentRow, 0, scanlineBytes);
            offset += scanlineBytes;

            // APPLY FILTER RECONSTRUCTION
            this.unfilterRow(currentRow, previousRow, filterType, bpp);

            // DECODE PIXELS FROM SCANLINE
            final int imageY = yStart + passY * yStep;
            this.decodeRowPixels(currentRow, pixels, imageY, xStart, xStep, passWidth, ihdr, plte, trns);

            // SWAP ROWS FOR NEXT ITERATION
            final byte[] temp = previousRow;
            previousRow = currentRow;
            currentRow = temp;
        }

        return offset;
    }

    /**
     * Calculates the number of bytes in a scanline (excluding filter byte)
     */
    private int scanlineBytes(final int width, final IHDR ihdr) {
        final int depth = ihdr.depth();
        final int colorType = ihdr.colorType();

        final int samplesPerPixel = switch (colorType) {
            case 0 -> 1;       // GREYSCALE
            case 2 -> 3;       // TRUECOLOR
            case 3 -> 1;       // INDEXED
            case 4 -> 2;       // GREYSCALE + ALPHA
            case 6 -> 4;       // TRUECOLOR + ALPHA
            default -> 1;
        };

        final int bitsPerPixel = samplesPerPixel * depth;
        return (width * bitsPerPixel + 7) / 8;
    }

    /**
     * Applies filter reconstruction to a scanline
     * Modifies currentRow in place
     */
    private void unfilterRow(final byte[] currentRow, final byte[] previousRow, final int filterType, final int bpp) throws IOException {
        final int length = currentRow.length;

        switch (filterType) {
            case FILTER_NONE -> {
                // NO TRANSFORMATION NEEDED
            }

            case FILTER_SUB -> {
                // Recon(x) = Filt(x) + Recon(a)
                for (int i = bpp; i < length; i++) {
                    final int a = currentRow[i - bpp] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + a);
                }
            }

            case FILTER_UP -> {
                // Recon(x) = Filt(x) + Recon(b)
                for (int i = 0; i < length; i++) {
                    final int b = previousRow[i] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + b);
                }
            }

            case FILTER_AVERAGE -> {
                // Recon(x) = Filt(x) + floor((Recon(a) + Recon(b)) / 2)
                for (int i = 0; i < length; i++) {
                    final int a = (i >= bpp) ? (currentRow[i - bpp] & 0xFF) : 0;
                    final int b = previousRow[i] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + ((a + b) >> 1));
                }
            }

            case FILTER_PAETH -> {
                // Recon(x) = Filt(x) + PaethPredictor(Recon(a), Recon(b), Recon(c))
                for (int i = 0; i < length; i++) {
                    final int a = (i >= bpp) ? (currentRow[i - bpp] & 0xFF) : 0;
                    final int b = previousRow[i] & 0xFF;
                    final int c = (i >= bpp) ? (previousRow[i - bpp] & 0xFF) : 0;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + this.pathPredictor(a, b, c));
                }
            }

            default -> throw new DecoderException("Unknown filter type: " + filterType);
        }
    }

    /**
     * Path predictor function as defined in PNG spec
     * Returns the value nearest to p = a + b - c
     */
    private int pathPredictor(final int a, final int b, final int c) {
        final int p = a + b - c;
        final int pa = Math.abs(p - a);
        final int pb = Math.abs(p - b);
        final int pc = Math.abs(p - c);

        // RETURN NEAREST, BREAKING TIES IN ORDER a, b, c
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    /**
     * Decodes pixels from a filtered scanline into the pixel array
     */
    private void decodeRowPixels(final byte[] row, final int[][] pixels, final int y, final int xStart, final int xStep,
                                 final int passWidth, final IHDR ihdr, final PLTE plte, final TRNS trns) throws IOException {
        final int depth = ihdr.depth();
        final int colorType = ihdr.colorType();
        final int imageWidth = ihdr.width();

        int bitOffset = 0;

        for (int passX = 0; passX < passWidth; passX++) {
            final int x = xStart + passX * xStep;
            if (x >= imageWidth) break;

            final int argb;
            switch (colorType) {
                case 0 -> { // GREYSCALE
                    int gray = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;
                    gray = this.scaleTo8Bit(gray, depth);

                    int alpha = 255;
                    if (trns != null && gray == trns.gray()) {
                        alpha = 0;
                    }
                    argb = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                }

                case 2 -> { // TRUECOLOR
                    int r = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;
                    int g = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;
                    int b = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;

                    r = this.scaleTo8Bit(r, depth);
                    g = this.scaleTo8Bit(g, depth);
                    b = this.scaleTo8Bit(b, depth);

                    int alpha = 255;
                    if (trns != null && r == this.scaleTo8Bit(trns.red(), depth) &&
                            g == this.scaleTo8Bit(trns.green(), depth) &&
                            b == this.scaleTo8Bit(trns.blue(), depth)) {
                        alpha = 0;
                    }
                    argb = (alpha << 24) | (r << 16) | (g << 8) | b;
                }

                case 3 -> { // INDEXED-COLOR
                    final int index = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;

                    if (plte == null) {
                        throw new DecoderException("Indexed-color image without PLTE");
                    }
                    final int rgb = plte.getColor(index);
                    final int alpha = (trns != null) ? trns.getAlpha(index) : 255;
                    argb = (alpha << 24) | rgb;
                }

                case 4 -> { // GREYSCALE WITH ALPHA
                    int gray = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;
                    int alpha = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;

                    gray = this.scaleTo8Bit(gray, depth);
                    alpha = this.scaleTo8Bit(alpha, depth);
                    argb = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                }

                case 6 -> { // TRUECOLOR WITH ALPHA
                    int r = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;
                    int g = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;
                    int b = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;
                    int alpha = this.extractSample(row, bitOffset, depth);
                    bitOffset += depth;

                    r = this.scaleTo8Bit(r, depth);
                    g = this.scaleTo8Bit(g, depth);
                    b = this.scaleTo8Bit(b, depth);
                    alpha = this.scaleTo8Bit(alpha, depth);
                    argb = (alpha << 24) | (r << 16) | (g << 8) | b;
                }

                default -> throw new DecoderException("Unknown color type: " + colorType);
            }

            pixels[y][x] = argb;
        }
    }

    /**
     * Extracts a sample value from the row at the given bit offset
     */
    private int extractSample(final byte[] row, final int bitOffset, final int depth) {
        final int byteOffset = bitOffset / 8;
        if (depth >= 8) {
            if (depth == 8) {
                return row[byteOffset] & 0xFF;
            } else { // 16-bit
                return ((row[byteOffset] & 0xFF) << 8) | (row[byteOffset + 1] & 0xFF);
            }
        } else {
            // SUB-BYTE SAMPLES (1, 2, 4 bits)
            final int bitInByte = 8 - (bitOffset % 8) - depth;
            final int mask = (1 << depth) - 1;
            return (row[byteOffset] >> bitInByte) & mask;
        }
    }

    /**
     * Scales a sample value from its original depth to 8-bit
     */
    private int scaleTo8Bit(final int value, final int depth) {
        if (depth == 8) return value;
        if (depth == 16) return value >> 8;
        if (depth == 1) return value * 255;
        if (depth == 2) return (value * 255) / 3;
        if (depth == 4) return (value * 255) / 15;
        return value;
    }

    /**
     * Converts ARGB pixel array to BGRA ByteBuffer
     */
    private ByteBuffer toBGRA(final int[][] pixels, final int width, final int height) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int argb = pixels[y][x];
                final int a = (argb >> 24) & 0xFF;
                DataTool.rgbaToBrga(buffer, argb, (byte) a);
            }
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Applies APNG dispose operation to output buffer
     */
    private void applyDispose(final int[][] outputBuffer, final int[][] previousBuffer, final FCTL fctl,
                              final BKGD bkgd, final int depth, final PLTE plte, final int canvasWidth, final int canvasHeight) {
        final int disposeOp = fctl.dispose() & 0xFF;
        final int xOffset = fctl.xOffset();
        final int yOffset = fctl.yOffset();
        final int frameWidth = fctl.width();
        final int frameHeight = fctl.height();

        switch (disposeOp) {
            case FCTL.DISPOSE_OP_NONE -> { // LEAVE AS IS

            }

            case FCTL.DISPOSE_OP_BACKGROUND -> { // CLEAR TO BACKGROUND COLOR
                final int bgColor = (bkgd != null) ? this.bkgdToARGB(bkgd, depth, plte) : 0x00000000;
                for (int y = yOffset; y < yOffset + frameHeight && y < canvasHeight; y++) {
                    for (int x = xOffset; x < xOffset + frameWidth && x < canvasWidth; x++) {
                        outputBuffer[y][x] = bgColor;
                    }
                }
            }

            case FCTL.DISPOSE_OP_PREVIOUS -> { // RESTORE TO PREVIOUS
                for (int y = yOffset; y < yOffset + frameHeight && y < canvasHeight; y++) {
                    for (int x = xOffset; x < xOffset + frameWidth && x < canvasWidth; x++) {
                        outputBuffer[y][x] = previousBuffer[y][x];
                    }
                }
            }
        }
    }

    /**
     * Applies APNG blend operation to composite frame onto output buffer
     */
    private void applyBlendOp(final int[][] outputBuffer, final int[][] framePixels, final FCTL fctl) {
        final int blendOp = fctl.blend() & 0xFF;
        final int xOffset = fctl.xOffset();
        final int yOffset = fctl.yOffset();
        final int frameWidth = fctl.width();
        final int frameHeight = fctl.height();

        for (int y = 0; y < frameHeight; y++) {
            final int outY = yOffset + y;
            if (outY >= outputBuffer.length) break;

            for (int x = 0; x < frameWidth; x++) {
                final int outX = xOffset + x;
                if (outX >= outputBuffer[0].length) break;

                final int srcARGB = framePixels[y][x];

                switch (fctl.blend()) {
                    case FCTL.BLEND_OP_SOURCE -> outputBuffer[outY][outX] = srcARGB;
                    case FCTL.BLEND_OP_OVER -> outputBuffer[outY][outX] = this.alphaComposite(srcARGB, outputBuffer[outY][outX]);

                    default -> {
                        // UNKNOWN BLEND OP, DEFAULT TO SOURCE
                        outputBuffer[outY][outX] = srcARGB;
                        LOGGER.warn(IT, "Unknown blend operation: {}", blendOp);
                    }
                }
            }
        }
    }

    /**
     * Alpha composites source over destination (Porter-Duff "over" operation)
     */
    private int alphaComposite(final int srcARGB, final int dstARGB) {
        final int srcA = (srcARGB >> 24) & 0xFF;
        final int srcR = (srcARGB >> 16) & 0xFF;
        final int srcG = (srcARGB >> 8) & 0xFF;
        final int srcB = srcARGB & 0xFF;

        final int dstA = (dstARGB >> 24) & 0xFF;
        final int dstR = (dstARGB >> 16) & 0xFF;
        final int dstG = (dstARGB >> 8) & 0xFF;
        final int dstB = dstARGB & 0xFF;

        // PREMULTIPLY
        final int srcRA = srcR * srcA;
        final int srcGA = srcG * srcA;
        final int srcBA = srcB * srcA;

        final int dstRA = dstR * dstA;
        final int dstGA = dstG * dstA;
        final int dstBA = dstB * dstA;

        // COMPOSITE
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

    /**
     * Converts chunk type int to readable string
     */
    private String chunkTypeToString(final int type) {
        return String.valueOf(new char[] {
                (char) ((type >> 24) & 0xFF),
                (char) ((type >> 16) & 0xFF),
                (char) ((type >> 8) & 0xFF),
                (char) (type & 0xFF)
        });
    }

    @Override
    public boolean test() {
        // TODO: add a proper test
        return true;
    }
}