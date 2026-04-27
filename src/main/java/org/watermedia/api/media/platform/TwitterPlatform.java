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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class TwitterPlatform implements IPlatform {
    public static final String NAME = "Twitter";
    private static final String API_URL = "https://cdn.syndication.twimg.com/tweet-result?id=%s&token=%s&lang=en";
    private static final String API_TOKEN = "watermedia-java-x-access-token";
    private static final Pattern ID_PATTERN = Pattern.compile("status/(\\d+)$");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("/(\\d{2,5})x(\\d{2,5})/");
    private static final Gson GSON = new Gson();

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
    public Result getSources(final URI uri) throws Exception {
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

        final List<MRL.Source> sources = new ArrayList<>(tweet.mediaDetails.length);
        for (int i = 0; i < tweet.mediaDetails.length; i++) {
            try {
                final MRL.Source source = this.buildSource(tweet, tweet.mediaDetails[i], title, author);
                if (source != null) sources.add(source);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Failed to build source for media {} of tweet '{}': {}", i, tweetId, e.getMessage());
            }
        }

        if (sources.isEmpty())
            throw new IllegalStateException("All media sources failed to load for tweet '" + tweetId + "'");

        LOGGER.debug(IT, "Loaded {} source(s) for tweet '{}'", sources.size(), tweetId);
        return new Result(null, sources.toArray(MRL.Source[]::new));
    }

    private MRL.Source buildSource(final Tweet tweet, final MediaDetail media, final String title, final String author) {
        if ("photo".equals(media.type)) {
            return MRL.sourceBuilder(MRL.MediaType.IMAGE)
                    .quality(MRL.Quality.of(media.originalInfo.width, media.originalInfo.height), URI.create(media.mediaUrlHttps))
                    .metadata(new MRL.Metadata(title, tweet.text, null, Instant.parse(tweet.createdAt), 0, author))
                    .build();
        }

        if ("video".equals(media.type)) {
            final MRL.SourceBuilder builder = MRL.sourceBuilder(MRL.MediaType.VIDEO)
                    .metadata(new MRL.Metadata(title, tweet.text, URI.create(media.mediaUrlHttps), Instant.parse(tweet.createdAt), media.videoInfo.durationMillis, author));

            for (final var variant: media.videoInfo.variants) {
                final var resMatcher = RESOLUTION_PATTERN.matcher(variant.url);
                if (!resMatcher.find()) continue;

                builder.quality(MRL.Quality.of(Integer.parseInt(resMatcher.group(1)), Integer.parseInt(resMatcher.group(2))), URI.create(variant.url));
            }

            return builder.build();
        }

        LOGGER.warn(IT, "Unsupported media type '{}', skipping", media.type);
        return null;
    }

    private Tweet fetchTweet(final String tweetId) throws IOException {
        final URI apiUri = URI.create(String.format(API_URL, tweetId, API_TOKEN));
        final HttpURLConnection conn = NetTool.connectToHTTP(apiUri, "GET", "application/json");

        try {
            NetTool.validateHTTP200(conn.getResponseCode(), apiUri);
            try (final InputStream in = conn.getInputStream()) {
                return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Tweet.class);
            }
        } finally {
            conn.disconnect();
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
