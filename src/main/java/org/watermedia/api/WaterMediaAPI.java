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

    public abstract void load(WaterMedia instance);

    public abstract boolean start(WaterMedia instance);

    public abstract void release(WaterMedia instance);

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
