package org.watermedia;

import org.watermedia.WaterMedia.BootStatus;
import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.network.NetworkServer;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MRL;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.ThreadTool;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServiceLifecycleProbe {
    public static void main(final String[] arguments) throws Exception {
        final Path root = Path.of("").toAbsolutePath();
        WaterConfig.setPath(root.resolve("config"));
        WaterMediaConfig.media.ffmpeg.disable = true;
        WaterMediaConfig.network.enableServer = false;
        WaterMediaConfig.network.forceEnableServer = false;
        WaterMediaConfig.network.timeout = 2000;
        final boolean client = !arguments[0].equals("uploads");
        WaterMedia.start("SERVICE_TEST", root.resolve("first"), root, client);
        try {
            switch (arguments[0]) {
                case "platform" -> platforms(root);
                case "uploads" -> uploads(root);
                case "reload" -> reload();
                case "evicted" -> evicted();
                case "status" -> status();
                case "failed-probe" -> PlatformSearchFailureProbe.run();
                default -> throw new IllegalArgumentException("Unknown scenario: " + arguments[0]);
            }
        } finally {
            WaterMedia.stop();
        }
        System.out.println("PROBE_OK " + arguments[0]);
    }

    private static void reload() throws Exception {
        for (final IPlatform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        PlatformAPI.register(new IPlatform() {
            @Override public String name() { return "Reload fixture"; }
            @Override public PlatformData getData(final URI uri) {
                return new PlatformData(null, new DataSource(MediaType.VIDEO, null, null, new RequestHeaders(),
                        List.of(new DataQuality(uri, 0, 0)), null, null));
            }
        });
        final MRL mrl = MediaAPI.mrl(URI.create("fixture:reload"));
        if (!mrl.await(3000) || mrl.status() != MRL.Status.LOADED) throw new AssertionError("Fixture did not load");
        awaitIdle(mrl);
        final var ownerField = MRL.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        final Object owner = ownerField.get(mrl);
        final FutureTask<IllegalStateException> attempt = new FutureTask<>(() -> {
            try {
                mrl.reload();
                throw new AssertionError("Retired MRL accepted a reload");
            } catch (final IllegalStateException expected) { return expected; }
        });
        final Thread racer = new Thread(attempt, "Service-Test-Reload");
        racer.setDaemon(true);
        final Throwable retired;
        synchronized (owner) {
            racer.start();
            if (!PlayerWait.awaitCondition(() -> racer.getState() == Thread.State.BLOCKED, 3000))
                throw new AssertionError("Reload did not wait at the state mutation lock");
            // NO LOADER IS ACTIVE; STOP CAN TAKE THIS REENTRANT OWNER LOCK WITHOUT WAITING FOR THAT WORKER.
            WaterMedia.stop();
            if (mrl.status() != MRL.Status.FORGOTTEN) throw new AssertionError("Stop did not retire the MRL");
            retired = mrl.exception();
        }
        attempt.get(3, TimeUnit.SECONDS);
        if (mrl.status() != MRL.Status.FORGOTTEN || mrl.exception() != retired)
            throw new AssertionError("Reload changed the retired MRL state or failure");
    }

    private static void evicted() throws Exception {
        for (final IPlatform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        final URI key = URI.create("fixture:expired");
        final AtomicBoolean first = new AtomicBoolean(true);
        PlatformAPI.register(new IPlatform() {
            @Override public String name() { return "Expiring fixture"; }
            @Override public PlatformData getData(final URI uri) {
                final var expires = key.equals(uri) && first.getAndSet(false) ? Instant.EPOCH : null;
                return new PlatformData(expires, new DataSource(MediaType.VIDEO, null, null, new RequestHeaders(),
                        List.of(new DataQuality(uri, 0, 0)), null, null));
            }
        });
        final MRL old = MediaAPI.mrl(key);
        if (!old.await(3000) || old.status() != MRL.Status.EXPIRED || old.sourceCount() != 1)
            throw new AssertionError("Expiring fixture did not load");
        awaitIdle(old);
        final var ownerField = MRL.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        final Object owner = ownerField.get(old);
        final var cleanupField = owner.getClass().getDeclaredField("nextClean");
        cleanupField.setAccessible(true);
        synchronized (owner) { cleanupField.setLong(owner, 0); }
        final MRL sweep = MediaAPI.mrl(URI.create("fixture:cleanup"));
        if (!sweep.await(3000) || old.status() != MRL.Status.FORGOTTEN || old.sourceCount() != 0)
            throw new AssertionError("Cleanup did not invalidate the expired handle");
        final MRL replacement = old.reload();
        if (replacement == old || !replacement.await(3000) || replacement.status() != MRL.Status.LOADED
                || replacement != MediaAPI.mrl(key) || old.status() != MRL.Status.FORGOTTEN || old.sourceCount() != 0)
            throw new AssertionError("Reload did not return the canonical replacement");
        WaterMedia.stop();
        if (replacement.status() != MRL.Status.FORGOTTEN || replacement.sourceCount() != 0)
            throw new AssertionError("Replacement escaped its owning session shutdown");
        try {
            replacement.reload();
            throw new AssertionError("Retired replacement accepted a reload");
        } catch (final IllegalStateException expected) {}
    }

    private static void awaitIdle(final MRL mrl) throws Exception {
        final var lockField = MRL.class.getDeclaredField("loadLock");
        final var loadingField = MRL.class.getDeclaredField("loading");
        lockField.setAccessible(true);
        loadingField.setAccessible(true);
        final Object lock = lockField.get(mrl);
        if (!PlayerWait.awaitCondition(() -> {
            synchronized (lock) {
                try { return !loadingField.getBoolean(mrl); }
                catch (final IllegalAccessException failure) { throw new AssertionError(failure); }
            }
        }, 3000)) throw new AssertionError("Loader did not become idle");
    }

    private static void status() throws Exception {
        for (final IPlatform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        PlatformAPI.register(new IPlatform() {
            @Override public String name() { return "Expiring status fixture"; }
            @Override public PlatformData getData(final URI uri) {
                return new PlatformData(Instant.EPOCH,
                        new DataSource(MediaType.VIDEO, null, null, new RequestHeaders(),
                                List.of(new DataQuality(uri, 0, 0)), null, null));
            }
        });
        final MRL mrl = MediaAPI.mrl(URI.create("fixture:status"));
        if (!mrl.await(3000) || mrl.sourceCount() != 1) throw new AssertionError("Fixture did not load");
        awaitIdle(mrl);
        final var ownerField = MRL.class.getDeclaredField("owner");
        final var lockField = MRL.class.getDeclaredField("loadLock");
        ownerField.setAccessible(true);
        lockField.setAccessible(true);
        final Object owner = ownerField.get(mrl);
        final Object lock = lockField.get(mrl);
        final FutureTask<MRL.Status> attempt = new FutureTask<>(mrl::status);
        final Thread reader = new Thread(attempt, "Service-Test-Status");
        reader.setDaemon(true);
        synchronized (owner) {
            synchronized (lock) {
                reader.start();
                if (!PlayerWait.awaitCondition(() -> reader.getState() == Thread.State.BLOCKED, 3000))
                    throw new AssertionError("Status read escaped the retirement state lock");
                // THE LOADER IS IDLE AND THESE ARE THE SAME REENTRANT LOCKS USED BY RETIREMENT.
                WaterMedia.stop();
            }
        }
        if (attempt.get(3, TimeUnit.SECONDS) != MRL.Status.FORGOTTEN || mrl.sourceCount() != 0)
            throw new AssertionError("Expiry overwrote retirement or observed inconsistent cleared state");
    }

    private static void platforms(final Path root) throws Exception {
        for (final IPlatform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final CountDownLatch resolveEntered = new CountDownLatch(1);
        final CountDownLatch resolveResume = new CountDownLatch(1);
        final AtomicBoolean callbackGuard = new AtomicBoolean();
        try {
            PlatformAPI.register(new IPlatform() {
                @Override public String name() { return "Controlled search"; }
                @Override public PlatformData getData(final URI uri) {
                    if (!"fixture".equals(uri.getScheme())) return null;
                    resolveEntered.countDown();
                    boolean done = false;
                    while (!done) {
                        try { done = resolveResume.await(10, TimeUnit.SECONDS); }
                        catch (final InterruptedException ignored) {}
                    }
                    return new PlatformData(null, new DataSource(MediaType.VIDEO, null, null, new RequestHeaders(),
                            List.of(new DataQuality(uri, 0, 0)), null, null));
                }
                @Override public List<PlatformResult> search(final String query, final int limit) {
                    if (!ThreadTool.workerThread()) throw new AssertionError("Search callback is not marked as an owned worker");
                    if (query.equals("old")) {
                        entered.countDown();
                        boolean done = false;
                        while (!done) {
                            try { done = resume.await(10, TimeUnit.SECONDS); }
                            catch (final InterruptedException ignored) {}
                        }
                    } else {
                        try {
                            WaterMedia.stop();
                            throw new AssertionError("Worker initiated a blocking shutdown");
                        } catch (final IllegalStateException expected) { callbackGuard.set(true); }
                    }
                    return List.of(new PlatformResult("fixture", query, null, URI.create("https://fixture.invalid/" + query)));
                }
            });
            final var old = PlatformAPI.search("old");
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Old probe did not enter");
            final var fresh = PlatformAPI.search("fresh");
            if (!PlayerWait.awaitCondition(fresh::done, 3000)) throw new AssertionError("New search was blocked behind a cancelled probe");
            if (!callbackGuard.get() || fresh.results().size() != 1 || !fresh.results().get(0).title().equals("fresh"))
                throw new AssertionError("Fresh search has incorrect results");
            if (!old.history().equals(List.of("old")) || !fresh.history().equals(List.of("fresh", "old")))
                throw new AssertionError("Search history was not a submission snapshot");
            MediaAPI.mrl(URI.create("fixture:held"));
            if (!resolveEntered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Resolver did not enter");
            final FutureTask<Void> stop = new FutureTask<>(() -> { WaterMedia.stop(); return null; });
            final Thread shutdown = new Thread(stop, "Service-Test-Shutdown");
            shutdown.start();
            try {
                if (!PlayerWait.awaitCondition(() -> WaterMedia.status().state() == BootStatus.State.STOPPING, 3000))
                    throw new AssertionError("Shutdown did not publish its admission barrier");
                try {
                    PlatformAPI.search("during-media-close");
                    throw new AssertionError("Search admitted work while an earlier service was still closing");
                } catch (final IllegalStateException expected) {}
                resolveResume.countDown();
                if (!PlayerWait.awaitCondition(() -> PlatformAPI.platforms().isEmpty(), 3000))
                    throw new AssertionError("Shutdown did not close search admission");
                try {
                    PlatformAPI.search("stopped");
                    throw new AssertionError("A stopped search service admitted a query");
                } catch (final IllegalStateException expected) {}
                resume.countDown();
                stop.get(5, TimeUnit.SECONDS);
            } finally {
                resolveResume.countDown();
                resume.countDown();
                shutdown.join(5000);
            }
            if (!old.results().isEmpty() || old.done()) throw new AssertionError("Late old results escaped their generation");
            WaterMedia.start("SERVICE_TEST", root.resolve("second"), root, true);
            for (final IPlatform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
            PlatformAPI.register(new IPlatform() {
                @Override public String name() { return "Replacement search"; }
                @Override public PlatformData getData(final URI uri) { return null; }
                @Override public List<PlatformResult> search(final String query, final int limit) {
                    return List.of(new PlatformResult("replacement", "second session", null, URI.create("https://fixture.invalid/new")));
                }
            });
            final var restarted = PlatformAPI.search("fresh");
            if (!PlayerWait.awaitCondition(restarted::done, 3000) || restarted.results().size() != 1
                    || !restarted.results().get(0).title().equals("second session") || !restarted.history().equals(List.of("fresh")))
                throw new AssertionError("Search cache or history crossed the session boundary");
        } finally {
            resolveResume.countDown();
            resume.countDown();
        }
    }

    private static void uploads(final Path root) throws Exception {
        final CountDownLatch entered = new CountDownLatch(4);
        final CountDownLatch respond = new CountDownLatch(1);
        final AtomicBoolean immediate = new AtomicBoolean();
        final var handlers = Executors.newFixedThreadPool(4, ThreadTool.createFactory("Upload-Origin", Thread.NORM_PRIORITY));
        final Path file = Files.write(root.resolve("upload.bin"), new byte[8192]);
        try (final LocalHttp origin = LocalHttp.start("/upload", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (!immediate.get()) {
                entered.countDown();
                try { respond.await(5, TimeUnit.SECONDS); }
                catch (final InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            try { LocalHttp.respond(exchange, "text/plain", "stored12".getBytes(StandardCharsets.UTF_8), 0); }
            catch (final IOException closed) { exchange.close(); }
        }, handlers)) {
            WaterMediaConfig.network.remoteHost = origin.uri("").toString();
            final NetworkServer.UploadStatus[] statuses = new NetworkServer.UploadStatus[12];
            for (int i = 0; i < statuses.length; i++) statuses[i] = NetworkAPI.upload(file.toFile());
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Upload workers did not start");
            WaterMedia.stop();
            for (final var status: statuses) {
                if (!status.failed() || status.completed()) throw new AssertionError("Shutdown left an upload nonterminal or completed");
            }
            if (!NetworkAPI.upload(file.toFile()).failed()) throw new AssertionError("Stopped service accepted an upload");
            respond.countDown();
            immediate.set(true);
            WaterMedia.start("SERVICE_TEST", root.resolve("second"), root, false);
            WaterMediaConfig.network.remoteHost = origin.uri("").toString();
            final var next = NetworkAPI.upload(file.toFile());
            if (!PlayerWait.awaitCondition(() -> next.completed() || next.failed(), 5000) || !next.completed() || !"stored12".equals(next.id()))
                throw new AssertionError("Restarted upload service failed: " + next.error());
            for (final var previous: statuses) {
                if (!previous.failed() || previous.completed()) throw new AssertionError("Old upload was completed by a later generation");
            }
        } finally {
            respond.countDown();
            handlers.shutdownNow();
            handlers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
