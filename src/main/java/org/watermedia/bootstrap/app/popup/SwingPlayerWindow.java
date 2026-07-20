package org.watermedia.bootstrap.app.popup;

import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.tools.ThreadTool;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Supplier;

/**
 * Shared Swing shell for the popped-out players. The AWT and JavaFX popups differ only in their video
 * surface (a {@code BufferedImage} panel vs a {@code JFXPanel}); everything else — the frame, the
 * transport bar, the ticker, keyboard shortcuts and the player lifecycle — lives here. The surface and
 * a per-tick {@code videoRenderer} are injected, so both popups stay in sync and refinements land once.
 */
final class SwingPlayerWindow implements PopupPlayers.Closer {
    private static final Color BAR = AppTheme.BG_2;
    private static final Color BLUE = AppTheme.NEON;
    private static final Color AMBER = AppTheme.AMBER;
    private static final Color SOFT = AppTheme.TEXT_SOFT;
    private static final Image ICON = loadIcon();
    private static final int SEEK_STEP_MS = 5_000;

    private final PopupVideo video; // OWNS THE SURFACE + A FRESH PLAYER-EXCLUSIVE ENGINE PER SOURCE
    private volatile MediaPlayer player;

    private final MRL mrl;
    private final MediaQuality quality;
    private final Supplier<SFXEngine> audio;
    private final int sourceCount;
    private int sourceIndex;

    private final JFrame frame;
    private final JButton playPause;
    private final JToggleButton loop;
    private final JSlider seek;
    private final JSlider volume;
    private final JLabel volLabel;
    private final JLabel time;
    private final Timer ticker;
    private boolean loopOn;
    private boolean muted;
    private boolean programmaticSeek;
    private boolean closed;

