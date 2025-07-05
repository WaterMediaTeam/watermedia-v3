package org.watermedia.tools;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ThreadTool {
    public static boolean tryAdquireLock(Semaphore semaphore, long timeout, TimeUnit unit) {
        try {
            return semaphore.tryAcquire(timeout, unit);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupted status
        }
        return false;
    }
}
