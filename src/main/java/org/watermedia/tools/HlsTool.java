package org.watermedia.tools;

import org.watermedia.WaterMedia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsTool {
    private HlsTool() {}

    public static Result parse(final String content, final String source) {
        if (content == null || content.isBlank()) {
            return new ErrorResult("Content is null or empty", null);
        }
        if (!content.trim().startsWith("#EXTM3U")) {
            return new ErrorResult("Invalid M3U8: Missing #EXTM3U header", null);
        }

        try {
            if (content.contains("#EXT-X-STREAM-INF")) {
                return parseMaster(content, source);
            }
            if (content.contains("#EXT-X-TARGETDURATION") || content.contains("#EXTINF:")) {
                return parseMedia(content, source);
            }
            return new ErrorResult("Unknown playlist type", null);
        } catch (final Exception e) {
            return new ErrorResult("Parse error: " + e.getMessage(), e);
        }
    }

    public static Result fetch(final URI uri) {
        try {
            final var http = NetTool.connectToHTTP(uri, "GET", "*/*");
            http.setConnectTimeout(10_000);
            http.setInstanceFollowRedirects(true);
            http.setRequestProperty("User-Agent", WaterMedia.USER_AGENT);
            http.setDoInput(true);
            NetTool.validateHTTP200(http.getResponseCode(), uri);

            final var response = http.getInputStream().readAllBytes();
            return parse(new String(response, StandardCharsets.UTF_8), uri.toString());

        } catch (final Exception e) {
            return new ErrorResult("Fetch error: " + e.getMessage(), e);
        }
    }

    /**
     * Parse from InputStream
     */
    public static Result parse(final InputStream stream, final String source) {
        try (final var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            final var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return parse(sb.toString(), source);
        } catch (final IOException e) {
            return new ErrorResult("Read error: " + e.getMessage(), e);
        }
    }

    // ==========================================================================
    // RESULT TYPES
    // ==========================================================================

    public sealed interface Result permits MasterResult, MediaResult, ErrorResult {}

    public record MasterResult(String source, int version, List<Variant> variants, List<Rendition> renditions, List<SessionData> sessionData) implements Result {

        public Optional<Variant> best() {
            return this.variants.stream().max((a, b) -> Long.compare(a.bandwidth, b.bandwidth));
        }

        public Optional<Variant> worst() {
            return this.variants.stream().min((a, b) -> Long.compare(a.bandwidth, b.bandwidth));
        }

        public Optional<Variant> byResolution(final int w, final int h) {
            return this.variants.stream().filter(v -> v.width == w && v.height == h).findFirst();
        }

        public List<Variant> sorted() {
            final List<Variant> sorted = new ArrayList<>(this.variants);
            sorted.sort((a, b) -> Long.compare(b.bandwidth, a.bandwidth));
            return Collections.unmodifiableList(sorted);
        }
    }

    public record MediaResult(String source, int version, List<Segment> segments, double targetDuration, long sequence, boolean live, boolean vod, double totalDuration) implements Result {

        public Optional<Segment> bySequence(final long seq) {
            return this.segments.stream().filter(s -> s.sequence == seq).findFirst();
        }
    }

    public record ErrorResult(String message, Exception cause) implements Result {}

    // ==========================================================================
    // DATA RECORDS
    // ==========================================================================

    public record Variant(
            String uri,
            long bandwidth,
            int width,
            int height,
            double fps,
            String codecs,
            String videoGroup,
            String audioGroup,
            String name
    ) {
        public String resolution() { return this.width + "x" + this.height; }

        public String quality() {
            final int f = (int) Math.round(this.fps);
            final String fpsStr = f > 30 ? String.valueOf(f) : "";
            if (this.height >= 2160) return "4K" + fpsStr;
            if (this.height >= 1440) return "1440p" + fpsStr;
            if (this.height >= 1080) return "1080p" + fpsStr;
            if (this.height >= 720) return "720p" + fpsStr;
            if (this.height >= 480) return "480p";
            if (this.height >= 360) return "360p";
            return this.height + "p";
        }
    }
    public record Segment(String uri, double duration, String title, long sequence, String dateTime) {


    }

    public record SessionData(String id, String value, String uri, String language) {}

    public record Rendition(String type, String groupId, String name, String language, String uri, boolean isDefault, boolean autoSelect) {}

    // ==========================================================================
    // INTERNAL PARSING
    // ==========================================================================

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,\\s]+))");
    private static final Pattern RES = Pattern.compile("(\\d+)x(\\d+)");

    private static MasterResult parseMaster(final String content, final String source) {
        final var variants = new ArrayList<Variant>();
        final var renditions = new ArrayList<Rendition>();
        final var sessionData = new ArrayList<SessionData>();
        final var mediaGroups = new HashMap<String, Rendition>();
        int version = 1;

        final String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i].trim();

            if (line.startsWith("#EXT-X-VERSION:")) {
                version = toInt(line.substring(15), 1);
            }
            else if (line.startsWith("#EXT-X-SESSION-DATA:")) {
                final var attrs = attrs(line.substring(20));
                final String id = attrs.get("DATA-ID");
                if (id != null) {
                    sessionData.add(new SessionData(id, attrs.get("VALUE"), attrs.get("URI"), attrs.get("LANGUAGE")));
                }
            }
            else if (line.startsWith("#EXT-X-MEDIA:")) {
                final var attrs = attrs(line.substring(13));
                final String type = attrs.get("TYPE");
                final String groupId = attrs.get("GROUP-ID");
                if (type != null && groupId != null) {
                    final var r = new Rendition(
                            type, groupId,
                            attrs.getOrDefault("NAME", ""),
                            attrs.get("LANGUAGE"),
                            attrs.get("URI"),
                            "YES".equalsIgnoreCase(attrs.get("DEFAULT")),
                            "YES".equalsIgnoreCase(attrs.get("AUTOSELECT"))
                    );
                    renditions.add(r);
                    mediaGroups.put(groupId, r);
                }
            }
            else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                final String uri = nextUri(lines, i + 1);
                if (uri != null) {
                    final var attrs = attrs(line.substring(18));

                    int w = 0, h = 0;
                    final String res = attrs.get("RESOLUTION");
                    if (res != null) {
                        final Matcher m = RES.matcher(res);
                        if (m.find()) {
                            w = toInt(m.group(1), 0);
                            h = toInt(m.group(2), 0);
                        }
                    }

                    final String videoGroup = attrs.get("VIDEO");
                    String name = "";
                    if (videoGroup != null) {
                        final Rendition r = mediaGroups.get(videoGroup);
                        if (r != null) name = r.name();
                    }

                    variants.add(new Variant(
                            uri,
                            toLong(attrs.get("BANDWIDTH"), 0),
                            w, h,
                            toDouble(attrs.get("FRAME-RATE"), 30.0),
                            attrs.get("CODECS"),
                            videoGroup,
                            attrs.get("AUDIO"),
                            name
                    ));
                }
            }
        }

        return new MasterResult(source, version, variants, renditions, sessionData);
    }

    private static MediaResult parseMedia(final String content, final String source) {
        final var segments = new ArrayList<Segment>();
        double targetDuration = 0;
        long sequence = 0;
        int version = 1;
        boolean vod = false;
        boolean endList = false;

        double segDuration = 0;
        String segTitle = "";
        String dateTime = null;

        for (String line: content.split("\n")) {
            line = line.trim();

            if (line.startsWith("#EXT-X-VERSION:")) {
                version = toInt(line.substring(15), 1);
            }
            else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = toDouble(line.substring(22), 0);
            }
            else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = toLong(line.substring(22), 0);
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
                if (comma > 0) {
                    segDuration = toDouble(extinf.substring(0, comma), 0);
                    segTitle = extinf.substring(comma + 1).trim();
                } else {
                    segDuration = toDouble(extinf.replace(",", ""), 0);
                    segTitle = "";
                }
            }
            else if (!line.startsWith("#") && !line.isEmpty() && segDuration > 0) {
                segments.add(new Segment(line, segDuration, segTitle, sequence + segments.size(), dateTime));
                segDuration = 0;
                segTitle = "";
                dateTime = null;
            }
        }

        final double total = segments.stream().mapToDouble(Segment::duration).sum();

        return new MediaResult(source, version, segments, targetDuration, sequence, !endList, vod, total);
    }

    // ==========================================================================
    // UTILITIES
    // ==========================================================================

    private static Map<String, String> attrs(final String line) {
        final var map = new HashMap<String, String>();
        final Matcher m = ATTR.matcher(line);
        while (m.find()) {
            map.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        return map;
    }

    private static String nextUri(final String[] lines, final int from) {
        for (int i = from; i < lines.length; i++) {
            final String l = lines[i].trim();
            if (!l.startsWith("#") && !l.isEmpty()) return l;
            if (l.startsWith("#EXT-X-") || l.startsWith("#EXTINF")) break;
        }
        return null;
    }

    private static int toInt(final String s, final int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    private static long toLong(final String s, final long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    private static double toDouble(final String s, final double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }
}