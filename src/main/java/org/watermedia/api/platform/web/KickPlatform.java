package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JsonTool;
import org.watermedia.tools.MPEGTool;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.watermedia.WaterMedia.LOGGER;

public class KickPlatform implements IPlatform {
    public static final String NAME = "Kick";
    private static final Marker IT = MarkerManager.getMarker(KickPlatform.class.getSimpleName());
    private static final String VIDEO_API = "https://kick.com/api/v2/video/%s";
    private static final String CHANNELS_API = "https://kick.com/api/v2/channels/%s";
    private static final String CLIPS_API = "https://kick.com/api/v2/clips/%s/play";
    private static final String SEARCH_API = "https://kick.com/api/search?searched_word=";
    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] HOSTS = { "kick.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String clipId = clipId(uri);
        if (clipId != null) { // /<channel>/clips/clip_... OR /<channel>?clip=clip_...
            LOGGER.debug(IT, "Kick resolving clip '{}' from {}", clipId, uri);
            return this.resolveClip(uri, clipId);
        }

        final var path = uri.getPath().substring(1).split("/");

        if (path.length == 1) { // ASSUME IT WAS A CHANNEL NAME
            final String slug = path[0];
            LOGGER.debug(IT, "Kick resolving channel '{}' from {}", slug, uri);
            final Channel channel = this.getChannelInfo(slug);

            if (channel.livestream == null || !channel.livestream.is_live)
                throw new PlatformException(KickPlatform.class, "Streamer '" + slug + "' is offline");

            if (channel.is_banned)
                throw new PlatformException(KickPlatform.class, "Streamer '" + slug + "' is banned");

            if (channel.livestream.is_mature && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(KickPlatform.class, "Streamer '" + slug + "' is marked as mature content");

            final String username = channel.user != null ? channel.user.username : slug;
            if (channel.livestream.session_title == null)
                LOGGER.warn(IT, "Kick channel '{}' is live but reports no session_title", slug);

            // FETCH THE HLS MASTER PLAYLIST AND EXPAND ITS RENDITIONS INTO QUALITY VARIANTS
            final DataQuality[] variants = variantsFrom(channel.url, "channel " + slug);

            final Metadata metadata = new Metadata(
                    username,
                    channel.livestream.session_title,
                    parseDate(channel.livestream.start_time, slug),
                    0,
                    username);

            LOGGER.info(IT, "Kick resolved live channel '{}' with {} variant(s)", slug, variants.length);
            // KICK LIVE CDN LINKS ROTATE; RE-RESOLVE PERIODICALLY (30 MIN) TO AVOID SERVING STALE PLAYLISTS
            final var entry = new DataSource(MediaType.VIDEO, channel.user != null ? channel.user.profile_pic : null, metadata,
                    RequestHeaders.defaults(uri),
                    variants,
                    null, null);
            return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entry);

        } else {
            if (!path[0].equalsIgnoreCase("video"))
                throw new PlatformException(KickPlatform.class, "Unrecognized URL (expected /<channel>, /video/<id>, or a clip): " + uri);

            final String id = path[path.length - 1];
            LOGGER.debug(IT, "Kick resolving VOD '{}' from {}", id, uri);
            final Video video = this.getVideoInfo(id);

            if (video.livestream == null || video.url == null)
                throw new PlatformException(KickPlatform.class, "VOD '" + id + "' is unavailable (no playback URL)");

            final String username = video.livestream.channel != null && video.livestream.channel.user != null
                    ? video.livestream.channel.user.username : null;

            if (video.livestream.channel != null && video.livestream.channel.is_banned)
                throw new PlatformException(KickPlatform.class, "Streamer '" + username + "' is banned");

            if (video.livestream.is_mature && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(KickPlatform.class, "VOD '" + id + "' is marked as mature content");

            // FETCH THE HLS PLAYLIST FROM THE VOD'S PLAYBACK URL (NOT THE PAGE URI)
            final DataQuality[] vodVariants = variantsFrom(video.url, "VOD " + id);

            final Metadata vodMetadata = new Metadata(
                    username,
                    video.livestream.session_title,
                    parseDate(video.livestream.start_time, id),
                    video.livestream.duration,
                    username);

            LOGGER.info(IT, "Kick resolved VOD '{}' with {} variant(s)", id, vodVariants.length);
            final var entry = new DataSource(MediaType.VIDEO,
                    video.livestream.channel != null && video.livestream.channel.user != null ? video.livestream.channel.user.profile_pic : null,
                    vodMetadata,
                    RequestHeaders.defaults(uri),
                    vodVariants,
                    null, null);
            return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entry);
        }
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws Exception {
        // KICK SEARCH IS CHANNEL-CENTRIC: channels[] IS THE ONLY SECTION CARRYING A SLUG + AVATAR. NOTE THE
        // NESTED user OBJECT IS camelCase (profilePic/username), UNLIKE THE snake_case /api/v2/channels PAYLOAD.
        final SearchResponse res;
        try (final NetRequest req = NetRequest.create(URI.create(SEARCH_API + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(KickPlatform.class, "Search API for '" + query + "' returned HTTP " + req.statusCode());
            res = req.json(SearchResponse.class);
        }
        if (res == null || res.channels == null) return List.of();

        final List<PlatformResult> out = new ArrayList<>(Math.min(res.channels.length, limit));
        for (final SearchChannel channel: res.channels) {
            if (out.size() >= limit) break;
            if (channel.slug == null) continue;
            final String username = channel.user != null ? channel.user.username : null;
            final String pic = channel.user != null ? channel.user.profilePic : null;
            // SAFE-PARSE THE NETWORK AVATAR URL: A MALFORMED ONE MUST NOT ABORT THE WHOLE RESULT SET
            final URI thumbnail = JsonTool.uri(pic);
            final URI page = JsonTool.uri("https://kick.com/" + channel.slug);
            if (page == null) continue;
            // THE USERNAME IS THE SEARCH LABEL — THIS ENDPOINT REPORTS NO LIVE STREAM TITLE
            out.add(new PlatformResult(NAME, username != null ? username : channel.slug, thumbnail, page));
        }
        return out;
    }

    private Channel getChannelInfo(final String channel) throws Exception {
        try (final NetRequest req = NetRequest.create(URI.create(String.format(CHANNELS_API, channel))).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(KickPlatform.class, "Channels API for '" + channel + "' returned HTTP " + req.statusCode());
            final Channel data = req.json(Channel.class);
            if (data == null) throw new PlatformException(KickPlatform.class, "Channels API returned an empty or non-JSON body for '" + channel + "'");
            return data;
        }
    }

    private Video getVideoInfo(final String videoId) throws Exception {
        try (final NetRequest req = NetRequest.create(URI.create(String.format(VIDEO_API, videoId))).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(KickPlatform.class, "Video API for '" + videoId + "' returned HTTP " + req.statusCode());
            final Video data = req.json(Video.class);
            if (data == null) throw new PlatformException(KickPlatform.class, "Video API returned an empty or non-JSON body for '" + videoId + "'");
            return data;
        }
    }

    private PlatformData resolveClip(final URI uri, final String clipId) throws Exception {
        final Clip clip = this.getClipInfo(clipId);

        if (clip.clipUrl == null)
            throw new PlatformException(KickPlatform.class, "Clip '" + clipId + "' has no playback URL (removed or still processing)");

        if (clip.isMature && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(KickPlatform.class, "Clip '" + clipId + "' is marked as mature content");

        // CLIP URL MAY BE A DIRECT FILE (mp4/webm) OR AN M3U8 PLAYLIST
        final DataQuality[] variants;
        if (clip.clipUrl.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
            variants = variantsFrom(clip.clipUrl, "clip " + clipId);
        } else {
            LOGGER.debug(IT, "Kick clip '{}' is a direct file: {}", clipId, clip.clipUrl);
            variants = new DataQuality[] { new DataQuality(clip.clipUrl, 0, 0) };
        }

        final Metadata metadata = new Metadata(
                clip.title,
                clip.category != null ? clip.category.name : null,
                parseIso(clip.createdAt),
                (long) (clip.duration * 1000L),
                clip.creator != null ? clip.creator.username : null);

        LOGGER.info(IT, "Kick resolved clip '{}' with {} variant(s)", clipId, variants.length);
        final var entry = new DataSource(MediaType.VIDEO, clip.thumbnail, metadata,
                RequestHeaders.defaults(uri),
                variants,
                null, null);
        return new PlatformData(null, entry);
    }

    private Clip getClipInfo(final String clipId) throws Exception {
        try (final NetRequest req = NetRequest.create(URI.create(String.format(CLIPS_API, clipId))).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(KickPlatform.class, "Clips API for '" + clipId + "' returned HTTP " + req.statusCode());
            final ClipResponse response = req.json(ClipResponse.class);
            if (response == null || response.clip == null) throw new PlatformException(KickPlatform.class, "Clip '" + clipId + "' is unavailable");
            return response.clip;
        }
    }

    // RESOLVES THE clip_... ID FROM BOTH URL SHAPES: /<channel>/clips/clip_... AND /<channel>?clip=clip_...
    // RETURNS NULL WHEN THE URI IS NOT A CLIP (LIVE/VOD)
    private static String clipId(final URI uri) {
        final String query = uri.getQuery();
        if (query != null) {
            for (final String part: query.split("&")) {
                if (part.startsWith("clip=")) {
                    final String value = part.substring("clip=".length());
                    if (value.startsWith("clip_")) return value;
                }
            }
        }

        final String[] path = uri.getPath().substring(1).split("/");
        for (int i = 0; i < path.length - 1; i++) {
            if (path[i].equalsIgnoreCase("clips") && path[i + 1].startsWith("clip_")) return path[i + 1];
        }
        return null;
    }

    // RESOLVES AN HLS STREAM URL INTO QUALITY VARIANTS. RESILIENT BY DESIGN (NEVER THROWS): A FETCH/PARSE
    // HICCUP, A MEDIA PLAYLIST, OR A NON-HLS RESOURCE FALLS BACK TO THE RAW URL SO FFMediaPlayer CAN PROBE.
    // RENDITION URLS COME BACK ALREADY ABSOLUTE (RESOLVED AGAINST source BY MPEGTool).
    private static DataQuality[] variantsFrom(final URI source, final String ctx) {
        final List<MPEGTool.Variant> variants = MPEGTool.qualities(source);
        final DataQuality[] out = new DataQuality[variants.size()];
        for (int i = 0; i < variants.size(); i++) {
            final MPEGTool.Variant v = variants.get(i);
            out[i] = new DataQuality(v.uri(), v.width(), v.height());
        }
        LOGGER.debug(IT, "Kick resolved {} HLS rendition(s) for {}", out.length, ctx);
        return out;
    }

    // PARSES KICK LIVE/VOD start_time ("yyyy-MM-dd HH:mm:ss" OR ISO-8601), NULL-SAFE: WARNS RATHER
    // THAN ABORTING THE WHOLE RESOLUTION WHEN THE TIMESTAMP IS MISSING OR MALFORMED
    private static Instant parseDate(final String value, final String ctx) {
        final Instant parsed = parseIso(value);
        if (parsed == null && value != null)
            LOGGER.warn(IT, "Kick unparseable start_time '{}' for {}", value, ctx);
        return parsed;
    }

    // CLIP TIMESTAMPS ARE ISO-8601 (created_at), UNLIKE THE "yyyy-MM-dd HH:mm:ss" USED BY LIVE/VOD
    private static Instant parseIso(final String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value, DATE_PATTERN).toInstant(ZoneOffset.UTC);
            } catch (final DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private record Channel(int id, boolean is_banned, Livestream livestream, User user, @SerializedName("playback_url") URI url) {


    }

    // SEARCH PAYLOAD: A SEPARATE SHAPE FROM Channel/User — channels[].slug + camelCase user{username, profilePic}
    private record SearchResponse(SearchChannel[] channels) {

    }

    private record SearchChannel(String slug, SearchUser user) {

    }

    private record SearchUser(String username, String profilePic) {

    }

    public record Livestream(int id, boolean is_live, boolean is_mature, long duration, String session_title, String start_time, Channel channel) {

    }

    public record User(int id, String username, URI profile_pic) {

    }

    private record Video(int id, Livestream livestream, @SerializedName("uri") URI url) {

    }

    private record ClipResponse(Clip clip) {

    }

    private record Clip(@SerializedName("clip_url") URI clipUrl, String title, Creator creator,
                        @SerializedName("thumbnail_url") URI thumbnail, float duration, Category category,
                        @SerializedName("created_at") String createdAt, @SerializedName("is_mature") boolean isMature) {

    }

    private record Creator(int id, String username) {

    }

    private record Category(int id, String name) {

    }
}
