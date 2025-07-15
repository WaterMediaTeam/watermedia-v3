package org.watermedia.tools;

import java.util.HashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ThreadTool {
    public static final HashMap<String, Integer> THREADS = new HashMap<>();
    public static boolean tryAdquireLock(Semaphore semaphore, long timeout, TimeUnit unit) {
        try {
            return semaphore.tryAcquire(timeout, unit);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupted status
        }
        return false;
    }

    public static void handBreak(long timeoutMillis) {
        try {
            Thread.sleep(timeoutMillis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupted status
        }
    }

    public static Thread create(String name, Runnable runnable) {
        final int c = THREADS.computeIfAbsent(name, k -> 0);
        final Thread t = new Thread(runnable, name + "-" + c);
        THREADS.put(name, c + 1);
        t.setDaemon(true); // Set the thread as a daemon thread
        t.setPriority(Thread.NORM_PRIORITY); // Set the thread priority to normal
        t.start(); // Start the thread immediately
        return t;
    }

    public static int maxThreads() { return Runtime.getRuntime().availableProcessors(); }
    public static int minThreads() {
        final int count = maxThreads();
        if (count <= 2) return 1;
        if (count <= 8) return 2;
        if (count <= 16) return 3;
        if (count <= 32) return 4;
        if (count <= 64) return 6;
        return 8;
    }

}
