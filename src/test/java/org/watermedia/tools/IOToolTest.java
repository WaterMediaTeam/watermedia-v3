package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.test.support.LocalHttp;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link IOTool}'s bounded IO, verified downloads and platform identification.
 */
@DisplayName("IOTool")
public class IOToolTest {

    @Test
    void testWorkerStartsWithUtf8RatherThanLatePropertyMutation() {
        assertEquals("UTF-8", System.getProperty("file.encoding"));
        assertEquals(StandardCharsets.UTF_8, Charset.defaultCharset());
    }

    @Test
    void nativeOperatingSystemsDoNotMisclassifyDarwinOrUnsupportedKernels() {
        final String previous = System.getProperty("os.name");
        try {
            for (final String name: new String[] { "Darwin", "Mac OS X" }) {
                System.setProperty("os.name", name);
                assertEquals("macos", IOTool.os());
            }
            System.setProperty("os.name", "Windows 10");
            assertEquals("windows", IOTool.os());
            System.setProperty("os.name", "Linux");
            assertEquals("linux", IOTool.os());
            for (final String name: new String[] { "AIX", "FreeBSD", "Unix", "unknown" }) {
                System.setProperty("os.name", name);
                assertNull(IOTool.os());
            }
        } finally {
            if (previous == null) System.clearProperty("os.name");
            else System.setProperty("os.name", previous);
        }
    }

    @Test
    void hashVerificationKeepsMismatchedFilesForTheirOwner(@TempDir final Path directory) throws IOException {
        final Path file = Files.writeString(directory.resolve("binary"), "original");
        final String hash = IOTool.sha256(file);
        IOTool.verifySha256(file, hash.toUpperCase(Locale.ROOT));
        assertThrows(IOException.class, () -> IOTool.verifySha256(file, "0".repeat(64)));
        assertEquals("original", Files.readString(file));
        assertThrows(IOException.class, () -> IOTool.verifySha256(file, "invalid"));
    }

    @Test
    void nativeFileChecksRejectSymbolicLinks(@TempDir final Path directory) throws IOException {
        final Path file = Files.writeString(directory.resolve("binary"), "original");
        final Path link = directory.resolve("linked");
        try { Files.createSymbolicLink(link, file); }
        catch (final FileSystemException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getMessage());
        }
        assertThrows(IOException.class, () -> IOTool.verifySha256(link, IOTool.sha256(file)));
        assertThrows(IOException.class, () -> IOTool.makeExecutable(link));
        assertEquals("original", Files.readString(file));
    }

    @Test
    void generationPointersStayInsideTheStorageDirectory(@TempDir final Path directory) throws IOException {
        final Path generation = Files.createDirectory(directory.resolve("fresh"));
        IOTool.publishGeneration(directory, generation);
        assertEquals(generation, IOTool.currentGeneration(directory));
        Files.writeString(directory.resolve("current"), "../outside");
        assertNull(IOTool.currentGeneration(directory));
        final Path outside = Files.createDirectory(directory.getParent().resolve("outside-" + System.nanoTime()));
        try {
            assertThrows(IOException.class, () -> IOTool.publishGeneration(directory, outside));
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void verifiedTransferRejectsHttpBeforeSendingRequests(@TempDir final Path directory) throws IOException {
        final AtomicInteger hits = new AtomicInteger();
        try (final LocalHttp server = LocalHttp.start("/binary", exchange -> {
            hits.incrementAndGet();
            LocalHttp.respond(exchange, "application/octet-stream", new byte[] { 1 }, 0);
        })) {
            final URI uri = server.uri("/binary");
            assertThrows(IOException.class, () -> IOTool.httpsText(uri, 128));
            final Path output = directory.resolve("binary");
            assertThrows(IOException.class, () -> IOTool.downloadVerified(uri, output, "0".repeat(64), 128));
            assertFalse(Files.exists(output));
            assertEquals(0, hits.get());
        }
    }

    @Test
    @DisplayName("readLimited returns the body when under the cap")
    void readLimitedUnderCap() throws IOException {
        final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), 100, -1L, 0L));
    }

    @Test
    @DisplayName("readLimited throws once the body exceeds the cap")
    void readLimitedOverCap() {
        final byte[] data = new byte[100];
        assertThrows(IOException.class, () -> IOTool.readLimited(new ByteArrayInputStream(data), 10, -1L, 0L));
    }

    @Test
    @DisplayName("readLimited never preallocates a hostile declared length")
    void readLimitedBoundsPreallocation() throws IOException {
        final byte[] data = "tiny".getBytes(StandardCharsets.UTF_8);
        // A 2 GiB DECLARATION WOULD EXHAUST THE TEST HEAP IF IT SIZED THE FIRST BUFFER
        assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), Long.MAX_VALUE, Integer.MAX_VALUE, 0L));
        assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), 16, Long.MAX_VALUE, 0L));
    }

    @Test
    @DisplayName("readLimited stops a slow stream at its total deadline")
    void readLimitedDeadline() {
        // EACH READ RETURNS ONE BYTE AFTER 20 MS: NEVER STUCK, BUT 200 BYTES WOULD TAKE FOUR SECONDS
        final InputStream slow = new InputStream() {
            private int left = 200;
            @Override
            public int read() throws IOException {
                if (this.left-- <= 0) return -1;
                try { Thread.sleep(20); }
                catch (final InterruptedException e) { throw new InterruptedIOException(); }
                return 'x';
            }
            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                final int value = this.read();
                if (value < 0) return -1;
                b[off] = (byte) value;
                return 1;
            }
        };
        final long start = System.nanoTime();
        final IOException failure = assertThrows(IOException.class, () -> IOTool.readLimited(slow, 1_024, -1L, 300L));
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(failure.getMessage().contains("deadline"), failure.getMessage());
        assertTrue(elapsedMs >= 300L, "Stopped before the deadline: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 4_000L, "Read the whole stream: " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("jarRead throws for a missing resource and web accepts only http and https")
    void jarReadAndWeb() throws IOException {
        assertThrows(FileNotFoundException.class, () -> IOTool.jarRead("missing/" + System.nanoTime() + ".json"));
        assertFalse(IOTool.jarRead("org/watermedia/tools/IOTool.class").isEmpty());
        assertTrue(IOTool.web(URI.create("http://a.test/")));
        assertTrue(IOTool.web(URI.create("HTTPS://a.test/")));
        assertFalse(IOTool.web(URI.create("file:///x")));
        assertFalse(IOTool.web(URI.create("jar:file:///x.zip!/y")));
        assertFalse(IOTool.web(URI.create("relative")));
        assertFalse(IOTool.web(null));
    }

    @Test
    @DisplayName("closeQuietly closes, swallows failures and accepts null")
    void closeQuietly() {
        final boolean[] closed = new boolean[1];
        IOTool.closeQuietly(() -> closed[0] = true);
        assertTrue(closed[0]);
        IOTool.closeQuietly(() -> { throw new IOException("ignored"); });
        IOTool.closeQuietly(null);
    }

    @Test
    @DisplayName("platformClassifier is never blank")
    void platformClassifier() {
        assertFalse(IOTool.platformClassifier().isBlank());
    }
}
