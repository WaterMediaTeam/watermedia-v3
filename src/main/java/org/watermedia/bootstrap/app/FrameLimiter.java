package org.watermedia.bootstrap.app;

import org.lwjgl.glfw.GLFWVidMode;

import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetWindowMonitor;

/**
 * Explicit frame pacing fallback for drivers where swap interval is ignored.
 */
final class FrameLimiter {

    private static final int DEFAULT_REFRESH_HZ = 60;
    private static final int MIN_REFRESH_HZ = 30;
    private static final int MAX_REFRESH_HZ = 360;

    private final int refreshRate;
    private final long frameNanos;
    private long nextFrameNanos;

    private FrameLimiter(final int refreshRate) {
        this.refreshRate = Math.max(MIN_REFRESH_HZ, Math.min(MAX_REFRESH_HZ, refreshRate));
        this.frameNanos = 1_000_000_000L / this.refreshRate;
    }

    static FrameLimiter forWindow(final long windowHandle) {
        long monitor = glfwGetWindowMonitor(windowHandle);
        if (monitor == 0L) monitor = glfwGetPrimaryMonitor();
        final GLFWVidMode mode = monitor != 0L ? glfwGetVideoMode(monitor) : null;
        final int hz = mode != null && mode.refreshRate() > 0 ? mode.refreshRate() : DEFAULT_REFRESH_HZ;
        return new FrameLimiter(hz);
    }

    void syncBeforeFrame() {
        long now = System.nanoTime();
        if (this.nextFrameNanos == 0L) {
            this.nextFrameNanos = now;
        }

        if (now < this.nextFrameNanos) {
            LockSupport.parkNanos(this.nextFrameNanos - now);
            now = System.nanoTime();
        } else if (now - this.nextFrameNanos > this.frameNanos) {
            this.nextFrameNanos = now;
        }

        this.nextFrameNanos += this.frameNanos;
    }

    double idleTimeoutSeconds() {
        return Math.min(0.25, Math.max(1.0 / this.refreshRate, 0.01));
    }
}
