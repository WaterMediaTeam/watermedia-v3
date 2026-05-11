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
import org.watermedia.tools.HlsTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class BlueskyPlatform implements IPlatform {
    public static final String NAME = "Bluesky";
    private static final Marker IT = MarkerManager.getMarker(BlueskyPlatform.class.getSimpleName());
    private static final String API_URL = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread";
    private static final String BLOB_URL_TMPL = "%s/xrpc/com.atproto.sync.getBlob?did=%s&cid=%s";
    private static final String PLC_DIRECTORY_URL = "https://plc.directory/%s";
    private static final String DEFAULT_PDS = "https://bsky.social";
    private static final Pattern WEB_PATTERN = Pattern.compile("/profile/([\\w.:%-]+)/post/(\\w+)");
    private static final Pattern AT_PATTERN = Pattern.compile("at://([\\w.:%-]+)/app\\.bsky\\.feed\\.post/(\\w+)");

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String uriStr = uri.toString();
        if (uriStr.startsWith("at://")) {
            return AT_PATTERN.matcher(uriStr).matches();
        }

        final String host = uri.getHost();
        if (host == null) return false;

        final boolean validHost = host.equalsIgnoreCase("bsky.app") || host.equalsIgnoreCase("www.bsky.app")
                || host.equalsIgnoreCase("main.bsky.dev") || host.equalsIgnoreCase("www.main.bsky.dev");
        return validHost && uri.getPath() != null && WEB_PATTERN.matcher(uri.getPath()).find();
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final String[] parsed = parseUri(uri);
        final String handle = parsed[0];
        final String postId = parsed[1];

        LOGGER.debug(IT, "Fetching Bluesky post '{}' by '{}'", postId, handle);
        final JsonObject post = fetchPost(handle, postId);
        final RequestHeaders headers = RequestHeaders.defaults(uri);

        final List<DataSource> entries = new ArrayList<>(2);
        final JsonObject embed = getObj(post, "embed");
        final JsonObject record = getObj(post, "record");

        // Direct embed (app.bsky.embed.video#view or app.bsky.embed.images#view)
        extractVideo(entries, post, embed, getObj(record, "embed"), postId, headers);
        extractImages(entries, post, embed, postId, headers);

        // RecordWithMedia (app.bsky.embed.recordWithMedia#view) — media in embed.media
        if (embed != null && embed.has("media")) {
            final JsonObject mediaEmbed = getObj(embed, "media");
            extractVideo(entries, post, mediaEmbed, getObj(record, "embed", "media"), postId, headers);
            extractImages(entries, post, mediaEmbed, postId, headers);
        }

        // Quoted post (app.bsky.embed.record#view) — media in nested post's embeds
        if (embed != null && embed.has("record")) {
            final JsonObject nestedPost = resolveNestedPost(getObj(embed, "record"));
            if (nestedPost != null) {
                final JsonElement embedsEl = nestedPost.get("embeds");
                if (embedsEl != null && embedsEl.isJsonArray()) {
                    final JsonArray embeds = embedsEl.getAsJsonArray();
                    if (!embeds.isEmpty() && embeds.get(0).isJsonObject()) {
                        final JsonObject nestedEmbed = embeds.get(0).getAsJsonObject();
                        extractVideo(entries, nestedPost, nestedEmbed,
                                getObj(nestedPost, "value", "embed"), postId, headers);
                        extractImages(entries, nestedPost, nestedEmbed, postId, headers);
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            throw new IllegalStateException("No media found in Bluesky post: " + uri);
        }

        return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entries.toArray(DataSource[]::new));
    }

    private void extractVideo(final List<DataSource> entries, final JsonObject postCtx,
                              final JsonObject embedView, final JsonObject recordEmbed, final String postId,
                              final RequestHeaders headers) {
        if (embedView == null || !embedView.has("playlist")) return;

        final String playlist = getStr(embedView, "playlist");
        if (playlist == null) return;

        final URI playlistUri = URI.create(playlist);
        final List<DataQuality> variants = new ArrayList<>();

        try {
            final var hlsResult = HlsTool.fetch(playlistUri);
            if (hlsResult instanceof final HlsTool.MasterResult master) {
                for (final var variant : master.variants()) {
                    if (variant.width() <= 0 || variant.height() <= 0) continue;
                    URI variantUri = URI.create(variant.uri());
                    if (!variantUri.isAbsolute()) {
                        variantUri = playlistUri.resolve(variantUri);
                    }
                    variants.add(new DataQuality(variantUri, variant.width(), variant.height()));
                }
            }
        } catch (final Exception e) {
            LOGGER.warn(IT, "HLS fetch failed for '{}': {}", postId, e.getMessage());
        }
        if (variants.isEmpty()) {
            variants.add(new DataQuality(playlistUri, 0, 0));
        }

        // Subtitle tracks via blob endpoint
        final List<DataSlave> subSlaves = new ArrayList<>();
        final String did = getStr(postCtx, "author", "did");
        if (did != null && recordEmbed != null && recordEmbed.has("captions")) {
            try {
                final String endpoint = resolveServiceEndpoint(did);
                final JsonElement captionsEl = recordEmbed.get("captions");
                if (captionsEl.isJsonArray()) {
                    for (final JsonElement capEl : captionsEl.getAsJsonArray()) {
                        if (!capEl.isJsonObject()) continue;
                        final JsonObject cap = capEl.getAsJsonObject();
                        final String lang = cap.has("lang") && !cap.get("lang").isJsonNull()
                                ? cap.get("lang").getAsString() : "und";
                        final String fileCid = getStr(cap, "file", "ref", "$link");
                        if (fileCid == null) continue;
                        subSlaves.add(new DataSlave(lang, lang, URI.create(String.format(BLOB_URL_TMPL, endpoint, enc(did), enc(fileCid)))));
                    }
                }
            } catch (final Exception e) {
                LOGGER.warn(IT, "Subtitle extraction failed for '{}': {}", postId, e.getMessage());
            }
        }

        // Metadata
        final String displayName = getStr(postCtx, "author", "displayName");
        final String authorHandle = getStr(postCtx, "author", "handle");
        final String thumbnailUrl = getStr(embedView, "thumbnail");
        final String indexedAt = getStr(postCtx, "indexedAt");

        String text = getStr(postCtx, "record", "text");
        if (text == null) text = getStr(postCtx, "value", "text");

        final String title = text != null && !text.isBlank()
                ? truncateTitle(text, 72)
                : "Bluesky video #" + postId;

        final URI thumbnail = thumbnailUrl != null ? URI.create(thumbnailUrl) : null;
        final Metadata metadata = new Metadata(
                title, text,
                indexedAt != null ? parseInstant(indexedAt) : null,
                0,
                displayName != null ? displayName : authorHandle
        );

        entries.add(new DataSource(MediaType.VIDEO, thumbnail, metadata, headers,
                variants.toArray(DataQuality[]::new),
                null, subSlaves.isEmpty() ? null : subSlaves));
    }

    private void extractImages(final List<DataSource> entries, final JsonObject postCtx,
                               final JsonObject embedView, final String postId,
                               final RequestHeaders headers) {
        if (embedView == null || !embedView.has("images")) return;

        final JsonElement imagesEl = embedView.get("images");
        if (!imagesEl.isJsonArray()) return;

        final String displayName = getStr(postCtx, "author", "displayName");
        final String authorHandle = getStr(postCtx, "author", "handle");
        final String indexedAt = getStr(postCtx, "indexedAt");
        String text = getStr(postCtx, "record", "text");
        if (text == null) text = getStr(postCtx, "value", "text");

        final String title = text != null && !text.isBlank()
                ? truncateTitle(text, 72)
                : "Bluesky image #" + postId;

        for (final JsonElement imgEl : imagesEl.getAsJsonArray()) {
            if (!imgEl.isJsonObject()) continue;
            final JsonObject img = imgEl.getAsJsonObject();

            final String fullsize = getStr(img, "fullsize");
            if (fullsize == null) continue;

            final JsonObject aspectRatio = getObj(img, "aspectRatio");
            final int width = aspectRatio != null ? getInt(aspectRatio, "width") : 0;
            final int height = aspectRatio != null ? getInt(aspectRatio, "height") : 0;

            final String thumb = getStr(img, "thumb");

            final URI imgThumb = thumb != null ? URI.create(thumb) : null;
            final Metadata imgMetadata = new Metadata(
                    title, text,
                    indexedAt != null ? parseInstant(indexedAt) : null,
                    0,
                    displayName != null ? displayName : authorHandle
            );
            entries.add(new DataSource(MediaType.IMAGE, imgThumb, imgMetadata, headers,
                    new DataQuality[] {new DataQuality(URI.create(fullsize), width, height)},
                    null, null));
        }
    }

    private JsonObject fetchPost(final String handle, final String postId) throws IOException {
        final String atUri = "at://" + handle + "/app.bsky.feed.post/" + postId;
        final URI apiUri = URI.create(API_URL + "?uri=" + enc(atUri) + "&depth=0&parentHeight=0");

        try (final NetRequest req = NetRequest.create(apiUri).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + apiUri);
            return JsonParser.parseString(req.readAllAsString()).getAsJsonObject()
                    .getAsJsonObject("thread")
                    .getAsJsonObject("post");
        }
    }

    private String resolveServiceEndpoint(final String did) {
        try {
            final String url = did.startsWith("did:web:")
                    ? "https://" + did.substring(8) + "/.well-known/did.json"
                    : String.format(PLC_DIRECTORY_URL, did);

            try (final NetRequest req = NetRequest.create(URI.create(url)).method("GET").accept("application/json").send()) {
                if (req.statusCode() != 200) return DEFAULT_PDS;

                final JsonObject doc = JsonParser.parseString(req.readAllAsString()).getAsJsonObject();

                if (doc.has("service") && doc.get("service").isJsonArray()) {
                    for (final JsonElement el : doc.getAsJsonArray("service")) {
                        if (!el.isJsonObject()) continue;
                        final JsonObject svc = el.getAsJsonObject();
                        if ("AtprotoPersonalDataServer".equals(getStr(svc, "type"))) {
                            final String ep = getStr(svc, "serviceEndpoint");
                            if (ep != null) return ep;
                        }
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.warn(IT, "Service endpoint resolution failed for '{}': {}", did, e.getMessage());
        }
        return DEFAULT_PDS;
    }

    private static JsonObject resolveNestedPost(final JsonObject quotedRef) {
        if (quotedRef == null) return null;
        if (quotedRef.has("record") && quotedRef.get("record").isJsonObject()) {
            return quotedRef.getAsJsonObject("record");
        }
        return quotedRef;
    }

    static String[] parseUri(final URI uri) {
        final String raw = uri.toString();
        Matcher m = AT_PATTERN.matcher(raw);
        if (m.matches()) return new String[]{ m.group(1), m.group(2) };

        final String path = uri.getPath();
        if (path != null) {
            m = WEB_PATTERN.matcher(path);
            if (m.find()) return new String[]{ m.group(1), m.group(2) };
        }
        throw new IllegalArgumentException("Cannot parse Bluesky URL: " + uri);
    }

    private static String truncateTitle(final String text, final int maxLen) {
        final String oneLine = text.replace('\n', ' ');
        return oneLine.length() > maxLen ? oneLine.substring(0, maxLen) + "..." : oneLine;
    }

    private static Instant parseInstant(final String iso) {
        try { return Instant.parse(iso); }
        catch (final Exception e) { return null; }
    }

    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonObject getObj(final JsonObject obj, final String... keys) {
        JsonObject current = obj;
        for (final String key : keys) {
            if (current == null || !current.has(key) || !current.get(key).isJsonObject()) return null;
            current = current.getAsJsonObject(key);
        }
        return current;
    }

    private static int getInt(final JsonObject obj, final String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsInt(); }
        catch (final Exception e) { return 0; }
    }

    private static String getStr(final JsonObject obj, final String... keys) {
        if (keys.length == 0 || obj == null) return null;
        JsonObject current = obj;
        for (int i = 0; i < keys.length - 1; i++) {
            if (current == null || !current.has(keys[i]) || !current.get(keys[i]).isJsonObject()) return null;
            current = current.getAsJsonObject(keys[i]);
        }
        final String last = keys[keys.length - 1];
        if (!current.has(last) || current.get(last).isJsonNull()) return null;
        return current.get(last).getAsString();
    }
}
