package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the pure byte/string helpers in {@link DataTool}. */
@DisplayName("DataTool")
public class DataToolTest {

    @Test
    @DisplayName("hex renders lowercase two-digit bytes")
    void hex() {
        assertEquals("00ff0a", DataTool.hex(new byte[]{0x00, (byte) 0xFF, 0x0A}));
    }

    @Test
    @DisplayName("parseQuery decodes pairs and tolerates value-less keys")
    void parseQuery() {
        final Map<String, String> q = DataTool.parseQuery("a=1&b=%20&c&&d=x=y");
        assertEquals("1", q.get("a"));
        assertEquals(" ", q.get("b"));
        assertEquals("", q.get("c"));
        assertEquals("x=y", q.get("d"));
        assertEquals(4, q.size());
        assertTrue(DataTool.parseQuery("").isEmpty());
        assertTrue(DataTool.parseQuery(null).isEmpty());
    }

    @Test
    @DisplayName("parseQuery decodes a raw query once and keeps malformed pairs raw")
    void parseQueryRaw() {
        assertEquals("a+b%3D", DataTool.parseQuery(URI.create("https://cdn.test/x?sig=a%2Bb%253D").getRawQuery()).get("sig"));
        final Map<String, String> q = DataTool.parseQuery("sig=a%2Bb%253D&bad=100%&tail=%E2%82%AC&k%zz=v");
        assertEquals("a+b%3D", q.get("sig"));
        assertEquals("100%", q.get("bad"));
        assertEquals("€", q.get("tail"));
        assertEquals("v", q.get("k%zz"));
        assertThrows(UnsupportedOperationException.class, () -> q.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> DataTool.parseQuery("a=1").put("x", "y"));
    }

    @Test
    @DisplayName("expiry reads a signed URL's lifetime in seconds or milliseconds and ignores the rest")
    void expiry() {
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), DataTool.expiry(URI.create("https://cdn.test/x?a=1&expire=1700000000")));
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), DataTool.expiry(URI.create("https://cdn.test/x?validto=1700000000000")));
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), DataTool.expiry(URI.create("https://cdn.test/x?X-Expires=1700000000")));
        assertNull(DataTool.expiry(URI.create("https://cdn.test/x?expires=soon")));
        assertNull(DataTool.expiry(URI.create("https://cdn.test/x?expires=3600")), "a relative lifetime is not an epoch");
        assertNull(DataTool.expiry(URI.create("https://cdn.test/x")));
        assertNull(DataTool.expiry(null));
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
        assertTrue(DataTool.equalsAnyIgnoreCase("ABC", "abc"));
        assertTrue(DataTool.contains("hello", "ell"));
    }

    @Test
    @DisplayName("lenient numeric parsing falls back to the default")
    void numericFallback() {
        assertEquals(42, DataTool.parseInt("42", 0));
        assertEquals(7, DataTool.parseInt("nope", 7));
        assertEquals(3L, DataTool.parseLong(null, 3L));
        assertEquals(1.5, DataTool.parseDouble("1.5", 0.0));
    }

    @Test
    @DisplayName("premultiply rounds every channel and alpha pair exactly within position and limit")
    void premultiply() {
        // EVERY (CHANNEL, ALPHA) PAIR: B AND R CARRY THE CHANNEL, G ITS COMPLEMENT, SO LANES CANNOT HIDE EACH OTHER
        final ByteBuffer pixels = ByteBuffer.allocateDirect(256 * 256 * 4 + 8);
        pixels.put(0, (byte) 0x7F).put(pixels.capacity() - 1, (byte) 0x7F).position(4);
        for (int a = 0; a < 256; a++)
            for (int c = 0; c < 256; c++)
                pixels.put((byte) c).put((byte) (255 - c)).put((byte) c).put((byte) a);
        pixels.position(4).limit(4 + 256 * 256 * 4);
        DataTool.premultiply(pixels);
        assertEquals(4, pixels.position());
        assertEquals(4 + 256 * 256 * 4, pixels.limit());
        pixels.limit(pixels.capacity());
        for (int a = 0, i = 4; a < 256; a++) {
            for (int c = 0; c < 256; c++, i += 4) {
                final int expected = Math.round(c * a / 255f);
                assertEquals(expected, pixels.get(i) & 0xFF, "B c=" + c + " a=" + a);
                assertEquals(Math.round((255 - c) * a / 255f), pixels.get(i + 1) & 0xFF, "G c=" + c + " a=" + a);
                assertEquals(expected, pixels.get(i + 2) & 0xFF, "R c=" + c + " a=" + a);
                assertEquals(a, pixels.get(i + 3) & 0xFF, "A c=" + c + " a=" + a);
            }
        }
        assertEquals(0x7F, pixels.get(0) & 0xFF);
        assertEquals(0x7F, pixels.get(pixels.capacity() - 1) & 0xFF);
    }

    @Test
    @DisplayName("blendOver passes opaque and clear sources through and never overflows a channel")
    void blendOver() {
        assertEquals(0x80FF0000, DataTool.blendOver(0x80FF0000, 0x00123456));
        assertEquals(0xFF0000FF, DataTool.blendOver(0x00FF0000, 0xFF0000FF));
        assertEquals(0xFF123456, DataTool.blendOver(0xFF123456, 0x80FF0000));
        // HALF-COVERING RED OVER OPAQUE BLUE IS AN EXACT LERP WITH AN OPAQUE RESULT
        assertEquals(0xFF80007F, DataTool.blendOver(0x80FF0000, 0xFF0000FF));
        // ALPHA 1 RED OVER ALPHA 2 RED MUST STAY RED WITH ALPHA 2: NO CHANNEL MAY OVERFLOW INTO ITS NEIGHBOUR
        assertEquals(0x02FF0000, DataTool.blendOver(0x01FF0000, 0x02FF0000));
        // EVERY ALPHA PAIR: RED COMES ONLY FROM THE SOURCE AND BLUE ONLY FROM THE DESTINATION, SO THEIR SUM IS
        // THE TOTAL COVERAGE AND CAN NEVER EXCEED 255; GREEN HAS NO CONTRIBUTOR AND STAYS ZERO
        for (int sa = 1; sa < 255; sa++) {
            for (int da = 0; da < 256; da++) {
                final int out = DataTool.blendOver((sa << 24) | 0xFF0000, (da << 24) | 0x0000FF);
                assertEquals(sa + da * (255 - sa) / 255, out >>> 24, "alpha sa=" + sa + " da=" + da);
                assertEquals(0, (out >> 8) & 0xFF, "green sa=" + sa + " da=" + da);
                assertTrue(((out >> 16) & 0xFF) + (out & 0xFF) <= 255, "coverage sa=" + sa + " da=" + da);
            }
        }
    }
}
