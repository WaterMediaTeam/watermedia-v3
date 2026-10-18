package org.watermedia;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.bootstrap.WaterMediaModule;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.LogTool;
import org.watermedia.tools.ThreadTool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.watermedia.WaterMedia.Status.Failure;
import org.watermedia.WaterMedia.Status.State;

/** Loads WaterMedia's modules once for the life of the process and exposes the loading state. */
public final class WaterMedia {
    private static final Marker IT = MarkerManager.getMarker(WaterMedia.class.getSimpleName());
    public static final String ID = "watermedia";
    public static final String NAME = "WaterMedia";
    public static final String VERSION = IOTool.jarVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final Logger LOGGER = LogTool.logger(ID);
    private static final Path DEFAULT_TEMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve(ID);
    private static final Path DEFAULT_CWD = Path.of("").toAbsolutePath();

    // THE ENVIRONMENT OF THE PROCESS; PUBLISHED BEFORE ANY MODULE STARTS AND KEPT FOR THE LIFE OF THE JVM
    private record Context(Path tmp, Path cwd, boolean clientSide) {}

    // A MODULE IN LOAD ORDER: WHETHER ONLY CLIENTS LOAD IT, WHETHER ITS FAILURE ENDS THE LOAD, AND ITS FACTORY,
    // RESOLVED ONLY AFTER THE SIDE CHECK PASSES (BINARIES STAYS BEHIND ITS LAZY HOLDER)
    private record Definition(boolean client, boolean essential, Supplier<WaterMediaModule> factory) {}

    private static final List<Definition> MODULES = List.of(
            new Definition(false, true, WaterMediaConfig.Module::new),
            new Definition(true, true, WaterMedia::binaries),
            new Definition(false, false, NetworkAPI.Module::new),
            new Definition(true, false, MediaAPI.Module::new));
    private static final Object LIFECYCLE = new Object();
    private static volatile Context instance;
    private static volatile Status status = new Status(State.IDLE, null, 0, 0, List.of());

    private WaterMedia() {}

