package org.watermedia.test.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.RequestHeaders;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the multi-valued, case-insensitive contract of {@link RequestHeaders}
 * and the default header bag built from a target URI.
 */
@DisplayName("RequestHeaders")
public class RequestHeadersTest {

    @Nested
    @DisplayName("mutation operations")
    class MutationTests {

        @Test
        @DisplayName("set replaces an existing single value")
        void setReplaces() {
            final RequestHeaders h = new RequestHeaders().set("X", "1").set("X", "2");
            assertEquals("2", h.get("X"));
            assertEquals(1, h.size());
        }

        @Test
        @DisplayName("add appends additional values without clobbering")
        void addAppends() {
            final RequestHeaders h = new RequestHeaders().add("X", "1").add("X", "2");
            assertEquals(List.of("1", "2"), h.getAll("X"));
        }

        @Test
        @DisplayName("name lookup is case-insensitive")
        void caseInsensitiveLookup() {
            final RequestHeaders h = new RequestHeaders().set("Accept", "json");
            assertEquals("json", h.get("ACCEPT"));
            assertEquals("json", h.get("accept"));
        }

        @Test
        @DisplayName("has reflects presence")
        void hasFlag() {
            final RequestHeaders h = new RequestHeaders().set("present", "yes");
            assertTrue(h.has("present"));
            assertTrue(h.has("PRESENT"));
            assertFalse(h.has("absent"));
        }

        @Test
        @DisplayName("removeAll wipes every match")
        void removeAllWipesEntries() {
            final RequestHeaders h = new RequestHeaders().add("X", "1").add("x", "2").add("Y", "3");
            h.removeAll("X");
            assertFalse(h.has("X"));
            assertEquals("3", h.get("Y"));
            assertEquals(1, h.size());
        }
    }

    @Nested
    @DisplayName("iteration and rendering")
    class RenderTests {

        @Test
        @DisplayName("entries preserve insertion order")
        void insertionOrder() {
            final RequestHeaders h = new RequestHeaders()
                    .set("First", "a")
                    .set("Second", "b")
                    .add("Second", "c");

            final List<RequestHeaders.Entry> entries = h.entries();
            assertEquals(3, entries.size());
            assertEquals("First", entries.get(0).name());
            assertEquals("Second", entries.get(1).name());
            assertEquals("b", entries.get(1).value());
            assertEquals("Second", entries.get(2).name());
            assertEquals("c", entries.get(2).value());
        }

        @Test
        @DisplayName("toRawString emits Name: Value lines terminated by newline")
        void rawString() {
            final RequestHeaders h = new RequestHeaders().set("A", "1").set("B", "2");
            assertEquals("A: 1\nB: 2\n", h.toRawString());
        }

        @Test
        @DisplayName("empty bag renders to empty string")
        void rawStringEmpty() {
            assertEquals("", new RequestHeaders().toRawString());
            assertTrue(new RequestHeaders().isEmpty());
        }
    }

    @Nested
    @DisplayName("defaults(URI)")
    class DefaultsTests {

        @Test
        @DisplayName("populates User-Agent, media-friendly Accept and host-derived Referer")
        void defaultsForHttpsUri() {
            final RequestHeaders h = RequestHeaders.defaults(URI.create("https://example.com/path"));

            assertNotNull(h.get("User-Agent"));
            assertFalse(h.get("User-Agent").isBlank());

            final String accept = h.get("Accept");
            assertNotNull(accept);
            assertTrue(accept.contains("audio/"));
            assertTrue(accept.contains("video/"));
            assertTrue(accept.contains("image/"));

            assertEquals("https://example.com/", h.get("Referer"));
        }

        @Test
        @DisplayName("null URI skips Referer")
        void nullUriSkipsReferer() {
            final RequestHeaders h = RequestHeaders.defaults(null);
            assertNull(h.get("Referer"));
            assertNotNull(h.get("User-Agent"));
        }

        @Test
        @DisplayName("toRawString of defaults emits all entries")
        void defaultsToRawString() {
            final RequestHeaders h = RequestHeaders.defaults(URI.create("https://example.com/"));
            final String raw = h.toRawString();
            assertTrue(raw.contains("User-Agent: "));
            assertTrue(raw.contains("Accept: "));
            assertTrue(raw.contains("Referer: https://example.com/"));
            assertTrue(raw.endsWith("\n"));
        }
    }
}
