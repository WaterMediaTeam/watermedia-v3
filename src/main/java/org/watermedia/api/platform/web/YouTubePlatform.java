package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.util.NetRequest;
import org.watermedia.binaries.BotGuardBinary;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JsonTool.str;
import static org.watermedia.tools.JsonTool.uri;

/**
 * YouTube platform backed by yt-dlp. Extends {@link YtDlpPlatform} to reuse its yt-dlp engine (binary
 * invocation + JSON→{@link org.watermedia.api.platform.DataSource} mapping) and adds the two things
 * YouTube alone needs: the BotGuard po_token retry for the "Sign in to confirm you're not a bot" gate,
 * and the InnerTube {@code visitorData} fetch the token must be bound to.
 *
 * <p>When yt-dlp is blocked by the bot check, the request is retried with a freshly minted po_token
 * supplied to the web-family clients, which unblocks their otherwise-gated stream URLs while keeping
 * yt-dlp's bot-resistant default clients. The rustypipe-botguard binary that mints the token is
 * provisioned lazily by the {@code binaries} module ({@link BotGuardBinary}); running it out-of-process
 * keeps a crash in its bundled JS engine from taking down the host JVM.
 */
public class YouTubePlatform extends YtDlpPlatform {
    public static final String NAME = "YouTube";
    private static final Marker IT = MarkerManager.getMarker(YouTubePlatform.class.getSimpleName());
    private static final String[] HOSTS = { "youtube.com", "youtu.be" };
    // VIDEO SHAPES (vs a pure playlist link) — DECIDES WHETHER TO PASS --no-playlist
    private static final Pattern YOUTUBE_VIDEO_ID = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|feeds/api/videos/|watch\\?v=|watch\\?.+&v=))([^/?&#]+)");

    private static final long BOTGUARD_TIMEOUT_SECONDS = 60; // COLD START RUNS THE BOTGUARD VM + NETWORK

    // INNERTUBE visitor_id ENDPOINT — A po_token MUST BE BOUND TO THE visitorData THEN HANDED TO yt-dlp
    private static final String VISITOR_ENDPOINT = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false";
    private static final String CLIENT_VERSION = "2.20250601.01.00"; // WEB client; the endpoint is lenient
    private static final String VISITOR_BODY =
            "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"" + CLIENT_VERSION
            + "\",\"hl\":\"en\",\"gl\":\"US\"}}}";

    private final BotGuardBinary botGuard = new BotGuardBinary();
    private final Object botGuardLock = new Object(); // SERIALISE MINTS: THE BOTGUARD RUNS SHARE A SNAPSHOT FILE

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // NOT A YOUTUBE URL → LET PlatformAPI KEEP PROBING OTHER HANDLERS (HOSTS ARE CASE-INSENSITIVE)
        final String host = uri.getHost();
        if (host == null || !DataTool.endsWith(host.toLowerCase(Locale.ROOT), HOSTS)) {
            return null;
        }

        // A VIDEO LINK PLAYS THE VIDEO (EVEN IF IT CARRIES &list=); A PURE PLAYLIST LINK PLAYS THE PLAYLIST
        final List<String> args = new ArrayList<>();
        if (YOUTUBE_VIDEO_ID.matcher(uri.toString()).find()) {
            args.add("--no-playlist");
        }

        final JsonObject info;
        try {
            info = this.info(uri.toString(), args);
        } catch (final IOException e) {
            if (isBotCheck(e.getMessage())) {
                return this.retryWithPoToken(uri, args);
            }
            throw new PlatformException(YouTubePlatform.class, "yt-dlp could not resolve " + uri + ": " + e.getMessage(), e);
        }
        return this.extract(info); // EXTRACTION EXCEPTIONS (Mature/Platform) PROPAGATE AS-IS
    }

    // RETRIES WITH A PO_TOKEN BOUND TO A FRESH visitorData, SUPPLIED TO EVERY WEB-FAMILY gvs CONTEXT
    // (THE BotGuard WEB TOKEN COVERS web/web_safari/mweb/tv). NO player_client OVERRIDE: yt-dlp KEEPS ITS
    // BOT-RESISTANT DEFAULTS (android_vr, web_safari) AND THE TOKEN UNBLOCKS THE WEB CLIENT'S GATED STREAMS
    // — FORCING ONLY THE WEB CLIENTS YIELDS NO FORMATS. SURFACES THE ORIGINAL ERROR IF THE RETRY ALSO FAILS.
    private PlatformData retryWithPoToken(final URI uri, final List<String> baseArgs) throws Exception {
        try {
            final String visitorData = fetchVisitorData();
            final String token = this.mintToken(visitorData);
            final List<String> args = new ArrayList<>(baseArgs);
            args.add("--extractor-args");
            args.add("youtube:po_token=web.gvs+" + token + ",web_safari.gvs+" + token
                    + ",mweb.gvs+" + token + ",tv.gvs+" + token
                    + ";visitor_data=" + visitorData + ";player_skip=webpage,configs");
            args.add("--extractor-args");
            args.add("youtubetab:skip=webpage");
            LOGGER.info(IT, "Retrying '{}' with a BotGuard po_token", uri);
            return this.extract(this.info(uri.toString(), args));
        } catch (final Exception retry) {
            throw new PlatformException(YouTubePlatform.class,
                    "YouTube blocked this request and the po_token retry failed: " + retry.getMessage(), retry);
        }
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws Exception {
        // ytsearchN: HITS ONLY YOUTUBE'S SEARCH ENDPOINT; --flat-playlist KEEPS IT TO RAW RESULT METADATA
        // (NO PER-VIDEO STREAM EXTRACTION), SO IT IS FAST AND NEEDS NO PO_TOKEN/BOTGUARD RETRY.
        final JsonObject info;
        try {
            info = this.info("ytsearch" + limit + ":" + query, List.of("--flat-playlist"));
        } catch (final IOException e) {
            throw new PlatformException(YouTubePlatform.class, "yt-dlp could not search '" + query + "': " + e.getMessage(), e);
        }

        final JsonArray entries = info.getAsJsonArray("entries");
        if (entries == null) return List.of();

        final List<PlatformResult> out = new ArrayList<>(Math.min(entries.size(), limit));
        for (final JsonElement element: entries) {
            if (element == null || element.isJsonNull()) continue;
            final JsonObject entry = element.getAsJsonObject();
            // A SEARCH ALSO SURFACES PLAYLISTS/CHANNELS (ie_key YoutubeTab) — KEEP ONLY PLAYABLE VIDEOS
            if (!"Youtube".equals(str(entry, "ie_key"))) continue;

            final String id = str(entry, "id");
            final String url = str(entry, "url");
            if (id == null && url == null) continue;

            // DERIVE THE THUMBNAIL FROM THE VIDEO ID: hqdefault EXISTS FOR EVERY VIDEO, UNLIKE THE
            // VERSION-DEPENDENT (AND OFTEN EXPIRING) thumbnails[] URLS yt-dlp EMITS IN FLAT MODE
            final URI thumbnail = id != null ? uri("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg") : null;
            // yt-dlp'S FLAT-PLAYLIST url CAN BE A BARE VIDEO ID (NO SCHEME); ONLY USE IT WHEN ABSOLUTE,
            // ELSE BUILD THE WATCH URL FROM THE ID SO SELECTING THE RESULT PLAYS IT INSTEAD OF RE-SEARCHING
            final URI parsed = uri(url);
            final URI page = parsed != null && parsed.isAbsolute() ? parsed
                    : id != null ? uri("https://www.youtube.com/watch?v=" + id) : null;
            if (page == null) continue; // NEITHER AN ABSOLUTE url NOR AN id — NOTHING PLAYABLE TO LINK
            out.add(new PlatformResult(NAME, str(entry, "title"), thumbnail, page));
            if (out.size() >= limit) break;
        }
        return out;
    }

    // MINTS A po_token FOR visitorData BY DRIVING rustypipe-botguard. ITS SINGLE OUTPUT LINE IS
    // "<token> valid_until=<unix> from_snapshot=<bool>", SO THE FIRST WHITESPACE FIELD IS THE TOKEN.
    // SERIALISED: CONCURRENT RUNS WOULD RACE ON THE SHARED --snapshot-file. FRESH visitorData PER CALL
    // MAKES CACHING POINTLESS, SO WE JUST MINT ON DEMAND (THE SNAPSHOT FILE ALREADY SPEEDS COLD STARTS).
    private String mintToken(final String visitorData) throws IOException {
        final Path exe;
        final Path snapshot;
        try {
            exe = this.botGuard.executable();
            snapshot = this.botGuard.snapshot();
        } catch (final IOException e) {
            throw new IOException("Failed to provision rustypipe-botguard: " + e.getMessage(), e);
        }

        final List<String> command = List.of(exe.toString(), "--snapshot-file", snapshot.toString(), "--", visitorData);
        final String stdout;
        synchronized (this.botGuardLock) {
            stdout = runProcess(command, BOTGUARD_TIMEOUT_SECONDS, "rustypipe-botguard");
        }

        for (final String line : stdout.split("\\R")) {
            if (!line.isBlank()) {
                final String token = line.trim().split("\\s+")[0];
                if (!token.isEmpty()) return token;
            }
        }
        throw new IOException("rustypipe-botguard produced no token");
    }

    // FETCHES A FRESH visitorData FROM YOUTUBE'S INNERTUBE visitor_id ENDPOINT (REPLACING NEWPIPE'S
    // FORMER visitor-data CALL). THE po_token MUST BE BOUND TO THE SAME visitorData HANDED TO yt-dlp.
    private static String fetchVisitorData() throws IOException {
        try (final NetRequest request = NetRequest.create(VISITOR_ENDPOINT)
                .method("POST")
                .contentType("application/json")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .body(VISITOR_BODY)
                .send()) {
            if (request.statusCode() != 200) {
                throw new IOException("visitor_id endpoint returned HTTP " + request.statusCode());
            }
            final JsonObject json = JsonParser.parseString(request.readAllAsString()).getAsJsonObject();
            return json.getAsJsonObject("responseContext").get("visitorData").getAsString();
        }
    }

    // yt-dlp'S MESSAGES FOR THE BOT/AGE GATE THAT A PO_TOKEN CAN BYPASS
    private static boolean isBotCheck(final String message) {
        if (message == null) return false;
        final String m = message.toLowerCase(Locale.ROOT);
        return m.contains("not a bot") || m.contains("sign in to confirm") || m.contains("po_token")
                || m.contains("confirm your age");
    }
}
