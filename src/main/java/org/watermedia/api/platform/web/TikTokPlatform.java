package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class TikTokPlatform implements IPlatform {
    private static final Marker IT = MarkerManager.getMarker(TikTokPlatform.class.getSimpleName());
    private static final String TIKTOK_REFERER = "https://www.tiktok.com/";

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/video/(\\d+)");
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("/embed(?:/v2)?/(\\d+)");
    private static final Pattern UNIVERSAL_DATA_PATTERN = Pattern.compile("<script[^>]+id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>\\s*(\\{.*?})\\s*</script>", Pattern.DOTALL);
    private static final Pattern URL_KEY_PATTERN = Pattern.compile("v[^_]+_([^_]+)_(\\d+)p_(\\d+)");

    @Override
    public String name() { return "TikTok"; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (
                host.equals("www.tiktok.com")
                || host.equals("tiktok.com")
                || host.equals("vm.tiktok.com")
                || host.equals("vt.tiktok.com")
                || host.equals("m.tiktok.com")
        );
    }

    @Override
    public PlatformData getData(URI uri) throws Exception {
        final String host = uri.getHost();
        if ("vm.tiktok.com".equals(host) || "vt.tiktok.com".equals(host)) {
            uri = resolveRedirect(uri);
        }

        final String path = uri.getPath();
        if (path != null && path.startsWith("/t/")) {
            uri = resolveRedirect(uri);
        }

        String videoId = null;
        Matcher matcher = VIDEO_ID_PATTERN.matcher(uri.toString());
        if (matcher.find()) {
            videoId = matcher.group(1);
        } else {
            matcher = EMBED_ID_PATTERN.matcher(uri.toString());
            if (matcher.find()) {
                videoId = matcher.group(1);
            }
        }

        if (videoId == null) {
            throw new IllegalArgumentException("No video ID found in TikTok URL: " + uri);
        }

        String html = fetchWebpage(uri, null);
        JsonObject videoData = extractVideoData(html, videoId);

        if (videoData == null && html.contains("Please wait...")) {
            LOGGER.debug(IT, "TikTok WAF challenge detected for video {}, solving...", videoId);
            final String challengeCookies = solveChallenge(html);
            html = fetchWebpage(uri, challengeCookies);
            videoData = extractVideoData(html, videoId);
        }

        if (videoData == null) {
            throw new IllegalStateException("No video data found in TikTok page for video " + videoId);
        }

        return buildResult(videoData, videoId);
    }

    // WAF CHALLENGE SOLVER (ported from yt-dlp _solve_challenge_and_set_cookies)
    private static String solveChallenge(final String html) throws Exception {
        final String csData = extractElementClass(html, "cs");
        if (csData == null || csData.isEmpty()) {
            throw new IllegalStateException("Unable to extract TikTok WAF challenge data");
        }

        final JsonObject challengeData = JsonParser.parseString(
                new String(base64Decode(csData), StandardCharsets.UTF_8)
        ).getAsJsonObject();

        final JsonObject v = challengeData.getAsJsonObject("v");
        final byte[] seed = Base64.getDecoder().decode(v.get("a").getAsString());
        final byte[] expectedDigest = Base64.getDecoder().decode(v.get("c").getAsString());

        final MessageDigest baseDigest = MessageDigest.getInstance("SHA-256");
        baseDigest.update(seed);

        boolean solved = false;
        for (int i = 0; i <= 1_000_000; i++) {
            final MessageDigest testDigest = (MessageDigest) baseDigest.clone();
            testDigest.update(String.valueOf(i).getBytes(StandardCharsets.UTF_8));
            if (MessageDigest.isEqual(testDigest.digest(), expectedDigest)) {
                challengeData.addProperty("d",
                        Base64.getEncoder().encodeToString(String.valueOf(i).getBytes(StandardCharsets.UTF_8)));
                solved = true;
                LOGGER.debug(IT, "TikTok WAF challenge solved at iteration {}", i);
                break;
            }
        }

        if (!solved) {
            throw new IllegalStateException("Unable to solve TikTok WAF challenge");
        }

        final String cookieValue = Base64.getEncoder().encodeToString(
                challengeData.toString().getBytes(StandardCharsets.UTF_8));

        String cookieName = extractElementClass(html, "wci");
        if (cookieName == null || cookieName.isEmpty()) cookieName = "_wafchallengeid";

        final StringBuilder cookies = new StringBuilder();
        cookies.append(cookieName).append('=').append(cookieValue);

        final String rciName = extractElementClass(html, "rci");
        final String rciValue = extractElementClass(html, "rs");
        if (rciName != null && !rciName.isEmpty() && rciValue != null && !rciValue.isEmpty()) {
            cookies.append("; ").append(rciName).append('=').append(rciValue);
        }

        return cookies.toString();
    }

    private static String extractElementClass(final String html, final String id) {
        final Matcher tagMatcher = Pattern.compile(
                "<p\\s([^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*)>"
        ).matcher(html);
        if (!tagMatcher.find()) return null;

        final Matcher classMatcher = Pattern.compile("\\bclass=\"([^\"]+)\"").matcher(tagMatcher.group(1));
        return classMatcher.find() ? classMatcher.group(1) : null;
    }

    private static byte[] base64Decode(final String input) {
        final String stripped = input.replaceAll("=+$", "");
        final int pad = (4 - stripped.length() % 4) % 4;
        return Base64.getDecoder().decode(stripped + "=".repeat(pad));
    }

    // RESULT BUILDING
    private PlatformData buildResult(final JsonObject awemeDetail, final String videoId) {
        final JsonObject video = awemeDetail.getAsJsonObject("video");
        if (video == null) {
            throw new IllegalStateException("No video object found for TikTok video " + videoId);
        }

        final String desc = jsonString(awemeDetail, "desc");
        final String title = (desc != null && !desc.isEmpty()) ? desc : "TikTok video #" + videoId;

        final Instant publishedAt = awemeDetail.has("createTime") && !awemeDetail.get("createTime").isJsonNull()
                ? Instant.ofEpochSecond(awemeDetail.get("createTime").getAsLong())
                : null;

        long duration = 0;
        if (video.has("duration") && !video.get("duration").isJsonNull() && video.get("duration").getAsInt() > 0) {
            duration = video.get("duration").getAsLong();
        }

        final URI thumbnail = findThumbnail(awemeDetail, video);
        final String author = extractAuthor(awemeDetail);

        final Metadata metadata = new Metadata(title, desc, publishedAt, duration, author);
        final List<DataQuality> variants = new ArrayList<>();

        if (video.has("bitrateInfo") && video.get("bitrateInfo").isJsonArray()) {
            parseBitrateInfo(variants, video.getAsJsonArray("bitrateInfo"), video);
        }

        if (variants.isEmpty()) {
            URI fallback = extractAddrUri(video, "playAddr");
            if (fallback == null) fallback = extractAddrUri(video, "downloadAddr");
            if (fallback == null) {
                throw new IllegalStateException("No playable video URLs found for TikTok video " + videoId);
            }
            // No reliable dimensions on this fallback — let FFMediaPlayer probe and re-bucket.
            variants.add(new DataQuality(fallback, 0, 0));
        }

        return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES),
                new DataSource(MediaType.VIDEO, thumbnail, metadata,
                        cdnHeaders(), variants.toArray(DataQuality[]::new), null, null));
    }

    /**
     * TikTok CDN URLs are signed but reject requests without a browser UA, the original
     * page Referer, and the WAF cookies captured during the HTML scrape. FFmpeg replays
     * these on every segment fetch, so we stamp them onto the {@link DataSource}.
     */
    private static RequestHeaders cdnHeaders() {
        final RequestHeaders h = new RequestHeaders()
                .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                .set("Accept", "*/*")
                .set("Referer", TIKTOK_REFERER);
        final String c = cdnCookies;
        if (c != null && !c.isEmpty()) h.set("Cookie", c);
        return h;
    }

    // FORMAT PARSING — picks the real dimensions reported by PlayAddr.Width/Height
    // first; falls back to the height encoded in UrlKey (e.g. "..._720p_...") when
    // PlayAddr omits them; finally to the parent video's width/height. MRL handles
    // bucket mapping — we do not pre-collapse here.
    private void parseBitrateInfo(final List<DataQuality> out, final JsonArray bitrateInfos, final JsonObject video) {
        final int playWidth = video.has("width") ? video.get("width").getAsInt() : 0;
        final int playHeight = video.has("height") ? video.get("height").getAsInt() : 0;

        for (int i = 0; i < bitrateInfos.size(); i++) {
            final JsonObject info = bitrateInfos.get(i).getAsJsonObject();
            if (!info.has("PlayAddr") || !info.get("PlayAddr").isJsonObject()) continue;

            final JsonObject playAddr = info.getAsJsonObject("PlayAddr");
            final URI url = pickBestUrl(playAddr);
            if (url == null) continue;

            final String urlKey = jsonString(playAddr, "UrlKey");
            if (urlKey != null) {
                final Matcher keyMatcher = URL_KEY_PATTERN.matcher(urlKey);
                if (keyMatcher.find() && "bytevc2".equals(keyMatcher.group(1))) continue;
            }

            int w = playAddr.has("Width") ? playAddr.get("Width").getAsInt() : 0;
            int h = playAddr.has("Height") ? playAddr.get("Height").getAsInt() : 0;

            if (h <= 0 && urlKey != null) {
                final Matcher keyMatcher = URL_KEY_PATTERN.matcher(urlKey);
                if (keyMatcher.find()) {
                    try { h = Integer.parseInt(keyMatcher.group(2)); }
                    catch (final NumberFormatException ignored) {}
                }
            }
            if (w <= 0 && h <= 0) { w = playWidth; h = playHeight; }

            out.add(new DataQuality(url, w, h));
        }
    }

    private static URI pickBestUrl(final JsonObject playAddr) {
        if (!playAddr.has("UrlList") || !playAddr.get("UrlList").isJsonArray()) return null;

        final JsonArray urlList = playAddr.getAsJsonArray("UrlList");
        URI fallback = null;

        for (int i = 0; i < urlList.size(); i++) {
            final String urlStr = urlList.get(i).getAsString();
            if (urlStr == null || urlStr.isEmpty()) continue;
            try {
                final URI candidate = URI.create(urlStr.startsWith("//") ? "https:" + urlStr : urlStr);
                if (!"www.tiktok.com".equals(candidate.getHost())) {
                    return candidate;
                }
                if (fallback == null) fallback = candidate;
            } catch (final Exception ignored) {}
        }

        return fallback;
    }

    private static URI extractAddrUri(final JsonObject video, final String key) {
        if (!video.has(key) || video.get(key).isJsonNull()) return null;

        final JsonElement element = video.get(key);

        if (element.isJsonPrimitive()) {
            return toUri(element.getAsString());
        }

        if (element.isJsonArray()) {
            for (final JsonElement item: element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    final URI src = toUri(jsonString(item.getAsJsonObject(), "src"));
                    if (src != null) return src;
                }
            }
            return null;
        }

        if (element.isJsonObject()) {
            final JsonObject obj = element.getAsJsonObject();
            if (obj.has("UrlList") && obj.get("UrlList").isJsonArray()) {
                final JsonArray urls = obj.getAsJsonArray("UrlList");
                for (int i = 0; i < urls.size(); i++) {
                    final URI u = toUri(urls.get(i).getAsString());
                    if (u != null) return u;
                }
            }
            final URI src = toUri(jsonString(obj, "src"));
            if (src != null) return src;
        }

        return null;
    }

    // METADATA EXTRACTION
    private static URI findThumbnail(final JsonObject awemeDetail, final JsonObject video) {
        for (final String key: new String[]{"originCover", "cover", "thumbnail"}) {
            URI uri = jsonUri(video, key);
            if (uri != null) return uri;
            uri = jsonUri(awemeDetail, key);
            if (uri != null) return uri;
        }
        return null;
    }

    private static String extractAuthor(final JsonObject awemeDetail) {
        for (final String key: new String[]{"author", "authorInfo"}) {
            if (awemeDetail.has(key) && awemeDetail.get(key).isJsonObject()) {
                final JsonObject obj = awemeDetail.getAsJsonObject(key);
                final String nickname = jsonString(obj, "nickname");
                if (nickname != null) return nickname;
                final String uniqueId = jsonString(obj, "uniqueId");
                if (uniqueId != null) return uniqueId;
            }
        }
        return null;
    }

    // DATA EXTRACTION
    private JsonObject extractVideoData(final String html, final String videoId) {
        final Matcher matcher = UNIVERSAL_DATA_PATTERN.matcher(html);
        if (!matcher.find()) {
            LOGGER.warn(IT, "No __UNIVERSAL_DATA_FOR_REHYDRATION__ found for TikTok video {}", videoId);
            return null;
        }

        final JsonObject root = JsonParser.parseString(matcher.group(1)).getAsJsonObject();
        if (!root.has("__DEFAULT_SCOPE__") || !root.get("__DEFAULT_SCOPE__").isJsonObject()) return null;

        final JsonObject defaultScope = root.getAsJsonObject("__DEFAULT_SCOPE__");
        if (!defaultScope.has("webapp.video-detail") || !defaultScope.get("webapp.video-detail").isJsonObject()) return null;

        final JsonObject videoDetail = defaultScope.getAsJsonObject("webapp.video-detail");

        if (videoDetail.has("statusCode") && videoDetail.get("statusCode").getAsInt() != 0) {
            final int status = videoDetail.get("statusCode").getAsInt();
            if (status == 10216 || status == 10222) {
                throw new IllegalStateException("TikTok video " + videoId + " is private or requires login");
            }
            if (status == 10204) {
                throw new IllegalStateException("IP address is blocked from accessing TikTok video " + videoId);
            }
            LOGGER.warn(IT, "TikTok video {} returned status code {}", videoId, status);
        }

        if (!videoDetail.has("itemInfo") || !videoDetail.get("itemInfo").isJsonObject()) return null;
        final JsonObject itemInfo = videoDetail.getAsJsonObject("itemInfo");
        if (!itemInfo.has("itemStruct") || !itemInfo.get("itemStruct").isJsonObject()) return null;

        return itemInfo.getAsJsonObject("itemStruct");
    }

    /** Captured WAF cookies — needed alongside the browser UA when streaming the CDN URL. */
    private static volatile String cdnCookies;

    // HTTP
    private static String fetchWebpage(final URI uri, final String cookies) throws IOException {
        final NetRequest.Builder builder = NetRequest.create(uri)
                .method("GET")
                .accept("text/html,application/xhtml+xml,*/*")
                .userAgent(NetRequest.UserAgent.GENERIC)
                .referer(TIKTOK_REFERER)
                .header("Accept-Language", "en-US,en;q=0.9");
        if (cookies != null) builder.header("Cookie", cookies);

        try (final NetRequest req = builder.send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);
            captureCookies(req);
            return req.readAllAsString();
        }
    }

    private static void captureCookies(final NetRequest req) {
        final List<String> setCookies = req.responseHeaders().getAll("Set-Cookie");
        if (setCookies == null || setCookies.isEmpty()) return;

        final StringBuilder sb = new StringBuilder();
        for (final String header: setCookies) {
            final String nameValue = header.split(";", 2)[0].trim();
            if (nameValue.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(nameValue);
        }
        if (!sb.isEmpty()) {
            cdnCookies = sb.toString();
            LOGGER.debug(IT, "Captured TikTok cookies: {}", cdnCookies);
        }
    }

    private static URI resolveRedirect(final URI shortUri) throws IOException {
        try (final NetRequest req = NetRequest.create(shortUri)
                .method("GET")
                .accept("*/*")
                .userAgent(NetRequest.UserAgent.GENERIC)
                .maxRedirects(0)
                .send()) {
            final String location = req.header("Location");
            return location != null ? URI.create(location) : shortUri;
        }
    }

    // UTILS
    private static URI toUri(final String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            return URI.create(url.startsWith("//") ? "https:" + url : url);
        } catch (final Exception e) {
            return null;
        }
    }

    private static String jsonString(final JsonObject obj, final String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static URI jsonUri(final JsonObject obj, final String key) {
        final String val = jsonString(obj, key);
        if (val == null || val.isEmpty()) return null;
        return toUri(val);
    }
}
