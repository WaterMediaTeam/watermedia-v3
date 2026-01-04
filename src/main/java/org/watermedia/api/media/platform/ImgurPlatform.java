package org.watermedia.api.media.platform;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class ImgurPlatform implements IPlatform {
    private static final String API_URL = "https://api.imgur.com/3";
    private static final String API_KEY = "685cdf74b1229b9";
    private static final Gson GSON = new Gson();

    // URLs format
    private static final String IMAGE_URL = API_URL + "/image/%s?client_id=" + API_KEY;
    private static final String GALLERY_URL = API_URL + "/gallery/%s?client_id=" + API_KEY;
    private static final String TAG_GALLERY_URL = API_URL + "/gallery/t/%s/%s?client_id=" + API_KEY;

    @Override
    public String name() {
        return "Imgur";
    }

    @Override
    public boolean validate(final URI uri) {
        // i.imgur.com usually points to raw files, imgur.com points to the site/api
        return "imgur.com".equalsIgnoreCase(uri.getHost());
    }

    @Override
    public MRL.Source[] getSources(final URI uri) throws Exception {
        final var path = uri.getPath();
        final var fragment = uri.getFragment();

        // LOGIC PARSING
        final boolean isGallery = path.startsWith("/gallery/") || path.startsWith("/a/");
        final boolean isTagGallery = fragment != null && fragment.contains("#/t/");

        // Remove first slash and split
        final var pathSplit = path.substring(1).split("/");
        // Clean ID (remove extension or extra params if any, though usually path is clean)
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
            final List<MRL.Source> sources = new ArrayList<>();
            
            // Iterate over gallery images
            for (final Image img: data.images()) {
                sources.add(this.buildSourceFromImage(img, data.title(), data.accountUrl()));
            }

            return sources.toArray(MRL.Source[]::new);

        } else {
            // Simple Image
            final String requestUrl = String.format(IMAGE_URL, id);
            final String json = this.fetch(requestUrl);
            final ImageResponse res = GSON.fromJson(json, ImageResponse.class);

            if (res == null || !res.success() || res.data() == null) {
                throw new IOException("Imgur image response was empty or failed.");
            }

            final Image img = res.data();
            final MRL.Source source = this.buildSourceFromImage(img, img.title(), img.accountUrl());
            
            return new MRL.Source[]{ source };
        }
    }

    private MRL.Source buildSourceFromImage(final Image img, final String fallbackTitle, final String accountUrl) {
        // Parse MimeType
        final MRL.MediaType type = MRL.MediaType.of(img.type());

        // Fix for when Imgur says "image/gif" but provides an mp4 link, treated as video
        final String link = switch (type) {
            case IMAGE -> img.link();
            case VIDEO -> img.hasSound() ? img.mp4() : img.link();
            default -> {
                LOGGER.warn("Imgur provided an unexpected media type: {}", img.type());
                yield img.link();
            }
        };

        // Build Metadata
        final String title = img.title() != null ? img.title() : fallbackTitle;
        final String desc = img.description();
        final Instant date = Instant.ofEpochSecond(img.datetime());
        
        final MRL.Metadata metadata = new MRL.Metadata(
                title,
                desc,
                null, // Thumbnail extraction could be added here if needed (imgur uses id + 's'.jpg usually)
                date,
                0, // Duration
                accountUrl
        );

        try {
            return MRL.sourceBuilder(type)
                    .quality(MRL.Quality.of(img.width, img.height), new URI(link))
                    .metadata(metadata)
                    .build();
        } catch (final Exception e) {
            // URI syntax exception handling
            throw new RuntimeException("Invalid URI received from Imgur: " + link, e);
        }
    }

    private String fetch(final String urlString) throws IOException {
        final URI uri = URI.create(urlString);
        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET", "application/json");
        
        try {
            final int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                try (final InputStream in = conn.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                throw new IOException("Imgur API responded with code: " + code);
            }
        } finally {
            conn.disconnect();
        }
    }

    // ==========================================
    // DATA RECORDS
    // ==========================================

    public record ImageResponse(Image data, boolean success, int status) {
    }

    public record GalleryResponse(Gallery data, boolean success, int status) {
    }

    public record Gallery(
            String id,
            String title,
            @SerializedName("description") String description, // JSON key matches field name logic, but explicit for clarity or rename
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