package org.watermedia.bootstrap.app.ui;

import org.lwjgl.system.MemoryUtil;
import org.watermedia.bootstrap.app.render.RenderSystem;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles text rendering with per-size and per-weight glyph texture caching.
 */
public final class TextRenderer {

    private static final int DEFAULT_FONT_SIZE = 20;
    private static final int LINE_MARGIN = 5;
    private static final int GLYPH_PAD = 3;
    private static final float DEFAULT_TRACKING = 1f;

    private final Map<AtlasKey, FontAtlas> atlases = new HashMap<>();
    private final Font plainFont;
    private final Font boldFont;
    private final int defaultStyle;
    private final int baseSize;
    private int margin = LINE_MARGIN;

    public TextRenderer() {
        this(loadBundledFont(Font.PLAIN), loadBundledFont(Font.BOLD), Font.PLAIN, DEFAULT_FONT_SIZE);
    }

    public TextRenderer(final String fontName, final int style, final int size) {
        this(new Font(fontName, Font.PLAIN, 1), new Font(fontName, Font.BOLD, 1), style, Math.max(1, size));
    }

    private TextRenderer(final Font plainFont, final Font boldFont, final int defaultStyle, final int baseSize) {
        this.plainFont = plainFont;
        this.boldFont = boldFont;
        this.defaultStyle = defaultStyle == Font.BOLD ? Font.BOLD : Font.PLAIN;
        this.baseSize = Math.max(1, baseSize);
    }

