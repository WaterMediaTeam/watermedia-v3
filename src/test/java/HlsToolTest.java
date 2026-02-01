import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.tools.HlsTool;
import org.watermedia.tools.HlsTool.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for HlsTool HLS/M3U8 playlist parser.
 * Tests cover master playlist parsing, media playlist parsing, error handling,
 * and various edge cases encountered in real-world HLS streams.
 */
public class HlsToolTest {

    // ==========================================================================
    // ERROR HANDLING TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Null content returns ErrorResult")
        void testNullContent() {
            final Result result = HlsTool.parse((String) null, "test.m3u8");

            assertInstanceOf(ErrorResult.class, result);
            final ErrorResult error = (ErrorResult) result;
            assertEquals("Content is null or empty", error.message());
            assertNull(error.cause());
        }

        @Test
        @DisplayName("Empty content returns ErrorResult")
        void testEmptyContent() {
            final Result result = HlsTool.parse("", "test.m3u8");

            assertInstanceOf(ErrorResult.class, result);
            final ErrorResult error = (ErrorResult) result;
            assertEquals("Content is null or empty", error.message());
        }

        @Test
        @DisplayName("Blank content returns ErrorResult")
        void testBlankContent() {
            final Result result = HlsTool.parse("   \n\t  ", "test.m3u8");

            assertInstanceOf(ErrorResult.class, result);
            final ErrorResult error = (ErrorResult) result;
            assertEquals("Content is null or empty", error.message());
        }

        @Test
        @DisplayName("Missing #EXTM3U header returns ErrorResult")
        void testMissingHeader() {
            final String content = """
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(ErrorResult.class, result);
            final ErrorResult error = (ErrorResult) result;
            assertEquals("Invalid M3U8: Missing #EXTM3U header", error.message());
        }

        @Test
        @DisplayName("Unknown playlist type returns ErrorResult")
        void testUnknownPlaylistType() {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:3
                # Just some comments, no actual content
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(ErrorResult.class, result);
            final ErrorResult error = (ErrorResult) result;
            assertEquals("Unknown playlist type", error.message());
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
        void testBasicMasterPlaylist() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                720p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
                480p.m3u8
                """;

            final Result result = HlsTool.parse(content, "master.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;

            assertEquals("master.m3u8", master.source());
            assertEquals(1, master.version()); // Default version
            assertEquals(2, master.variants().size());
        }

        @Test
        @DisplayName("Parse version from master playlist")
        void testMasterVersion() {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:6
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            assertEquals(6, master.version());
        }

        @Test
        @DisplayName("Parse variant bandwidth and resolution")
        void testVariantBandwidthResolution() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=9014525,RESOLUTION=1920x1080
                1080p.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            assertEquals(1, master.variants().size());

            final Variant variant = master.variants().get(0);
            assertEquals(9014525L, variant.bandwidth());
            assertEquals(1920, variant.width());
            assertEquals(1080, variant.height());
            assertEquals("1920x1080", variant.resolution());
            assertEquals("1080p.m3u8", variant.uri());
        }

        @Test
        @DisplayName("Parse variant with codecs and frame rate")
        void testVariantCodecsFrameRate() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",FRAME-RATE=60.000
                stream.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            final Variant variant = master.variants().get(0);

