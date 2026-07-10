package org.watermedia.bootstrap.app.screen;

/**
 * A single footer shortcut: the key cap text (empty for a bare hint) and its label.
 *
 * @param key   the shortcut key text (e.g. {@code "ESC"}); empty when the entry is a plain hint
 * @param label the human-readable action label
 */
public record Keybind(String key, String label) {
}
