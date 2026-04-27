package org.watermedia.api.media.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class BiliBiliPlatform implements IPlatform {
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String VIDEO_VIEW_API = "https://api.bilibili.com/x/web-interface/view?bvid=%s";
    private static final String VIDEO_PLAYURL_API = "https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%d&fnval=4048";

    private static final String BANGUMI_SEASON_EP_API = "https://api.bilibili.com/pgc/view/web/season?ep_id=%s";
    private static final String BANGUMI_SEASON_SS_API = "https://api.bilibili.com/pgc/view/web/season?season_id=%s";
    private static final String BANGUMI_PLAYURL_API = "https://api.bilibili.com/pgc/player/web/playurl?ep_id=%s&cid=%s&fnval=4048";

    private static final String LIVE_PLAY_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=%s&no_playurl=0&mask=1&qn=0&platform=web&protocol=0,1&format=0,1,2&codec=0,1,2";
    private static final String LIVE_ROOM_INFO_API = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=%s";
    private static final String LIVE_USER_INFO_API = "https://api.bilibili.com/x/space/acc/info?mid=%d";
    private static final String LIVE_PLAYURL_API = "https://api.live.bilibili.com/xlive/web-room/v1/playUrl/playUrl?cid=%s&platform=h5&qn=%d";

    private static final Pattern BVID_PATTERN = Pattern.compile("(BV[a-zA-Z0-9]+)");
    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]p=(\\d+)");
    private static final Pattern EP_PATTERN = Pattern.compile("/ep(\\d+)");
    private static final Pattern SS_PATTERN = Pattern.compile("/ss(\\d+)");
    private static final Pattern LIVE_ROOM_PATTERN = Pattern.compile("live\\.bilibili\\.com/(\\d+)");

    @Override
    public String name() { return "BiliBili"; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.equals("www.bilibili.com")
                || host.equals("bilibili.com")
                || host.equals("live.bilibili.com")
                || host.equals("b23.tv")
        );
    }

    @Override
    public Result getSources(URI uri) throws Exception {
        if ("b23.tv".equalsIgnoreCase(uri.getHost())) {
            uri = resolveRedirect(uri);
        }

        final String host = uri.getHost();
        if ("live.bilibili.com".equals(host)) {
            return this.getLiveSources(uri);
        }

        final String path = uri.getPath();
        if (path != null && path.contains("/bangumi/play/")) {
            return this.getBangumiSources(uri);
        }

        return this.getVideoSources(uri);
    }

    // VIDEO
    private Result getVideoSources(final URI uri) throws Exception {
        final String url = uri.toString();

        final Matcher bvidMatcher = BVID_PATTERN.matcher(url);
        if (!bvidMatcher.find()) throw new IllegalArgumentException("No BVID found in URL: " + uri);
        final String bvid = bvidMatcher.group(1);

        int page = 1;
        final Matcher pageMatcher = PAGE_PATTERN.matcher(url);
        if (pageMatcher.find()) page = Integer.parseInt(pageMatcher.group(1));

        final JsonObject viewData = fetchJson(URI.create(String.format(VIDEO_VIEW_API, bvid)), "data");
        final String title = viewData.get("title").getAsString();
        final String author = viewData.getAsJsonObject("owner").get("name").getAsString();
        final String desc = jsonString(viewData, "desc");
        final URI thumbnail = jsonUri(viewData, "pic");
        final long duration = viewData.has("duration") ? viewData.get("duration").getAsLong() : 0;
        final Instant publishedAt = viewData.has("pubdate") ? Instant.ofEpochSecond(viewData.get("pubdate").getAsLong()) : null;

        final long cid;
        String partName = null;
        final JsonArray pages = viewData.getAsJsonArray("pages");
        if (pages != null && pages.size() > 1) {
            final int index = Math.max(0, Math.min(page - 1, pages.size() - 1));
            final JsonObject pageObj = pages.get(index).getAsJsonObject();
            cid = pageObj.get("cid").getAsLong();
            partName = pageObj.get("part").getAsString();
            if (partName.equals(title)) partName = null;
        } else {
            cid = viewData.get("cid").getAsLong();
        }

        final JsonObject playData = fetchJson(URI.create(String.format(VIDEO_PLAYURL_API, bvid, cid)), "data");

        final String fullTitle = partName != null ? title + " - " + partName : title;
        final MRL.Metadata metadata = new MRL.Metadata(fullTitle, desc, thumbnail, publishedAt, duration, author);
        return this.buildResult(playData, metadata);
    }

    // BANGUMI
    private Result getBangumiSources(final URI uri) throws Exception {
        final String url = uri.toString();

        String epId = null;
        String ssId = null;

        final Matcher epMatcher = EP_PATTERN.matcher(url);
        if (epMatcher.find()) epId = epMatcher.group(1);

        final Matcher ssMatcher = SS_PATTERN.matcher(url);
        if (ssMatcher.find()) ssId = ssMatcher.group(1);

        if (epId == null && ssId == null) throw new IllegalArgumentException("No ep/ss ID found in URL: " + uri);

        final String seasonApi = epId != null
                ? String.format(BANGUMI_SEASON_EP_API, epId)
                : String.format(BANGUMI_SEASON_SS_API, ssId);

        final JsonObject result = fetchJson(URI.create(seasonApi), "result");
        final String seasonTitle = result.get("title").getAsString();

        if (epId == null) {
            final JsonArray episodes = result.getAsJsonArray("episodes");
            if (episodes == null || episodes.isEmpty()) throw new IllegalStateException("No episodes found for season " + ssId);

            int page = 0;
            final Matcher pageMatcher = PAGE_PATTERN.matcher(url);
            if (pageMatcher.find()) page = Integer.parseInt(pageMatcher.group(1));

            final int index = (page > 0 && page <= episodes.size()) ? page - 1 : 0;
            epId = String.valueOf(episodes.get(index).getAsJsonObject().get("id").getAsInt());
        }

        String cid = null;
        String epTitle = null;
        URI epCover = null;
        long epDurationMs = 0;
        final JsonArray episodes = result.getAsJsonArray("episodes");
        if (episodes != null) {
            for (int i = 0; i < episodes.size(); i++) {
                final JsonObject ep = episodes.get(i).getAsJsonObject();
                if (String.valueOf(ep.get("id").getAsInt()).equals(epId)) {
                    cid = String.valueOf(ep.get("cid").getAsLong());
                    epTitle = ep.get("share_copy").getAsString();
                    epCover = jsonUri(ep, "cover");
                    epDurationMs = ep.has("duration") ? ep.get("duration").getAsLong() : 0;
                    break;
                }
            }
        }
        if (cid == null) throw new IllegalStateException("Episode ep" + epId + " not found in season");

        final JsonObject playResult = fetchJson(URI.create(String.format(BANGUMI_PLAYURL_API, epId, cid)), "result");

        if (playResult.has("is_preview") && playResult.get("is_preview").getAsInt() == 1) {
            throw new IOException("Content requires BiliBili premium membership (VIP)");
        }

        final String fullTitle = epTitle != null ? seasonTitle + " - " + epTitle : seasonTitle;
        final String desc = jsonString(result, "evaluate");
        final URI thumbnail = epCover != null ? epCover : jsonUri(result, "cover");
        final MRL.Metadata metadata = new MRL.Metadata(fullTitle, desc, thumbnail, null, epDurationMs / 1000, null);
        return this.buildResult(playResult, metadata);
    }

    // LIVE
    private Result getLiveSources(final URI uri) throws Exception {
        final Matcher roomMatcher = LIVE_ROOM_PATTERN.matcher(uri.toString());
        if (!roomMatcher.find()) throw new IllegalArgumentException("No room ID found in URL: " + uri);
        final String roomId = roomMatcher.group(1);

        final JsonObject playInfo = fetchJson(URI.create(String.format(LIVE_PLAY_INFO_API, roomId)), "data");
        if (playInfo.get("live_status").getAsInt() != 1) throw new IllegalStateException("Live stream is offline");
        final String realRoomId = String.valueOf(playInfo.get("room_id").getAsInt());
        final long uid = playInfo.has("uid") ? playInfo.get("uid").getAsLong() : 0;

        String title = "BiliBili Live";
        URI thumbnail = null;
        try {
            final JsonObject roomInfo = fetchJson(URI.create(String.format(LIVE_ROOM_INFO_API, realRoomId)), "data");
            if (roomInfo.has("title") && !roomInfo.get("title").isJsonNull()) {
                title = roomInfo.get("title").getAsString();
            }
            thumbnail = jsonUri(roomInfo, "keyframe");
            if (thumbnail == null) thumbnail = jsonUri(roomInfo, "user_cover");
        } catch (final Exception e) {
            LOGGER.warn(IT, "Failed to fetch live room title for room {}", realRoomId);
        }

        String author = null;
        if (uid > 0) {
            try {
                final JsonObject userInfo = fetchJson(URI.create(String.format(LIVE_USER_INFO_API, uid)), "data");
                if (userInfo.has("name") && !userInfo.get("name").isJsonNull()) {
                    author = userInfo.get("name").getAsString();
                }
            } catch (final Exception e) {
                LOGGER.warn(IT, "Failed to fetch author name for uid {}", uid);
            }
        }

        final JsonObject qualityData = fetchJson(URI.create(String.format(LIVE_PLAYURL_API, realRoomId, 0)), "data");
        final JsonArray qualityOptions = qualityData.getAsJsonArray("quality_description");

        int bestQn = 10000;
        if (qualityOptions != null && !qualityOptions.isEmpty()) {
            bestQn = qualityOptions.get(0).getAsJsonObject().get("qn").getAsInt();
        }

        final JsonObject streamData = fetchJson(URI.create(String.format(LIVE_PLAYURL_API, realRoomId, bestQn)), "data");
        final JsonArray durl = streamData.getAsJsonArray("durl");
        if (durl == null || durl.isEmpty()) throw new IllegalStateException("No live stream URL available");

        final String streamUrl = durl.get(0).getAsJsonObject().get("url").getAsString();
        final MRL.Metadata metadata = new MRL.Metadata(title, null, thumbnail, null, 0, author);
        final MRL.Source source = new MRL.Source(MRL.MediaType.VIDEO, URI.create(streamUrl), metadata);

        return new Result(Instant.now().plus(10, ChronoUnit.MINUTES), source);
    }

    // DASH / DURL PARSING
    private Result buildResult(final JsonObject data, final MRL.Metadata metadata) {
        final MRL.SourceBuilder builder = MRL.sourceBuilder(MRL.MediaType.VIDEO);
        builder.metadata(metadata);

        if (data.has("dash") && !data.get("dash").isJsonNull()) {
            final JsonObject dash = data.getAsJsonObject("dash");
            final JsonArray videoStreams = dash.has("video") ? dash.getAsJsonArray("video") : null;
            final JsonArray audioStreams = dash.has("audio") ? dash.getAsJsonArray("audio") : null;

            if (videoStreams != null && !videoStreams.isEmpty()) {
                final JsonArray supportFormats = data.getAsJsonArray("support_formats");
                parseDashVideo(builder, videoStreams, supportFormats);

                if (audioStreams != null && !audioStreams.isEmpty()) {
                    builder.audioSlave(URI.create(audioStreams.get(0).getAsJsonObject().get("baseUrl").getAsString()));
                }

                return new Result(Instant.now().plus(90, ChronoUnit.MINUTES), builder.build());
            }
        }

        if (data.has("durl")) {
            final JsonArray durl = data.getAsJsonArray("durl");
            if (durl != null && !durl.isEmpty()) {
                builder.quality(MRL.Quality.UNKNOWN, URI.create(durl.get(0).getAsJsonObject().get("url").getAsString()));
                return new Result(Instant.now().plus(90, ChronoUnit.MINUTES), builder.build());
            }
        }

        throw new IllegalStateException("No playable streams found in BiliBili API response");
    }

    private static void parseDashVideo(final MRL.SourceBuilder builder, final JsonArray videoStreams, final JsonArray supportFormats) {
        final EnumSet<MRL.Quality> registered = EnumSet.noneOf(MRL.Quality.class);

        if (supportFormats != null && !supportFormats.isEmpty()) {
            for (int i = 0; i < supportFormats.size(); i++) {
                final int qualityId = supportFormats.get(i).getAsJsonObject().get("quality").getAsInt();
                final MRL.Quality quality = mapQuality(qualityId);
                if (registered.contains(quality)) continue;

                final JsonObject stream = findBestCodecStream(videoStreams, qualityId);
                if (stream != null) {
                    builder.quality(quality, URI.create(stream.get("baseUrl").getAsString()));
                    registered.add(quality);
                }
            }
        } else {
            for (int i = 0; i < videoStreams.size(); i++) {
                final int qualityId = videoStreams.get(i).getAsJsonObject().get("id").getAsInt();
                final MRL.Quality quality = mapQuality(qualityId);
                if (registered.contains(quality)) continue;

                final JsonObject stream = findBestCodecStream(videoStreams, qualityId);
                if (stream != null) {
                    builder.quality(quality, URI.create(stream.get("baseUrl").getAsString()));
                    registered.add(quality);
                }
            }
        }
    }

    // AVC (H.264) > HEVC (H.265) > AV1
    private static JsonObject findBestCodecStream(final JsonArray streams, final int targetId) {
        JsonObject best = null;
        int bestScore = -1;

        for (int i = 0; i < streams.size(); i++) {
            final JsonObject stream = streams.get(i).getAsJsonObject();
            if (stream.get("id").getAsInt() != targetId) continue;

            final String codecs = stream.has("codecs") ? stream.get("codecs").getAsString() : "";
            final int score;
            if (codecs.contains("avc1")) score = 3;
            else if (codecs.contains("hev1")) score = 2;
            else if (codecs.contains("av01")) score = 1;
            else score = 0;

            if (score > bestScore) {
                best = stream;
                bestScore = score;
            }
        }
        return best;
    }

    private static MRL.Quality mapQuality(final int biliQuality) {
        return switch (biliQuality) {
            case 127 -> MRL.Quality.Q8K;
            case 126, 125, 120 -> MRL.Quality.HIGHEST;
            case 116, 112, 80 -> MRL.Quality.HIGH;
            case 74, 64 -> MRL.Quality.MEDIUM;
            case 32 -> MRL.Quality.LOW;
            case 16 -> MRL.Quality.LOWER;
            case 6 -> MRL.Quality.LOWEST;
            default -> MRL.Quality.UNKNOWN;
        };
    }

    // FETCH
    private static JsonObject fetchJson(final URI uri, final String dataKey) throws IOException {
        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET", "application/json");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Referer", REFERER);
        final String c = WaterMediaConfig.media.platforms.biliBiliCookie;
        if (c != null && !c.isEmpty()) {
            conn.setRequestProperty("Cookie", c);
        }

        try {
            NetTool.validateHTTP200(conn.getResponseCode(), uri);
            final String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (json.has("code") && json.get("code").getAsInt() != 0) {
                final String msg = json.has("message") ? json.get("message").getAsString() : "code " + json.get("code").getAsInt();
                throw new IOException("BiliBili API error: " + msg);
            }

            if (!json.has(dataKey) || json.get(dataKey).isJsonNull()) {
                throw new IOException("BiliBili API returned no '" + dataKey + "' field");
            }

            return json.getAsJsonObject(dataKey);
        } finally {
            conn.disconnect();
        }
    }

    private static String jsonString(final JsonObject obj, final String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static URI jsonUri(final JsonObject obj, final String key) {
        final String val = jsonString(obj, key);
        if (val == null || val.isEmpty()) return null;
        return URI.create(val.startsWith("//") ? "https:" + val : val);
    }

    private static URI resolveRedirect(final URI shortUri) throws IOException {
        final HttpURLConnection conn = NetTool.connectToHTTP(shortUri, "GET", "*/*");
        conn.setInstanceFollowRedirects(false);

        try {
            final int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM) {
                final String location = conn.getHeaderField("Location");
                if (location != null) return URI.create(location);
            }
            return shortUri;
        } finally {
            conn.disconnect();
        }
    }
}