    /**
     * Loads every module, once for the life of the process; nothing is ever unloaded. Client-only modules
     * never load on a server and a module that is not installed is skipped. A module the library can live
     * without (the file server, FFmpeg) leaves the state LOADED with its failure listed in {@link #status()};
     * an essential one (the configuration, an installed Binaries module) leaves FAILED and throws.
     *
     * @throws IllegalStateException when WaterMedia already started, when called from an owned worker thread,
     *         or when an essential module fails, with that failure as the cause
     */
    public static void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        if (ThreadTool.workerThread()) throw new IllegalStateException("Start WaterMedia from a host thread, not a managed worker");
        if (Objects.requireNonNull(name).isBlank()) throw new IllegalArgumentException("Environment name cannot be empty");
        final List<Definition> modules = new ArrayList<>(MODULES.size());
        for (final Definition definition: MODULES) if (clientSide || !definition.client()) modules.add(definition);
        synchronized (LIFECYCLE) {
            if (status.state() != State.IDLE) throw new IllegalStateException("WaterMedia has already started");
            instance = new Context((tmp == null ? DEFAULT_TEMP : tmp).toAbsolutePath().normalize(),
                    (cwd == null ? DEFAULT_CWD : cwd).toAbsolutePath().normalize(), clientSide);
            status = new Status(State.LOADING, null, 0, modules.size(), List.of());
        }
        LOGGER.info(IT, "Starting {} {} for {} ({})", NAME, VERSION, name, clientSide ? "client" : "server");
        final List<Failure> failures = new ArrayList<>();
        int done = 0;
        Class<?> type = null;
        try {
            for (final Definition definition: modules) {
                type = null;
                try {
                    final WaterMediaModule module = definition.factory().get();
                    if (module != null) {
                        type = module.getClass();
                        status = new Status(State.LOADING, type, done, modules.size(), List.copyOf(failures));
                        module.load();
                        for (final Throwable partial: module.failures()) failures.add(new Failure(type, partial));
                    }
                } catch (final Exception | LinkageError failure) {
                    final Failure failed = new Failure(type, failure);
                    failures.add(failed);
                    final boolean interrupted = failure instanceof InterruptedException;
                    if (interrupted) Thread.currentThread().interrupt();
                    if (definition.essential() || interrupted) {
                        status = new Status(State.FAILED, type, done, modules.size(), List.copyOf(failures));
                        LOGGER.error(IT, "{} did not load: module {} {}", NAME, failed.name(), interrupted ? "was interrupted" : "failed", failure);
                        throw new IllegalStateException((interrupted ? "WaterMedia load interrupted at module " : "Essential WaterMedia module failed: ") + failed.name(), failure);
                    }
                }
                done++;
            }
        } catch (final Error fatal) { // A VM ERROR LEAVES THE LOAD WHERE IT WAS; NOTHING CAN BE RETRIED
            failures.add(new Failure(type, fatal));
            status = new Status(State.FAILED, type, done, modules.size(), List.copyOf(failures));
            throw fatal;
        }
        status = new Status(State.LOADED, null, done, modules.size(), List.copyOf(failures));
        for (final Failure failure: failures)
            LOGGER.error(IT, "Module {} failed; the features it provides are unavailable", failure.name(), failure.cause());
        LOGGER.info(IT, "{} loaded {} module(s) with {} failure(s)", NAME, done, failures.size());
    }

    /** True once every module loaded; the failures of optional modules do not change it. */
    public static boolean loaded() { return status.state() == State.LOADED; }

    /** Returns the current load state. */
    public static Status status() { return status; }

    /** Prefixes a resource path with this library's identifier, as {@code watermedia:path}. */
    public static String toId(final String path) { return ID + ":" + path; }

    /** Returns the process's absolute working directory; throws before {@link #start}. */
    public static Path cwd() { return context().cwd; }

    /** Returns the process's absolute temporary directory; throws before {@link #start}. */
    public static Path tmp() { return context().tmp; }

    /** Whether WaterMedia runs on a client; throws before {@link #start}. */
    public static boolean clientSide() { return context().clientSide; }

    /**
     * Rejects a client-only operation requested by {@code type} before {@link #start} or on a server.
     * Engines, media players and any client-only tool call it from their constructors, so a dedicated server
     * cannot reach rendering, audio or decoding code even where the host does not hide those classes.
     */
    public static void checkIsClientSideOrThrow(final Class<?> type) {
        if (!context().clientSide) throw new IllegalStateException("Called " + type.getSimpleName() + " in a server environment");
    }

    private static Context context() {
        final Context context = instance;
        if (context == null) throw new IllegalStateException(NAME + " has not started");
        return context;
    }

    // THE JVM VERIFIES MODULE SUBTYPES WHILE LINKING; KEEP THE CLIENT JAR BEHIND A LAZY HOLDER.
    // THE CLASS LOADER SEES OTHER MODULES; Class.getResource ONLY SEARCHES THIS ONE ON FORGE 1.21.1+
    private static WaterMediaModule binaries() {
        return WaterMedia.class.getClassLoader().getResource("org/watermedia/binaries/WaterMediaBinaries.class") == null
                ? null : Client.BINARIES.get();
    }

    private static final class Client {
        private static final Supplier<WaterMediaModule> BINARIES = WaterMediaBinaries::new;
    }

    /** The load state: the module being loaded (null between modules), modules done out of total, and every failure so far, the essential one that ended the load included. */
    public record Status(State state, Class<?> loading, int done, int total, List<Failure> failures) {
        public Status {
            failures = List.copyOf(failures);
        }

        public enum State { IDLE, LOADING, LOADED, FAILED }

        /** Returns the display name of a module class: the API that owns a nested Module, or "unknown" for null. */
        public static String name(final Class<?> module) {
            if (module == null) return "unknown";
            final Class<?> owner = module.getEnclosingClass();
            return (owner != null ? owner : module).getSimpleName();
        }

        /** A module that failed to load with its cause; {@code module} is null when it could not even be constructed. */
        public record Failure(Class<?> module, Throwable cause) {
            /** Returns the module's display name, or "unknown" when it never got constructed. */
            public String name() { return Status.name(this.module); }
        }
    }
}
