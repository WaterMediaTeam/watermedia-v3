package org.watermedia.binaries;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.tukaani.xz.XZInputStream;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.IOTool;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.watermedia.binaries.WaterMediaBinaries.LOGGER;

/**
 * Resolves the platform-specific {@code rustypipe-botguard} executable, tracking the <em>latest</em>
 * upstream Codeberg release rather than a pinned version: the Forgejo/Gitea releases API is queried on
 * first use, and if the latest tag differs from the cached one the new archive is downloaded and the
 * single binary extracted from it ({@code .tar.xz} on Linux/macOS, {@code .zip} on Windows). A
 * {@code -Dwatermedia.botguard.binary=<path>} system property overrides resolution.
 *
 * <p>Resolution is lazy (first {@link #executable()} call) and memoised, so the API is hit at most once
 * per session and the download only occurs when a YouTube link actually needs a po_token. If the API is
 * unreachable but a binary is already cached, that cached binary is used (updates resume next session).
 * Shared download plumbing lives in {@link NetRequest}/{@link IOTool}; this class owns the per-platform asset
 * selection and the archive extraction.
 */
public final class BotGuardBinary {
    private static final Marker IT = MarkerManager.getMarker(BotGuardBinary.class.getSimpleName());
    private static final String OVERRIDE_PROPERTY = "watermedia.botguard.binary";
    private static final String API_LATEST = "https://codeberg.org/api/v1/repos/ThetaDev/rustypipe-botguard/releases/latest";
    private static final String SNAPSHOT_FILE = "bg_snapshot.bin";
    private static final String VERSION_FILE = "version"; // RECORDS THE CACHED RELEASE TAG NEXT TO THE BINARY

    // PER-PLATFORM ASSET NAME SUFFIX (THE LEADING "<name>-v<version>" PART MOVES WITH EACH RELEASE)
    private final String assetSuffix;
    private final boolean zip;
    private final String binaryName;
    private final String unsupportedReason;

    private volatile Path ready;

    public BotGuardBinary() {
        final String os = IOTool.os();
        final String arch = IOTool.arch();
        String suffix = null, binaryName = "rustypipe-botguard", reason = null;
        boolean zip = false;

        switch (os == null || arch == null ? "?" : os + "-" + arch) {
            case "windows-x86_64" -> {
                suffix = "-x86_64-pc-windows-msvc.zip";
                zip = true;
                binaryName = "rustypipe-botguard.exe";
            }
            case "macos-aarch64" -> suffix = "-aarch64-apple-darwin.tar.xz";
            case "macos-x86_64"  -> suffix = "-x86_64-apple-darwin.tar.xz";
            case "linux-aarch64" -> suffix = "-aarch64-unknown-linux-gnu.tar.xz";
            case "linux-x86_64"  -> suffix = "-x86_64-unknown-linux-gnu.tar.xz";
            default -> reason = "No rustypipe-botguard build for "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
        }

        this.assetSuffix = suffix;
        this.zip = zip;
        this.binaryName = binaryName;
        this.unsupportedReason = reason;
    }

    // PATH OF THE BOTGUARD VM SNAPSHOT CACHE (SPEEDS UP REPEAT INVOCATIONS). INVALIDATED BY
    // executable() ON EACH VERSION CHANGE, SINCE THE SNAPSHOT FORMAT IS TIED TO THE BINARY THAT WROTE IT.
    public Path snapshot() throws IOException {
        return WaterMediaBinaries.binaryDir(WaterMediaBinaries.BOTGUARD_ID).resolve(SNAPSHOT_FILE);
    }

