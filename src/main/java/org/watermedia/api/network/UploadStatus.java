package org.watermedia.api.network;

/**
 * Tracks the progress of a file upload to a WaterMedia server.
 * Returned by {@link NetworkAPI#upload} methods.
 * All getters are thread-safe and can be polled from any thread.
 */
public class UploadStatus {
    private final long totalBytes;
    private volatile long uploadedBytes;
    private volatile long speed;
    private volatile String id;
    private volatile boolean complete;
    private volatile boolean failed;
    private volatile String error;

    UploadStatus(final long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long totalBytes() { return this.totalBytes; }
    public long uploadedBytes() { return this.uploadedBytes; }

    /** Current upload speed in bytes per second */
    public long speed() { return this.speed; }

    /** Server-assigned ID, null until upload completes */
    public String id() { return this.id; }

    public boolean isComplete() { return this.complete; }
    public boolean isFailed() { return this.failed; }
    public String error() { return this.error; }

    /** Upload progress from 0.0 to 100.0 */
    public double percentage() {
        return this.totalBytes > 0 ? (this.uploadedBytes * 100.0) / this.totalBytes : 0;
    }

    /** Formatted speed string (e.g. "1.5 MB/s") */
    public String displaySpeed() {
        return NetworkAPI.displaySpeed(this.speed);
    }

    void setUploadedBytes(final long bytes) { this.uploadedBytes = bytes; }
    void setSpeed(final long speed) { this.speed = speed; }

    void complete(final String id) {
        this.id = id;
        this.complete = true;
    }

    void fail(final String error) {
        this.error = error;
        this.failed = true;
    }
}
