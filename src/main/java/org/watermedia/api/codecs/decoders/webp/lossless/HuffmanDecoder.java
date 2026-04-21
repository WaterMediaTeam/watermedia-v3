package org.watermedia.api.codecs.decoders.webp.lossless;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.webp.common.BitReader;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.codecs.decoders.webp.lossless.VP8LDecoder.IT;

public final class HuffmanDecoder {

    // CODE LENGTH CODE ORDER (RFC 9649 SECTION 3.7.2.1.2)
    private static final int[] CODE_LENGTH_ORDER = {
            17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private HuffmanDecoder() {
    }

    // READ A SINGLE HUFFMAN TABLE FROM BITSTREAM
    public static HuffmanTable readTable(final BitReader reader, final int alphabetSize) throws XCodecException {
        final boolean isSimple = reader.readBool();
        LOGGER.debug(IT, "readTable: alphabetSize={}, isSimple={}", alphabetSize, isSimple);

        if (isSimple) {
            return readSimpleCode(reader, alphabetSize);
        } else {
            return readNormalCode(reader, alphabetSize);
        }
    }

    // READ HUFFMAN GROUP (5 TABLES)
    public static HuffmanGroup readGroup(final BitReader reader, final int colorCacheSize) throws XCodecException {
        // ALPHABET SIZES:
        // GREEN: 256 LITERALS + 24 LENGTH CODES + COLOR_CACHE_SIZE
        // RED, BLUE, ALPHA: 256
        // DISTANCE: 40
        final int greenSize = 256 + 24 + colorCacheSize;

        LOGGER.debug(IT, "readGroup: before GREEN: {}", reader.bitPosition());
        final HuffmanTable green = readTable(reader, greenSize);
        LOGGER.debug(IT, "readGroup: before RED: {}", reader.bitPosition());
        final HuffmanTable red = readTable(reader, 256);
        LOGGER.debug(IT, "readGroup: before BLUE: {}", reader.bitPosition());
        final HuffmanTable blue = readTable(reader, 256);
        LOGGER.debug(IT, "readGroup: before ALPHA: {}", reader.bitPosition());
        final HuffmanTable alpha = readTable(reader, 256);
        LOGGER.debug(IT, "readGroup: before DIST: {}", reader.bitPosition());
        final HuffmanTable dist = readTable(reader, 40);
        LOGGER.debug(IT, "readGroup: after DIST: {}", reader.bitPosition());

        return new HuffmanGroup(green, red, blue, alpha, dist);
    }

    // READ MULTIPLE HUFFMAN GROUPS
    public static HuffmanGroup[] readGroups(final BitReader reader, final int numGroups, final int colorCacheSize) throws XCodecException {
        final HuffmanGroup[] groups = new HuffmanGroup[numGroups];
        for (int i = 0; i < numGroups; i++) {
            groups[i] = readGroup(reader, colorCacheSize);
        }
        return groups;
    }

    private static HuffmanTable readSimpleCode(final BitReader reader, final int alphabetSize) throws XCodecException {
        final int numSymbols = reader.read(1) + 1;
        final int is8Bits = reader.read(1);

        final int sym0 = reader.read(1 + 7 * is8Bits);
        if (sym0 >= alphabetSize) {
            throw new XCodecException("Simple code symbol out of range: " + sym0);
        }

        int sym1 = 0;
        if (numSymbols == 2) {
            sym1 = reader.read(8);
            if (sym1 >= alphabetSize) {
                throw new XCodecException("Simple code symbol out of range: " + sym1);
            }
        }

        LOGGER.debug(IT, "readSimpleCode: alphabetSize={}, numSymbols={}, is8Bits={}, sym0={}, sym1={}",
                alphabetSize, numSymbols, is8Bits, sym0, sym1);

        return HuffmanTable.simple(numSymbols, sym0, sym1);
    }

    private static HuffmanTable readNormalCode(final BitReader reader, final int alphabetSize) throws XCodecException {
        LOGGER.debug(IT, "readNormalCode: alphabetSize={}, remaining={}", alphabetSize, reader.remaining());

        // READ NUMBER OF CODE LENGTH CODES
        final int numCodeLengthCodes = reader.read(4) + 4;
        LOGGER.debug(IT, "  numCodeLengthCodes={}", numCodeLengthCodes);

        // READ CODE LENGTH CODE LENGTHS
        final int[] codeLengthCodeLengths = new int[19];
        for (int i = 0; i < numCodeLengthCodes; i++) {
            codeLengthCodeLengths[CODE_LENGTH_ORDER[i]] = reader.read(3);
        }

        // BUILD CODE LENGTH HUFFMAN TABLE
        final HuffmanTable codeLengthTable = HuffmanTable.build(codeLengthCodeLengths, 19);
        LOGGER.debug(IT, "  codeLengthTable: {}", codeLengthTable.debugInfo());

        // READ MAX SYMBOL
        final int maxSymbol;
        final boolean useLength = reader.readBool();
        if (useLength) {
            final int lengthNBits = reader.read(3);
            final int lengthBits = 2 + 2 * lengthNBits;
            maxSymbol = 2 + reader.read(lengthBits);
            LOGGER.debug(IT, "  lengthNBits={}, lengthBits={}, maxSymbol={}", lengthNBits, lengthBits, maxSymbol);
            if (maxSymbol > alphabetSize) {
                LOGGER.debug(IT, "  ERROR: maxSymbol={} > alphabetSize={}", maxSymbol, alphabetSize);
                throw new XCodecException("Max symbol exceeds alphabet size");
            }
        } else {
            maxSymbol = alphabetSize;
            LOGGER.debug(IT, "  maxSymbol={} (default)", maxSymbol);
        }

        // READ CODE LENGTHS
        // MAX_SYMBOL IS A TOKEN COUNTER (NUMBER OF CODE-LENGTH ENTRIES TO READ),
        // NOT A SYMBOL POSITION LIMIT. REPEAT CODES (16/17/18) CONSUME ONE TOKEN
        // BUT FILL MULTIPLE SYMBOLS. MATCH LIBWEBP'S READHUFFMANCODELENGTHS BEHAVIOR.
        final int[] codeLengths = new int[alphabetSize];
        int prevCodeLen = 8;
        int symbol = 0;
        int tokensRemaining = maxSymbol;

        LOGGER.debug(IT, "  Reading code lengths, maxSymbol={}, position before: {}", maxSymbol, reader.bitPosition());
        while (symbol < alphabetSize && tokensRemaining > 0) {
            tokensRemaining--;
            final int code = codeLengthTable.read(reader);

            if (code < 16) {
                // LITERAL CODE LENGTH
                codeLengths[symbol++] = code;
                if (code != 0) {
                    prevCodeLen = code;
                }
            } else if (code == 16) {
                // REPEAT PREVIOUS
                final int extraBits = reader.read(2);
                final int repeat = 3 + extraBits;
                if (symbol + repeat > alphabetSize) {
                    throw new XCodecException("Repeat code overflow: symbol=" + symbol + ", repeat=" + repeat + ", alphabetSize=" + alphabetSize);
                }
                for (int i = 0; i < repeat; i++) {
                    codeLengths[symbol++] = prevCodeLen;
                }
            } else if (code == 17) {
                // REPEAT ZERO (SHORT)
                final int extraBits = reader.read(3);
                final int repeat = 3 + extraBits;
                if (symbol + repeat > alphabetSize) {
                    throw new XCodecException("Repeat zero overflow: symbol=" + symbol + ", repeat=" + repeat + ", alphabetSize=" + alphabetSize);
                }
                symbol += repeat;
            } else if (code == 18) {
                // REPEAT ZERO (LONG)
                final int extraBits = reader.read(7);
                final int repeat = 11 + extraBits;
                if (symbol + repeat > alphabetSize) {
                    throw new XCodecException("Repeat zero overflow: symbol=" + symbol + ", repeat=" + repeat + ", alphabetSize=" + alphabetSize);
                }
                symbol += repeat;
            }
        }

        // COUNT SYMBOLS WITH CODES
        int symbolCount = 0;
        for (int i = 0; i < alphabetSize; i++) {
            if (codeLengths[i] > 0) {
                symbolCount++;
            }
        }
        LOGGER.debug(IT, "  symbolCount={}, position after: {}", symbolCount, reader.bitPosition());

        return HuffmanTable.build(codeLengths, alphabetSize);
    }
}
