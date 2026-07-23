package org.watermedia.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;

/**
 * Shared Gson binding, null-safe accessors and lenient URL parsing.
 * One source of truth so a fix (e.g. tolerating a malformed value) lands everywhere at once
 * instead of being copied into each consumer under a different name, and one shared
 * {@link Gson} instance so the whole codebase parses and writes JSON the same way.
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
     * @throws com.google.gson.JsonSyntaxException when the tree does not match {@code type}
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
     * @return the bound instance, or {@code null} when {@code json} is {@code null} or holds only JSON {@code null}
     * @throws com.google.gson.JsonSyntaxException when the text is not valid JSON or does not match {@code type}
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

    /**
     * Returns the string at {@code key}, or {@code null} when the key is absent or JSON {@code null}.
     */
    public static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    /**
     * Returns the double at {@code key}, or {@code 0} when the key is absent or JSON {@code null}.
     */
    public static double dbl(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? 0d : e.getAsDouble();
    }

    /**
     * Returns the boolean at {@code key}, or {@code false} when the key is absent or JSON {@code null}.
     */
    public static boolean bool(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    /**
     * Returns the int at {@code key}, or {@code null} when the key is absent or JSON {@code null}.
     */
    public static Integer intOrNull(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsInt();
    }

    /**
     * Returns the int at {@code key}, or {@code def} when the key is absent or JSON {@code null}.
     */
    public static int intOr(final JsonObject o, final String key, final int def) {
        final Integer v = intOrNull(o, key);
        return v == null ? def : v;
    }

    /**
     * Parses a URL leniently: {@code null}/empty or unparseable input (e.g. illegal characters such as
     * spaces) yields {@code null}, so one bad URL never aborts a whole result set. Protocol-relative URLs
     * ({@code //host/...}) are promoted to {@code https}.
     * @param url the raw URL string
     * @return the parsed URI, or {@code null} if it is missing or malformed
     */
    public static URI uri(final String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            return URI.create(url.startsWith("//") ? "https:" + url : url);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Convenience for {@link #uri(String) uri}({@link #str(JsonObject, String) str}(o, key)).
     * @param o the JSON object
     * @param key the key holding the URL string
     * @return the parsed URI, or {@code null} if absent or malformed
     */
    public static URI uri(final JsonObject o, final String key) {
        return uri(str(o, key));
    }
}
