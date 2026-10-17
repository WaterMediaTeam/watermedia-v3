package org.watermedia.tools;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

public class ThreadTool {
    private static final ConcurrentHashMap<String, Integer> THREADS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> WORKER = new ThreadLocal<>();

    public static Thread createStarted(final String name, final Runnable runnable) {
        // AUTO-APPENDS A PER-NAME COUNTER (name-0, name-1, ...); merge IS ATOMIC AND RETURNS THE POST-INCREMENT
        // VALUE, SO SUBTRACT 1 FOR THE SUFFIX
        final int c = THREADS.merge(name, 1, Integer::sum) - 1;
        final Thread t = new Thread(runnable, name + "-" + c);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
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

    public static ThreadGroupFactory createThreadGroupFactory(final String name, final int priority) {
        final AtomicInteger count = new AtomicInteger(0);
        return () -> {
            final int group = count.incrementAndGet();
            return (childName, r) -> {
                final Thread t = new Thread(r);
                t.setDaemon(true);
                t.setPriority(Math.min(Math.max(priority, Thread.MIN_PRIORITY), Thread.MAX_PRIORITY));
                t.setName(name + "-" + group + "-" + childName);
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

    /** Creates owned service workers whose callbacks cannot initiate blocking lifecycle operations. */
    public static ThreadFactory workerFactory(final String name, final int priority) {
        final ThreadFactory factory = createFactory(name, priority);
        return task -> factory.newThread(() -> {
            final Boolean previous = WORKER.get();
            WORKER.set(Boolean.TRUE);
            try {
                task.run();
            } finally {
                if (previous == null) WORKER.remove();
                else WORKER.set(previous);
            }
        });
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
}
