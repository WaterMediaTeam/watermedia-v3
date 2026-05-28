package org.watermedia.test.platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.platform.internal.WaterPlatform;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link WaterPlatform#toHttpURL(URI)} mapping for the three
 * supported hosts ({@code local}, {@code global}, {@code remote}) and the
 * unknown-host failure mode.
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
}
