package org.watermedia.api;

import org.watermedia.WaterMedia;

/**
 * Base class for every top-level API in WaterMedia (codecs, platforms, media,
 * network). Subclasses publish their boot progress through the inherited
 * {@link #step}/{@link #steps}/{@link #stepName} fields — only the lifecycle
 * methods are abstract.
 */
public abstract class WaterMediaAPI {

    protected int step;
    protected int steps;
    protected String stepName = "";

    public abstract String name();

    /**
     * Pre-load stage: resets boot progress so progress UIs read a clean state.
     * Subclasses that publish work override this, call {@code super} and set {@link #steps}.
     */
    public void load(final WaterMedia instance) {
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
    }

    public abstract boolean start(WaterMedia instance);

    /**
     * Teardown stage: resets boot progress so a later {@link #load} starts clean.
     * Subclasses that hold resources override this, tear down, and call {@code super}.
     */
    public void release(final WaterMedia instance) {
        this.step = 0;
        this.steps = 0;
        this.stepName = "";
    }

    public String stepName() {
        return this.stepName;
    }

    public int steps() {
        return this.steps;
    }

    public int step() {
        return this.step;
    }
}
