package org.watermedia.bootstrap.app.popup;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.JFXEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.app.AppContext;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.util.function.Supplier;

/**
 * JavaFX showcase popup: supplies a {@link JfxVideo} (a {@link JFXEngine} whose {@code PixelBuffer} image
 * binds to a JavaFX {@link ImageView} inside a {@link JFXPanel}) to the shared {@link SwingPlayerWindow}.
 * A fresh engine is built per source.
 * <p>
 * JavaFX is forced onto its <b>software</b> Prism pipeline: its hardware (Direct3D) renderer does not
 * repaint when hosted alongside the app's GLFW GL/VK context, and the JFXPanel embeds the scene through
 * the (working) AWT EDT. The per-tick render binds the image and pumps a repaint so still images stay on
 * screen under the event-driven software pulse.
 */
public final class JFXPlayerWindow {
    static {
        // MUST BE SET BEFORE THE FX TOOLKIT INITIALIZES (THE FIRST JFXPanel BELOW) — SEE THE CLASS JAVADOC
        if (System.getProperty("prism.order") == null) System.setProperty("prism.order", "sw");
    }

    private JFXPlayerWindow() {}

    /** Opens a popup player for the app's currently selected MRL, replacing any open popup. */
    public static void open(final AppContext ctx) {
        final MRL mrl = ctx.selectedMRL;
        if (mrl == null) return;
        final String title = ctx.selectedMRLName;
        final int sourceIndex = ctx.sourceSelectorIndex;
        final int sourceCount = ctx.availableSources != null ? ctx.availableSources.length : 1;
        final MediaQuality quality = ctx.selectedQuality;
        final Supplier<SFXEngine> audio = ctx.audioEngine.supplier();
        SwingUtilities.invokeLater(() ->
                new SwingPlayerWindow(new JfxVideo(), mrl, title, sourceIndex, sourceCount, quality, audio));
    }

    // A JFXPanel SURFACE WITH A FRESH JFXEngine PER SOURCE; render() BINDS THE CURRENT ENGINE'S IMAGE ON THE
    // FX THREAD AND PUMPS A REPAINT SO STILL IMAGES STAY VISIBLE UNDER THE EVENT-DRIVEN SOFTWARE PULSE.
    private static final class JfxVideo implements PopupVideo {
        private volatile JFXEngine engine;
        private final JFXPanel panel = new JFXPanel();
        private final ImageView[] view = {null};
        private int throttle;
        private boolean loggedBind;

        JfxVideo() {
            this.panel.setBackground(Color.BLACK);
            Platform.runLater(() -> {
                Platform.setImplicitExit(false); // CLOSING A POPUP MUST NOT SHUT THE SHARED FX TOOLKIT DOWN
                final ImageView iv = new ImageView();
                iv.setPreserveRatio(true);
                final StackPane root = new StackPane(iv);
                root.setStyle("-fx-background-color:black;");
                iv.fitWidthProperty().bind(root.widthProperty());
                iv.fitHeightProperty().bind(root.heightProperty());
                this.view[0] = iv;
                this.panel.setScene(new Scene(root));
            });
        }

        @Override
        public GFXEngine newEngine() {
            this.loggedBind = false;
            return this.engine = new JFXEngine.Builder().build();
        }

        @Override
        public JComponent component() {
            return this.panel;
        }

        @Override
        public void render() {
            final JFXEngine eng = this.engine;
            if (eng == null) return;
            Platform.runLater(() -> {
                final ImageView iv = this.view[0];
                final Image img = eng.image();
                if (iv != null && img != null && iv.getImage() != img) {
                    iv.setImage(img);
                    if (!this.loggedBind) {
                        this.loggedBind = true;
                        WaterMedia.LOGGER.info("JavaFX popup: bound video image {}x{}", (int) img.getWidth(), (int) img.getHeight());
                    }
                }
            });
            // PUMP A REPAINT (~10fps) SO A STATIC IMAGE KEEPS RENDERING UNDER THE EVENT-DRIVEN SW PULSE
            if (++this.throttle >= 3) {
                this.throttle = 0;
                eng.repaint();
            }
        }
    }
}
