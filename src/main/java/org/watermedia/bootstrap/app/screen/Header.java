package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Font;
import java.util.Locale;

/**
 * The screen header as a retained view — the pixel-exact port of the imperative chrome header: a
 * gradient band closed by the neon rule and its glow, the app banner, the amber separator, the bold
 * uppercase screen name (truncated), an optional subtitle and an optional right-aligned tag. An extra
 * right-slot element can be attached for screen-specific controls (e.g. a reset button).
 */
public final class Header extends Group<Header> {

    /** Fixed header height in pixels. */
    public static final int H = 124;

    private String name = "";
    private String sub;
    private String right;
    private Element<?> extra;

    public Header() {
        this.width = MAX_PARENT;
        this.height = H;
    }

    /** Sets the screen name (rendered bold uppercase). */
    public Header name(final String value) {
        this.name = value == null ? "" : value;
        return this;
    }

    /** Sets the optional subtitle rendered after the name; null or empty hides it. */
    public Header sub(final String value) {
        this.sub = value;
        return this;
    }

    /** Sets the optional right-aligned tag; null or empty hides it. */
    public Header right(final String value) {
        this.right = value;
        return this;
    }

    /** Places an element in the right slot (e.g. a screen action button); null clears the slot. */
    public Header rightSlot(final Element<?> element) {
        if (this.extra != null) this.remove(this.extra);
        this.extra = element;
        if (element != null) this.add(element);
        return this;
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        // SLOT CHILDREN WRAP TO THEIR OWN SIZE; THE HEADER ITSELF IS A FIXED BAND
        for (final Element<?> child: this.children) {
            if (child.visible()) child.measure(innerAvailWidth, innerAvailHeight);
        }
        this.contentWidth = 0;
        this.contentHeight = H;
    }

    @Override
    protected void onLayout() {
        // RIGHT SLOT SITS WHERE THE IMPERATIVE HEADER PLACED ITS RIGHT TAG (32px INSET, y+25)
        for (final Element<?> child: this.children) {
            if (!child.visible()) continue;
            child.layout(this.left + this.measuredWidth - child.measuredWidth() - 32, this.top + 25);
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.ctx == null || this.ctx.text == null) {
            super.onDraw(canvas);
            return;
        }
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        // GRADIENT BAND, NEON RULE + GLOW, BANNER, AMBER SEPARATOR, TITLES
        canvas.gradientV(x, y, w, H,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 0.86f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.55f);
        canvas.lineH(x, y + H - 7, w, AppTheme.NEON, 2f);
        canvas.lineH(x, y + H - 2, w, AppTheme.NEON_DARK, 1f);
        canvas.glow(x, y + H - 8, w, 4, 0f, AppTheme.NEON, 0.16f);

        final int bannerX = x + 18;
        final int bannerW = Math.min(665, Math.max(430, (int) (w * 0.40f)));
        final int actualBannerW = this.bannerWidth(bannerW, 100);
        final int sepX = bannerX + actualBannerW + 14;
        canvas.fill(sepX, y + 38, 4, 42, AppTheme.AMBER);
        canvas.glow(sepX - 1, y + 38, 6, 42, 0f, AppTheme.AMBER, 0.24f);
        this.drawBanner(canvas, bannerX, y + 10, bannerW, 100);

        final int titleX = sepX + 24;
        int rightReserve = this.right != null && !this.right.isEmpty()
                ? this.tagWidth(this.right) + 52
                : 24;
        if (this.extra != null && this.extra.visible()) {
            rightReserve = Math.max(rightReserve, this.extra.measuredWidth() + 52);
        }
        final float titleScale = AppTheme.TEXT_SECTION;
        final float subScale = AppTheme.TEXT_BODY;
        final int titleMaxW = Math.max(120, x + w - titleX - rightReserve);
        final String title = this.ctx.text.truncateToWidth(this.name.toUpperCase(Locale.ROOT), titleMaxW, titleScale, Font.BOLD);
        final int titleBoxY = y + 42;
        final int titleBoxH = 36;
        this.ctx.text.renderBold(title, titleX,
                titleBoxY + Math.max(0, (titleBoxH - this.ctx.text.glyphHeightBold(titleScale)) / 2),
                AppTheme.NEON_LIGHT, titleScale);
        if (this.sub != null && !this.sub.isEmpty()) {
            final int subX = titleX + this.ctx.text.widthBold(title, titleScale) + 18;
            final int subMaxW = Math.max(80, x + w - subX - rightReserve);
            final String subtitle = this.ctx.text.truncateToWidth(this.sub.toUpperCase(Locale.ROOT), subMaxW, subScale);
            this.ctx.text.render(subtitle, subX,
                    titleBoxY + Math.max(0, (titleBoxH - this.ctx.text.glyphHeight(subScale)) / 2),
                    AppTheme.TEXT_FAINT, subScale);
        }
        if (this.right != null && !this.right.isEmpty()) {
            // FILLED OUTLINED CHIP WITH THE UPPERCASE LABEL
            final int tagW = this.tagWidth(this.right);
            final int tagX = x + w - tagW - 32;
            final int tagY = y + 25;
            canvas.fill(tagX, tagY, tagW, 24, AppTheme.alpha(AppTheme.BG_1, 170));
            canvas.stroke(tagX, tagY, tagW, 24, AppTheme.TEXT_SOFT, 1f);
            this.ctx.text.render(this.right.toUpperCase(Locale.ROOT), tagX + 14,
                    tagY + Math.max(0, (24 - this.ctx.text.glyphHeight(AppTheme.TEXT_BODY)) / 2) + 1,
                    AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        }
        super.onDraw(canvas);
    }

    private int tagWidth(final String label) {
        return this.ctx.text.width(label.toUpperCase(Locale.ROOT), AppTheme.TEXT_BODY) + 28;
    }

    private void drawBanner(final Canvas canvas, final int x, final int y, final int maxW, final int maxH) {
        if (this.ctx.assets.bannerId <= 0 || this.ctx.assets.bannerWidth <= 0 || this.ctx.assets.bannerHeight <= 0) return;
        final float scale = Math.min((float) maxW / this.ctx.assets.bannerWidth, (float) maxH / this.ctx.assets.bannerHeight);
        final int w = (int) (this.ctx.assets.bannerWidth * scale);
        final int h = (int) (this.ctx.assets.bannerHeight * scale);
        canvas.image(this.ctx.assets.bannerId, x, y + (maxH - h) / 2f, w, h, null);
    }

    private int bannerWidth(final int maxW, final int maxH) {
        if (this.ctx.assets.bannerId <= 0 || this.ctx.assets.bannerWidth <= 0 || this.ctx.assets.bannerHeight <= 0) return maxW;
        final float scale = Math.min((float) maxW / this.ctx.assets.bannerWidth, (float) maxH / this.ctx.assets.bannerHeight);
        return (int) (this.ctx.assets.bannerWidth * scale);
    }
}
