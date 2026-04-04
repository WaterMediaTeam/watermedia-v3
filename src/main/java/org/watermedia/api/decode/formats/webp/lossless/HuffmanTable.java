package org.watermedia.api.decode.formats.webp.lossless;

import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.formats.webp.common.BitReader;

public final class HuffmanTable {
    private static final int LOOKUP_BITS = 7;
    private static final int LOOKUP_SIZE = 1 << LOOKUP_BITS;
    private static final int LOOKUP_MASK = LOOKUP_SIZE - 1;

    // LOOKUP TABLE: LOW 8 BITS = LENGTH, HIGH 24 BITS = SYMBOL
    private final int[] lookup;
    // FOR CODES LONGER THAN LOOKUP_BITS
    private final int[] codes;
    private final int[] lengths;
    private final int maxCode;
    // SINGLE SYMBOL FLAG - IF TRUE, singleSymbolValue IS RETURNED WITHOUT READING BITS
    private final boolean isSingleSymbol;
    private final int singleSymbolValue;

    private HuffmanTable(final int[] lookup, final int[] codes, final int[] lengths, final int maxCode,
                         final boolean isSingleSymbol, final int singleSymbolValue) {
        this.lookup = lookup;
        this.codes = codes;
        this.lengths = lengths;
        this.maxCode = maxCode;
        this.isSingleSymbol = isSingleSymbol;
        this.singleSymbolValue = singleSymbolValue;
    }

    public int read(final BitReader reader) throws DecoderException {
        // CHECK FOR SINGLE SYMBOL TABLE (NO BITS NEEDED)
        if (this.isSingleSymbol) {
            return this.singleSymbolValue;
        }

        // LSB-FIRST READING: ACCUMULATE BITS FROM LEAST SIGNIFICANT
        // WEBP STORES BITS LSB-FIRST, SO WE ACCUMULATE THEM LSB-FIRST
        // AND USE REVERSED CODES IN THE LOOKUP TABLE
        int code = 0;
        for (int len = 1; len <= 15; len++) {
            final int bit = reader.read(1);
            code |= (bit << (len - 1));

            // CHECK LOOKUP TABLE FOR SHORT CODES
            if (len <= LOOKUP_BITS) {
                final int entry = this.lookup[code];
                final int entryLen = entry & 0xFF;
                if (entryLen == len && entryLen > 0) {
                    return entry >>> 8;
                }
            } else {
                // SLOW PATH: SEARCH IN CODES ARRAY COMPARING REVERSED CODES
                for (int j = 0; j < this.lengths.length; j++) {
                    if (this.lengths[j] == len) {
                        final int reversedCode = reverseBits(this.codes[j], len);
                        if (reversedCode == code) {
                            return j;
                        }
                    }
                }
            }
        }
        // Debug: show what's in the lookup table
        final StringBuilder sb = new StringBuilder();
        sb.append("Invalid huffman code (code=").append(code).append(")");
        sb.append(", table info: ").append(this.debugInfo());
        sb.append(", first 8 lookup entries: ");
        for (int i = 0; i < Math.min(8, this.lookup.length); i++) {
            sb.append("[").append(i).append("]=").append(this.lookup[i] & 0xFF).append("/").append(this.lookup[i] >>> 8).append(" ");
        }
        throw new DecoderException(sb.toString());
    }

