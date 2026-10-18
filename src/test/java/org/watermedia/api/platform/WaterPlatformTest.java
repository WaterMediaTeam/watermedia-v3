package org.watermedia.api.platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.internal.WaterPlatform;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link WaterPlatform#location(URI)} mapping for the three
 * supported hosts ({@code local}, {@code global}, {@code remote}) and the
 * unknown-host failure mode.
 * <p>
 * {@code WaterPlatform} resolves {@code water://} URIs without any network
 * access, so it also serves as the offline witness for all three
 * {@link WaterPlatform#resolve(URI)} outcomes: {@code null} (foreign URI),
 * a {@link Resolution} instance (valid URI) and a thrown exception
 * (unknown host).
 */
@DisplayName("WaterPlatform")
public class WaterPlatformTest {

    // location(local) NEEDS WaterMedia.cwd(); THE SHARED CLIENT LOAD KEEPS THE TEST JVM A CLIENT FOR THE MEDIA TESTS
    @BeforeAll
    static void initWaterMedia() { MediaBootstrap.client(); }

    // ==========================================================================
    // location MAPPING TESTS
    // ==========================================================================

    @Nested
    @DisplayName("location Mapping")
    class ToHttpUrlTests {

        @Test
        @DisplayName("water://local resolves to a file:// URI containing the path")
        void testLocalResolvesToFileUriContainingPath() throws IOException {
            final String resolved = WaterPlatform.location(URI.create("water://local/foo.png"));
            assertTrue(resolved.startsWith("file:"),
                    "water://local/... must resolve to a file:// URI, got: " + resolved);
            assertTrue(resolved.endsWith("foo.png"),
                    "Resolved URI must end with the requested path component, got: " + resolved);
        }

        @Test
        @DisplayName("water://global resolves to the global server prefix")
        void testGlobalResolvesToGlobalServerPrefix() throws IOException {
            final String resolved = WaterPlatform.location(URI.create("water://global/x"));
            assertEquals(WaterPlatform.GLOBAL_SERVER + "x", resolved,
                    "water://global/x must concatenate after WaterPlatform.GLOBAL_SERVER");
        }

        @Test
        @DisplayName("water://remote keeps the raw path against the configured remote URL")
        void testRemoteKeepsRawPath() throws IOException {
            assertEquals(NetworkAPI.remoteUrl() + "/a%20b", WaterPlatform.location(URI.create("water://remote/a%20b")),
                    "A percent-encoded path must reach the remote server unchanged");
        }

        @Test
        @DisplayName("Unknown host throws IOException mentioning the host")
        void testUnknownHostThrowsIOException() {
            final IOException ex = assertThrows(IOException.class,
                    () -> WaterPlatform.location(URI.create("water://unknown/x")),
                    "Unknown water:// host must throw IOException");
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("unknown"),
                    "IOException message should mention the unknown host, got: " + ex.getMessage());
        }
    }

    // ==========================================================================
    // resolve() CONTRACT TESTS
    // ==========================================================================

    @Nested
    @DisplayName("resolve() contract")
    class ResolveTests {

        // STATE: NULL — A NON-water:// URI IS NOT CLAIMED BY THIS PLATFORM
        @Test
        @DisplayName("resolve() returns null for a foreign URI")
        void testResolveReturnsNullForForeignUri() throws Exception {
            assertNull(new WaterPlatform().resolve(URI.create("https://example.com/x.png")),
                    "resolve() must return null for a URI that is not a water:// scheme");
        }

        // STATE: INSTANCE — A VALID water:// URI RESOLVES TO A Resolution WITHOUT NETWORK
        @Test
        @DisplayName("resolve() returns a Resolution for a valid URI")
        void testResolveResolvesValidUriToInstance() throws Exception {
            final Resolution data = new WaterPlatform().resolve(URI.create("water://global/x"));
            assertNotNull(data, "resolve() must return a Resolution for a valid water:// URI");
            assertEquals(1, data.sourceCount(), "Resolved water:// URI must produce exactly one source");
            assertEquals(WaterPlatform.GLOBAL_SERVER + "x",
                    data.sources().get(0).qualities().get(0).uri().toString(),
                    "Resolved variant must point at the mapped global URL");
        }

        // STATE: EXCEPTION — A CLAIMED BUT UNRESOLVABLE URI PROPAGATES THE FAILURE
        @Test
        @DisplayName("resolve() throws for an unknown host")
        void testResolveThrowsForUnknownHost() {
            assertThrows(IOException.class,
                    () -> new WaterPlatform().resolve(URI.create("water://unknown/x")),
                    "resolve() must throw for a water:// URI with an unknown host");
        }
    }
}
