package org.watermedia.api.platform.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.HlsTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class TwitchPlatform implements IPlatform {
    private static final Marker IT = MarkerManager.getMarker(TwitchPlatform.class.getSimpleName());
    // TWITCH GQL ENDPOINT AND USHER CDN URLS
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String LIVE_URL = "https://usher.ttvnw.net/api/channel/hls/%s.m3u8";
    private static final String VOD_URL = "https://usher.ttvnw.net/vod/%s.m3u8";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final Gson GSON = new Gson();

    // INLINE GQL QUERY FOR ACCESS TOKENS
    private static final String ACCESS_TOKEN_QUERY =
            "query PlaybackAccessToken_Template($login: String!, $isLive: Boolean!, $vodID: ID!, $isVod: Boolean!, $playerType: String!) {" +
            "  streamPlaybackAccessToken(channelName: $login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isLive) { value signature }" +
            "  videoPlaybackAccessToken(id: $vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isVod) { value signature }" +
            "}";

    // PERSISTED QUERY HASHES — ALIGNED WITH YT-DLP'S TWITCH EXTRACTOR
    private static final String HASH_STREAM_METADATA = "ad022ca32220d5523d03a23cbcb5beaa1e0999889c1f8f78f9f2520dafb5cae6";
    private static final String HASH_COMSCORE = "e1edae8122517d013405f237ffcc124515dc6ded82480a88daef69c83b53ac01";
    private static final String HASH_VIDEO_PREVIEW = "9515480dee68a77e667cb19de634739d33f243572b007e98e67184b1a5d8369f";
    private static final String HASH_VIDEO_METADATA = "45111672eea2e507f8ba44d101a61862f9c56b11dee09a15634cb75cb9b9084d";
    private static final String HASH_CLIP = "0a02bb974443b576f5579aab0fef1d4b7f44e58a8a256f0c5adfead0db70640f";
    private static final String HASH_CONTENT_CLASS = "57bb6c1aca3631b2b3e74b1c3c8adbecbbcc3becb70ec52d7c5ef0f90d7c3b02";

    // SIGN POST FLAGGED BY TWITCH WHEN THE CONTENT CLASSIFICATION LABELS MARK A STREAM/VOD/CLIP AS MATURE
    private static final String MATURE_SIGN_POST = "SIGN_POST_TYPE_MATURE";

    private static final Set<String> VALID_HOSTS = Set.of(
            "twitch.tv", "www.twitch.tv", "m.twitch.tv", "go.twitch.tv",
            "player.twitch.tv", "clips.twitch.tv"
    );

    // /videos/{id}, /{user}/v/{id}, /{user}/video/{id}
    private static final Pattern VOD_PATH = Pattern.compile("^/(?:[^/]+/v(?:ideo)?|videos)/(\\d+)");
    // /{user}/clip/{slug}
    private static final Pattern CLIP_PATH = Pattern.compile("^/(?:[^/]+/)?clip/([^/?#&]+)");
    // /{channel}
    private static final Pattern CHANNEL_PATH = Pattern.compile("^/([^/#?]+)/?$");

    @Override
    public String name() { return "Twitch"; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && VALID_HOSTS.contains(host);
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final String host = uri.getHost();
        final String path = uri.getPath();
        final String query = uri.getQuery();

        // CLIPS.TWITCH.TV/{SLUG} OR CLIPS.TWITCH.TV/EMBED?CLIP={SLUG}
        if ("clips.twitch.tv".equals(host)) {
            final String slug = parseClipsHost(path, query);
            if (slug == null) throw new IllegalArgumentException("No clip slug found: " + uri);
            return this.getClipSources(slug);
        }

        // PLAYER.TWITCH.TV/?VIDEO={ID} OR ?CHANNEL={NAME}
        if ("player.twitch.tv".equals(host)) {
            if (query != null) {
                final String videoId = queryParam(query, "video");
                if (videoId != null) return this.getVodSources(videoId.replaceFirst("^v", ""));
                final String channel = queryParam(query, "channel");
                if (channel != null) return this.getStreamSources(channel.toLowerCase());
            }
            throw new IllegalArgumentException("Invalid player URL: " + uri);
        }

        if (path == null || path.length() <= 1) throw new IllegalArgumentException("Invalid Twitch URL: " + uri);

        // VOD: /VIDEOS/{ID}, /{USER}/V/{ID}, /{USER}/VIDEO/{ID}
        Matcher m = VOD_PATH.matcher(path);
        if (m.find()) return this.getVodSources(m.group(1));

        // SCHEDULE: ?VODID={ID}
        if (query != null) {
            final String vodId = queryParam(query, "vodID");
            if (vodId != null) return this.getVodSources(vodId);
        }

        // CLIP: /{USER}/CLIP/{SLUG}
        m = CLIP_PATH.matcher(path);
        if (m.find()) return this.getClipSources(m.group(1));

        // STREAM: /{CHANNEL}
        m = CHANNEL_PATH.matcher(path);
        if (m.find()) return this.getStreamSources(m.group(1).toLowerCase());

        throw new IllegalArgumentException("Unrecognized Twitch URL: " + uri);
    }

    // --- CONTENT-TYPE HANDLERS ---

    private PlatformData getStreamSources(final String channel) throws Exception {
        this.ensureNotMature(ContentKind.STREAM, channel);
        final DataQuality[] variants = this.fetchHlsVariants(channel, false);

        Metadata metadata = null;
        URI thumbnail = null;
        try {
            final MetadataWithThumbnail mwt = this.fetchStreamMetadata(channel);
            if (mwt != null) { metadata = mwt.metadata; thumbnail = mwt.thumbnail; }
        } catch (final Exception e) {
            LOGGER.warn(IT, "Stream metadata fetch failed for '{}': {}", channel, e.getMessage());
        }

        return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES),
                new DataSource(MediaType.VIDEO, thumbnail, metadata,
                        RequestHeaders.defaults(URI.create("https://www.twitch.tv/")),
                        variants, null, null));
    }

    private PlatformData getVodSources(final String vodId) throws Exception {
        this.ensureNotMature(ContentKind.VOD, vodId);
        final DataQuality[] variants = this.fetchHlsVariants(vodId, true);

        Metadata metadata = null;
        URI thumbnail = null;
        try {
            final MetadataWithThumbnail mwt = this.fetchVodMetadata(vodId);
            if (mwt != null) { metadata = mwt.metadata; thumbnail = mwt.thumbnail; }
        } catch (final Exception e) {
            LOGGER.warn(IT, "VOD metadata fetch failed for '{}': {}", vodId, e.getMessage());
        }

        return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES),
                new DataSource(MediaType.VIDEO, thumbnail, metadata,
                        RequestHeaders.defaults(URI.create("https://www.twitch.tv/")),
                        variants, null, null));
    }

    private PlatformData getClipSources(final String slug) throws Exception {
        this.ensureNotMature(ContentKind.CLIP, slug);
        final JsonObject clip = this.fetchClipData(slug);
        if (clip == null) throw new IOException("Clip not found or no longer available: " + slug);

        final JsonObject accessToken = clip.getAsJsonObject("playbackAccessToken");
        if (accessToken == null) throw new IOException("No access token for clip: " + slug);

        final String sig = accessToken.get("signature").getAsString();
        final String token = accessToken.get("value").getAsString();
        final String accessQuery = "sig=" + URLEncoder.encode(sig, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        final List<DataQuality> clipVariants = new ArrayList<>();

        final JsonArray assets = clip.getAsJsonArray("assets");
        if (assets != null && !assets.isEmpty()) {
            final JsonObject asset = assets.get(0).getAsJsonObject();
            final JsonArray videoQualities = asset.getAsJsonArray("videoQualities");
            if (videoQualities != null) {
                for (final JsonElement qe: videoQualities) {
                    final JsonObject q = qe.getAsJsonObject();
                    final String sourceUrl = jsonString(q, "sourceURL");
                    final int height = jsonInt(q, "quality", 0);
                    if (sourceUrl != null && height > 0) {
                        final String separator = sourceUrl.contains("?") ? "&" : "?";
                        clipVariants.add(new DataQuality(
                                URI.create(sourceUrl + separator + accessQuery),
                                0, height));
                    }
                }
            }
        }

        if (clipVariants.isEmpty()) throw new IOException("No video qualities found for clip: " + slug);

        final String title = jsonString(clip, "title");
        final int duration = jsonInt(clip, "durationSeconds", 0);
        String author = null;
        final JsonElement broadcasterEl = clip.get("broadcaster");
        if (broadcasterEl != null && !broadcasterEl.isJsonNull()) {
            author = jsonString(broadcasterEl.getAsJsonObject(), "displayName");
        }

        URI thumbnailUri = null;
        if (!assets.isEmpty()) {
            final String thumbUrl = jsonString(assets.get(0).getAsJsonObject(), "thumbnailURL");
            if (thumbUrl != null) thumbnailUri = URI.create(thumbUrl);
        }

        Instant publishedAt = null;
        final String createdAt = jsonString(clip, "createdAt");
        if (createdAt != null) {
            try { publishedAt = Instant.parse(createdAt); }
            catch (final Exception ignored) {}
        }

        final Metadata clipMetadata = new Metadata(title, null, publishedAt, duration, author);
        return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES),
                new DataSource(MediaType.VIDEO, thumbnailUri, clipMetadata,
                        RequestHeaders.defaults(URI.create("https://www.twitch.tv/")),
                        clipVariants.toArray(DataQuality[]::new), null, null));
    }

    // --- HLS FETCHING ---

    /**
     * Resolves the channel/VOD master playlist to a list of {@link DataQuality} carrying
     * the real {@code width}/{@code height} reported by each HLS rendition tag. Falls
     * back to a single {@code (0, 0)} variant pointing at the master URL when the
     * playlist is not a master (MRL will mark it UNKNOWN and FFMediaPlayer will
     * upgrade it after probing the stream).
     */
    private DataQuality[] fetchHlsVariants(final String id, final boolean isVod) throws IOException {
        final String playlistUrl = this.buildPlaylistUrl(id, isVod);

        final var hlsResult = HlsTool.fetch(URI.create(playlistUrl));
        if (hlsResult instanceof final HlsTool.ErrorResult error) {
            throw new IOException("Failed to fetch Twitch " + (isVod ? "VOD" : "stream") + " playlist: " + error.message());
        }

        if (hlsResult instanceof final HlsTool.MasterResult master) {
            final List<DataQuality> variants = new ArrayList<>(master.variants().size());
            for (final var variant: master.variants()) {
                variants.add(new DataQuality(URI.create(variant.uri()), variant.width(), variant.height()));
            }
            return variants.toArray(DataQuality[]::new);
        }
        return new DataQuality[] { new DataQuality(URI.create(playlistUrl), 0, 0) };
    }

    private String buildPlaylistUrl(final String id, final boolean isVod) throws IOException {
        final JsonObject token = this.fetchAccessToken(id, isVod);
        final String signature = token.get("signature").getAsString();
        final String encodedToken = URLEncoder.encode(token.get("value").getAsString(), StandardCharsets.UTF_8);
        final long p = ThreadLocalRandom.current().nextLong(1_000_000, 10_000_001);
        final String sessionId = UUID.randomUUID().toString().replace("-", "");

        return String.format(isVod ? VOD_URL : LIVE_URL, id) +
                "?allow_source=true&allow_audio_only=true&allow_spectre=true" +
                "&p=" + p +
                "&play_session_id=" + sessionId +
                "&player=twitchweb&platform=web&player_backend=mediaplayer" +
                "&playlist_include_framerate=true" +
                "&supported_codecs=av1,h265,h264" +
                "&sig=" + signature + "&token=" + encodedToken;
    }

    // --- ACCESS TOKEN ---

    private JsonObject fetchAccessToken(final String id, final boolean isVod) throws IOException {
        final Map<String, Object> variables = new HashMap<>();
        variables.put("isLive", !isVod);
        variables.put("isVod", isVod);
        variables.put("login", isVod ? "" : id);
        variables.put("vodID", isVod ? id : "");
        variables.put("playerType", "site");

        final Map<String, Object> body = new HashMap<>();
        body.put("operationName", "PlaybackAccessToken_Template");
        body.put("query", ACCESS_TOKEN_QUERY);
        body.put("variables", variables);

        final String response = this.gqlPost(body);
        final JsonObject data = JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("data");
        if (data == null) throw new IOException("Access token response missing 'data' field");

        final String tokenKey = isVod ? "videoPlaybackAccessToken" : "streamPlaybackAccessToken";
        final JsonObject token = data.getAsJsonObject(tokenKey);
        if (token == null) throw new IOException("Access token not found (key: " + tokenKey + ")");

        return token;
    }

    private record MetadataWithThumbnail(Metadata metadata, URI thumbnail) {}

    // --- METADATA ---

    private MetadataWithThumbnail fetchStreamMetadata(final String channel) {
        try {
            final List<Map<String, Object>> batch = new ArrayList<>(3);

            final Map<String, Object> streamVars = new HashMap<>();
            streamVars.put("channelLogin", channel);
            streamVars.put("includeIsDJ", true);
            batch.add(persistedQuery("StreamMetadata", HASH_STREAM_METADATA, streamVars));

            final Map<String, Object> comscoreVars = new HashMap<>();
            comscoreVars.put("channel", channel);
            comscoreVars.put("clipSlug", "");
            comscoreVars.put("isClip", false);
            comscoreVars.put("isLive", true);
            comscoreVars.put("isVodOrCollection", false);
            comscoreVars.put("vodID", "");
            batch.add(persistedQuery("ComscoreStreamingQuery", HASH_COMSCORE, comscoreVars));

            final Map<String, Object> previewVars = new HashMap<>();
            previewVars.put("login", channel);
            batch.add(persistedQuery("VideoPreviewOverlay", HASH_VIDEO_PREVIEW, previewVars));

            final String response = this.gqlPost(batch);
            final JsonArray results = JsonParser.parseString(response).getAsJsonArray();

            // STREAM METADATA — USER EXISTENCE + STREAM STATE
            final JsonElement userEl = results.get(0).getAsJsonObject().getAsJsonObject("data").get("user");
            if (userEl == null || userEl.isJsonNull()) return null;
            final JsonObject user = userEl.getAsJsonObject();

            final JsonElement streamEl = user.get("stream");
            final JsonObject stream = (streamEl != null && !streamEl.isJsonNull()) ? streamEl.getAsJsonObject() : null;

            Instant createdAt = null;
            String streamType = null;
            if (stream != null) {
                streamType = jsonString(stream, "type");
                final String createdStr = jsonString(stream, "createdAt");
                if (createdStr != null) {
                    try { createdAt = Instant.parse(createdStr); }
                    catch (final Exception ignored) {}
                }
            }

            // COMSCORE — TITLE + AUTHOR
            String title = null;
            String displayName = null;
            if (results.size() > 1) {
                final JsonElement sqUserEl = results.get(1).getAsJsonObject().getAsJsonObject("data").get("user");
                if (sqUserEl != null && !sqUserEl.isJsonNull()) {
                    final JsonObject sqUser = sqUserEl.getAsJsonObject();
                    displayName = jsonString(sqUser, "displayName");
                    final JsonElement broadcastEl = sqUser.get("broadcastSettings");
                    if (broadcastEl != null && !broadcastEl.isJsonNull()) {
                        title = jsonString(broadcastEl.getAsJsonObject(), "title");
                    }
                }
            }

            // VIDEO PREVIEW — STREAM THUMBNAIL (NOT PROFILE IMAGE)
            URI thumbnailUri = null;
            if (results.size() > 2) {
                try {
                    final JsonElement pvUserEl = results.get(2).getAsJsonObject().getAsJsonObject("data").get("user");
                    if (pvUserEl != null && !pvUserEl.isJsonNull()) {
                        final JsonElement pvStreamEl = pvUserEl.getAsJsonObject().get("stream");
                        if (pvStreamEl != null && !pvStreamEl.isJsonNull()) {
                            final String thumbUrl = jsonString(pvStreamEl.getAsJsonObject(), "previewImageURL");
                            if (thumbUrl != null) thumbnailUri = URI.create(thumbUrl);
                        }
                    }
                } catch (final Exception ignored) {}
            }

            if ("rerun".equals(streamType) && title != null) {
                title = title + " (rerun)";
            }

            return new MetadataWithThumbnail(new Metadata(title, null, createdAt, 0, displayName), thumbnailUri);
        } catch (final Exception e) {
            LOGGER.warn(IT, "Failed to fetch stream metadata for '{}': {}", channel, e.getMessage());
            return null;
        }
    }

    private MetadataWithThumbnail fetchVodMetadata(final String vodId) {
        try {
            final Map<String, Object> vars = new HashMap<>();
            vars.put("channelLogin", "");
            vars.put("videoID", vodId);

            final List<Map<String, Object>> batch = List.of(
                    persistedQuery("VideoMetadata", HASH_VIDEO_METADATA, vars)
            );

            final String response = this.gqlPost(batch);
            final JsonArray results = JsonParser.parseString(response).getAsJsonArray();

            final JsonElement videoEl = results.get(0).getAsJsonObject().getAsJsonObject("data").get("video");
            if (videoEl == null || videoEl.isJsonNull()) return null;
            final JsonObject video = videoEl.getAsJsonObject();

            final String title = jsonString(video, "title");
            final String description = jsonString(video, "description");
            final int duration = jsonInt(video, "lengthSeconds", 0);

            String author = null;
            final JsonElement ownerEl = video.get("owner");
            if (ownerEl != null && !ownerEl.isJsonNull()) {
                author = jsonString(ownerEl.getAsJsonObject(), "displayName");
            }

            URI thumbnailUri = null;
            final String thumbUrl = jsonString(video, "previewThumbnailURL");
            if (thumbUrl != null) {
                // FULL-SIZE THUMBNAIL: REPLACE DIMENSION PLACEHOLDER (E.G. 640x480.jpg → 0x0.jpg)
                thumbnailUri = URI.create(thumbUrl.replaceAll("\\d+x\\d+(\\.\\w+)($|(?=[?#]))", "0x0$1$2"));
            }

            Instant publishedAt = null;
            final String publishedStr = jsonString(video, "postedAt");
            if (publishedStr != null) {
                try { publishedAt = Instant.parse(publishedStr); }
                catch (final Exception ignored) {}
            }

            return new MetadataWithThumbnail(
                    new Metadata(title != null ? title : "Untitled Broadcast", description, publishedAt, duration, author),
                    thumbnailUri
            );
        } catch (final Exception e) {
            LOGGER.warn(IT, "Failed to fetch VOD metadata for '{}': {}", vodId, e.getMessage());
            return null;
        }
    }

    // --- CLIP DATA ---

    private JsonObject fetchClipData(final String slug) throws IOException {
        final Map<String, Object> vars = new HashMap<>();
        vars.put("slug", slug);

        final List<Map<String, Object>> batch = List.of(
                persistedQuery("ShareClipRenderStatus", HASH_CLIP, vars)
        );

        final String response = this.gqlPost(batch);
        final JsonArray results = JsonParser.parseString(response).getAsJsonArray();
        final JsonElement clipEl = results.get(0).getAsJsonObject().getAsJsonObject("data").get("clip");
        return (clipEl != null && !clipEl.isJsonNull()) ? clipEl.getAsJsonObject() : null;
    }

    // --- MATURE CONTENT ---

    private enum ContentKind { STREAM, VOD, CLIP }

    // QUERIES TWITCH'S CONTENT CLASSIFICATION SIGN POST AND ABORTS WHEN THE CONTENT IS MATURE
    // AND THE HOST DISABLED MATURE CONTENT. RUNS BEFORE ANY PLAYLIST/CLIP FETCH SO NO DATA IS RETRIEVED.
    private void ensureNotMature(final ContentKind kind, final String id) throws IOException {
        if (WaterMediaConfig.media.platforms.allowMatureContent) return;

        final Map<String, Object> vars = new HashMap<>();
        vars.put("login", kind == ContentKind.STREAM ? id : "");
        vars.put("vodID", kind == ContentKind.VOD ? id : "");
        vars.put("clipSlug", kind == ContentKind.CLIP ? id : "");
        vars.put("isStream", kind == ContentKind.STREAM);
        vars.put("isVOD", kind == ContentKind.VOD);
        vars.put("isClip", kind == ContentKind.CLIP);

        final String response = this.gqlPost(List.of(persistedQuery("ContentClassificationContext", HASH_CONTENT_CLASS, vars)));
        final JsonObject data = JsonParser.parseString(response).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("data");
        if (data == null) return;

        // STREAM NESTS UNDER user.stream; VOD UNDER video; CLIP UNDER clip
        final JsonElement node = switch (kind) {
            case STREAM -> {
                final JsonElement user = data.get("user");
                yield (user != null && !user.isJsonNull()) ? user.getAsJsonObject().get("stream") : null;
            }
            case VOD -> data.get("video");
            case CLIP -> data.get("clip");
        };
        if (node == null || node.isJsonNull()) return;

        final JsonElement policy = node.getAsJsonObject().get("contentClassificationLabelPolicyProperties");
        if (policy == null || policy.isJsonNull()) return;
        final JsonElement sign = policy.getAsJsonObject().get("signPostProperties");
        if (sign == null || sign.isJsonNull()) return;

        if (MATURE_SIGN_POST.equals(jsonString(sign.getAsJsonObject(), "signPost")))
            throw new MatureContentException("Twitch " + kind.name().toLowerCase() + " '" + id + "' is marked as mature content");
    }

    // --- GQL HELPERS ---

    private String gqlPost(final Object body) throws IOException {
        try (final NetRequest req = NetRequest.create(URI.create(GQL_URL))
                .method("POST")
                .accept("application/json")
                .contentType("application/json; charset=utf-8")
                .header("Client-ID", CLIENT_ID)
                .body(GSON.toJson(body))
                .send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for Twitch GQL");
            return req.readAllAsString();
        }
    }

    private static Map<String, Object> persistedQuery(final String opName, final String hash, final Map<String, ?> variables) {
        final Map<String, Object> op = new HashMap<>(3);
        op.put("operationName", opName);
        op.put("variables", variables);
        op.put("extensions", Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash)));
        return op;
    }

    // --- URL PARSING ---

    private static String parseClipsHost(final String path, final String query) {
        if ("/embed".equals(path) && query != null) {
            return queryParam(query, "clip");
        }
        if (path != null && path.length() > 1) {
            final String[] segments = path.substring(1).split("/");
            final String last = segments[segments.length - 1];
            return last.isEmpty() ? null : last;
        }
        return null;
    }

    private static String queryParam(final String query, final String key) {
        if (query == null) return null;
        for (final String param: query.split("&")) {
            final String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    // --- JSON HELPERS ---

    private static String jsonString(final JsonObject obj, final String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static int jsonInt(final JsonObject obj, final String key, final int def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : def;
    }
}