    // BUILD TABLE FROM CODE LENGTHS
    public static HuffmanTable build(final int[] codeLengths, final int alphabetSize) throws DecoderException {
        if (codeLengths == null || codeLengths.length == 0) {
            // EMPTY TABLE - SINGLE SYMBOL 0
            final int[] lookup = new int[LOOKUP_SIZE];
            java.util.Arrays.fill(lookup, 1); // LENGTH 1, SYMBOL 0
            return new HuffmanTable(lookup, new int[0], new int[0], 0, true, 0);
        }

        // COUNT CODE LENGTHS AND FIND SINGLE SYMBOL IF PRESENT
        final int[] lengthCount = new int[16];
        int maxLen = 0;
        int symbolCount = 0;
        int singleSymbol = -1;
        for (int i = 0; i < codeLengths.length; i++) {
            if (codeLengths[i] > 0) {
                lengthCount[codeLengths[i]]++;
                maxLen = Math.max(maxLen, codeLengths[i]);
                symbolCount++;
                singleSymbol = i;
            }
        }

        // RFC 9649: IF ONLY ONE SYMBOL HAS NON-ZERO CODE LENGTH, IT REQUIRES ZERO BITS
        if (symbolCount == 1) {
            final int[] lookup = new int[LOOKUP_SIZE];
            final int[] codes = new int[alphabetSize];
            final int[] lengths = new int[alphabetSize];
            return new HuffmanTable(lookup, codes, lengths, alphabetSize - 1, true, singleSymbol);
        }

        // NO SYMBOLS WITH CODES - TREAT AS SINGLE SYMBOL 0
        if (symbolCount == 0) {
            final int[] lookup = new int[LOOKUP_SIZE];
            final int[] codes = new int[alphabetSize];
            final int[] lengths = new int[alphabetSize];
            return new HuffmanTable(lookup, codes, lengths, alphabetSize - 1, true, 0);
        }

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

        // BUILD LOOKUP TABLE
        // Codes are stored reversed because we read bits LSB-first
        final int[] lookup = new int[LOOKUP_SIZE];
        java.util.Arrays.fill(lookup, 0);

        for (int sym = 0; sym < alphabetSize; sym++) {
            final int len = lengths[sym];
            if (len > 0 && len <= LOOKUP_BITS) {
                final int c = codes[sym];
                // REVERSE BITS FOR LSB-FIRST READING
                final int reversed = reverseBits(c, len);
                // FILL ALL ENTRIES THAT MATCH THIS PREFIX
                final int step = 1 << len;
                for (int i = reversed; i < LOOKUP_SIZE; i += step) {
                    lookup[i] = (sym << 8) | len;
                }
            }
            // FOR CODES WITH len > LOOKUP_BITS, WE DON'T PUT ANYTHING IN LOOKUP
            // THE read() METHOD WILL FALL THROUGH TO SLOW PATH
        }

        return new HuffmanTable(lookup, codes, lengths, alphabetSize - 1, false, 0);
    }

    // BUILD SIMPLE TABLE (1-2 SYMBOLS)
    public static HuffmanTable simple(final int numSymbols, final int sym0, final int sym1) {
        final int[] lookup = new int[LOOKUP_SIZE];
        final int[] codes = new int[Math.max(sym0, sym1) + 1];
        final int[] lengths = new int[Math.max(sym0, sym1) + 1];

        if (numSymbols == 1) {
            // SINGLE SYMBOL - NO BITS NEEDED, ALWAYS RETURNS THIS SYMBOL
            // Lookup and lengths stay at defaults (not used due to isSingleSymbol flag)
            return new HuffmanTable(lookup, codes, lengths, Math.max(sym0, sym1), true, sym0);
        } else {
            // TWO SYMBOLS, LENGTH 1 EACH
            // BIT 0 = sym0, BIT 1 = sym1
            for (int i = 0; i < LOOKUP_SIZE; i++) {
                if ((i & 1) == 0) {
                    lookup[i] = (sym0 << 8) | 1;
                } else {
                    lookup[i] = (sym1 << 8) | 1;
                }
            }
            codes[sym0] = 0;
            lengths[sym0] = 1;
            codes[sym1] = 1;
            lengths[sym1] = 1;
            return new HuffmanTable(lookup, codes, lengths, Math.max(sym0, sym1), false, 0);
        }
    }

    public String debugInfo() {
        int nonZeroLookup = 0;
        int maxLen = 0;
        for (int i = 0; i < LOOKUP_SIZE; i++) {
            final int len = this.lookup[i] & 0xFF;
            if (len > 0) nonZeroLookup++;
            maxLen = Math.max(maxLen, len);
        }

        int nonZeroLengths = 0;
        int maxCodeLen = 0;
        for (int i = 0; i < this.lengths.length; i++) {
            if (this.lengths[i] > 0) {
                nonZeroLengths++;
                maxCodeLen = Math.max(maxCodeLen, this.lengths[i]);
            }
        }

        return "HuffmanTable[lookupNonZero=" + nonZeroLookup + ", maxLookupLen=" + maxLen +
                ", symbolsWithCode=" + nonZeroLengths + ", maxCodeLen=" + maxCodeLen + "]";
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