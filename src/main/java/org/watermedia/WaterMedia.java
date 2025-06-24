package org.watermedia;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.omegaconfig.OmegaConfig;
import org.watermedia.tools.IOTool;

import java.io.File;
import java.nio.file.Path;

public class WaterMedia {
    private static final Marker IT = MarkerManager.getMarker(WaterMedia.class.getSimpleName());
    public static final String ID = "watermedia";
    public static final String NAME = "WATERMeDIA";
    public static final String VERSION = IOTool.getVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final Logger LOGGER = LogManager.getLogger(ID);

    private static final Path DEFAULT_TEMP = new File(System.getProperty("java.io.tmpdir")).toPath().toAbsolutePath().resolve("watermedia");
    private static final Path DEFAULT_CWD = new File("run").toPath().toAbsolutePath();

    private static Loader loader;
    private static WaterMedia instance;
    private WaterMedia() {}

    /**
     * Prepares the WaterMedia instance with the specified loader.
     * NOTE: Each environment MUST have its own loader.
     * @param boot the loader to use for preparation
     * @return the prepared WaterMedia instance
     * @throws NullPointerException if the boot loader is null
     * @throws IllegalStateException if WaterMedia is already prepared
     */
    public static WaterMedia prepare(Loader boot) {
        if (boot == null) throw new NullPointerException("Bootstrap is null");
        if (instance != null) throw new IllegalStateException(NAME + " is already prepared");

        LOGGER.info(IT, "Preparing '{}' for '{}'", NAME, boot.name());
        LOGGER.info(IT, "Loading {} version '{}'", NAME, VERSION);
        LOGGER.info(IT, "Detected OS: {} ({})", System.getProperty("os.name"), System.getProperty("os.arch"));

        LOGGER.info(IT, "Registering config file");
        OmegaConfig.register(WaterMediaConfig.class);
        LOGGER.info(IT, "Successfully registered config file");

        loader = boot;
        return instance = new WaterMedia();
    }

    /**
     * Returns the current WaterMedia instance.
     * @return the current WaterMedia instance, or throws an exception if not prepared
     */
    public static Loader getLoader() {
        if (loader == null) throw new IllegalStateException("WaterMedia is not prepared, call WaterMedia.prepare() first");
        return loader;
    }

    public static String createId(String path) { return WaterMedia.ID + ":" + path; }


    /**
     * Prepares the WaterMedia instance with the specified loader.
     * NOTE: Each environment MUST have its own loader.
     */
    public static abstract class Loader {
        /**
         * Name of the loader.
         */
        public abstract String name();

        /**
         * Returns the temporary directory for the loader.
         */
        public Path tmp() {
            return DEFAULT_TEMP;
        }

        /**
         * Returns the current working directory for the loader.
         */
        public Path cwd() {
            return DEFAULT_CWD;
        }

        /**
         * Indicates if the loader is for a client environment.
         * ITs is used to determine if the loader is intended for server-side operations, such as the static server API.
         * @return true if the loader is for a client, false otherwise
         */
        public abstract boolean client();
    }
}
