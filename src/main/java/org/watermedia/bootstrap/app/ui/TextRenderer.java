package org.watermedia.bootstrap.app.ui;

import org.lwjgl.system.MemoryUtil;
import org.watermedia.tools.DrawTool;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Handles text rendering with character texture caching.
 */
public final class TextRenderer {

    private static final int DEFAULT_FONT_SIZE = 24;
    private static final int LINE_MARGIN = 6;

    private final Map<Character, CharTexture> charCache = new HashMap<>();
    private final Font font;
    private int lineHeight;
    private int margin = LINE_MARGIN;

    public TextRenderer() {
        this("Consolas", Font.PLAIN, DEFAULT_FONT_SIZE);
    }

    public TextRenderer(final String fontName, final int style, final int size) {
        this.font = new Font(fontName, style, size);
        this.recalculateLineHeight();
    }

    public void margin(final int margin) {
        this.margin = margin;
        this.recalculateLineHeight();
    }

    public int lineHeight() {
        return this.lineHeight;
    }

    private void recalculateLineHeight() {
        final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        final Graphics2D g2d = img.createGraphics();
        g2d.setFont(this.font);
        final Rectangle2D bounds = this.font.getStringBounds("Ayg|", g2d.getFontRenderContext());
        this.lineHeight = (int) Math.ceil(bounds.getHeight()) + this.margin;
        g2d.dispose();
    }

    public void render(final String text, final int x, final int y, final Color color) {
        if (text == null || text.isEmpty()) return;
        DrawTool.color(color);

        int currentX = x;
        for (final char c: text.toCharArray()) {
            final CharTexture ct = this.getOrCreateCharTexture(c);
            if (ct == null) continue;
            DrawTool.bindTexture(ct.textureId);
            DrawTool.blit(currentX, y, ct.width, ct.height);
            currentX += ct.width;
        }
    }

    public void render(final String text, final float x, final float y, final Color color, final float scale) {
        if (text == null || text.isEmpty()) return;
        DrawTool.color(color);

        float currentX = x;
        for (final char c: text.toCharArray()) {
            final CharTexture ct = this.getOrCreateCharTexture(c);
            if (ct == null) continue;
            DrawTool.bindTexture(ct.textureId);
            DrawTool.blit(currentX, y, ct.width * scale, ct.height * scale);
            currentX += ct.width * scale;
        }
    }

    public int width(final String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (final char c: text.toCharArray()) {
            final CharTexture ct = this.getOrCreateCharTexture(c);
            if (ct != null) width += ct.width;
        }
        return width;
    }

    public String truncate(final String text, final int maxLen) {
        if (text == null || text.isEmpty()) return "Unknown";
        return text.length() > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
    }

    public String truncateToWidth(final String text, final int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (this.width(text) <= maxWidth) return text;

        final String ellipsis = "...";
        final int ellipsisW = this.width(ellipsis);
        if (maxWidth <= ellipsisW) return "";

        final StringBuilder sb = new StringBuilder();
        int currentW = 0;
        for (final char c: text.toCharArray()) {
            final CharTexture ct = this.getOrCreateCharTexture(c);
            if (ct == null) continue;
            if (currentW + ct.width + ellipsisW > maxWidth) break;
            sb.append(c);
            currentW += ct.width;
        }
        return sb + ellipsis;
    }

    public String padOrTruncate(final String s, final int len) {
        if (s == null) return String.format("%-" + len + "s", "");
        return s.length() > len ? s.substring(0, len - 3) + "..." : String.format("%-" + len + "s", s);
    }

    private CharTexture getOrCreateCharTexture(final char c) {
        if (this.charCache.containsKey(c)) return this.charCache.get(c);

        final BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g2d = img.createGraphics();
        g2d.setFont(this.font);
        final Rectangle2D bounds = this.font.getStringBounds(String.valueOf(c), g2d.getFontRenderContext());
        g2d.dispose();

        final int charW = (int) Math.ceil(bounds.getWidth());
        final int charH = (int) Math.ceil(bounds.getHeight());
        if (charW == 0 || charH == 0) return null;

        final BufferedImage charImage = new BufferedImage(charW, charH, BufferedImage.TYPE_INT_ARGB);
        g2d = charImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g2d.setFont(this.font);
        g2d.setColor(Color.WHITE);
        g2d.drawString(String.valueOf(c), -(int) bounds.getX(), -(int) bounds.getY());
        g2d.dispose();

        final int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        final int[] pixels = new int[charW * charH];
        charImage.getRGB(0, 0, charW, charH, pixels, 0, charW);

        final ByteBuffer buffer = MemoryUtil.memAlloc(charW * charH * 4);
        for (int i = 0; i < charH; i++) {
            for (int j = 0; j < charW; j++) {
                final int pixel = pixels[i * charW + j];
                buffer.put((byte) ((pixel >> 16) & 0xFF));
                buffer.put((byte) ((pixel >> 8) & 0xFF));
                buffer.put((byte) (pixel & 0xFF));
                buffer.put((byte) ((pixel >> 24) & 0xFF));
            }
        }
        buffer.flip();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, charW);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, charW, charH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        MemoryUtil.memFree(buffer);

        final CharTexture charTex = new CharTexture(textureId, charW, charH);
        this.charCache.put(c, charTex);
        return charTex;
    }

    private record CharTexture(int textureId, int width, int height) {
    }
}
