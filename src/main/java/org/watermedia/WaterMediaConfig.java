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
    private static final int DEFAULT_NETWORK_SERVER_PORT = 25572;

    @Spec.Field
    @Comment("Prints all system information like hardware, os (and version), capabilities, supported codecs and engines version (GL/VK/AL)")
    @Comment("Turns out, this impacts A LOT in bootstrap performance because it runs BAT and SH scripts to obtain certain driver capabilities (I am looking at you NVIDIA)")
    @Comment("Enable by developer request. This is usually requested once")
    public static boolean logSystemInformation = false;

    @Spec.Field
    @Comment("CodecsAPI settings")
    public static final Decoders decoders = new Decoders();

    @Spec.Field
    @Comment("NetworkAPI settings")
    public static final Network network = new Network();

    @Spec.Field
    @Comment("MediaAPI settings")
    public static final Media media = new Media();

    @Spec.Field
    @Comment("Platform-specific settings")
    public static final Platforms platforms = new Platforms();

    @Spec(value = "decoders", disableStatic = true)
    public static final class Decoders {
        @Spec.Field(suffix = "MB")
        @Comment("Hard cap (in MB) for image downloads consumed by TxMediaPlayer")
        @Comment("Sources above this limit are aborted before full buffering")
        @NumberConditions(minInt = 1, math = true)
        public int maxImageSourceBytesMB = 128;

        @Spec.Field
        @Comment("Related config for PNG codec")
        public final Png png = new Png();

        @Spec.Field
        @Comment("Related config for SVG codec")
        public final Svg svg = new Svg();

        @Spec.Field
        @Comment("Related config for VP8 codec (WEBP lossy)")
        public final Vp8 vp8 = new Vp8();

        @Spec.Field
        @Comment("Related config for GIF codec")
        public final Gif gif = new Gif();

        @Spec(value = "svg", disableStatic = true)
        public static final class Svg {
            @Spec.Field(suffix = "px")
            @Comment("Default maximum dimension (px) when rasterizing vector SVG images")
            @Comment("SVG is resolution-independent; the larger raster side is capped to this, preserving aspect ratio (never upscaled)")
            @NumberConditions(minInt = 1, maxInt = 8192, math = true)
            public int maxSize = 512;
        }

        @Spec(value = "png", disableStatic = true)
        public static final class Png {
            @Spec.Field
            @Comment("Decoder fails when PNG chunk data doesn't match with the CRC")
            public boolean failOnCorruptedData = true;

            @Spec.Field
            @Comment("Use the PNG bKGD chunk as the disposal background colour")
            @Comment("When false, transparent disposal is used (recommended for compositing).")
            public boolean useBKGDChunk = false;
        }

        @Spec(value = "vp8", disableStatic = true)
        public static final class Vp8 {
            @Spec.Field
            @Comment("Attempt to repair VP8 frames declaring broken token partition sizes instead of failing the decode")
            @Comment("Broken sizes are clamped to the bytes actually available; empty partitions are replaced with a duplicate of the previous one or with fake zeroed data")
            @Comment("Repaired frames decode best-effort and may show visual artifacts")
            public boolean brokenTokens = false;
        }

        @Spec(value = "gif", disableStatic = true)
        public static final class Gif {
            @Spec.Field
            @Comment("Clamp animation frames whose image descriptor extends beyond the logical screen instead of failing the decode")
            @Comment("When false an out-of-bounds frame throws; when true it is clamped to the canvas (best-effort, may show artifacts)")
            public boolean clampImageDesc = false;
        }
    }

    @Spec(value = "media", disableStatic = true)
    public final static class Media {
        @Spec.Field(control = Control.DROPDOWN)
        @Comment("Default quality for streaming media without a specified quality")
        public MediaQuality defaultQuality = MediaQuality.HIGHER;

        @Spec.Field(suffix = "min")
        @Comment("Cleanup Interval in Minutes for all expired MRL or with errors (memory saver)")
        @Comment("MRL manager usually doesn't consume much memory, intervals below 60 minutes will not reduce memory consumption at all")
        public float cleanupInterval = 60.0f;

        @Spec.Field
        @Comment("FFMPEG general settings")
        public final Ffmpeg ffmpeg = new Ffmpeg();

        @Spec.Field
        @Comment("WaterTexture general settings")
        public final WaterTx tx = new WaterTx();

        @Spec(value = "ffmpeg", disableStatic = true)
        public static final class Ffmpeg {
            @Spec.Field
            @Comment("Disables FFMPEG bootstrap, run this requires a restart")
            public boolean disable = false;

            @Spec.Field(control = Control.INPUT_FOLDER)
            @Comment("Adds this path to the discovery path")
            @Comment("Path must not be the fat ffmpeg.exe file")
            public Path customPath = new File("").toPath();

            @Spec.Field
            @Comment("Enables hardware-accelerated video decoding (NVDEC, QuickSync, D3D11VA, VAAPI...)")
            @Comment("Disable if you experience stutter, lag or black video — software decoding is often smoother on some GPUs (notably AMD)")
            public boolean hardwareAccel = true;

            @Spec.Field(suffix = "packets", control = Control.SEEKBAR)
            @Comment("Configures how many audio packets read when video has an audio slave")
            @Comment("Increment this value if you find YouTube videos with slow playback")
            @NumberConditions(minInt = 1, maxInt = 12)
            public int slavePacketReads = 3;

            @Spec.Field
            @Comment("Enables the on-disk HTTP media cache used by FFMediaPlayer for small files")
            public boolean cache = true;

            @Spec.Field(suffix = "MB")
            @Comment("Maximum FFMediaPlayer network response size to cache, in megabytes")
            @NumberConditions(minInt = 1, math = true)
            public int cacheMaxSize = 10;

            @Spec.Field(suffix = "ms")
            @Comment("FFMediaPlayer stream probing duration (FFmpeg analyzeduration), in milliseconds")
            @Comment("Higher values help FFmpeg detect audio/video parameters (sample rate, channels) on slow or live HLS streams, at the cost of startup latency")
            @Comment("Zero leaves FFmpeg's own default in place")
            @NumberConditions(minLong = 0L, maxLong = 60_000)
            public long analyzeDuration = 7000;

            @Spec.Field(suffix = "MB")
            @Comment("FFMediaPlayer stream probing size (FFmpeg probesize), in megabytes")
            @Comment("Higher values let FFmpeg read more data while detecting stream parameters, improving codec detection on streams with sparse headers")
            @NumberConditions(minInt = 1, math = true)
            public int probeSize = 10;
        }

        @Spec(value = "watertx", disableStatic = true)
        public static final class WaterTx { // PUBLICLY KNOWN AS WaterTX (WaterTexture), INTERNALLY CLAMPED INTO TX.
            @Spec.Field
            @Comment("Enables the on-disk HTTP image cache used by TxMediaPlayer")
            public boolean cache = true;

            @Spec.Field
            @Comment("Enables the on-disk BC7/DDS codec cache used by TxMediaPlayer")
            @Comment("Decoded frames are recompressed to GPU block-compressed textures (DDS) so replays skip the decode and use a quarter of the VRAM")
            @Comment("Requires GPU block-compression support; ignored when the BC codecs are unavailable")
            public boolean codecCache = false;

            @Spec.Field(suffix = "MB", control = Control.SEEKBAR)
            @Comment("VRAM budget (in MB) for keeping an animated image as one GL texture per frame")
            @Comment("Animations whose decoded frames fit under this budget skip per-frame streaming entirely (best performance, like v2)")
            @Comment("Set to 0 to force TxMediaPlayer to stream animated image frames into a single texture")
            @NumberConditions(minInt = 0, maxInt = 512)
            public int texturesBudget = 32;
        }
    }

    @Spec(value = "platforms", disableStatic = true)
    public final static class Platforms {
        @Spec.Field
        @Comment("Allow resolving streams/videos marked as mature content (e.g. Kick).")
        @Comment("When false, mature streams throw and never reach the player.")
        public boolean allowMatureContent = false;

        @Spec.Field(suffix = "min")
        @Comment("Cleanup interval in minutes for the in-memory platform search results cache")
        @Comment("Identical searches are served from memory until the whole cache is flushed on this interval (general sweep)")
        @Comment("Set to 0 to disable caching and always query the platforms")
        public float searchCacheCleanup = 15.0f;

        @Spec.Field(control = Control.PASSWORD)
        @Comment("BiliBili session cookie for authenticated access")
        @Comment("Without it only 360p/480p are available, with a free account up to 1080p, VIP up to 8K")
        @Comment("Value should look like: SESSDATA=abc123; bili_jct=def456; DedeUserID=789")
        public String biliBiliCookie = "";
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
        public int serverPort = DEFAULT_NETWORK_SERVER_PORT;

        @Spec.Field
        // FULL URL (scheme://host[:port][/path]); host MAY BE AN IPv4 OR A DOMAIN. MATCHES THE DEFAULT AND
        // THE FORM NetworkAPI.upload BUILDS ("<remoteHost>/upload"), UNLIKE THE OLD BARE-HOST-ONLY REGEX.
        @StringConditions(value = "^https?://([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?)(\\.([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?))*(:\\d{1,5})?(/.*)?$", regexFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, mode = StringField.Mode.REGEX)
        @Comment("Sets the remote file server to upload and download files, it must match your game server IP or domain with the given port")
        @Comment("Include the scheme and port, e.g. http://your-host:25572/")
        @Comment("Used only on client-side")
        public String remoteHost = "http://localhost:" + DEFAULT_NETWORK_SERVER_PORT + "/";

        @Spec.Field(control = Control.PASSWORD)
        @Comment("The password to access the server, change this every time you have to update your server and the client to prevent external access and bandwidth abuse")
        @Comment("Used on server-side and client-side")
        public String token = "watermedia_default_token_change_it";

        @Spec.Field(suffix = "ms")
        @Comment("Default connect/read timeout for NetworkRequest, in milliseconds")
        @NumberConditions(minInt = 1000, maxInt = 600_000)
        public int timeout = 15_000;

        @Spec.Field(control = Control.SEEKBAR)
        @Comment("Maximum number of redirect hops NetworkRequest will follow before aborting")
        @NumberConditions(minInt = 0, maxInt = 50)
        public int maxRedirects = 10;

        @Spec.Field(suffix = "bytes")
        @Comment("Hard cap (in bytes) for NetworkRequest text/JSON bodies — anything larger throws")
        @NumberConditions(minInt = 1024, math = true)
        public int maxTextSize = (1024 * 1024) * 16;
    }
}
