package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link Metadata} record: accessor wiring, null tolerance and the rejection of a
 * negative duration, the only validation it carries.
 */
@DisplayName("Metadata record")
public class MetadataTest {

    @Test
    @DisplayName("constructor populates every accessor")
    void testAllFieldsPopulated() {
        final Instant postedAt = Instant.parse("2025-06-01T12:00:00Z");
        final Metadata md = new Metadata("Song", "Catchy tune", postedAt, 240_000L, "Artist");

        assertEquals("Song", md.title());
        assertEquals("Catchy tune", md.description());
        assertEquals(postedAt, md.postedAt());
        assertEquals(240_000L, md.durationMs());
        assertEquals("Artist", md.author());
    }

    @Test
    @DisplayName("nulls flow through the record unchanged")
    void testNullableFields() {
        final Metadata md = new Metadata(null, null, null, 0L, null);

        assertNull(md.title());
        assertNull(md.description());
        assertNull(md.postedAt());
        assertEquals(0L, md.durationMs());
        assertNull(md.author());
    }

    @Test
    @DisplayName("a negative duration is rejected")
    void testNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () -> new Metadata("t", null, null, -1L, null));
    }

    @Test
    @DisplayName("equals/hashCode follow record semantics")
    void testRecordEquality() {
        final Instant ts = Instant.parse("2025-06-01T12:00:00Z");
        final Metadata a = new Metadata("t", "d", ts, 1L, "x");
        final Metadata b = new Metadata("t", "d", ts, 1L, "x");
        final Metadata c = new Metadata("t", "d", ts, 2L, "x");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