            assertEquals("avc1.64002A,mp4a.40.2", variant.codecs());
            assertEquals(60.0, variant.fps(), 0.001);
        }

        @Test
        @DisplayName("Parse session data")
        void testSessionData() {
            final String content = """
                #EXTM3U
                #EXT-X-SESSION-DATA:DATA-ID="com.example.node",VALUE="server1.example.com"
                #EXT-X-SESSION-DATA:DATA-ID="com.example.broadcast",VALUE="12345",LANGUAGE="en"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            assertEquals(2, master.sessionData().size());

            final SessionData sd1 = master.sessionData().get(0);
            assertEquals("com.example.node", sd1.id());
            assertEquals("server1.example.com", sd1.value());

            final SessionData sd2 = master.sessionData().get(1);
            assertEquals("com.example.broadcast", sd2.id());
            assertEquals("12345", sd2.value());
            assertEquals("en", sd2.language());
        }

        @Test
        @DisplayName("Parse media renditions")
        void testMediaRenditions() {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio_en.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",LANGUAGE="es",DEFAULT=NO,AUTOSELECT=YES,URI="audio_es.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="audio"
                video.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            assertEquals(2, master.renditions().size());

            final Rendition en = master.renditions().get(0);
            assertEquals("AUDIO", en.type());
            assertEquals("audio", en.groupId());
            assertEquals("English", en.name());
            assertEquals("en", en.language());
            assertEquals("audio_en.m3u8", en.uri());
            assertTrue(en.isDefault());
            assertTrue(en.autoSelect());

            final Rendition es = master.renditions().get(1);
            assertEquals("Spanish", es.name());
            assertEquals("es", es.language());
            assertFalse(es.isDefault());
            assertTrue(es.autoSelect());
        }

        @Test
        @DisplayName("Parse video group with name")
        void testVideoGroupName() {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,VIDEO="chunked"
                chunked.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            final Variant variant = master.variants().get(0);

            assertEquals("chunked", variant.videoGroup());
            assertEquals("1080p60 (source)", variant.name());
        }

        @Test
        @DisplayName("best() returns highest bandwidth variant")
        void testBestVariant() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
                360p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
                1080p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
                720p.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");
            final MasterResult master = (MasterResult) result;

            assertTrue(master.best().isPresent());
            assertEquals(5000000L, master.best().get().bandwidth());
            assertEquals("1080p.m3u8", master.best().get().uri());
        }

        @Test
        @DisplayName("worst() returns lowest bandwidth variant")
        void testWorstVariant() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
                360p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
                1080p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
                720p.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");
            final MasterResult master = (MasterResult) result;

            assertTrue(master.worst().isPresent());
            assertEquals(500000L, master.worst().get().bandwidth());
            assertEquals("360p.m3u8", master.worst().get().uri());
        }

        @Test
        @DisplayName("sorted() returns variants by bandwidth descending")
        void testSortedVariants() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
                360p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
                1080p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
                720p.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");
            final MasterResult master = (MasterResult) result;

            final List<Variant> sorted = master.sorted();
            assertEquals(3, sorted.size());
            assertEquals(5000000L, sorted.get(0).bandwidth());
            assertEquals(2000000L, sorted.get(1).bandwidth());
            assertEquals(500000L, sorted.get(2).bandwidth());
        }

        @Test
        @DisplayName("byResolution() finds variant by width and height")
        void testByResolution() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
                360p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
                720p.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");
            final MasterResult master = (MasterResult) result;

            assertTrue(master.byResolution(1280, 720).isPresent());
            assertEquals("720p.m3u8", master.byResolution(1280, 720).get().uri());

            assertFalse(master.byResolution(1920, 1080).isPresent());
        }

        @Test
        @DisplayName("Variant quality() labels")
        void testVariantQualityLabels() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=20000000,RESOLUTION=3840x2160,FRAME-RATE=60
                4k60.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=15000000,RESOLUTION=3840x2160,FRAME-RATE=30
                4k.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=2560x1440,FRAME-RATE=60
                1440p60.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,FRAME-RATE=60
                1080p60.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,FRAME-RATE=30
                1080p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,FRAME-RATE=60
                720p60.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,FRAME-RATE=30
                720p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480
                480p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
                360p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=426x240
                240p.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");
            final MasterResult master = (MasterResult) result;
            final List<Variant> sorted = master.sorted();

            assertEquals("4K60", sorted.get(0).quality());
            assertEquals("4K", sorted.get(1).quality());
            assertEquals("1440p60", sorted.get(2).quality());
            assertEquals("1080p60", sorted.get(3).quality());
            assertEquals("1080p", sorted.get(4).quality());
            assertEquals("720p60", sorted.get(5).quality());
            assertEquals("720p", sorted.get(6).quality());
            assertEquals("480p", sorted.get(7).quality());
            assertEquals("360p", sorted.get(8).quality());
            assertEquals("240p", sorted.get(9).quality());
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
        void testBasicMediaPlaylist() {
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

            final Result result = HlsTool.parse(content, "playlist.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals("playlist.m3u8", media.source());
            assertEquals(3, media.version());
            assertEquals(6.0, media.targetDuration(), 0.001);
            assertEquals(2, media.segments().size());
            assertFalse(media.live());
            assertFalse(media.vod());
        }

        @Test
        @DisplayName("Parse media sequence")
        void testMediaSequence() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:1074
                #EXTINF:4.0,
                segment1074.ts
                #EXTINF:4.0,
                segment1075.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals(1074L, media.sequence());
            assertEquals(1074L, media.segments().get(0).sequence());
            assertEquals(1075L, media.segments().get(1).sequence());
        }

        @Test
        @DisplayName("Parse segment duration and title")
        void testSegmentDurationTitle() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:10
                #EXTINF:4.167,live segment
                segment1.ts
                #EXTINF:4.166,
                segment2.ts
                #EXT-X-ENDLIST
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            final Segment seg1 = media.segments().get(0);
            assertEquals(4.167, seg1.duration(), 0.001);
            assertEquals("live segment", seg1.title());
            assertEquals("segment1.ts", seg1.uri());

            final Segment seg2 = media.segments().get(1);
            assertEquals(4.166, seg2.duration(), 0.001);
            assertEquals("", seg2.title());
        }

        @Test
        @DisplayName("Parse program date time")
        void testProgramDateTime() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:56:19.451Z
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                segment2.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals("2025-12-16T10:56:19.451Z", media.segments().get(0).dateTime());
            assertNull(media.segments().get(1).dateTime()); // Only first segment has dateTime
        }

        @Test
        @DisplayName("Detect live stream (no ENDLIST)")
        void testLiveStream() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                segment2.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertTrue(media.live());
        }

        @Test
        @DisplayName("Detect VOD stream")
        void testVodStream() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:4.0,
                segment1.ts
                #EXT-X-ENDLIST
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertTrue(media.vod());
            assertFalse(media.live());
        }

        @Test
        @DisplayName("Calculate total duration")
        void testTotalDuration() {
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

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals(12.5, media.totalDuration(), 0.001);
        }

        @Test
        @DisplayName("bySequence() finds segment by sequence number")
        void testBySequence() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:100
                #EXTINF:4.0,
                segment100.ts
                #EXTINF:4.0,
                segment101.ts
                #EXTINF:4.0,
                segment102.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");
            final MediaResult media = (MediaResult) result;

            assertTrue(media.bySequence(101).isPresent());
            assertEquals("segment101.ts", media.bySequence(101).get().uri());

            assertFalse(media.bySequence(999).isPresent());
        }

        @Test
        @DisplayName("Parse EXTINF without comma")
        void testExtinfWithoutComma() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.5
                segment.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals(1, media.segments().size());
            assertEquals(4.5, media.segments().get(0).duration(), 0.001);
        }
    }

    // ==========================================================================
    // INPUT STREAM PARSING TESTS
    // ==========================================================================

    @Nested
    @DisplayName("InputStream Parsing")
    class InputStreamTests {

        @Test
        @DisplayName("Parse master playlist from InputStream")
        void testParseInputStreamMaster() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                720p.m3u8
                """;

            final var stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            final Result result = HlsTool.parse(stream, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            assertEquals(1, master.variants().size());
        }

        @Test
        @DisplayName("Parse media playlist from InputStream")
        void testParseInputStreamMedia() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                #EXT-X-ENDLIST
                """;

            final var stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            final Result result = HlsTool.parse(stream, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;
            assertEquals(1, media.segments().size());
        }
    }

    // ==========================================================================
    // EDGE CASE TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Handle whitespace around header")
        void testWhitespaceAroundHeader() {
            final String content = """
                   #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
        }

        @Test
        @DisplayName("Handle empty variant list")
        void testEmptyVariantList() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            assertEquals(0, master.variants().size()); // No URI follows the STREAM-INF
        }

        @Test
        @DisplayName("Handle full URLs in segments")
        void testFullUrlSegments() {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                https://cdn.example.com/stream/segment1.ts
                #EXTINF:4.0,
                https://cdn.example.com/stream/segment2.ts
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals("https://cdn.example.com/stream/segment1.ts", media.segments().get(0).uri());
        }

        @Test
        @DisplayName("Handle missing resolution in variant")
        void testMissingResolution() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            final Variant variant = master.variants().get(0);

            assertEquals(0, variant.width());
            assertEquals(0, variant.height());
            assertEquals("0x0", variant.resolution());
        }

        @Test
        @DisplayName("Handle default frame rate")
        void testDefaultFrameRate() {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080
                stream.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            final Variant variant = master.variants().get(0);

            assertEquals(30.0, variant.fps(), 0.001); // Default is 30
        }

        @Test
        @DisplayName("Handle multiple PROGRAM-DATE-TIME tags")
        void testMultipleProgramDateTime() {
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

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals("2025-12-16T10:00:00.000Z", media.segments().get(0).dateTime());
            assertEquals("2025-12-16T10:00:04.000Z", media.segments().get(1).dateTime());
        }

        @Test
        @DisplayName("Handle audio group in variant")
        void testAudioGroup() {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",NAME="Stereo"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="stereo"
                stream.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;
            final Variant variant = master.variants().get(0);

            assertEquals("stereo", variant.audioGroup());
        }

        @Test
        @DisplayName("Handle session data with URI")
        void testSessionDataWithUri() {
            final String content = """
                #EXTM3U
                #EXT-X-SESSION-DATA:DATA-ID="com.example.metadata",URI="metadata.json"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;

            final Result result = HlsTool.parse(content, "test.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;

            assertEquals(1, master.sessionData().size());
            final SessionData sd = master.sessionData().get(0);
            assertEquals("com.example.metadata", sd.id());
            assertEquals("metadata.json", sd.uri());
            assertNull(sd.value());
        }

        @Test
        @DisplayName("Complex real-world master playlist")
        void testRealWorldMaster() {
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

            final Result result = HlsTool.parse(content, "master.m3u8");

            assertInstanceOf(MasterResult.class, result);
            final MasterResult master = (MasterResult) result;

            // Verify session data
            assertEquals(2, master.sessionData().size());

            // Verify renditions
            assertEquals(4, master.renditions().size());

            // Verify variants
            assertEquals(4, master.variants().size());

            // Verify best/worst
            assertTrue(master.best().isPresent());
            assertEquals(9014525L, master.best().get().bandwidth());
            assertEquals("1080p60", master.best().get().quality());

            assertTrue(master.worst().isPresent());
            assertEquals(630000L, master.worst().get().bandwidth());
            assertEquals("360p", master.worst().get().quality());

            // Verify sorted order
            final List<Variant> sorted = master.sorted();
            assertEquals("1080p60", sorted.get(0).quality());
            assertEquals("720p60", sorted.get(1).quality());
            assertEquals("480p", sorted.get(2).quality());
            assertEquals("360p", sorted.get(3).quality());
        }

        @Test
        @DisplayName("Complex real-world media playlist")
        void testRealWorldMedia() {
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

            final Result result = HlsTool.parse(content, "playlist.m3u8");

            assertInstanceOf(MediaResult.class, result);
            final MediaResult media = (MediaResult) result;

            assertEquals("playlist.m3u8", media.source());
            assertEquals(3, media.version());
            assertEquals(6.0, media.targetDuration(), 0.001);
            assertEquals(1074L, media.sequence());
            assertTrue(media.live()); // No ENDLIST
            assertFalse(media.vod());
            assertEquals(12.5, media.totalDuration(), 0.001);

            // Verify segments
            assertEquals(3, media.segments().size());

            final Segment seg1 = media.segments().get(0);
            assertEquals("https://cdn.example.com/segment1.ts", seg1.uri());
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
    @DisplayName("Result Types")
    class ResultTypeTests {

        @Test
        @DisplayName("Result sealed interface permits only defined types")
        void testSealedInterface() {
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

            final Result master = HlsTool.parse(masterContent, "test.m3u8");
            final Result media = HlsTool.parse(mediaContent, "test.m3u8");
            final Result error = HlsTool.parse((String) null, "test.m3u8");

            // Verify types using instanceof
            assertInstanceOf(MasterResult.class, master);
            assertInstanceOf(MediaResult.class, media);
            assertInstanceOf(ErrorResult.class, error);

            // Verify each type is distinct
            assertFalse(master instanceof MediaResult);
            assertFalse(master instanceof ErrorResult);
            assertFalse(media instanceof MasterResult);
            assertFalse(media instanceof ErrorResult);
            assertFalse(error instanceof MasterResult);
            assertFalse(error instanceof MediaResult);
        }
    }
}
