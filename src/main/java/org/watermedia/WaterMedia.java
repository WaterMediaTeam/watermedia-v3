package org.watermedia;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.omegaconfig.OmegaConfig;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.tools.IOTool;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.ServiceLoader;

public class WaterMedia {
    private static final Marker IT = MarkerManager.getMarker(WaterMedia.class.getSimpleName());
    public static final String ID = "watermedia";
    public static final String NAME = "WATERMeDIA";
    public static final String VERSION = IOTool.getVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final Logger LOGGER = LogManager.getLogger(ID);

    // DEFAULT OP
    private static final Path DEFAULT_TEMP = new File(System.getProperty("java.io.tmpdir")).toPath().toAbsolutePath().resolve("watermedia");
    private static final Path DEFAULT_CWD = new File("run").toPath().toAbsolutePath();

    private static WaterMedia instance;
    private static ServiceLoader<WaterMediaAPI> apis;
    public final String name;
    public final Path tmp, cwd;
    public final boolean clientSide;

    private WaterMedia(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        if (instance != null) throw new IllegalStateException("Instance was already created");
        this.name = name;
        this.tmp = tmp == null ? DEFAULT_TEMP : tmp;
        this.cwd = cwd == null ? DEFAULT_CWD : cwd;
        this.clientSide = clientSide;
    }

    /**
     * Starts the WaterMedia API and all its internals
     * @param name Name of the environment, in minecraft context we use the name of the mod loader such as "FORGE"
     *             or "FABRIC", cannot be null or empty
     * @param tmp the TMP folder path, in case the environment has a custom path,
     *            when null it takes the path defined in the system properties
     * @param cwd the CWD folder path, the path where the process is running.
     *            when null it takes the result of make a new instance of {@link File}
     * @param clientSide Determines if the current environment its a client-side environment, when it its false, turns
     *                   off all the client side features and locks the class loading of them
     */
    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
         Objects.requireNonNull(name, "Name of the environment cannot be null");
         WaterMedia.instance = new WaterMedia(name, tmp, cwd, clientSide);

        LOGGER.info(IT, "Preparing '{}v{}' for '{}'", NAME, VERSION, name);
        LOGGER.info(IT, "OS Detected: {} ({})", System.getProperty("os.name"), System.getProperty("os.arch"));

        LOGGER.info(IT, "Registering {} config spec into OmegaConfig", NAME);
        OmegaConfig.register(WaterMediaConfig.class);
        LOGGER.info(IT, "Successfully registered config spec");

        apis = ServiceLoader.load(WaterMediaAPI.class);
        for (final WaterMediaAPI api: apis) {
            LOGGER.info(IT, "Loading {}", api.name());
            try {
                if (!api.start(instance)) {
                    LOGGER.warn(IT, "The {} module refuses to load", api.name());
                }
            } catch (final Exception e) {
                LOGGER.fatal(IT, "Unexpected exception handled loading API module {}, we cannot recover back!", api.name());
                throw new UnsupportedOperationException("Failed to start WATERMeDIA: Multimedia API", e);
            }
            LOGGER.info(IT, "Loaded {} successfully", api.name());
        }
    }

    public static String toId(final String path) { return WaterMedia.ID + ":" + path; }

    public static void checkIsClientSideOrThrow(Class<?> clazz) {
        if (instance == null) throw new IllegalStateException("WATERMeDIA was not initialized");
        if (!instance.clientSide)
            throw new IllegalStateException("Called a " + clazz.getSimpleName() + " method on a server-side environment");
    }
}
