package org.watermedia;

import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.Control;
import me.srrapero720.waterconfig.api.annotations.*;
import me.srrapero720.waterconfig.impl.fields.StringField;
import org.watermedia.api.util.MediaQuality;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Spec(value = WaterMedia.ID, format = WaterConfig.FORMAT_TOML)
public class WaterMediaConfig {

    @Spec.Field
    @Comment("CodecsAPI settings")
    public static final Decoders decoders = new Decoders();

    @Spec.Field
    @Comment("NetworkAPI settings")
    public static final Network network = new Network();

    @Spec.Field
    @Comment("MediaAPI settings")
    public static final Media media = new Media();

    @Spec(value = "decoders", disableStatic = true)
    public final static class Decoders {
        @Spec.Field
        @Comment("Decoder fails when PNG chunk data doesn't match with the CRC")
        public boolean pngFailOnCorruptedData = true;

        @Spec.Field(suffix = "MB")
        @Comment("Hard cap (in MB) for image downloads consumed by TxMediaPlayer")
        @Comment("Sources above this limit are aborted before full buffering")
        @NumberConditions(minInt = 1, math = true)
        public int maxImageSourceBytesMB = 128;

        @Spec.Field
        @Comment("Use the PNG bKGD chunk as the disposal background colour")
        @Comment("When false, transparent disposal is used (recommended for compositing).")
        public boolean pngUseBKGDChunk = false;
    }

    @Spec(value = "media", disableStatic = true)
    public final static class Media {
        @Spec.Field(suffix = "min")
        @Comment("Cleanup Interval in Minutes for all expired MRL or with errors (memory saver)")
        @Comment("MRL manager usually doesn't consume much memory, intervals below 60 minutes will not reduce memory consumption at all")
        public float mrlManagerCleanupInterval = 60.0f;

        @Spec.Field(control = Control.DROPDOWN)
        @Comment("Default quality for streaming media without a specified quality")
        public MediaQuality defaultQuality = MediaQuality.HIGHER;

        @Spec.Field
        @Comment("Disables FFMPEG engine (AT ALL)")
        public boolean disableFFMPEG = false;

        @Spec.Field
        @Comment("Enables hardware-accelerated video decoding (NVDEC, QuickSync, D3D11VA, VAAPI...)")
        @Comment("Disable if you experience stutter, lag or black video — software decoding is often smoother on some GPUs (notably AMD)")
        public boolean ffmpegHardwareAcceleration = true;

        @Spec.Field(suffix = "packets", control = Control.SEEKBAR)
        @Comment("Configures how many audio packets read when video has an audio slave")
        @Comment("Increment this value if you find YouTube videos with slow playback")
        @NumberConditions(minInt = 1, maxInt = 12)
        public int ffmpegSlavePacketReads = 3;

        @Spec.Field(suffix = "MB", control = Control.SEEKBAR)
        @Comment("VRAM budget (in MB) for keeping an animated image as one GL texture per frame")
        @Comment("Animations whose decoded frames fit under this budget skip per-frame streaming entirely (best performance, like v2)")
        @Comment("Set to 0 to force TxMediaPlayer to stream animated image frames into a single texture")
        @NumberConditions(minInt = 0, maxInt = 512)
        public int txFrameTexturesBudgetMB = 32;

        @Spec.Field
        @Comment("Enables the on-disk HTTP image cache used by TxMediaPlayer")
        public boolean txNetworkCache = true;

        @Spec.Field
        @Comment("Enables the on-disk HTTP media cache used by FFMediaPlayer for small files")
        public boolean ffmpegNetworkCache = true;

        @Spec.Field(suffix = "MB")
        @Comment("Maximum FFMediaPlayer network response size to cache, in megabytes")
        @NumberConditions(minInt = 1, math = true)
        public int ffmpegNetworkCacheMaxBytesMB = 10;

        @Spec.Field(suffix = "ms")
        @Comment("FFMediaPlayer stream probing duration (FFmpeg analyzeduration), in milliseconds")
        @Comment("Higher values help FFmpeg detect audio/video parameters (sample rate, channels) on slow or live HLS streams, at the cost of startup latency")
        @Comment("Zero leaves FFmpeg's own default in place")
        @NumberConditions(minInt = 0, maxInt = 60_000)
        public int ffmpegAnalyzeDurationMs = 7000;

