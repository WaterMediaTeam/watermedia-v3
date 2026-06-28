package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.binaries.YtDlpBinary;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.tools.JsonTool.bool;
import static org.watermedia.tools.JsonTool.dbl;
import static org.watermedia.tools.JsonTool.intOr;
import static org.watermedia.tools.JsonTool.intOrNull;
import static org.watermedia.tools.JsonTool.str;
import static org.watermedia.tools.JsonTool.uri;

/**
 * Generic yt-dlp platform: a single handler for every site whose extraction needs nothing beyond
 * yt-dlp's defaults (SoundCloud, Facebook, Instagram, Newgrounds, ...). Supporting a new site is a
 * one-line change: add its host to {@link #HOSTS}.
 *
 * <p>This class also <em>is</em> the shared yt-dlp engine: it drives the bundled yt-dlp binary
 * out-of-process ({@link #info}) and maps its {@code --dump-single-json} output into WaterMedia
 * {@link DataSource}s ({@link #extract}). {@link YouTubePlatform} extends it to reuse that engine while
 * adding the YouTube-only BotGuard po_token retry — YouTube keeps its own handler because yt-dlp emits
 * the same JSON shape for every site, but only YouTube needs the bot gate bypass.
 *
 * <p>Out-of-process (like ffmpeg) keeps any failure inside yt-dlp from touching the JVM. yt-dlp already
 * deciphers the signature and throttling (n) parameters, so each {@code formats[].url} is playable
 * directly — but it is bound to the {@code http_headers} (notably {@code User-Agent}) yt-dlp used, which
 * are copied into the source's {@link RequestHeaders} so the player sends them. The yt-dlp binary itself
 * is provisioned lazily by the {@code binaries} module ({@link YtDlpBinary}).
 */
public class YtDlpPlatform implements IPlatform {
    public static final String NAME = "yt-dlp";
    // HOST SUFFIXES, MATCHED CASE-INSENSITIVELY WITH endsWith SO SUBDOMAINS (www./m./web.) ARE COVERED
    private static final String[] HOSTS = {
            "soundcloud.com", "snd.sc",     // SOUNDCLOUD
            "facebook.com", "fb.watch",     // FACEBOOK
            "instagram.com", "instagr.am",  // INSTAGRAM
            "newgrounds.com",               // NEWGROUNDS
    };

    private static final long PROCESS_TIMEOUT_SECONDS = 120; // SINGLE VIDEO ~SECONDS; PLAYLISTS CAN BE SLOWER
    private static final long LIVE_TTL_SECONDS = 30 * 60;
    private static final long FALLBACK_TTL_SECONDS = 4 * 3600;
    private static final Pattern RESOLUTION = Pattern.compile("(\\d+)p");
    private static final Pattern HEIGHT_IN_RES = Pattern.compile("\\d+x(\\d+)");

