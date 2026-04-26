package org.watermedia.api.media.platform;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.api.media.MRL;
import org.watermedia.tools.HlsTool;
import org.watermedia.tools.NetTool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.watermedia.WaterMedia.LOGGER;

public class TwitchPlatform implements IPlatform {
    // TWITCH GQL ENDPOINT AND USHER CDN URLS
    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    private static final String LIVE_URL = "https://usher.ttvnw.net/api/channel/hls/%s.m3u8";
    private static final String VOD_URL = "https://usher.ttvnw.net/vod/%s.m3u8";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final Gson GSON = new Gson();

    // INLINE GQL QUERY FOR ACCESS TOKENS
    private static final String ACCESS_TOKEN_QUERY =
            "query PlaybackAccessToken_Template($login: String!, $isLive: Boolean!, $vodID: ID!, $isVod: Boolean!, $playerType: String!) {" +
            "  streamPlaybackAccessToken(channelName: $login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isLive) { value signature __typename }" +
            "  videoPlaybackAccessToken(id: $vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isVod) { value signature __typename }" +
            "}";

    // PERSISTED QUERY HASHES — THESE ARE TIED TO TWITCH'S GQL SCHEMA VERSION
    private static final String HASH_SESSION_MANAGER = "694c36677896425624f1293c9cb5aa4d08ed813993cf84c80d13d9380721fda2";
    private static final String HASH_USE_LIVE = "639d5f11bfb8bf3053b424d9ef650d04c4ebb7d94711d644afb08fe9a0fad5d9";

