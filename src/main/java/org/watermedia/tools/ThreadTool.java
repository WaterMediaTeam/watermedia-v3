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

    public static void sleep(long timeoutMillis) {
        try {
            Thread.sleep(timeoutMillis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupted status
        }
    }

    /**
     * Creates a new thread, already started with a while loop.
     * Thread checks for the interruped status on the loop, which will stop the loop
     * if the thread got interrumped
     * @param name
     * @param runnable
     * @return
     */
    public static Thread createStartedLoop(String name, Runnable runnable) {
        final int c = THREADS.computeIfAbsent(name, k -> 0);
        final Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                runnable.run();
            }
        }, name + "-" + c);
        THREADS.put(name, c + 1);
        t.setDaemon(true); // PLEASE KILL YOURSELF WHEN THE MAIN THREAD DO IT TOO
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
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
