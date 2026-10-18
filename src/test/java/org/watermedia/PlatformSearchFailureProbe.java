package org.watermedia;

import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class PlatformSearchFailureProbe {
    static void run() throws Exception {
        for (final Platform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        final CountDownLatch slowEntered = new CountDownLatch(1);
        final CountDownLatch releaseSlow = new CountDownLatch(1);
        final AtomicInteger successfulProbes = new AtomicInteger();
        PlatformAPI.register(new Platform() {
            @Override public String name() { return "Slow fixture"; }
            @Override public Resolution resolve(final URI uri) { return null; }
            @Override public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
                successfulProbes.incrementAndGet();
                slowEntered.countDown();
                try {
                    if (!releaseSlow.await(5, TimeUnit.SECONDS)) throw new PlatformException("Slow fixture", "not released");
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new PlatformException("Slow fixture", "interrupted");
                }
                return List.of(new PlatformResult(this, "late success", null, URI.create("https://fixture.invalid/success")));
            }
        });
        final AtomicInteger names = new AtomicInteger();
        PlatformAPI.register(new Platform() {
            @Override public String name() {
                if (names.incrementAndGet() > 1) throw new IllegalStateException("Broken diagnostic name");
                return "Failing fixture";
            }
            @Override public Resolution resolve(final URI uri) { return null; }
            @Override public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
                throw new PlatformException("Failing fixture", "Fixture probe failed");
            }
        });
        final var field = PlatformAPI.class.getDeclaredField("searchPool");
        field.setAccessible(true);
        final ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        final var coordinatorField = PlatformAPI.class.getDeclaredField("coordinator");
        coordinatorField.setAccessible(true);
        final ThreadPoolExecutor coordinator = (ThreadPoolExecutor) coordinatorField.get(null);
        try {
            final var search = PlatformAPI.search("failure contract");
            if (!slowEntered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Second probe did not start");
            if (!PlayerWait.awaitCondition(() -> pool.getCompletedTaskCount() >= 1, 3000))
                throw new AssertionError("Failing probe did not complete exceptionally");
            if (PlayerWait.awaitCondition(search::done, 250))
                throw new AssertionError("Search completed while another probe was still running");
            releaseSlow.countDown();
            if (!PlayerWait.awaitCondition(search::done, 3000) || search.results().size() != 1
                    || !search.results().get(0).title().equals("late success"))
                throw new AssertionError("Successful late probe was lost");
            if (!PlayerWait.awaitCondition(() -> coordinator.getCompletedTaskCount() >= 1, 3000))
                throw new AssertionError("Search coordinator did not finish publishing its cache");
            final var cached = PlatformAPI.search("failure contract");
            if (!cached.done() || cached.results().size() != 1 || !cached.results().get(0).title().equals("late success")
                    || successfulProbes.get() != 1)
                throw new AssertionError("Completed search did not cache its full result set");
        } finally {
            releaseSlow.countDown();
        }
    }
}
