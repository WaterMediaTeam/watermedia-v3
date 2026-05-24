package org.watermedia.api.codecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized image metadata shared by every image decoder.
 *
 * <p>Common fields get explicit accessors. Format-specific, uncommon, private, or non-standard
 * metadata is exposed through {@link #values()} using keys defined in {@link CodecsAPI}.
 * Accessors return {@code null} when the value does not exist or is blank.
 */
public class ImageMetadata {
    public static final ImageMetadata EMPTY = new ImageMetadata(true);

    private final boolean readOnly;
    private String title;
    private String description;
    private final List<String> authors = new ArrayList<>();
    private String copyright;
    private final List<String> comments = new ArrayList<>();
    private String creationTime;
    private String software;
    private String source;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public ImageMetadata() {
        this(false);
    }

    private ImageMetadata(final boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String title() {
        return clean(this.title);
    }

    public String description() {
        return clean(this.description);
    }

    public List<String> authors() {
        return this.authors.isEmpty() ? null : Collections.unmodifiableList(this.authors);
    }

    public String copyright() {
        return clean(this.copyright);
    }

    public List<String> comments() {
        return this.comments.isEmpty() ? null : Collections.unmodifiableList(this.comments);
    }

    public String creationTime() {
        return clean(this.creationTime);
    }

    public String software() {
        return clean(this.software);
    }

    public String source() {
        return clean(this.source);
    }

    public Map<String, Object> values() {
        return this.values.isEmpty() ? null : Collections.unmodifiableMap(this.values);
    }

    public Object value(final String key) {
        return key == null || key.isBlank() ? null : this.values.get(key);
    }

    public boolean empty() {
        return this.title() == null
                && this.description() == null
                && this.authors.isEmpty()
                && this.copyright() == null
                && this.comments.isEmpty()
                && this.creationTime() == null
                && this.software() == null
                && this.source() == null
                && this.values.isEmpty();
    }

    public ImageMetadata title(final String value) {
        this.checkMutable();
        this.title = clean(value);
        return this;
    }

    public ImageMetadata description(final String value) {
        this.checkMutable();
        this.description = clean(value);
        return this;
    }

    public ImageMetadata author(final String value) {
        this.checkMutable();
        final String clean = clean(value);
        if (clean != null) this.authors.add(clean);
        return this;
    }

    public ImageMetadata copyright(final String value) {
        this.checkMutable();
        this.copyright = clean(value);
        return this;
    }

    public ImageMetadata comment(final String value) {
        this.checkMutable();
        final String clean = clean(value);
        if (clean != null) this.comments.add(clean);
        return this;
    }

    public ImageMetadata creationTime(final String value) {
        this.checkMutable();
        this.creationTime = clean(value);
        return this;
    }

    public ImageMetadata software(final String value) {
        this.checkMutable();
        this.software = clean(value);
        return this;
    }

    public ImageMetadata source(final String value) {
        this.checkMutable();
        this.source = clean(value);
        return this;
    }

    public ImageMetadata put(final String key, final Object value) {
        this.checkMutable();
        if (key == null || key.isBlank() || value == null) return this;
        if (value instanceof final String text && clean(text) == null) return this;
        if (value instanceof final byte[] bytes && bytes.length == 0) return this;
        if (value instanceof final List<?> list && list.isEmpty()) return this;
        if (value instanceof final Map<?, ?> map && map.isEmpty()) return this;
        this.values.put(key, value);
        return this;
    }

    private void checkMutable() {
        if (this.readOnly) throw new UnsupportedOperationException("ImageMetadata.EMPTY is read-only");
    }

    private static String clean(final String value) {
        if (value == null) return null;
        final String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }
}
