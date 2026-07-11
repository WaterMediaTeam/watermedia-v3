package org.watermedia.binaries;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.watermedia.binaries.WaterMediaBinaries.LOGGER;

/**
 * Resolves the platform-specific yt-dlp <em>frozen</em> executable (bundles Python, no interpreter
 * needed), tracking the <em>latest</em> official yt-dlp release rather than a pinned version: the GitHub
 * releases API is queried on first use, and if the latest tag differs from the cached one the new binary
 * is downloaded (and verified against the release's {@code SHA2-256SUMS}). A
 * {@code -Dwatermedia.ytdlp.binary=<path>} system property overrides resolution.
 *
 * <p>Resolution is lazy (on first {@link #executable()}) and memoised, so the API is hit at most once per
 * session and the ~20&nbsp;MB download only happens when a link actually needs extraction. If the API is
 * unreachable but a binary is already cached, that cached binary is used (updates resume next session).
 */
public final class YtDlpBinary {
    private static final Marker IT = MarkerManager.getMarker(YtDlpBinary.class.getSimpleName());
    private static final String OVERRIDE_PROPERTY = "watermedia.ytdlp.binary";
    private static final String API_LATEST = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    private static final String CHECKSUMS_ASSET = "SHA2-256SUMS";
    private static final String VERSION_FILE = "version"; // RECORDS THE CACHED RELEASE TAG NEXT TO THE BINARY

    // PER-PLATFORM ASSET NAME (STABLE ACROSS RELEASES; ONLY THE RELEASE TAG MOVES)
    private final String binaryName;
    private final String unsupportedReason;

    private volatile Path ready;

    public YtDlpBinary() {
        final String os = IOTool.os();
        final String arch = IOTool.arch();
        // MACOS SHIPS A SINGLE UNIVERSAL BINARY; OTHER OSES ARE PER-ARCH
        final String key = (os == null || arch == null) ? null : (os.equals("macos") ? "macos" : os + "-" + arch);
        final String name = key == null ? null : switch (key) {
            case "windows-x86_64"  -> "yt-dlp.exe";
            case "windows-aarch64" -> "yt-dlp_arm64.exe";
            case "linux-x86_64"    -> "yt-dlp_linux";
            case "linux-aarch64"   -> "yt-dlp_linux_aarch64";
            case "macos"           -> "yt-dlp_macos";
            default                -> null;
        };
        this.binaryName = name;
        this.unsupportedReason = name != null ? null
                : "No yt-dlp build for " + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
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
                    throw new IOException("watermedia.ytdlp.binary points to a missing file: " + o);
                }
                IOTool.makeExecutable(o);
                LOGGER.info(IT, "Using overridden yt-dlp binary: {}", o);
                return this.ready = o;
            }

            if (this.unsupportedReason != null) {
                throw new IOException(this.unsupportedReason);
            }

            final Path dir = WaterMediaBinaries.binaryDir(WaterMediaBinaries.YTDLP_ID);
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
                    LOGGER.warn(IT, "Could not check for yt-dlp updates ({}); using cached {}", e.getMessage(), cached);
                    IOTool.makeExecutable(binary);
                    return this.ready = binary;
                }
                throw new IOException("Could not resolve a yt-dlp release and none is cached: " + e.getMessage(), e);
            }

            // ALREADY ON THE LATEST → REUSE THE CACHED BINARY
            if (latest.version.equals(cached)) {
                LOGGER.info(IT, "yt-dlp {} is up to date", latest.version);
                IOTool.makeExecutable(binary);
                return this.ready = binary;
            }

            // DOWNLOAD+VERIFY INTO A UNIQUE TEMP, THEN ATOMIC-MOVE INTO PLACE, SO A CRASH (OR A SECOND JVM
            // SHARING THE TEMP DIR) CAN NEVER LEAVE A TRUNCATED BINARY AT THE CANONICAL PATH
            LOGGER.info(IT, "Updating yt-dlp {} -> {}", cached == null ? "(none)" : cached, latest.version);
            Files.createDirectories(dir);
            final Path part = Files.createTempFile(dir, this.binaryName, ".part");
            try {
                try (final NetRequest req = NetRequest.create(latest.url).send()) {
                    req.download(part);
                }
                if (latest.sha256 != null) IOTool.verifySha256(part, latest.sha256);
                IOTool.move(part, binary);
                Files.writeString(versionFile, latest.version);
            } finally {
                Files.deleteIfExists(part); // NO-OP ONCE MOVED; CLEANS UP ON FAILURE
            }
            IOTool.makeExecutable(binary);
            LOGGER.info(IT, "yt-dlp {} ready at {}", latest.version, binary);
            return this.ready = binary;
        }
    }

    // QUERIES GITHUB FOR THE LATEST yt-dlp RELEASE AND RESOLVES THIS PLATFORM'S BINARY ASSET + ITS SHA-256
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
            throw new IOException("Unparseable yt-dlp release metadata", e);
        }
        if (release == null) {
            throw new IOException("Non-JSON yt-dlp release metadata: " + API_LATEST);
        }
        final String version = release.get("tag_name").getAsString();
        final JsonArray assets = release.getAsJsonArray("assets");
        if (assets == null) throw new IOException("yt-dlp release " + version + " lists no assets");

        String url = null, sumsUrl = null;
        for (final JsonElement el : assets) {
            final JsonObject a = el.getAsJsonObject();
            final String name = a.get("name").getAsString();
            if (name.equals(this.binaryName)) url = a.get("browser_download_url").getAsString();
            else if (name.equals(CHECKSUMS_ASSET)) sumsUrl = a.get("browser_download_url").getAsString();
        }
        if (url == null) throw new IOException("yt-dlp release " + version + " has no asset '" + this.binaryName + "'");

        return new Release(version, url, sumsUrl == null ? null : sha256For(sumsUrl, this.binaryName));
    }

    // PARSES A "<hash>  [*]<filename>" CHECKSUMS FILE AND RETURNS THE HASH FOR target, OR null IF ABSENT
    private static String sha256For(final String sumsUrl, final String target) throws IOException {
        final String sums;
        try (final NetRequest req = NetRequest.create(sumsUrl).send()) {
            if (req.statusCode() != 200) {
                throw new IOException("GET failed (HTTP " + req.statusCode() + "): " + sumsUrl);
            }
            sums = req.readAllAsString();
        }
        for (final String line : sums.split("\\R")) {
            final String[] parts = line.strip().split("\\s+");
            if (parts.length < 2) continue;
            String file = parts[parts.length - 1];
            if (file.startsWith("*")) file = file.substring(1); // BINARY-MODE MARKER
            if (file.equals(target)) return parts[0];
        }
        return null; // NOT LISTED — THE DOWNLOAD STILL GETS NetRequest'S TRUNCATION CHECK + HTTPS
    }

    // A RESOLVED RELEASE ASSET: TAG, DOWNLOAD URL, AND ITS EXPECTED SHA-256 (NULL WHEN UNLISTED)
    private record Release(String version, String url, String sha256) {}
}
