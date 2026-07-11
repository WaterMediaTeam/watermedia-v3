package org.watermedia.tools;

import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ThreadTool {
    public static final HashMap<String, Integer> THREADS = new HashMap<>();

    public static Thread createStarted(final String name, final Runnable runnable) {
        // AUTO-APPEND A PER-NAME COUNTER (name-0, name-1, ...) CONSISTENTLY WITH createStartedLoop
        final int c = THREADS.computeIfAbsent(name, k -> 0);
        final Thread t = new Thread(runnable, name + "-" + c);
        THREADS.put(name, c + 1);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
        return t;
    }

    public static boolean tryAcquireLock(final Semaphore semaphore, final long timeout, final TimeUnit unit) {
        try {
            return semaphore.tryAcquire(timeout, unit);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // RESTORE INTERRUPTED STATUS
        }
        return false;
    }

    public static Executor createRecommendedThreadPool(final String name, final int priority) {
        return Executors.newFixedThreadPool(halfLeastThreads(2), createFactory(name, priority));
    }

    public static Executor createScheduledThreadPool(final String name, final int threadCount, final int priority) {
        return Executors.newScheduledThreadPool(threadCount, createFactory(name, priority));
    }

    public static ThreadGroupFactory createThreadGroupFactory(final String name, final int priority) {
        final AtomicInteger count = new AtomicInteger(0);
        return () -> {
            count.getAndIncrement();
            return (childName, r) -> {
                final Thread t = new Thread(r);
                t.setDaemon(true);
                t.setPriority(Math.min(Math.max(priority, Thread.MIN_PRIORITY), Thread.MAX_PRIORITY));
                t.setName(name + "-" + count.get() + "-" + childName);
                return t;
            };
        };
    }

    public static ThreadFactory createFactory(final String name, final int priority) {
        final AtomicInteger count = new AtomicInteger(0);
        return r -> {
            final Thread t = new Thread(r);
            t.setDaemon(true);
            t.setPriority(Math.min(Math.max(priority, Thread.MIN_PRIORITY), Thread.MAX_PRIORITY));
            t.setName(name + "-" + count.getAndIncrement());
            return t;
        };
    }

    public static boolean join(final Thread target) {
        boolean interrupted = false;
        while (target.isAlive()) {
            try {
                target.join();
            } catch (final InterruptedException e) {
                interrupted = true; // KEEP WAITING — RESTORE THE STATUS ONCE THE TARGET DIES
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return !interrupted;
    }

    public static boolean wait(final Object obj) {
        try {
            obj.wait();
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // RESTORE INTERRUPTED STATUS
            return false;
        }
    }

    // RETURNS TRUE IF THE SLEEP COMPLETED, FALSE IF IT WAS INTERRUPTED
    public static boolean sleep(final long timeoutMillis) {
        try {
            Thread.sleep(timeoutMillis);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // RESTORE INTERRUPTED STATUS
            return false;
        }
    }

    // SAME AS sleep BUT YIELDS THE CPU AFTERWARDS
    public static boolean sleepYield(final long timeoutMillis) {
        try {
            Thread.sleep(timeoutMillis);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // RESTORE INTERRUPTED STATUS
            return false;
        } finally {
            Thread.yield();
        }
    }

    /**
     * Creates and starts a daemon thread that runs {@code runnable} in a loop until interrupted.
     * The loop checks the interrupted status on each iteration, so interrupting the thread stops it.
     *
     * @param name     base thread name; a per-name counter is appended ({@code name-0}, {@code name-1}, ...)
     * @param runnable the body executed on every loop iteration
     * @return the started thread
     */
    public static Thread createStartedLoop(final String name, final Runnable runnable) {
        final int c = THREADS.computeIfAbsent(name, k -> 0);
        final Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                runnable.run();
            }
        }, name + "-" + c);
        THREADS.put(name, c + 1);
        t.setDaemon(true); // DIE ALONGSIDE THE MAIN THREAD
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
        return t;
    }

    public static int maxThreads() { return Runtime.getRuntime().availableProcessors(); }
    public static int halfLeastThreads(final int count) { return Math.max(count, halfThreads()); }
    public static int halfThreads() { return maxThreads() / 2; }
    public static int minThreads() {
        final int count = maxThreads();
        if (count <= 2) return 1;
        if (count <= 8) return 2;
        if (count <= 16) return 3;
        if (count <= 32) return 4;
        if (count <= 64) return 6;
        return 8;
    }

    public static boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    public static void interrupt() {
        Thread.currentThread().interrupt();
    }

    public interface ThreadGroupFactory {
        BiFunction<String, Runnable, Thread> newFactory();
    }
}
