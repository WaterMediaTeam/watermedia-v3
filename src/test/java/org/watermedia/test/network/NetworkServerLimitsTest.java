package org.watermedia.test.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.network.NetworkServer;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.PlayerWait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.watermedia.test.support.MediaBootstrap.runProbe;

public class NetworkServerLimitsTest {
    private static final String TOKEN = "local-test-upload-token";
    @TempDir Path directory;
    private WaterMedia instance;
    private String previousBind;
    private String previousToken;
    private int previousRequests;
    private int previousTimeout;
    private int previousStorage;
    private int previousUpload;

    @BeforeEach
    void start() throws Exception {
        final var config = WaterMediaConfig.network;
        this.previousBind = config.serverBindAddress;
        this.previousToken = config.token;
        this.previousRequests = config.serverMaxRequests;
        this.previousTimeout = config.serverTimeout;
        this.previousStorage = config.maxStorageSize;
        this.previousUpload = config.maxUploadSize;
        config.serverBindAddress = "127.0.0.1";
        config.token = TOKEN;
        config.serverMaxRequests = 2;
        config.serverTimeout = 1000;
        config.maxStorageSize = 1;
        config.maxUploadSize = 1;
        this.instance = instance(this.directory);
        NetworkServer.start(0, this.instance);
    }

    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].equals("storage-lock"))
            throw new IllegalArgumentException("Unknown storage probe");
        final Path directory = Path.of("").toAbsolutePath();
        final var config = WaterMediaConfig.network;
        config.serverBindAddress = "127.0.0.1";
        config.token = TOKEN;
        config.serverMaxRequests = 2;
        config.serverTimeout = 1000;
        config.maxStorageSize = 1;
        config.maxUploadSize = 1;
        try {
            NetworkServer.start(0, instance(directory));
            throw new AssertionError("Another process acquired the live storage directory");
        } catch (final IOException occupied) {
            if (!occupied.getMessage().contains("storage is already in use")) throw occupied;
        }
        if (!Files.exists(directory.resolve("watermedia/files/.upload-Live1234/partial.bin")))
            throw new AssertionError("The competing process deleted a live upload");
        System.out.println("PROBE_OK storage-lock");
    }

    @AfterEach
    void stop() {
        NetworkServer.stop();
        final var config = WaterMediaConfig.network;
        config.serverBindAddress = this.previousBind;
        config.token = this.previousToken;
        config.serverMaxRequests = this.previousRequests;
        config.serverTimeout = this.previousTimeout;
        config.maxStorageSize = this.previousStorage;
        config.maxUploadSize = this.previousUpload;
    }

    @Test
    void rejectsPublicDefaultTokenAndPropagatesBindFailure() throws Exception {
        NetworkServer.stop();
        WaterMediaConfig.network.serverBindAddress = "0.0.0.0";
        WaterMediaConfig.network.token = "watermedia_default_token_change_it";
        assertThrows(IllegalArgumentException.class, () -> NetworkServer.start(0, this.instance));
        assertNull(NetworkServer.address());
        WaterMediaConfig.network.serverBindAddress = "127.0.0.1";
        WaterMediaConfig.network.token = " ";
        assertThrows(IllegalArgumentException.class, () -> NetworkServer.start(0, this.instance));
        WaterMediaConfig.network.token = TOKEN;
        try (final ServerSocket occupied = new ServerSocket(0)) {
            assertThrows(IOException.class, () -> NetworkServer.start(occupied.getLocalPort(), this.instance));
            assertNull(NetworkServer.address());
        }
        try (final FileChannel contender = FileChannel.open(this.directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE);
             final FileLock released = contender.tryLock()) {
            assertNotNull(released);
        }
        NetworkServer.start(0, this.instance);
        final InetSocketAddress address = NetworkServer.address();
        assertNotNull(address);
        assertTrue(address.getAddress().isLoopbackAddress());
    }

    @Test
    void tokenSnapshotDoesNotChangeDuringARequestGeneration() throws Exception {
        WaterMediaConfig.network.token = "replacement";
        assertEquals(401, this.upload("replacement", new byte[1]).getResponseCode());
        assertEquals(200, this.upload(TOKEN, new byte[1]).getResponseCode());
    }

    @Test
    void aggregatesUnauthorizedWarningsAndReportsPendingRejectionsOnStop() throws Exception {
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            for (int request = 0; request < 12; request++) {
                final HttpURLConnection connection = this.upload("untrusted-secret", new byte[1]);
                try { assertEquals(401, connection.getResponseCode()); }
                finally { connection.disconnect(); }
            }
            final var warnings = capture.events().stream()
                    .filter(event -> event.getMessage().getFormattedMessage().contains("unauthorized uploads")).toList();
            assertEquals(1, warnings.size());
            assertEquals("NetworkServer", warnings.get(0).getMarker().getName());
            assertTrue(warnings.get(0).getMessage().getFormattedMessage().contains("Rejected 1 unauthorized uploads"));
            NetworkServer.stop();
            final var finalWarnings = capture.events().stream()
                    .map(event -> event.getMessage().getFormattedMessage()).filter(text -> text.contains("unauthorized uploads")).toList();
            assertEquals(2, finalWarnings.size());
            assertTrue(finalWarnings.get(1).contains("Rejected 11 additional unauthorized uploads"));
            assertTrue(capture.events().stream().noneMatch(event -> event.getMessage().getFormattedMessage().contains("untrusted-secret")));
        }
    }

    @Test
    void totalStorageIncludesExistingFilesAfterRestart() throws Exception {
        final byte[] data = new byte[600_000];
        assertEquals(200, this.upload(TOKEN, data).getResponseCode());
        NetworkServer.stop();
        NetworkServer.start(0, this.instance);
        try (final Socket rejected = this.request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: next.bin\r\nContent-Length: 600000\r\n\r\n")) {
            assertTrue(new BufferedReader(new InputStreamReader(rejected.getInputStream(), StandardCharsets.US_ASCII)).readLine().contains("507"));
        }
    }

    @Test
    void startupRemovesOnlyIncompleteUploadDirectories() throws Exception {
        NetworkServer.stop();
        final Path storage = this.directory.resolve("watermedia/files");
        final Path incomplete = Files.createDirectory(storage.resolve(".upload-AbC123zZ"));
        Files.write(incomplete.resolve("partial.bin"), new byte[800_000]);
        final Path empty = Files.createDirectory(storage.resolve(".upload-Empty123"));
        final Path unknown = Files.createDirectory(storage.resolve(".upload-short"));
        Files.writeString(unknown.resolve("keep.txt"), "keep");
        final Path multiple = Files.createDirectory(storage.resolve(".upload-Two12345"));
        Files.writeString(multiple.resolve("one.txt"), "one");
        Files.writeString(multiple.resolve("two.txt"), "two");
        final Path complete = Files.createDirectory(storage.resolve("Pub12345"));
        Files.writeString(complete.resolve("file.txt"), "saved");
        NetworkServer.start(0, this.instance);
        assertFalse(Files.exists(incomplete));
        assertFalse(Files.exists(empty));
        assertEquals("keep", Files.readString(unknown.resolve("keep.txt")));
        assertEquals("one", Files.readString(multiple.resolve("one.txt")));
        assertEquals("two", Files.readString(multiple.resolve("two.txt")));
        assertEquals("saved", Files.readString(complete.resolve("file.txt")));
        assertEquals(200, this.upload(TOKEN, new byte[400_000]).getResponseCode());
    }

    @Test
    void occupiedStorageCannotCleanAnotherServerUpload() throws Exception {
        NetworkServer.stop();
        final Path storage = this.directory.resolve("watermedia/files");
        final Path incomplete = Files.createDirectory(storage.resolve(".upload-Live1234"));
        final Path partial = Files.writeString(incomplete.resolve("partial.bin"), "still uploading");
        final Path lock = storage.resolve(".server.lock");
        try (final FileChannel owner = FileChannel.open(lock, StandardOpenOption.WRITE);
             final FileLock held = owner.lock()) {
            assertThrows(IOException.class, () -> NetworkServer.start(0, this.instance));
            assertNull(NetworkServer.address());
            assertEquals("still uploading", Files.readString(partial));
        }

        NetworkServer.start(0, this.instance);
        assertFalse(Files.exists(incomplete));
        try (final FileChannel contender = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            assertThrows(OverlappingFileLockException.class, contender::tryLock);
        }
    }

    @Test
    void secondProcessCannotCleanLiveUpload() throws Exception {
        final Path storage = this.directory.resolve("watermedia/files");
        final Path incomplete = Files.createDirectory(storage.resolve(".upload-Live1234"));
        final Path partial = Files.writeString(incomplete.resolve("partial.bin"), "still uploading");
        runProbe(NetworkServerLimitsTest.class, this.directory, "storage-lock", false);
        assertEquals("still uploading", Files.readString(partial));
        assertEquals(200, this.get().getResponseCode());
    }

    @Test
    void startupDoesNotFollowIncompleteUploadSymlinks() throws Exception {
        NetworkServer.stop();
        final Path storage = this.directory.resolve("watermedia/files");
        final Path outside = Files.writeString(this.directory.resolve("outside.txt"), "external");
        final Path linked = storage.resolve(".upload-Link1234");
        final Path child = Files.createDirectory(storage.resolve(".upload-Child123"));
        try {
            Files.createSymbolicLink(linked, outside);
            Files.createSymbolicLink(child.resolve("outside"), outside);
        } catch (final FileSystemException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getMessage());
        }

        NetworkServer.start(0, this.instance);
        assertTrue(Files.exists(linked, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(child.resolve("outside"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("external", Files.readString(outside));
    }

    @Test
    void reservationsBoundConcurrentUploadsAndDisconnectReleasesThem() throws Exception {
        try (final Socket stalled = this.request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: partial.bin\r\nContent-Length: 700000\r\n\r\nx")) {
            assertTrue(PlayerWait.awaitCondition(this::hasTemporary, 500));
            try (final Socket rejected = this.request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: other.bin\r\nContent-Length: 700000\r\n\r\n")) {
                assertTrue(new BufferedReader(new InputStreamReader(rejected.getInputStream(), StandardCharsets.US_ASCII)).readLine().contains("507"));
            }
        }
        assertTrue(PlayerWait.awaitCondition(() -> !this.hasTemporary(), 3000));
        assertEquals(200, this.upload(TOKEN, new byte[700_000]).getResponseCode());
    }

    @Test
    void deadlineClosesSlowHeadersAndAdmissionHasNoQueue() throws Exception {
        try (final Socket first = this.request("GET / HTTP/1.1\r\nHost: localhost");
             final Socket second = this.request("GET / HTTP/1.1\r\nHost: localhost")) {
            final Field running = NetworkServer.class.getDeclaredField("running");
            running.setAccessible(true);
            final Object state = running.get(null);
            final Field workers = state.getClass().getDeclaredField("executor");
            workers.setAccessible(true);
            final ThreadPoolExecutor executor = (ThreadPoolExecutor) workers.get(state);
            assertTrue(PlayerWait.awaitCondition(() -> executor.getActiveCount() == 2, 500));
            try (final Socket rejected = this.request("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")) {
                try { assertEquals(-1, rejected.getInputStream().read()); }
                catch (final SocketException closed) { assertFalse(rejected.isClosed()); }
            }
            assertEquals(0, executor.getQueue().size());
            assertTrue(executor.getLargestPoolSize() <= 2);
            try { assertEquals(-1, first.getInputStream().read()); }
            catch (final SocketException closed) { assertFalse(first.isClosed()); }
            try { assertEquals(-1, second.getInputStream().read()); }
            catch (final SocketException closed) { assertFalse(second.isClosed()); }
        }
        assertTrue(PlayerWait.awaitCondition(() -> {
            try { return this.get().getResponseCode() == 200; }
            catch (final IOException rejected) { return false; }
        }, 3000));
    }

    @Test
    void deadlineAndStopRemoveIncompleteUploads() throws Exception {
        final String upload = "POST /upload HTTP/1.1\r\nHost: localhost\r\nX-WaterMedia-Token: " + TOKEN
                + "\r\nX-WaterMedia-Filename: partial.bin\r\nContent-Length: 1000\r\n\r\nx";
        try (final Socket stalled = this.request(upload)) {
            assertTrue(PlayerWait.awaitCondition(this::hasTemporary, 500));
            assertTrue(PlayerWait.awaitCondition(() -> !this.hasTemporary(), 3000));
        }
        final int port = port();
        try (final Socket stalled = this.request(upload)) {
            assertTrue(PlayerWait.awaitCondition(this::hasTemporary, 500));
            NetworkServer.stop();
            assertFalse(this.hasTemporary());
            assertNull(NetworkServer.address());
        }
        NetworkServer.stop();
        NetworkServer.start(port, this.instance);
        assertEquals(200, this.get().getResponseCode());
    }

    @Test
    void incompleteShutdownKeepsOwnershipUntilWorkersActuallyExit() throws Exception {
        final Field running = NetworkServer.class.getDeclaredField("running");
        running.setAccessible(true);
        final Object state = running.get(null);
        final Field workers = state.getClass().getDeclaredField("executor");
        workers.setAccessible(true);
        final ThreadPoolExecutor executor = (ThreadPoolExecutor) workers.get(state);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { finish.await(); done = true; }
                catch (final InterruptedException ignored) {}
            }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, NetworkServer::stop);
            assertSame(state, running.get(null));
            assertThrows(IllegalStateException.class, () -> NetworkServer.start(0, this.instance));
            try (final FileChannel contender = FileChannel.open(this.directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, contender::tryLock);
            }
        } finally {
            finish.countDown();
            NetworkServer.stop();
        }
        try (final FileChannel contender = FileChannel.open(this.directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE);
             final FileLock released = contender.tryLock()) {
            assertNotNull(released);
        }
        NetworkServer.start(0, this.instance);
        assertEquals(200, this.get().getResponseCode());
    }

    private boolean hasTemporary() {
        try (final var paths = Files.list(this.directory.resolve("watermedia/files"))) {
            return paths.anyMatch(path -> path.getFileName().toString().startsWith(".upload-"));
        } catch (final IOException e) { throw new IllegalStateException(e); }
    }

    private static WaterMedia instance(final Path directory) throws ReflectiveOperationException {
        final Field singleton = WaterMedia.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        final Object saved = singleton.get(null);
        try {
            singleton.set(null, null);
            final Constructor<WaterMedia> constructor = WaterMedia.class.getDeclaredConstructor(String.class, Path.class, Path.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance("FILE_SERVER_TEST", directory, directory, false);
        } finally {
            singleton.set(null, saved);
        }
    }

    private static int port() {
        final InetSocketAddress address = NetworkServer.address();
        assertNotNull(address);
        return address.getPort();
    }

    private Socket request(final String request) throws IOException {
        final Socket socket = new Socket("127.0.0.1", port());
        socket.setSoTimeout(4000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private HttpURLConnection get() throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port() + "/").toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(4000);
        return connection;
    }

    private HttpURLConnection upload(final String token, final byte[] data) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port() + "/upload").toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(4000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty(NetworkAPI.X_WATERMEDIA_TOKEN, token);
        connection.setRequestProperty(NetworkAPI.X_WATERMEDIA_FILENAME, "data.bin");
        connection.setFixedLengthStreamingMode(data.length);
        try (final var output = connection.getOutputStream()) { output.write(data); }
        return connection;
    }
}
