package org.watermedia.bootstrap.app.ui;

import org.watermedia.bootstrap.app.render.RenderSystem;

/**
 * Text input renderer with placeholder and focus state.
 */
public class TextInputElement extends Element {

    private String value = "";
    private String placeholder = "";
    private int width = 320;
    private int height = 36;

    public TextInputElement value(final String value) {
        this.value = value == null ? "" : value;
        return this;
    }

    public String value() {
        return this.value;
    }

    public TextInputElement placeholder(final String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        return this;
    }

    public TextInputElement size(final int width, final int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public void append(final char c) {
        this.value += c;
    }

    public void backspace() {
        if (!this.value.isEmpty()) {
            this.value = this.value.substring(0, this.value.length() - 1);
        }
    }

    @Override
    public void render(final TextRenderer renderer, final int windowW, final int windowH) {
        RenderSystem.setupOrtho(windowW, windowH);
        if (this.focused) RenderSystem.glowRect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), 0f, AppTheme.NEON, 0.22f);
        RenderSystem.fill(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), AppTheme.BG_2);
        RenderSystem.rect(this.bounds.x(), this.bounds.y(), this.bounds.width(), this.bounds.height(), this.focused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 1f);

        final int textX = this.bounds.x() + 9;
        final int textY = this.bounds.y() + Math.max(0, (this.bounds.height() - renderer.glyphHeight()) / 2);
        final String visibleValue = renderer.truncateToWidth(this.value, this.bounds.width() - 18);
        final String visibleText = this.value.isEmpty()
                ? renderer.truncateToWidth(this.placeholder, this.bounds.width() - 24)
                : visibleValue;
        renderer.render(visibleText,
                textX, textY,
                this.value.isEmpty() ? AppTheme.TEXT_FAINT : AppTheme.TEXT);
        if (this.focused && ((System.currentTimeMillis() / 480L) % 2L) == 0L) {
            final int caretTextW = this.value.isEmpty() ? 0 : renderer.width(visibleValue);
            final int caretX = Math.min(this.bounds.right() - 9, textX + caretTextW + (this.value.isEmpty() ? -5 : 1));
            final int caretY = this.bounds.y() + Math.max(0, (this.bounds.height() - renderer.glyphHeight()) / 2);
            RenderSystem.fill(caretX, caretY, 2, renderer.glyphHeight(), AppTheme.NEON_LIGHT);
        }
        RenderSystem.restoreProjection();
    }

    @Override
    public Dimension calculateBounds(final TextRenderer text, final int startX, final int startY) {
        this.bounds = new Dimension(startX, startY, this.width, this.height);
        return this.bounds;
    }

    @Override
    public boolean handleClick(final double mx, final double my) {
        this.focused = this.contains(mx, my);
        return this.focused;
    }
}
