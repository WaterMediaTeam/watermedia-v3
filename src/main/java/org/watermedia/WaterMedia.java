package org.watermedia;

import org.apache.logging.log4j.Logger;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MediaPlayer;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.bootstrap.WaterMediaModule;
import org.watermedia.bootstrap.WaterMediaModule.Bootstrap;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.LogTool;
import org.watermedia.tools.ThreadTool;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.watermedia.WaterMedia.BootStatus.Id;
import org.watermedia.WaterMedia.BootStatus.State;

/** Coordinates WaterMedia services and exposes immutable bootstrap diagnostics. */
public final class WaterMedia {
    public static final String ID = "watermedia";
    public static final String NAME = "WaterMedia";
    public static final String VERSION = IOTool.jarVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final Logger LOGGER = LogTool.logger(ID);
    private static final Path DEFAULT_TEMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve(ID);
    private static final Path DEFAULT_CWD = Path.of("").toAbsolutePath();

    // THE ENVIRONMENT OF ONE SESSION; PUBLISHED BEFORE ANY MODULE STARTS AND RETAINED UNTIL EVERY MODULE STOPS.
    private record Context(Path tmp, Path cwd, boolean clientSide) {}

    // FACTORY BODIES ARE RESOLVED ONLY AFTER SIDE AND DEPENDENCY GATES PASS.
    private static final List<Bootstrap.Definition> MODULES = List.of(
            new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), WaterMediaConfig.Module::new),
            new Bootstrap.Definition(Id.BINARIES, true, true, List.of(Id.CONFIG), WaterMedia::binaries),
            new Bootstrap.Definition(Id.NETWORK, false, false, List.of(Id.CONFIG), NetworkAPI.Module::new),
            new Bootstrap.Definition(Id.PLATFORMS, true, false, List.of(Id.CONFIG, Id.NETWORK), PlatformAPI.Module::new),
            new Bootstrap.Definition(Id.MEDIA, true, false, List.of(Id.CONFIG, Id.NETWORK), MediaAPI.Module::new)
    );
    private static final Object LIFECYCLE = new Object();
    private static boolean transitioning;
    private static volatile Context instance;
    private static volatile Bootstrap bootstrap = new Bootstrap(MODULES);

    private WaterMedia() {}

    /**
     * Starts configured services. Client-only factories are never invoked on a server.
     * Optional failures produce DEGRADED; essential failures throw and retain their diagnostics.
     */
    public static void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        if (ThreadTool.workerThread()) throw new IllegalStateException("Start WaterMedia from a host lifecycle thread, not a managed worker");
        if (Objects.requireNonNull(name).isBlank()) throw new IllegalArgumentException("Environment name cannot be empty");
        final Context context = new Context((tmp == null ? DEFAULT_TEMP : tmp).toAbsolutePath().normalize(),
                (cwd == null ? DEFAULT_CWD : cwd).toAbsolutePath().normalize(), clientSide);
        final Bootstrap session = new Bootstrap(MODULES);
        synchronized (LIFECYCLE) {
            if (transitioning || instance != null) throw new IllegalStateException("WaterMedia already has a session or lifecycle transition");
            synchronized (MediaPlayer.class) {
                session.state(State.STARTING);
                bootstrap = session;
                instance = context;
                transitioning = true;
            }
        }
        LOGGER.info("Starting {} {} for {} ({})", NAME, VERSION, name, clientSide ? "client" : "server");
        try {
            session.start(clientSide);
            LOGGER.info("{} startup completed: {}", NAME, session.status().state());
        } finally {
            synchronized (LIFECYCLE) { transitioning = false; }
            for (final BootStatus.Failure failure: session.status().failures())
                LOGGER.error("Service {} failed during {}", failure.id(), failure.task(), failure.cause());
        }
    }

    /**
     * Stops services in reverse order. Release all players on their owning host contexts first.
     * Incomplete shutdown retains the session and must be retried before another start.
     */
    public static void stop() {
        if (ThreadTool.workerThread()) throw new IllegalStateException("Stop WaterMedia from a host lifecycle thread, not a managed worker");
        final Bootstrap session;
        synchronized (LIFECYCLE) {
            if (transitioning) throw new IllegalStateException("WaterMedia is already changing lifecycle state");
            if (instance == null) return;
            session = bootstrap;
            // PLAYER ADMISSION AND CLOSING THE SESSION SHARE THIS SHORT GATE; NO CALLBACK RUNS HERE.
            synchronized (MediaPlayer.class) {
                if (MediaPlayer.openPlayers() != 0)
                    throw new IllegalStateException("Release all media players on their owning contexts before stopping WaterMedia");
                session.state(State.STOPPING);
                transitioning = true;
            }
        }
        try {
            session.stop();
            LOGGER.info("{} stopped", NAME);
        } finally {
            synchronized (LIFECYCLE) {
                if (session.status().state() == State.STOPPED && !session.ownsResources()) instance = null;
                transitioning = false;
            }
        }
    }

    /** True only after startup finishes with ready or degraded services. */
    public static boolean started() {
        final State state = bootstrap.status().state();
        return state == State.READY || state == State.DEGRADED;
    }

    /** Returns one coherent lifecycle, progress and failure snapshot. */
    public static BootStatus status() { return bootstrap.status(); }

    /** Prefixes a resource path with this library's identifier, as {@code watermedia:path}. */
    public static String toId(final String path) { return ID + ":" + path; }

    /** Returns the session's absolute working directory; throws when WaterMedia is not initialized. */
    public static Path cwd() { return context().cwd; }

    /** Returns the session's absolute temporary directory; throws when WaterMedia is not initialized. */
    public static Path tmp() { return context().tmp; }

    /** Whether the session runs on a client; throws when WaterMedia is not initialized. */
    public static boolean clientSide() { return context().clientSide; }

    /**
     * Rejects a client-only operation requested by {@code type} when WaterMedia is uninitialized or server-side.
     * Engines, media players and any client-only tool call it from their constructors, so a dedicated server
     * cannot reach rendering, audio or decoding code even where the host does not hide those classes.
     */
    public static void checkIsClientSideOrThrow(final Class<?> type) {
        if (!context().clientSide) throw new IllegalStateException("Called " + type.getSimpleName() + " in a server environment");
    }

    private static Context context() {
        final Context context = instance;
        if (context == null) throw new IllegalStateException(NAME + " was not initialized");
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

    /** A coherent snapshot of the current bootstrap session and its diagnostics. */
    public record BootStatus(State state, int step, int steps, Id current, Progress progress,
                             List<Service> services, List<Failure> failures) {
        public BootStatus {
            services = List.copyOf(services);
            failures = List.copyOf(failures);
        }

        /** Whether this snapshot contains a successfully initialized service. */
        public boolean ready(final Id id) {
            return this.services.stream().anyMatch(service -> service.id() == id && service.outcome() == Outcome.READY);
        }

        public enum State { STOPPED, STARTING, READY, DEGRADED, FAILED, STOPPING }
        public enum Outcome { PENDING, STARTING, READY, SKIPPED, FAILED, BLOCKED, STOPPED }
        public enum Id { CONFIG, BINARIES, NETWORK, PLATFORMS, MEDIA }

        /** Service outcome; dependency identifies a service blocking startup or shutdown. */
        public record Service(Id id, Outcome outcome, Id dependency, String reason) {}

        /** A startup or shutdown failure of the service {@code id}, with its original cause. */
        public record Failure(Id id, String task, Throwable cause) {}

        /** Task and transfer progress published together by the owning module. */
        public record Progress(int taskStep, int taskSteps, String taskName, long work, long workTotal,
                               String workName, boolean remote) {
            public static final Progress NONE = new Progress(0, 0, "", 0, 0, "", false);
        }
    }
}
