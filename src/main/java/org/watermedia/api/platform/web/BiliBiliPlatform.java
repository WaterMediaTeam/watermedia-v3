package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
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
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class BiliBiliPlatform implements IPlatform {
    public static final String NAME = "BiliBili";
    private static final Marker IT = MarkerManager.getMarker(BiliBiliPlatform.class.getSimpleName());
    private static final String REFERER = "https://www.bilibili.com/";

    /**
     * BiliBili rejects non-browser UAs and requires a bilibili.com Referer; CDN URLs
     * additionally need the user's session cookie when fetching premium qualities.
     */
    private static RequestHeaders headers() {
        final RequestHeaders h = new RequestHeaders()
                .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                .set("Accept", "*/*")
                .set("Referer", REFERER);
        final String c = WaterMediaConfig.platforms.biliBiliCookie;
        if (c != null && !c.isEmpty()) h.set("Cookie", c);
        return h;
    }

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
    private static final String[] HOSTS = { "www.bilibili.com", "bilibili.com", "live.bilibili.com", "b23.tv" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(URI uri) throws Exception {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        if ("b23.tv".equalsIgnoreCase(uri.getHost())) {
            uri = resolveRedirect(uri);
        }

        final String host = uri.getHost();
        if ("live.bilibili.com".equals(host)) {
            return this.resolveLive(uri);
        }

        final String path = uri.getPath();
        if (path != null && path.contains("/bangumi/play/")) {
            return this.resolveBangumi(uri);
        }

        return this.resolveVideo(uri);
    }

    // VIDEO
    private PlatformData resolveVideo(final URI uri) throws Exception {
        final String url = uri.toString();

        final Matcher bvidMatcher = BVID_PATTERN.matcher(url);
        if (!bvidMatcher.find()) throw new PlatformException(BiliBiliPlatform.class, "No BVID found in URL: " + uri);
        final String bvid = bvidMatcher.group(1);

        int page = 1;
        final Matcher pageMatcher = PAGE_PATTERN.matcher(url);
        if (pageMatcher.find()) page = Integer.parseInt(pageMatcher.group(1));
        LOGGER.debug(IT, "BiliBili resolving video bvid={} page={}", bvid, page);

        final JsonObject viewData = fetchJson(URI.create(String.format(VIDEO_VIEW_API, bvid)), "data");
        final String title = jsonString(viewData, "title");
        final JsonObject owner = viewData.has("owner") && viewData.get("owner").isJsonObject() ? viewData.getAsJsonObject("owner") : null;
        final String author = owner != null ? jsonString(owner, "name") : null;
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
            LOGGER.debug(IT, "BiliBili multi-part video ({} parts), selected part {} (cid={})", pages.size(), index + 1, cid);
        } else {
            cid = viewData.get("cid").getAsLong();
        }

        final JsonObject playData = fetchJson(URI.create(String.format(VIDEO_PLAYURL_API, bvid, cid)), "data");

        final String fullTitle = partName != null ? title + " - " + partName : title;
        final Metadata metadata = new Metadata(fullTitle, desc, publishedAt, duration, author);
        return this.buildResult(playData, metadata, thumbnail);
    }

    // BANGUMI
    private PlatformData resolveBangumi(final URI uri) throws Exception {
        final String url = uri.toString();

        String epId = null;
        String ssId = null;

        final Matcher epMatcher = EP_PATTERN.matcher(url);
        if (epMatcher.find()) epId = epMatcher.group(1);

        final Matcher ssMatcher = SS_PATTERN.matcher(url);
        if (ssMatcher.find()) ssId = ssMatcher.group(1);

        if (epId == null && ssId == null) throw new PlatformException(BiliBiliPlatform.class, "No ep/ss ID found in bangumi URL: " + uri);
        LOGGER.debug(IT, "BiliBili resolving bangumi epId={} ssId={}", epId, ssId);

        final String seasonApi = epId != null
                ? String.format(BANGUMI_SEASON_EP_API, epId)
                : String.format(BANGUMI_SEASON_SS_API, ssId);

        final JsonObject result = fetchJson(URI.create(seasonApi), "result");
        final String seasonTitle = result.get("title").getAsString();

        if (epId == null) {
            final JsonArray episodes = result.getAsJsonArray("episodes");
            if (episodes == null || episodes.isEmpty()) throw new PlatformException(BiliBiliPlatform.class, "No episodes found for season " + ssId);

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
        if (cid == null) throw new PlatformException(BiliBiliPlatform.class, "Episode ep" + epId + " not found in season");

        final JsonObject playResult = fetchJson(URI.create(String.format(BANGUMI_PLAYURL_API, epId, cid)), "result");

        if (playResult.has("is_preview") && playResult.get("is_preview").getAsInt() == 1) {
            throw new PlatformException(BiliBiliPlatform.class, "Content requires premium membership (VIP)");
        }

        final String fullTitle = epTitle != null ? seasonTitle + " - " + epTitle : seasonTitle;
        final String desc = jsonString(result, "evaluate");
        final URI thumbnail = epCover != null ? epCover : jsonUri(result, "cover");
        final Metadata metadata = new Metadata(fullTitle, desc, null, epDurationMs / 1000, null);
        return this.buildResult(playResult, metadata, thumbnail);
    }

    // LIVE
    private PlatformData resolveLive(final URI uri) throws Exception {
        final Matcher roomMatcher = LIVE_ROOM_PATTERN.matcher(uri.toString());
        if (!roomMatcher.find()) throw new PlatformException(BiliBiliPlatform.class, "No room ID found in live URL: " + uri);
        final String roomId = roomMatcher.group(1);
        LOGGER.debug(IT, "BiliBili resolving live room {}", roomId);

        final JsonObject playInfo = fetchJson(URI.create(String.format(LIVE_PLAY_INFO_API, roomId)), "data");
        if (playInfo.get("live_status").getAsInt() != 1) throw new PlatformException(BiliBiliPlatform.class, "Live room " + roomId + " is offline");
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
            LOGGER.warn(IT, "BiliBili failed to fetch live room title for room {}", realRoomId);
        }

        String author = null;
        if (uid > 0) {
            try {
                final JsonObject userInfo = fetchJson(URI.create(String.format(LIVE_USER_INFO_API, uid)), "data");
                if (userInfo.has("name") && !userInfo.get("name").isJsonNull()) {
                    author = userInfo.get("name").getAsString();
                }
            } catch (final Exception e) {
                LOGGER.warn(IT, "BiliBili failed to fetch author name for uid {}", uid);
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
        if (durl == null || durl.isEmpty()) throw new PlatformException(BiliBiliPlatform.class, "Live room " + realRoomId + " returned no stream URL (qn=" + bestQn + ")");

        final String streamUrl = durl.get(0).getAsJsonObject().get("url").getAsString();
        final Metadata metadata = new Metadata(title, null, null, 0, author);
        final var entry = new DataSource(MediaType.VIDEO, thumbnail, metadata, headers(),
                new DataQuality[] {new DataQuality(URI.create(streamUrl), 0, 0)},
                null, null);

        LOGGER.info(IT, "BiliBili resolved live room {} (qn={}, author='{}')", realRoomId, bestQn, author);
        return new PlatformData(Instant.now().plus(10, ChronoUnit.MINUTES), entry);
    }

    // DASH / DURL PARSING
    private PlatformData buildResult(final JsonObject data, final Metadata metadata, final URI thumbnail) throws PlatformException {
        if (data.has("dash") && !data.get("dash").isJsonNull()) {
            final JsonObject dash = data.getAsJsonObject("dash");
            final JsonArray videoStreams = dash.has("video") ? dash.getAsJsonArray("video") : null;
            final JsonArray audioStreams = dash.has("audio") ? dash.getAsJsonArray("audio") : null;

            if (videoStreams != null && !videoStreams.isEmpty()) {
                final JsonArray supportFormats = data.getAsJsonArray("support_formats");
                final List<DataQuality> variants = parseDashVideo(videoStreams, supportFormats);
                LOGGER.debug(IT, "BiliBili DASH: {} raw video stream(s), {} audio stream(s) -> {} variant(s)",
                        videoStreams.size(), audioStreams != null ? audioStreams.size() : 0, variants.size());

                List<DataSlave> audioSlaves = null;
                if (audioStreams != null && !audioStreams.isEmpty()) {
                    audioSlaves = List.of(new DataSlave(null, null, URI.create(audioStreams.get(0).getAsJsonObject().get("baseUrl").getAsString())));
                } else {
                    LOGGER.warn(IT, "BiliBili DASH '{}' has no audio stream; video will play muted", metadata.title());
                }

                LOGGER.info(IT, "BiliBili resolved '{}' with {} variant(s) (DASH)", metadata.title(), variants.size());
                return new PlatformData(Instant.now().plus(90, ChronoUnit.MINUTES),
                        new DataSource(MediaType.VIDEO, thumbnail, metadata, headers(),
                                variants.toArray(DataQuality[]::new), audioSlaves, null));
            }
        }

        if (data.has("durl")) {
            final JsonArray durl = data.getAsJsonArray("durl");
            if (durl != null && !durl.isEmpty()) {
                // FLV/MP4 fallback: no per-rendition dimensions, let FFMediaPlayer probe.
                final DataQuality flat = new DataQuality(
                        URI.create(durl.get(0).getAsJsonObject().get("url").getAsString()), 0, 0);
                LOGGER.info(IT, "BiliBili resolved '{}' with a single muxed stream (DURL fallback)", metadata.title());
                return new PlatformData(Instant.now().plus(90, ChronoUnit.MINUTES),
                        new DataSource(MediaType.VIDEO, thumbnail, metadata, headers(),
                                new DataQuality[] { flat }, null, null));
            }
        }

        throw new PlatformException(BiliBiliPlatform.class, "API response carries no playable streams (neither DASH nor DURL) for '" + metadata.title() + "'");
    }

    /**
     * Builds the variant list directly from the DASH response. When BiliBili
     * reports {@code support_formats} (the bucket index) we walk it once and
     * pick the best codec stream per bucket — no duplicates by construction.
     * Without it, every stream is emitted as-is and MRL collapses overlaps
     * when bucketing dimensions into qualities.
     * <p>
     * Width/height from the stream itself win when present; we fall back to
     * the nominal BiliBili height (see {@link #biliHeight(int)}).
     */
    private static List<DataQuality> parseDashVideo(final JsonArray videoStreams, final JsonArray supportFormats) {
        final List<DataQuality> out = new ArrayList<>();

        if (supportFormats != null && !supportFormats.isEmpty()) {
            for (int i = 0; i < supportFormats.size(); i++) {
                final int qualityId = supportFormats.get(i).getAsJsonObject().get("quality").getAsInt();
                final JsonObject stream = findBestCodecStream(videoStreams, qualityId);
                if (stream != null) out.add(toVariant(stream, qualityId));
            }
        } else {
            for (int i = 0; i < videoStreams.size(); i++) {
                final JsonObject stream = videoStreams.get(i).getAsJsonObject();
                final int qualityId = stream.has("id") ? stream.get("id").getAsInt() : 0;
                out.add(toVariant(stream, qualityId));
            }
        }
        return out;
    }

    private static DataQuality toVariant(final JsonObject stream, final int qualityId) {
        final int w = stream.has("width") && !stream.get("width").isJsonNull() ? stream.get("width").getAsInt() : 0;
        int h = stream.has("height") && !stream.get("height").isJsonNull() ? stream.get("height").getAsInt() : 0;
        if (h <= 0) h = biliHeight(qualityId);
        return new DataQuality(URI.create(stream.get("baseUrl").getAsString()), w, h);
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

    /**
     * Nominal vertical resolution for a BiliBili {@code quality} id, used only
     * as a fallback when the DASH stream omits its own {@code height}. These
     * are BiliBili's documented buckets, not MRL's enum.
     */
    private static int biliHeight(final int biliQuality) {
        return switch (biliQuality) {
            case 127 -> 4320;            // 8K
            case 126, 125, 120 -> 2160;  // 4K HDR / Dolby / 4K
            case 116, 112, 80 -> 1080;   // 1080P60 / 1080P+ / 1080P
            case 74, 64 -> 720;          // 720P60 / 720P
            case 32 -> 480;
            case 16 -> 360;
            case 6 -> 240;
            default -> 0;
        };
    }

    // FETCH
    private static JsonObject fetchJson(final URI uri, final String dataKey) throws IOException {
        final NetRequest.Builder builder = NetRequest.create(uri).method("GET").accept("application/json")
                .userAgent(NetRequest.UserAgent.GENERIC)
                .referer(REFERER);
        final String c = WaterMediaConfig.platforms.biliBiliCookie;
        if (c != null && !c.isEmpty()) builder.header("Cookie", c);

        try (final NetRequest req = builder.send()) {
            if (req.statusCode() != 200) throw new PlatformException(BiliBiliPlatform.class, "HTTP " + req.statusCode() + " for " + uri);
            final JsonObject json = JsonParser.parseString(req.readAllAsString()).getAsJsonObject();

            if (json.has("code") && json.get("code").getAsInt() != 0) {
                final String msg = json.has("message") ? json.get("message").getAsString() : "code " + json.get("code").getAsInt();
                throw new PlatformException(BiliBiliPlatform.class, "API error: " + msg);
            }

            if (!json.has(dataKey) || json.get(dataKey).isJsonNull()) {
                throw new PlatformException(BiliBiliPlatform.class, "API returned no '" + dataKey + "' field");
            }

            return json.getAsJsonObject(dataKey);
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
        try (final NetRequest req = NetRequest.create(shortUri).method("GET").accept("*/*").maxRedirects(0).send()) {
            final String location = req.header("Location");
            return location != null ? URI.create(location) : shortUri;
        }
    }
}
