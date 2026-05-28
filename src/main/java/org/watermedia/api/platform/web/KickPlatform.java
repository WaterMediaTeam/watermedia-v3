package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.HlsTool;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.watermedia.WaterMedia.LOGGER;

public class KickPlatform implements IPlatform {
    public static final String NAME = "Kick";
    private static final Marker IT = MarkerManager.getMarker(KickPlatform.class.getSimpleName());
    private static final String VIDEO_API = "https://kick.com/api/v2/video/%s";
    private static final String CHANNELS_API = "https://kick.com/api/v2/channels/%s";
    private static final String CLIPS_API = "https://kick.com/api/v2/clips/%s/play";
    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() { return "Kick"; }

    @Override
    public boolean validate(final URI uri) {
        return "kick.com".equalsIgnoreCase(uri.getHost());
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final String clipId = clipId(uri);
        if (clipId != null) // /<channel>/clips/clip_... OR /<channel>?clip=clip_...
            return this.getClipData(uri, clipId);

        final var path = uri.getPath().substring(1).split("/");

        if (path.length == 1) { // ASSUME IT WAS A CHANNEL NAME
            final Channel channel = this.getChannelInfo(path[0]);

            if (channel.livestream == null || !channel.livestream.is_live)
                throw new IllegalStateException("Streamer " + path[0] + " is not online");

            if (channel.is_banned)
                throw new IllegalStateException("Streamer " + path[0] + " is banned");

            if (channel.livestream.is_mature && !WaterMediaConfig.media.platforms.allowMatureContent)
                throw new IllegalStateException("Streamer " + path[0] + " is marked as mature content");

            // INIT
            final var r = HlsTool.fetch(channel.url);

            // BASIC METADATA
            final Metadata metadata = new Metadata(
                    channel.user.username,
                    channel.livestream.session_title,
                    LocalDateTime.parse(channel.livestream.start_time, DATE_PATTERN).toInstant(ZoneOffset.UTC),
                    0,
                    channel.user.username);

            final DataQuality[] variants = variantsFrom(r, channel.url);

            // WE MAKE IT EXPIRES EVERY 30 SECONDS BECAUSE... I AM REALLY NOT SURE IF THE LINKS EVEN EXPIRES
            final var entry = new DataSource(MediaType.VIDEO, channel.user.profile_pic, metadata,
                    RequestHeaders.defaults(uri),
                    variants,
                    null, null);
            return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entry);

        } else {
            if (!path[0].equalsIgnoreCase("video"))
                throw new IllegalArgumentException("Invalid Kick URL: " + uri);

            final String id = path[path.length - 1];
            final Video video = this.getVideoInfo(id);

            if (video.livestream == null || video.url == null)
                throw new IllegalStateException("Video " + id + " uri is unavailable");

            if (video.livestream.channel.is_banned)
                throw new IllegalStateException("Streamer " + video.livestream.channel.user.username + " is banned");

            if (video.livestream.is_mature && !WaterMediaConfig.media.platforms.allowMatureContent)
                throw new IllegalStateException("Video is marked as mature content");

            final var r = HlsTool.fetch(uri);

            final Metadata vodMetadata = new Metadata(
                    video.livestream.channel.user.username,
                    video.livestream.session_title,
                    LocalDateTime.parse(video.livestream.start_time, DATE_PATTERN).toInstant(ZoneOffset.UTC),
                    video.livestream.duration,
                    video.livestream.channel.user.username);

            final DataQuality[] vodVariants = variantsFrom(r, video.url);

            final var entry = new DataSource(MediaType.VIDEO, video.livestream.channel.user.profile_pic, vodMetadata,
                    RequestHeaders.defaults(uri),
                    vodVariants,
                    null, null);
            return new PlatformData(Instant.now().plus(30, ChronoUnit.MINUTES), entry);
        }
    }

    private Channel getChannelInfo(final String channel) throws Exception {
        try (final NetRequest req = NetRequest.create(URI.create(String.format(CHANNELS_API, channel))).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + req.uri());
            return req.json(Channel.class);
        }
    }

    private Video getVideoInfo(final String videoId) throws Exception {
        try (final NetRequest req = NetRequest.create(URI.create(String.format(VIDEO_API, videoId))).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + req.uri());
            return req.json(Video.class);
        }
    }

    private PlatformData getClipData(final URI uri, final String clipId) throws Exception {
        final Clip clip = this.getClipInfo(clipId);

        if (clip.clipUrl == null)
            throw new IllegalStateException("Clip " + clipId + " uri is unavailable");

        if (clip.isMature && !WaterMediaConfig.media.platforms.allowMatureContent)
            throw new IllegalStateException("Clip " + clipId + " is marked as mature content");

        // CLIP URL MAY BE A DIRECT FILE (mp4/webm) OR AN M3U8 PLAYLIST
        final DataQuality[] variants;
        if (clip.clipUrl.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
            variants = variantsFrom(HlsTool.fetch(clip.clipUrl), clip.clipUrl);
        } else {
            variants = new DataQuality[] { new DataQuality(clip.clipUrl, 0, 0) };
        }

        final Metadata metadata = new Metadata(
                clip.title,
                clip.category != null ? clip.category.name : null,
                parseIso(clip.createdAt),
                (long) (clip.duration * 1000L),
                clip.creator != null ? clip.creator.username : null);

        final var entry = new DataSource(MediaType.VIDEO, clip.thumbnail, metadata,
                RequestHeaders.defaults(uri),
                variants,
                null, null);
        return new PlatformData(null, entry);
    }

    private Clip getClipInfo(final String clipId) throws Exception {
        try (final NetRequest req = NetRequest.create(URI.create(String.format(CLIPS_API, clipId))).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + req.uri());
            final ClipResponse response = req.json(ClipResponse.class);
            if (response == null || response.clip == null) throw new IllegalStateException("Clip " + clipId + " is unavailable");
            return response.clip;
        }
    }

    // RESOLVES THE clip_... ID FROM BOTH URL SHAPES: /<channel>/clips/clip_... AND /<channel>?clip=clip_...
    // RETURNS NULL WHEN THE URI IS NOT A CLIP (LIVE/VOD)
    private static String clipId(final URI uri) {
        final String query = uri.getQuery();
        if (query != null) {
            for (final String part: query.split("&")) {
                if (part.startsWith("clip=")) {
                    final String value = part.substring("clip=".length());
                    if (value.startsWith("clip_")) return value;
                }
            }
        }

        final String[] path = uri.getPath().substring(1).split("/");
        for (int i = 0; i < path.length - 1; i++) {
            if (path[i].equalsIgnoreCase("clips") && path[i + 1].startsWith("clip_")) return path[i + 1];
        }
        return null;
    }

    private static DataQuality[] variantsFrom(final HlsTool.Result result, final URI fallback) {
        final List<DataQuality> variants = new ArrayList<>();
        if (result instanceof final HlsTool.MasterResult master) {
            for (final var variant: master.variants()) {
                variants.add(new DataQuality(URI.create(variant.uri()), variant.width(), variant.height()));
            }
        }
        if (variants.isEmpty()) {
            LOGGER.warn(IT, "Failed to parse M3U8 data, proceeding to encapsulate uri with high quality as default");
            variants.add(new DataQuality(fallback, 0, 0));
        }
        return variants.toArray(DataQuality[]::new);
    }

    // CLIP TIMESTAMPS ARE ISO-8601 (created_at), UNLIKE THE "yyyy-MM-dd HH:mm:ss" USED BY LIVE/VOD
    private static Instant parseIso(final String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value, DATE_PATTERN).toInstant(ZoneOffset.UTC);
            } catch (final DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private record Channel(int id, boolean is_banned, Livestream livestream, User user, @SerializedName("playback_url") URI url) {


    }

    public record Livestream(int id, boolean is_live, boolean is_mature, long duration, String session_title, String start_time, Channel channel) {

    }

    public record User(int id, String username, URI profile_pic) {

    }

    private record Video(int id, Livestream livestream, @SerializedName("uri") URI url) {

    }

    private record ClipResponse(Clip clip) {

    }

    private record Clip(@SerializedName("clip_url") URI clipUrl, String title, Creator creator,
                        @SerializedName("thumbnail_url") URI thumbnail, float duration, Category category,
                        @SerializedName("created_at") String createdAt, @SerializedName("is_mature") boolean isMature) {

    }

    private record Creator(int id, String username) {

    }

    private record Category(int id, String name) {

    }
}
