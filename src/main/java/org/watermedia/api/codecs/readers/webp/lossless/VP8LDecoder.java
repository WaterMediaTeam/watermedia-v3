package org.watermedia.api.codecs.readers.webp.lossless;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.webp.BitReader;
import org.watermedia.api.codecs.common.webp.ColorCache;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public final class VP8LDecoder {
    static final Marker IT = MarkerManager.getMarker(VP8LDecoder.class.getSimpleName());
    // RECOMMENDED (LOG-ONLY) HUFFMAN GROUP COUNT. THE ENTROPY IMAGE CAN DECLARE UP TO 65536
    // GROUPS (16-BIT META CODES) AND EACH ONE COSTS 5 TABLES; COUNTS THE REMAINING BITSTREAM
    // CANNOT PHYSICALLY ENCODE ARE REJECTED BEFORE TABLE ALLOCATION (SEE FEASIBILITY GATE BELOW)
    private static final int RECOMMENDED_GROUPS = 256;
    // MINIMUM BITS ONE GROUP CAN OCCUPY: 5 SIMPLE-CODE TABLES x 4 BITS (isSimple + numSymbols +
    // is8Bits + 1-BIT SYMBOL) EACH
    private static final int MIN_GROUP_BITS = 20;

    private VP8LDecoder() {
    }

    // DECODE VP8L BITSTREAM TO ARGB PIXELS
    public static int[] decode(final BitReader reader, final int width, final int height) throws XCodecException {
        LOGGER.trace(IT, "Starting decode: {}x{}", width, height);

        // PARSE TRANSFORMS
        final List<Transform> transforms = new ArrayList<>();
        int currentWidth = width;
        int seenTransforms = 0;

        while (reader.readBool()) {
            final Transform.Type type = Transform.typeof(reader.read(2));
            // RFC 9649: EACH TRANSFORM TYPE MAY APPEAR AT MOST ONCE; UNCHECKED REPEATS WOULD LET
            // A TINY FILE QUEUE THOUSANDS OF FULL-IMAGE INVERSE PASSES (CPU DOS)
            final int typeBit = 1 << type.ordinal();
            if ((seenTransforms & typeBit) != 0) throw new XCodecException("Duplicate VP8L transform: " + type);
            seenTransforms |= typeBit;
            LOGGER.trace(IT, "Reading transform type: {}", type);
            final Transform transform = readTransform(reader, type, currentWidth, height);
            LOGGER.trace(IT, "Transform read complete, remaining bytes: {}", reader.remaining());
            transforms.add(transform);

            // COLOR INDEXING MAY REDUCE WIDTH
            if (type == Transform.Type.COLOR_INDEXING) { // COLOR_INDEXING
                final int tableSize = transform.data().length;
                final int widthBits = ColorTransform.widthBits(tableSize);
                if (widthBits > 0) {
                    currentWidth = (currentWidth + (1 << widthBits) - 1) >> widthBits;
                }
            }
        }

        LOGGER.trace(IT, "No more transforms, position: {}", reader.bitPosition());

        // READ COLOR CACHE INFO
        int colorCacheBits = 0;
        ColorCache colorCache = null;
        if (reader.readBool()) {
            colorCacheBits = reader.read(4);
            LOGGER.trace(IT, "Main color cache bits: {}", colorCacheBits);
            colorCache = new ColorCache(colorCacheBits);
        } else {
            LOGGER.trace(IT, "No main color cache");
        }
        final int colorCacheSize = (colorCache != null) ? colorCache.size() : 0;

        // READ META PREFIX CODES (ENTROPY IMAGE)
        final HuffmanGroup[] huffmanGroups;
        int[] metaPrefixImage = null;
        int metaBits = 0;
        int metaWidth = 0;

        if (reader.readBool()) {
            // MULTIPLE HUFFMAN GROUPS
            metaBits = reader.read(3) + 2;
            metaWidth = (currentWidth + (1 << metaBits) - 1) >> metaBits;
            final int metaHeight = (height + (1 << metaBits) - 1) >> metaBits;
            LOGGER.trace(IT, "Meta prefix: bits={}, size={}x{}, remaining bytes: {}", metaBits, metaWidth, metaHeight, reader.remaining());

            // DECODE ENTROPY IMAGE
            metaPrefixImage = decodeImage(reader, metaWidth, metaHeight);

            // FIND MAX META PREFIX CODE
            int maxCode = 0;
            for (final int code: metaPrefixImage) {
                final int meta = ((code >> 8) & 0xFF) | ((code >> 16) & 0xFF) << 8;
                maxCode = Math.max(maxCode, meta);
            }

            final int numGroups = maxCode + 1;
            // FEASIBILITY GATE: A GROUP COUNT THE REMAINING BITSTREAM CANNOT POSSIBLY ENCODE IS
            // CORRUPT OR MALICIOUS (A 1x1 ENTROPY IMAGE CAN DECLARE 65536 GROUPS ~ 200 MB OF
            // TABLES FROM A FEW-KB FILE); FAIL BEFORE ALLOCATING INSTEAD OF AFTER
            final long feasibleGroups = (reader.remaining() * 8L + reader.bitsAvail()) / MIN_GROUP_BITS;
            if (numGroups > feasibleGroups)
                throw new XCodecException("VP8L declares " + numGroups + " huffman groups, bitstream can hold at most " + feasibleGroups);
            if (numGroups > RECOMMENDED_GROUPS) {
                LOGGER.warn(IT, "VP8L declares {} huffman groups, above the recommended limit of {}", numGroups, RECOMMENDED_GROUPS);
                LOGGER.trace(IT, "VP8L huffman groups: declared={}, meta={}x{} (bits={}), colorCacheSize={}, tables={}",
                        numGroups, metaWidth, metaHeight, metaBits, colorCacheSize, numGroups * 5);
            }
            huffmanGroups = HuffmanDecoder.readGroups(reader, numGroups, colorCacheSize);
        } else {
            // SINGLE HUFFMAN GROUP
            huffmanGroups = new HuffmanGroup[]{HuffmanDecoder.readGroup(reader, colorCacheSize)};
        }

        // DECODE MAIN IMAGE
        final int[] pixels = decodeImageData(reader, currentWidth, height, huffmanGroups,
                metaPrefixImage, metaBits, metaWidth, colorCache);

        // APPLY INVERSE TRANSFORMS IN REVERSE ORDER
        for (int i = transforms.size() - 1; i >= 0; i--) {
            final Transform t = transforms.get(i);
            switch (t.type()) {
                case COLOR_INDEXING -> {
                    final int widthBits = ColorTransform.widthBits(t.data().length);
                    ColorTransform.applyPalette(pixels, width, height, t.data(), widthBits);
                }
                case SUBTRACT_GREEN -> ColorTransform.addGreen(pixels);
                case COLOR -> ColorTransform.inverse(pixels, width, height, t.data(), t.bits());
                case PREDICTOR -> Predictor.inverse(pixels, width, height, t.data(), t.bits());
            }
        }

        return pixels;
    }

    // DECODE VP8L BITSTREAM DIRECTLY TO BGRA BYTEBUFFER (EFFICIENT - USES ZERO-COPY WRAP)
    public static ByteBuffer decodeToBgra(final BitReader reader, final int width, final int height) throws XCodecException {
        final int[] bgra = decode(reader, width, height);
        return DataTool.bgraToBuffer(bgra);
    }

    private static Transform readTransform(final BitReader reader, final Transform.Type type, final int width, final int height) throws XCodecException {
        return switch (type) {
            case PREDICTOR -> {
                final int bits = reader.read(3) + 2;
                final int blockWidth = (width + (1 << bits) - 1) >> bits;
                final int blockHeight = (height + (1 << bits) - 1) >> bits;
                final int[] data = decodeImage(reader, blockWidth, blockHeight);
                yield Transform.block(Transform.Type.PREDICTOR, bits, data);
            }
            case COLOR -> {
                final int bits = reader.read(3) + 2;
                final int blockWidth = (width + (1 << bits) - 1) >> bits;
                final int blockHeight = (height + (1 << bits) - 1) >> bits;
                final int[] data = decodeImage(reader, blockWidth, blockHeight);
                yield Transform.block(Transform.Type.COLOR, bits, data);
            }
            case SUBTRACT_GREEN -> Transform.subtractGreen();
            case COLOR_INDEXING -> {
                final int tableSize = reader.read(8) + 1;
                // PIXEL BUNDLING SUPPORT IS PENDING: PALETTES WITH 16 OR FEWER COLORS PACK SEVERAL
                // PIXELS INTO EACH GREEN-CHANNEL BYTE, SO THE MAIN IMAGE DECODES AT A REDUCED WIDTH.
                // THE CURRENT INVERSE PATH (ColorTransform.applyPalette) UNPACKS INTO A FULL-WIDTH
                // BUFFER AND ARRAYCOPIES IT BACK INTO THE REDUCED-WIDTH PIXEL ARRAY, OVERFLOWING IT
                // WITH AN UNCONTROLLED IndexOutOfBoundsException. FAIL CLEANLY INSTEAD.
                // REMOVE THIS EXCEPTION ONCE BUNDLING IS PROPERLY IMPLEMENTED (THE UNPACKED
                // FULL-WIDTH BUFFER MUST BECOME THE DECODE RESULT INSTEAD OF BEING COPIED BACK).
                if (ColorTransform.widthBits(tableSize) > 0)
                    throw new XCodecException("WEBP lossless bundled palette not supported yet (" + tableSize + " colors)");
                final int[] rawTable = decodeImage(reader, tableSize, 1);
                final int[] colorTable = ColorTransform.decodeColorTable(rawTable);
                yield Transform.colorTable(colorTable);
            }
        };
    }

    // DECODE SUB-IMAGE (FOR TRANSFORMS AND ENTROPY IMAGE)
    private static int[] decodeImage(final BitReader reader, final int width, final int height) throws XCodecException {
        // READ OPTIONAL COLOR CACHE FOR THIS SUB-IMAGE; THE ColorCache CTOR REJECTS OUT-OF-RANGE
        // BITS (A BITSTREAM ERROR PER SPEC) INSTEAD OF SILENTLY DROPPING THE DECLARED CACHE
        int colorCacheSize = 0;
        ColorCache subCache = null;
        if (reader.readBool()) {
            subCache = new ColorCache(reader.read(4));
            colorCacheSize = subCache.size();
        }

        final HuffmanGroup group = HuffmanDecoder.readGroup(reader, colorCacheSize);
        return decodeImageData(reader, width, height, new HuffmanGroup[]{group}, null, 0, 0, subCache);
    }

    private static int[] decodeImageData(final BitReader reader, final int width, final int height,
                                         final HuffmanGroup[] groups, final int[] metaImage,
                                         final int metaBits, final int metaWidth,
                                         final ColorCache colorCache) throws XCodecException {
        final int total = width * height;
        final int[] pixels = new int[total];
        int pos = 0;
        // INCREMENTAL PIXEL COORDS FOR THE META-GROUP LOOKUP, AVOIDING PER-PIXEL DIV/MOD
        int x = 0, y = 0;

        while (pos < total) {
            // GET HUFFMAN GROUP FOR CURRENT POSITION
            final HuffmanGroup group;
            if (metaImage != null) {
                final int metaCode = metaImage[(y >> metaBits) * metaWidth + (x >> metaBits)];
                final int groupIndex = ((metaCode >> 8) & 0xFF) | (((metaCode >> 16) & 0xFF) << 8);
                group = groups[Math.min(groupIndex, groups.length - 1)];
            } else {
                group = groups[0];
            }

            // READ GREEN/LENGTH SYMBOL
            final int code = group.green().read(reader);

            if (code < 256) {
                // LITERAL ARGB
                final int green = code;
                final int red = group.red().read(reader);
                final int blue = group.blue().read(reader);
                final int alpha = group.alpha().read(reader);

                final int argb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                pixels[pos++] = argb;
                if (colorCache != null) colorCache.insert(argb);
                if (++x == width) { x = 0; y++; }
            } else if (code < 256 + 24) {
                // LZ77 BACKWARD REFERENCE
                final int length = LZ77.prefixToValue(code - 256, reader);
                final int distCode = group.dist().read(reader);
                final int distValue = LZ77.prefixToValue(distCode, reader);
                final int dist = LZ77.distanceToOffset(distValue, width);

                // RFC 9649: BACKWARD REFERENCES MUST STAY INSIDE THE ALREADY-DECODED PIXELS;
                // OUT-OF-RANGE DISTANCE OR OVERLONG LENGTH IS A BITSTREAM ERROR, NOT BEST-EFFORT
                if (dist > pos) throw new XCodecException("VP8L backward reference before image start");
                if (pos + length > total) throw new XCodecException("VP8L backward reference overruns image");

                for (int i = 0; i < length; i++) {
                    final int argb = pixels[pos - dist];
                    pixels[pos++] = argb;
                    if (colorCache != null) colorCache.insert(argb);
                }
                // ADVANCE COORDS BY THE COPIED RUN (ONE DIV PER TOKEN, NOT PER PIXEL)
                x += length;
                if (x >= width) { y += x / width; x %= width; }
            } else {
                // COLOR CACHE
                final int cacheIndex = code - 256 - 24;
                if (colorCache == null) throw new XCodecException("Color cache code without color cache");
                final int argb = colorCache.lookup(cacheIndex);
                pixels[pos++] = argb;
                colorCache.insert(argb);
                if (++x == width) { x = 0; y++; }
            }
        }

        return pixels;
    }
}
