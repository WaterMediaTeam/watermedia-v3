package org.watermedia;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.IOTool;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class WaterMedia {
    private static final Marker IT = MarkerManager.getMarker(WaterMedia.class.getSimpleName());
    public static final String ID = "watermedia";
    public static final String NAME = "WaterMedia";
    public static final String VERSION = IOTool.jarVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final Logger LOGGER = LogManager.getLogger(ID);

    // DEFAULT PATHS
    private static final Path DEFAULT_TEMP = new File(System.getProperty("java.io.tmpdir")).toPath().toAbsolutePath().resolve("watermedia");
    private static final Path DEFAULT_CWD = new File("run").toPath().toAbsolutePath();

    // API REGISTRY — each entry counts as one outer step. Order matters: codecs
    // first (so consumers can decode images), then platforms (so MRL lookups
    // work), then the media engine (FFmpeg), then the network layer.
    private static final List<WaterMediaAPI> APIS = List.of(
            new CodecsAPI(),
            new PlatformAPI(),
            new MediaAPI(),
            new NetworkAPI()
    );

    private static WaterMedia instance;
    private static volatile WaterMediaAPI currentAPI;
    private static volatile int currentStep;
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
     * @param clientSide Determines if the current environment it's a client-side environment, when it its false, turns
     *                   off all the client side features and locks the class loading of them
     */
    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
         Objects.requireNonNull(name, "Name of the environment cannot be null");
         if (name.isBlank()) throw new IllegalArgumentException("Name of the environment cannot be empty");
         WaterMedia.instance = new WaterMedia(name, tmp, cwd, clientSide);

        LOGGER.info(IT, "Running '{} v{}' for '{}' in {} side", NAME, VERSION, instance.name, instance.clientSide ? "client" : "server");
        LOGGER.info(IT, "OS Detected: {} ({}) - Java: {}", System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"));
        LOGGER.info(IT, "RAM stats (used/total/max): {}/{}/{} MB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024, Runtime.getRuntime().totalMemory() / 1024 / 1024, Runtime.getRuntime().maxMemory() / 1024 / 1024);
        LOGGER.info(IT, "Process PATH: {}", instance.cwd.toAbsolutePath());
        LOGGER.info(IT, "Temp folder PATH: {}", instance.tmp.toAbsolutePath());

        if (clientSide) {
            LOGGER.info(IT, "Starting binary dependency {}", WaterMediaBinaries.NAME);
            // DO NOT THROW — SOME ENVIRONMENTS MAY NOT NEED IT
            try {
                WaterMediaBinaries.start(instance.name, instance.tmp, instance.cwd, true);
            } catch (Throwable t) {
                LOGGER.error(IT, "Failed to start binary dependency {}", WaterMediaBinaries.NAME, t);
            }
        } else {
            LOGGER.info(IT, "Skipping WMB startup on server-side environment");
        }

        LOGGER.info(IT, "Starting config dependency {}", WaterConfig.ID); {
            WaterConfig.init();
            WaterConfig.registerBlocking(WaterMediaConfig.class);
        }

        // PRE-LOAD: each API computes its own step count up-front so progress UIs
        // can read steps() before any work begins.
        for (final WaterMediaAPI api : APIS) {
            api.load(instance);
        }

        // LOAD: walk the registered APIs in order. Each API publishes its progress
        // through step()/steps()/stepName(), and WaterMedia tracks which API is
        // currently running via currentAPI()/step()/steps().
        for (int i = 0; i < APIS.size(); i++) {
            final WaterMediaAPI api = APIS.get(i);
            currentAPI = api;
            currentStep = i + 1;
            LOGGER.info(IT, "Starting {} API ({}/{})", api.name(), currentStep, APIS.size());
            try {
                if (!api.start(instance)) {
                    LOGGER.error(IT, "Failed to start {} API", api.name());
                }
            } catch (final Throwable t) {
                LOGGER.error(IT, "Failed to start {} API", api.name(), t);
            }
        }

        LOGGER.info(IT, "{} initialized successfully", NAME);
    }

    public static String toId(final String path) { return WaterMedia.ID + ":" + path; }

    public static Path cwd() {
        if (instance == null) throw new IllegalStateException(NAME + " was not initialized");
        return instance.cwd;
    }

    public static Path tmp() {
        if (instance == null) throw new IllegalStateException(NAME + " was not initialized");
        return instance.tmp;
    }

    public static void checkIsClientSideOrThrow(Class<?> clazz) {
        if (instance == null) throw new IllegalStateException(NAME + " was not initialized");
        if (!instance.clientSide)
            throw new IllegalStateException("Called a " + clazz.getSimpleName() + " method on a server-side environment");
    }

    /**
     * Total number of registered APIs — each one counts as a single outer step
     * for boot progress reporting (e.g. {@code 2/4 - NetworkAPI}).
     */
    public static int steps() {
        return APIS.size();
    }

    /**
     * 1-based index of the API currently being initialized. Returns 0 before
     * {@link #start(String, Path, Path, boolean)} starts iterating the registry.
     */
    public static int step() {
        return currentStep;
    }

    /**
     * The API currently being initialized. Callers building a loading screen
     * should poll {@link WaterMediaAPI#step()}, {@link WaterMediaAPI#steps()}
     * and {@link WaterMediaAPI#stepName()} on this instance to render the
     * inner progress bar (e.g. {@code Loading FFMPEG} / {@code Loading KickPlatform}).
     */
    public static WaterMediaAPI currentAPI() {
        return currentAPI;
    }
}
