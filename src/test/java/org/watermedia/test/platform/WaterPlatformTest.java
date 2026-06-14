package org.watermedia.test.platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.internal.WaterPlatform;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link WaterPlatform#toHttpURL(URI)} mapping for the three
 * supported hosts ({@code local}, {@code global}, {@code remote}) and the
 * unknown-host failure mode.
 * <p>
 * {@code WaterPlatform} resolves {@code water://} URIs without any network
 * access, so it also serves as the offline witness for all three
 * {@link WaterPlatform#getData(URI)} outcomes: {@code null} (foreign URI),
 * a {@link PlatformData} instance (valid URI) and a thrown exception
 * (unknown host).
 */
public class WaterPlatformTest {

    @BeforeAll
    static void initWaterMedia() {
        // toHttpURL(local) NEEDS WaterMedia.cwd(); START THE FRAMEWORK ONCE PER TEST JVM.
        // CALLING start() TWICE THROWS IllegalStateException — SUPPRESS WHEN ALREADY INITIALIZED.
        try {
            WaterMedia.start("TEST", null, null, false);
        } catch (final IllegalStateException ignored) {
            // ALREADY STARTED BY ANOTHER TEST
        }
    }

    @Test
    void localResolvesToFileUriContainingPath() throws IOException {
        final String resolved = WaterPlatform.toHttpURL(URI.create("water://local/foo.png"));
        assertTrue(resolved.startsWith("file:"),
                "water://local/... must resolve to a file:// URI, got: " + resolved);
        assertTrue(resolved.endsWith("foo.png"),
                "Resolved URI must end with the requested path component, got: " + resolved);
    }

    @Test
    void globalResolvesToGlobalServerPrefix() throws IOException {
        final String resolved = WaterPlatform.toHttpURL(URI.create("water://global/x"));
        assertEquals(WaterPlatform.GLOBAL_SERVER + "x", resolved,
                "water://global/x must concatenate after WaterPlatform.GLOBAL_SERVER");
    }

    @Test
    void unknownHostThrowsIOException() {
        final IOException ex = assertThrows(IOException.class,
                () -> WaterPlatform.toHttpURL(URI.create("water://unknown/x")),
                "Unknown water:// host must throw IOException");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("unknown"),
                "IOException message should mention the unknown host, got: " + ex.getMessage());
    }

    // STATE: NULL — A NON-water:// URI IS NOT CLAIMED BY THIS PLATFORM
    @Test
    void getDataReturnsNullForForeignUri() throws Exception {
        assertNull(new WaterPlatform().getData(URI.create("https://example.com/x.png")),
                "getData must return null for a URI that is not a water:// scheme");
    }

    // STATE: INSTANCE — A VALID water:// URI RESOLVES TO PlatformData WITHOUT NETWORK
    @Test
    void getDataResolvesValidUriToInstance() throws Exception {
        final PlatformData data = new WaterPlatform().getData(URI.create("water://global/x"));
        assertNotNull(data, "getData must resolve a valid water:// URI to a PlatformData instance");
        assertEquals(1, data.size(), "Resolved water:// URI must produce exactly one entry");
        assertEquals(WaterPlatform.GLOBAL_SERVER + "x",
                data.entries()[0].variants()[0].uri().toString(),
                "Resolved variant must point at the mapped global URL");
    }

    // STATE: EXCEPTION — A CLAIMED BUT UNRESOLVABLE URI PROPAGATES THE FAILURE
    @Test
    void getDataThrowsForUnknownHost() {
        assertThrows(IOException.class,
                () -> new WaterPlatform().getData(URI.create("water://unknown/x")),
                "getData must throw for a water:// URI with an unknown host");
    }
}
