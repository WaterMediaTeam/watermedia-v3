package org.watermedia.api.util;

import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;

import java.net.URI;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Insertion-ordered, case-insensitive, multi-valued HTTP header bag.
 * <p>
 * Used by {@link NetRequest} for both the headers we send and the headers we receive.
 * {@link #toRawString()} produces the {@code "Name: Value\r\n..."} form expected by raw
 * consumers like FFmpeg's {@code headers} option.
 */
public final class RequestHeaders implements Iterable<RequestHeaders.Entry> {

    public record Entry(String name, String value) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public RequestHeaders() {}

    public RequestHeaders(final RequestHeaders other) {
        if (other != null) this.entries.addAll(other.entries);
    }

    /**
     * Default header bag for outbound platform requests: WaterMedia User-Agent, a wide
     * media-friendly Accept, and a Referer derived from {@code mrlUri}'s host. This is the
     * blob that used to be hardcoded in {@code FFMediaPlayer}; platforms now stamp it onto
     * each {@link MRL.Source} they emit.
     */
    public static RequestHeaders defaults(final URI mrlUri) {
        final RequestHeaders h = new RequestHeaders()
                .set("User-Agent", WaterMedia.USER_AGENT)
                .set("Accept", "video/*,audio/*,image/*,application/vnd.apple.mpegurl,application/x-mpegurl,application/dash+xml,application/ogg,*/*;q=0.8");
        if (mrlUri != null && mrlUri.getHost() != null) {
            h.set("Referer", mrlUri.getScheme() + "://" + mrlUri.getHost() + "/");
        }
        return h;
    }

    /**
     * Replaces all values for {@code name} with a single entry. Case-insensitive match.
     */
    public RequestHeaders set(final String name, final String value) {
        this.removeAll(name);
        this.entries.add(new Entry(name, value));
        return this;
    }

    /**
     * Appends a new value for {@code name}. Existing values are kept.
     */
    public RequestHeaders add(final String name, final String value) {
        this.entries.add(new Entry(name, value));
        return this;
    }

    /**
     * Removes every entry whose name matches (case-insensitive).
     */
    public RequestHeaders removeAll(final String name) {
        final Iterator<Entry> it = this.entries.iterator();
        while (it.hasNext()) {
            if (eq(it.next().name, name)) it.remove();
        }
        return this;
    }

    /**
     * First value for {@code name}, or {@code null} if none.
     */
    public String get(final String name) {
        for (final Entry e: this.entries) {
            if (eq(e.name, name)) return e.value;
        }
        return null;
    }

    /**
     * All values for {@code name}, in insertion order.
     */
    public List<String> getAll(final String name) {
        final List<String> out = new ArrayList<>();
        for (final Entry e: this.entries) {
            if (eq(e.name, name)) out.add(e.value);
        }
        return out;
    }

    public boolean has(final String name) {
        for (final Entry e: this.entries) {
            if (eq(e.name, name)) return true;
        }
        return false;
    }

    public boolean isEmpty() { return this.entries.isEmpty(); }

    public int size() { return this.entries.size(); }

    public List<Entry> entries() { return List.copyOf(this.entries); }

    /**
     * Applies every entry to {@code conn} via {@link URLConnection#setRequestProperty(String, String)}
     * for the first occurrence of each name and {@link URLConnection#addRequestProperty(String, String)}
     * for subsequent ones, preserving multi-valued headers.
     */
    void writeTo(final URLConnection conn) {
        final List<String> seen = new ArrayList<>();
        for (final Entry e: this.entries) {
            boolean first = true;
            for (final String s: seen) {
                if (eq(s, e.name)) { first = false; break; }
            }
            if (first) {
                conn.setRequestProperty(e.name, e.value);
                seen.add(e.name);
            } else {
                conn.addRequestProperty(e.name, e.value);
            }
        }
    }

    /**
     * Snapshots all response headers exposed by {@code conn}. The pseudo-header at index 0
     * (the HTTP status line) is dropped — only real {@code Name: Value} pairs are kept.
     */
    static RequestHeaders fromResponse(final URLConnection conn) {
        final RequestHeaders out = new RequestHeaders();
        final Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) return out;
        for (final var entry: fields.entrySet()) {
            final String name = entry.getKey();
            if (name == null) continue; // status line — no header name
            for (final String value: entry.getValue()) {
                if (value != null) out.add(name, value);
            }
        }
        return out;
    }

    /**
     * Serializes the headers as {@code "Name: Value\n"} pairs, terminated by {@code CRLF}.
     * Empty when there are no entries. Suitable for FFmpeg's {@code -headers} option.
     */
    public String toRawString() {
        if (this.entries.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder(this.entries.size() * 32);
        for (final Entry e: this.entries) {
            sb.append(e.name).append(": ").append(e.value).append("\n");
        }
        return sb.toString();
    }

    @Override
    public Iterator<Entry> iterator() { return this.entries().iterator(); }

    @Override
    public String toString() { return this.toRawString(); }

    private static boolean eq(final String a, final String b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
