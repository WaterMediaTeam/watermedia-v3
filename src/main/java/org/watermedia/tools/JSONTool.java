package org.watermedia.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import java.net.URI;

/**
 * Shared Gson binding, lenient accessors and lenient URL parsing.
 * One source of truth so a fix (e.g. tolerating a malformed value) lands everywhere at once
 * instead of being copied into each consumer under a different name, and one shared
 * {@link Gson} instance so the whole codebase parses and writes JSON the same way.
 * <p>
 * The accessors {@link #str}, {@link #num}, {@link #bool}, {@link #intOr} and {@link #longOr} never
 * throw: a missing key, JSON {@code null} or a value of the wrong type (object, array, non-numeric
 * string) yields {@code null} or the default, so a remote API changing a field's type never aborts
 * a whole resolve.
 */
public final class JSONTool {
    private static final Gson GSON = new Gson();

    private JSONTool() {}

    /**
     * Binds a parsed JSON tree to {@code type} through the shared {@link Gson} instance.
     *
     * @param json the JSON tree, may be {@code null}
     * @param type the class to bind to
     * @param <T> the bound type
     * @return the bound instance, or {@code null} when {@code json} is {@code null}
     * @throws JsonSyntaxException when the tree does not match {@code type}
     */
    public static <T> T parse(final JsonElement json, final Class<T> type) {
        return json == null ? null : GSON.fromJson(json, type);
    }

    /**
     * Parses a raw JSON string and binds it to {@code type} through the shared {@link Gson} instance.
     *
     * @param json the raw JSON text, may be {@code null}
     * @param type the class to bind to
     * @param <T> the bound type
     * @return the bound instance, or {@code null} when {@code json} is {@code null}, empty or holds only JSON {@code null}
     * @throws JsonSyntaxException when the text is not valid JSON or does not match {@code type}
     */
    public static <T> T parse(final String json, final Class<T> type) {
        return json == null ? null : GSON.fromJson(json, type);
    }

    /**
     * Serializes {@code value} to its JSON representation through the shared {@link Gson} instance.
     *
     * @param value the value to serialize, may be {@code null}
     * @return the JSON text ({@code "null"} when {@code value} is {@code null})
     */
    public static String write(final Object value) {
        return GSON.toJson(value);
    }

    /** Returns the string, number or boolean at {@code key} as text, or {@code null} when absent, JSON {@code null}, an object or an array. */
    public static String str(final JsonObject o, final String key) {
        final JsonPrimitive p = primitive(o, key);
        return p == null ? null : p.getAsString();
    }

    /** Returns the finite number at {@code key}, numeric strings included, or {@code 0} when absent, JSON {@code null} or not a number. */
    public static double num(final JsonObject o, final String key) {
        final JsonPrimitive p = primitive(o, key);
        if (p == null) return 0d;
        try {
            final double value = p.getAsDouble();
            return Double.isFinite(value) ? value : 0d;
        } catch (final NumberFormatException e) {
            return 0d;
        }
    }

    /** Returns the boolean at {@code key}, {@code "true"} strings included, or {@code false} when absent, JSON {@code null} or not a boolean. */
    public static boolean bool(final JsonObject o, final String key) {
        final JsonPrimitive p = primitive(o, key);
        return p != null && p.getAsBoolean();
    }

    /** Returns the int at {@code key}, numeric strings included, or {@code def} when absent, JSON {@code null} or not a number. */
    public static int intOr(final JsonObject o, final String key, final int def) {
        final JsonPrimitive p = primitive(o, key);
        if (p == null) return def;
        try { return p.getAsInt(); }
        catch (final NumberFormatException e) { return def; }
    }

    /** Returns the long at {@code key}, numeric strings included, or {@code def} when absent, JSON {@code null} or not a number. */
    public static long longOr(final JsonObject o, final String key, final long def) {
        final JsonPrimitive p = primitive(o, key);
        if (p == null) return def;
        try { return p.getAsLong(); }
        catch (final NumberFormatException e) { return def; }
    }

    /**
     * Parses a web URL leniently: {@code null}/empty, unparseable input (e.g. illegal characters such as
     * spaces) or a scheme other than http and https yields {@code null}, so one bad URL never aborts a whole
     * result set and remote data can never point at local resources. Protocol-relative URLs
     * ({@code //host/...}) are promoted to {@code https}.
     * @param uri the raw URI text
     * @return the parsed http or https URI, or {@code null} if it is missing, malformed or not a web URL
     */
    public static URI uri(final String uri) {
        if (uri == null || uri.isEmpty()) return null;
        try {
            final URI parsed = URI.create(uri.startsWith("//") ? "https:" + uri : uri);
            return IOTool.web(parsed) ? parsed : null;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Convenience for {@link #uri(String) uri}({@link #str(JsonObject, String) str}(o, key)).
     * @param o the JSON object
     * @param key the key holding the URL string
     * @return the parsed http or https URI, or {@code null} if absent, malformed or not a web URL
     */
    public static URI uri(final JsonObject o, final String key) {
        return uri(str(o, key));
    }

    // THE ONLY SHAPE THE ACCESSORS READ; A NULL OBJECT, A MISSING KEY, JSON NULL, OBJECTS AND ARRAYS ALL YIELD NULL
    private static JsonPrimitive primitive(final JsonObject o, final String key) {
        final JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
    }
}
