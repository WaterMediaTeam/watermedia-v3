package org.watermedia.test.media.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.players.util.NetworkCache;
import org.watermedia.api.util.PixelFormat;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Codec-tier behaviour of {@link NetworkCache}. Without a native BC codec the cache must resolve to
 * {@link NetworkCache.Mode#DISK} and keep every codec entry point inert — even when the codec cache
 * is enabled in config — so the disk path is never disturbed.
 */
@DisplayName("NetworkCache codec mode")
public class NetworkCacheCodecTest {

    private static final String ACCEPT = "image/*,*/*";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Defaults to disk mode with codec entry points inert")
    void testDefaultsToDiskMode() throws Exception {
        NetworkCache.start(this.tempDir.resolve("cache"));
        try {
            final URI uri = URI.create("http://example.test/anim.gif");
            assertEquals(NetworkCache.Mode.DISK, NetworkCache.mode());
            assertFalse(NetworkCache.codecEnabled());
            assertNull(NetworkCache.openCodecWriter(uri, null, ACCEPT, 32, 32, PixelFormat.BGRA));
            assertFalse(NetworkCache.codecReadable(uri, null, ACCEPT));
            assertNull(NetworkCache.openCodecReader(uri, null, ACCEPT));
        } finally {
            NetworkCache.release();
        }
    }

    @Test
    @DisplayName("Stays in disk mode when codec cache is enabled but no BC codec exists")
    void testStaysDiskWithoutBc() throws Exception {
        final boolean previous = WaterMediaConfig.media.txCodecCache;
        WaterMediaConfig.media.txCodecCache = true;
        try {
            NetworkCache.start(this.tempDir.resolve("cache-enabled"));
            try {
                // CONFIG ASKS FOR CODEC MODE, BUT NO NATIVE BC CODEC IS PRESENT -> DISK.
                assertEquals(NetworkCache.Mode.DISK, NetworkCache.mode());
                assertFalse(NetworkCache.codecEnabled());
            } finally {
                NetworkCache.release();
            }
        } finally {
            WaterMediaConfig.media.txCodecCache = previous;
        }
    }
}