    private static Font loadBundledFont(final int style) {
        final String resource = style == Font.BOLD ? "assets/jetbrainsmono-bld.ttf" : "assets/jetbrainsmono-reg.ttf";
        try (final InputStream in = TextRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.PLAIN, 1f);
            }
        } catch (final Exception ignored) {
        }
        return new Font("Consolas", style, 1);
    }

    public void margin(final int margin) {
        this.margin = Math.max(0, margin);
    }

    public int lineHeight() {
        return this.lineHeight(1f);
    }

    public int lineHeight(final float scale) {
        return this.glyphHeight(scale) + this.margin;
    }

    public int glyphHeight() {
        return this.glyphHeight(1f);
    }

    public int glyphHeight(final float scale) {
        final FontRun run = this.fontRun(scale, this.defaultStyle);
        return Math.max(1, Math.round(run.atlas.glyphHeight * run.drawScale));
    }

    public int glyphHeightBold(final float scale) {
        final FontRun run = this.fontRun(scale, Font.BOLD);
        return Math.max(1, Math.round(run.atlas.glyphHeight * run.drawScale));
    }

    public void render(final String text, final int x, final int y, final Color color) {
        this.render(text, (float) x, (float) y, color, 1f, this.defaultStyle);
    }

    public void render(final String text, final float x, final float y, final Color color, final float scale) {
        this.render(text, x, y, color, scale, this.defaultStyle);
    }

    public void renderBold(final String text, final float x, final float y, final Color color, final float scale) {
        this.render(text, x, y, color, scale, Font.BOLD);
    }

    public void render(final String text, final float x, final float y, final Color color, final float scale, final int style) {
        if (text == null || text.isEmpty()) return;
        RenderSystem.color(color);

        final FontRun run = this.fontRun(scale, style);
        float currentX = x;
        final char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final CharTexture ct = run.atlas.getOrCreate(chars[i]);
            if (ct.textureId > 0 && ct.width > 0 && ct.height > 0) {
                RenderSystem.bindTexture(ct.textureId);
                RenderSystem.blit(
                        currentX + ct.offsetX * run.drawScale,
                        y + ct.offsetY * run.drawScale,
                        ct.width * run.drawScale,
                        ct.height * run.drawScale
                );
            }
            currentX += (ct.advance + (i < chars.length - 1 ? DEFAULT_TRACKING : 0f)) * run.drawScale;
        }
    }

    public int width(final String text) {
        return this.width(text, 1f, this.defaultStyle);
    }

    public int width(final String text, final float scale) {
        return this.width(text, scale, this.defaultStyle);
    }

    public int widthBold(final String text, final float scale) {
        return this.width(text, scale, Font.BOLD);
    }

    public int width(final String text, final float scale, final int style) {
        if (text == null || text.isEmpty()) return 0;
        final FontRun run = this.fontRun(scale, style);
        float width = 0f;
        final char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final CharTexture ct = run.atlas.getOrCreate(chars[i]);
            width += (ct.advance + (i < chars.length - 1 ? DEFAULT_TRACKING : 0f)) * run.drawScale;
        }
        return (int) Math.ceil(width);
    }

    public String truncate(final String text, final int maxLen) {
        if (text == null || text.isEmpty()) return "Unknown";
        return text.length() > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
    }

    public String truncateToWidth(final String text, final int maxWidth) {
        return this.truncateToWidth(text, maxWidth, 1f, this.defaultStyle);
    }

    public String truncateToWidth(final String text, final int maxWidth, final float scale) {
        return this.truncateToWidth(text, maxWidth, scale, this.defaultStyle);
    }

    public String truncateToWidth(final String text, final int maxWidth, final float scale, final int style) {
        if (text == null || text.isEmpty()) return "";
        if (this.width(text, scale, style) <= maxWidth) return text;

        final String ellipsis = "...";
        final int ellipsisW = this.width(ellipsis, scale, style);
        if (maxWidth <= ellipsisW) return "";

        final StringBuilder sb = new StringBuilder();
        int currentW = 0;
        final char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final int advance = this.width(String.valueOf(chars[i]), scale, style);
            if (currentW + advance + ellipsisW > maxWidth) break;
            sb.append(chars[i]);
            currentW += advance;
        }
        return sb + ellipsis;
    }

    public String padOrTruncate(final String s, final int len) {
        if (s == null) return String.format("%-" + len + "s", "");
        return s.length() > len ? s.substring(0, len - 3) + "..." : String.format("%-" + len + "s", s);
    }

    private FontRun fontRun(final float scale, final int style) {
        final int targetSize = Math.max(1, Math.round(this.baseSize * Math.max(0.05f, scale)));
        final int atlasSize = targetSize % 2 == 0 ? targetSize + 1 : targetSize;
        final FontAtlas atlas = this.atlas(style == Font.BOLD ? Font.BOLD : Font.PLAIN, atlasSize);
        return new FontRun(atlas, targetSize / (float) atlasSize);
    }

    private FontAtlas atlas(final int style, final int size) {
        final AtlasKey key = new AtlasKey(style, size);
        FontAtlas atlas = this.atlases.get(key);
        if (atlas == null) {
            atlas = new FontAtlas((style == Font.BOLD ? this.boldFont : this.plainFont).deriveFont((float) size));
            this.atlases.put(key, atlas);
        }
        return atlas;
    }

    private static Graphics2D textGraphics(final BufferedImage img, final Font font) {
        final Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g2d.setFont(font);
        g2d.setColor(Color.WHITE);
        return g2d;
    }

    private static int alpha(final BufferedImage image, final int x, final int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }

    private record AtlasKey(int style, int size) {
    }

    private record FontRun(FontAtlas atlas, float drawScale) {
    }

    private static final class FontAtlas {
        private static final String SAMPLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789[]{}()/_-|";

        private final Map<Character, CharTexture> chars = new HashMap<>();
        private final Font font;
        private final FontMetrics metrics;
        private final int fontTop;
        private final int baseline;
        private final int glyphHeight;

        private FontAtlas(final Font font) {
            this.font = font;

            final BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = textGraphics(metricsImage, font);
            this.metrics = g2d.getFontMetrics(font);
            g2d.dispose();

            final int sampleW = Math.max(64, this.metrics.stringWidth(SAMPLE) + GLYPH_PAD * 2);
            final int sampleH = Math.max(8, this.metrics.getHeight() + GLYPH_PAD * 2);
            this.baseline = GLYPH_PAD + this.metrics.getAscent();

            final BufferedImage sample = new BufferedImage(sampleW, sampleH, BufferedImage.TYPE_INT_ARGB);
            g2d = textGraphics(sample, font);
            g2d.drawString(SAMPLE, GLYPH_PAD, this.baseline);
            g2d.dispose();

            int top = sampleH;
            int bottom = -1;
            for (int y = 0; y < sampleH; y++) {
                for (int x = 0; x < sampleW; x++) {
                    if (alpha(sample, x, y) == 0) continue;
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }

            if (bottom < top) {
                top = GLYPH_PAD;
                bottom = GLYPH_PAD + this.metrics.getAscent();
            }
            this.fontTop = top;
            this.glyphHeight = Math.max(1, bottom - top + 1);
        }

        private CharTexture getOrCreate(final char c) {
            CharTexture cached = this.chars.get(c);
            if (cached != null) return cached;

            final int advance = Math.max(1, this.metrics.charWidth(c));
            if (Character.isWhitespace(c)) {
                cached = new CharTexture(0, 0, 0, 0, 0, advance);
                this.chars.put(c, cached);
                return cached;
            }

            final int imgW = Math.max(1, advance + GLYPH_PAD * 2);
            final int imgH = Math.max(8, this.metrics.getHeight() + GLYPH_PAD * 2);
            final BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g2d = textGraphics(image, this.font);
            g2d.drawString(String.valueOf(c), GLYPH_PAD, this.baseline);
            g2d.dispose();

            int left = imgW;
            int right = -1;
            int top = imgH;
            int bottom = -1;
            for (int y = 0; y < imgH; y++) {
                for (int x = 0; x < imgW; x++) {
                    if (alpha(image, x, y) == 0) continue;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }

            if (right < left || bottom < top) {
                cached = new CharTexture(0, 0, 0, 0, 0, advance);
                this.chars.put(c, cached);
                return cached;
            }

            final int w = right - left + 1;
            final int h = bottom - top + 1;
            final int[] pixels = new int[w * h];
            image.getRGB(left, top, w, h, pixels, 0, w);

            final ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
            for (final int pixel : pixels) {
                buffer.put((byte) ((pixel >> 16) & 0xFF));
                buffer.put((byte) ((pixel >> 8) & 0xFF));
                buffer.put((byte) (pixel & 0xFF));
                buffer.put((byte) ((pixel >> 24) & 0xFF));
            }
            buffer.flip();

            final int textureId = RenderSystem.createTexture(w, h, buffer);
            MemoryUtil.memFree(buffer);

            cached = new CharTexture(textureId, w, h, left - GLYPH_PAD, top - this.fontTop, advance);
            this.chars.put(c, cached);
            return cached;
        }
    }

    private record CharTexture(int textureId, int width, int height, int offsetX, int offsetY, int advance) {
    }
}
