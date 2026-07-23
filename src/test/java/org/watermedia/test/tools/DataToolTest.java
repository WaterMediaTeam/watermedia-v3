package org.watermedia.test.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.tools.DataTool;

import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure byte/string helpers in {@link DataTool}.
 */
@DisplayName("DataTool")
public class DataToolTest {

    @Test
    @DisplayName("bytesAt extracts the little-endian byte at a position")
    void bytesAt() {
        final long packet = 0x0102030405060708L;
        assertEquals((byte) 0x08, DataTool.bytesAt(packet, 0));
        assertEquals((byte) 0x01, DataTool.bytesAt(packet, 7));
    }

    @Test
    @DisplayName("toInt / toLong honor byte order")
    void byteOrderAssembly() {
        assertEquals(0x01020304, DataTool.toInt((byte) 1, (byte) 2, (byte) 3, (byte) 4, ByteOrder.BIG_ENDIAN));
        assertEquals(0x04030201, DataTool.toInt((byte) 1, (byte) 2, (byte) 3, (byte) 4, ByteOrder.LITTLE_ENDIAN));
        assertEquals(0x0102030405060708L,
                DataTool.toLong((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6, (byte) 7, (byte) 8, ByteOrder.BIG_ENDIAN));
    }

    @Test
    @DisplayName("hex renders lowercase two-digit bytes")
    void hex() {
        assertEquals("00ff0a", DataTool.hex(new byte[]{0x00, (byte) 0xFF, 0x0A}));
    }

    @Test
    @DisplayName("parseQuery decodes pairs and tolerates value-less keys")
    void parseQuery() {
        final Map<String, String> q = DataTool.parseQuery("a=1&b=%20&c");
        assertEquals("1", q.get("a"));
        assertEquals(" ", q.get("b"));
        assertEquals("", q.get("c"));
        assertTrue(DataTool.parseQuery("").isEmpty());
        assertTrue(DataTool.parseQuery(null).isEmpty());
    }

    @Test
    @DisplayName("arrayMapper pairs keys with values and rejects odd counts")
    void arrayMapper() {
        final Map<String, String> m = DataTool.arrayMapper(new String[]{"k1", "v1", "k2", "v2"});
        assertEquals("v1", m.get("k1"));
        assertEquals("v2", m.get("k2"));
        assertThrows(IllegalArgumentException.class, () -> DataTool.arrayMapper(new String[]{"lonely"}));
    }

    @Test
    @DisplayName("string prefix / suffix / case-insensitive helpers")
    void stringHelpers() {
        assertTrue(DataTool.startsWith("hello", "x", "he"));
        assertFalse(DataTool.startsWith("hello", "x", "y"));
        assertTrue(DataTool.endsWith("file.mp4", ".mkv", ".mp4"));
        assertTrue(DataTool.equalsAnyIgnoreCase("ABC", "abc"));
        assertTrue(DataTool.contains("hello", "ell"));
    }

    @Test
    @DisplayName("lenient numeric parsing falls back to the default")
    void numericFallback() {
        assertEquals(42, DataTool.toInt("42", 0));
        assertEquals(7, DataTool.toInt("nope", 7));
        assertEquals(3L, DataTool.toLong(null, 3L));
        assertEquals(1.5, DataTool.toDouble("1.5", 0.0));
        assertEquals(6L, DataTool.sumArray(1L, 2L, 3L));
    }
}
