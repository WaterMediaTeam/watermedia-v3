package org.watermedia.api.media;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.global.avformat;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.binding.internal.libvlc_instance_t;
import org.watermedia.videolan4j.discovery.NativeDiscovery;

public class MediaAPI extends WaterMediaAPI {
    private static final String[] SUPPORTED_MYMETYPES = {
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "video/mp4",
            "video/x-matroska",
            "video/avi",
            "video/webm",
            "video/mpeg",
            "video/quicktime",
            "video/x-msvideo",
            "video/x-flv",
            "video/x-ms-wmv",
            "video/x-mpeg",
            "video/x-matroska-3d",
            "audio/mpeg",
            "audio/wav",
            "audio/ogg",
            "audio/flac",
            "audio/aac",
            "audio/x-ms-wma",
            "audio/x-wav",
            "audio/x-matroska",
            "audio/x-flac",
            "audio/x-aac",
            "audio/x-ms-wax",
            "audio/x-mpegurl",
            "audio/x-scpls",
            "audio/x-mpeg",
    };

    protected static libvlc_instance_t VLC_INSTANCE;
    protected static AVFormatContext FFMPEG_INSTANCE;


    public static void initAllMediaStuffAsAWorkarroudnWhileWeHaveNoBootstrap() {
        VideoLan4J.load();

        // Initialize VLC instance
        VLC_INSTANCE = VideoLan4J.createInstance("--no-quiet", "--verbose", "--file-logging", "--logfile=/logs/vlc.log", "--vout=direct3d11");

        // Initialize FFmpeg libraries
        avformat.avformat_network_init();
    }
    public static libvlc_instance_t getVlcInstance() {
        return VLC_INSTANCE;
    }
}
