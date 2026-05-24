package org.watermedia.api.codecs.decoders.webp.lossless;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.webp.common.BitReader;

public final class HuffmanTable {
    private static final int LOOKUP_BITS = 7;
    private static final int LOOKUP_SIZE = 1 << LOOKUP_BITS;

    // LOOKUP TABLE: LOW 8 BITS = LENGTH, HIGH 24 BITS = SYMBOL
    private final int[] lookup;
    // PRE-COMPUTED REVERSED LONG CODES (len > LOOKUP_BITS) FOR FAST SLOW-PATH LINEAR SCAN
    private final int[] longLens;
    private final int[] longCodes;   // already reversed for LSB-first compare
    private final int[] longSymbols;
    private final int longMaxLen;
    // SINGLE SYMBOL FLAG - IF TRUE, singleSymbolValue IS RETURNED WITHOUT READING BITS
    private final boolean isSingleSymbol;
    private final int singleSymbolValue;

    private HuffmanTable(final int[] lookup,
                         final int[] longLens, final int[] longCodes, final int[] longSymbols, final int longMaxLen,
                         final boolean isSingleSymbol, final int singleSymbolValue) {
        this.lookup = lookup;
        this.longLens = longLens;
        this.longCodes = longCodes;
        this.longSymbols = longSymbols;
        this.longMaxLen = longMaxLen;
        this.isSingleSymbol = isSingleSymbol;
        this.singleSymbolValue = singleSymbolValue;
    }

    public int read(final BitReader reader) throws XCodecException {
        // CHECK FOR SINGLE SYMBOL TABLE (NO BITS NEEDED)
        if (this.isSingleSymbol) {
            return this.singleSymbolValue;
        }

        final int idx = reader.peek(LOOKUP_BITS);
        if (reader.bitsAvail() >= LOOKUP_BITS) {
            // FAST PATH: PEEK LOOKUP_BITS, RESOLVE IN O(1) FROM THE PREFILLED LOOKUP TABLE.
            final int entry = this.lookup[idx];
            final int len = entry & 0xFF;
            if (len > 0) {
                reader.consume(len);
                return entry >>> 8;
            }
            // LONG CODE: PEELED 7 BITS, KEEP EXTENDING ONE AT A TIME AND COMPARE AGAINST
            // PRE-COMPUTED REVERSED CODES.
            int code = idx;
            reader.consume(LOOKUP_BITS);
            for (int curLen = LOOKUP_BITS + 1; curLen <= this.longMaxLen; curLen++) {
                final int bit = reader.read(1);
                code |= bit << (curLen - 1);
                for (int j = 0; j < this.longLens.length; j++) {
                    if (this.longLens[j] == curLen && this.longCodes[j] == code) {
                        return this.longSymbols[j];
                    }
                }
            }
            throw new XCodecException("Invalid huffman code (code=" + code + ")");
        }

        // SLOW SAFE PATH: NEAR EOF, READ BIT-BY-BIT WHILE PROPAGATING EOF EXCEPTIONS.
        int code = 0;
        for (int curLen = 1; curLen <= 15; curLen++) {
            final int bit = reader.read(1);
            code |= bit << (curLen - 1);
            if (curLen <= LOOKUP_BITS) {
                final int entry = this.lookup[code];
                if ((entry & 0xFF) == curLen) {
                    return entry >>> 8;
                }
            } else {
                for (int j = 0; j < this.longLens.length; j++) {
                    if (this.longLens[j] == curLen && this.longCodes[j] == code) {
                        return this.longSymbols[j];
                    }
                }
            }
        }
        throw new XCodecException("Invalid huffman code (code=" + code + ")");
    }

