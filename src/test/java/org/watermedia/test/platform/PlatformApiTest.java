package org.watermedia.test.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link PlatformAPI} registry contract against the new
 * {@link IPlatform#getData(URI)} protocol, where a single call both claims and
 * resolves a URI. {@link PlatformAPI#fetch(URI)} must:
 * <ul>
 *   <li>skip platforms that return {@code null} (the URI is not theirs);</li>
 *   <li>return the first non-null {@link PlatformData} (reverse-registration);</li>
 *   <li>wrap a platform's exception ({@link IOException} or any other) in a {@link PlatformException}, preserving the cause.</li>
 * </ul>
 *
 * <p>Each test tracks the {@link IPlatform} instances it registers so
 * {@link #cleanup()} can pull only those entries out of the package-private
 * {@code PLATFORMS} list — the test never clears the list because other tests
 * or framework startup may have placed handlers there.
 */
@DisplayName("PlatformAPI registry")
public class PlatformApiTest {

    // STUB PLATFORMS REGISTERED BY THE CURRENT TEST — REMOVED IN @AfterEach
    private final List<IPlatform> registered = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        // PLATFORMS IS PACKAGE-PRIVATE; CROSS-PACKAGE TESTS REACH IT VIA REFLECTION
        final Field f = PlatformAPI.class.getDeclaredField("PLATFORMS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        final CopyOnWriteArrayList<IPlatform> platforms = (CopyOnWriteArrayList<IPlatform>) f.get(null);
        for (final IPlatform p: this.registered) platforms.remove(p);
        this.registered.clear();
    }

    // RESOLVER SUPPLIES getData; name() IS FIXED. KEEPS THE STUBS A ONE-LINER EACH.
    @FunctionalInterface
    private interface Resolver {
        PlatformData getData(URI uri) throws Exception;
    }

    private void register(final Resolver resolver) {
        final IPlatform platform = new IPlatform() {
            @Override public String name() { return "stub"; }
            @Override public PlatformData getData(final URI uri) throws Exception { return resolver.getData(uri); }
        };
        PlatformAPI.register(platform);
        this.registered.add(platform);
    }

    private static PlatformData marker(final String url) {
        final DataSource entry = new DataSource(MediaType.VIDEO, null, null,
                new RequestHeaders(),
                new DataQuality[] { new DataQuality(URI.create(url), 0, 0) },
                null, null);
        return new PlatformData(null, entry);
    }

    // ==========================================================================
    // CLAIM RESOLUTION TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Claim Resolution")
    class ClaimTests {

        // STATE: INSTANCE — A PLATFORM THAT CLAIMS AND RESOLVES THE URI
        @Test
        @DisplayName("fetch returns data from the matching platform")
        void testFetchReturnsDataFromMatchingPlatform() throws Exception {
            final PlatformData data = marker("https://example.com/a.mp4");
            PlatformApiTest.this.register(uri -> "match.test".equals(uri.getHost()) ? data : null);

            final PlatformData result = PlatformAPI.fetch(URI.create("https://match.test/x"));
            assertNotNull(result, "Expected fetch to return data for the matching URI");
            assertSame(data, result, "fetch should return the stub's PlatformData instance");
        }

        // STATE: NULL — PLATFORMS THAT RETURN NULL ARE SKIPPED, NEXT CLAIMANT WINS
        @Test
        @DisplayName("fetch skips platforms that return null")
        void testFetchSkipsPlatformsThatReturnNull() throws Exception {
            final PlatformData claimed = marker("https://claimed.example/");
            // REGISTERED FIRST → CHECKED LAST; CLAIMS THE URI
            PlatformApiTest.this.register(uri -> claimed);
            // REGISTERED LAST → CHECKED FIRST; DISCLAIMS (NULL) SO THE PROBE CONTINUES
            PlatformApiTest.this.register(uri -> null);

            final PlatformData result = PlatformAPI.fetch(URI.create("https://anything.test/"));
            assertSame(claimed, result, "fetch must skip null-returning platforms and use the next claimant");
        }

        @Test
        @DisplayName("Last-registered platform wins over prior registrations")
        void testLastRegisteredWinsOverPrior() throws Exception {
            final PlatformData markerA = marker("https://a.example/");
            final PlatformData markerB = marker("https://b.example/");

            PlatformApiTest.this.register(uri -> markerA);
            PlatformApiTest.this.register(uri -> markerB);

            // REVERSE ITERATION: STUB B IS CHECKED FIRST AND WINS
            final PlatformData result = PlatformAPI.fetch(URI.create("https://anything.test/"));
            assertSame(markerB, result, "Last-registered platform must win");
        }

        @Test
        @DisplayName("fetch returns null when no platform claims the URI")
        void testFetchReturnsNullWhenNoPlatformClaims() throws Exception {
            PlatformApiTest.this.register(uri -> "only.this".equals(uri.getHost()) ? marker("https://x/") : null);

            assertNull(PlatformAPI.fetch(URI.create("https://nobody.handles.me/")),
                    "fetch must return null when no platform claims the URI");
        }
    }

    // ==========================================================================
    // EXCEPTION PROPAGATION TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Exception Propagation")
    class ExceptionTests {

        // STATE: EXCEPTION — A PLATFORM IOException IS WRAPPED IN A PlatformException (CAUSE PRESERVED)
        @Test
        @DisplayName("IOException from a platform is wrapped in PlatformException (cause preserved)")
        void testFetchWrapsIOException() {
            final IOException boom = new IOException("resolve failed");
            PlatformApiTest.this.register(uri -> { throw boom; });

            final IOException thrown = assertThrows(IOException.class,
                    () -> PlatformAPI.fetch(URI.create("https://anything.test/")));
            assertInstanceOf(PlatformException.class, thrown);
            assertSame(boom, thrown.getCause(), "The platform's IOException must be preserved as the cause");
        }

        // STATE: EXCEPTION — NON-IO EXCEPTIONS ARE WRAPPED IN IOException
        @Test
        @DisplayName("Non-IO exceptions are wrapped in IOException")
        void testFetchWrapsNonIOException() {
            final RuntimeException boom = new IllegalStateException("parse blew up");
            PlatformApiTest.this.register(uri -> { throw boom; });

            final IOException thrown = assertThrows(IOException.class,
                    () -> PlatformAPI.fetch(URI.create("https://anything.test/")));
            assertSame(boom, thrown.getCause(), "Non-IO exceptions must be wrapped with the original as cause");
        }
    }
}