    // RESOLVES THE EXECUTABLE, UPDATING TO THE LATEST RELEASE WHEN ONE IS AVAILABLE. THREAD-SAFE.
    public Path executable() throws IOException {
        Path p = this.ready;
        if (p != null) return p;
        synchronized (this) {
            if (this.ready != null) return this.ready;

            final String override = System.getProperty(OVERRIDE_PROPERTY);
            if (override != null && !override.isBlank()) {
                final Path o = Path.of(override);
                if (!Files.isRegularFile(o)) {
                    throw new IOException("watermedia.botguard.binary points to a missing file: " + o);
                }
                IOTool.makeExecutable(o);
                LOGGER.info(IT, "Using overridden rustypipe-botguard binary: {}", o);
                return this.ready = o;
            }

            if (this.unsupportedReason != null) {
                throw new IOException(this.unsupportedReason);
            }

            final Path dir = WaterMediaBinaries.binaryDir(WaterMediaBinaries.BOTGUARD_ID);
            final Path binary = dir.resolve(this.binaryName);
            final Path versionFile = dir.resolve(VERSION_FILE);
            final String cached = (Files.isRegularFile(binary) && Files.isRegularFile(versionFile))
                    ? Files.readString(versionFile).strip() : null;

            // RESOLVE THE LATEST RELEASE; ON A NETWORK/PARSE FAILURE FALL BACK TO THE CACHED BINARY
            final Release latest;
            try {
                latest = this.latest();
            } catch (final IOException e) {
                if (cached != null) {
                    LOGGER.warn(IT, "Could not check for rustypipe-botguard updates ({}); using cached {}", e.getMessage(), cached);
                    IOTool.makeExecutable(binary);
                    return this.ready = binary;
                }
                throw new IOException("Could not resolve a rustypipe-botguard release and none is cached: " + e.getMessage(), e);
            }

            // ALREADY ON THE LATEST → REUSE THE CACHED BINARY
            if (latest.version.equals(cached)) {
                LOGGER.info(IT, "rustypipe-botguard {} is up to date", latest.version);
                IOTool.makeExecutable(binary);
                return this.ready = binary;
            }

            LOGGER.info(IT, "Updating rustypipe-botguard {} -> {}", cached == null ? "(none)" : cached, latest.version);
            this.install(dir, binary, latest.url);
            Files.writeString(versionFile, latest.version);
            Files.deleteIfExists(dir.resolve(SNAPSHOT_FILE)); // STALE: SNAPSHOT FORMAT IS TIED TO THE BINARY
            IOTool.makeExecutable(binary);
            LOGGER.info(IT, "rustypipe-botguard {} ready at {}", latest.version, binary);
            return this.ready = binary;
        }
    }

    // QUERIES CODEBERG (FORGEJO/GITEA API) FOR THE LATEST RELEASE AND RESOLVES THIS PLATFORM'S ARCHIVE.
    // ASSETS ARE MATCHED BY SUFFIX BECAUSE THE LEADING "<name>-v<version>" PART MOVES WITH EACH RELEASE.
    private Release latest() throws IOException {
        final JsonObject release;
        try (final NetRequest req = NetRequest.create(API_LATEST).accept(NetRequest.ACCEPT_JSON).send()) {
            if (req.statusCode() != 200) {
                throw new IOException("GET failed (HTTP " + req.statusCode() + "): " + API_LATEST);
            }
            final JsonElement body = req.json(); // NULL WHEN THE RESPONSE IS NOT application/json
            release = body == null ? null : body.getAsJsonObject();
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Unparseable rustypipe-botguard release metadata", e);
        }
        if (release == null) {
            throw new IOException("Non-JSON rustypipe-botguard release metadata: " + API_LATEST);
        }
        final String version = release.get("tag_name").getAsString();
        final JsonArray assets = release.getAsJsonArray("assets");
        if (assets == null) throw new IOException("rustypipe-botguard release " + version + " lists no assets");

        for (final JsonElement el : assets) {
            final JsonObject a = el.getAsJsonObject();
            if (a.get("name").getAsString().endsWith(this.assetSuffix)) {
                return new Release(version, a.get("browser_download_url").getAsString());
            }
        }
        throw new IOException("rustypipe-botguard release " + version + " has no asset ending with '" + this.assetSuffix + "'");
    }