    SwingPlayerWindow(final PopupVideo video, final MRL mrl, final String title,
                      final int sourceIndex, final int sourceCount,
                      final MediaQuality quality, final Supplier<SFXEngine> audio) {
        this.video = video;
        this.mrl = mrl;
        this.quality = quality;
        this.audio = audio;
        this.sourceCount = Math.max(1, sourceCount);
        this.sourceIndex = sourceIndex;

        this.playPause = PopupSkin.button("▶");
        this.playPause.setToolTipText("Play / Pause (Space)");
        this.loop = PopupSkin.toggle("↻");
        this.loop.setToolTipText("Loop (L)");
        this.seek = PopupSkin.skinSlider(new JSlider(0, 1000, 0));
        this.volume = PopupSkin.skinSlider(new JSlider(0, 100, 100));
        this.volume.setPreferredSize(new Dimension(90, 22));
        this.time = new JLabel("0:00 / 0:00", SwingConstants.RIGHT);
        this.time.setForeground(SOFT);
        this.volLabel = new JLabel("100%");
        this.volLabel.setForeground(SOFT);
        this.volLabel.setPreferredSize(new Dimension(38, 22));

        this.playPause.addActionListener(e -> togglePlay());
        this.loop.addActionListener(e -> setLoop(this.loop.isSelected()));
        this.seek.addChangeListener(e -> {
            final MediaPlayer p = this.player;
            if (this.programmaticSeek || p == null || p.duration() <= 0) return;
            final long target = p.duration() * this.seek.getValue() / 1000;
            if (this.seek.getValueIsAdjusting()) {
                this.time.setText(PopupPlayers.fmtTime(target) + " / " + PopupPlayers.fmtTime(p.duration())); // PREVIEW
            } else {
                p.seek(target);
            }
        });
        this.volume.addChangeListener(e -> {
            final MediaPlayer p = this.player;
            if (p == null) return;
            if (this.muted && this.volume.getValue() > 0) { this.muted = false; p.mute(false); }
            p.volume(this.volume.getValue());
            updateVolLabel();
        });

        final JPanel left = flow(this.playPause, this.loop);
        final JPanel right = flow(this.time, Box.createHorizontalStrut(12), this.volLabel, this.volume);
        final JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, AppTheme.STROKE),
                BorderFactory.createEmptyBorder(8, 12, 10, 12)));
        controls.setBackground(BAR);
        controls.add(left, BorderLayout.WEST);
        controls.add(this.seek, BorderLayout.CENTER);
        controls.add(right, BorderLayout.EAST);

        this.frame = new JFrame(title == null || title.isBlank() ? "WaterMedia Player" : title);
        this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        if (ICON != null) this.frame.setIconImage(ICON);
        this.frame.getContentPane().setBackground(Color.BLACK);
        this.frame.add(this.video.component(), BorderLayout.CENTER);
        this.frame.add(controls, BorderLayout.SOUTH);
        this.frame.setSize(960, 600);
        this.frame.setLocationRelativeTo(null);
        this.frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                close();
            }
        });
        installShortcuts();

        // EXCLUSIVE: CLOSE ANY OTHER OPEN POPUP, THEN SHOW ABOVE THE APP WINDOW AND TAKE FOCUS
        PopupPlayers.adopt(this);
        this.frame.setAlwaysOnTop(true);
        this.frame.setVisible(true);
        this.frame.toFront();
        this.frame.requestFocus();
        final Timer raise = new Timer(400, e -> this.frame.setAlwaysOnTop(false));
        raise.setRepeats(false);
        raise.start();

        this.ticker = new Timer(33, e -> tick());
        this.ticker.start();

        startPlayer();
    }

    // CREATES AND STARTS THE PLAYER FOR THE CURRENT SOURCE OFF THE EDT (MRL RESOLUTION CAN BLOCK). A FRESH
    // PLAYER-EXCLUSIVE ENGINE IS BUILT PER SOURCE — THE PREVIOUS PLAYER'S release() DESTROYS THE OLD ONE.
    private void startPlayer() {
        final GFXEngine engine = this.video.newEngine();
        ThreadTool.createStarted("Popup-Open", () -> {
            try {
                final MediaPlayer p = MediaAPI.createPlayer(this.mrl, this.sourceIndex, () -> engine, this.audio);
                if (p == null) {
                    WaterMedia.LOGGER.error("Popup player: MediaAPI.createPlayer returned null");
                    return;
                }
                p.quality(this.quality);
                p.repeat(this.loopOn);
                p.start();
                this.player = p;
            } catch (final Throwable t) {
                WaterMedia.LOGGER.error("Popup player: failed to open the media player", t);
            }
        });
    }

    // NEXT / PREV SOURCE — RELEASE THE CURRENT PLAYER AND START THE NEXT ONE ON THE SAME ENGINE/SURFACE
    private void switchSource(final int delta) {
        if (this.closed || this.sourceCount <= 1) return;
        this.sourceIndex = (this.sourceIndex + delta + this.sourceCount) % this.sourceCount;
        final MediaPlayer old = this.player;
        this.player = null;
        if (old != null) {
            old.stop();
            ThreadTool.createStarted("Popup-Release", old::release);
        }
        startPlayer();
    }

    private void tick() {
        this.video.render();
        final MediaPlayer p = this.player;
        if (p == null) return;
        // VIDEO FINISHED AND NOT LOOPING — CLOSE; A STILL IMAGE (duration 0) STAYS OPEN
        if (p.ended() && !this.loopOn && p.duration() > 0) {
            close();
            return;
        }
        final long duration = p.duration();
        if (!this.seek.getValueIsAdjusting()) {
            if (duration > 0) {
                this.programmaticSeek = true;
                this.seek.setValue((int) (p.time() * 1000 / duration));
                this.programmaticSeek = false;
            }
            this.time.setText(PopupPlayers.fmtTime(p.time()) + " / " + PopupPlayers.fmtTime(duration));
        }
        this.playPause.setText(p.playing() ? "❚❚" : "▶");
    }

    private void togglePlay() {
        final MediaPlayer p = this.player;
        if (p == null) return;
        if (p.ended()) { p.seek(0); p.resume(); } // RESTART FROM THE END INSTEAD OF A NO-OP RESUME
        else if (p.playing()) p.pause();
        else p.resume();
    }

    private void setLoop(final boolean on) {
        this.loopOn = on;
        this.loop.setSelected(on);
        this.loop.setForeground(on ? AMBER : BLUE);
        final MediaPlayer p = this.player;
        if (p != null) p.repeat(on);
    }

    private void nudgeSeek(final long deltaMs) {
        final MediaPlayer p = this.player;
        if (p == null || p.duration() <= 0) return;
        p.seek(Math.max(0, Math.min(p.duration(), p.time() + deltaMs)));
    }

    private void bumpVolume(final int delta) {
        this.volume.setValue(Math.max(0, Math.min(100, this.volume.getValue() + delta)));
    }

    private void toggleMute() {
        final MediaPlayer p = this.player;
        if (p == null) return;
        this.muted = !this.muted;
        p.mute(this.muted);
        updateVolLabel();
    }

    private void updateVolLabel() {
        this.volLabel.setText(this.muted ? "MUTE" : this.volume.getValue() + "%");
        this.volLabel.setForeground(this.muted ? AMBER : SOFT);
    }

    // SPACE=play/pause, ←/→=seek ±5s, ↑/↓=volume, M=mute, L=loop — ACTIVE ANYWHERE IN THE WINDOW
    private void installShortcuts() {
        bind("SPACE", this::togglePlay);
        bind("LEFT", () -> nudgeSeek(-SEEK_STEP_MS));
        bind("RIGHT", () -> nudgeSeek(SEEK_STEP_MS));
        bind("UP", () -> bumpVolume(5));
        bind("DOWN", () -> bumpVolume(-5));
        bind("M", this::toggleMute);
        bind("L", () -> setLoop(!this.loopOn));
        bind("N", () -> switchSource(1));
        bind("B", () -> switchSource(-1));
    }

    private void bind(final String key, final Runnable action) {
        final String id = "wm-" + key;
        this.frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), id);
        this.frame.getRootPane().getActionMap().put(id, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }

    @Override
    public void close() {
        javax.swing.SwingUtilities.invokeLater(this::disposeNow);
    }

    private void disposeNow() {
        if (this.closed) return;
        this.closed = true;
        PopupPlayers.forget(this);
        this.ticker.stop();
        final MediaPlayer p = this.player;
        this.player = null;
        if (p != null) {
            p.stop();
            // release() JOINS THE DECODER LIFECYCLE AND BLOCKS ~HUNDREDS OF MS — KEEP IT OFF THE EDT
            ThreadTool.createStarted("Popup-Release", p::release);
        }
        this.frame.dispose();
    }

    private static JPanel flow(final java.awt.Component... items) {
        final JPanel p = new JPanel();
        p.setOpaque(false);
        for (final java.awt.Component c: items) p.add(c);
        return p;
    }

    private static Image loadIcon() {
        try (var in = SwingPlayerWindow.class.getResourceAsStream("/icon.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (final Exception e) {
            WaterMedia.LOGGER.warn("Popup player: failed to load window icon", e);
            return null;
        }
    }
}
