package org.watermedia.api.media.platform;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.tools.HlsTools;
import org.watermedia.tools.NetTool;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.text.DateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import static org.watermedia.WaterMedia.LOGGER;

public class KickPlatform implements IPlatform {
    private static final String VIDEO_API = "https://kick.com/api/v2/video/%s";
    private static final String CHANNELS_API = "https://kick.com/api/v2/channels/%s";
    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "Kick";
    }

    @Override
    public boolean validate(final URI uri) {
        return "kick.com".equalsIgnoreCase(uri.getHost());
    }

    @Override
    public MRL.Source[] getSources(final URI uri) throws Exception {
        final var path = uri.getPath().substring(1).split("/");

        if (path.length == 1) { // ASSUME IT WAS A CHANNEL NAME
            final Channel channel = this.getChannelInfo(path[0]);

            if (channel.livestream == null || !channel.livestream.is_live)
                throw new IllegalStateException("Streamer " + path[0] + " is not online");

            if (channel.is_banned)
                throw new IllegalStateException("Streamer " + path[0] + " is banned");

            if (channel.livestream.is_mature) // TODO: add option to allow mature content
                throw new IllegalStateException("Streamer " + path[0] + " is marked as mature content");

            // INIT
            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.VIDEO);
            final var r = HlsTools.fetch(channel.url);

            // BASIC METADATA
            sourceBuilder.metadata(new MRL.Metadata(
                    channel.user.username,
                    channel.livestream.session_title,
                    channel.user.profile_pic,
                    LocalDateTime.parse(channel.livestream.start_time, DATE_PATTERN).toInstant(ZoneOffset.UTC),
                    0,
                    channel.user.username)
            );

            if (r instanceof final HlsTools.MasterResult master) {
                for (final var variant: master.variants()) {
                    sourceBuilder.quality(MRL.Quality.of(variant.width(), variant.height()), URI.create(variant.uri()));
                }
            } else {
                LOGGER.warn(IT, "Failed to parse M3U8 data, proceeding to encapsulate source with high quality as default");
                sourceBuilder.quality(MRL.Quality.HIGH, channel.url);
            }

            return new MRL.Source[] { sourceBuilder.build() };

        } else {
            if (!path[0].equalsIgnoreCase("video"))
                throw new IllegalArgumentException("Invalid Kick URL: " + uri);

            final String id = path[path.length - 1];
            final Video video = this.getVideoInfo(id);

            if (video.livestream == null || video.url == null)
                throw new IllegalStateException("Video " + id + " source is unavailable");

            if (video.livestream.channel.is_banned)
                throw new IllegalStateException("Streamer " + video.livestream.channel.user.username + " is banned");

            if (video.livestream.is_mature) // TODO: add an option to support mature content
                throw new IllegalStateException("Video is marked as mature content");

            final var sourceBuilder = new MRL.SourceBuilder(MRL.MediaType.VIDEO);
            final var r = HlsTools.fetch(uri);

            sourceBuilder.metadata(new MRL.Metadata(
                    video.livestream.channel.user.username,
                    video.livestream.session_title,
                    video.livestream.channel.user.profile_pic,
                    LocalDateTime.parse(video.livestream.start_time, DATE_PATTERN).toInstant(ZoneOffset.UTC),
                    video.livestream.duration,
                    video.livestream.channel.user.username)
            );

            if (r instanceof final HlsTools.MasterResult master) {
                for (final var variant: master.variants()) {
                    sourceBuilder.quality(MRL.Quality.of(variant.width(), variant.height()), URI.create(variant.uri()));
                }
            } else {
                LOGGER.warn(IT, "Failed to parse M3U8 data, proceeding to encapsulate source with high quality as default");
                sourceBuilder.quality(MRL.Quality.HIGH, video.url);
            }

            return new MRL.Source[] { sourceBuilder.build() };
        }
    }

    private Channel getChannelInfo(final String channel) throws Exception {
        try (final InputStreamReader in = new InputStreamReader(this.getInputStream(new URI(String.format(CHANNELS_API, channel))))) {
            return GSON.fromJson(in, Channel.class);
        }
    }

    private Video getVideoInfo(final String videoId) throws Exception {
        try (final InputStreamReader in = new InputStreamReader(this.getInputStream(new URI(String.format(VIDEO_API, videoId))))) {
            return GSON.fromJson(in, Video.class);
        }
    }

    private InputStream getInputStream(final URI uri) throws IOException {
        final HttpURLConnection conn = NetTool.connectToHTTP(uri, "GET");
        conn.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        try {
            NetTool.validateHTTP200(conn.getResponseCode(), uri);
            return new ByteArrayInputStream(conn.getInputStream().readAllBytes());
        } finally {
            conn.disconnect();
        }
    }

    private record Channel(int id, boolean is_banned, Livestream livestream, User user, @SerializedName("playback_url") URI url) {


    }

    public record Livestream(int id, boolean is_live, boolean is_mature, long duration, String session_title, String start_time, Channel channel) {

    }

    public record User(int id, String username, URI profile_pic) {

    }

    private record Video(int id, Livestream livestream, @SerializedName("source") URI url) {

    }
}
