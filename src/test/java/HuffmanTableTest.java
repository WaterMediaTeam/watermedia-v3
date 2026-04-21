import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.webp.common.BitReader;
import org.watermedia.api.codecs.decoders.webp.lossless.HuffmanTable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Unit tests for the HuffmanTable class used in VP8L lossless decoding.
 * Tests cover single symbol tables, two symbol tables, and canonical Huffman code generation.
 */
public class HuffmanTableTest {

    /**
     * Test factory for single symbol Huffman tables.
     * Single symbol tables should return the symbol without reading any bits.
     *
     * @return collection of dynamic tests for single symbol scenarios
     */
    @TestFactory
    Iterable<DynamicTest> testSingleSymbol() {
        final List<DynamicTest> tests = new ArrayList<>();

        // TEST VARIOUS SINGLE SYMBOL VALUES
        final int[] symbols = {0, 1, 42, 127, 255};

        for (final int symbol : symbols) {
            tests.add(dynamicTest(
                    "Single symbol [" + symbol + "]",
                    () -> verifySingleSymbol(symbol)
            ));
        }

        return tests;
    }

    /**
     * Verifies that a single symbol table returns the correct symbol without consuming bits.
     */
    private void verifySingleSymbol(final int symbol) throws XCodecException {
        // CREATE SINGLE SYMBOL TABLE
        final HuffmanTable table = HuffmanTable.simple(1, symbol, 0);

        // CREATE BIT READER WITH SOME DATA (SHOULD NOT BE CONSUMED)
        final byte[] data = {(byte) 0xFF, (byte) 0xFF};
        final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));

        // READ MULTIPLE TIMES - SHOULD ALWAYS RETURN SAME SYMBOL
        for (int i = 0; i < 10; i++) {
            final int result = table.read(reader);
            assertEquals(symbol, result, "Single symbol table should always return: " + symbol);
        }

        // VERIFY NO BITS WERE CONSUMED (REMAINING BYTES UNCHANGED)
        assertEquals(2, reader.remaining(), "Single symbol table should not consume any bits");
    }

    /**
     * Test factory for two symbol Huffman tables with 1-bit codes.
     * Two symbol tables use 1-bit codes: bit 0 = symbol0, bit 1 = symbol1.
     *
     * @return collection of dynamic tests for two symbol scenarios
     */
    @TestFactory
    Iterable<DynamicTest> testTwoSymbol() {
        final List<DynamicTest> tests = new ArrayList<>();

        // TEST VARIOUS SYMBOL PAIRS
        tests.add(dynamicTest("Two symbols [0, 1]", () -> verifyTwoSymbols(0, 1)));
        tests.add(dynamicTest("Two symbols [10, 20]", () -> verifyTwoSymbols(10, 20)));
        tests.add(dynamicTest("Two symbols [100, 200]", () -> verifyTwoSymbols(100, 200)));

        return tests;
    }

    /**
     * Verifies that a two symbol table correctly reads 1-bit codes.
     */
    private void verifyTwoSymbols(final int sym0, final int sym1) throws XCodecException {
        // CREATE TWO SYMBOL TABLE
        final HuffmanTable table = HuffmanTable.simple(2, sym0, sym1);

        // BYTE 0xAA = 10101010 IN BINARY
        // READING LSB-FIRST: BITS ARE 0,1,0,1,0,1,0,1
        // SO WE EXPECT: SYM0, SYM1, SYM0, SYM1, ...
        final byte[] data = {(byte) 0xAA};
        final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));

        final int[] expected = {sym0, sym1, sym0, sym1, sym0, sym1, sym0, sym1};
        for (int i = 0; i < 8; i++) {
            final int result = table.read(reader);
            assertEquals(expected[i], result, "Mismatch at bit position " + i);
        }
    }

    /**
     * Test for building Huffman tables from code lengths.
     * Verifies canonical Huffman code generation follows the spec.
     */
    @Test
    void testBuildFromCodeLengths() throws XCodecException {
        // CREATE TABLE WITH SYMBOLS AT DIFFERENT CODE LENGTHS
        // SYMBOL 0: LENGTH 1 -> CODE 0
        // SYMBOL 1: LENGTH 2 -> CODE 10
        // SYMBOL 2: LENGTH 3 -> CODE 110
        // SYMBOL 3: LENGTH 3 -> CODE 111
        final int[] codeLengths = new int[8];
        codeLengths[0] = 1;
        codeLengths[1] = 2;
        codeLengths[2] = 3;
        codeLengths[3] = 3;

        final HuffmanTable table = HuffmanTable.build(codeLengths, 8);

        // VERIFY DEBUG INFO SHOWS EXPECTED STRUCTURE
        final String info = table.debugInfo();
        assertNotNull(info, "Debug info should not be null");
        assertTrue(info.contains("symbolsWithCode=4"), "Should have 4 symbols with codes");
    }

    /**
     * Test for empty/single symbol code length arrays.
     * These edge cases should create valid single-symbol tables.
     */
    @Test
    void testEdgeCaseCodeLengths() throws XCodecException {
        // ALL ZERO CODE LENGTHS - SHOULD CREATE SINGLE SYMBOL 0 TABLE
        final int[] allZeros = new int[10];
        final HuffmanTable zeroTable = HuffmanTable.build(allZeros, 10);
        assertNotNull(zeroTable, "Should handle all-zero code lengths");

        // SINGLE NON-ZERO CODE LENGTH
        final int[] singleSymbol = new int[10];
        singleSymbol[5] = 1;
        final HuffmanTable singleTable = HuffmanTable.build(singleSymbol, 10);

        // VERIFY SINGLE SYMBOL TABLE RETURNS CORRECT VALUE
        final byte[] data = {(byte) 0xFF};
        final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
        final int result = singleTable.read(reader);
        assertEquals(5, result, "Single symbol table should return symbol 5");
    }

    /**
     * Test for reading multiple symbols in sequence.
     * Verifies bit consumption is correct for mixed code lengths.
     */
    @Test
    void testSequentialReads() throws XCodecException {
        // CREATE SIMPLE TWO-SYMBOL TABLE
        final HuffmanTable table = HuffmanTable.simple(2, 0, 1);

        // BYTE 0x55 = 01010101 IN BINARY
        // READING LSB-FIRST: BITS ARE 1,0,1,0,1,0,1,0
        // SO WE EXPECT: 1, 0, 1, 0, ...
        final byte[] data = {(byte) 0x55, (byte) 0x55};
        final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));

        int sum = 0;
        for (int i = 0; i < 16; i++) {
            sum += table.read(reader);
        }

        // 16 READS, ALTERNATING 1,0,1,0... = 8 ONES
        assertEquals(8, sum, "Should read 8 ones and 8 zeros");
    }

    /**
     * Test for two symbol table with different code lengths (2-bit codes).
     * This is similar to what 6.webp's meta GREEN table uses.
     */
    @Test
    void testTwoSymbol2BitCodes() throws XCodecException {
        // BUILD TABLE WITH 2 SYMBOLS, BOTH WITH 2-BIT CODES
        final int[] codeLengths = new int[280];
        codeLengths[0] = 2;
        codeLengths[1] = 2;

        final HuffmanTable table = HuffmanTable.build(codeLengths, 280);

        // VERIFY TABLE WAS BUILT
        final String info = table.debugInfo();
        assertNotNull(info, "Debug info should not be null");

        // TEST READING - BYTE 0x00 SHOULD GIVE ALL ZEROS (ASSUMING CANONICAL CODE 00 -> SYMBOL 0)
        final byte[] data = {0x00};
        final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));

        // READ 4 TIMES (8 BITS / 2 BITS PER SYMBOL = 4 READS)
        for (int i = 0; i < 4; i++) {
            final int result = table.read(reader);
            assertEquals(0, result, "Should read symbol 0 for code 00");
        }
    }

    /**
     * Test that null/empty code lengths are handled gracefully.
     */
    @Test
    void testNullCodeLengths() throws XCodecException {
        // NULL CODE LENGTHS SHOULD CREATE DEFAULT TABLE
        final HuffmanTable table = HuffmanTable.build(null, 10);
        assertNotNull(table, "Should handle null code lengths");
    }
}
