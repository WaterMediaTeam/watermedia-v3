package org.watermedia;

import org.omegaconfig.OmegaConfig;
import org.omegaconfig.api.annotations.NumberConditions;
import org.omegaconfig.api.annotations.Spec;
import org.omegaconfig.api.annotations.StringConditions;
import org.omegaconfig.impl.fields.StringField;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Spec(value = WaterMedia.ID, suffix = "client", format = OmegaConfig.FORMAT_JSON5)
public class WaterMediaConfig {
    @Spec.Field
    private static boolean videolanEnabled = true;

    @Spec.Field
    private static Path customVideolanPath = new File("").toPath();

    @Spec.Field
    private static boolean ffmpegEnabled = true;

    @Spec.Field
    private static Path customFFmpegPath = new File("").toPath();


    @Spec(value = "server")
    public static class Server {
        @Spec.Field
        private static boolean enabled = false;

        @Spec.Field
        @StringConditions(value = "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$|^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)+([A-Za-z]|[A-Za-z][A-Za-z0-9\\-]*[A-Za-z0-9])$", regexFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, mode = StringField.Mode.REGEX)
        private static String host = "localhost";

        @Spec.Field
        @NumberConditions(minInt = 1, maxInt = 65535)
        private static int port = 25580;
    }
}
