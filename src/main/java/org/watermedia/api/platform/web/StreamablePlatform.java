package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class StreamablePlatform implements IPlatform {
    public static final String NAME = "Streamable";
    private static final String API_URL = "https://api.streamable.com/videos/";

    @Override
    public String name() { return NAME; }

    @Override
    public boolean validate(final URI uri) { return "streamable.com".equalsIgnoreCase(uri.getHost()); }

    @Override
    public PlatformData getData(final URI uri) {
        final String videoId = uri.getPath().substring(1);

        try (final NetRequest req = NetRequest.create(URI.create(API_URL + videoId)).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new IOException("HTTP " + req.statusCode() + " for " + uri);

            final VideoData video = req.json(VideoData.class);

            final URI thumbnailUri = URI.create(!video.thumbnail.startsWith("http:") ? "http:" + video.thumbnail : video.thumbnail);
            final Metadata metadata = new Metadata(video.title, videoId, null, (long) (video.files.original.duration * 1000L), null);

            if (video.status != 2) {
                throw new IllegalStateException("Video " + videoId + " is not ready yet, status: " + video.status + ", message: " + video.message);
            }

            final List<DataQuality> variants = new ArrayList<>();
            variants.add(new DataQuality(new URI(video.files.mp4.url), video.files.mp4.width, video.files.mp4.height));
            if (video.files.mp4Small != null) {
                variants.add(new DataQuality(new URI(video.files.mp4Small.url), video.files.mp4Small.width, video.files.mp4Small.height));
            }

            final var entry = new DataSource(MediaType.VIDEO, thumbnailUri, metadata,
                    RequestHeaders.defaults(uri),
                    variants.toArray(DataQuality[]::new),
                    null, null);
            return new PlatformData(null, entry);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to get Streamable video sources from " + uri, e);
        }
    }

    private record VideoData(String title, int status, int percent, String message, @SerializedName("thumbnail_url") String thumbnail, VideoFiles files) {

    }

    private record VideoFiles(VideoFile original, VideoFile mp4, @SerializedName("mp4-mobile") VideoFile mp4Small) {

    }

    public record VideoFile(int status, String url, float framerate, int width, int height, int bitrate, int size, float duration) {

    }
}
