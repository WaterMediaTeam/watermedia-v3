package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class ImgurPlatform implements IPlatform {
    public static final String NAME = "Imgur";
    private static final Marker IT = MarkerManager.getMarker(ImgurPlatform.class.getSimpleName());
    private static final String API_URL = "https://api.imgur.com/3";
    private static final String API_KEY = "685cdf74b1229b9";

    // URLS FORMAT
    private static final String IMAGE_URL = API_URL + "/image/%s?client_id=" + API_KEY;
    private static final String GALLERY_URL = API_URL + "/gallery/%s?client_id=" + API_KEY;
    private static final String TAG_GALLERY_URL = API_URL + "/gallery/t/%s/%s?client_id=" + API_KEY;
    private static final String[] HOSTS = { "imgur.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // i.imgur.com IS EXCLUDED, THOSE ARE ALREADY STATIC
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS)) return null;

        final var path = uri.getPath();
        final var fragment = uri.getFragment();

        // LOGIC PARSING
        final boolean isGallery = path.startsWith("/gallery/") || path.startsWith("/a/");
        final boolean isTagGallery = fragment != null && fragment.contains("#/t/");

        // REMOVE FIRST SLASH AND SPLIT
        final var pathSplit = path.substring(1).split("/");
        // CLEAN ID (REMOVE EXTENSION OR EXTRA PARAMS IF ANY)
        final var idSplit = pathSplit[pathSplit.length - 1].split("-");

        final var tag = fragment != null && fragment.length() > 4 ? fragment.substring("#/t/".length()) : null;
        final var id = idSplit[idSplit.length - 1];
        LOGGER.debug(IT, "Imgur parsed id='{}', tag='{}', gallery={}, tagGallery={} from {}", id, tag, isGallery, isTagGallery, uri);

        if (isGallery) {
            final String requestUrl = isTagGallery
                    ? String.format(TAG_GALLERY_URL, tag, id)
                    : String.format(GALLERY_URL, id);

            final GalleryResponse res = this.fetch(requestUrl, GalleryResponse.class);
            if (res == null || !res.success() || res.data() == null || res.data().images() == null) {
                throw new PlatformException(ImgurPlatform.class, "Gallery '" + id + "' response was empty or unsuccessful (status "
                        + (res != null ? res.status() : "n/a") + ")");
            }

            final Gallery data = res.data();
            if (data.images().length == 0)
                throw new PlatformException(ImgurPlatform.class, "Gallery '" + id + "' contains no images");

            final List<DataSource> entries = new ArrayList<>(data.images().length);
            // ITERATE OVER GALLERY IMAGES
            for (final Image img: data.images()) {
                entries.add(this.buildEntryFromImage(img, data.title(), data.accountUrl(), uri));
            }

            LOGGER.info(IT, "Imgur resolved gallery '{}' with {} entry(es)", id, entries.size());
            return new PlatformData(null, entries.toArray(DataSource[]::new));

        } else {
            // SIMPLE IMAGE
            final String requestUrl = String.format(IMAGE_URL, id);
            final ImageResponse res = this.fetch(requestUrl, ImageResponse.class);
            if (res == null || !res.success() || res.data() == null) {
                throw new PlatformException(ImgurPlatform.class, "Image '" + id + "' response was empty or unsuccessful (status "
                        + (res != null ? res.status() : "n/a") + ")");
            }

            final Image img = res.data();
            LOGGER.info(IT, "Imgur resolved image '{}' ({})", id, img.type());
            return new PlatformData(null, this.buildEntryFromImage(img, img.title(), img.accountUrl(), uri));
        }
    }

    private DataSource buildEntryFromImage(final Image img, final String fallbackTitle, final String accountUrl, final URI mrlUri) throws PlatformException {
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
                    RequestHeaders.defaults(mrlUri),
                    new DataQuality[] {new DataQuality(new URI(link), img.width, img.height)},
                    null, null);
        } catch (final Exception e) {
            throw new PlatformException(ImgurPlatform.class, "Item '" + img.id() + "' has a malformed link URI: " + link, e);
        }
    }

    private <T> T fetch(final String urlString, final Class<T> type) throws IOException {
        try (final NetRequest req = NetRequest.create(URI.create(urlString)).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(ImgurPlatform.class, "API for " + urlString + " returned HTTP " + req.statusCode());
            final T data = req.json(type);
            if (data == null) throw new PlatformException(ImgurPlatform.class, "API returned an empty or non-JSON body for " + urlString);
            return data;
        }
    }

    // DATA RECORDS
    public record ImageResponse(Image data, boolean success, int status) {
    }

    public record GalleryResponse(Gallery data, boolean success, int status) {
    }

    public record Gallery(
            String id,
            String title,
            @SerializedName("description") String description, // JSON KEY MATCHES FIELD NAME, EXPLICIT FOR CLARITY
            long datetime,
            String cover,
            @SerializedName("account_url") String accountUrl,
            @SerializedName("account_id") int accountId,
            String privacy,
            String layout,
            int views,
            String link,
            int ups,
            int downs,
            int points,
            int score,
            @SerializedName("is_album") boolean isAlbum,
            String vote,
            @SerializedName("comment_count") int commentCount,
            @SerializedName("images_count") int imagesCount,
            Image[] images
    ) {}

    public record Image(
            String id,
            @SerializedName("account_id") String accountId,
            @SerializedName("account_url") String accountUrl,
            @SerializedName("ad_type") int adType,
            @SerializedName("ad_url") String adUrl,
            String title,
            @SerializedName("description") String description,
            String name,
            String type,
            int width,
            int height,
            int size,
            int views,
            String section,
            String vote,
            long bandwidth,
            @SerializedName("animated") boolean animated,
            @SerializedName("favorite") boolean favorite,
            @SerializedName("in_gallery") boolean inGallery,
            @SerializedName("in_most_viral") boolean inMostViral,
            @SerializedName("has_sound") boolean hasSound,
            @SerializedName("is_ad") boolean isAd,
            String nsfw,
            String link,
            long datetime,
            String mp4,
            String hls
    ) {}
}
