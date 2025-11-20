package org.watermedia.api.media.sources;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import org.watermedia.api.media.MRL;
import org.watermedia.tools.NetTool;

import java.io.InputStreamReader;
import java.net.URI;
import java.util.Map;

public class StreamablePlatform implements IPlatform {
    private static final String API_URL = "https://api.streamable.com/videos/";
    private static final Gson GSON = new Gson();

    @Override public String name() { return "Streamable"; }
    @Override public boolean validate(final URI uri) { return "streamable.com".equals(uri.getHost()); }

    @Override
    public MRL[] getSources(final URI uri) {
        final String videoId = uri.getPath().substring(1);

        try {
            final var connection = NetTool.connectToHTTP(new URI(API_URL + videoId), "GET");
            NetTool.validateHTTP200(connection.getResponseCode(), uri);

            try (final var is = new InputStreamReader(connection.getInputStream())) {
                final VideoData video = GSON.fromJson(is, VideoData.class);

                final var qualities = Map.of(
                    MRL.MediaQuality.of(video.files.mp4.width), new URI(video.files.mp4.url),
                    MRL.MediaQuality.of(video.files.mp4_mobile.width), new URI(video.files.mp4_mobile.url)
                );

                return new MRL[] { new MRL(MRL.MediaType.VIDEO, qualities) };
            } finally {
                connection.disconnect();
            }
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to get Streamable video sources from " + uri, e);
        }
    }

    private record VideoData(int status, int percent, String message, VideoFiles files) {

    }

    private record VideoFiles(VideoFile mp4, @SerializedName("mp4-mobile") VideoFile mp4_mobile) {

    }

    public record VideoFile(int status, String url, int framerate, int width, int height, int bitrate, int size, float duration) {

    }
}
