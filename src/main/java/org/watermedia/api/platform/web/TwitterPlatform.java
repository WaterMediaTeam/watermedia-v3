package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class TwitterPlatform implements IPlatform {
    public static final String NAME = "Twitter";
    private static final Marker IT = MarkerManager.getMarker(TwitterPlatform.class.getSimpleName());
    private static final String API_URL = "https://cdn.syndication.twimg.com/tweet-result?id=%s&token=%s&lang=en";
    private static final String API_TOKEN = "watermedia-java-x-access-token";
    private static final Pattern ID_PATTERN = Pattern.compile("status/(\\d+)$");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("/(\\d{2,5})x(\\d{2,5})/");

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        if (host == null) return false;
        final boolean validHost = host.equalsIgnoreCase("x.com") || host.equalsIgnoreCase("www.x.com")
                || host.equalsIgnoreCase("twitter.com") || host.equalsIgnoreCase("www.twitter.com");
        return validHost && uri.getPath() != null && ID_PATTERN.matcher(uri.getPath()).find();
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        String path = uri.getPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        final Matcher m = ID_PATTERN.matcher(path);
        if (!m.find()) throw new IllegalArgumentException("No tweet ID found in URL: " + uri);

        final String tweetId = m.group(1);
        LOGGER.debug(IT, "Fetching tweet '{}'", tweetId);

        final Tweet tweet = this.fetchTweet(tweetId);

        if ("TweetTombstone".equals(tweet.typename))
            throw new IllegalStateException("Tweet '" + tweetId + "' is unavailable (tombstoned)");

        if (tweet.mediaDetails == null || tweet.mediaDetails.length == 0)
            throw new IllegalStateException("No media found in tweet '" + tweetId + "'");

        final String title = tweet.user != null ? tweet.user.name : tweet.text;
        final String author = tweet.user != null ? tweet.user.screenName : null;

        final RequestHeaders headers = RequestHeaders.defaults(uri);
        final List<DataSource> entries = new ArrayList<>(tweet.mediaDetails.length);
        for (int i = 0; i < tweet.mediaDetails.length; i++) {
            try {
                final DataSource entry = this.buildEntry(tweet, tweet.mediaDetails[i], title, author, headers);
                if (entry != null) entries.add(entry);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Failed to build entry for media {} of tweet '{}': {}", i, tweetId, e.getMessage());
            }
        }

        if (entries.isEmpty())
            throw new IllegalStateException("All media sources failed to load for tweet '" + tweetId + "'");

        LOGGER.debug(IT, "Loaded {} entry(es) for tweet '{}'", entries.size(), tweetId);
        return new PlatformData(null, entries.toArray(DataSource[]::new));
    }

    private DataSource buildEntry(final Tweet tweet, final MediaDetail media, final String title, final String author, final RequestHeaders headers) {
        if ("photo".equals(media.type)) {
            final Metadata metadata = new Metadata(title, tweet.text, Instant.parse(tweet.createdAt), 0, author);
            return new DataSource(MediaType.IMAGE, null, metadata, headers,
                    new DataQuality[] {new DataQuality(URI.create(media.mediaUrlHttps), media.originalInfo.width, media.originalInfo.height)},
                    null, null);
        }

        if ("video".equals(media.type)) {
            final URI thumbnail = URI.create(media.mediaUrlHttps);
            final Metadata metadata = new Metadata(title, tweet.text, Instant.parse(tweet.createdAt), media.videoInfo.durationMillis, author);

            final List<DataQuality> variants = new ArrayList<>();
            for (final var variant: media.videoInfo.variants) {
                final var resMatcher = RESOLUTION_PATTERN.matcher(variant.url);
                if (!resMatcher.find()) continue;
                variants.add(new DataQuality(URI.create(variant.url),
                        Integer.parseInt(resMatcher.group(1)), Integer.parseInt(resMatcher.group(2))));
            }

            if (variants.isEmpty()) return null;

            return new DataSource(MediaType.VIDEO, thumbnail, metadata, headers,
                    variants.toArray(DataQuality[]::new),
                    null, null);
        }

        LOGGER.warn(IT, "Unsupported media type '{}', skipping", media.type);
        return null;
    }

    private Tweet fetchTweet(final String tweetId) throws IOException {
        final URI apiUri = URI.create(String.format(API_URL, tweetId, API_TOKEN));
        try (final NetRequest req = NetRequest.create(apiUri).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + apiUri);
            return req.json(Tweet.class);
        }
    }

    private record Tweet(
            @SerializedName("__typename") String typename,
            String text,
            User user,
            @SerializedName("created_at") String createdAt,
            MediaDetail[] mediaDetails
    ) {}

    private record User(String name, @SerializedName("screen_name") String screenName) {}

    private record MediaDetail(
            String type,
            @SerializedName("media_url_https") String mediaUrlHttps,
            @SerializedName("video_info") VideoInfo videoInfo,
            @SerializedName("original_info") MediaOriginalInfo originalInfo
    ) {}

    private record MediaOriginalInfo(
       int width,
       int height
    ) {}

    private record VideoInfo(
            @SerializedName("duration_millis") int durationMillis,
            VideoVariant[] variants
    ) {}

    private record VideoVariant(
            int bitrate,
            @SerializedName("content_type") String contentType,
            String url
    ) {}
}