    private final YtDlpBinary binary = new YtDlpBinary();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // NONE OF OUR HOSTS → LET PlatformAPI KEEP PROBING OTHER HANDLERS (HOSTS ARE CASE-INSENSITIVE)
        final String host = uri.getHost();
        if (host == null || !DataTool.endsWith(host.toLowerCase(Locale.ROOT), HOSTS)) {
            return null;
        }
        final JsonObject info;
        try {
            info = this.info(uri.toString(), List.of());
        } catch (final IOException e) {
            throw new PlatformException(YtDlpPlatform.class, "yt-dlp could not resolve " + uri + ": " + e.getMessage(), e);
        }
        return this.extract(info); // EXTRACTION EXCEPTIONS (Mature/Platform) PROPAGATE AS-IS
    }

    // ====================================================================================================
    // yt-dlp ENGINE — SHARED WITH YouTubePlatform
    // ====================================================================================================

    /**
     * Runs the yt-dlp binary and returns its parsed {@code --dump-single-json} output.
     *
     * @param url       the media URL (or a {@code ytsearchN:query} pseudo-URL)
     * @param extraArgs additional yt-dlp args (e.g. {@code --no-playlist}, {@code --extractor-args ...})
     * @throws IOException if the binary cannot be provisioned, times out, exits non-zero, or emits
     *                     unparseable JSON — the message carries yt-dlp's last stderr line
     */
    protected JsonObject info(final String url, final List<String> extraArgs) throws IOException {
        final Path exe;
        try {
            exe = this.binary.executable();
        } catch (final IOException e) {
            throw new IOException("Failed to provision yt-dlp: " + e.getMessage(), e);
        }

        final List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.add("-J");
        command.add("--no-warnings");
        command.add("--ignore-config");          // HERMETIC: IGNORE THE USER'S yt-dlp.conf
        command.add("--cache-dir");
        command.add(WaterMedia.tmp().resolve(WaterMediaBinaries.YTDLP_ID).resolve("cache").toString());
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        if (extraArgs != null) {
            command.addAll(extraArgs);
        }
        command.add(url);

        final String stdout = runProcess(command, PROCESS_TIMEOUT_SECONDS, "yt-dlp");
        try {
            return JsonParser.parseString(stdout).getAsJsonObject();
        } catch (final Exception e) {
            throw new IOException("yt-dlp produced unparseable JSON", e);
        }
    }

    /**
     * Runs {@code command} as a child process and returns its stdout. Both pipes are drained on separate
     * threads <em>before</em> waiting, so {@code timeoutSeconds} actually bounds the child: reading stdout
     * on the calling thread would block until the child closed it, reaching the wait only afterwards — and
     * a stuck child would then wedge a {@link PlatformAPI} search thread. On timeout/interrupt the child
     * is killed, which closes the pipes and lets the drains reach EOF. After a normal exit the pipes EOF
     * too, so the drains are joined unbounded (no truncation) and each buffer is read after its
     * {@code join()}, making the handoff race-free.
     *
     * @throws IOException carrying the child's last stderr line on a non-zero exit, timeout, or interrupt
     */
    protected static String runProcess(final List<String> command, final long timeoutSeconds, final String tool) throws IOException {
        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (final IOException e) {
            throw new IOException("Could not start " + tool, e);
        }

        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        final Thread outDrain = new Thread(() -> out.append(readAll(process.getInputStream())), tool + "-stdout");
        final Thread errDrain = new Thread(() -> err.append(readAll(process.getErrorStream())), tool + "-stderr");
        outDrain.setDaemon(true);
        errDrain.setDaemon(true);
        outDrain.start();
        errDrain.start();

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(tool + " timed out after " + timeoutSeconds + "s");
            }
            // CHILD HAS EXITED, SO ITS STDOUT/STDERR ARE AT EOF — THESE JOINS RETURN ONCE EACH DRAIN
            // FINISHES READING (UNBOUNDED ON PURPOSE: A 1s CAP TRUNCATED LARGE OUTPUTS LIKE BIG PLAYLISTS).
            outDrain.join();
            errDrain.join();
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + tool, e);
        }

        final int exit = process.exitValue();
        if (exit != 0) {
            final String e = err.toString().trim();
            throw new IOException(tool + " exited " + exit + ": "
                    + (e.isEmpty() ? "(no stderr)" : e.lines().reduce((a, b) -> b).orElse(e)));
        }
        return out.toString();
    }

    private static String readAll(final InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return "";
        }
    }

    // ====================================================================================================
    // JSON → PlatformData EXTRACTION — SHARED WITH YouTubePlatform
    // ====================================================================================================

    /**
     * Builds the {@link PlatformData} from yt-dlp's JSON: a single media object becomes one source, a
     * playlist ({@code entries[]}) becomes several. Resolution exceptions reference {@link #getClass()},
     * so the actual handler (yt-dlp or YouTube) names itself.
     */
    protected PlatformData extract(final JsonObject info) throws Exception {
        final JsonArray entries = info.getAsJsonArray("entries");
        if (entries == null) {
            final Result r = this.single(info);
            return new PlatformData(r.expires(), r.source());
        }

        final List<DataSource> sources = new ArrayList<>();
        Instant earliest = null;
        for (final JsonElement e : entries) {
            if (e == null || e.isJsonNull()) continue; // yt-dlp NULLS OUT UNAVAILABLE PLAYLIST ITEMS
            try {
                final Result r = this.single(e.getAsJsonObject());
                sources.add(r.source());
                if (earliest == null || (r.expires() != null && r.expires().isBefore(earliest))) {
                    earliest = r.expires();
                }
            } catch (final Exception ignored) {
                // SKIP UNRESOLVABLE PLAYLIST ITEMS
            }
        }
        if (sources.isEmpty()) {
            throw new PlatformException(this.getClass(), "No playable entries in playlist");
        }
        return new PlatformData(earliest, sources.toArray(DataSource[]::new));
    }

    private Result single(final JsonObject media) throws Exception {
        // RESPECT THE MATURE-CONTENT GATE (yt-dlp REPORTS age_limit; >=18 IS AGE-RESTRICTED)
        if (intOr(media, "age_limit", 0) >= 18 && !WaterMediaConfig.platforms.allowMatureContent) {
            throw new MatureContentException(this.getClass(), "Age-restricted: " + str(media, "webpage_url"));
        }

        final JsonArray formats = media.getAsJsonArray("formats");
        if (formats == null || formats.isEmpty()) {
            throw new PlatformException(this.getClass(), "No formats for " + str(media, "webpage_url"));
        }

        final URI sourceUri = uri(str(media, "webpage_url"));
        final boolean live = bool(media, "is_live") || "is_live".equals(str(media, "live_status"));
        final URI thumbnail = uri(str(media, "thumbnail"));
        final Metadata metadata = metadata(media);
        final List<DataSlave> subtitles = subtitles(media);

        // SPLIT FORMATS: VIDEO-ONLY (acodec none), MUXED (both), AUDIO-ONLY (vcodec none). SKIP STORYBOARDS.
        final List<DataQuality> variants = new ArrayList<>();
        final Set<Integer> seenHeights = new HashSet<>();
        JsonObject headerSource = null;
        JsonObject bestAudio = null;
        int bestAudioRate = -1;

        // VIDEO-ONLY FIRST (BEST QUALITY, AUDIO CARRIED BY A SLAVE), THEN MUXED FILLS REMAINING TIERS
        for (int pass = 0; pass < 2; pass++) {
            for (final JsonElement fe : formats) {
                final JsonObject f = fe.getAsJsonObject();
                if (!playable(f)) continue;
                final boolean hasVideo = !"none".equals(str(f, "vcodec"));
                final boolean hasAudio = !"none".equals(str(f, "acodec"));
                if (!hasVideo) {
                    if (pass == 0) {
                        final int rate = audioRate(f);
                        if (rate > bestAudioRate) { bestAudio = f; bestAudioRate = rate; }
                    }
                    continue;
                }
                final boolean videoOnly = !hasAudio;
                if ((pass == 0) != videoOnly) continue; // PASS 0 = VIDEO-ONLY, PASS 1 = MUXED
                final int height = heightOf(f);
                if (height > 0 && !seenHeights.add(height)) continue;
                final URI url = uri(str(f, "url"));
                if (url == null) continue; // MALFORMED FORMAT URL — SKIP THIS VARIANT
                final int width = intOr(f, "width", height > 0 ? height * 16 / 9 : 0);
                variants.add(new DataQuality(url, width, height));
                if (headerSource == null) headerSource = f;
            }
        }

        final RequestHeaders headers = headers(headerSource != null ? headerSource : bestAudio, sourceUri);

        if (!variants.isEmpty()) {
            final URI bestAudioUri = bestAudio == null ? null : uri(str(bestAudio, "url"));
            final List<DataSlave> audioSlaves = bestAudioUri == null ? null
                    : List.of(new DataSlave(null, null, bestAudioUri));
            final DataSource source = new DataSource(MediaType.VIDEO, thumbnail, metadata, headers,
                    variants.toArray(DataQuality[]::new), audioSlaves, subtitles);
            return new Result(expiry(variants.get(0).uri(), live), source);
        }

        // AUDIO-ONLY (SOUNDCLOUD, YOUTUBE MUSIC)
        if (bestAudio != null) {
            final URI audio = uri(str(bestAudio, "url"));
            if (audio != null) {
                final DataSource source = new DataSource(MediaType.AUDIO, thumbnail, metadata, headers,
                        new DataQuality[] { new DataQuality(audio, 0, 0) }, null, subtitles);
                return new Result(expiry(audio, live), source);
            }
        }

        throw new PlatformException(this.getClass(), "No usable streams for " + str(media, "webpage_url"));
    }

    // A FORMAT IS PLAYABLE IF IT CARRIES MEDIA (NOT A STORYBOARD/THUMBNAIL TRACK) AND HAS A URL
    private static boolean playable(final JsonObject f) {
        if (str(f, "url") == null) return false;
        if ("mhtml".equals(str(f, "protocol"))) return false; // STORYBOARDS
        final boolean hasVideo = !"none".equals(str(f, "vcodec"));
        final boolean hasAudio = !"none".equals(str(f, "acodec"));
        if (f.get("vcodec") == null && f.get("acodec") == null) return false;
        return hasVideo || hasAudio;
    }

    private static int audioRate(final JsonObject f) {
        final double abr = dbl(f, "abr");
        return (int) Math.round(abr > 0 ? abr : dbl(f, "tbr"));
    }

    private static int heightOf(final JsonObject f) {
        final Integer h = intOrNull(f, "height");
        if (h != null) return h;
        final String res = str(f, "resolution");
        if (res != null) {
            final Matcher m = HEIGHT_IN_RES.matcher(res);
            if (m.find()) return parsePixels(m.group(1));
        }
        final String note = str(f, "format_note");
        if (note != null) {
            final Matcher m = RESOLUTION.matcher(note);
            if (m.find()) return parsePixels(m.group(1));
        }
        return 0;
    }

    // PARSES A REGEX-CAPTURED RESOLUTION TOKEN; AN OUT-OF-int-RANGE RUN OF DIGITS IS GARBAGE → 0 (UNKNOWN)
    private static int parsePixels(final String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    // COPIES yt-dlp'S PER-FORMAT http_headers (User-Agent ETC.) INTO RequestHeaders SO THE PLAYER SENDS
    // THEM — THE GOOGLEVIDEO URLS 403 WITHOUT THE MATCHING UA. FALLS BACK TO WATERMEDIA DEFAULTS.
    private static RequestHeaders headers(final JsonObject format, final URI sourceUri) {
        final JsonObject hh = format == null ? null : format.getAsJsonObject("http_headers");
        if (hh == null || hh.size() == 0) {
            return sourceUri == null ? new RequestHeaders() : RequestHeaders.defaults(sourceUri);
        }
        final RequestHeaders headers = new RequestHeaders();
        for (final Map.Entry<String, JsonElement> e : hh.entrySet()) {
            if (!e.getValue().isJsonNull()) {
                headers.set(e.getKey(), e.getValue().getAsString());
            }
        }
        return headers;
    }

    private static Metadata metadata(final JsonObject media) {
        final String title = str(media, "title");
        String author = str(media, "uploader");
        if (author == null) author = str(media, "channel");
        final String description = str(media, "description");
        final long durationMs = (long) (dbl(media, "duration") * 1000);

        Instant publishedAt = null;
        final JsonElement ts = media.get("timestamp");
        if (ts != null && !ts.isJsonNull()) {
            publishedAt = Instant.ofEpochSecond(ts.getAsLong());
        } else {
            final String date = str(media, "upload_date"); // YYYYMMDD
            if (date != null && date.length() == 8) {
                try {
                    publishedAt = LocalDate.of(Integer.parseInt(date.substring(0, 4)),
                            Integer.parseInt(date.substring(4, 6)), Integer.parseInt(date.substring(6, 8)))
                            .atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (final Exception ignored) {
                    // LEAVE UNSET
                }
            }
        }
        return new Metadata(title, description, publishedAt, durationMs, author);
    }

    // BUILDS SUBTITLE SLAVES FROM subtitles{} (MANUAL) THEN automatic_captions{} (AUTO), ONE PER LANGUAGE
    private static List<DataSlave> subtitles(final JsonObject media) {
        final List<DataSlave> out = new ArrayList<>();
        final Set<String> langs = new HashSet<>();
        collectSubs(media.getAsJsonObject("subtitles"), false, langs, out);
        collectSubs(media.getAsJsonObject("automatic_captions"), true, langs, out);
        return out.isEmpty() ? null : out;
    }

    private static void collectSubs(final JsonObject subs, final boolean auto,
                                    final Set<String> langs, final List<DataSlave> out) {
        if (subs == null) return;
        for (final Map.Entry<String, JsonElement> e : subs.entrySet()) {
            final String lang = e.getKey();
            if (!langs.add(lang) || !e.getValue().isJsonArray()) continue;
            final JsonArray tracks = e.getValue().getAsJsonArray();
            if (tracks.isEmpty()) continue;
            final JsonObject track = tracks.get(0).getAsJsonObject();
            final String url = str(track, "url");
            if (url == null) continue;
            String name = str(track, "name");
            if (name == null) name = lang;
            if (auto) name = name + " (auto-generated)";
            out.add(new DataSlave(name, lang, uri(url)));
        }
    }

    // READS THE expire=<epoch> PARAM FROM A GOOGLEVIDEO/CDN URL; LIVE OR MISSING → SHORT/FALLBACK TTL
    private static Instant expiry(final URI uri, final boolean live) {
        if (live) return Instant.now().plusSeconds(LIVE_TTL_SECONDS);
        if (uri != null) {
            final String query = uri.getRawQuery();
            if (query != null) {
                for (final String param : query.split("&")) {
                    if (param.startsWith("expire=")) {
                        try {
                            return Instant.ofEpochSecond(Long.parseLong(param.substring(7)));
                        } catch (final NumberFormatException ignored) {
                            // FALL THROUGH
                        }
                    }
                }
            }
        }
        return Instant.now().plusSeconds(FALLBACK_TTL_SECONDS);
    }

    // PAIRS A RESOLVED SOURCE WITH ITS EXPIRATION
    private record Result(Instant expires, DataSource source) {}
}
