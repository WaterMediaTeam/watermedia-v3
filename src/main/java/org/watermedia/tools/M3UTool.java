package org.watermedia.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for IPTV-style extended M3U playlists. Unlike HLS (handled by {@link HlsTool}),
 * an IPTV M3U is a flat list of independent channels: each {@code #EXTINF} line introduces
 * a separate stream pointing at its own URL, carrying {@code tvg-*} attributes (name, logo,
 * country, group) plus a trailing comma-separated title.
 *
 * <p>{@link #isIptv(String)} discriminates IPTV M3U from HLS: an IPTV playlist has
 * {@code #EXTM3U} and at least one {@code #EXTINF:} entry but NONE of the HLS
 * {@code #EXT-X-*} tags (HLS media playlists always carry {@code #EXT-X-TARGETDURATION},
 * masters always carry {@code #EXT-X-STREAM-INF}).
 */
public final class M3UTool {
    private M3UTool() {}

    // QUOTED ATTRIBUTES IN THE #EXTINF HEADER (tvg-name="..." tvg-logo="..." ...)
    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9-]+)=\"([^\"]*)\"");

    /**
     * Returns true when the content is an IPTV-style M3U playlist (flat list of channels),
     * false for HLS master/media playlists and for non-M3U content.
     */
    public static boolean isIptv(final String content) {
        if (content == null) return false;
        final String t = content.trim();
        if (!t.startsWith("#EXTM3U")) return false;
        if (!t.contains("#EXTINF:")) return false;
        // ANY HLS-SPECIFIC TAG MEANS THIS IS A STANDARD HLS PLAYLIST, NOT IPTV
        return !t.contains("#EXT-X-STREAM-INF")
                && !t.contains("#EXT-X-TARGETDURATION")
                && !t.contains("#EXT-X-MEDIA-SEQUENCE")
                && !t.contains("#EXT-X-MEDIA:")
                && !t.contains("#EXT-X-PLAYLIST-TYPE")
                && !t.contains("#EXT-X-ENDLIST")
                && !t.contains("#EXT-X-VERSION")
                && !t.contains("#EXT-X-KEY")
                && !t.contains("#EXT-X-MAP");
    }

    /**
     * Parses an IPTV M3U into its channel entries. Lines that aren't recognised
     * (other {@code #...} tags such as {@code #EXTVLCOPT} or {@code #KODIPROP}) are
     * skipped: those attach playback hints to the following URL, which this parser
     * doesn't propagate today. A bare URL line without a preceding {@code #EXTINF}
     * is still accepted and synthesised into an entry whose title equals the URL.
     */
    public static List<Entry> parse(final String content) {
        final List<Entry> out = new ArrayList<>();
        if (content == null) return out;

        String title = null;
        Map<String, String> attrs = null;

        for (final String raw: content.split("\\r?\\n")) {
            final String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTINF:")) {
                // FORMAT: #EXTINF:<DURATION> [attr="value"]* ,TITLE
                final String text = line.substring(8);
                // FIND THE LAST UNQUOTED COMMA THAT SEPARATES ATTRS FROM TITLE
                int commaIdx = -1;
                boolean inQuotes = false;
                for (int i = 0; i < text.length(); i++) {
                    final char c = text.charAt(i);
                    if (c == '"') inQuotes = !inQuotes;
                    else if (c == ',' && !inQuotes) commaIdx = i;
                }
                final String header = commaIdx >= 0 ? text.substring(0, commaIdx) : text;
                title = commaIdx >= 0 ? text.substring(commaIdx + 1).trim() : null;

                attrs = new HashMap<>();
                final Matcher m = ATTR.matcher(header);
                while (m.find()) attrs.put(m.group(1).toLowerCase(), m.group(2));
                continue;
            }

            if (line.charAt(0) == '#') continue;

            // PAYLOAD LINE — FLUSH THE PENDING ENTRY (OR A BARE-URL ONE)
            final Map<String, String> a = attrs != null ? attrs : Map.of();
            final String name = title != null && !title.isEmpty() ? title : a.getOrDefault("tvg-name", line);
            out.add(new Entry(name, line,
                    a.get("tvg-id"),
                    a.get("tvg-name"),
                    a.get("tvg-logo"),
                    a.get("tvg-country"),
                    a.get("group-title"),
                    a.isEmpty() ? Map.of() : Collections.unmodifiableMap(new HashMap<>(a))));
            title = null;
            attrs = null;
        }
        return out;
    }

    public static List<Entry> parse(final InputStream stream) throws IOException {
        try (final var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder(16 * 1024);
            final char[] buf = new char[4096];
            int r;
            while ((r = reader.read(buf)) > 0) sb.append(buf, 0, r);
            return parse(sb.toString());
        }
    }

    /**
     * A single channel entry parsed from an IPTV M3U.
     *
     * @param title       trailing comma-separated label, or {@code tvg-name}/URL when missing
     * @param url         the stream URL
     * @param tvgId       optional {@code tvg-id} attribute (EPG cross-reference)
     * @param tvgName     optional {@code tvg-name} attribute
     * @param tvgLogo     optional {@code tvg-logo} attribute (channel icon URL)
     * @param tvgCountry  optional {@code tvg-country} ISO code
     * @param tvgGroup    optional {@code group-title} attribute (broadcaster/country grouping)
     * @param attrs       unmodifiable view of every raw {@code key="value"} attribute, lower-cased
     */
    public record Entry(String title, String url, String tvgId, String tvgName,
                        String tvgLogo, String tvgCountry, String tvgGroup,
                        Map<String, String> attrs) {
    }
}
