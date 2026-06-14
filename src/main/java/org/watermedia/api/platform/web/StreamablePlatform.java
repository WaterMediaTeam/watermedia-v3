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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public class StreamablePlatform implements IPlatform {
    public static final String NAME = "Streamable";
    private static final Marker IT = MarkerManager.getMarker(StreamablePlatform.class.getSimpleName());
    private static final String API_URL = "https://api.streamable.com/videos/";
    // STREAMABLE STATUS CODES: 0=UPLOADING, 1=PROCESSING, 2=READY, 3=ERROR
    private static final int STATUS_READY = 2;
    private static final String[] HOSTS = { "streamable.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public PlatformData getData(final URI uri) throws PlatformException {
        if (!DataTool.containsIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String videoId = uri.getPath().substring(1);

        try (final NetRequest req = NetRequest.create(URI.create(API_URL + videoId)).method("GET").accept("application/json").send()) {
            if (req.statusCode() != 200) throw new PlatformException(StreamablePlatform.class, "API for video '" + videoId + "' returned HTTP " + req.statusCode());

            final VideoData video = req.json(VideoData.class);
            if (video == null) throw new PlatformException(StreamablePlatform.class, "API returned an empty or non-JSON body for video '" + videoId + "'");

            LOGGER.debug(IT, "Streamable video '{}' raw status={} percent={}", videoId, video.status, video.percent);

            // GATE ON READINESS BEFORE TOUCHING files — A PROCESSING VIDEO HAS NO RENDITIONS YET
            if (video.status != STATUS_READY)
                throw new PlatformException(StreamablePlatform.class, "Video '" + videoId + "' is not ready (status=" + video.status
                        + ", " + video.percent + "%" + (video.message != null ? ", message: " + video.message : "") + ")");

            if (video.files == null || video.files.mp4 == null || video.files.mp4.url == null)
                throw new PlatformException(StreamablePlatform.class, "Video '" + videoId + "' is marked ready but exposes no mp4 rendition");

            final List<DataQuality> variants = new ArrayList<>(2);
            variants.add(new DataQuality(URI.create(video.files.mp4.url), video.files.mp4.width, video.files.mp4.height));
            if (video.files.mp4Small != null && video.files.mp4Small.url != null) {
                variants.add(new DataQuality(URI.create(video.files.mp4Small.url), video.files.mp4Small.width, video.files.mp4Small.height));
            }

            final URI thumbnailUri = video.thumbnail != null
                    ? URI.create(video.thumbnail.startsWith("http") ? video.thumbnail : "http:" + video.thumbnail)
                    : null;
            if (video.thumbnail == null) LOGGER.warn(IT, "Streamable video '{}' has no thumbnail", videoId);

            final long durationMs = video.files.original != null ? (long) (video.files.original.duration * 1000L) : 0L;
            final Metadata metadata = new Metadata(video.title, videoId, null, durationMs, null);

            LOGGER.info(IT, "Streamable resolved video '{}' with {} variant(s)", videoId, variants.size());
            final var entry = new DataSource(MediaType.VIDEO, thumbnailUri, metadata,
                    RequestHeaders.defaults(uri),
                    variants.toArray(DataQuality[]::new),
                    null, null);
            return new PlatformData(null, entry);
        } catch (final PlatformException e) {
            throw e; // ALREADY CARRIES A PRECISE DIAGNOSTIC (NOT-READY, NO-RENDITION, ...)
        } catch (final Exception e) {
            // HTTP/IO, JSON, OR URI FAILURES — ADD THE VIDEO CONTEXT AND KEEP THE ROOT CAUSE
            throw new PlatformException(StreamablePlatform.class, "Failed to resolve video '" + videoId + "' from " + uri, e);
        }
    }

    private record VideoData(String title, int status, int percent, String message, @SerializedName("thumbnail_url") String thumbnail, VideoFiles files) {

    }

    private record VideoFiles(VideoFile original, VideoFile mp4, @SerializedName("mp4-mobile") VideoFile mp4Small) {

    }

    public record VideoFile(int status, String url, float framerate, int width, int height, int bitrate, int size, float duration) {

    }
}
