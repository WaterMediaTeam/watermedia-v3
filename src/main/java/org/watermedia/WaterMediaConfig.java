package org.watermedia;

import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.annotations.Comment;
import me.srrapero720.waterconfig.api.annotations.NumberConditions;
import me.srrapero720.waterconfig.api.annotations.Spec;
import me.srrapero720.waterconfig.api.annotations.StringConditions;
import me.srrapero720.waterconfig.impl.fields.StringField;
import org.watermedia.api.media.MRL;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Spec(value = WaterMedia.ID, suffix = "common", format = WaterConfig.FORMAT_TOML)
public class WaterMediaConfig {

    @Spec.Field
    public static String remoteHost = "http://localhost:25580/";

    @Spec.Field
    public static String remoteToken = "watermedia_default_token_change_it";

    @Spec.Field
    public static int remotePort = 25580;

    @Spec.Field
    public static MRL.Quality defaultMediaQuality = MRL.Quality.HIGHEST;

    @Spec.Field
    public static boolean ffmpegEnabled = true;

    @Spec.Field
    public static Path customFFmpegPath = new File("").toPath();

    @Spec.Field
    public static boolean pngDecoder$useBKGDChunk = false;

    @Spec.Field
    @Comment("Decoder fails when PNG chunk data doesn't match with the CRC")
    public static boolean pngDecoder$failOnCorruptedData = true;




    @Spec(value = "server")
    public static class Server {
        @Spec.Field
        public static boolean enabled = false;

        @Spec.Field
        @StringConditions(value = "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$|^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)+([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9])$", regexFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, mode = StringField.Mode.REGEX)
        public static String host = "localhost";

        @Spec.Field
        @NumberConditions(minInt = 1, maxInt = 65535)
        public static int port = 25580;
    }
}
