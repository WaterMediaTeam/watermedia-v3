package org.watermedia.api.codecs.readers.svg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A parsed SVG element: its local tag name, the merged attribute map (presentation attributes with
 * the inline {@code style=""} declarations overlaid on top), and child elements. Deliberately
 * generic — the renderer dispatches on {@link #tag()} rather than using one class per element.
 *
 * @param tag      local element name (no namespace prefix)
 * @param attrs    merged attribute map (inline style overlaid on presentation attributes)
 * @param children child elements, populated during parsing
 */
record SVGNode(String tag, Map<String, String> attrs, List<SVGNode> children) {

    SVGNode(final String tag, final Map<String, String> attrs) {
        this(tag, attrs, new ArrayList<>());
    }

    String attr(final String name) {
        return this.attrs.get(name);
    }
}
