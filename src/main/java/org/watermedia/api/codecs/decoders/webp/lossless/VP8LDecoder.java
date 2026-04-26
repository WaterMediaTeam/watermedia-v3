package org.watermedia.api.codecs.decoders.webp.lossless;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.webp.common.BitReader;
import org.watermedia.api.codecs.decoders.webp.common.ColorCache;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public final class VP8LDecoder {
    static final Marker IT = MarkerManager.getMarker(VP8LDecoder.class.getSimpleName());

    private VP8LDecoder() {
    }

    // DECODE VP8L BITSTREAM TO ARGB PIXELS
    public static int[] decode(final BitReader reader, final int width, final int height) throws XCodecException {
        LOGGER.debug(IT, "Starting decode: {}x{}", width, height);

        // PARSE TRANSFORMS
        final List<Transform> transforms = new ArrayList<>();
        int currentWidth = width;

        while (reader.readBool()) {
            final Transform.Type type = Transform.typeof(reader.read(2));
            LOGGER.debug(IT, "Reading transform type: {}", type);
            final Transform transform = readTransform(reader, type, currentWidth, height);
            LOGGER.debug(IT, "Transform read complete, remaining bytes: {}", reader.remaining());
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

        LOGGER.debug(IT, "No more transforms, position: {}", reader.bitPosition());

        // READ COLOR CACHE INFO
        int colorCacheBits = 0;
        ColorCache colorCache = null;
        if (reader.readBool()) {
            colorCacheBits = reader.read(4);
            LOGGER.debug(IT, "Main color cache bits: {}", colorCacheBits);
            colorCache = new ColorCache(colorCacheBits);
        } else {
            LOGGER.debug(IT, "No main color cache");
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
            LOGGER.debug(IT, "Meta prefix: bits={}, size={}x{}, remaining bytes: {}", metaBits, metaWidth, metaHeight, reader.remaining());

            // DECODE ENTROPY IMAGE
            LOGGER.debug(IT, "Decoding meta prefix image...");
            metaPrefixImage = decodeImage(reader, metaWidth, metaHeight, 0, null);
            LOGGER.debug(IT, "Meta prefix image decoded, remaining bytes: {}", reader.remaining());

            // FIND MAX META PREFIX CODE
            int maxCode = 0;
            for (final int code: metaPrefixImage) {
                final int meta = ((code >> 8) & 0xFF) | ((code >> 16) & 0xFF) << 8;
                maxCode = Math.max(maxCode, meta);
            }

            huffmanGroups = HuffmanDecoder.readGroups(reader, maxCode + 1, colorCacheSize);
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
                    currentWidth = width;
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

    // DECODE WITHOUT HEADER (FOR ALPHA CHANNEL)
    public static int[] decodeNoHeader(final BitReader reader, final int width, final int height) throws XCodecException {
        return decode(reader, width, height);
    }

    private static Transform readTransform(final BitReader reader, final Transform.Type type, final int width, final int height) throws XCodecException {
        return switch (type) {
            case PREDICTOR -> {
                final int bits = reader.read(3) + 2;
                final int blockWidth = (width + (1 << bits) - 1) >> bits;
                final int blockHeight = (height + (1 << bits) - 1) >> bits;
                final int[] data = decodeImage(reader, blockWidth, blockHeight, 0, null);
                yield Transform.block(Transform.Type.PREDICTOR, bits, data);
            }
            case COLOR -> {
                final int bits = reader.read(3) + 2;
                final int blockWidth = (width + (1 << bits) - 1) >> bits;
                final int blockHeight = (height + (1 << bits) - 1) >> bits;
                final int[] data = decodeImage(reader, blockWidth, blockHeight, 0, null);
                yield Transform.block(Transform.Type.COLOR, bits, data);
            }
            case SUBTRACT_GREEN -> Transform.subtractGreen();
            case COLOR_INDEXING -> {
                final int tableSize = reader.read(8) + 1;
                final int[] rawTable = decodeImage(reader, tableSize, 1, 0, null);
                final int[] colorTable = ColorTransform.decodeColorTable(rawTable);
                yield Transform.colorTable(colorTable);
            }
        };
    }

    // DECODE SUB-IMAGE (FOR TRANSFORMS AND ENTROPY IMAGE)
    private static int[] decodeImage(final BitReader reader, final int width, final int height,
                                     final int colorCacheBits, final ColorCache cache) throws XCodecException {
        LOGGER.debug(IT, "decodeImage: {}x{}, position: {}", width, height, reader.bitPosition());

        // NO META PREFIX FOR SUB-IMAGES
        int colorCacheSize = (colorCacheBits > 0) ? (1 << colorCacheBits) : 0;

        // READ COLOR CACHE FOR THIS SUB-IMAGE
        ColorCache subCache = null;
        if (reader.readBool()) {
            final int bits = reader.read(4);
            LOGGER.debug(IT, "Sub-image color cache bits: {}", bits);

            if (bits >= 1 && bits <= 11) {
                subCache = new ColorCache(bits);
                colorCacheSize = subCache.size();
            }
        } else {
            LOGGER.debug(IT, "Sub-image has no color cache");
        }

        LOGGER.debug(IT, "Reading huffman group, colorCacheSize={}", colorCacheSize);
        final HuffmanGroup group = HuffmanDecoder.readGroup(reader, colorCacheSize);
        LOGGER.debug(IT, "Huffman group read, remaining bytes: {}", reader.remaining());

        LOGGER.debug(IT, "Decoding {} pixels...", width * height);
        LOGGER.debug(IT, "Before pixel decode: {}", reader.bitPosition());
        LOGGER.debug(IT, "First pixel GREEN table info: {}", group.green().debugInfo());
        final int[] result = decodeImageData(reader, width, height, new HuffmanGroup[]{group},
                null, 0, 0, subCache);
        LOGGER.debug(IT, "Pixels decoded, position: {}", reader.bitPosition());
        return result;
    }

    private static int[] decodeImageData(final BitReader reader, final int width, final int height,
                                         final HuffmanGroup[] groups, final int[] metaImage,
                                         final int metaBits, final int metaWidth,
                                         final ColorCache colorCache) throws XCodecException {
        final int[] pixels = new int[width * height];
        int pos = 0;
        final int total = width * height;

        while (pos < total) {
            // GET HUFFMAN GROUP FOR CURRENT POSITION
            final HuffmanGroup group;
            if (metaImage != null) {
                final int x = pos % width;
                final int y = pos / width;
                final int mx = x >> metaBits;
                final int my = y >> metaBits;
                final int metaCode = metaImage[my * metaWidth + mx];
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

                if (colorCache != null) {
                    colorCache.insert(argb);
                }
            } else if (code < 256 + 24) {
                // LZ77 BACKWARD REFERENCE
                final int lengthCode = code - 256;
                final int length = LZ77.prefixToValue(lengthCode, reader);

                final int distCode = group.dist().read(reader);
                final int distValue = LZ77.prefixToValue(distCode, reader);
                final int dist = LZ77.distanceToOffset(distValue, width);

                // COPY PIXELS
                final int copyLength = Math.min(length, total - pos);
                for (int i = 0; i < copyLength; i++) {
                    final int argb;
                    if (pos - dist < 0) {
                        // REFERENCE BEFORE IMAGE START: USE BLACK
                        argb = 0xFF000000;
                    } else {
                        argb = pixels[pos - dist];
                    }
                    pixels[pos++] = argb;
                    if (colorCache != null) {
                        colorCache.insert(argb);
                    }
                }
            } else {
                // COLOR CACHE
                final int cacheIndex = code - 256 - 24;
                if (colorCache == null) {
                    throw new XCodecException("Color cache code without color cache");
                }
                final int argb = colorCache.lookup(cacheIndex);
                pixels[pos++] = argb;
                colorCache.insert(argb);
            }
        }

        return pixels;
    }
}