        @Spec.Field(suffix = "MB")
        @Comment("FFMediaPlayer stream probing size (FFmpeg probesize), in megabytes")
        @Comment("Higher values let FFmpeg read more data while detecting stream parameters, improving codec detection on streams with sparse headers")
        @NumberConditions(minInt = 1, math = true)
        public int ffmpegProbeSizeMB = 10;

        @Spec.Field(control = Control.INPUT_FOLDER)
        @Comment("Adds this path to the discovery path")
        @Comment("Path must not be the fat ffmpeg.exe file")
        public Path customFFMPEGPath = new File("").toPath();

        @Spec.Field
        @Comment("Platform-specific settings")
        public final Platforms platforms = new Platforms();
    }

    @Spec(value = "platforms", disableStatic = true)
    public final static class Platforms {
        @Spec.Field(control = Control.PASSWORD)
        @Comment("BiliBili session cookie for authenticated access")
        @Comment("Without it only 360p/480p are available, with a free account up to 1080p, VIP up to 8K")
        @Comment("Value should look like: SESSDATA=abc123; bili_jct=def456; DedeUserID=789")
        public String biliBiliCookie = "";

        @Spec.Field
        @Comment("Allow resolving streams/videos marked as mature content (e.g. Kick).")
        @Comment("When false, mature streams throw and never reach the player.")
        public boolean allowMatureContent = false;
    }

    @Spec(value = "network", disableStatic = true)
    public final static class Network {
        @Spec.Field
        @Comment("Enables file storage server")
        @Comment("Used only on server-side")
        public boolean enableServer = false;

        @Spec.Field
        @Comment("Force-Enable file storage server even on client-side")
        @Comment("NOTE: this doesn't mean that the server will be accessible from other devices, you need to open the port and set the remoteHost to your public IP or domain")
        public boolean forceEnableServer = false;

        @Spec.Field(suffix = "MB")
        @Comment("Maximum upload file size in megabytes, files larger than this will be rejected by the server")
        @Comment("Zero means unlimited, but be careful with that if your users aren't trustworthy enough")
        @Comment("NOTE: this doesn't affect manual file uploads to the server storage folder or existing files, but only uploads through the API")
        @Comment("Used only on server-side")
        @NumberConditions(minInt = 0, math = true)
        public int maxUploadSizeMB = 8;

        @Spec.Field
        @NumberConditions(minInt = 1, maxInt = 65535)
        @Comment("Sets file storage server port, ask to your provider if the port is open worldwide before start the server or blame the lib")
        @Comment("Used only on server-side")
        public int serverPort = 25580;

        @Spec.Field
        @StringConditions(value = "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$|^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)+([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9])$", regexFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, mode = StringField.Mode.REGEX)
        @Comment("Sets the remote file server to upload and download files, it must match your game server IP or domain with the given port")
        @Comment("Used only on client-side")
        public String remoteHost = "http://localhost:25580/";

        @Spec.Field(control = Control.PASSWORD)
        @Comment("The password to access the server, change this every time you have to update your server and the client to prevent external access and bandwidth abuse")
        @Comment("Used on server-side and client-side")
        public String token = "watermedia_default_token_change_it";

        @Spec.Field(suffix = "ms")
        @Comment("Default connect/read timeout for NetworkRequest, in milliseconds")
        @NumberConditions(minInt = 1000, maxInt = 600_000)
        public int requestTimeoutMs = 15_000;

        @Spec.Field(control = Control.SEEKBAR)
        @Comment("Maximum number of redirect hops NetworkRequest will follow before aborting")
        @NumberConditions(minInt = 0, maxInt = 50)
        public int maxRedirects = 10;

        @Spec.Field(suffix = "bytes")
        @Comment("Hard cap (in bytes) for NetworkRequest text/JSON bodies — anything larger throws")
        @NumberConditions(minInt = 1024, math = true)
        public int maxTextBytes = (1024 * 1024) * 16;
    }
}