    @Override
    public String name() { return "Twitch"; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.equals("www.twitch.tv") || host.equals("twitch.tv"));
    }

    @Override
    public Result getSources(final URI uri) throws Exception {
        final String path = uri.getPath();
        if (path == null || path.length() <= 1) throw new IllegalArgumentException("Invalid Twitch URL: " + uri);

        final boolean isVod = path.startsWith("/videos/");
        final String id = isVod ? path.substring("/videos/".length()) : path.substring(1);

        // STEP 1: FETCH ACCESS TOKEN AND BUILD PLAYLIST URL
        final String playlistUrl = this.buildPlaylistUrl(id, isVod);

        // STEP 2: FETCH AND PARSE HLS PLAYLIST
        final var hlsResult = HlsTool.fetch(URI.create(playlistUrl));
        if (hlsResult instanceof final HlsTool.ErrorResult error) {
            throw new IOException("Failed to fetch Twitch playlist: " + error.message());
        }

        final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.VIDEO);

        if (hlsResult instanceof final HlsTool.MasterResult master) {
            for (final var variant: master.variants()) {
                sourceBuilder.quality(MRL.Quality.of(variant.width(), variant.height()), URI.create(variant.uri()));
            }
        } else {
            LOGGER.warn(IT, "Twitch returned non-master playlist, using source URL directly");
            sourceBuilder.uri(URI.create(playlistUrl));
        }

        // STEP 3: FETCH METADATA — NON-CRITICAL, FAILURE NEVER BREAKS PLAYBACK
        try {
            final MRL.Metadata metadata = this.fetchMetadata(id, isVod);
            if (metadata != null) {
                sourceBuilder.metadata(metadata);
            }
        } catch (final Exception e) {
            LOGGER.warn(IT, "Metadata fetch failed for '{}', continuing without it: {}", id, e.getMessage());
        }

        return new Result(Instant.now().plus(30, ChronoUnit.MINUTES), sourceBuilder.build());
    }

    // BUILDS THE USHER PLAYLIST URL WITH AN ACCESS TOKEN FROM GQL
    private String buildPlaylistUrl(final String id, final boolean isVod) throws IOException {
        final JsonObject token = this.fetchAccessToken(id, isVod);
        final String signature = token.get("signature").getAsString();
        final String encodedToken = URLEncoder.encode(token.get("value").getAsString(), StandardCharsets.UTF_8);

        return String.format(isVod ? VOD_URL : LIVE_URL, id) +
                "?acmb=e30%3D&allow_source=true&fast_bread=true&p=7370379" +
                "&play_session_id=21efcd962e7b3fbc891bac088214aa63&player_backend=mediaplayer" +
                "&playlist_include_framerate=true&reassignments_supported=true" +
                "&sig=" + signature + "&supported_codecs=avc1&token=" + encodedToken +
                "&transcode_mode=cbr_v1&cdm=wv&player_version=1.21.0";
    }

    // FETCHES PLAYBACK ACCESS TOKEN VIA INLINE GQL QUERY
    private JsonObject fetchAccessToken(final String id, final boolean isVod) throws IOException {
        final HttpURLConnection conn = NetTool.connectToHTTP(URI.create(GQL_URL), "POST", "application/json");
        conn.setDoOutput(true);
        conn.setRequestProperty("Client-ID", CLIENT_ID);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        try {
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

            try (final OutputStream os = conn.getOutputStream()) {
                os.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            }

            final String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String tokenKey = isVod ? "videoPlaybackAccessToken" : "streamPlaybackAccessToken";

            return JsonParser.parseString(response)
                    .getAsJsonObject().getAsJsonObject("data")
                    .getAsJsonObject(tokenKey);
        } finally {
            conn.disconnect();
        }
    }

    // FETCHES STREAM/VOD METADATA VIA BATCHED PERSISTED GQL QUERIES
    // RETURNS NULL ON ANY FAILURE — METADATA IS NEVER CRITICAL FOR PLAYBACK
    private MRL.Metadata fetchMetadata(final String id, final boolean isVod) {
        try {
            final HttpURLConnection conn = NetTool.connectToHTTP(URI.create(GQL_URL), "POST", "application/json");
            conn.setDoOutput(true);
            conn.setRequestProperty("Client-ID", CLIENT_ID);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            try {
                // BUILD BATCH: ALWAYS SESSION MANAGER, ADD USE LIVE FOR LIVE STREAMS
                final List<Map<String, Object>> batch = new ArrayList<>(2);

                final Map<String, Object> sessionVars = new HashMap<>();
                sessionVars.put("channel", isVod ? "" : id);
                sessionVars.put("clipSlug", "");
                sessionVars.put("isClip", false);
                sessionVars.put("isLive", !isVod);
                sessionVars.put("isVodOrCollection", isVod);
                sessionVars.put("vodID", isVod ? id : "");

                batch.add(Map.of(
                        "operationName", "VideoPlayerMediaSessionManager",
                        "variables", sessionVars,
                        "extensions", Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", HASH_SESSION_MANAGER))
                ));

                if (!isVod) {
                    batch.add(Map.of(
                            "operationName", "UseLive",
                            "variables", Map.of("channelLogin", id),
                            "extensions", Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", HASH_USE_LIVE))
                    ));
                }

                try (final OutputStream os = conn.getOutputStream()) {
                    os.write(GSON.toJson(batch).getBytes(StandardCharsets.UTF_8));
                }

                final String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                final JsonArray results = JsonParser.parseString(response).getAsJsonArray();

                // PARSE SESSION MANAGER RESPONSE: TITLE, AUTHOR, THUMBNAIL
                final JsonObject user = results.get(0).getAsJsonObject()
                        .getAsJsonObject("data")
                        .getAsJsonObject("user");

                if (user == null) return null;

                final String displayName = jsonString(user, "displayName");
                final String profileImage = jsonString(user, "profileImageURL");

                String title = null;
                final JsonObject broadcast = user.getAsJsonObject("broadcastSettings");
                if (broadcast != null) {
                    title = jsonString(broadcast, "title");
                }

                // PARSE USE LIVE RESPONSE: STREAM START TIME (LIVE ONLY)
                Instant publishedAt = null;
                if (!isVod && results.size() > 1) {
                    try {
                        final JsonObject stream = results.get(1).getAsJsonObject()
                                .getAsJsonObject("data")
                                .getAsJsonObject("user")
                                .getAsJsonObject("stream");

                        if (stream != null && stream.has("createdAt")) {
                            publishedAt = Instant.parse(stream.get("createdAt").getAsString());
                        }
                    } catch (final Exception ignored) {
                        // STREAM FIELD MAY BE NULL IF CHANNEL JUST WENT OFFLINE BETWEEN REQUESTS
                    }
                }

                return new MRL.Metadata(
                        title,
                        null,
                        profileImage != null ? URI.create(profileImage) : null,
                        publishedAt,
                        0,
                        displayName
                );
            } finally {
                conn.disconnect();
            }
        } catch (final Exception e) {
            LOGGER.warn(IT, "Failed to fetch Twitch metadata for '{}': {}", id, e.getMessage());
            return null;
        }
    }

    // SAFE JSON STRING EXTRACTION — RETURNS NULL FOR MISSING OR NULL FIELDS
    private static String jsonString(final JsonObject obj, final String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
