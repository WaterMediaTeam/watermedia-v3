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
    private static final String[] HOSTS = { "x.com", "www.x.com", "twitter.com", "www.twitter.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        if (uri.getPath() == null || !ID_PATTERN.matcher(uri.getPath()).find()) return null;

        String path = uri.getPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        final Matcher m = ID_PATTERN.matcher(path);
        if (!m.find()) throw new PlatformException(TwitterPlatform.class, "No tweet ID found in URL: " + uri);

        final String tweetId = m.group(1);
        LOGGER.debug(IT, "Twitter fetching tweet '{}'", tweetId);

        final Tweet tweet = this.fetchTweet(tweetId);

        if ("TweetTombstone".equals(tweet.typename))
            throw new PlatformException(TwitterPlatform.class, "Tweet '" + tweetId + "' is unavailable (tombstoned / age-restricted / deleted)");

        if (tweet.mediaDetails == null || tweet.mediaDetails.length == 0)
            throw new PlatformException(TwitterPlatform.class, "Tweet '" + tweetId + "' carries no media (text-only or protected)");

        final String title = tweet.user != null ? tweet.user.name : tweet.text;
        final String author = tweet.user != null ? tweet.user.screenName : null;
        // PARSE THE TIMESTAMP ONCE AND REUSE ACROSS EVERY MEDIA ENTRY (A BAD DATE MUST NOT DROP MEDIA)
        final Instant postedAt = parseInstant(tweet.createdAt);

        final RequestHeaders headers = RequestHeaders.defaults(uri);
        final List<DataSource> entries = new ArrayList<>(tweet.mediaDetails.length);
        for (int i = 0; i < tweet.mediaDetails.length; i++) {
            try {
                final DataSource entry = this.buildEntry(tweet, tweet.mediaDetails[i], title, author, postedAt, headers);
                if (entry != null) entries.add(entry);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Twitter failed to build entry for media {} of tweet '{}': {}", i, tweetId, e.getMessage());
            }
        }

        if (entries.isEmpty())
            throw new PlatformException(TwitterPlatform.class, "All " + tweet.mediaDetails.length + " media source(s) failed to resolve for tweet '" + tweetId + "'");

        LOGGER.info(IT, "Twitter resolved {} of {} media entry(es) for tweet '{}'", entries.size(), tweet.mediaDetails.length, tweetId);
        return new PlatformData(null, entries.toArray(DataSource[]::new));
    }

    private DataSource buildEntry(final Tweet tweet, final MediaDetail media, final String title, final String author, final Instant postedAt, final RequestHeaders headers) {
        if ("photo".equals(media.type)) {
            final int w = media.originalInfo != null ? media.originalInfo.width : 0;
            final int h = media.originalInfo != null ? media.originalInfo.height : 0;
            final Metadata metadata = new Metadata(title, tweet.text, postedAt, 0, author);
            return new DataSource(MediaType.IMAGE, null, metadata, headers,
                    new DataQuality[] {new DataQuality(URI.create(media.mediaUrlHttps), w, h)},
                    null, null);
        }

        if ("video".equals(media.type) || "animated_gif".equals(media.type)) {
            if (media.videoInfo == null || media.videoInfo.variants == null) {
                LOGGER.warn(IT, "Twitter media type '{}' has no video_info/variants, skipping", media.type);
                return null;
            }
            final URI thumbnail = media.mediaUrlHttps != null ? URI.create(media.mediaUrlHttps) : null;
            final Metadata metadata = new Metadata(title, tweet.text, postedAt, media.videoInfo.durationMillis, author);

            final List<DataQuality> variants = new ArrayList<>(media.videoInfo.variants.length);
            for (final var variant: media.videoInfo.variants) {
                if (variant.url == null) continue;
                final var resMatcher = RESOLUTION_PATTERN.matcher(variant.url);
                if (!resMatcher.find()) continue; // SKIP NON-PROGRESSIVE RENDITIONS (E.G. THE m3u8 PLAYLIST ENTRY)
                variants.add(new DataQuality(URI.create(variant.url),
                        Integer.parseInt(resMatcher.group(1)), Integer.parseInt(resMatcher.group(2))));
            }

            if (variants.isEmpty()) {
                LOGGER.warn(IT, "Twitter video media in tweet exposed {} variant(s) but none were progressive MP4 renditions, skipping", media.videoInfo.variants.length);
                return null;
            }

            return new DataSource(MediaType.VIDEO, thumbnail, metadata, headers,
                    variants.toArray(DataQuality[]::new),
                    null, null);
        }

        LOGGER.warn(IT, "Twitter unsupported media type '{}', skipping", media.type);
        return null;
    }

    private Tweet fetchTweet(final String tweetId) throws IOException {
        final URI apiUri = URI.create(String.format(API_URL, tweetId, API_TOKEN));
        try (final NetRequest req = NetRequest.create(apiUri).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(TwitterPlatform.class, "Syndication API for tweet '" + tweetId + "' returned HTTP " + req.statusCode());
            final Tweet tweet = req.json(Tweet.class);
            if (tweet == null) throw new PlatformException(TwitterPlatform.class, "Syndication API returned an empty or non-JSON body for tweet '" + tweetId + "'");
            return tweet;
        }
    }

    private static Instant parseInstant(final String iso) {
        if (iso == null) return null;
        try { return Instant.parse(iso); }
        catch (final Exception e) { LOGGER.warn(IT, "Twitter unparseable created_at '{}'", iso); return null; }
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
