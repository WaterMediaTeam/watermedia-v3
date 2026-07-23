package org.watermedia.bootstrap.app.popup;

import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.AWTEngine;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppTheme;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * AWT showcase popup: supplies an {@link AwtVideo} (an {@link AWTEngine} painting a {@code BufferedImage}
 * onto a Swing panel) to the shared {@link SwingPlayerWindow}. A fresh engine is built per source.
 */
public final class AWTPlayerWindow {
    private AWTPlayerWindow() {}

    /** Opens a popup player for the app's currently selected MRL, replacing any open popup. */
    public static void open(final AppContext ctx) {
        PopupPlayers.open(ctx, AwtVideo::new);
    }

    // A BufferedImage SURFACE WITH A FRESH AWTEngine PER SOURCE (THE PANEL ALWAYS READS THE CURRENT ONE).
    private static final class AwtVideo implements PopupVideo {
        private volatile AWTEngine engine;
        private final VideoPanel panel = new VideoPanel();

        @Override
        public GFXEngine newEngine() {
            return this.engine = MediaAPI.awtEngine(null);
        }

        @Override
        public JComponent component() {
            return this.panel;
        }

        @Override
        public void render() {
            this.panel.repaint();
        }

        // PAINTS THE CURRENT FRAME ASPECT-FIT ON BLACK, OR A STATUS LINE WHILE THERE IS NO FRAME
        private final class VideoPanel extends JComponent {
            VideoPanel() {
                setOpaque(true); // FULLY PAINTS ITS BOUNDS SO repaint() RELIABLY REDRAWS THE FRAME
            }

            @Override
            protected void paintComponent(final Graphics g) {
                final int pw = getWidth();
                final int ph = getHeight();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, pw, ph);

                final AWTEngine e = AwtVideo.this.engine;
                final BufferedImage img = e == null ? null : e.image();
                if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                    g.setColor(AppTheme.TEXT_SOFT);
                    g.setFont(g.getFont().deriveFont(Font.PLAIN, 14f));
                    final String s = "Loading…";
                    g.drawString(s, (pw - g.getFontMetrics().stringWidth(s)) / 2, ph / 2);
                    return;
                }

                final float scale = Math.min((float) pw / img.getWidth(), (float) ph / img.getHeight());
                final int dw = Math.max(1, Math.round(img.getWidth() * scale));
                final int dh = Math.max(1, Math.round(img.getHeight() * scale));
                final Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, (pw - dw) / 2, (ph - dh) / 2, dw, dh, null);
            }
        }
    }
}
