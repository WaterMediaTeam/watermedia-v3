package org.watermedia;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.IOTool;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
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
    // PROCESS WORKING DIRECTORY: new File("") RESOLVES TO user.dir WHEN MADE ABSOLUTE
    private static final Path DEFAULT_CWD = new File("").toPath().toAbsolutePath();

    // MODULE REGISTRY — EACH ENTRY IS ONE OUTER BOOT STEP. ORDER MATTERS: BINARIES FIRST (SO FFMPEG
    // FILES EXIST ON DISK), THEN CONFIG (SO EVERY LATER MODULE READS REGISTERED VALUES), THEN CODECS
    // (SO CONSUMERS CAN DECODE IMAGES), PLATFORMS (SO MRL LOOKUPS WORK), THE MEDIA ENGINE (FFMPEG)
    // AND FINALLY THE NETWORK LAYER.
    private static final List<WaterMediaModule> MODULES = List.of(
            new WaterMediaBinaries(),
            new WaterMediaConfig(),
            new CodecsAPI(),
            new PlatformAPI(),
            new MediaAPI(),
            new NetworkAPI()
    );

    private static volatile WaterMedia instance;
    private static volatile WaterMediaModule currentModule;
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
     *            when null it defaults to the process working directory ({@code user.dir})
     * @param clientSide Determines if the current environment is a client-side environment, when it is false, turns
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

        // BOOT: WALK THE MODULE REGISTRY IN ORDER. EACH MODULE COMPUTES ITS STEP COUNT IN load()
        // RIGHT BEFORE ITS OWN start(), SO CONFIG-DEPENDENT COUNTS READ THE CONFIG MODULE'S RESULT.
        // A MODULE FAILURE IS NEVER FATAL: IT IS LOGGED AND RECORDED IN ITS failures FOR THE UI.
        for (int i = 0; i < MODULES.size(); i++) {
            final WaterMediaModule module = MODULES.get(i);
            currentModule = module;
            currentStep = i + 1;
            LOGGER.info(IT, "Starting {} ({}/{})", module.name(), currentStep, MODULES.size());
            try {
                module.load(instance);
                if (!module.start(instance)) {
                    LOGGER.error(IT, "Failed to start {}", module.name());
                    module.failures.add(module.name());
                }
            } catch (final Throwable t) {
                LOGGER.error(IT, "Failed to start {}", module.name(), t);
                module.failures.add(module.stepName.isEmpty() ? module.name() : module.stepName);
            }
        }

        LOGGER.info(IT, "{} initialized successfully", NAME);
    }

    /**
     * Tears down every registered module in reverse boot order and clears the singleton so
     * {@link #start(String, Path, Path, boolean)} can run again in the same process. A never-started
     * (or already-stopped) instance is a no-op.
     */
    public static synchronized void stop() {
        if (instance == null) return;
        // REVERSE ORDER SO DEPENDENTS TEAR DOWN BEFORE THE MODULES THEY DEPEND ON
        for (int i = MODULES.size() - 1; i >= 0; i--) {
            final WaterMediaModule module = MODULES.get(i);
            try {
                module.release(instance);
            } catch (final Throwable t) {
                LOGGER.error(IT, "Failed to stop {}", module.name(), t);
            }
        }
        currentModule = null;
        currentStep = 0;
        instance = null;
        LOGGER.info(IT, "{} stopped", NAME);
    }

    /**
     * Whether WaterMedia has been booted and not yet {@link #stop() stopped}. Lets a second mod probe
     * for an existing boot instead of triggering the single-instance guard in {@link #start}.
     */
    public static boolean started() {
        return instance != null;
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

    // ==========================================================================
    // BOOT METRICS — THREE PROGRESS BARS PLUS SAFE FAILURES, ALL BY NAME.
    // MODULE INSTANCES ARE NEVER EXPOSED; UIS POLL THESE STATICS EVERY FRAME.
    // ==========================================================================

    /** Bar 1 — total number of registered modules. */
    public static int steps() {
        return MODULES.size();
    }

    /** Bar 1 — 1-based index of the module currently booting; 0 before boot starts. */
    public static int step() {
        return currentStep;
    }

    /** Bar 1 — name of the module currently booting; empty before boot starts. */
    public static String stepName() {
        final WaterMediaModule module = currentModule;
        return module == null ? "" : module.name();
    }

    /** Bar 2 — number of elements the current module loads; 0 when none or between modules. */
    public static int taskSteps() {
        final WaterMediaModule module = currentModule;
        return module == null ? 0 : module.steps;
    }

    /** Bar 2 — 1-based index of the element the current module is loading. */
    public static int taskStep() {
        final WaterMediaModule module = currentModule;
        return module == null ? 0 : module.step;
    }

    /** Bar 2 — name of the element the current module is loading (e.g. {@code FFMPEG}). */
    public static String taskName() {
        final WaterMediaModule module = currentModule;
        return module == null ? "" : module.stepName;
    }

    /** Bar 3 — completed units of the demanding task in flight (bytes for downloads/extractions). */
    public static long work() {
        final WaterMediaModule module = currentModule;
        return module == null ? 0L : module.work;
    }

    /** Bar 3 — total units of the demanding task in flight; 0 when no such task is active. */
    public static long workTotal() {
        final WaterMediaModule module = currentModule;
        return module == null ? 0L : module.workTotal;
    }

    /** Bar 3 — display name of the demanding task in flight (e.g. the file being extracted). */
    public static String workName() {
        final WaterMediaModule module = currentModule;
        return module == null ? "" : module.workName;
    }

    /** Bar 3 — whether the demanding task downloads from the network; {@code false} means local extraction. */
    public static boolean workRemote() {
        final WaterMediaModule module = currentModule;
        return module != null && module.workRemote;
    }

    /** A non-fatal boot failure: the module that reported it and the step that failed. */
    public record Failure(String api, String step) {}

    /**
     * Snapshot of every non-fatal ("safe") boot failure recorded so far, in boot order —
     * e.g. a failed FFmpeg load or binaries extraction. Empty when boot went clean.
     */
    public static List<Failure> failures() {
        final List<Failure> out = new ArrayList<>();
        for (final WaterMediaModule module: MODULES) {
            for (final String step: module.failures) out.add(new Failure(module.name(), step));
        }
        return List.copyOf(out);
    }
}
