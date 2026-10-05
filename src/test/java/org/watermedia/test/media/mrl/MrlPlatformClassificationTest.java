package org.watermedia.test.media.mrl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
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
 * Regression coverage for platform-resolved sources typed {@link MediaType#UNKNOWN}.
 *
 * <p>A platform that cannot know the payload type (water://, MediaFire) delivers UNKNOWN and MRL
 * used to keep it verbatim — images were dispatched to FFmpeg as one-frame "videos" that ENDED
 * instantly and restarted forever on synced displays (PLAYING→ENDED→LOADING roundtrip). A
 * single-source platform result must now be classified like a direct URL, while multi-source
 * results (IPTV lists) must stay untouched so no per-channel probe storm happens.
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
        final IPlatform platform = platform(claimed, source(MediaType.UNKNOWN, opaque.toUri()));
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
        final IPlatform platform = platform(claimed, source(MediaType.UNKNOWN, target), source(MediaType.UNKNOWN, target));
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

    private static DataSource source(final MediaType type, final URI target) {
        return new DataSource(type, null, null, RequestHeaders.defaults(target),
                List.of(new DataQuality(target, 0, 0)), null, null);
    }

    // MINIMAL HANDLER CLAIMING EXACTLY ONE URI, LIKE WaterPlatform DOES FOR water://
    private static IPlatform platform(final URI claimed, final DataSource... entries) {
        return new IPlatform() {
            @Override
            public String name() {
                return "UnknownType Test";
            }

            @Override
            public PlatformData data(final URI uri) {
                return claimed.equals(uri) ? new PlatformData(null, List.of(entries)) : null;
            }
        };
    }
}
