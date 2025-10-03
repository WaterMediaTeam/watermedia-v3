package org.watermedia.api;

import org.watermedia.WaterMedia;

public abstract class WaterMediaAPI {

    public String name() {
        return this.getClass().getSimpleName();
    }

    public abstract boolean start(WaterMedia instance) throws Exception;

    public abstract boolean onlyClient();

    public abstract void test();

    public abstract Priority priority();

    public abstract void release(WaterMedia instance);

    public enum Priority {
        HIGHEST,
        HIGH,
        NORMAL,
        LOW,
        LOWEST,
        BENCHMARK
    }
}
