package org.watermedia.test.tools;

import org.junit.jupiter.api.Test;
import org.watermedia.tools.M3UTool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link M3UTool} discriminates IPTV M3U from HLS and extracts channel
 * entries with their {@code tvg-*} attributes intact.
 */
public class M3UToolTest {

    private static final String IPTV_SAMPLE =
            "#EXTM3U x-tvg-url=\"https://epg.example/epg.xml.gz\"\n" +
            "#EXTINF:-1 tvg-name=\"Channel One\" tvg-logo=\"https://logo.example/one.png\" tvg-id=\"One.us\" tvg-country=\"US\" group-title=\"USA\",Channel One\n" +
            "https://stream.example/one/playlist.m3u8\n" +
            "#EXTINF:-1 tvg-name=\"Channel Two\" tvg-logo=\"https://logo.example/two.png\" group-title=\"USA\",Channel Two\n" +
            "https://stream.example/two/playlist.m3u8\n";

    private static final String HLS_MASTER_SAMPLE =
            "#EXTM3U\n" +
            "#EXT-X-VERSION:6\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720\n" +
            "720p.m3u8\n";

    private static final String HLS_MEDIA_SAMPLE =
            "#EXTM3U\n" +
            "#EXT-X-VERSION:3\n" +
            "#EXT-X-TARGETDURATION:6\n" +
            "#EXTINF:5.005,\n" +
            "segment-0.ts\n";

    @Test
    public void isIptvAcceptsFlatExtinfPlaylist() {
        assertTrue(M3UTool.isIptv(IPTV_SAMPLE));
    }

    @Test
    public void isIptvRejectsHlsMaster() {
        assertFalse(M3UTool.isIptv(HLS_MASTER_SAMPLE));
    }

    @Test
    public void isIptvRejectsHlsMedia() {
        assertFalse(M3UTool.isIptv(HLS_MEDIA_SAMPLE));
    }

    @Test
    public void isIptvRejectsNonPlaylistText() {
        assertFalse(M3UTool.isIptv(""));
        assertFalse(M3UTool.isIptv(null));
        assertFalse(M3UTool.isIptv("hello world"));
    }

    @Test
    public void parseExtractsChannelEntriesWithAttributes() {
        final List<M3UTool.Entry> entries = M3UTool.parse(IPTV_SAMPLE);
        assertEquals(2, entries.size());

        final M3UTool.Entry first = entries.get(0);
        assertEquals("Channel One", first.title());
        assertEquals("https://stream.example/one/playlist.m3u8", first.url());
        assertEquals("https://logo.example/one.png", first.tvgLogo());
        assertEquals("One.us", first.tvgId());
        assertEquals("US", first.tvgCountry());
        assertEquals("USA", first.tvgGroup());

        final M3UTool.Entry second = entries.get(1);
        assertEquals("Channel Two", second.title());
        assertNull(second.tvgId());
        assertEquals("USA", second.tvgGroup());
    }

    @Test
    public void parseToleratesBareUrlWithoutExtinf() {
        final List<M3UTool.Entry> entries = M3UTool.parse("#EXTM3U\nhttps://only-the-url.example/stream\n");
        assertEquals(1, entries.size());
        final M3UTool.Entry e = entries.get(0);
        assertNotNull(e);
        assertEquals("https://only-the-url.example/stream", e.url());
        assertEquals("https://only-the-url.example/stream", e.title());
    }

    @Test
    public void parseSkipsKodiAndVlcOptLines() {
        final String src =
                "#EXTM3U\n" +
                "#EXTINF:-1 tvg-name=\"X\",X\n" +
                "#KODIPROP:inputstream.adaptive.license_type=clearkey\n" +
                "#EXTVLCOPT:http-user-agent=foo\n" +
                "https://x.example/x\n";
        final List<M3UTool.Entry> entries = M3UTool.parse(src);
        assertEquals(1, entries.size());
        assertEquals("https://x.example/x", entries.get(0).url());
    }
}
