package org.watermedia.test.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.tools.JsonTool;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the null-safe accessors and lenient URL parsing in {@link JsonTool}.
 */
@DisplayName("JsonTool")
public class JsonToolTest {

    private static JsonObject sample() {
        return JsonTool.parse("{\"s\":\"x\",\"d\":2.5,\"b\":true,\"i\":7,\"nil\":null}", JsonObject.class);
    }

    @Test
    @DisplayName("parse(null) yields null; parse binds an object")
    void parseBinding() {
        assertNull(JsonTool.parse((String) null, JsonObject.class));
        assertTrue(JsonTool.write(java.util.Map.of("a", 1)).contains("\"a\""));
    }

    @Test
    @DisplayName("typed accessors read present keys")
    void typedAccessors() {
        final JsonObject o = sample();
        assertEquals("x", JsonTool.str(o, "s"));
        assertEquals(2.5, JsonTool.dbl(o, "d"));
        assertTrue(JsonTool.bool(o, "b"));
        assertEquals(7, JsonTool.intOr(o, "i", 0));
        assertEquals(Integer.valueOf(7), JsonTool.intOrNull(o, "i"));
    }

    @Test
    @DisplayName("absent or JSON-null keys fall back to their default")
    void missingKeys() {
        final JsonObject o = sample();
        assertNull(JsonTool.str(o, "missing"));
        assertNull(JsonTool.str(o, "nil"));
        assertEquals(0d, JsonTool.dbl(o, "missing"));
        assertFalse(JsonTool.bool(o, "missing"));
        assertEquals(9, JsonTool.intOr(o, "missing", 9));
        assertNull(JsonTool.intOrNull(o, "nil"));
    }

    @Test
    @DisplayName("uri promotes protocol-relative URLs and swallows malformed input")
    void lenientUri() {
        assertEquals(URI.create("https://host/x"), JsonTool.uri("//host/x"));
        assertEquals(URI.create("http://a.com"), JsonTool.uri("http://a.com"));
        assertNull(JsonTool.uri("bad uri with spaces"));
        assertNull(JsonTool.uri((String) null));
        assertNull(JsonTool.uri(""));
    }
}
