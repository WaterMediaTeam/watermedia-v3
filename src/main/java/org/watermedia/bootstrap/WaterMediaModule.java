package org.watermedia.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    // A PART THE MODULE CAN LIVE WITHOUT FAILED; THE MODULE KEEPS LOADING AND WaterMedia LISTS THE CAUSE
    protected final synchronized void failure(final Throwable cause) {
        this.failures.add(Objects.requireNonNull(cause));
    }

    /** Returns the optional failures the module reported while starting. */
    public final synchronized List<Throwable> failures() {
        return List.copyOf(this.failures);
    }
}
