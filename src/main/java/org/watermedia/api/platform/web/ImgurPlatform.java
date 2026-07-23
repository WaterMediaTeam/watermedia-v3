package org.watermedia.api.platform.web;

import com.google.gson.JsonObject;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public final class ImgurPlatform implements IPlatform {
    public static final String NAME = "Imgur";
    private static final Marker IT = MarkerManager.getMarker(ImgurPlatform.class.getSimpleName());
    private static final String API_URL = "https://api.imgur.com/3";
    private static final String API_KEY = "685cdf74b1229b9";

    // URLS FORMAT
    private static final String IMAGE_URL = API_URL + "/image/%s?client_id=" + API_KEY;
    private static final String GALLERY_URL = API_URL + "/gallery/%s?client_id=" + API_KEY;
    private static final String ALBUM_URL = API_URL + "/album/%s?client_id=" + API_KEY;
    private static final String SEARCH_URL = API_URL + "/gallery/search/top/all/0?client_id=" + API_KEY + "&q=%s";
    private static final String[] HOSTS = { "imgur.com", "www.imgur.com", "m.imgur.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // i.imgur.com IS EXCLUDED, THOSE ARE ALREADY STATIC
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final var path = uri.getPath();
        if (path == null || path.length() <= 1) {
            LOGGER.warn(IT, "Imgur URL {} has no media path, ignoring it", uri);
            return null;
        }

        // REMOVE FIRST SLASH AND SPLIT
        final var pathSplit = path.substring(1).split("/");

        // POST KINDS BY PATH: /a/{id} IS AN ALBUM (MAY BE HIDDEN — MISSING FROM THE GALLERY API),
        // /gallery/{id} AND /t/{tag}/{id} ARE GALLERY POSTS; ANYTHING ELSE IS A BARE IMAGE ID
        final boolean isAlbum = path.startsWith("/a/");
        final boolean isGallery = path.startsWith("/gallery/") || (pathSplit.length >= 3 && pathSplit[0].equals("t"));

        // LAST SEGMENT CARRIES THE ID; MODERN URLS PREFIX A TITLE SLUG (title-slug-id)
        // AND SOME LINKS CARRY AN EXTENSION (id.png)
        var last = pathSplit[pathSplit.length - 1];
        final int dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);
        final var idSplit = last.split("-");
        final var id = idSplit[idSplit.length - 1];
        if (id.isEmpty()) {
            LOGGER.warn(IT, "Imgur URL {} has no media id, ignoring it", uri);
            return null;
        }
        LOGGER.debug(IT, "Imgur parsed id='{}', album={}, gallery={} from {}", id, isAlbum, isGallery, uri);

        if (isAlbum || isGallery) {
            final String requestUrl = String.format(isAlbum ? ALBUM_URL : GALLERY_URL, id);

            // GALLERY POSTS COME IN TWO SHAPES: ALBUMS CARRY data.images[] WHILE SINGLE-IMAGE
            // POSTS CARRY THE IMAGE FIELDS DIRECTLY ON data — BIND THE ENVELOPE FIRST, THEN PICK
            final JsonObject root = NetRequest.fetchJson(ImgurPlatform.class, requestUrl, JsonObject.class);
            final GalleryResponse res = JsonTool.parse(root, GalleryResponse.class);
            if (!res.success() || res.data() == null) {
                throw new PlatformException(ImgurPlatform.class, "Post '" + id + "' response was empty or unsuccessful (status "
                        + res.status() + ")");
            }

            final Gallery data = res.data();
            if (Boolean.TRUE.equals(data.nsfw()) && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(ImgurPlatform.class, "Post '" + id + "' is marked as mature content");

            // SINGLE-IMAGE POST: data IS THE IMAGE ITSELF
            if (data.images() == null && !data.isAlbum()) {
                final Image img = JsonTool.parse(root.get("data"), Image.class);
                LOGGER.info(IT, "Imgur resolved single-image post '{}' ({})", id, img.type());
                return new PlatformData(null, this.buildEntry(img, img.title(), img.accountUrl(), uri));
            }

            if (data.images() == null || data.images().length == 0)
                throw new PlatformException(ImgurPlatform.class, "Post '" + id + "' contains no images");

            final List<DataSource> entries = new ArrayList<>(data.images().length);
            // ITERATE OVER ALBUM IMAGES
            for (final Image img: data.images()) {
                entries.add(this.buildEntry(img, data.title(), data.accountUrl(), uri));
            }

            LOGGER.info(IT, "Imgur resolved post '{}' with {} entry(es)", id, entries.size());
            return new PlatformData(null, entries);

        } else {
            // SIMPLE IMAGE
            final String requestUrl = String.format(IMAGE_URL, id);
            final ImageResponse res = NetRequest.fetchJson(ImgurPlatform.class, requestUrl, ImageResponse.class);
            if (!res.success() || res.data() == null) {
                throw new PlatformException(ImgurPlatform.class, "Image '" + id + "' response was empty or unsuccessful (status "
                        + res.status() + ")");
            }

            final Image img = res.data();
            LOGGER.info(IT, "Imgur resolved image '{}' ({})", id, img.type());
            return new PlatformData(null, this.buildEntry(img, img.title(), img.accountUrl(), uri));
        }
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws Exception {
        final SearchResponse res = NetRequest.fetchJson(ImgurPlatform.class, String.format(SEARCH_URL, URLEncoder.encode(query, StandardCharsets.UTF_8)), SearchResponse.class);
        if (!res.success() || res.data() == null) return List.of();

        final List<PlatformResult> out = new ArrayList<>(Math.min(res.data().length, limit));
        for (final SearchItem item: res.data()) {
            if (out.size() >= limit) break;
            if (item.id() == null) continue;
            // ALBUM POSTS HAVE NO IMAGE OF THEIR OWN — THUMBNAIL THE COVER; SINGLE IMAGES THUMBNAIL THEMSELVES.
            // THE 'm' SUFFIX (MEDIUM, 320px) GOES BEFORE THE EXTENSION AND RETURNS A STATIC FRAME EVEN FOR GIFS.
            final String thumbId = item.isAlbum() && item.cover() != null ? item.cover() : item.id();
            out.add(new PlatformResult(NAME, item.title(),
                    URI.create("https://i.imgur.com/" + thumbId + "m.jpg"),
                    URI.create("https://imgur.com/gallery/" + item.id())));
        }
        return out;
    }

    private DataSource buildEntry(final Image img, final String fallbackTitle, final String accountUrl, final URI uri) throws PlatformException {
        if (Boolean.TRUE.equals(img.nsfw()) && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(ImgurPlatform.class, "Item '" + img.id() + "' is marked as mature content");

        // PARSE MIME TYPE
        final MediaType type = MediaType.of(img.type());

        // FIX: IMGUR SAYS "image/gif" BUT PROVIDES AN mp4 LINK — TREAT AS VIDEO
        final String link = switch (type) {
            case IMAGE -> img.link();
            case VIDEO -> img.hasSound() ? img.mp4() : img.link();
            default -> {
                LOGGER.warn(IT, "Imgur item '{}' has unexpected media type '{}', defaulting to its link", img.id(), img.type());
                yield img.link();
            }
        };

        // BUILD METADATA
        final String title = img.title() != null ? img.title() : fallbackTitle;
        final String desc = img.description();
        final Instant date = Instant.ofEpochSecond(img.datetime());

        final Metadata metadata = new Metadata(title, desc, date, 0, accountUrl);

        if (link == null)
            throw new PlatformException(ImgurPlatform.class, "Item '" + img.id() + "' (" + img.type() + ") has no usable link");

        try {
            return new DataSource(type, null, metadata,
                    RequestHeaders.defaults(uri),
                    List.of(new DataQuality(new URI(link), img.width(), img.height())),
                    null, null);
        } catch (final Exception e) {
            throw new PlatformException(ImgurPlatform.class, "Item '" + img.id() + "' has a malformed link URI: " + link, e);
        }
    }

    // DATA RECORDS — BIND ONLY THE CONSUMED FIELDS: IMGUR SENDS null FOR MANY NUMERIC FIELDS
    // DEPENDING ON THE ENDPOINT, AND A null INTO A PRIMITIVE RECORD COMPONENT MAKES GSON THROW
    public record ImageResponse(Image data, boolean success, int status) {
    }

    // GALLERY SEARCH RETURNS A MIXED ARRAY OF ALBUMS AND SINGLE IMAGES; ONLY THE DISPLAY FIELDS ARE BOUND
    public record SearchResponse(SearchItem[] data, boolean success, int status) {
    }

    public record SearchItem(String id, String title, String cover, @SerializedName("is_album") boolean isAlbum) {
    }

    public record GalleryResponse(Gallery data, boolean success, int status) {
    }

    public record Gallery(
            String title,
            @SerializedName("account_url") String accountUrl,
            @SerializedName("is_album") boolean isAlbum,
            Boolean nsfw,
            Image[] images
    ) {}

    public record Image(
            String id,
            @SerializedName("account_url") String accountUrl,
            String title,
            String description,
            String type,
            int width,
            int height,
            long datetime,
            Boolean nsfw,
            @SerializedName("has_sound") boolean hasSound,
            String link,
            String mp4
    ) {}
}
