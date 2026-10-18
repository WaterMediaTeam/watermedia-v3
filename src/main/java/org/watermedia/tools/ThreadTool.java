package org.watermedia.tools;

import org.watermedia.api.util.MathUtil;

import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Daemon thread creation with one per-name counter shared by every creator, so no two threads ever
 * carry the same name, plus the owned-worker mark that keeps service callbacks from running lifecycle
 * operations.
 */
public class ThreadTool {
    // NAME -> NEXT SUFFIX, SHARED BY thread, factory, workerFactory AND groupFactory
    private static final ConcurrentHashMap<String, Integer> THREADS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> WORKER = new ThreadLocal<>();

    /** Starts a daemon thread with normal priority named {@code name-N}. */
    public static Thread thread(final String name, final Runnable task) {
        final Thread t = factory(name, Thread.NORM_PRIORITY).newThread(task);
        t.start();
        return t;
    }

    public static boolean tryAcquire(final Semaphore semaphore, final long timeout, final TimeUnit unit) {
        try {
            return semaphore.tryAcquire(timeout, unit);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // RESTORE INTERRUPTED STATUS
        }
        return false;
    }

    /** Creates daemon threads named {@code name-N}; the priority is clamped to the valid range. */
    public static ThreadFactory factory(final String name, final int priority) {
        final int clamped = MathUtil.clamp(priority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY);
        return task -> {
            final Thread t = new Thread(task, name + "-" + next(name));
            t.setDaemon(true);
            t.setPriority(clamped);
            return t;
        };
    }

    /** Creates owned service workers whose callbacks cannot initiate blocking lifecycle operations. */
    public static ThreadFactory workerFactory(final String name, final int priority) {
        final ThreadFactory factory = factory(name, priority);
        return task -> factory.newThread(owned(task));
    }

    /** Creates groups of owned service workers named {@code name-G-child}, one group per {@link ThreadGroupFactory#newFactory()}. */
    public static ThreadGroupFactory groupFactory(final String name, final int priority) {
        final int clamped = MathUtil.clamp(priority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY);
        return () -> {
            final String group = name + "-" + next(name);
            return (child, task) -> {
                final Thread t = new Thread(owned(task), group + "-" + child);
                t.setDaemon(true);
                t.setPriority(clamped);
                return t;
            };
        };
    }

    /** Whether this thread is executing an owned service worker. */
    public static boolean workerThread() { return Boolean.TRUE.equals(WORKER.get()); }

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

    public static int processors() { return Runtime.getRuntime().availableProcessors(); }
    public static int halfProcessors() { return processors() / 2; }
    // A SMALL WORKER COUNT THAT GROWS SLOWLY WITH THE MACHINE, FOR POOLS THAT MUST LEAVE THE HOST ITS OWN CORES
    public static int baseThreads() {
        final int count = processors();
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

    // UTILITY
    // NEXT SUFFIX FOR name; merge IS ATOMIC AND RETURNS THE POST-INCREMENT VALUE, SO THE FIRST THREAD GETS 0
    private static int next(final String name) {
        return THREADS.merge(name, 1, Integer::sum) - 1;
    }

    // MARKS task AS AN OWNED WORKER WHILE IT RUNS, RESTORING THE PREVIOUS MARK AFTER A NESTED run()
    private static Runnable owned(final Runnable task) {
        return () -> {
            final Boolean previous = WORKER.get();
            WORKER.set(Boolean.TRUE);
            try {
                task.run();
            } finally {
                if (previous == null) WORKER.remove();
                else WORKER.set(previous);
            }
        };
    }
}
