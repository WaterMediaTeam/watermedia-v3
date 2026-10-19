package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.LogCapture;
import org.watermedia.tools.M3UTool.Channel;
import org.watermedia.tools.M3UTool.Iptv;
import org.watermedia.tools.M3UTool.Master;
import org.watermedia.tools.M3UTool.Media;
import org.watermedia.tools.M3UTool.Playlist;
import org.watermedia.tools.M3UTool.Rendition;
import org.watermedia.tools.M3UTool.Segment;
import org.watermedia.tools.M3UTool.Variant;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link M3UTool}, the unified M3U/M3U8 playlist parser: HLS master and media
 * playlists, IPTV channel lists, error handling and real-world edge cases.
 *
 * <p>Classification is single-entry through {@link M3UTool#parse(String, URI)}:
 * {@code #EXT-X-STREAM-INF} → {@link Master}, any other {@code #EXT-X-} tag →
 * {@link Media}, a tag-less {@code #EXTINF} list → {@link Iptv}, and anything else
 * throws {@link IOException}. Every entry URL ({@link Variant}, {@link Segment},
 * {@link Rendition}, {@link Channel}) is resolved against the source while parsing,
 * so callers always receive absolute {@link URI}s.
 */
@DisplayName("M3UTool")
public class M3UToolTest {
    // ENTRIES ONLY SURVIVE WHEN THEY RESOLVE TO http OR https, SO EVERY SOURCE LIVES ON A WEB ORIGIN
    private static final String BASE = "https://cdn.test/";

    // ==========================================================================
    // ERROR HANDLING TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Null content throws IOException")
        void testNullContent() {
            final IOException error = assertThrows(IOException.class,
                    () -> M3UTool.parse((String) null, URI.create(BASE + "test.m3u8")));
            assertTrue(error.getMessage().contains("Empty playlist"));
        }

        @Test
        @DisplayName("Empty content throws IOException")
        void testEmptyContent() {
            final IOException error = assertThrows(IOException.class,
                    () -> M3UTool.parse("", URI.create(BASE + "test.m3u8")));
            assertTrue(error.getMessage().contains("Empty playlist"));
        }

        @Test
        @DisplayName("Blank content throws IOException")
        void testBlankContent() {
            final IOException error = assertThrows(IOException.class,
                    () -> M3UTool.parse("   \n\t  ", URI.create(BASE + "test.m3u8")));
            assertTrue(error.getMessage().contains("Empty playlist"));
        }

        @Test
        @DisplayName("Missing #EXTM3U header throws IOException")
        void testMissingHeader() {
            final String content = """
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                """;

            final IOException error = assertThrows(IOException.class,
                    () -> M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertTrue(error.getMessage().contains("#EXTM3U"));
        }

        @Test
        @DisplayName("Unknown playlist type throws IOException")
        void testUnknownPlaylistType() {
            // #EXTM3U present but NO #EXT-X-STREAM-INF, NO #EXT-X-* tag and NO #EXTINF:
            final String content = """
                #EXTM3U
                # Just some comments, no actual content
                """;

            final IOException error = assertThrows(IOException.class,
                    () -> M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertTrue(error.getMessage().contains("Unrecognized"));
        }

        @Test
        @DisplayName("Non-playlist text throws IOException")
        void testNonPlaylistText() {
            assertThrows(IOException.class, () -> M3UTool.parse("", URI.create(BASE + "src")));
            assertThrows(IOException.class, () -> M3UTool.parse((String) null, URI.create(BASE + "src")));
            assertThrows(IOException.class, () -> M3UTool.parse("hello world", URI.create(BASE + "src")));
        }
    }

    // ==========================================================================
    // MASTER PLAYLIST TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Master Playlist Parsing")
    class MasterPlaylistTests {

        @Test
        @DisplayName("Parse basic master playlist")
        void testBasicMasterPlaylist() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                720p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
                480p.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "master.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;

            assertEquals(URI.create(BASE + "master.m3u8"), master.source());
            assertEquals("master", master.kind());
            assertEquals(1, master.version()); // DEFAULT VERSION
            assertEquals(2, master.variants().size());
        }

        @Test
        @DisplayName("Parse version from master playlist")
        void testMasterVersion() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:6
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            assertEquals(6, master.version());
        }

        @Test
        @DisplayName("Parse variant bandwidth and resolution")
        void testVariantBandwidthResolution() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=9014525,RESOLUTION=1920x1080
                1080p.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            assertEquals(1, master.variants().size());

            final Variant variant = master.variants().get(0);
            assertEquals(9014525L, variant.bandwidth());
            assertEquals(1920, variant.width());
            assertEquals(1080, variant.height());
            assertEquals(URI.create(BASE + "1080p.m3u8"), variant.uri());
        }

        @Test
        @DisplayName("Parse variant with codecs and frame rate")
        void testVariantCodecsFrameRate() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",FRAME-RATE=60.000
                stream.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            final Variant variant = master.variants().get(0);

            assertEquals("avc1.64002A,mp4a.40.2", variant.codecs());
            assertEquals(60.0, variant.fps(), 0.001);
        }

        @Test
        @DisplayName("Parse media renditions")
        void testMediaRenditions() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio_en.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",LANGUAGE="es",DEFAULT=NO,AUTOSELECT=YES,URI="audio_es.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="audio"
                video.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            assertEquals(2, master.renditions().size());

            final Rendition en = master.renditions().get(0);
            assertEquals("AUDIO", en.type());
            assertEquals("audio", en.groupId());
            assertEquals("English", en.name());
            assertEquals("en", en.language());
            assertEquals(URI.create(BASE + "audio_en.m3u8"), en.uri());
            assertTrue(en.preferred());
            assertTrue(en.autoSelect());

            final Rendition es = master.renditions().get(1);
            assertEquals("Spanish", es.name());
            assertEquals("es", es.language());
            assertFalse(es.preferred());
            assertTrue(es.autoSelect());
        }

        @Test
        @DisplayName("Parse video group with name")
        void testVideoGroupName() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (uri)",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,VIDEO="chunked"
                chunked.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            final Variant variant = master.variants().get(0);

            assertEquals("chunked", variant.videoGroup());
            assertEquals("1080p60 (uri)", variant.name());
        }
    }

    // ==========================================================================
    // MEDIA PLAYLIST TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Media Playlist Parsing")
    class MediaPlaylistTests {

        @Test
        @DisplayName("Parse basic media playlist")
        void testBasicMediaPlaylist() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                segment2.ts
                #EXT-X-ENDLIST
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "playlist.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals(URI.create(BASE + "playlist.m3u8"), media.source());
            assertEquals("media", media.kind());
            assertEquals(3, media.version());
            assertEquals(6.0, media.targetDuration(), 0.001);
            assertEquals(2, media.segments().size());
            assertFalse(media.live());
            assertFalse(media.vod());
        }

        @Test
        @DisplayName("Parse media sequence")
        void testMediaSequence() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:1074
                #EXTINF:4.0,
                segment1074.ts
                #EXTINF:4.0,
                segment1075.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals(1074L, media.sequence());
            assertEquals(1074L, media.segments().get(0).sequence());
            assertEquals(1075L, media.segments().get(1).sequence());
        }

        @Test
        @DisplayName("Parse segment duration and title")
        void testSegmentDurationTitle() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:10
                #EXTINF:4.167,live segment
                segment1.ts
                #EXTINF:4.166,
                segment2.ts
                #EXT-X-ENDLIST
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            final Segment seg1 = media.segments().get(0);
            assertEquals(4.167, seg1.duration(), 0.001);
            assertEquals("live segment", seg1.title());
            assertEquals(URI.create(BASE + "segment1.ts"), seg1.uri());

            final Segment seg2 = media.segments().get(1);
            assertEquals(4.166, seg2.duration(), 0.001);
            assertEquals("", seg2.title());
        }

        @Test
        @DisplayName("Parse program date time")
        void testProgramDateTime() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:56:19.451Z
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                segment2.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals("2025-12-16T10:56:19.451Z", media.segments().get(0).dateTime());
            assertNull(media.segments().get(1).dateTime()); // ONLY FIRST SEGMENT HAS dateTime
        }

        @Test
        @DisplayName("Detect live stream (no ENDLIST)")
        void testLiveStream() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                segment2.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertTrue(media.live());
        }

        @Test
        @DisplayName("Detect VOD stream")
        void testVodStream() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:4.0,
                segment1.ts
                #EXT-X-ENDLIST
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertTrue(media.vod());
            assertFalse(media.live());
        }

        @Test
        @DisplayName("Calculate total duration")
        void testTotalDuration() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.167,
                segment1.ts
                #EXTINF:4.166,
                segment2.ts
                #EXTINF:4.167,
                segment3.ts
                #EXT-X-ENDLIST
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals(12.5, media.totalDuration(), 0.001);
        }

        @Test
        @DisplayName("Parse EXTINF without comma")
        void testExtinfWithoutComma() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.5
                segment.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals(1, media.segments().size());
            assertEquals(4.5, media.segments().get(0).duration(), 0.001);
        }
    }

    // ==========================================================================
    // IPTV PLAYLIST TESTS
    // ==========================================================================

    @Nested
    @DisplayName("IPTV Playlist Parsing")
    class IptvPlaylistTests {

        private static final String IPTV_SAMPLE =
                "#EXTM3U x-tvg-url=\"https://epg.example/epg.xml.gz\"\n" +
                "#EXTINF:-1 tvg-name=\"Channel One\" tvg-logo=\"https://logo.example/one.png\" tvg-id=\"One.us\" tvg-country=\"US\" group-title=\"USA\",Channel One\n" +
                "https://stream.example/one/playlist.m3u8\n" +
                "#EXTINF:-1 tvg-name=\"Channel Two\" tvg-logo=\"https://logo.example/two.png\" group-title=\"USA\",Channel Two\n" +
                "https://stream.example/two/playlist.m3u8\n";

        @Test
        @DisplayName("Flat #EXTINF list (no #EXT-X-* tags) is classified as IPTV")
        void testFlatExtinfClassifiedAsIptv() throws IOException {
            assertInstanceOf(Iptv.class, M3UTool.parse(IPTV_SAMPLE, URI.create(BASE + "iptv.m3u")));
        }

        @Test
        @DisplayName("HLS master is NOT classified as IPTV")
        void testHlsMasterNotIptv() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:6
                #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720
                720p.m3u8
                """;
            assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "src")));
        }

        @Test
        @DisplayName("HLS media is NOT classified as IPTV")
        void testHlsMediaNotIptv() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:5.005,
                segment-0.ts
                """;
            assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "src")));
        }

        @Test
        @DisplayName("Extract channels with their tvg-* attributes")
        void testExtractsChannelsWithAttributes() throws IOException {
            final Playlist result = M3UTool.parse(IPTV_SAMPLE, URI.create(BASE + "iptv.m3u"));

            assertInstanceOf(Iptv.class, result);
            final List<Channel> channels = ((Iptv) result).channels();
            assertEquals(2, channels.size());

            final Channel first = channels.get(0);
            assertEquals("Channel One", first.title());
            assertEquals(URI.create("https://stream.example/one/playlist.m3u8"), first.uri());
            assertEquals("https://logo.example/one.png", first.tvgLogo());
            assertEquals("One.us", first.tvgId());
            assertEquals("US", first.tvgCountry());
            assertEquals("USA", first.tvgGroup());

            final Channel second = channels.get(1);
            assertEquals("Channel Two", second.title());
            assertNull(second.tvgId());
            assertEquals("USA", second.tvgGroup());
        }

        @Test
        @DisplayName("Tolerate a bare URL line inside an IPTV list")
        void testToleratesBareUrlInList() throws IOException {
            final String src =
                    "#EXTM3U\n" +
                    "#EXTINF:-1 tvg-name=\"X\",X\n" +
                    "https://x.example/x\n" +
                    "https://bare.example/y\n";

            final Playlist result = M3UTool.parse(src, URI.create(BASE + "iptv.m3u"));
            assertInstanceOf(Iptv.class, result);
            final List<Channel> channels = ((Iptv) result).channels();

            assertEquals(2, channels.size());
            final Channel bare = channels.get(1);
            assertNotNull(bare);
            assertEquals(URI.create("https://bare.example/y"), bare.uri());
            assertEquals("https://bare.example/y", bare.title()); // SYNTHESISED FROM URL
        }

        @Test
        @DisplayName("Skip #KODIPROP and #EXTVLCOPT hint lines")
        void testSkipsKodiAndVlcOptLines() throws IOException {
            final String src =
                    "#EXTM3U\n" +
                    "#EXTINF:-1 tvg-name=\"X\",X\n" +
                    "#KODIPROP:inputstream.adaptive.license_type=clearkey\n" +
                    "#EXTVLCOPT:http-user-agent=foo\n" +
                    "https://x.example/x\n";

            final Playlist result = M3UTool.parse(src, URI.create(BASE + "iptv.m3u"));
            assertInstanceOf(Iptv.class, result);
            final List<Channel> channels = ((Iptv) result).channels();
            assertEquals(1, channels.size());
            assertEquals(URI.create("https://x.example/x"), channels.get(0).uri());
        }
    }

    // ==========================================================================
    // INPUT STREAM PARSING TESTS
    // ==========================================================================

    @Nested
    @DisplayName("InputStream Parsing")
    class InputStreamTests {
    }

    // ==========================================================================
    // EDGE CASE TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Handle whitespace around header")
        void testWhitespaceAroundHeader() throws IOException {
            final String content = """
                   #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
        }

        @Test
        @DisplayName("Handle empty variant list")
        void testEmptyVariantList() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            assertEquals(0, master.variants().size()); // NO URI FOLLOWS THE STREAM-INF
        }

        @Test
        @DisplayName("Handle full URLs in segments")
        void testFullUrlSegments() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                https://cdn.example.com/stream/segment1.ts
                #EXTINF:4.0,
                https://cdn.example.com/stream/segment2.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals(URI.create("https://cdn.example.com/stream/segment1.ts"), media.segments().get(0).uri());
        }

        @Test
        @DisplayName("Resolve relative segment URLs against the source")
        void testRelativeSegmentResolution() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segments/segment1.ts
                #EXTINF:4.0,
                ../other/segment2.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create("https://cdn.example.com/live/stream/playlist.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals(URI.create("https://cdn.example.com/live/stream/segments/segment1.ts"), media.segments().get(0).uri());
            assertEquals(URI.create("https://cdn.example.com/live/other/segment2.ts"), media.segments().get(1).uri());
        }

        @Test
        @DisplayName("Handle missing resolution in variant")
        void testMissingResolution() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            final Variant variant = master.variants().get(0);

            assertEquals(0, variant.width());
            assertEquals(0, variant.height());
        }

        @Test
        @DisplayName("Handle default frame rate")
        void testDefaultFrameRate() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080
                stream.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            final Variant variant = master.variants().get(0);

            assertEquals(30.0, variant.fps(), 0.001); // DEFAULT IS 30
        }

        @Test
        @DisplayName("Handle multiple PROGRAM-DATE-TIME tags")
        void testMultipleProgramDateTime() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:00:00.000Z
                #EXTINF:4.0,
                segment1.ts
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:00:04.000Z
                #EXTINF:4.0,
                segment2.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals("2025-12-16T10:00:00.000Z", media.segments().get(0).dateTime());
            assertEquals("2025-12-16T10:00:04.000Z", media.segments().get(1).dateTime());
        }

        @Test
        @DisplayName("Handle audio group in variant")
        void testAudioGroup() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",NAME="Stereo"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="stereo"
                stream.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "test.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;
            final Variant variant = master.variants().get(0);

            assertEquals("stereo", variant.audioGroup());
        }

        @Test
        @DisplayName("Complex real-world master playlist")
        void testRealWorldMaster() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-SESSION-DATA:DATA-ID="NODE",VALUE="cloudfront.hls.live-video.net"
                #EXT-X-SESSION-DATA:DATA-ID="BROADCAST-ID",VALUE="315425928548"
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=9014525,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
                https://example.com/chunked.m3u8
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000
                https://example.com/720p60.m3u8
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="480p30",NAME="480p",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=1427999,RESOLUTION=852x480,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="480p30",FRAME-RATE=30.000
                https://example.com/480p30.m3u8
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="360p30",NAME="360p",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=630000,RESOLUTION=640x360,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="360p30",FRAME-RATE=30.000
                https://example.com/360p30.m3u8
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "master.m3u8"));

            assertInstanceOf(Master.class, result);
            final Master master = (Master) result;

            // RENDITIONS
            assertEquals(4, master.renditions().size());

            // VARIANTS KEEP THE DECLARATION ORDER OF THE LADDER
            assertEquals(4, master.variants().size());
            assertEquals(9014525L, master.variants().get(0).bandwidth());
            assertEquals(URI.create("https://example.com/chunked.m3u8"), master.variants().get(0).uri());
            assertEquals(630000L, master.variants().get(3).bandwidth());
            assertEquals(URI.create("https://example.com/360p30.m3u8"), master.variants().get(3).uri());
        }

        @Test
        @DisplayName("Complex real-world media playlist")
        void testRealWorldMedia() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:1074
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:56:19.451Z
                #EXTINF:4.167,live
                https://cdn.example.com/segment1.ts
                #EXTINF:4.166,live
                https://cdn.example.com/segment2.ts
                #EXTINF:4.167,live
                https://cdn.example.com/segment3.ts
                """;

            final Playlist result = M3UTool.parse(content, URI.create(BASE + "playlist.m3u8"));

            assertInstanceOf(Media.class, result);
            final Media media = (Media) result;

            assertEquals(URI.create(BASE + "playlist.m3u8"), media.source());
            assertEquals(3, media.version());
            assertEquals(6.0, media.targetDuration(), 0.001);
            assertEquals(1074L, media.sequence());
            assertTrue(media.live()); // NO ENDLIST
            assertFalse(media.vod());
            assertEquals(12.5, media.totalDuration(), 0.001);

            // SEGMENTS
            assertEquals(3, media.segments().size());

            final Segment seg1 = media.segments().get(0);
            assertEquals(URI.create("https://cdn.example.com/segment1.ts"), seg1.uri());
            assertEquals(4.167, seg1.duration(), 0.001);
            assertEquals("live", seg1.title());
            assertEquals(1074L, seg1.sequence());
            assertEquals("2025-12-16T10:56:19.451Z", seg1.dateTime());

            final Segment seg3 = media.segments().get(2);
            assertEquals(1076L, seg3.sequence());
        }
    }

    // ==========================================================================
    // SEALED INTERFACE TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Playlist Types")
    class PlaylistTypeTests {

        @Test
        @DisplayName("Playlist sealed interface permits only Master/Media/Iptv; invalid input throws")
        void testSealedInterface() throws IOException {
            final String masterContent = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;

            final String mediaContent = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                """;

            final String iptvContent =
                    "#EXTM3U\n" +
                    "#EXTINF:-1 tvg-name=\"X\",X\n" +
                    "https://x.example/x\n";

            final Playlist master = M3UTool.parse(masterContent, URI.create(BASE + "test.m3u8"));
            final Playlist media = M3UTool.parse(mediaContent, URI.create(BASE + "test.m3u8"));
            final Playlist iptv = M3UTool.parse(iptvContent, URI.create(BASE + "test.m3u"));

            // VERIFY TYPES USING instanceof
            assertInstanceOf(Master.class, master);
            assertInstanceOf(Media.class, media);
            assertInstanceOf(Iptv.class, iptv);

            // VERIFY DISCRIMINATOR
            assertEquals("master", master.kind());
            assertEquals("media", media.kind());
            assertEquals("iptv", iptv.kind());

            // VERIFY EACH TYPE IS DISTINCT
            assertFalse(master instanceof Media);
            assertFalse(master instanceof Iptv);
            assertFalse(media instanceof Master);
            assertFalse(media instanceof Iptv);
            assertFalse(iptv instanceof Master);
            assertFalse(iptv instanceof Media);

            // INVALID INPUT IS NO LONGER A RESULT TYPE — IT THROWS
            assertThrows(IOException.class, () -> M3UTool.parse((String) null, URI.create(BASE + "test.m3u8")));
        }
    }

    // ==========================================================================
    // WEB-ONLY ENTRIES AND FETCH TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Web-only entries and fetch")
    class WebOnlyTests {

        @Test
        @DisplayName("Entries outside http and https are skipped")
        void testSkipsLocalEntries() throws IOException {
            final String iptv =
                    "#EXTM3U\n" +
                    "#EXTINF:-1,Local\n" +
                    "file:///etc/passwd\n" +
                    "#EXTINF:-1,Archive\n" +
                    "jar:file:///tmp/x.zip!/y\n" +
                    "#EXTINF:-1,Stream\n" +
                    "rtsp://cam.example/live\n" +
                    "#EXTINF:-1,Web\n" +
                    "HTTPS://x.example/x\n";
            final List<Channel> channels = ((Iptv) M3UTool.parse(iptv, URI.create(BASE + "iptv.m3u"))).channels();
            assertEquals(List.of("Stream", "Web"), channels.stream().map(Channel::title).toList());

            final String media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                file:///C:/secret.ts
                """;
            final List<Segment> segments = ((Media) M3UTool.parse(media, URI.create(BASE + "live.m3u8"))).segments();
            assertEquals(1, segments.size());
            assertEquals(URI.create(BASE + "segment1.ts"), segments.get(0).uri());

            // A LOCAL PLAYLIST MAY LIST LOCAL FILES, RELATIVE OR ABSOLUTE; AN ARCHIVE ENTRY STAYS OUT
            final List<Segment> local = ((Media) M3UTool.parse(media, URI.create("file:///C:/videos/live.m3u8"))).segments();
            assertEquals(List.of(URI.create("file:///C:/videos/segment1.ts"), URI.create("file:///C:/secret.ts")),
                    local.stream().map(Segment::uri).toList());
            assertEquals(List.of("Local", "Stream", "Web"), ((Iptv) M3UTool.parse(iptv, URI.create("file:///C:/videos/iptv.m3u"))).channels().stream()
                    .map(Channel::title).toList());
        }

        @Test
        @DisplayName("Each playlist logs only its first skipped entry")
        void testLogsFirstSkipPerPlaylist() throws IOException {
            final String iptv = "#EXTM3U\n#EXTINF:-1,Local\nfile:///etc/passwd\n#EXTINF:-1,Archive\njar:file:///tmp/x.zip!/y\n#EXTINF:-1,Web\nhttps://x.example/x\n";
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                M3UTool.parse(iptv, URI.create(BASE + "first.m3u"));
                M3UTool.parse(iptv, URI.create(BASE + "second.m3u"));
                assertEquals(2, capture.events().stream().filter(event -> event.getMessage().getFormattedMessage().startsWith("Skipping")).count());
            }
        }

        @Test
        @DisplayName("fetch sends the caller's User-Agent and Accept")
        void testFetchHonoursHeaders() throws IOException {
            final List<String> received = new CopyOnWriteArrayList<>();
            try (final LocalHttp server = LocalHttp.start("/live.m3u8", ex -> {
                received.add(ex.getRequestHeaders().getFirst("User-Agent") + "|" + ex.getRequestHeaders().getFirst("Accept"));
                LocalHttp.respond(ex, "application/vnd.apple.mpegurl",
                        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:4.0,\nsegment1.ts\n".getBytes(StandardCharsets.UTF_8), 0);
            })) {
                final HttpHeaders headers = new HttpHeaders().set("User-Agent", "custom-agent").set("Accept", "application/vnd.apple.mpegurl");
                final Playlist playlist = M3UTool.fetch(server.uri("/live.m3u8"), headers);
                assertInstanceOf(Media.class, playlist);
                M3UTool.fetch(server.uri("/live.m3u8"));
                assertEquals(List.of("custom-agent|application/vnd.apple.mpegurl", NetRequest.UserAgent.WATERMEDIA.value() + "|*/*"), received);
            }
        }
    }
}
