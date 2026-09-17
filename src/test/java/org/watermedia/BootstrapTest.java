package org.watermedia;

import org.watermedia.WaterMedia.BootStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.watermedia.WaterMedia.BootStatus.*;
import org.watermedia.WaterMediaModule.Bootstrap;

class BootstrapTest {
    private final WaterMedia client = new WaterMedia("TEST", null, null, true);
    private final WaterMedia server = new WaterMedia("TEST", null, null, false);

    @Test
    void serverSkipsClientFactoryWithoutConstructingIt() {
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.MEDIA, true, false, List.of(), () -> {
            throw new AssertionError("Client factory was invoked");
        })));
        boot.start(this.server);
        assertEquals(State.READY, boot.status().state());
        assertEquals(Outcome.SKIPPED, boot.status().modules().get(0).outcome());
        assertTrue(boot.status().failures().isEmpty());
        boot.stop(this.server);
        assertFalse(boot.ownsResources());
    }

    @Test
    void startupProgressIsCoherentAndNotReadyUntilWorkFinishes() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> new WaterMediaModule() {
            @Override
            protected void start(final WaterMedia context) throws InterruptedException {
                this.task(1, 1, "Configuration");
                this.work("config", 4, 8, false);
                entered.countDown();
                assertTrue(resume.await(5, TimeUnit.SECONDS));
            }
        })));
        final CompletableFuture<Void> running = CompletableFuture.runAsync(() -> boot.start(this.client));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            final BootStatus snapshot = boot.status();
            assertEquals(State.STARTING, snapshot.state());
            assertEquals(Id.CONFIG, snapshot.current());
            assertEquals(1, snapshot.step());
            assertEquals("Configuration", snapshot.progress().taskName());
            assertEquals(4, snapshot.progress().work());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.modules().clear());
            resume.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertEquals(State.STARTING, snapshot.state());
            assertEquals(State.READY, boot.status().state());
        } finally {
            resume.countDown();
            running.get(5, TimeUnit.SECONDS);
            boot.stop(this.client);
        }
    }

    @Test
    void failedPrerequisiteBlocksDependentAndReleasesPartialModuleInReverseOrder() {
        final List<Id> released = new ArrayList<>();
        final RuntimeException failure = new IllegalStateException("Cannot open network");
        final Bootstrap boot = new Bootstrap(List.of(
                new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) {}
                    @Override protected void release(final WaterMedia context) { released.add(Id.CONFIG); }
                }),
                new Bootstrap.Definition(Id.NETWORK, false, false, List.of(Id.CONFIG), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) { throw failure; }
                    @Override protected void release(final WaterMedia context) { released.add(Id.NETWORK); }
                }),
                new Bootstrap.Definition(Id.MEDIA, true, false, List.of(Id.NETWORK), () -> { throw new AssertionError("Blocked factory invoked"); })
        ));
        boot.start(this.client);
        assertEquals(State.DEGRADED, boot.status().state());
        assertEquals(Outcome.BLOCKED, boot.status().modules().get(2).outcome());
        assertEquals(Id.NETWORK, boot.status().modules().get(2).dependency());
        assertSame(failure, boot.status().failures().get(0).cause());
        boot.stop(this.client);
        assertEquals(List.of(Id.NETWORK, Id.CONFIG), released);
        boot.stop(this.client);
        assertEquals(2, released.size());
    }

    @Test
    void optionalOperationFailureDoesNotDisableIndependentMedia() {
        final Bootstrap boot = new Bootstrap(List.of(
                new Bootstrap.Definition(Id.BINARIES, true, false, List.of(), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) { this.failure("FFmpeg", new IllegalStateException("Missing native")); }
                }),
                new Bootstrap.Definition(Id.MEDIA, true, false, List.of(), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) { this.task(1, 1, "Images"); }
                })
        ));
        boot.start(this.client);
        assertEquals(State.DEGRADED, boot.status().state());
        assertEquals(Outcome.READY, boot.status().modules().get(1).outcome());
        assertEquals("FFmpeg", boot.status().failures().get(0).task());
        boot.stop(this.client);
    }

    @Test
    void essentialFailureUnwindsAndDoesNotPretendStartupSucceeded() {
        final List<String> closed = new ArrayList<>();
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> new WaterMediaModule() {
            @Override protected void start(final WaterMedia context) { throw new IllegalArgumentException("Invalid configuration"); }
            @Override protected void release(final WaterMedia context) { closed.add("config"); }
        })));
        assertThrows(IllegalStateException.class, () -> boot.start(this.client));
        assertEquals(State.FAILED, boot.status().state());
        assertEquals(List.of("config"), closed);
        assertFalse(boot.ownsResources());
    }

    @Test
    void fatalErrorsPropagateAfterCleanup() {
        final InternalError failure = new InternalError("Fatal VM failure");
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> new WaterMediaModule() {
            @Override protected void start(final WaterMedia context) { throw failure; }
        })));
        assertSame(failure, assertThrows(InternalError.class, () -> boot.start(this.client)));
        assertEquals(State.FAILED, boot.status().state());
        assertFalse(boot.ownsResources());
    }

    @Test
    void failedShutdownRetainsOwnershipUntilRetrySucceeds() {
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.NETWORK, false, false, List.of(), () -> new WaterMediaModule() {
            private boolean first = true;
            @Override protected void start(final WaterMedia context) {}
            @Override protected void release(final WaterMedia context) {
                if (this.first) { this.first = false; throw new IllegalStateException("Worker has not stopped"); }
            }
        })));
        boot.start(this.client);
        assertThrows(IllegalStateException.class, () -> boot.stop(this.client));
        assertTrue(boot.ownsResources());
        assertEquals(State.FAILED, boot.status().state());
        boot.stop(this.client);
        assertEquals(State.STOPPED, boot.status().state());
        assertFalse(boot.ownsResources());
    }

    @Test
    void rejectsInvalidDependencyOrder() {
        assertThrows(IllegalArgumentException.class, () -> new Bootstrap(List.of(
                new Bootstrap.Definition(Id.MEDIA, true, false, List.of(Id.CONFIG), () -> null))));
    }

    @Test
    void incompleteReleaseKeepsEarlierServicesAliveUntilRetry() {
        final List<Id> released = new ArrayList<>();
        final Bootstrap boot = new Bootstrap(List.of(
                new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) {}
                    @Override protected void release(final WaterMedia context) { released.add(Id.CONFIG); }
                }),
                new Bootstrap.Definition(Id.NETWORK, false, false, List.of(Id.CONFIG), () -> new WaterMediaModule() {
                    private boolean first = true;
                    @Override protected void start(final WaterMedia context) {}
                    @Override protected void release(final WaterMedia context) {
                        released.add(Id.NETWORK);
                        if (this.first) { this.first = false; throw new IllegalStateException("Still closing"); }
                    }
                })
        ));
        boot.start(this.client);
        assertThrows(IllegalStateException.class, () -> boot.stop(this.client));
        assertEquals(List.of(Id.NETWORK), released);
        assertEquals(Outcome.BLOCKED, boot.status().modules().get(0).outcome());
        boot.stop(this.client);
        assertEquals(List.of(Id.NETWORK, Id.NETWORK, Id.CONFIG), released);
    }
}
