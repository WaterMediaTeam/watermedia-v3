package org.watermedia.api.platform.web;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class ImgurPlatform implements IPlatform {
    public static final String NAME = "Imgur";
    private static final String API_URL = "https://api.imgur.com/3";
    private static final String API_KEY = "685cdf74b1229b9";
    private static final Gson GSON = new Gson();

    // URLS FORMAT
    private static final String IMAGE_URL = API_URL + "/image/%s?client_id=" + API_KEY;
    private static final String GALLERY_URL = API_URL + "/gallery/%s?client_id=" + API_KEY;
    private static final String TAG_GALLERY_URL = API_URL + "/gallery/t/%s/%s?client_id=" + API_KEY;

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) { return "imgur.com".equalsIgnoreCase(uri.getHost()); }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
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

        if (isGallery) {
            final String requestUrl = isTagGallery
                    ? String.format(TAG_GALLERY_URL, tag, id)
                    : String.format(GALLERY_URL, id);

            final String json = this.fetch(requestUrl);
            final GalleryResponse res = GSON.fromJson(json, GalleryResponse.class); // Assuming type token handling in DataTool or simple erasure casting

            if (res == null || !res.success() || res.data() == null || res.data().images() == null) {
                throw new IOException("Imgur gallery response was empty or failed.");
            }

            final Gallery data = res.data();
            final List<DataSource> entries = new ArrayList<>();

            // ITERATE OVER GALLERY IMAGES
            for (final Image img: data.images()) {
                entries.add(this.buildEntryFromImage(img, data.title(), data.accountUrl(), uri));
            }

            return new PlatformData(null, entries.toArray(DataSource[]::new));

        } else {
            // SIMPLE IMAGE
            final String requestUrl = String.format(IMAGE_URL, id);
            final String json = this.fetch(requestUrl);
            final ImageResponse res = GSON.fromJson(json, ImageResponse.class);

            if (res == null || !res.success() || res.data() == null) {
                throw new IOException("Imgur image response was empty or failed.");
            }

            final Image img = res.data();
            return new PlatformData(null, this.buildEntryFromImage(img, img.title(), img.accountUrl(), uri));
        }
    }

    private DataSource buildEntryFromImage(final Image img, final String fallbackTitle, final String accountUrl, final URI mrlUri) {
        // PARSE MIME TYPE
        final MediaType type = MediaType.of(img.type());

        // FIX: IMGUR SAYS "image/gif" BUT PROVIDES AN mp4 LINK — TREAT AS VIDEO
        final String link = switch (type) {
            case IMAGE -> img.link();
            case VIDEO -> img.hasSound() ? img.mp4() : img.link();
            default -> {
                LOGGER.warn("Imgur provided an unexpected media type: {}", img.type());
                yield img.link();
            }
        };

        // BUILD METADATA
        final String title = img.title() != null ? img.title() : fallbackTitle;
        final String desc = img.description();
        final Instant date = Instant.ofEpochSecond(img.datetime());

        final Metadata metadata = new Metadata(title, desc, date, 0, accountUrl);

        try {
            return new DataSource(type, null, metadata,
                    RequestHeaders.defaults(mrlUri),
                    new DataQuality[] {new DataQuality(new URI(link), img.width, img.height)},
                    null, null);
        } catch (final Exception e) {
            throw new RuntimeException("Invalid URI received from Imgur: " + link, e);
        }
    }

    private String fetch(final String urlString) throws IOException {
        try (final NetRequest req = NetRequest.create(URI.create(urlString)).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("Imgur API responded with code: " + req.statusCode());
            return req.readAllAsString();
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
