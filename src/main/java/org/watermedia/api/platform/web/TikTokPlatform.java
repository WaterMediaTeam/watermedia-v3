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
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.*;

public final class TikTokPlatform implements Platform {
    public static final String NAME = "TikTok";
    private static final Marker IT = MarkerManager.getMarker(TikTokPlatform.class.getSimpleName());
    private static final String TIKTOK_REFERER = "https://www.tiktok.com/";

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/video/(\\d+)");
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("/embed(?:/v2)?/(\\d+)");
    private static final Pattern UNIVERSAL_DATA_PATTERN = Pattern.compile("<script[^>]+id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>\\s*(\\{.*?})\\s*</script>", Pattern.DOTALL);
    private static final Pattern URL_KEY_PATTERN = Pattern.compile("v[^_]+_([^_]+)_(\\d+)p_(\\d+)");
    private static final String[] HOSTS = { "www.tiktok.com", "tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "m.tiktok.com" };
    private static final String[] SHORT_HOSTS = { "vm.tiktok.com", "vt.tiktok.com" };
    private static final String WAF_MARKER = "Please wait...";
    // THE CHALLENGE IS A PROOF OF WORK; THE INTERRUPT FLAG IS CHECKED EVERY FEW THOUSAND HASHES
    private static final int CHALLENGE_ATTEMPTS = 1_000_000;
    private static final int CHALLENGE_CHECK_MASK = 0x3FFF;
    // PLAY URLS ARE SIGNED; WHEN THEY CARRY NO expire PARAMETER THIS IS THE FALLBACK
    private static final Duration TTL = Duration.ofMinutes(30);

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI link) throws PlatformException {
        final String host = link.getHost();
        if (!DataTool.equalsAnyIgnoreCase(host, HOSTS)) return null;

        try {
            // SHORT LINKS (vm/vt HOSTS OR /t/ PATHS) REDIRECT TO THE CANONICAL VIDEO PAGE
            final URI shortLink = DataTool.equalsAnyIgnoreCase(host, SHORT_HOSTS) ? resolveRedirect(link) : link;
            final String path = shortLink.getPath();
            final URI uri = path != null && path.startsWith("/t/") ? resolveRedirect(shortLink) : shortLink;

            Matcher matcher = VIDEO_ID_PATTERN.matcher(uri.toString());
            if (!matcher.find()) matcher = EMBED_ID_PATTERN.matcher(uri.toString());
            if (!matcher.find()) throw PlatformException.unsupported(NAME, uri);
            final String videoId = matcher.group(1);

            // THREAD THE CAPTURED WAF COOKIES THROUGH INSTEAD OF A STATIC FIELD — CONCURRENT RESOLUTIONS
            // ON THE SEARCH POOL WOULD OTHERWISE STAMP EACH OTHER'S COOKIES ONTO THEIR SOURCES.
            String cookies = null;
            Webpage page = fetchWebpage(uri, null);
            if (page.cookies() != null) cookies = page.cookies();
            JsonObject videoData = this.extractVideoData(page.html(), videoId);

            if (videoData == null && page.html().contains(WAF_MARKER)) {
                LOGGER.debug(IT, "TikTok WAF challenge detected for video {}, solving...", videoId);
                final String challengeCookies = solveChallenge(page.html());
                page = fetchWebpage(uri, challengeCookies);
                if (page.cookies() != null) cookies = page.cookies();
                videoData = this.extractVideoData(page.html(), videoId);
            }

            if (videoData == null) throw new PlatformException(NAME, "No video data found in page for video " + videoId);

            return this.buildResult(videoData, videoId, cookies);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + link, e);
        }
    }

    // WAF CHALLENGE SOLVER (PORTED FROM yt-dlp _solve_challenge_and_set_cookies)
    private static String solveChallenge(final String html) throws PlatformException {
        final String csData = extractElementClass(html, "cs");
        if (csData == null || csData.isEmpty()) throw new PlatformException(NAME, "Unable to extract WAF challenge data");

        final JsonObject challengeData = JsonParser.parseString(
                new String(base64Decode(csData), StandardCharsets.UTF_8)
        ).getAsJsonObject();

        final JsonObject v = challengeData.getAsJsonObject("v");
        final byte[] seed = Base64.getDecoder().decode(v.get("a").getAsString());
        final byte[] expectedDigest = Base64.getDecoder().decode(v.get("c").getAsString());

        boolean solved = false;
        try {
            final MessageDigest baseDigest = MessageDigest.getInstance("SHA-256");
            baseDigest.update(seed);
            for (int i = 0; i <= CHALLENGE_ATTEMPTS; i++) {
                if ((i & CHALLENGE_CHECK_MASK) == 0 && Thread.currentThread().isInterrupted())
                    throw new PlatformException(NAME, "Interrupted while solving the WAF challenge");
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
        } catch (final NoSuchAlgorithmException | CloneNotSupportedException e) { // SHA-256 IS MANDATED BY THE JVM SPEC
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }

        if (!solved) throw new PlatformException(NAME, "Unable to solve WAF challenge");

        final String cookieValue = Base64.getEncoder().encodeToString(
                challengeData.toString().getBytes(StandardCharsets.UTF_8));

        String cookieName = extractElementClass(html, "wci");
        if (cookieName == null || cookieName.isEmpty()) cookieName = "_wafchallengeid";

        final StringBuilder cookies = new StringBuilder();
        cookies.append(cookieName).append('=').append(cookieValue);

        final String rciName = extractElementClass(html, "rci");
        final String rciValue = extractElementClass(html, "rs");
        if (rciName != null && !rciName.isEmpty() && rciValue != null && !rciValue.isEmpty())
            cookies.append("; ").append(rciName).append('=').append(rciValue);

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
    private Resolution buildResult(final JsonObject awemeDetail, final String videoId, final String cookies) throws PlatformException {
        final JsonObject video = awemeDetail.getAsJsonObject("video");
        if (video == null) throw new PlatformException(NAME, "No video object found for video " + videoId);

        final String desc = str(awemeDetail, "desc");
        final String title = (desc != null && !desc.isEmpty()) ? desc : "TikTok video #" + videoId;

        final Instant publishedAt = awemeDetail.has("createTime") && !awemeDetail.get("createTime").isJsonNull()
                ? Instant.ofEpochSecond(awemeDetail.get("createTime").getAsLong())
                : null;

        // TIKTOK REPORTS video duration IN SECONDS; Metadata IS MILLISECONDS
        final long durationMs = Math.max(0L, intOr(video, "duration", 0)) * 1000L;

        final URI thumbnail = findThumbnail(awemeDetail, video);
        final String author = extractAuthor(awemeDetail);

        final Metadata metadata = new Metadata(title, desc, publishedAt, durationMs, author);
        final List<Quality> variants = new ArrayList<>();

        if (video.has("bitrateInfo") && video.get("bitrateInfo").isJsonArray()) this.parseBitrateInfo(variants, video.getAsJsonArray("bitrateInfo"), video);

        if (variants.isEmpty()) {
            URI fallback = extractAddrUri(video, "playAddr");
            if (fallback == null) fallback = extractAddrUri(video, "downloadAddr");
            if (fallback == null)
                throw new PlatformException(NAME, "Video '" + videoId + "' exposed no playable URLs (bitrateInfo, playAddr, and downloadAddr all empty)");
            // NO RELIABLE DIMENSIONS ON THIS FALLBACK, FFMediaPlayer PROBES AND RE-BUCKETS IT
            LOGGER.debug(IT, "TikTok video '{}' had no usable bitrateInfo, falling back to {}", videoId, fallback);
            variants.add(new Quality(fallback, 0, 0));
        }

        if (author == null) LOGGER.warn(IT, "TikTok video '{}' has no resolvable author", videoId);
        LOGGER.debug(IT, "TikTok resolved video '{}' with {} variant(s)", videoId, variants.size());
        final Instant signed = DataTool.expiry(variants.get(0).uri());
        return new Resolution(signed != null ? signed : Instant.now().plus(TTL),
                new Source(MediaType.VIDEO, thumbnail, metadata,
                        cdnHeaders(cookies), variants, null, null));
    }

    // TIKTOK CDN URLS ARE SIGNED BUT REJECT REQUESTS WITHOUT A BROWSER UA, THE ORIGINAL PAGE REFERER,
    // AND THE WAF COOKIES CAPTURED DURING THE HTML SCRAPE. FFMPEG REPLAYS THESE ON EVERY SEGMENT FETCH.
    private static HttpHeaders cdnHeaders(final String cookies) {
        final HttpHeaders h = new HttpHeaders()
                .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                .set("Accept", NetRequest.ACCEPT_ANY)
                .set("Referer", TIKTOK_REFERER);
        if (cookies != null && !cookies.isEmpty()) h.set("Cookie", cookies);
        return h;
    }

    // FORMAT PARSING: PlayAddr.Width/Height FIRST, THEN THE HEIGHT ENCODED IN UrlKey ("..._720p_..."),
    // FINALLY THE PARENT VIDEO'S SIZE; MRL MAPS THE BUCKETS, NOTHING IS PRE-COLLAPSED HERE
    private void parseBitrateInfo(final List<Quality> out, final JsonArray bitrateInfos, final JsonObject video) {
        final int playWidth = Math.max(0, intOr(video, "width", 0));
        final int playHeight = Math.max(0, intOr(video, "height", 0));

        for (int i = 0; i < bitrateInfos.size(); i++) {
            final JsonObject info = bitrateInfos.get(i).getAsJsonObject();
            if (!info.has("PlayAddr") || !info.get("PlayAddr").isJsonObject()) continue;

            final JsonObject playAddr = info.getAsJsonObject("PlayAddr");
            final URI url = pickBestUrl(playAddr);
            if (url == null) continue;

            final String urlKey = str(playAddr, "UrlKey");
            if (urlKey != null) {
                final Matcher keyMatcher = URL_KEY_PATTERN.matcher(urlKey);
                if (keyMatcher.find() && "bytevc2".equals(keyMatcher.group(1))) continue;
            }

            int w = Math.max(0, intOr(playAddr, "Width", 0));
            int h = Math.max(0, intOr(playAddr, "Height", 0));

            if (h <= 0 && urlKey != null) {
                final Matcher keyMatcher = URL_KEY_PATTERN.matcher(urlKey);
                if (keyMatcher.find()) h = Math.max(0, DataTool.parseInt(keyMatcher.group(2), 0));
            }
            if (w <= 0 && h <= 0) { w = playWidth; h = playHeight; }

            out.add(new Quality(url, w, h));
        }
        LOGGER.debug(IT, "TikTok parsed {} variant(s) from {} bitrateInfo entry(es)", out.size(), bitrateInfos.size());
    }

    private static URI pickBestUrl(final JsonObject playAddr) {
        if (!playAddr.has("UrlList") || !playAddr.get("UrlList").isJsonArray()) return null;

        final JsonArray urlList = playAddr.getAsJsonArray("UrlList");
        URI fallback = null;

        for (int i = 0; i < urlList.size(); i++) {
            final URI candidate = JSONTool.uri(urlList.get(i).getAsString());
            if (candidate == null) continue;
            if (!"www.tiktok.com".equalsIgnoreCase(candidate.getHost())) return candidate;
            if (fallback == null) fallback = candidate;
        }

        return fallback;
    }

    private static URI extractAddrUri(final JsonObject video, final String key) {
        if (!video.has(key) || video.get(key).isJsonNull()) return null;

        final JsonElement element = video.get(key);

        if (element.isJsonPrimitive()) return uri(element.getAsString());

        if (element.isJsonArray()) {
            for (final JsonElement item: element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    final URI src = uri(str(item.getAsJsonObject(), "src"));
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
                    final URI u = uri(urls.get(i).getAsString());
                    if (u != null) return u;
                }
            }
            return uri(str(obj, "src"));
        }

        return null;
    }

    // METADATA EXTRACTION
    private static URI findThumbnail(final JsonObject awemeDetail, final JsonObject video) {
        for (final String key: new String[]{"originCover", "cover", "thumbnail"}) {
            URI uri = JSONTool.uri(video, key);
            if (uri != null) return uri;
            uri = JSONTool.uri(awemeDetail, key);
            if (uri != null) return uri;
        }
        return null;
    }

    private static String extractAuthor(final JsonObject awemeDetail) {
        for (final String key: new String[]{"author", "authorInfo"}) {
            if (awemeDetail.has(key) && awemeDetail.get(key).isJsonObject()) {
                final JsonObject obj = awemeDetail.getAsJsonObject(key);
                final String nickname = str(obj, "nickname");
                if (nickname != null) return nickname;
                final String uniqueId = str(obj, "uniqueId");
                if (uniqueId != null) return uniqueId;
            }
        }
        return null;
    }

    // DATA EXTRACTION: null MEANS THE PAGE CARRIED NO VIDEO DATA, WHICH IS NORMAL FOR THE FIRST FETCH OF A
    // WAF-CHALLENGED REQUEST; THE CALLER DECIDES WHETHER TO SOLVE THE CHALLENGE OR FAIL
    private JsonObject extractVideoData(final String html, final String videoId) throws PlatformException {
        final Matcher matcher = UNIVERSAL_DATA_PATTERN.matcher(html);
        if (!matcher.find()) {
            LOGGER.debug(IT, "No __UNIVERSAL_DATA_FOR_REHYDRATION__ found for TikTok video {}", videoId);
            return null;
        }

        final JsonObject root = JsonParser.parseString(matcher.group(1)).getAsJsonObject();
        if (!root.has("__DEFAULT_SCOPE__") || !root.get("__DEFAULT_SCOPE__").isJsonObject()) return null;

        final JsonObject defaultScope = root.getAsJsonObject("__DEFAULT_SCOPE__");
        if (!defaultScope.has("webapp.video-detail") || !defaultScope.get("webapp.video-detail").isJsonObject()) return null;

        final JsonObject videoDetail = defaultScope.getAsJsonObject("webapp.video-detail");

        if (videoDetail.has("statusCode") && videoDetail.get("statusCode").getAsInt() != 0) {
            final int status = videoDetail.get("statusCode").getAsInt();
            if (status == 10_216 || status == 10_222) throw new PlatformException(NAME, "Video " + videoId + " is private or requires login");
            if (status == 10_204) throw new PlatformException(NAME, "IP address is blocked from accessing video " + videoId);
            LOGGER.warn(IT, "TikTok video {} returned status code {}", videoId, status);
        }

        if (!videoDetail.has("itemInfo") || !videoDetail.get("itemInfo").isJsonObject()) return null;
        final JsonObject itemInfo = videoDetail.getAsJsonObject("itemInfo");
        if (!itemInfo.has("itemStruct") || !itemInfo.get("itemStruct").isJsonObject()) return null;

        return itemInfo.getAsJsonObject("itemStruct");
    }

    // FETCHED PAGE HTML PAIRED WITH THE WAF COOKIES CAPTURED FROM ITS Set-Cookie RESPONSE (null WHEN NONE)
    private record Webpage(String html, String cookies) {}

    // HTTP
    private static Webpage fetchWebpage(final URI uri, final String cookies) throws IOException {
        final NetRequest.Builder builder = NetRequest.create(uri)
                .method("GET")
                .accept("text/html,application/xhtml+xml,*/*")
                .userAgent(NetRequest.UserAgent.GENERIC)
                .referer(TIKTOK_REFERER)
                .header("Accept-Language", "en-US,en;q=0.9");
        if (cookies != null) builder.header("Cookie", cookies);

        try (final NetRequest req = builder.send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "HTTP " + req.statusCode() + " for " + uri);
            return new Webpage(req.text(), captureCookies(req));
        }
    }

    // JOINS THE name=value PAIRS FROM EVERY Set-Cookie HEADER; RETURNS null WHEN THE RESPONSE SET NONE
    private static String captureCookies(final NetRequest req) {
        final List<String> setCookies = req.responseHeaders().values("Set-Cookie");
        if (setCookies.isEmpty()) return null;

        final StringBuilder sb = new StringBuilder();
        for (final String header: setCookies) {
            final String nameValue = header.split(";", 2)[0].trim();
            if (nameValue.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(nameValue);
        }
        if (sb.isEmpty()) return null;
        return sb.toString();
    }

    // THE REQUEST FOLLOWS THE SHORT LINK'S REDIRECTS ITSELF AND REPORTS WHERE IT LANDED
    private static URI resolveRedirect(final URI shortUri) throws IOException {
        try (final NetRequest req = NetRequest.create(shortUri)
                .method("GET")
                .accept(NetRequest.ACCEPT_ANY)
                .userAgent(NetRequest.UserAgent.GENERIC)
                .send()) {
            return req.uri();
        }
    }
}
