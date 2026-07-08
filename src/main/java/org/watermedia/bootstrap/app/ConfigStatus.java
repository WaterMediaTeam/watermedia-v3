package org.watermedia.bootstrap.app;

/**
 * Config status shown by settings in the engine strip.
 */
public final class ConfigStatus {
    public volatile boolean visible;
    public volatile String text = "READY";
    public volatile boolean pulse;
    public volatile boolean warn;
    public volatile boolean error;
    public volatile boolean strike;
}
