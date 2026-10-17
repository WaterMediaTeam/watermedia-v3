package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

public final class StreamablePlatform implements Platform {
    public static final String NAME = "Streamable";
    private static final Marker IT = MarkerManager.getMarker(StreamablePlatform.class.getSimpleName());
    private static final String API_URL = "https://api.streamable.com/videos/";
    // STREAMABLE STATUS CODES: 0=UPLOADING, 1=PROCESSING, 2=READY, 3=ERROR
    private static final int STATUS_READY = 2;
    private static final String[] HOSTS = { "streamable.com", "www.streamable.com" };

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS) || uri.getPath() == null || uri.getPath().length() < 2) return null;

        // SHARE LINKS ARE /<id>, EMBEDS /e/<id> OR /o/<id> AND /s/<id>/<slug> CARRIES A TITLE; ANY OTHER SHAPE IS FOREIGN
        final String[] segments = uri.getPath().substring(1).split("/");
        final boolean prefixed = segments.length > 1 && DataTool.equalsAnyIgnoreCase(segments[0], "e", "o", "s");
        if (segments.length > (prefixed ? ("s".equalsIgnoreCase(segments[0]) ? 3 : 2) : 1)) return null;
        final String videoId = prefixed ? segments[1] : segments[0];
        if (videoId.isEmpty()) return null;

        try {
            final VideoData video = NetRequest.fetchJson(NAME, API_URL + videoId, VideoData.class);
            LOGGER.debug(IT, "Streamable video '{}' raw status={} percent={}", videoId, video.status, video.percent);

            // GATE ON READINESS BEFORE TOUCHING files — A PROCESSING VIDEO HAS NO RENDITIONS YET
            if (video.status != STATUS_READY)
                throw new PlatformException(NAME, "Video '" + videoId + "' is not ready (status=" + video.status
                        + ", " + video.percent + "%" + (video.message != null ? ", message: " + video.message : "") + ")");

            if (video.files == null || video.files.mp4 == null || video.files.mp4.url == null)
                throw new PlatformException(NAME, "Video '" + videoId + "' is marked ready but exposes no mp4 rendition");

            final List<Quality> variants = new ArrayList<>(2);
            variants.add(new Quality(URI.create(video.files.mp4.url), video.files.mp4.width, video.files.mp4.height));
            if (video.files.mp4Small != null && video.files.mp4Small.url != null) {
                variants.add(new Quality(URI.create(video.files.mp4Small.url), video.files.mp4Small.width, video.files.mp4Small.height));
            }

            final URI thumbnailUri = video.thumbnail != null
                    ? URI.create(video.thumbnail.startsWith("http") ? video.thumbnail : "http:" + video.thumbnail)
                    : null;
            if (video.thumbnail == null) LOGGER.warn(IT, "Streamable video '{}' has no thumbnail", videoId);

            final long durationMs = video.files.original != null ? (long) (video.files.original.duration * 1000L) : 0L;
            final Metadata metadata = new Metadata(video.title, videoId, null, durationMs, null);

            LOGGER.info(IT, "Streamable resolved video '{}' with {} variant(s)", videoId, variants.size());
            final var entry = new Source(MediaType.VIDEO, thumbnailUri, metadata,
                    HttpHeaders.defaults(uri),
                    variants,
                    null, null);
            return new Resolution(null, entry);
        } catch (final PlatformException e) {
            throw e; // ALREADY CARRIES A PRECISE DIAGNOSTIC (NOT-READY, NO-RENDITION, ...)
        } catch (final Exception e) {
            // HTTP/IO, JSON, OR URI FAILURES — ADD THE VIDEO CONTEXT AND KEEP THE ROOT CAUSE
            throw new PlatformException(NAME, "Failed to resolve video '" + videoId + "' from " + uri, e);
        }
    }

    private record VideoData(String title, int status, int percent, String message, @SerializedName("thumbnail_url") String thumbnail, VideoFiles files) {

    }

    private record VideoFiles(VideoFile original, VideoFile mp4, @SerializedName("mp4-mobile") VideoFile mp4Small) {

    }

    private record VideoFile(int status, String url, float framerate, int width, int height, int bitrate, int size, float duration) {

    }
}
