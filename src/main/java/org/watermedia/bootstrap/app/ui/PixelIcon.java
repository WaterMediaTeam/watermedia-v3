package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;
import java.util.Map;

/**
 * Tiny raster-style icon renderer. Icons are built from fixed pixel masks, not SVG.
 */
public final class PixelIcon {

    private static final Map<String, String[]> ICONS = Map.ofEntries(
            Map.entry("play", new String[]{"1000", "1100", "1110", "1111", "1110", "1100", "1000"}),
            Map.entry("pause", new String[]{"11011", "11011", "11011", "11011", "11011"}),
            Map.entry("stop", new String[]{"11111", "11111", "11111", "11111", "11111"}),
            Map.entry("folder", new String[]{"110000", "111111", "100001", "100001", "111111"}),
            Map.entry("tv", new String[]{"1000001", "0100010", "0010100", "0111110", "0100010", "0100010", "0111110", "0011100"}),
            Map.entry("upload", new String[]{"001100", "011110", "110011", "001100", "001100", "001100"}),
            Map.entry("broom", new String[]{"00010", "00100", "01110", "11111", "10101", "10101"}),
            Map.entry("x", new String[]{"10001", "01010", "00100", "01010", "10001"}),
            Map.entry("link", new String[]{
                    "00001110000",
                    "00010001000",
                    "00100001000",
                    "00100110000",
                    "00011000000",
                    "00000110000",
                    "00011001000",
                    "00100001000",
                    "00100010000",
                    "00011100000"
            }),
            Map.entry("search", new String[]{"0110", "1001", "1001", "0110", "0011"}),
            Map.entry("settings", new String[]{"01010", "11111", "01110", "11111", "01010"}),
            Map.entry("warn", new String[]{"00100", "01010", "10001", "11111", "00100"}),
            Map.entry("info", new String[]{"010", "000", "010", "010", "010"}),
            Map.entry("copy", new String[]{"01110", "01010", "11110", "10100", "11100"}),
            Map.entry("save", new String[]{"111111", "101101", "101101", "111111", "100001", "101101", "111111"}),
            Map.entry("debug", new String[]{"1110", "1010", "1110", "0100", "1110"}),
            Map.entry("speaker", new String[]{"00110", "01110", "11110", "01110", "00110"}),
            Map.entry("repeat", new String[]{"011110", "010010", "110011", "000010", "100011", "010010", "011110"}),
            Map.entry("next", new String[]{"1001", "1101", "1111", "1101", "1001"}),
            Map.entry("prev", new String[]{"1001", "1011", "1111", "1011", "1001"}),
            Map.entry("forward", new String[]{"100100", "110110", "111111", "110110", "100100"}),
            Map.entry("rewind", new String[]{"001001", "011011", "111111", "011011", "001001"}),
            Map.entry("reload", new String[]{"0011100", "0100010", "1000001", "1000001", "0000011", "0000101", "0000011"}),
            Map.entry("arrow-left", new String[]{"0010", "0110", "1111", "0110", "0010"}),
            Map.entry("arrow-right", new String[]{"0100", "0110", "1111", "0110", "0100"}),
            Map.entry("check", new String[]{"00001", "00010", "10100", "01000", "00000"}),
            Map.entry("arrows", new String[]{"00100", "01110", "10101", "01110", "00100"})
    );

    private PixelIcon() {
    }

    public static void draw(final String name, final int x, final int y, final int size, final Color color) {
        final String[] mask = ICONS.getOrDefault(name, ICONS.get("info"));
        final int rows = mask.length;
        final int cols = mask[0].length();
        final int cell = Math.max(1, size / Math.max(rows, cols));
        final int ox = x + Math.max(0, (size - cols * cell) / 2);
        final int oy = y + Math.max(0, (size - rows * cell) / 2);
        for (int row = 0; row < rows; row++) {
            final String bits = mask[row];
            for (int col = 0; col < cols; col++) {
                if (bits.charAt(col) == '1') {
                    RenderSystem.fill(ox + col * cell, oy + row * cell, cell, cell, color);
                }
            }
        }
    }
}
