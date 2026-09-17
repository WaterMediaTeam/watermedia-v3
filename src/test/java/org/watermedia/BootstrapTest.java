package org.watermedia;

import org.watermedia.WaterMedia.BootStatus;
import org.junit.jupiter.api.Test;
import org.watermedia.tools.ThreadTool;

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
    void presentBinariesFinishBeforeNetworkAndAbsentBinariesAreSkipped() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        final List<Id> started = new ArrayList<>();
        final Bootstrap boot = new Bootstrap(List.of(
                new Bootstrap.Definition(Id.BINARIES, true, true, List.of(), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) throws InterruptedException {
                        entered.countDown();
                        if (!resume.await(5, TimeUnit.SECONDS)) throw new InterruptedException("Test timeout");
                        started.add(Id.BINARIES);
                    }
                }),
                new Bootstrap.Definition(Id.NETWORK, false, false, List.of(), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) { started.add(Id.NETWORK); }
                })
        ));
        final CompletableFuture<Void> loading = CompletableFuture.runAsync(() -> boot.start(this.client));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(loading.isDone());
            assertEquals(Outcome.PENDING, boot.status().modules().get(1).outcome());
            resume.countDown();
            loading.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(Id.BINARIES, Id.NETWORK), started);
            assertTrue(boot.status().ready(Id.BINARIES));
        } finally {
            resume.countDown();
            loading.get(5, TimeUnit.SECONDS);
            boot.stop(this.client);
        }
        final Bootstrap absent = new Bootstrap(List.of(new Bootstrap.Definition(Id.BINARIES, true, true, List.of(), () -> null)));
        absent.start(this.client);
        assertEquals(State.READY, absent.status().state());
        assertEquals(Outcome.SKIPPED, absent.status().modules().get(0).outcome());
        assertFalse(absent.status().ready(Id.BINARIES));
        absent.stop(this.client);
    }

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
    void progressUpdatesDoNotDuplicateFailuresOrChangeOlderSnapshots() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        final RuntimeException failure = new IllegalStateException("Unavailable cache");
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.MEDIA, true, false, List.of(), () -> new WaterMediaModule() {
            @Override
            protected void start(final WaterMedia context) throws InterruptedException {
                this.task(1, 1, "Media");
                this.failure("Cache", failure);
                entered.countDown();
                assertTrue(resume.await(5, TimeUnit.SECONDS));
                for (int i = 1; i <= 8; i++) this.work("Media", i, 8, false);
                this.failure("Decoder", new IllegalStateException("Unavailable decoder"));
            }
        })));
        final CompletableFuture<Void> running = CompletableFuture.runAsync(() -> boot.start(this.client));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            final BootStatus snapshot = boot.status();
            assertEquals(1, snapshot.failures().size());
            assertSame(failure, snapshot.failures().get(0).cause());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.failures().clear());
            resume.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertEquals(1, snapshot.failures().size());
            assertEquals(0, snapshot.progress().work());
            assertEquals(2, boot.status().failures().size());
            assertSame(failure, boot.status().failures().get(0).cause());
            assertEquals(8, boot.status().progress().work());
            assertEquals(State.DEGRADED, boot.status().state());
        } finally {
            resume.countDown();
            running.get(5, TimeUnit.SECONDS);
            boot.stop(this.client);
        }
    }

    @Test
    void earlierAndReleasedModulesCannotPublishLateUpdates() {
        final WaterMediaModule first = new WaterMediaModule() {
            @Override protected void start(final WaterMedia context) { this.task(1, 1, "Configuration"); }
        };
        final Bootstrap boot = new Bootstrap(List.of(
                new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> first),
                new Bootstrap.Definition(Id.NETWORK, false, false, List.of(Id.CONFIG), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) {
                        this.task(1, 1, "Network");
                        first.task(2, 2, "Late configuration");
                        first.work("Late work", 1, 2, false);
                        first.failure("Late failure", new IllegalStateException("Stale callback"));
                    }
                })
        ));
        boot.start(this.client);
        assertEquals(State.READY, boot.status().state());
        assertEquals(Id.NETWORK, boot.status().current());
        assertEquals("Network", boot.status().progress().taskName());
        assertEquals(0, boot.status().progress().work());
        assertTrue(boot.status().failures().isEmpty());
        boot.stop(this.client);
        final BootStatus stopped = boot.status();
        first.task(3, 3, "Released configuration");
        first.work("Released work", 2, 2, false);
        first.failure("Released failure", new IllegalStateException("Released callback"));
        assertSame(stopped, boot.status());
    }

    @Test
    void optionalLinkageFailureAllowsIndependentModulesToStart() {
        final LinkageError failure = new UnsatisfiedLinkError("Missing optional native");
        final Bootstrap boot = new Bootstrap(List.of(
                new Bootstrap.Definition(Id.NETWORK, false, false, List.of(), () -> { throw failure; }),
                new Bootstrap.Definition(Id.MEDIA, true, false, List.of(), () -> new WaterMediaModule() {
                    @Override protected void start(final WaterMedia context) {}
                })
        ));
        boot.start(this.client);
        assertEquals(State.DEGRADED, boot.status().state());
        assertEquals(Outcome.FAILED, boot.status().modules().get(0).outcome());
        assertTrue(boot.status().ready(Id.MEDIA));
        assertSame(failure, boot.status().failures().get(0).cause());
        boot.stop(this.client);
        assertFalse(boot.ownsResources());
    }

    @Test
    void interruptedStartupClearsInterruptDuringCleanupAndRestoresIt() {
        final List<Boolean> interrupts = new ArrayList<>();
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> new WaterMediaModule() {
            @Override protected void start(final WaterMedia context) throws InterruptedException {
                ThreadTool.interrupt();
                throw new InterruptedException("Cancelled startup");
            }
            @Override protected void release(final WaterMedia context) { interrupts.add(ThreadTool.isInterrupted()); }
        })));
        try {
            assertThrows(IllegalStateException.class, () -> boot.start(this.client));
            assertTrue(ThreadTool.isInterrupted());
            assertEquals(List.of(false), interrupts);
            assertEquals(State.FAILED, boot.status().state());
            assertFalse(boot.ownsResources());
            assertInstanceOf(InterruptedException.class, boot.status().failures().get(0).cause());
        } finally {
            Thread.interrupted();
        }
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
    void fatalCleanupKeepsStartupFailureAndRetainsResourcesForRetry() {
        final AssertionError startup = new AssertionError("Startup failed");
        final InternalError cleanup = new InternalError("Cleanup failed");
        final Bootstrap boot = new Bootstrap(List.of(new Bootstrap.Definition(Id.CONFIG, false, true, List.of(), () -> new WaterMediaModule() {
            private boolean first = true;
            @Override protected void start(final WaterMedia context) { throw startup; }
            @Override protected void release(final WaterMedia context) {
                if (this.first) { this.first = false; throw cleanup; }
            }
        })));
        assertSame(cleanup, assertThrows(InternalError.class, () -> boot.start(this.client)));
        assertArrayEquals(new Throwable[] { startup }, cleanup.getSuppressed());
        assertEquals(State.FAILED, boot.status().state());
        assertEquals(2, boot.status().failures().size());
        assertTrue(boot.ownsResources());
        boot.stop(this.client);
        assertEquals(State.STOPPED, boot.status().state());
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
