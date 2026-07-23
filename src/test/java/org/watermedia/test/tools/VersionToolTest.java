package org.watermedia.test.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.tools.VersionTool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link VersionTool} parsing, ordering and the equals/hashCode contract
 * kept consistent with {@link VersionTool#compareTo}.
 */
@DisplayName("VersionTool")
public class VersionToolTest {

    @Test
    @DisplayName("parses major.minor.revision and a dash-separated qualifier")
    void parsesQualifier() {
        final VersionTool v = new VersionTool("3.2.1-beta");
        assertEquals(3, v.major);
        assertEquals(2, v.minor);
        assertEquals(1, v.revision);
        assertEquals("beta", v.extra);
    }

    @Test
    @DisplayName("an empty qualifier tail yields a null extra")
    void emptyQualifierIsNull() {
        final VersionTool v = new VersionTool("1.4.0");
        assertEquals(1, v.major);
        assertEquals(4, v.minor);
        assertEquals(0, v.revision);
        assertNull(v.extra);
    }

    @Test
    @DisplayName("null and unparseable inputs become the zero version")
    void zeroVersion() {
        assertTrue(new VersionTool(null).isZero());
        assertTrue(new VersionTool("not-a-version").isZero());
        assertFalse(new VersionTool("0.0.1").isZero());
        assertEquals("<Not Found>", new VersionTool(null).toString());
    }

    @Test
    @DisplayName("compareTo weighs major.minor.revision only")
    void comparison() {
        assertTrue(new VersionTool("1.2.3").compareTo(new VersionTool("1.2.4")) < 0);
        assertTrue(new VersionTool("1.3.0").compareTo(new VersionTool("1.2.9")) > 0);
        assertEquals(0, new VersionTool("1.2.3-gpl").compareTo(new VersionTool("1.2.3-lgpl")));
        assertTrue(new VersionTool("2.0.0").atLeast(new VersionTool("2.0.0")));
        assertTrue(new VersionTool("2.5.0").inRange(new VersionTool("2.0.0"), new VersionTool("3.0.0")));
        assertFalse(new VersionTool("3.0.0").inRange(new VersionTool("2.0.0"), new VersionTool("3.0.0")));
    }

    @Test
    @DisplayName("equals/hashCode are consistent with compareTo (extra is ignored)")
    void equalityConsistentWithOrdering() {
        final VersionTool a = new VersionTool("1.2.3-gpl");
        final VersionTool b = new VersionTool("1.2.3-lgpl");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new VersionTool("1.2.4"));
    }
}
