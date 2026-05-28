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

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the {@link PlatformAPI} registry contract: registered platforms are
 * consulted in reverse order (last registered wins) and an unmatched URI yields
 * {@code null}.
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

    @Test
    void fetchReturnsDataFromMatchingPlatform() throws Exception {
        final DataSource entry = new DataSource(MediaType.VIDEO, null, null,
                new RequestHeaders(),
                new DataQuality[] { new DataQuality(URI.create("https://example.com/a.mp4"), 0, 0) },
                null, null);
        final PlatformData marker = new PlatformData(null, entry);

        final IPlatform stub = new IPlatform() {
            @Override public String name() { return "stub-match"; }
            @Override public boolean validate(final URI uri) { return "match.test".equals(uri.getHost()); }
            @Override public PlatformData getData(final URI uri) { return marker; }
        };
        PlatformAPI.register(stub);
        this.registered.add(stub);

        final PlatformData result = PlatformAPI.fetch(URI.create("https://match.test/x"));
        assertNotNull(result, "Expected fetch to return data for the matching URI");
        assertSame(marker, result, "fetch should return the stub's PlatformData instance");
    }

    @Test
    void lastRegisteredWinsOverPrior() throws Exception {
        final DataSource entryA = new DataSource(MediaType.VIDEO, null, null,
                new RequestHeaders(),
                new DataQuality[] { new DataQuality(URI.create("https://a.example/"), 0, 0) },
                null, null);
        final PlatformData markerA = new PlatformData(null, entryA);

        final DataSource entryB = new DataSource(MediaType.VIDEO, null, null,
                new RequestHeaders(),
                new DataQuality[] { new DataQuality(URI.create("https://b.example/"), 0, 0) },
                null, null);
        final PlatformData markerB = new PlatformData(null, entryB);

        final IPlatform stubA = new IPlatform() {
            @Override public String name() { return "stub-A"; }
            @Override public boolean validate(final URI uri) { return true; }
            @Override public PlatformData getData(final URI uri) { return markerA; }
        };
        final IPlatform stubB = new IPlatform() {
            @Override public String name() { return "stub-B"; }
            @Override public boolean validate(final URI uri) { return true; }
            @Override public PlatformData getData(final URI uri) { return markerB; }
        };
        PlatformAPI.register(stubA);
        this.registered.add(stubA);
        PlatformAPI.register(stubB);
        this.registered.add(stubB);

        // REVERSE ITERATION: STUB B IS CHECKED FIRST AND WINS
        final PlatformData result = PlatformAPI.fetch(URI.create("https://anything.test/"));
        assertSame(markerB, result, "Last-registered platform must win");
    }

    @Test
    void fetchReturnsNullWhenNoPlatformValidates() throws Exception {
        final DataSource entry = new DataSource(MediaType.VIDEO, null, null,
                new RequestHeaders(),
                new DataQuality[] { new DataQuality(URI.create("https://x/"), 0, 0) },
                null, null);
        final PlatformData marker = new PlatformData(null, entry);

        final IPlatform picky = new IPlatform() {
            @Override public String name() { return "stub-picky"; }
            @Override public boolean validate(final URI uri) { return "only.this".equals(uri.getHost()); }
            @Override public PlatformData getData(final URI uri) { return marker; }
        };
        PlatformAPI.register(picky);
        this.registered.add(picky);

        assertNull(PlatformAPI.fetch(URI.create("https://nobody.handles.me/")),
                "fetch must return null when no platform validates the URI");
    }
}
