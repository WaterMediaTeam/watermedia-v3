package org.watermedia;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base class for every bootable WaterMedia module: the top-level APIs (codecs,
 * platforms, media, network) plus annex processes like the binaries extractor
 * and the config loader. Modules publish their boot progress through the
 * protected fields; {@link WaterMedia} orchestrates the lifecycle and re-exposes
 * the metrics by name — module instances are never published.
 */
public abstract class WaterMediaModule {

    // BOOT PROGRESS — WRITTEN BY THE BOOT THREAD, POLLED FROM UI/RENDER THREADS (HENCE VOLATILE)
    protected volatile int step;
    protected volatile int steps;
    protected volatile String stepName = "";

    // DIMENSION PROGRESS FOR DEMANDING WORK (DOWNLOADS/EXTRACTIONS) — workTotal == 0 MEANS INACTIVE
    protected volatile long work;
    protected volatile long workTotal;
    protected volatile String workName = "";
    protected volatile boolean workRemote; // TRUE WHEN THE WORK DOWNLOADS FROM THE NETWORK, FALSE = LOCAL EXTRACTION

    // NON-FATAL ("SAFE") STEP FAILURES IN OCCURRENCE ORDER — COW: RARE WRITES, LOCK-FREE UI READS
    protected final List<String> failures = new CopyOnWriteArrayList<>();

    /** Display name of the module, surfaced by the {@link WaterMedia} boot metrics. */
    public abstract String name();

    /**
     * Pre-load stage: resets boot progress so progress UIs read a clean state.
     * Subclasses that publish work override this, call {@code super} and set {@link #steps}.
     */
    protected void load(final WaterMedia instance) {
        this.reset();
    }

    /**
     * Boot stage: performs the module work, publishing progress through the protected fields.
     * @return {@code false} when the module refused or failed to start
     */
    protected abstract boolean start(WaterMedia instance);

    /**
     * Teardown stage: resets boot progress so a later {@link #load} starts clean.
     * Subclasses that hold resources override this, tear down, and call {@code super}.
     */
    protected void release(final WaterMedia instance) {
        this.reset();
    }

    // CLEAN SLATE SHARED BY BOTH LIFECYCLE EDGES
    private void reset() {
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
        this.work = 0L;
        this.workTotal = 0L;
        this.workName = "";
        this.workRemote = false;
        this.failures.clear();
    }
}
