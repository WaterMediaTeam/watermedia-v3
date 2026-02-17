package org.watermedia;

import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.annotations.Comment;
import me.srrapero720.waterconfig.api.annotations.NumberConditions;
import me.srrapero720.waterconfig.api.annotations.Spec;
import me.srrapero720.waterconfig.api.annotations.StringConditions;
import me.srrapero720.waterconfig.impl.fields.StringField;
import org.watermedia.api.media.MRL;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Spec(value = WaterMedia.ID, suffix = "common", format = WaterConfig.FORMAT_TOML)
public class WaterMediaConfig {

    @Spec.Field
    @Comment("DecodersAPI settings")
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

        @Spec.Field
        @Comment("Disposal")
        public boolean pngUseBKGDChunk = false;
    }

    @Spec(value = "media", disableStatic = true)
    public final static class Media {
        @Spec.Field
        public MRL.Quality defaultQuality = MRL.Quality.HIGHEST;

        @Spec.Field
        @Comment("Disables FFMPEG engine (AT ALL)")
        public boolean disableFFMPEG = false;

        @Spec.Field
        @Comment("Adds this path to the discovery path")
        @Comment("Path must not be the fat ffmpeg.exe file")
        public Path customFFmpegPath = new File("").toPath();
    }

    @Spec(value = "network", disableStatic = true)
    public final static class Network {
        @Spec.Field
        @Comment("Enables file storage server")
        @Comment("Used only on server-side")
        public boolean enableServer = false;

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

        @Spec.Field
        @Comment("The password to access the server, change this every time you have to update your server and the client to prevent external access and bandwidth abuse")
        @Comment("Used on server-side and client-side")
        public String token = "watermedia_default_token_change_it";
    }
}