    // DOWNLOADS THE RELEASE ARCHIVE AND EXTRACTS THE SINGLE BINARY IT CONTAINS INTO A UNIQUE TEMP, THEN
    // ATOMIC-MOVES IT INTO PLACE, SO A CRASH (OR A SECOND JVM SHARING THE TEMP DIR) CAN NEVER LEAVE A
    // PARTIAL BINARY AT THE CANONICAL PATH. THE ARCHIVE AND ANY STAGING ARTEFACTS ARE ALWAYS CLEANED UP.
    private void install(final Path dir, final Path binary, final String url) throws IOException {
        Files.createDirectories(dir);
        final Path archive = Files.createTempFile(dir, "rustypipe-botguard", this.zip ? ".zip" : ".tar.xz");
        try {
            try (final NetRequest req = NetRequest.create(url).send()) {
                req.download(archive);
            }

            if (this.zip) {
                // THE WINDOWS ZIP CARRIES rustypipe-botguard.exe AT ITS ROOT — EXTRACT TO A TEMP DIR,
                // THEN MOVE THAT ONE FILE OUT
                final Path stageDir = Files.createTempDirectory(dir, "rpbg-");
                try {
                    try (final InputStream in = Files.newInputStream(archive)) {
                        IOTool.jarExtractZip(in, stageDir.toFile());
                    } catch (final IOException e) {
                        throw e;
                    } catch (final Exception e) {
                        throw new IOException("Failed to extract " + archive.getFileName(), e);
                    }
                    final Path extracted = stageDir.resolve(this.binaryName);
                    if (!Files.isRegularFile(extracted)) {
                        throw new IOException("Archive did not contain " + this.binaryName);
                    }
                    IOTool.move(extracted, binary);
                } finally {
                    IOTool.delete(stageDir.toFile()); // RECURSIVELY REMOVE THE STAGING DIR
                }
            } else {
                // THE .tar.xz CARRIES A SINGLE FILE — EXTRACT IT TO A TEMP, THEN MOVE INTO PLACE
                final Path part = Files.createTempFile(dir, "rustypipe-botguard", ".part");
                try {
                    extractTarXz(archive, part);
                    IOTool.move(part, binary);
                } finally {
                    Files.deleteIfExists(part);
                }
            }
        } finally {
            Files.deleteIfExists(archive);
        }

        if (!Files.isRegularFile(binary)) {
            throw new IOException("Extraction did not produce the expected binary: " + binary);
        }
    }

    // EXTRACTS THE FIRST REGULAR FILE FROM A SINGLE-ENTRY .tar.xz (THE RUSTYPIPE-BOTGUARD ARCHIVES SHIP
    // EXACTLY ONE FILE). A FULL TAR LIBRARY WOULD BE OVERKILL HERE, SO WE PARSE THE 512-BYTE USTAR HEADER
    // DIRECTLY: BYTES [124,136) HOLD THE OCTAL FILE SIZE AND BYTE 156 THE TYPEFLAG.
    private static void extractTarXz(final Path archive, final Path destination) throws IOException {
        try (final InputStream fin = Files.newInputStream(archive);
             final XZInputStream xz = new XZInputStream(new BufferedInputStream(fin))) {
            final byte[] header = new byte[512];
            while (readFully(xz, header) == 512) {
                if (isAllZero(header)) break; // TWO ZERO BLOCKS MARK THE END OF THE ARCHIVE
                final long size = parseOctal(header, 124, 12);
                final char type = (char) (header[156] & 0xFF);
                if ((type == '0' || type == '\0') && size > 0) {
                    try (final OutputStream out = Files.newOutputStream(destination)) {
                        copyExactly(xz, out, size);
                    }
                    return; // FOUND OUR BINARY
                }
                skipExactly(xz, size + padding(size)); // SKIP NON-FILE ENTRY AND ITS 512-BYTE PADDING
            }
            throw new IOException("No regular file inside " + archive.getFileName());
        }
    }

    private static long parseOctal(final byte[] header, final int offset, final int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            final int c = header[i] & 0xFF;
            if (c == 0 || c == ' ') continue; // FIELD IS NUL/SPACE PADDED
            if (c < '0' || c > '7') break;
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static long padding(final long size) {
        final long remainder = size % 512;
        return remainder == 0 ? 0 : 512 - remainder;
    }

    private static boolean isAllZero(final byte[] block) {
        for (final byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static int readFully(final InputStream in, final byte[] buffer) throws IOException {
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        return total;
    }

    private static void copyExactly(final InputStream in, final OutputStream out, final long count) throws IOException {
        final byte[] buffer = new byte[1 << 16];
        long remaining = count;
        while (remaining > 0) {
            final int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) throw new IOException("Truncated tar entry");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(final InputStream in, final long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) return; // EOF
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    // A RESOLVED RELEASE ASSET: TAG AND ARCHIVE DOWNLOAD URL
    private record Release(String version, String url) {}
}
