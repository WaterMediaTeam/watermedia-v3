package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public final class TwitterPlatform implements Platform {
    public static final String NAME = "Twitter";
    private static final Marker IT = MarkerManager.getMarker(TwitterPlatform.class.getSimpleName());
    private static final String API_URL = "https://cdn.syndication.twimg.com/tweet-result?id=%s&token=%s&lang=en";
    private static final String API_TOKEN = "watermedia-java-x-access-token";
    private static final Pattern ID_PATTERN = Pattern.compile("/status/(\\d+)(?:/|$)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("/(\\d{2,5})x(\\d{2,5})/");
    private static final String[] HOSTS = { "x.com", "www.x.com", "twitter.com", "www.twitter.com" };
    private static final int TITLE_LENGTH = 72;

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        // STATUS LINKS MAY END WITH A SEGMENT LIKE /video/1 OR /photo/1, SO THE ID IS NOT ANCHORED TO THE PATH END
        final Matcher m = ID_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!m.find()) throw PlatformException.unsupported(NAME, uri);

        final String tweetId = m.group(1);
        LOGGER.debug(IT, "Twitter fetching tweet '{}'", tweetId);

        final Tweet tweet = WebRequests.fetchJson(NAME, String.format(API_URL, tweetId, API_TOKEN), Tweet.class);

        if ("TweetTombstone".equals(tweet.typename)) {
            // THE TOMBSTONE TEXT SAYS WHY: AN AGE GATE IS A MATURE BLOCK, THE REST IS GONE FOR GOOD
            final String reason = tweet.tombstone != null && tweet.tombstone.text != null ? tweet.tombstone.text.text : null;
            if (reason != null && reason.toLowerCase(Locale.ROOT).contains("age-restricted") && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(NAME, "Tweet '" + tweetId + "' is age-restricted");
            throw new PlatformException(NAME, "Tweet '" + tweetId + "' is unavailable" + (reason == null ? " (deleted or protected)" : ": " + reason));
        }

        if (tweet.possiblySensitive && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(NAME, "Tweet '" + tweetId + "' is marked as sensitive");

        if (tweet.mediaDetails == null || tweet.mediaDetails.length == 0)
            throw new PlatformException(NAME, "Tweet '" + tweetId + "' carries no media (text-only or protected)");

        // THE TWEET TEXT IS THE CONTENT; THE ACCOUNT THAT POSTED IT IS THE AUTHOR
        final String text = tweet.text;
        final String title = text != null && !text.isBlank() ? truncateTitle(text) : "Tweet #" + tweetId;
        final String author = tweet.user == null ? null : tweet.user.name != null ? tweet.user.name : tweet.user.screenName;
        // PARSE THE TIMESTAMP ONCE AND REUSE ACROSS EVERY MEDIA ENTRY (A BAD DATE MUST NOT DROP MEDIA)
        final Instant postedAt = parseInstant(tweet.createdAt);

        final HttpHeaders headers = HttpHeaders.defaults(uri);
        final List<Source> entries = new ArrayList<>(tweet.mediaDetails.length);
        for (int i = 0; i < tweet.mediaDetails.length; i++) {
            try {
                final Source entry = this.buildEntry(tweet, tweet.mediaDetails[i], title, author, postedAt, headers);
                if (entry != null) entries.add(entry);
            } catch (final RuntimeException e) {
                LOGGER.warn(IT, "Twitter failed to build entry for media {} of tweet '{}'", i, tweetId, e);
            }
        }

        if (entries.isEmpty())
            throw new PlatformException(NAME, "All " + tweet.mediaDetails.length + " media source(s) failed to resolve for tweet '" + tweetId + "'");

        LOGGER.debug(IT, "Twitter resolved {} of {} media entry(es) for tweet '{}'", entries.size(), tweet.mediaDetails.length, tweetId);
        return new Resolution(null, entries);
    }

    private Source buildEntry(final Tweet tweet, final MediaDetail media, final String title, final String author, final Instant postedAt, final HttpHeaders headers) {
        if ("photo".equals(media.type)) {
            final URI photo = DataTool.uri(media.mediaUrlHttps);
            if (photo == null) {
                LOGGER.warn(IT, "Twitter photo without a usable URL, skipping");
                return null;
            }
            final int w = media.originalInfo != null ? Math.max(0, media.originalInfo.width) : 0;
            final int h = media.originalInfo != null ? Math.max(0, media.originalInfo.height) : 0;
            final Metadata metadata = new Metadata(title, tweet.text, postedAt, 0, author);
            return new Source(MediaType.IMAGE, null, metadata, headers,
                    List.of(new Quality(photo, w, h)),
                    null, null);
        }

        if ("video".equals(media.type) || "animated_gif".equals(media.type)) {
            if (media.videoInfo == null || media.videoInfo.variants == null) {
                LOGGER.warn(IT, "Twitter media type '{}' has no video_info/variants, skipping", media.type);
                return null;
            }
            final URI thumbnail = DataTool.uri(media.mediaUrlHttps);
            final Metadata metadata = new Metadata(title, tweet.text, postedAt, Math.max(0, media.videoInfo.durationMillis), author);

            final List<Quality> variants = new ArrayList<>(media.videoInfo.variants.length);
            for (final var variant: media.videoInfo.variants) {
                final URI rendition = DataTool.uri(variant.url);
                if (rendition == null) continue;
                final var resMatcher = RESOLUTION_PATTERN.matcher(variant.url);
                if (!resMatcher.find()) continue; // SKIP NON-PROGRESSIVE RENDITIONS (E.G. THE m3u8 PLAYLIST ENTRY)
                variants.add(new Quality(rendition, DataTool.parseInt(resMatcher.group(1), 0), DataTool.parseInt(resMatcher.group(2), 0)));
            }

            if (variants.isEmpty()) {
                LOGGER.warn(IT, "Twitter video media in tweet exposed {} variant(s) but none were progressive MP4 renditions, skipping", media.videoInfo.variants.length);
                return null;
            }

            return new Source(MediaType.VIDEO, thumbnail, metadata, headers,
                    variants,
                    null, null);
        }

        LOGGER.warn(IT, "Twitter unsupported media type '{}', skipping", media.type);
        return null;
    }

    private static String truncateTitle(final String text) {
        final String oneLine = text.replace('\n', ' ');
        return oneLine.length() > TITLE_LENGTH ? oneLine.substring(0, TITLE_LENGTH) + "..." : oneLine;
    }

    private static Instant parseInstant(final String iso) {
        if (iso == null) return null;
        try { return Instant.parse(iso); }
        catch (final DateTimeParseException e) { LOGGER.warn(IT, "Twitter unparseable created_at '{}'", iso, e); return null; }
    }

    private record Tweet(
            @SerializedName("__typename") String typename,
            String text,
            User user,
            @SerializedName("created_at") String createdAt,
            @SerializedName("possibly_sensitive") boolean possiblySensitive,
            MediaDetail[] mediaDetails,
            Tombstone tombstone
    ) {}

    private record User(String name, @SerializedName("screen_name") String screenName) {}

    // A TOMBSTONE CARRIES ITS REASON AS text.text ("Age-restricted adult content...", "This Post was deleted...")
    private record Tombstone(TombstoneText text) {}

    private record TombstoneText(String text) {}

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
