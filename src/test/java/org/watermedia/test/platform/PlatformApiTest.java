package org.watermedia.test.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 *   <li>propagate {@link IOException} as-is and wrap any other exception.</li>
 * </ul>
 *
 * <p>Each test tracks the {@link IPlatform} instances it registers so
 * {@link #cleanup()} can pull only those entries out of the package-private
 * {@code PLATFORMS} list — the test never clears the list because other tests
 * or framework startup may have placed handlers there.
 */
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

    // STATE: INSTANCE — A PLATFORM THAT CLAIMS AND RESOLVES THE URI
    @Test
    void fetchReturnsDataFromMatchingPlatform() throws Exception {
        final PlatformData data = marker("https://example.com/a.mp4");
        this.register(uri -> "match.test".equals(uri.getHost()) ? data : null);

        final PlatformData result = PlatformAPI.fetch(URI.create("https://match.test/x"));
        assertNotNull(result, "Expected fetch to return data for the matching URI");
        assertSame(data, result, "fetch should return the stub's PlatformData instance");
    }

    // STATE: NULL — PLATFORMS THAT RETURN NULL ARE SKIPPED, NEXT CLAIMANT WINS
    @Test
    void fetchSkipsPlatformsThatReturnNull() throws Exception {
        final PlatformData claimed = marker("https://claimed.example/");
        // REGISTERED FIRST → CHECKED LAST; CLAIMS THE URI
        this.register(uri -> claimed);
        // REGISTERED LAST → CHECKED FIRST; DISCLAIMS (NULL) SO THE PROBE CONTINUES
        this.register(uri -> null);

        final PlatformData result = PlatformAPI.fetch(URI.create("https://anything.test/"));
        assertSame(claimed, result, "fetch must skip null-returning platforms and use the next claimant");
    }

    @Test
    void lastRegisteredWinsOverPrior() throws Exception {
        final PlatformData markerA = marker("https://a.example/");
        final PlatformData markerB = marker("https://b.example/");

        this.register(uri -> markerA);
        this.register(uri -> markerB);

        // REVERSE ITERATION: STUB B IS CHECKED FIRST AND WINS
        final PlatformData result = PlatformAPI.fetch(URI.create("https://anything.test/"));
        assertSame(markerB, result, "Last-registered platform must win");
    }

    @Test
    void fetchReturnsNullWhenNoPlatformClaims() throws Exception {
        this.register(uri -> "only.this".equals(uri.getHost()) ? marker("https://x/") : null);

        assertNull(PlatformAPI.fetch(URI.create("https://nobody.handles.me/")),
                "fetch must return null when no platform claims the URI");
    }

    // STATE: EXCEPTION — IOException IS PROPAGATED UNCHANGED
    @Test
    void fetchPropagatesIOException() throws Exception {
        final IOException boom = new IOException("resolve failed");
        this.register(uri -> { throw boom; });

        final IOException thrown = assertThrows(IOException.class,
                () -> PlatformAPI.fetch(URI.create("https://anything.test/")));
        assertSame(boom, thrown, "IOException from a platform must be propagated as-is");
    }

    // STATE: EXCEPTION — NON-IO EXCEPTIONS ARE WRAPPED IN IOException
    @Test
    void fetchWrapsNonIOException() throws Exception {
        final RuntimeException boom = new IllegalStateException("parse blew up");
        this.register(uri -> { throw boom; });

        final IOException thrown = assertThrows(IOException.class,
                () -> PlatformAPI.fetch(URI.create("https://anything.test/")));
        assertSame(boom, thrown.getCause(), "Non-IO exceptions must be wrapped with the original as cause");
    }
}
