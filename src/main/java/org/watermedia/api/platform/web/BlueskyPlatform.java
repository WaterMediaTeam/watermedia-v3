package org.watermedia.api.platform.web;

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
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.Track;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;
import org.watermedia.tools.M3UTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public final class BlueskyPlatform implements Platform {
    public static final String NAME = "Bluesky";
    private static final Marker IT = MarkerManager.getMarker(BlueskyPlatform.class.getSimpleName());
    private static final String API_URL = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread";
    private static final String BLOB_URL_TMPL = "%s/xrpc/com.atproto.sync.getBlob?did=%s&cid=%s";
    private static final String PLC_DIRECTORY_URL = "https://plc.directory/%s";
    private static final String DEFAULT_PDS = "https://bsky.social";
    private static final Pattern WEB_PATTERN = Pattern.compile("/profile/([\\w.:%-]+)/post/(\\w+)");
    private static final Pattern AT_PATTERN = Pattern.compile("at://([\\w.:%-]+)/app\\.bsky\\.feed\\.post/(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final String[] HOSTS = { "bsky.app", "www.bsky.app", "main.bsky.dev", "www.main.bsky.dev" };
    // LABELS THE NETWORK APPLIES TO ADULT AND GRAPHIC POSTS
    private static final Set<String> MATURE_LABELS = Set.of("porn", "sexual", "nudity", "graphic-media");
    private static final int TITLE_LENGTH = 72;
    // VIDEO PLAYLISTS ARE SERVED THROUGH A CDN WITH SHORT-LIVED SESSION TOKENS
    private static final Duration TTL = Duration.ofMinutes(30);

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        final String handle, postId;
        if ("at".equalsIgnoreCase(uri.getScheme())) {
            final Matcher m = AT_PATTERN.matcher(uri.toString());
            if (!m.matches()) throw PlatformException.unsupported(NAME, uri);
            handle = m.group(1);
            postId = m.group(2);
        } else {
            if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
            final Matcher m = WEB_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!m.find()) throw PlatformException.unsupported(NAME, uri);
            handle = m.group(1);
            postId = m.group(2);
        }

        LOGGER.debug(IT, "Fetching Bluesky post '{}' by '{}'", postId, handle);
        try {
            final JsonObject post = this.fetchPost(handle, postId);
            ensureNotMature(post, postId);
            final HttpHeaders headers = HttpHeaders.defaults(uri);

            final List<Source> entries = new ArrayList<>(2);
            final JsonObject embed = obj(post, "embed");
            final JsonObject record = obj(post, "record");

            // DIRECT EMBED (app.bsky.embed.video#view OR app.bsky.embed.images#view)
            this.extractVideo(entries, post, embed, obj(record, "embed"), postId, headers);
            this.extractImages(entries, post, embed, postId, headers);

            // RECORD WITH MEDIA (app.bsky.embed.recordWithMedia#view): THE MEDIA LIVES IN embed.media
            if (embed != null && embed.has("media")) {
                final JsonObject mediaEmbed = obj(embed, "media");
                this.extractVideo(entries, post, mediaEmbed, obj(record, "embed", "media"), postId, headers);
                this.extractImages(entries, post, mediaEmbed, postId, headers);
            }

            // QUOTED POST (app.bsky.embed.record#view): THE MEDIA LIVES IN THE NESTED POST'S EMBEDS
            if (embed != null && embed.has("record")) {
                final JsonObject nestedPost = resolveNestedPost(obj(embed, "record"));
                if (nestedPost != null) {
                    ensureNotMature(nestedPost, postId);
                    final JsonElement embedsEl = nestedPost.get("embeds");
                    if (embedsEl != null && embedsEl.isJsonArray()) {
                        final JsonArray embeds = embedsEl.getAsJsonArray();
                        if (!embeds.isEmpty() && embeds.get(0).isJsonObject()) {
                            final JsonObject nestedEmbed = embeds.get(0).getAsJsonObject();
                            this.extractVideo(entries, nestedPost, nestedEmbed,
                                    obj(nestedPost, "value", "embed"), postId, headers);
                            this.extractImages(entries, nestedPost, nestedEmbed, postId, headers);
                        }
                    }
                }
            }

            if (entries.isEmpty())
                throw new PlatformException(NAME, "Post '" + postId + "' has no playable media (text-only, external link, or unsupported embed): " + uri);

            LOGGER.debug(IT, "Bluesky resolved {} media entry(es) for post '{}'", entries.size(), postId);
            return new Resolution(Instant.now().plus(TTL), entries);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }

    // THE NETWORK'S LABELS TRAVEL ON THE POST VIEW AND ON ITS AUTHOR; THE GATE RUNS BEFORE ANY MEDIA IS TOUCHED, FOR
    // THE POST ITSELF AND FOR THE POST IT QUOTES
    private static void ensureNotMature(final JsonObject post, final String postId) throws MatureContentException {
        if (WaterMediaConfig.platforms.allowMatureContent) return;
        final JsonObject author = obj(post, "author");
        for (final JsonElement labels: new JsonElement[] { post.get("labels"), author == null ? null : author.get("labels") }) {
            if (labels == null || !labels.isJsonArray()) continue;
            for (final JsonElement label: labels.getAsJsonArray()) {
                final String value = label.isJsonObject() ? str(label.getAsJsonObject(), "val") : null;
                if (value != null && MATURE_LABELS.contains(value))
                    throw new MatureContentException(NAME, "Post '" + postId + "' is labelled '" + value + "'");
            }
        }
    }

    private void extractVideo(final List<Source> entries, final JsonObject postCtx,
                              final JsonObject embedView, final JsonObject recordEmbed, final String postId,
                              final HttpHeaders headers) {
        if (embedView == null || !embedView.has("playlist")) return;

        final URI playlistUri = JSONTool.uri(str(embedView, "playlist"));
        if (playlistUri == null) {
            LOGGER.warn(IT, "Bluesky post '{}' carries a video without a usable playlist URL; skipping it", postId);
            return;
        }
        final List<Quality> variants = new ArrayList<>();

        try {
            // VARIANT URLS COME BACK ALREADY ABSOLUTE (RESOLVED AGAINST playlistUri BY M3UTool);
            // ONLY KEEP RENDITIONS THAT ADVERTISE A REAL RESOLUTION, RAW-URL FALLBACK HANDLES THE REST.
            final M3UTool.Playlist hlsResult = M3UTool.fetch(playlistUri);
            if (hlsResult instanceof final M3UTool.Master master) {
                for (final M3UTool.Variant variant: master.variants()) {
                    if (variant.width() <= 0 || variant.height() <= 0) continue;
                    variants.add(new Quality(variant.uri(), variant.width(), variant.height(), (float) variant.fps()));
                }
                LOGGER.debug(IT, "Bluesky parsed {} HLS rendition(s) for '{}'", variants.size(), postId);
            }
        } catch (final IOException e) {
            LOGGER.warn(IT, "Bluesky failed to fetch HLS for '{}'; falling back to the raw playlist URL", postId, e);
        }
        if (variants.isEmpty()) variants.add(new Quality(playlistUri, 0, 0));

        // SUBTITLE TRACKS THROUGH THE BLOB ENDPOINT
        final List<Track> subtitleTracks = new ArrayList<>();
        final String did = str(postCtx, "author", "did");
        if (did != null && recordEmbed != null && recordEmbed.has("captions")) {
            try {
                final String endpoint = this.resolveServiceEndpoint(did);
                final JsonElement captionsEl = recordEmbed.get("captions");
                if (captionsEl.isJsonArray()) {
                    for (final JsonElement capEl: captionsEl.getAsJsonArray()) {
                        if (!capEl.isJsonObject()) continue;
                        final JsonObject cap = capEl.getAsJsonObject();
                        final String lang = cap.has("lang") && !cap.get("lang").isJsonNull()
                                ? cap.get("lang").getAsString() : "und";
                        final String fileCid = str(cap, "file", "ref", "$link");
                        if (fileCid == null) continue;
                        subtitleTracks.add(new Track(lang, lang, URI.create(String.format(BLOB_URL_TMPL, endpoint, enc(did), enc(fileCid)))));
                    }
                }
            } catch (final RuntimeException e) {
                LOGGER.warn(IT, "Bluesky failed to extract subtitles for '{}'", postId, e);
            }
        }

        // METADATA
        final String displayName = str(postCtx, "author", "displayName");
        final String authorHandle = str(postCtx, "author", "handle");
        final String indexedAt = str(postCtx, "indexedAt");

        String text = str(postCtx, "record", "text");
        if (text == null) text = str(postCtx, "value", "text");

        final String title = text != null && !text.isBlank()
                ? truncateTitle(text)
                : "Bluesky video #" + postId;

        final Metadata metadata = new Metadata(
                title, text,
                indexedAt != null ? parseInstant(indexedAt) : null,
                0,
                displayName != null ? displayName : authorHandle
        );

        entries.add(new Source(MediaType.VIDEO, JSONTool.uri(str(embedView, "thumbnail")), metadata, headers,
                variants,
                null, subtitleTracks.isEmpty() ? null : subtitleTracks));
    }

    private void extractImages(final List<Source> entries, final JsonObject postCtx,
                               final JsonObject embedView, final String postId,
                               final HttpHeaders headers) {
        if (embedView == null || !embedView.has("images")) return;

        final JsonElement imagesEl = embedView.get("images");
        if (!imagesEl.isJsonArray()) return;

        final String displayName = str(postCtx, "author", "displayName");
        final String authorHandle = str(postCtx, "author", "handle");
        final String indexedAt = str(postCtx, "indexedAt");
        String text = str(postCtx, "record", "text");
        if (text == null) text = str(postCtx, "value", "text");

        final String title = text != null && !text.isBlank()
                ? truncateTitle(text)
                : "Bluesky image #" + postId;

        for (final JsonElement imgEl: imagesEl.getAsJsonArray()) {
            if (!imgEl.isJsonObject()) continue;
            final JsonObject img = imgEl.getAsJsonObject();

            // ONE BROKEN PICTURE MUST NOT TAKE THE REST OF THE POST DOWN
            final URI fullsize = JSONTool.uri(str(img, "fullsize"));
            if (fullsize == null) {
                LOGGER.warn(IT, "Bluesky post '{}' carries an image without a usable URL; skipping it", postId);
                continue;
            }

            final JsonObject aspectRatio = obj(img, "aspectRatio");
            final int width = aspectRatio != null ? Math.max(0, JSONTool.intOr(aspectRatio, "width", 0)) : 0;
            final int height = aspectRatio != null ? Math.max(0, JSONTool.intOr(aspectRatio, "height", 0)) : 0;

            final Metadata imgMetadata = new Metadata(
                    title, text,
                    indexedAt != null ? parseInstant(indexedAt) : null,
                    0,
                    displayName != null ? displayName : authorHandle
            );
            entries.add(new Source(MediaType.IMAGE, JSONTool.uri(str(img, "thumb")), imgMetadata, headers,
                    List.of(new Quality(fullsize, width, height)),
                    null, null));
        }
    }

    private JsonObject fetchPost(final String handle, final String postId) throws IOException {
        final String atUri = "at://" + handle + "/app.bsky.feed.post/" + postId;
        final URI apiUri = URI.create(API_URL + "?uri=" + enc(atUri) + "&depth=0&parentHeight=0");

        try (final NetRequest req = NetRequest.create(apiUri).method("GET").accept(NetRequest.ACCEPT_JSON).send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "getPostThread for '" + atUri + "' returned HTTP " + req.statusCode());

            // RESPONSE SHAPE: { thread: { post: {...} } } — A BLOCKED/NOT-FOUND THREAD OMITS "post"
            final JsonObject thread = obj(JsonParser.parseString(req.text()).getAsJsonObject(), "thread");
            final JsonObject post = obj(thread, "post");
            if (post == null)
                throw new PlatformException(NAME, "Post '" + postId + "' by '" + handle + "' was not found, blocked, or deleted");
            return post;
        }
    }

    private String resolveServiceEndpoint(final String did) {
        try {
            final String url = did.startsWith("did:web:")
                    ? "https://" + did.substring(8) + "/.well-known/did.json"
                    : String.format(PLC_DIRECTORY_URL, did);

            try (final NetRequest req = NetRequest.create(URI.create(url)).method("GET").accept(NetRequest.ACCEPT_JSON).send()) {
                if (req.statusCode() != 200) return DEFAULT_PDS;

                final JsonObject doc = JsonParser.parseString(req.text()).getAsJsonObject();

                if (doc.has("service") && doc.get("service").isJsonArray()) {
                    for (final JsonElement el: doc.getAsJsonArray("service")) {
                        if (!el.isJsonObject()) continue;
                        final JsonObject svc = el.getAsJsonObject();
                        if ("AtprotoPersonalDataServer".equals(str(svc, "type"))) {
                            final String ep = str(svc, "serviceEndpoint");
                            if (ep != null) return ep;
                        }
                    }
                }
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn(IT, "Bluesky failed to resolve the service endpoint for '{}'; using the default PDS", did, e);
        }
        return DEFAULT_PDS;
    }

    private static JsonObject resolveNestedPost(final JsonObject quotedRef) {
        if (quotedRef == null) return null;
        if (quotedRef.has("record") && quotedRef.get("record").isJsonObject()) return quotedRef.getAsJsonObject("record");
        return quotedRef;
    }

    private static String truncateTitle(final String text) {
        final String oneLine = text.replace('\n', ' ');
        return oneLine.length() > TITLE_LENGTH ? oneLine.substring(0, TITLE_LENGTH) + "..." : oneLine;
    }

    private static Instant parseInstant(final String iso) {
        try { return Instant.parse(iso); }
        catch (final DateTimeParseException e) { return null; }
    }

    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // WALKS A NESTED-KEY PATH TO THE OBJECT AT ITS END, OR null IF ANY SEGMENT IS ABSENT/NON-OBJECT
    private static JsonObject obj(final JsonObject root, final String... keys) {
        JsonObject current = root;
        for (final String key: keys) {
            if (current == null || !current.has(key) || !current.get(key).isJsonObject()) return null;
            current = current.getAsJsonObject(key);
        }
        return current;
    }

    // WALKS A NESTED-KEY PATH TO THE STRING AT ITS END, OR null IF ANY SEGMENT IS ABSENT/NULL
    private static String str(final JsonObject root, final String... keys) {
        if (keys.length == 0 || root == null) return null;
        JsonObject current = root;
        for (int i = 0; i < keys.length - 1; i++) {
            if (current == null || !current.has(keys[i]) || !current.get(keys[i]).isJsonObject()) return null;
            current = current.getAsJsonObject(keys[i]);
        }
        final String last = keys[keys.length - 1];
        if (!current.has(last) || current.get(last).isJsonNull()) return null;
        return current.get(last).getAsString();
    }
}
