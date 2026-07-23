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

public final class KickPlatform implements IPlatform {
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
            final Channel channel = NetRequest.fetchJson(KickPlatform.class, String.format(CHANNELS_API, slug), Channel.class);

            if (channel.livestream == null || !channel.livestream.live)
                throw new PlatformException(KickPlatform.class, "Streamer '" + slug + "' is offline");

            if (channel.banned)
                throw new PlatformException(KickPlatform.class, "Streamer '" + slug + "' is banned");

            if (channel.livestream.mature && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(KickPlatform.class, "Streamer '" + slug + "' is marked as mature content");

            final String username = channel.user != null ? channel.user.username : slug;
            if (channel.livestream.sessionTitle == null)
                LOGGER.warn(IT, "Kick channel '{}' is live but reports no session_title", slug);

            // FETCH THE HLS MASTER PLAYLIST AND EXPAND ITS RENDITIONS INTO QUALITY VARIANTS
            final List<DataQuality> variants = variantsFrom(channel.url, "channel " + slug);

            final Metadata metadata = new Metadata(
                    username,
                    channel.livestream.sessionTitle,
                    parseDate(channel.livestream.startTime, slug),
                    0,
                    username);

            LOGGER.info(IT, "Kick resolved live channel '{}' with {} variant(s)", slug, variants.size());
            // KICK LIVE CDN LINKS ROTATE; RE-RESOLVE PERIODICALLY (30 MIN) TO AVOID SERVING STALE PLAYLISTS
            final var entry = new DataSource(MediaType.VIDEO, channel.user != null ? channel.user.profilePic : null, metadata,
                    RequestHeaders.defaults(uri),
                    variants,
                    null, null);
            return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entry);

        } else {
            if (!path[0].equalsIgnoreCase("video"))
                throw new PlatformException(KickPlatform.class, "Unrecognized URL (expected /<channel>, /video/<id>, or a clip): " + uri);

            final String id = path[path.length - 1];
            LOGGER.debug(IT, "Kick resolving VOD '{}' from {}", id, uri);
            final Video video = NetRequest.fetchJson(KickPlatform.class, String.format(VIDEO_API, id), Video.class);

            if (video.livestream == null || video.url == null)
                throw new PlatformException(KickPlatform.class, "VOD '" + id + "' is unavailable (no playback URL)");

            final String username = video.livestream.channel != null && video.livestream.channel.user != null
                    ? video.livestream.channel.user.username : null;

            if (video.livestream.channel != null && video.livestream.channel.banned)
                throw new PlatformException(KickPlatform.class, "Streamer '" + username + "' is banned");

            if (video.livestream.mature && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(KickPlatform.class, "VOD '" + id + "' is marked as mature content");

            // FETCH THE HLS PLAYLIST FROM THE VOD'S PLAYBACK URL (NOT THE PAGE URI)
            final List<DataQuality> vodVariants = variantsFrom(video.url, "VOD " + id);

            final Metadata vodMetadata = new Metadata(
                    username,
                    video.livestream.sessionTitle,
                    parseDate(video.livestream.startTime, id),
                    video.livestream.duration,
                    username);

            LOGGER.info(IT, "Kick resolved VOD '{}' with {} variant(s)", id, vodVariants.size());
            final var entry = new DataSource(MediaType.VIDEO,
                    video.livestream.channel != null && video.livestream.channel.user != null ? video.livestream.channel.user.profilePic : null,
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
        final SearchResponse res = NetRequest.fetchJson(KickPlatform.class, SEARCH_API + URLEncoder.encode(query, StandardCharsets.UTF_8), SearchResponse.class);
        if (res.channels == null) return List.of();

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

    private PlatformData resolveClip(final URI uri, final String clipId) throws Exception {
        final ClipResponse response = NetRequest.fetchJson(KickPlatform.class, String.format(CLIPS_API, clipId), ClipResponse.class);
        final Clip clip = response.clip;
        if (clip == null)
            throw new PlatformException(KickPlatform.class, "Clip '" + clipId + "' is unavailable");

        if (clip.clipUrl == null)
            throw new PlatformException(KickPlatform.class, "Clip '" + clipId + "' has no playback URL (removed or still processing)");

        if (clip.isMature && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(KickPlatform.class, "Clip '" + clipId + "' is marked as mature content");

        // CLIP URL MAY BE A DIRECT FILE (mp4/webm) OR AN M3U8 PLAYLIST
        final List<DataQuality> variants;
        if (clip.clipUrl.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
            variants = variantsFrom(clip.clipUrl, "clip " + clipId);
        } else {
            LOGGER.debug(IT, "Kick clip '{}' is a direct file: {}", clipId, clip.clipUrl);
            variants = List.of(new DataQuality(clip.clipUrl, 0, 0));
        }

        final Metadata metadata = new Metadata(
                clip.title,
                clip.category != null ? clip.category.name : null,
                parseIso(clip.createdAt),
                (long) (clip.duration * 1000L),
                clip.creator != null ? clip.creator.username : null);

        LOGGER.info(IT, "Kick resolved clip '{}' with {} variant(s)", clipId, variants.size());
        final var entry = new DataSource(MediaType.VIDEO, clip.thumbnail, metadata,
                RequestHeaders.defaults(uri),
                variants,
                null, null);
        return new PlatformData(null, entry);
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
    private static List<DataQuality> variantsFrom(final URI source, final String ctx) {
        final List<MPEGTool.Variant> variants = MPEGTool.qualities(source);
        final List<DataQuality> out = new ArrayList<>(variants.size());
        for (final MPEGTool.Variant v: variants) {
            out.add(new DataQuality(v.uri(), v.width(), v.height()));
        }
        LOGGER.debug(IT, "Kick resolved {} HLS rendition(s) for {}", out.size(), ctx);
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

    private record Channel(int id, @SerializedName("is_banned") boolean banned, Livestream livestream, User user, @SerializedName("playback_url") URI url) {}

    // SEARCH PAYLOAD: A SEPARATE SHAPE FROM Channel/User — channels[].slug + camelCase user{username, profilePic}
    private record SearchResponse(SearchChannel[] channels) {}

    private record SearchChannel(String slug, SearchUser user) {}

    private record SearchUser(String username, String profilePic) {}

    private record Livestream(int id, @SerializedName("is_live") boolean live, @SerializedName("is_mature") boolean mature,
                              long duration, @SerializedName("session_title") String sessionTitle,
                              @SerializedName("start_time") String startTime, Channel channel) {}

    private record User(int id, String username, @SerializedName("profile_pic") URI profilePic) {}

    private record Video(int id, Livestream livestream, @SerializedName("uri") URI url) {}

    private record ClipResponse(Clip clip) {}

    private record Clip(@SerializedName("clip_url") URI clipUrl, String title, Creator creator,
                        @SerializedName("thumbnail_url") URI thumbnail, float duration, Category category,
                        @SerializedName("created_at") String createdAt, @SerializedName("is_mature") boolean isMature) {}

    private record Creator(int id, String username) {}

    private record Category(int id, String name) {}
}
