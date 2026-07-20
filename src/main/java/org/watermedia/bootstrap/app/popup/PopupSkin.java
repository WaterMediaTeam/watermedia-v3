package org.watermedia.bootstrap.app.popup;

import org.watermedia.bootstrap.app.ui.AppTheme;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * Swing look-and-feel skins that mimic the app's blocky/CRT control style — square outlined boxes with a
 * neon border and hover fill, and a scrubber with a dark-blue track, a solid neon fill and a small amber
 * square thumb. Keeps the popups visually close to the in-app player.
 */
final class PopupSkin {
    private PopupSkin() {}

    private static final Color NEON = AppTheme.NEON;
    private static final Color AMBER = AppTheme.AMBER;
    private static final Color TRACK = AppTheme.alpha(AppTheme.NEON_DARK, 90);
    private static final Color BOX = AppTheme.alpha(AppTheme.BG_2, 220);
    private static final Color BOX_HOVER = AppTheme.alpha(AppTheme.NEON, 60);
    private static final int THUMB = 12;
    private static final int TRACK_H = 5;

    /** A flat, square, neon-outlined button in the app style. */
    static JButton button(final String text) {
        final JButton b = new JButton(text);
        prep(b);
        return b;
    }

    /** A flat, square toggle; its border and text go amber while selected (used for LOOP). */
    static JToggleButton toggle(final String text) {
        final JToggleButton b = new JToggleButton(text);
        prep(b);
        return b;
    }

    /** Applies the scrubber UI (dark track, neon fill, amber square thumb) and returns the slider. */
    static JSlider skinSlider(final JSlider s) {
        s.setOpaque(false);
        s.setUI(new ScrubberUI(s));
        s.setPreferredSize(new Dimension(s.getPreferredSize().width, 22));
        return s;
    }

    private static void prep(final AbstractButton b) {
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setForeground(NEON);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
        // ZERO THE DEFAULT ~14px BUTTON MARGIN — IT SQUEEZED THE GLYPH TO "..." AT THIS WIDTH
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        b.setPreferredSize(new Dimension(48, 30));
        b.setUI(new BoxButtonUI());
    }

    // SQUARE OUTLINED BOX WITH A HOVER FILL; THE ACCENT (BORDER) TURNS AMBER WHILE SELECTED
    private static final class BoxButtonUI extends BasicButtonUI {
        @Override
        public void paint(final Graphics g, final JComponent c) {
            final AbstractButton b = (AbstractButton) c;
            final ButtonModel m = b.getModel();
            final int w = c.getWidth();
            final int h = c.getHeight();
            final Graphics2D g2 = (Graphics2D) g;
            g2.setColor(m.isRollover() ? BOX_HOVER : BOX);
            g2.fillRect(0, 0, w, h);
            g2.setColor(m.isSelected() ? AMBER : NEON);
            g2.setStroke(new BasicStroke(m.isRollover() ? 1.7f : 1.5f));
            g2.drawRect(0, 0, w - 1, h - 1);
            super.paint(g, c); // TEXT (USES THE BUTTON'S FOREGROUND)
        }
    }

    // MEDIA SCRUBBER: DARK-BLUE TRACK, SOLID NEON FILL UP TO THE THUMB, SMALL AMBER SQUARE THUMB
    private static final class ScrubberUI extends BasicSliderUI {
        ScrubberUI(final JSlider s) {
            super(s);
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(THUMB, THUMB);
        }

        @Override
        public void paintFocus(final Graphics g) {
            // NO FOCUS RING
        }

        @Override
        public void paintTrack(final Graphics g) {
            final Rectangle t = this.trackRect;
            final int ty = t.y + (t.height - TRACK_H) / 2;
            final int fill = Math.max(0, this.thumbRect.x + this.thumbRect.width / 2 - t.x);
            g.setColor(TRACK);
            g.fillRect(t.x, ty, t.width, TRACK_H);
            g.setColor(NEON);
            g.fillRect(t.x, ty, Math.min(t.width, fill), TRACK_H);
        }

        @Override
        public void paintThumb(final Graphics g) {
            final Rectangle k = this.thumbRect;
            final int x = k.x + (k.width - THUMB) / 2;
            final int y = k.y + (k.height - THUMB) / 2;
            g.setColor(AMBER);
            g.fillRect(x, y, THUMB, THUMB);
        }
    }
}
