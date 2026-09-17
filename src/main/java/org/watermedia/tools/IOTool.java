package org.watermedia.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class IOTool {
    public static final int BUFFER_SIZE = 1024 * 64; // 64 KB
    public static final String VERSION_FILE = "version.cfg";

    public static String platformClassifier() {
        final String system = os();
        final String cpu = arch();
        return system == null || cpu == null ? "unsupported" : system + (cpu.equals("aarch64") ? "-arm64" : "");
    }

    // NORMALISED OS TOKEN ("windows"/"macos"/"linux"), OR null IF UNSUPPORTED. UNLIKE platformClassifier
    // (WHICH NAMES ffmpeg ZIPS), THIS FEEDS PER-OS/ARCH ASSET RESOLUTION FOR DOWNLOADED NATIVE BINARIES.
    public static String os() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        return null;
    }

    // NORMALISED ARCH TOKEN ("x86_64"/"aarch64"), OR null IF UNSUPPORTED
    public static String arch() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return null;
    }

    /** Stable operating-system and CPU identifier, or unsupported when either is unknown. */
    public static String platform() {
        final String system = os();
        final String cpu = arch();
        return system == null || cpu == null ? "unsupported" : system + "-" + cpu;
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

    public static int deleteOnExit(final File path) {
        if (path == null || !path.exists()) {
            return 0;
        }
        // REGISTER THE PARENT BEFORE ITS CHILDREN: deleteOnExit RUNS IN REVERSE REGISTRATION
        // ORDER, SO DIRECTORIES MUST BE REGISTERED FIRST TO BE DELETED LAST (ONCE EMPTY)
        path.deleteOnExit();
        int registered = 1;
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (final File file: files) {
                    registered += deleteOnExit(file);
                }
            }
        }
        return registered;
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
        final int initialCapacity = expectedBytes > 0L && expectedBytes <= Integer.MAX_VALUE ? (int) expectedBytes : BUFFER_SIZE;
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

    /** Reads bounded UTF-8 text through HTTPS, including every redirect. */
    public static String httpsText(final URI uri, final long maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("HTTPS text limit must be positive");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        transferHttps(uri, output, maxBytes);
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Downloads a bounded HTTPS file and verifies its publisher-provided SHA-256. */
    public static void downloadVerified(final URI uri, final Path destination, final String expected, final long maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("HTTPS download limit must be positive");
        final String digest = sha256Digest(expected);
        boolean created = false;
        try {
            try (final OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                created = true;
                transferHttps(uri, output, maxBytes);
            }
            verifySha256(destination, digest);
        } catch (final IOException failure) {
            if (created) {
                try { Files.deleteIfExists(destination); }
                catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    // CHECK HTTPS ON EVERY HOP SO A REDIRECT CANNOT DOWNGRADE A VERIFIED DOWNLOAD.
    private static void transferHttps(final URI origin, final OutputStream output, final long limit) throws IOException {
        URI uri = origin;
        final long deadline = System.nanoTime() + 180_000_000_000L;
        for (int redirects = 0; redirects <= 8; redirects++) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IOException("Verified downloads require HTTPS without credentials: " + uri);
            if (System.nanoTime() > deadline) throw new IOException("HTTPS transfer deadline exceeded: " + uri);
            final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", "WaterMedia");
            try {
                final int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    final String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without a location: " + uri);
                    try { uri = uri.resolve(location); }
                    catch (final IllegalArgumentException invalid) { throw new IOException("Invalid HTTPS redirect: " + uri, invalid); }
                    continue;
                }
                if (status != 200) throw new IOException("Download returned HTTP " + status + ": " + uri);
                final long expected = connection.getContentLengthLong();
                if (expected > limit) throw new IOException("HTTPS response exceeds its size limit: " + uri);
                long total = 0;
                try (final InputStream input = connection.getInputStream()) {
                    final byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > limit || System.nanoTime() > deadline)
                            throw new IOException("HTTPS transfer budget exceeded: " + uri);
                        output.write(buffer, 0, count);
                    }
                }
                if (expected >= 0 && total != expected) throw new IOException("Truncated HTTPS download: " + uri);
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many HTTPS redirects: " + origin);
    }

    public static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Publishes an installed direct child through an atomically replaced pointer. */
    public static void publishGeneration(final Path directory, final Path installation) throws IOException {
        final Path base = directory.toAbsolutePath().normalize();
        final Path child = installation.toAbsolutePath().normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || child.getFileName() == null)
            throw new IOException("Installation storage is missing or is a symbolic link: " + base);
        final String name = child.getFileName().toString();
        if (!base.equals(child.getParent()) || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Installation is not a direct, regular child of " + base);
        final Path pointer = Files.createTempFile(base, ".current-", ".tmp");
        try {
            Files.writeString(pointer, name, StandardCharsets.UTF_8);
            Files.move(pointer, base.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pointer);
        }
    }

    /** Resolves a direct installed generation without following a pointer or target symlink. */
    public static Path currentGeneration(final Path directory) throws IOException {
        final Path base = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return null;
        final Path pointer = base.resolve("current");
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) return null;
        final String name = Files.readString(pointer, StandardCharsets.UTF_8);
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return null;
        final Path child = base.resolve(name);
        return Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ? child : null;
    }

    /** Deletes a tree without traversing symbolic links. */
    public static void deleteTree(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (final var paths = Files.walk(directory)) {
            for (final Path path: paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** Requires a regular executable file and sets its owner execute bit outside Windows. */
    public static void makeExecutable(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Executable is missing or is a symbolic link: " + file);
        if (!"windows".equals(os()) && !file.toFile().setExecutable(true, true))
            throw new IOException("Cannot grant executable permission: " + file);
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

    /** Validates and normalizes a SHA-256 digest from a manifest or release index. */
    public static String sha256Digest(final String value) throws IOException {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) throw new IOException("Missing or invalid SHA-256 digest");
        return value.toLowerCase(Locale.ROOT);
    }

    /** Verifies a regular file without following links or modifying it after a mismatch. */
    public static void verifySha256(final Path file, final String expected) throws IOException {
        final String digest = sha256Digest(expected);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !sha256(file).equals(digest))
            throw new IOException("File SHA-256 mismatch: " + file.getFileName());
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

    public static String jarReadZip(final InputStream in, final String fileInZip) {
        if (in == null) return null; // MISSING RESOURCE: LET THE CALLER REPORT IT INSTEAD OF SWALLOWING AN NPE
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
        // MANIFEST OF THE JAR THAT LOADED THIS CLASS: A PLAIN CLASSLOADER LOOKUP WOULD RETURN THE FIRST
        // MANIFEST OF THE WHOLE CLASSPATH (E.G. LOG4J'S) AND REPORT A FOREIGN VERSION. CLASSES-DIR RUNS
        // (IDE) SHIP NO MANIFEST AND FALL TO THE DEV PLACEHOLDER.
        final String cls = IOTool.class.getName().replace('.', '/') + ".class";
        final URL url = IOTool.class.getClassLoader().getResource(cls);
        if (url != null) {
            final String base = url.toString();
            try (final var in = URI.create(base.substring(0, base.length() - cls.length()) + "META-INF/MANIFEST.MF").toURL().openStream()) {
                final String version = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
                if (version != null) return version;
            } catch (final Exception ignored) {}
        }
        return "3.0.0-unknown";
    }

    /** Combines cleanup failures without hiding a fatal VM error behind an ordinary exception. */
    public static Throwable mergeFailure(final Throwable previous, final Throwable next) {
        Objects.requireNonNull(next, "next");
        if (previous == null || previous == next) return next;
        final boolean previousFatal = previous instanceof VirtualMachineError || previous instanceof ThreadDeath;
        final boolean nextFatal = next instanceof VirtualMachineError || next instanceof ThreadDeath;
        if ((nextFatal && !previousFatal) || (next instanceof Error && !(previous instanceof Error))) {
            next.addSuppressed(previous);
            return next;
        }
        previous.addSuppressed(next);
        return previous;
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
