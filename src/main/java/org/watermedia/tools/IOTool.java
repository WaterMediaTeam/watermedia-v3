package org.watermedia.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class IOTool {
    public static final int BUFFER_SIZE = 1024 * 64; // 64 KB
    public static final String VERSION_FILE = "version.cfg";

    public static String platformClassifier() {
        final String os = System.getProperty("os.name").toLowerCase();
        final String arch = System.getProperty("os.arch").toLowerCase();

        final String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
        final String archSuffix = (arch.contains("aarch64") || arch.contains("arm64")) ? "-arm64" : "";

        return osName + archSuffix;
    }

    // NORMALISED OS TOKEN ("windows"/"macos"/"linux"), OR null IF UNSUPPORTED. UNLIKE platformClassifier
    // (WHICH NAMES ffmpeg ZIPS), THIS FEEDS PER-OS/ARCH ASSET RESOLUTION FOR DOWNLOADED NATIVE BINARIES.
    public static String os() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) return "linux";
        return null;
    }

    // NORMALISED ARCH TOKEN ("x86_64"/"aarch64"), OR null IF UNSUPPORTED
    public static String arch() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return null;
    }

    public static String read(final File path) {
        try {
            return Files.readString(path.toPath(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            return null;
        }
    }

    public static boolean copy(final File inFile, final File outFile) {
        try (final var in = Files.newInputStream(inFile.toPath())) {
            return write(in, outFile);
        } catch (final IOException e) {
            return false;
        }
    }

    public static boolean write(final InputStream in, final File outFile) {
        final byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;

        try (in; final var out = Files.newOutputStream(outFile.toPath())) {
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    public static int count(final File path) {
        int count = 0;
        if (path == null || !path.exists()) {
            return 0;
        }
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (final File file: files) {
                    count += count(file);
                }
            }
        } else {
            count++;
        }
        return count;
    }

    public static int delete(final File path) {
        int deletedCount = 0;
        if (path == null || !path.exists()) {
            return 0;
        }
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (final File file: files) {
                    deletedCount += delete(file);
                }
            }
        }
        if (path.delete()) {
            deletedCount++;
        }
        return deletedCount;
    }

    public static int deleteOnExit(final File path) {
        int deletedCount = 0;
        if (path == null || !path.exists()) {
            return 0;
        }
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (final File file: files) {
                    deletedCount += deleteOnExit(file);
                }
            }
        }
        path.deleteOnExit();
        return ++deletedCount;
    }

    public static boolean closeQuietly(final AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
                return true;
            } catch (final Exception ignored) {}
        }
        return false;
    }

    public static byte[] readLimited(final InputStream in, final long maxBytes, final long expectedBytes) throws IOException {
        final int initialCapacity = expectedBytes > 0L && expectedBytes <= Integer.MAX_VALUE
                ? (int) expectedBytes
                : BUFFER_SIZE;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(initialCapacity);
        final byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        while (true) {
            final int read = in.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > maxBytes) throw new IOException("Failed to read input: exceeds limit (" + total + " > " + maxBytes + " bytes)");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // MARKS file EXECUTABLE (POSIX); A NO-OP WHERE THE OS HAS NO SUCH BIT (E.G. WINDOWS)
    public static void makeExecutable(final Path file) {
        file.toFile().setExecutable(true);
    }

    // LOWERCASE HEX SHA-256 OF file
    public static String sha256(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final Exception e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        try (final InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return DataTool.hex(digest.digest());
    }

    // THROWS (AND DELETES file) IF ITS SHA-256 DOES NOT MATCH expected (CASE-INSENSITIVE HEX). THE DELETE
    // LETS A RETRY RE-DOWNLOAD INSTEAD OF TRUSTING A CORRUPT FILE.
    public static void verifySha256(final Path file, final String expected) throws IOException {
        final String actual = sha256(file);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(file);
            throw new IOException("SHA-256 mismatch for " + file.getFileName()
                    + " (expected " + expected + ", got " + actual + ")");
        }
    }

    public static boolean jarExtractZip(final String resource, final File output, final ClassLoader from) throws Exception {
        return jarExtractZip(jarOpenFile(resource, from), output);
    }

    // THROWS BECAUSE THIS IS A MORE COMPLEX TASK AND THE CALLER SHOULD HANDLE FAILURES
    public static boolean jarExtractZip(final InputStream is, final File output) throws Exception {
        try (final var in = new BufferedInputStream(is, BUFFER_SIZE); final var zip = new ZipInputStream(in)) {
            ZipEntry entry;
            final byte[] buffer = new byte[BUFFER_SIZE]; // THIS IS OUTSIDE THE LOOP TO AVOID MULTIPLE ALLOCATIONS PER ENTRY
            final String outputPrefix = output.getCanonicalPath() + File.separator; // ZIP SLIP GUARD BASELINE
            while ((entry = zip.getNextEntry()) != null) {
                final File outFile = new File(output, entry.getName());
                // ZIP SLIP: REJECT ENTRIES WHOSE RESOLVED PATH ESCAPES output
                if (!outFile.getCanonicalPath().startsWith(outputPrefix)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        return false;
                    }
                } else {
                    final File parentDir = outFile.getParentFile();
                    if (!parentDir.exists() && !parentDir.mkdirs()) {
                        return false;
                    }
                    try (final var out = Files.newOutputStream(outFile.toPath())) {
                        int len;
                        while ((len = zip.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                zip.closeEntry();
            }
            return true;
        }
    }

    // READ A FILE INSIDE A ZIP, WHICH IS INSIDE THE JAR RESOURCE AND RETURN AS STRING
    public static String jarReadZip(final String zipResource, final String fileInZip, final ClassLoader from) {
        return jarReadZip(jarOpenFile(zipResource, from), fileInZip);
    }

    public static String jarReadZip(final InputStream in, final String fileInZip) {
        try (in; final var zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(fileInZip)) {
                    final byte[] data = zip.readAllBytes();
                    return new String(data, StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
            return null;
        } catch (final Exception e) {
            return null;
        }
    }

    public static String jarVersion() {
        try {
            final String version = new Manifest(jarOpenFile("/META-INF/MANIFEST.MF", IOTool.class.getClassLoader())).getMainAttributes().getValue("Implementation-Version");
            return version == null ? "3.0.0-unknown" : version;
        } catch (final IOException e) {
            throw new RuntimeException("Failed to read self manifest", e);
        }
    }

    public static String jarRead(final String path) {
        try (final var in = jarOpenFile(path, IOTool.class.getClassLoader())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return null;
        }
    }

    public static InputStream jarOpenFile(final String name) {
        return jarOpenFile(name, IOTool.class.getClassLoader());
    }

    public static InputStream jarOpenFile(final String source, final ClassLoader classLoader) {
        var is = classLoader.getResourceAsStream(source);
        if (is == null && source.startsWith("/")) is = classLoader.getResourceAsStream(source.substring(1));
        return is;
    }

}
