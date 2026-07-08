package org.watermedia.bootstrap.app;

/**
 * State for the cleanup cache dialog.
 */
public final class CleanupState {
    public volatile boolean visible;
    public volatile boolean working;
    public volatile boolean done;
    public volatile boolean error;
    public volatile int stage = 1;
    public volatile int fileCount;
    public volatile String sizeLabel = "0 B";
    public volatile String state = "READY";
    public volatile int progress;
}
