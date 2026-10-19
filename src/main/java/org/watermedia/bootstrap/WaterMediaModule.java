package org.watermedia.bootstrap;

import java.util.ArrayList;
import java.util.List;

/** Internal module contract: a start hook that runs once, plus the optional failures it reports; only WaterMedia loads modules. */
public abstract class WaterMediaModule {
    private final List<Throwable> failures = new ArrayList<>();
    private boolean loaded;

    protected abstract void start() throws Exception;

    /** Runs the start hook, once for the life of the process; WaterMedia is its only caller. */
    public final void load() throws Exception {
        if (this.loaded) throw new IllegalStateException("The module has already loaded");
        this.loaded = true;
        this.start();
    }

    /** Combines cleanup failures without hiding a fatal VM error behind an ordinary exception. */
    public static Throwable mergeFailure(final Throwable previous, final Throwable next) {
        if (next == null) throw new IllegalArgumentException("The next failure is required");
        if (previous == null || previous == next) return next;
        final boolean previousFatal = previous instanceof VirtualMachineError;
        final boolean nextFatal = next instanceof VirtualMachineError;
        if ((nextFatal && !previousFatal) || (next instanceof Error && !(previous instanceof Error))) {
            next.addSuppressed(previous);
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }

    // A PART THE MODULE CAN LIVE WITHOUT FAILED; THE MODULE KEEPS LOADING AND WaterMedia LISTS THE CAUSE
    protected final synchronized void failure(final Throwable cause) {
        if (cause == null) throw new IllegalArgumentException("A failure needs its cause");
        this.failures.add(cause);
    }

    /** Returns the optional failures the module reported while starting. */
    public final synchronized List<Throwable> failures() {
        return List.copyOf(this.failures);
    }
}
