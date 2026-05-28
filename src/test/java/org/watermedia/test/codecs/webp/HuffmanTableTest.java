package org.watermedia.test.codecs.webp;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.webp.BitReader;
import org.watermedia.api.codecs.readers.webp.lossless.HuffmanTable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * HuffmanTable unit tests for the VP8L canonical Huffman decoder. Covers single-symbol
 * tables (no bits consumed), 1-bit two-symbol tables, multi-length code-length builds
 * and a few edge cases (null/all-zero code lengths).
 */
public class HuffmanTableTest {

    @TestFactory
    Iterable<DynamicTest> testSingleSymbol() {
        final List<DynamicTest> tests = new ArrayList<>();

        for (final int symbol: new int[] {0, 1, 42, 127, 255}) {
            tests.add(dynamicTest("Single symbol [" + symbol + "]", () -> {
                // SINGLE-SYMBOL TABLE — READS RETURN THE SYMBOL WITHOUT CONSUMING BITS
                final HuffmanTable table = HuffmanTable.simple(1, symbol, 0);
                final byte[] data = {(byte) 0xFF, (byte) 0xFF};
                final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));

                for (int i = 0; i < 10; i++) {
                    final int result = table.read(reader);
                    assertEquals(symbol, result, "Single symbol table should always return: " + symbol);
                }
                assertEquals(2, reader.remaining(), "Single symbol table should not consume any bits");
            }));
        }

        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> testTwoSymbol() {
        final List<DynamicTest> tests = new ArrayList<>();

        // BYTE 0xAA = 10101010 — LSB-FIRST READ ORDER YIELDS sym0, sym1, sym0, sym1...
        final int[][] pairs = {{0, 1}, {10, 20}, {100, 200}};
        for (final int[] pair: pairs) {
            final int sym0 = pair[0];
            final int sym1 = pair[1];
            tests.add(dynamicTest("Two symbols [" + sym0 + ", " + sym1 + "]", () -> {
                final HuffmanTable table = HuffmanTable.simple(2, sym0, sym1);
                final byte[] data = {(byte) 0xAA};
                final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
                final int[] expected = {sym0, sym1, sym0, sym1, sym0, sym1, sym0, sym1};
                for (int i = 0; i < 8; i++) {
                    assertEquals(expected[i], table.read(reader), "Mismatch at bit position " + i);
                }
            }));
        }

        return tests;
    }

    @Test
    void buildFromCodeLengths() throws XCodecException {
        // CANONICAL ASSIGNMENT: SYM0/1 SYM1/10 SYM2/110 SYM3/111
        final int[] codeLengths = new int[8];
        codeLengths[0] = 1;
        codeLengths[1] = 2;
        codeLengths[2] = 3;
        codeLengths[3] = 3;

        final HuffmanTable table = HuffmanTable.build(codeLengths, 8);
        final String info = table.debugInfo();
        assertNotNull(info, "Debug info should not be null");
        assertTrue(info.contains("symbolsWithCode=4"), "Should have 4 symbols with codes");
    }

    @Test
    void edgeCaseCodeLengths() throws XCodecException {
        // ALL ZEROS COLLAPSES TO THE SINGLE SYMBOL 0 TABLE
        final int[] allZeros = new int[10];
        final HuffmanTable zeroTable = HuffmanTable.build(allZeros, 10);
        assertNotNull(zeroTable, "Should handle all-zero code lengths");

        // SINGLE NON-ZERO CODE LENGTH AT INDEX 5 — TABLE MUST RETURN SYMBOL 5
        final int[] singleSymbol = new int[10];
        singleSymbol[5] = 1;
        final HuffmanTable singleTable = HuffmanTable.build(singleSymbol, 10);
        final BitReader reader = new BitReader(ByteBuffer.wrap(new byte[]{(byte) 0xFF}).order(ByteOrder.LITTLE_ENDIAN));
        assertEquals(5, singleTable.read(reader), "Single symbol table should return symbol 5");
    }

    @Test
    void sequentialReadsConsumeBitsCorrectly() throws XCodecException {
        final HuffmanTable table = HuffmanTable.simple(2, 0, 1);
        // BYTE 0x55 = 01010101 — LSB-FIRST READS YIELD 1, 0, 1, 0...
        final byte[] data = {(byte) 0x55, (byte) 0x55};
        final BitReader reader = new BitReader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));

        int sum = 0;
        for (int i = 0; i < 16; i++) sum += table.read(reader);
        assertEquals(8, sum, "Should read 8 ones and 8 zeros");
    }

    @Test
    void twoSymbol2BitCodes() throws XCodecException {
        // TWO SYMBOLS WITH EQUAL 2-BIT CODES — MIRRORS WHAT 6.webp'S META GREEN TABLE USES
        final int[] codeLengths = new int[280];
        codeLengths[0] = 2;
        codeLengths[1] = 2;

        final HuffmanTable table = HuffmanTable.build(codeLengths, 280);
        assertNotNull(table.debugInfo(), "Debug info should not be null");

        // 0x00 BIT STREAM SHOULD YIELD SYMBOL 0 (CODE 00) FOUR TIMES
        final BitReader reader = new BitReader(ByteBuffer.wrap(new byte[]{0x00}).order(ByteOrder.LITTLE_ENDIAN));
        for (int i = 0; i < 4; i++) {
            assertEquals(0, table.read(reader), "Should read symbol 0 for code 00");
        }
    }

    @Test
    void nullCodeLengthsHandled() throws XCodecException {
        // NULL CODE LENGTHS MUST PRODUCE A VALID FALLBACK TABLE
        assertNotNull(HuffmanTable.build(null, 10), "Should handle null code lengths");
    }
}
