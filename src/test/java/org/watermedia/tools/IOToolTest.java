package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.test.support.LocalHttp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
        assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), 100, -1L));
    }

    @Test
    @DisplayName("readLimited throws once the body exceeds the cap")
    void readLimitedOverCap() {
        final byte[] data = new byte[100];
        assertThrows(IOException.class, () -> IOTool.readLimited(new ByteArrayInputStream(data), 10, -1L));
    }

    @Test
    @DisplayName("platformClassifier is never blank")
    void platformClassifier() {
        assertFalse(IOTool.platformClassifier().isBlank());
    }
}
