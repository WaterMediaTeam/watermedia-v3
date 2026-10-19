package org.watermedia.api.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link MRL} classifies platform sources typed {@link MediaType#UNKNOWN} (water://,
 * MediaFire): a single source is byte-sniffed like a direct URL, so an image never reaches FFmpeg as
 * a one-frame video, while multi-source results such as IPTV lists stay UNKNOWN and are never probed.
 */
@DisplayName("MRL platform source classification")
public class MrlPlatformClassificationTest {
    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    private static final long TIMEOUT_MS = 5000L;

    // OPAQUE NAME WITHOUT EXTENSION — MIRRORS A NETWORK-SERVER ID URL WITH AN AMBIGUOUS CONTENT
    // TYPE, SO ONLY THE LEADING-BYTE SNIFF CAN CLASSIFY IT. COPIED ONCE INTO build/ INSTEAD OF A
    // PER-TEST @TempDir: WINDOWS DELETE/REPLACE OF A JUST-WRITTEN FILE FLAKES AGAINST AV SCANS.
    private static Path opaque;

    @BeforeAll
    static void copyOpaqueFixture() throws IOException {
        opaque = Path.of("build", "test-sniff", "8DuZ3dat");
        Files.createDirectories(opaque.getParent());
        Files.copy(Fixtures.PNG_BROKEN, opaque, StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @DisplayName("Single UNKNOWN platform source is byte-sniffed to IMAGE")
    void testSingleUnknownSourceSniffedToImage() {
        final URI claimed = URI.create("watertest://remote/8DuZ3dat");
        final Platform platform = platform(claimed, source(MediaType.UNKNOWN, opaque.toUri()));
        PlatformAPI.register(platform);
        try {
            final MRL mrl = MediaAPI.mrl(claimed);
            assertTrue(mrl.await(TIMEOUT_MS));
            assertEquals(1, mrl.sourceCount());
            final MRL.Source source = mrl.source(0);
            assertNotNull(source);
            assertEquals(MediaType.IMAGE, source.type());
        } finally {
            PlatformAPI.unregister(platform);
        }
    }

    @Test
    @DisplayName("Multi-source UNKNOWN data is exempt from probing")
    void testMultiSourceUnknownStaysUntouched() {
        // THE TARGET IS A REAL SNIFFABLE IMAGE: IF A PROBE EVER RAN, THE TYPE WOULD FLIP TO IMAGE
        final URI claimed = URI.create("watertest://iptv/list");
        final URI target = opaque.toUri();
        final Platform platform = platform(claimed, source(MediaType.UNKNOWN, target), source(MediaType.UNKNOWN, target));
        PlatformAPI.register(platform);
        try {
            final MRL mrl = MediaAPI.mrl(claimed);
            assertTrue(mrl.await(TIMEOUT_MS));
            assertEquals(2, mrl.sourceCount());
            final MRL.Source first = mrl.source(0);
            final MRL.Source second = mrl.source(1);
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(MediaType.UNKNOWN, first.type());
            assertEquals(MediaType.UNKNOWN, second.type());
        } finally {
            PlatformAPI.unregister(platform);
        }
    }

    private static Source source(final MediaType type, final URI target) {
        return new Source(type, null, null, HttpHeaders.defaults(target),
                List.of(new Quality(target, 0, 0)), null, null);
    }

    // MINIMAL HANDLER CLAIMING EXACTLY ONE URI, LIKE WaterPlatform DOES FOR water://
    private static Platform platform(final URI claimed, final Source... entries) {
        return new Platform() {
            @Override
            public String name() {
                return "UnknownType Test";
            }

            @Override
            public Resolution resolve(final URI uri) {
                return claimed.equals(uri) ? new Resolution(null, List.of(entries)) : null;
            }
        };
    }
}
