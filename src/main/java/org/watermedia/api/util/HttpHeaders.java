package org.watermedia.api.util;

import org.watermedia.WaterMedia;
import org.watermedia.api.platform.Platform;

import java.net.URI;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Insertion-ordered, case-insensitive, multi-valued HTTP header bag.
 * <p>
 * Used by {@link NetRequest} for both the headers we send and the headers we receive.
 * {@link #toRawString()} produces the {@code "Name: Value\r\n..."} form expected by raw
 * consumers like FFmpeg's {@code -headers} option; {@link #toString()} lists header names only.
 * A bag shared across threads (every resolved source carries one) is {@link #freeze() frozen}:
 * its mutators then throw, and {@link #HttpHeaders(HttpHeaders) copying} it yields a mutable bag again.
 */
public final class HttpHeaders implements Iterable<HttpHeaders.Entry> {

    public record Entry(String name, String value) {
        public Entry {
            if (name == null || value == null) throw new IllegalArgumentException("Header name and value are required");
            if (name.isEmpty()) throw new IllegalArgumentException("Header name cannot be empty");
            for (int i = 0; i < name.length(); i++) {
                final char c = name.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && "!#$%&'*+-.^_`|~".indexOf(c) < 0)
                    throw new IllegalArgumentException("Header name must be an HTTP token");
            }
            // RAW NATIVE CONSUMERS REQUIRE THE SAME FIELD BOUNDARIES AS HTTP CONNECTIONS.
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                if ((c < 0x20 && c != '\t') || c == 0x7f)
                    throw new IllegalArgumentException("Header value contains a prohibited control character");
            }
        }

        // NAME ONLY: LOGGED SOURCES MUST NEVER PRINT COOKIE OR AUTHORIZATION VALUES
        @Override
        public String toString() { return this.name; }
    }

    private List<Entry> entries = new ArrayList<>();

    public HttpHeaders() {}

    public HttpHeaders(final HttpHeaders other) {
        if (other != null) this.entries.addAll(other.entries);
    }

    /** Makes this bag immutable: every later mutator throws {@link UnsupportedOperationException}. */
    public HttpHeaders freeze() {
        this.entries = Collections.unmodifiableList(this.entries);
        return this;
    }

    /**
     * Default header bag for outbound platform requests: WaterMedia User-Agent,
     * {@link NetRequest#ACCEPT_MEDIA} and a Referer derived from {@code uri}'s origin; platforms
     * stamp it onto each {@link Platform.Source} they emit.
     */
    public static HttpHeaders defaults(final URI uri) {
        final HttpHeaders h = new HttpHeaders().set("User-Agent", WaterMedia.USER_AGENT).set("Accept", NetRequest.ACCEPT_MEDIA);
        final String referer = referer(uri);
        if (referer != null) h.set("Referer", referer);
        return h;
    }

    // ORIGIN-SHAPED REFERER scheme://host[:port]/ KEEPING AN EXPLICIT PORT, OR NULL WITHOUT A HOST
    static String referer(final URI uri) {
        if (uri == null || uri.getHost() == null) return null;
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "") + "/";
    }

    /**
     * Replaces all values for {@code name} with a single entry. Case-insensitive match.
     */
    public HttpHeaders set(final String name, final String value) {
        final Entry entry = new Entry(name, value);
        this.removeAll(name);
        this.entries.add(entry);
        return this;
    }

    /**
     * Appends a new value for {@code name}. Existing values are kept.
     */
    public HttpHeaders add(final String name, final String value) {
        this.entries.add(new Entry(name, value));
        return this;
    }

    /**
     * Applies {@code other} on top of this bag: the first occurrence of each name replaces any
     * existing entries (like {@link #set}), later occurrences append (like {@link #add}),
     * preserving multi-valued headers. A {@code null} or empty bag, or this same bag, is a no-op.
     */
    public HttpHeaders merge(final HttpHeaders other) {
        if (other == null || other == this || other.empty()) return this;
        final Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final Entry e: other.entries) {
            if (seen.add(e.name)) this.set(e.name, e.value);
            else this.add(e.name, e.value);
        }
        return this;
    }

    /**
     * Removes every entry whose name matches (case-insensitive).
     */
    public HttpHeaders removeAll(final String name) {
        this.entries.removeIf(entry -> eq(entry.name, name));
        return this;
    }

    /**
     * First value for {@code name}, or {@code null} if none.
     */
    public String value(final String name) {
        for (final Entry e: this.entries) {
            if (eq(e.name, name)) return e.value;
        }
        return null;
    }

    /**
     * All values for {@code name}, in insertion order.
     */
    public List<String> values(final String name) {
        final List<String> out = new ArrayList<>();
        for (final Entry e: this.entries) {
            if (eq(e.name, name)) out.add(e.value);
        }
        return out;
    }

    /**
     * Whether any value exists for {@code name} (case-insensitive).
     */
    public boolean has(final String name) {
        for (final Entry e: this.entries) {
            if (eq(e.name, name)) return true;
        }
        return false;
    }

    /** Whether the bag has no entries. */
    public boolean empty() { return this.entries.isEmpty(); }

    /** Number of stored values, counting repeated names. */
    public int count() { return this.entries.size(); }

    /** Immutable snapshot of every entry, values included, in insertion order. */
    public List<Entry> entries() { return List.copyOf(this.entries); }

    // REPEATED HEADER NAMES USE ADD AFTER THE FIRST VALUE SO NONE ARE LOST.
    void writeTo(final URLConnection conn) {
        final Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final Entry e: this.entries) {
            if (seen.add(e.name)) conn.setRequestProperty(e.name, e.value);
            else conn.addRequestProperty(e.name, e.value);
        }
    }

    // THE STATUS LINE HAS NO HEADER NAME AND IS NOT PART OF THE SNAPSHOT.
    static HttpHeaders fromResponse(final URLConnection conn) {
        final HttpHeaders out = new HttpHeaders();
        final Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) return out;
        for (final var entry: fields.entrySet()) {
            final String name = entry.getKey();
            if (name == null) continue;
            for (final String value: entry.getValue()) {
                if (value != null) out.add(name, value);
            }
        }
        return out;
    }

    /**
     * Serializes the headers as {@code "Name: Value\r\n"} pairs (each terminated by {@code CRLF}).
     * Empty when there are no entries. Suitable for FFmpeg's {@code -headers} option.
     */
    public String toRawString() {
        if (this.entries.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder(this.entries.size() * 32);
        for (final Entry e: this.entries) {
            sb.append(e.name).append(": ").append(e.value).append("\r\n");
        }
        return sb.toString();
    }

    @Override
    public Iterator<Entry> iterator() { return Collections.unmodifiableList(this.entries).iterator(); }

    @Override
    public String toString() { return "HttpHeaders" + this.entries; }

    // CASE-INSENSITIVE HEADER-NAME MATCH; equalsIgnoreCase IS ALLOCATION-FREE UNLIKE toLowerCase().equals(...)
    private static boolean eq(final String a, final String b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }
}
