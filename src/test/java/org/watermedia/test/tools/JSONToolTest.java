package org.watermedia.test.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.tools.JSONTool;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the null-safe accessors and lenient URL parsing in {@link JSONTool}.
 */
@DisplayName("JsonTool")
public class JSONToolTest {

    private static JsonObject sample() {
        return JSONTool.parse("{\"s\":\"x\",\"d\":2.5,\"b\":true,\"i\":7,\"nil\":null}", JsonObject.class);
    }

    @Test
    @DisplayName("parse(null) yields null; parse binds an object")
    void parseBinding() {
        assertNull(JSONTool.parse((String) null, JsonObject.class));
        assertTrue(JSONTool.write(java.util.Map.of("a", 1)).contains("\"a\""));
    }

    @Test
    @DisplayName("typed accessors read present keys")
    void typedAccessors() {
        final JsonObject o = sample();
        assertEquals("x", JSONTool.str(o, "s"));
        assertEquals(2.5, JSONTool.dbl(o, "d"));
        assertTrue(JSONTool.bool(o, "b"));
        assertEquals(7, JSONTool.intOr(o, "i", 0));
        assertEquals(Integer.valueOf(7), JSONTool.intOrNull(o, "i"));
    }

    @Test
    @DisplayName("absent or JSON-null keys fall back to their default")
    void missingKeys() {
        final JsonObject o = sample();
        assertNull(JSONTool.str(o, "missing"));
        assertNull(JSONTool.str(o, "nil"));
        assertEquals(0d, JSONTool.dbl(o, "missing"));
        assertFalse(JSONTool.bool(o, "missing"));
        assertEquals(9, JSONTool.intOr(o, "missing", 9));
        assertNull(JSONTool.intOrNull(o, "nil"));
    }

    @Test
    @DisplayName("uri promotes protocol-relative URLs and swallows malformed input")
    void lenientUri() {
        assertEquals(URI.create("https://host/x"), JSONTool.uri("//host/x"));
        assertEquals(URI.create("http://a.com"), JSONTool.uri("http://a.com"));
        assertNull(JSONTool.uri("bad uri with spaces"));
        assertNull(JSONTool.uri((String) null));
        assertNull(JSONTool.uri(""));
    }
}
