package org.watermedia.tools;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.NetRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Unified parser for the M3U/M3U8 playlist family. A single {@link #parse(String, URI)}
 * classifies the content and returns the matching {@link Playlist}:
 *
 * <ul>
 *   <li>{@link Master} — HLS master playlist carrying {@code #EXT-X-STREAM-INF} variant
 *       streams (the quality ladder of a single piece of content).</li>
 *   <li>{@link Media} — HLS media playlist carrying {@code #EXT-X-*} tags and {@code #EXTINF}
 *       segments (chunks of a single stream, live or VOD).</li>
 *   <li>{@link Iptv} — flat IPTV playlist: {@code #EXTINF} channels with {@code tvg-*}
 *       attributes and NO {@code #EXT-X-*} tags; each entry is an independent stream.</li>
 * </ul>
 *
 * <p><b>Error contract.</b> {@link #parse} and {@link #fetch} are strict: anything that is
 * not a usable playlist throws {@link IOException}, and every message states <i>where</i>
 * (the source {@link URI}), <i>what</i> failed, and <i>why</i> it cannot continue. Use the
 * convenience {@link #qualities(URI)} when you instead want a best-effort result that never
 * fails — it absorbs those exceptions and falls back to the raw URL so FFmpeg can still probe.
 *
 * <p><b>URIs are resolved here.</b> Every {@link Variant}, {@link Segment}, {@link Rendition}
 * and {@link Channel} URL is resolved against the source while parsing, so callers always get
 * absolute {@link URI}s and never repeat the relative-resolution dance themselves. Malformed
 * URLs are skipped (logged) rather than aborting the whole playlist.
 *
 * <p><b>Classification order is significant:</b> {@code #EXT-X-STREAM-INF} wins (master), then
 * ANY remaining {@code #EXT-X-} tag marks an HLS media playlist, and only a tag-less
 * {@code #EXTINF} list is treated as IPTV. {@link String#indexOf(String)} is JIT-intrinsified
 * and short-circuits, so the classification probe is far cheaper than a parse pass.
 */
public final class MPEGTools {
    private MPEGTools() {}

    private static final Marker IT = MarkerManager.getMarker("MPEGTools");

    // ATTRIBUTE PATTERN COVERS BOTH HLS (UPPERCASE KEYS, QUOTED OR BARE VALUES)
    // AND IPTV (LOWERCASE tvg-* KEYS, ALWAYS QUOTED VALUES)
    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9-]+)=(?:\"([^\"]*)\"|([^,\\s]+))");
    private static final Pattern RES = Pattern.compile("(\\d+)x(\\d+)");

    private static final int FETCH_TIMEOUT_MS = 10_000;

    // ================================================================================================
    // ENTRY POINTS
    // ================================================================================================

    /**
     * Fetches and parses the playlist at {@code uri} using WaterMedia's User-Agent.
     *
     * @throws IOException if the request fails, the server does not answer {@code 200},
     *                     or the body is not a recognizable playlist (see {@link #parse}).
     */
    public static Playlist fetch(final URI uri) throws IOException {
        return fetch(uri, WaterMedia.USER_AGENT);
    }

    /**
     * Fetches and parses the playlist at {@code uri} with an explicit User-Agent.
     *
     * @throws IOException if the request fails, the status code is not {@code 200},
     *                     or the body is not a recognizable playlist.
     */
    public static Playlist fetch(final URI uri, final String userAgent) throws IOException {
        try (final NetRequest req = NetRequest.create(uri)
                .method("GET")
                .accept("*/*")
                .header("User-Agent", userAgent)
                .connectTimeout(FETCH_TIMEOUT_MS)
                .readTimeout(FETCH_TIMEOUT_MS)
                .send()) {
            final int code = req.statusCode();
            if (code != 200)
                throw new IOException("Cannot fetch playlist from " + uri + ": server returned HTTP " + code + " (expected 200 OK)");
            return parse(req.readAllAsString(), uri);
        }
    }

    /**
     * Parses an already-loaded playlist body.
     *
     * @param raw    the full playlist text (a leading UTF-8 BOM is tolerated)
     * @param source the URI the body came from; relative entry URLs are resolved against it
     * @throws IOException when the body is empty/blank, is missing the mandatory {@code #EXTM3U}
     *                     header, or carries no recognizable {@code #EXT-X-STREAM-INF} /
     *                     {@code #EXT-X-*} / {@code #EXTINF} content. The message names the source,
     *                     the concrete defect, and why parsing cannot proceed.
     */
    public static Playlist parse(final String raw, final URI source) throws IOException {
        if (raw == null || raw.isBlank())
            throw new IOException("Empty playlist from " + source + ": the response body was null or blank, nothing to parse");

        // STRIP A LEADING UTF-8 BOM (SOME IPTV PROVIDERS EMIT IT) BEFORE LOOKING FOR THE HEADER
        final String content = raw.charAt(0) == '\uFEFF' ? raw.substring(1) : raw;

        final String head = content.stripLeading();
        if (!head.startsWith("#EXTM3U"))
            throw new IOException("Invalid playlist from " + source + ": missing the mandatory #EXTM3U header (body starts with '"
                    + preview(head) + "'), so it is not an M3U/M3U8 document");

        // CLASSIFICATION: STREAM-INF WINS (MASTER); ANY OTHER #EXT-X- MARKS A MEDIA PLAYLIST;
        // A TAG-LESS #EXTINF LIST IS IPTV. indexOf IS INTRINSIFIED AND SHORT-CIRCUITS.
        if (content.contains("#EXT-X-STREAM-INF")) return parseMaster(content, source);
        if (content.contains("#EXT-X-"))           return parseMedia(content, source);
        if (content.contains("#EXTINF:"))          return parseIptv(content, source);

        throw new IOException("Unrecognized playlist from " + source
                + ": has #EXTM3U but none of #EXT-X-STREAM-INF (master), #EXT-X-* (media) or #EXTINF (iptv), so its shape is unknown");
    }

    /**
     * Reads a playlist from a stream and parses it as UTF-8. The stream is always closed.
     *
     * @throws IOException if reading fails (wrapped with the source for context) or the parsed
     *                     body is not a recognizable playlist.
     */
    public static Playlist parse(final InputStream stream, final URI source) throws IOException {
        final String text;
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder(16 * 1024);
            final char[] buf = new char[8192];
            int r;
            while ((r = reader.read(buf)) > 0) sb.append(buf, 0, r);
            text = sb.toString();
        } catch (final IOException e) {
            throw new IOException("Failed to read the playlist stream from " + source + ": " + e.getMessage(), e);
        }
        return parse(text, source);
    }

    /**
     * Best-effort resolution of a single HLS stream URL into its quality ladder, ready to play.
     * Unlike {@link #fetch}/{@link #parse}, this never throws — it is the resilient path platforms
     * use so a parse hiccup never breaks playback:
     *
     * <ul>
     *   <li>master playlist  → its variants (URLs already absolute);</li>
     *   <li>media playlist   → a single variant pointing at the playlist itself;</li>
     *   <li>IPTV / fetch / parse failure → a single variant pointing at {@code source}, logged,
     *       so FFmpeg can still probe the raw URL.</li>
     * </ul>
     */
    public static List<Variant> qualities(final URI source) {
        try {
            final Playlist playlist = fetch(source);
            if (playlist instanceof final Master master && !master.variants().isEmpty()) {
                LOGGER.debug(IT, "Resolved {} HLS rendition(s) from master {}", master.variants().size(), source);
                return master.variants();
            }
            if (playlist instanceof Iptv)
                LOGGER.warn(IT, "Expected an HLS stream but {} is an IPTV playlist; falling back to the raw URL", source);
            // MEDIA PLAYLIST, OR MASTER WITH NO USABLE VARIANTS: THE URL ITSELF IS THE ONLY QUALITY
            return List.of(Variant.self(source));
        } catch (final IOException e) {
            LOGGER.warn(IT, "Could not resolve HLS qualities for {}: {}; falling back to the raw URL", source, e.getMessage());
            return List.of(Variant.self(source));
        }
    }

    /**
     * Loads an IPTV catalog. Strict by design — a failed catalog load is meaningful, so it throws.
     *
     * @throws IOException if the fetch/parse fails, or the resource is an HLS playlist rather than IPTV.
     */
    public static List<Channel> channels(final URI source) throws IOException {
        final Playlist playlist = fetch(source);
        if (playlist instanceof final Iptv iptv) return iptv.channels();
        throw new IOException("Expected an IPTV playlist from " + source + " but got an HLS " + playlist.kind() + " playlist");
    }

    // ================================================================================================
    // PLAYLIST TYPES
    // ================================================================================================

    public sealed interface Playlist permits Master, Media, Iptv {
        /** The URI this playlist was parsed from; entry URLs are already resolved against it. */
        URI source();

        /** Lower-case discriminator ({@code "master"}, {@code "media"}, {@code "iptv"}). */
        String kind();
    }

    /** HLS master playlist: a quality ladder ({@code variants}) plus alternate {@code renditions}. */
    public record Master(URI source, int version, List<Variant> variants, List<Rendition> renditions) implements Playlist {
        @Override public String kind() { return "master"; }

        /** Highest-bandwidth variant, if any. */
        public Optional<Variant> best() {
            return this.variants.stream().max(Comparator.comparingLong(Variant::bandwidth));
        }

        /** Lowest-bandwidth variant, if any. */
        public Optional<Variant> worst() {
            return this.variants.stream().min(Comparator.comparingLong(Variant::bandwidth));
        }

        /** Variants ordered by descending bandwidth. */
        public List<Variant> sorted() {
            final List<Variant> sorted = new ArrayList<>(this.variants);
            sorted.sort(Comparator.comparingLong(Variant::bandwidth).reversed());
            return Collections.unmodifiableList(sorted);
        }
    }

    /** HLS media playlist: ordered {@code segments} plus live/VOD timing metadata. */
    public record Media(URI source, int version, List<Segment> segments, double targetDuration,
                        long sequence, boolean live, boolean vod, double totalDuration) implements Playlist {
        @Override public String kind() { return "media"; }
    }

    /** Flat IPTV catalog: a list of independent {@code channels}. */
    public record Iptv(URI source, List<Channel> channels) implements Playlist {
        @Override public String kind() { return "iptv"; }

        /** Distinct {@code group-title} values, in first-seen order. */
        public Set<String> groups() {
            final Set<String> g = new LinkedHashSet<>();
            for (final Channel c: this.channels) if (c.tvgGroup != null) g.add(c.tvgGroup);
            return Collections.unmodifiableSet(g);
        }

        /** Channels belonging to {@code group} (pass {@code null} for ungrouped). */
        public List<Channel> byGroup(final String group) {
            final List<Channel> out = new ArrayList<>();
            for (final Channel c: this.channels) if (Objects.equals(c.tvgGroup, group)) out.add(c);
            return Collections.unmodifiableList(out);
        }
    }

    // ================================================================================================
    // DATA RECORDS
    // ================================================================================================

    /** One rendition of a {@link Master}'s quality ladder. URL is absolute. */
    public record Variant(URI uri, long bandwidth, int width, int height, double fps,
                          String codecs, String videoGroup, String audioGroup, String name) {
        /** A metadata-less variant that just points at a stream URL (media playlist / fallback). */
        public static Variant self(final URI uri) {
            return new Variant(uri, 0L, 0, 0, 0d, null, null, null, null);
        }
    }

    /** One segment of a {@link Media} playlist. URL is absolute. */
    public record Segment(URI uri, double duration, String title, long sequence, String dateTime) {}

    /** An alternate {@code #EXT-X-MEDIA} rendition (audio/subtitle/video group). URL may be null. */
    public record Rendition(String type, String groupId, String name, String language, URI uri,
                            boolean isDefault, boolean autoSelect) {}

    /**
     * A single channel entry parsed from an IPTV M3U.
     *
     * @param title       trailing label, or {@code tvg-name}/URL when missing
     * @param url         the stream URL (absolute)
     * @param tvgId       optional {@code tvg-id} (EPG cross-reference)
     * @param tvgName     optional {@code tvg-name}
     * @param tvgLogo     optional {@code tvg-logo} (channel icon URL, passed through verbatim)
     * @param tvgCountry  optional {@code tvg-country} ISO code
     * @param tvgGroup    optional {@code group-title}
     * @param attrs       unmodifiable view of every raw {@code key="value"} attribute, lower-cased
     */
    public record Channel(String title, URI url, String tvgId, String tvgName, String tvgLogo,
                          String tvgCountry, String tvgGroup, Map<String, String> attrs) {}

    // ================================================================================================
    // PARSERS  (single pass each: walk the body once with indexOf, no regex split / no String[] alloc)
    // ================================================================================================

    private static Master parseMaster(final String content, final URI source) {
        final List<Variant> variants = new ArrayList<>();
        final List<Rendition> renditions = new ArrayList<>();
        final Map<String, Rendition> videoGroups = new HashMap<>();
        int version = 1;

        final int len = content.length();
        int i = 0;
        while (i < len) {
            final int nl = content.indexOf('\n', i);
            final int end = nl < 0 ? len : nl;
            final String line = content.substring(i, end).trim();
            i = nl < 0 ? len : nl + 1;
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-VERSION:")) {
                version = DataTool.toInt(line.substring(15), 1);
            }
            else if (line.startsWith("#EXT-X-MEDIA:")) {
                final Map<String, String> a = attrs(line.substring(13));
                final String type = a.get("TYPE");
                final String groupId = a.get("GROUP-ID");
                if (type != null && groupId != null) {
                    final Rendition r = new Rendition(
                            type, groupId,
                            a.getOrDefault("NAME", ""),
                            a.get("LANGUAGE"),
                            resolve(source, a.get("URI")),
                            "YES".equalsIgnoreCase(a.get("DEFAULT")),
                            "YES".equalsIgnoreCase(a.get("AUTOSELECT")));
                    renditions.add(r);
                    if ("VIDEO".equalsIgnoreCase(type)) videoGroups.put(groupId, r);
                }
            }
            else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                // PEEK THE FOLLOWING URI LINE; THE MAIN LOOP THEN SKIPS IT (IT MATCHES NO TAG)
                final URI uri = resolve(source, nextUri(content, i, len));
                if (uri != null) {
                    final Map<String, String> a = attrs(line.substring(18));

                    int w = 0, h = 0;
                    final String res = a.get("RESOLUTION");
                    if (res != null) {
                        final Matcher m = RES.matcher(res);
                        if (m.find()) {
                            w = DataTool.toInt(m.group(1), 0);
                            h = DataTool.toInt(m.group(2), 0);
                        }
                    }

                    final String videoGroup = a.get("VIDEO");
                    String name = "";
                    if (videoGroup != null) {
                        final Rendition r = videoGroups.get(videoGroup);
                        if (r != null) name = r.name();
                    }

                    variants.add(new Variant(
                            uri,
                            DataTool.toLong(a.get("BANDWIDTH"), 0),
                            w, h,
                            DataTool.toDouble(a.get("FRAME-RATE"), 30d),
                            a.get("CODECS"),
                            videoGroup,
                            a.get("AUDIO"),
                            name));
                }
            }
        }

        return new Master(source, version, List.copyOf(variants), List.copyOf(renditions));
    }

    private static Media parseMedia(final String content, final URI source) {
        final List<Segment> segments = new ArrayList<>();
        double targetDuration = 0;
        long sequence = 0;
        int version = 1;
        boolean vod = false;
        boolean endList = false;
        double totalDuration = 0;

        double segDuration = 0;
        String segTitle = "";
        String dateTime = null;

        final int len = content.length();
        int i = 0;
        while (i < len) {
            final int nl = content.indexOf('\n', i);
            final int end = nl < 0 ? len : nl;
            final String line = content.substring(i, end).trim();
            i = nl < 0 ? len : nl + 1;
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-VERSION:")) {
                version = DataTool.toInt(line.substring(15), 1);
            }
            else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = DataTool.toDouble(line.substring(22), 0);
            }
            else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = DataTool.toLong(line.substring(22), 0);
            }
            else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                vod = "VOD".equalsIgnoreCase(line.substring(21).trim());
            }
            else if (line.equals("#EXT-X-ENDLIST")) {
                endList = true;
            }
            else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                dateTime = line.substring(25).trim();
            }
            else if (line.startsWith("#EXTINF:")) {
                final String extinf = line.substring(8);
                final int comma = extinf.indexOf(',');
                segDuration = DataTool.toDouble(comma >= 0 ? extinf.substring(0, comma) : extinf, 0);
                segTitle = comma >= 0 ? extinf.substring(comma + 1).trim() : "";
            }
            else if (line.charAt(0) != '#' && segDuration > 0) {
                final URI uri = resolve(source, line);
                if (uri != null) {
                    segments.add(new Segment(uri, segDuration, segTitle, sequence + segments.size(), dateTime));
                    totalDuration += segDuration;
                }
                segDuration = 0;
                segTitle = "";
                dateTime = null;
            }
        }

        return new Media(source, version, List.copyOf(segments), targetDuration, sequence, !endList, vod, totalDuration);
    }

    private static Iptv parseIptv(final String content, final URI source) {
        final List<Channel> channels = new ArrayList<>();
        String title = null;
        Map<String, String> attrs = null;

        final int len = content.length();
        int i = 0;
        while (i < len) {
            final int nl = content.indexOf('\n', i);
            final int end = nl < 0 ? len : nl;
            final String line = content.substring(i, end).trim();
            i = nl < 0 ? len : nl + 1;
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTINF:")) {
                // FORMAT: #EXTINF:<DURATION> [attr="value"]* ,TITLE
                final String text = line.substring(8);
                // FIND THE LAST UNQUOTED COMMA THAT SEPARATES ATTRS FROM TITLE
                int commaIdx = -1;
                boolean inQuotes = false;
                for (int k = 0; k < text.length(); k++) {
                    final char c = text.charAt(k);
                    if (c == '"') inQuotes = !inQuotes;
                    else if (c == ',' && !inQuotes) commaIdx = k;
                }
                final String header = commaIdx >= 0 ? text.substring(0, commaIdx) : text;
                title = commaIdx >= 0 ? text.substring(commaIdx + 1).trim() : null;

                // IPTV KEYS ARE LOWER-CASED FOR STABLE tvg-* LOOKUPS
                attrs = new HashMap<>();
                final Matcher m = ATTR.matcher(header);
                while (m.find()) {
                    attrs.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) != null ? m.group(2) : m.group(3));
                }
                continue;
            }

            if (line.charAt(0) == '#') continue;

            // PAYLOAD LINE — FLUSH THE PENDING ENTRY (OR A BARE-URL ONE)
            final URI url = resolve(source, line);
            if (url != null) {
                final Map<String, String> a = attrs != null ? attrs : Map.of();
                final String name = title != null && !title.isEmpty() ? title : a.getOrDefault("tvg-name", line);
                channels.add(new Channel(name, url,
                        a.get("tvg-id"),
                        a.get("tvg-name"),
                        a.get("tvg-logo"),
                        a.get("tvg-country"),
                        a.get("group-title"),
                        a.isEmpty() ? Map.of() : Map.copyOf(a)));
            }
            title = null;
            attrs = null;
        }

        return new Iptv(source, List.copyOf(channels));
    }

    // ================================================================================================
    // UTILITIES
    // ================================================================================================

    /** Resolves {@code spec} against {@code base}; null/blank or malformed inputs yield null (logged). */
    private static URI resolve(final URI base, final String spec) {
        if (spec == null || spec.isEmpty()) return null;
        try {
            return base.resolve(spec);
        } catch (final IllegalArgumentException e) {
            LOGGER.warn(IT, "Skipping malformed URI '{}' relative to {}", spec, base);
            return null;
        }
    }

    /** Parses {@code KEY=VALUE} / {@code KEY="VALUE"} pairs from an HLS tag body (keys kept as-is). */
    private static Map<String, String> attrs(final String body) {
        final Map<String, String> map = new HashMap<>();
        final Matcher m = ATTR.matcher(body);
        while (m.find()) {
            map.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        return map;
    }

    /** First non-comment, non-empty line at/after {@code from}; null if a tag is hit first. */
    private static String nextUri(final String content, final int from, final int len) {
        int i = from;
        while (i < len) {
            final int nl = content.indexOf('\n', i);
            final int end = nl < 0 ? len : nl;
            final String l = content.substring(i, end).trim();
            i = nl < 0 ? len : nl + 1;
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-") || l.startsWith("#EXTINF")) return null;
            if (l.charAt(0) == '#') continue;
            return l;
        }
        return null;
    }

    /** Short, single-line excerpt of a body for error messages. */
    private static String preview(final String s) {
        final String cut = s.substring(0, Math.min(32, s.length()));
        return cut.replace('\n', ' ').replace('\r', ' ');
    }
}