    // BUILD TABLE FROM CODE LENGTHS
    public static HuffmanTable build(final int[] codeLengths, final int alphabetSize) throws XCodecException {
        if (codeLengths == null || codeLengths.length == 0) {
            // EMPTY TABLE - SINGLE SYMBOL 0
            return singleSymbolTable(0);
        }

        // COUNT CODE LENGTHS AND FIND SINGLE SYMBOL IF PRESENT
        final int[] lengthCount = new int[16];
        int maxLen = 0;
        int symbolCount = 0;
        int singleSymbol = -1;
        for (int i = 0; i < codeLengths.length; i++) {
            if (codeLengths[i] > 0) {
                lengthCount[codeLengths[i]]++;
                if (codeLengths[i] > maxLen) maxLen = codeLengths[i];
                symbolCount++;
                singleSymbol = i;
            }
        }

        // RFC 9649: IF ONLY ONE SYMBOL HAS NON-ZERO CODE LENGTH, IT REQUIRES ZERO BITS
        if (symbolCount == 1) return singleSymbolTable(singleSymbol);
        // NO SYMBOLS WITH CODES - TREAT AS SINGLE SYMBOL 0
        if (symbolCount == 0) return singleSymbolTable(0);

        // COMPUTE FIRST CODE FOR EACH LENGTH
        final int[] nextCode = new int[16];
        int code = 0;
        for (int len = 1; len <= maxLen; len++) {
            code = (code + lengthCount[len - 1]) << 1;
            nextCode[len] = code;
        }

        // ASSIGN CODES TO SYMBOLS
        final int[] codes = new int[alphabetSize];
        final int[] lengths = new int[alphabetSize];
        for (int i = 0; i < codeLengths.length && i < alphabetSize; i++) {
            final int len = codeLengths[i];
            if (len > 0) {
                codes[i] = nextCode[len]++;
                lengths[i] = len;
            }
        }

        // BUILD LOOKUP TABLE FOR SHORT CODES
        // Codes are stored reversed because we read bits LSB-first
        final int[] lookup = new int[LOOKUP_SIZE];

        // BUILD COMPACT LONG-CODE TABLES FOR len > LOOKUP_BITS
        int longCount = 0;
        for (int sym = 0; sym < alphabetSize; sym++) {
            if (lengths[sym] > LOOKUP_BITS) longCount++;
        }
        final int[] longLens = new int[longCount];
        final int[] longCodes = new int[longCount];
        final int[] longSymbols = new int[longCount];
        int longIdx = 0;
        int longMaxLen = 0;

        for (int sym = 0; sym < alphabetSize; sym++) {
            final int len = lengths[sym];
            if (len == 0) continue;
            final int c = codes[sym];
            final int reversed = reverseBits(c, len);
            if (len <= LOOKUP_BITS) {
                // FILL ALL ENTRIES THAT MATCH THIS PREFIX
                final int step = 1 << len;
                final int packed = (sym << 8) | len;
                for (int i = reversed; i < LOOKUP_SIZE; i += step) {
                    lookup[i] = packed;
                }
            } else {
                longLens[longIdx] = len;
                longCodes[longIdx] = reversed;
                longSymbols[longIdx] = sym;
                longIdx++;
                if (len > longMaxLen) longMaxLen = len;
            }
        }

        return new HuffmanTable(lookup, longLens, longCodes, longSymbols, longMaxLen, false, 0);
    }

    // BUILD SIMPLE TABLE (1-2 SYMBOLS)
    public static HuffmanTable simple(final int numSymbols, final int sym0, final int sym1) {
        if (numSymbols == 1) {
            return singleSymbolTable(sym0);
        }

        // TWO SYMBOLS, LENGTH 1 EACH. BIT 0 = sym0, BIT 1 = sym1
        final int[] lookup = new int[LOOKUP_SIZE];
        for (int i = 0; i < LOOKUP_SIZE; i++) {
            lookup[i] = ((i & 1) == 0 ? (sym0 << 8) : (sym1 << 8)) | 1;
        }
        return new HuffmanTable(lookup, new int[0], new int[0], new int[0], 0, false, 0);
    }

    private static HuffmanTable singleSymbolTable(final int symbol) {
        final int[] lookup = new int[LOOKUP_SIZE];
        return new HuffmanTable(lookup, new int[0], new int[0], new int[0], 0, true, symbol);
    }

    public String debugInfo() {
        int nonZeroLookup = 0;
        int maxLookupLen = 0;
        for (int i = 0; i < LOOKUP_SIZE; i++) {
            final int len = this.lookup[i] & 0xFF;
            if (len > 0) nonZeroLookup++;
            if (len > maxLookupLen) maxLookupLen = len;
        }

        int symbolsWithCode;
        int maxCodeLen;
        if (this.isSingleSymbol) {
            symbolsWithCode = 1;
            maxCodeLen = 0;
        } else {
            // Distinct symbols across both lookup entries and long-code list.
            int distinct = this.longLens.length;
            final boolean[] seen = new boolean[65536];
            for (int i = 0; i < LOOKUP_SIZE; i++) {
                final int entry = this.lookup[i];
                if ((entry & 0xFF) == 0) continue;
                final int sym = entry >>> 8;
                if (sym < seen.length && !seen[sym]) {
                    seen[sym] = true;
                    distinct++;
                }
            }
            symbolsWithCode = distinct;
            maxCodeLen = Math.max(maxLookupLen, this.longMaxLen);
        }

        return "HuffmanTable[lookupNonZero=" + nonZeroLookup + ", maxLookupLen=" + maxLookupLen +
                ", symbolsWithCode=" + symbolsWithCode + ", maxCodeLen=" + maxCodeLen + "]";
    }

    private static int reverseBits(int value, final int numBits) {
        int result = 0;
        for (int i = 0; i < numBits; i++) {
            result = (result << 1) | (value & 1);
            value >>>= 1;
        }
        return result;
    }
}
