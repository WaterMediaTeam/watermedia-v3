package org.watermedia;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.watermedia.WaterMedia.BootStatus.Id;
import org.watermedia.WaterMedia.BootStatus.State;
import org.watermedia.WaterMediaModule.Bootstrap;

/** Coordinates WaterMedia services and exposes immutable bootstrap diagnostics. */
public final class WaterMedia {
    public static final String ID = "watermedia";
    public static final String NAME = "WaterMedia";
    public static final String VERSION = IOTool.jarVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final Logger LOGGER = LogManager.getLogger(ID);
    private static final Path DEFAULT_TEMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve(ID);
    private static final Path DEFAULT_CWD = Path.of("").toAbsolutePath();

    // FACTORY BODIES ARE RESOLVED ONLY AFTER SIDE AND DEPENDENCY GATES PASS.
    private static final List<Bootstrap.Definition> MODULES = List.of(
            new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), WaterMediaConfig.Module::new),
            new Bootstrap.Definition(Id.NETWORK, false, false, List.of(Id.CONFIG), NetworkAPI.Module::new),
            new Bootstrap.Definition(Id.BINARIES, true, false, List.of(Id.CONFIG), BinariesModule::new),
            new Bootstrap.Definition(Id.PLATFORMS, true, false, List.of(Id.CONFIG, Id.NETWORK), PlatformAPI.Module::new),
            new Bootstrap.Definition(Id.MEDIA, true, false, List.of(Id.CONFIG, Id.NETWORK), MediaAPI.Module::new)
    );
    private static final Object LIFECYCLE = new Object();
    private static boolean transitioning;
    private static volatile WaterMedia instance;
    private static volatile Bootstrap bootstrap = new Bootstrap(MODULES);
    public final String name;
    public final Path tmp, cwd;
    public final boolean clientSide;

    WaterMedia(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        this.name = Objects.requireNonNull(name);
        if (name.isBlank()) throw new IllegalArgumentException("Environment name cannot be empty");
        this.tmp = (tmp == null ? DEFAULT_TEMP : tmp).toAbsolutePath().normalize();
        this.cwd = (cwd == null ? DEFAULT_CWD : cwd).toAbsolutePath().normalize();
        this.clientSide = clientSide;
    }

    /**
     * Starts configured services. Client-only factories are never invoked on a server.
     * Optional failures produce DEGRADED; essential failures throw and retain their diagnostics.
     */
    public static void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        if (ThreadTool.workerThread()) throw new IllegalStateException("Start WaterMedia from a host lifecycle thread, not a managed worker");
        final WaterMedia context = new WaterMedia(name, tmp, cwd, clientSide);
        final Bootstrap session = new Bootstrap(MODULES);
        synchronized (LIFECYCLE) {
            if (transitioning || instance != null) throw new IllegalStateException("WaterMedia already has a session or lifecycle transition");
            synchronized (MediaPlayer.class) {
                session.starting();
                bootstrap = session;
                instance = context;
                transitioning = true;
            }
        }
        LOGGER.info("Starting {} {} for {} ({})", NAME, VERSION, name, clientSide ? "client" : "server");
        try {
            session.start(context);
            LOGGER.info("{} startup completed: {}", NAME, session.status().state());
        } finally {
            synchronized (LIFECYCLE) { transitioning = false; }
            for (final BootStatus.Failure failure: session.status().failures())
                LOGGER.error("Module {} failed during {}", failure.module(), failure.task(), failure.cause());
        }
    }

    /**
     * Stops services in reverse order. Release all players on their owning host contexts first.
     * Incomplete shutdown retains the session and must be retried before another start.
     */
    public static void stop() {
        if (ThreadTool.workerThread()) throw new IllegalStateException("Stop WaterMedia from a host lifecycle thread, not a managed worker");
        final WaterMedia context;
        final Bootstrap session;
        synchronized (LIFECYCLE) {
            if (transitioning) throw new IllegalStateException("WaterMedia is already changing lifecycle state");
            context = instance;
            if (context == null) return;
            session = bootstrap;
            // PLAYER ADMISSION AND CLOSING THE SESSION SHARE THIS SHORT GATE; NO CALLBACK RUNS HERE.
            synchronized (MediaPlayer.class) {
                if (MediaPlayer.openPlayers() != 0)
                    throw new IllegalStateException("Release all media players on their owning contexts before stopping WaterMedia");
                session.stopping();
                transitioning = true;
            }
        }
        try {
            session.stop(context);
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

    public static String toId(final String path) { return ID + ":" + path; }

    public static Path cwd() {
        final WaterMedia context = instance;
        if (context == null) throw new IllegalStateException(NAME + " was not initialized");
        return context.cwd;
    }

    public static Path tmp() {
        final WaterMedia context = instance;
        if (context == null) throw new IllegalStateException(NAME + " was not initialized");
        return context.tmp;
    }

    public static void checkIsClientSideOrThrow(final Class<?> type) {
        final WaterMedia context = instance;
        if (context == null) throw new IllegalStateException(NAME + " was not initialized");
        if (!context.clientSide) throw new IllegalStateException("Called " + type.getSimpleName() + " in a server environment");
    }

    private static final class BinariesModule extends WaterMediaModule {
        @Override
        protected void start(final WaterMedia context) throws Exception {
            WaterMediaBinaries.resolve(context.tmp);
            if (WaterMediaConfig.media.ffmpeg.disable) return;
            this.task(1, 1, "FFmpeg");
            try {
                WaterMediaBinaries.provision((name, done, total) -> this.work(name, done, total, false));
            } finally {
                this.work("", 0, 0, false);
            }
        }

        @Override
        protected void release(final WaterMedia context) {
            WaterMediaBinaries.release();
        }
    }

    /** A coherent snapshot of the current bootstrap session and its diagnostics. */
    public record BootStatus(State state, int step, int steps, Id current, Progress progress,
                             List<Module> modules, List<Failure> failures) {
        public BootStatus {
            modules = List.copyOf(modules);
            failures = List.copyOf(failures);
        }

        public enum State { STOPPED, STARTING, READY, DEGRADED, FAILED, STOPPING }
        public enum Outcome { PENDING, STARTING, READY, SKIPPED, FAILED, BLOCKED, STOPPED }
        public enum Id { CONFIG, NETWORK, BINARIES, PLATFORMS, MEDIA }

        /** Module outcome; dependency identifies a service blocking startup or shutdown. */
        public record Module(Id id, Outcome outcome, Id dependency, String reason) {}

        /** A startup or shutdown failure with its original cause. */
        public record Failure(Id module, String task, Throwable cause) {}

        /** Task and transfer progress published together by the owning module. */
        public record Progress(int taskStep, int taskSteps, String taskName, long work, long workTotal,
                               String workName, boolean remote) {
            public static final Progress NONE = new Progress(0, 0, "", 0, 0, "", false);
        }
    }
}
