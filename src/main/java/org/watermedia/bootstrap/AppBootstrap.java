package org.watermedia.bootstrap;

import org.watermedia.bootstrap.app.WaterMediaApp;
import org.watermedia.tools.IOTool;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// BOOTSTRAP LAUNCHER FOR WATERMEDIA STANDALONE APPLICATION
// WHY A NEW PROCESS IS REQUIRED:
// THE JVM LOCKS THE CLASSPATH AT STARTUP. URLCLASSLOADER TRICKS BREAK WITH NATIVE LIBS,
// SERVICELOADER (LOG4J SPI), AND SPLIT PACKAGES. SPAWNING A FRESH JVM IS THE ONLY RELIABLE SOLUTION.
public class AppBootstrap {
    private static final Path LIBS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "watermedia/libs");
    private static final String MAVEN = "https://repo1.maven.org/maven2/";
    private static final String BOOTSTRAPPED_FLAG = "watermedia.app";

    // UI CONSTANTS
    private static final Font FONT = new Font("Consolas", Font.PLAIN, 18);
    private static final Font FONT_BOLD = new Font("Consolas", Font.BOLD, 24);
    private static final Color C_BLACK = Color.BLACK, C_WHITE = Color.WHITE;
    private static final Color C_GREEN = new Color(0, 255, 0), C_RED = new Color(255, 100, 100);
    private static final Color C_BLUE = new Color(79, 181, 255), C_BLUE_DARK = new Color(0, 150, 255);
    private static final Color C_GRAY = new Color(80, 80, 80), C_GRAY_DARK = new Color(30, 30, 30);
    private static final int PAD = 15, SCROLL_W = 12;

    // DEPENDENCIES
    private static final String[][] DEPS = {
            {"log4j-api-2.25.0.jar", "org/apache/logging/log4j/log4j-api/2.25.0/log4j-api-2.25.0.jar"},
            {"log4j-core-2.25.0.jar", "org/apache/logging/log4j/log4j-core/2.25.0/log4j-core-2.25.0.jar"},
            {"lwjgl-3.3.6.jar", "org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6.jar"},
            {"lwjgl-glfw-3.3.6.jar", "org/lwjgl/lwjgl-glfw/3.3.6/lwjgl-glfw-3.3.6.jar"},
            {"lwjgl-opengl-3.3.6.jar", "org/lwjgl/lwjgl-opengl/3.3.6/lwjgl-opengl-3.3.6.jar"},
            {"lwjgl-stb-3.3.6.jar", "org/lwjgl/lwjgl-stb/3.3.6/lwjgl-stb-3.3.6.jar"},
            {"lwjgl-openal-3.3.6.jar", "org/lwjgl/lwjgl-openal/3.3.6/lwjgl-openal-3.3.6.jar"},
            {"gson-2.10.1.jar", "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"},
    };
    private static final String[] NATIVES = {"lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb", "lwjgl-openal"};

    private static BootstrapWindow window;


    // LOW LEVEL SIDELOADING INTERFACE - USED BY WATERMEDIA EXTENSIONS/PLUGINS TO HOOK INTO THE BOOTSTRAP PROCESS
    public interface Sideloadable {
        void load();
    }

    public static void main(final String... args) {
        if (System.getProperty(BOOTSTRAPPED_FLAG) != null) {
            try {
                WaterMediaApp.start(() -> {
                    WaterMediaApp.log("Launched with embedded WaterMedia App Bootstrap");
                    WaterMediaApp.log("Searching for sideloadable extensions...");
                    ServiceLoader.load(Sideloadable.class).forEach(Sideloadable::load);
                });
                // SIDELOADING
            } catch (final Throwable e) {
                showError(e);
            }
            return;
        }

        try {
            window = new BootstrapWindow();
            window.setVisible(true);
            System.out.println("WaterMedia App Bootstrap");
            System.out.println("========================");

            Files.createDirectories(LIBS_DIR);

            final List<Path> jars = new ArrayList<>();
            final List<String[]> toDownload = new ArrayList<>();

            // FIND BINARIES
            final Path binaries = findLocalJar("wm_binaries");
            boolean inCp = false;
            try {
                Class.forName("org.watermedia.binaries.WaterMediaBinaries");
                inCp = true;
            } catch (final ClassNotFoundException ignored) {}

            if (binaries == null && !inCp) {
                showError("WaterMedia Binaries JAR not found.\nDownload the latest version from CurseForge.");
                return;
            }
            if (binaries != null) jars.add(binaries);
            System.out.println("[OK] WaterMedia Binaries found");

            // COLLECT DEPS
            for (final String[] dep : DEPS) {
                final Path p = LIBS_DIR.resolve(dep[0]);
                if (Files.exists(p)) {
                    jars.add(p);
                    System.out.println("[CACHED] " + dep[0]);
                } else {
                    toDownload.add(dep);
                }
            }

            final String nat = "natives-" + IOTool.getPlatformClassifier();
            System.out.println("Platform: " + nat);
            for (final String mod : NATIVES) {
                final String fn = mod + "-3.3.6-" + nat + ".jar";
                final Path p = LIBS_DIR.resolve(fn);
                if (Files.exists(p)) {
                    jars.add(p);
                    System.out.println("[CACHED] " + fn);
                } else {
                    toDownload.add(new String[]{fn, "org/lwjgl/" + mod + "/3.3.6/" + fn});
                }
            }

            // DOWNLOAD
            for (final String[] dep : toDownload) {
                final Path d = LIBS_DIR.resolve(dep[0]);
                download(MAVEN + dep[1], d);
                jars.add(d);
            }

            jars.add(Path.of(AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

            System.out.println("\n[OK] Dependencies ready - " + jars.size() + " JARs");

            Thread.sleep(1000);

            // SEARCH FOR ANY WATERMEDIA EXTENSION/PLUGIN
            final File[] files = new File("").getAbsoluteFile().listFiles();
            if (files != null) {
                System.out.println("\n\n==========================\n\nSearching for extensions...");
                for (final File f: files) {
                    final String name = f.getName().toLowerCase();
                    if ((name.startsWith("watermedia_") || name.startsWith("wm_") || name.startsWith("waterm_") || name.startsWith("wmedia_")) && f.getName().endsWith(".jar")) {
                        jars.add(f.getAbsoluteFile().toPath());
                        System.out.println("[FOUND] Extension filename: " + f.getName());
                    }
                    Thread.sleep(100);
                }
            }

            System.out.println("Relaunching...");

            Thread.sleep(3000);
            window.dispose();
            relaunch(jars, args);
        } catch (final Exception e) {
            showError(e);
        }
    }

    // RELAUNCH METHOD - SPAWNS A NEW JVM PROCESS WITH UPDATED CLASSPATH
    // THE CLASSPATH NOW INCLUDES THE DOWNLOADED DEPENDENCIES
    // THE FLAG "watermedia.app=true" PREVENTS INFINITE RELAUNCH LOOPS (SEE main() CHECK AT LINE 63)
    // inheritIO() CONNECTS STDIN/STDOUT/STDERR SO THE USER SEES ALL OUTPUT - NO HIDDEN BEHAVIOR
    private static void relaunch(final List<Path> jars, final String[] args) throws Exception {
        // BUILD CLASSPATH FROM ALL COLLECTED JARS
        final StringJoiner cp = new StringJoiner(File.pathSeparator);
        jars.forEach(j -> cp.add(j.toAbsolutePath().toString()));

        // PRESERVE EXISTING CLASSPATH ENTRIES
        final String cur = System.getProperty("java.class.path");
        if (cur != null && !cur.isBlank()) cp.add(cur);

        // INCLUDE ANY URLS FROM PARENT CLASSLOADERS
        for (ClassLoader cl = Thread.currentThread().getContextClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof final URLClassLoader u) {
                for (final URL url : u.getURLs()) {
                    try {
                        cp.add(Path.of(url.toURI()).toString());
                    } catch (final Exception ignored) {}
                }
            }
        }

        // BUILD COMMAND: [java executable] [-Dwatermedia.app=true] [-cp classpath] [main class] [args]
        // USES THE SAME JAVA FROM java.home
        final List<String> cmd = new ArrayList<>(Arrays.asList(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + BOOTSTRAPPED_FLAG + "=true", "-cp", cp.toString(), AppBootstrap.class.getName()));
        cmd.addAll(Arrays.asList(args));
        // START THE NEW PROCESS AND WAIT FOR IT TO COMPLETE, THEN EXIT WITH ITS EXIT CODE
        System.exit(new ProcessBuilder(cmd).inheritIO().start().waitFor());
    }

    private static Path findLocalJar(final String prefix) {
        final File[] files = new File("").getAbsoluteFile().listFiles();
        if (files != null) {
            for (final File f : files) {
                if (f.getName().startsWith(prefix) && f.getName().endsWith(".jar")) return f.toPath();
            }
        }
        return null;
    }

    // DOWNLOAD METHOD - FETCHES JAR FILES FROM MAVEN CENTRAL (repo1.maven.org)
    // MAVEN CENTRAL IS THE OFFICIAL PUBLIC REPOSITORY FOR JAVA LIBRARIES - TRUSTED SOURCE
    // DOWNLOADS ONLY WELL-KNOWN LIBRARIES: LOG4J, LWJGL, GSON (SEE DEPS ARRAY AT LINE 48)
    // FILES ARE CACHED IN SYSTEM TEMP FOLDER (java.io.tmpdir/watermedia/libs) TO AVOID RE-DOWNLOADING
    // THIS IS STANDARD PRACTICE FOR JAVA APPLICATIONS THAT NEED RUNTIME DEPENDENCIES
    private static void download(final String url, final Path dest) throws Exception {
        System.out.println("[DOWNLOADING] " + dest.getFileName());
        window.setDownloading(true);

        // STANDARD HTTP CONNECTION TO MAVEN CENTRAL
        final URLConnection c = URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "WaterMedia/3.0.0");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);

        // SIMPLE FILE DOWNLOAD WITH PROGRESS TRACKING - WRITES DIRECTLY TO DESTINATION PATH
        final long total = c.getContentLengthLong();
        try (final InputStream in = new BufferedInputStream(c.getInputStream());
             final OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
            final byte[] buf = new byte[8192];
            long dl = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                dl += r;
                if (total > 0) window.setProgress((int) (dl * 100 / total));
            }
        }
        window.setProgress(100);
        Thread.sleep(500);
        window.setDownloading(false);
    }

    private static void showError(final Throwable e) {
        final StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        showError(sw.toString());
    }

    private static void showError(final String msg) {
        System.err.println("ERROR: " + msg);
        if (window != null) window.dispose();

        final Dialog dlg = new Dialog((Frame) null, "WaterMedia: Fatal Error", true);
        dlg.setLayout(new BorderLayout(0, 0));
        dlg.setBackground(C_BLACK);
        loadIcon(dlg::setIconImage);

        final ScrollPanel txt = new ScrollPanel(msg.replace("\t", "    "), C_RED);

        final Button ok = new Button("    OK    ");
        ok.setFont(new Font("Consolas", Font.BOLD, 18));
        ok.setPreferredSize(new Dimension(120, 40));
        ok.addActionListener(e -> {
            dlg.dispose();
            System.exit(1);
        });
        ok.setBackground(new Color(60, 60, 60));
        ok.setForeground(C_WHITE);

        final Panel btn = new Panel(new FlowLayout(FlowLayout.CENTER, 20, 15));
        btn.setBackground(C_BLACK);
        btn.add(ok);

        dlg.add(txt, BorderLayout.CENTER);
        dlg.add(btn, BorderLayout.SOUTH);
        dlg.setSize(1100, 500);
        dlg.setLocationRelativeTo(null);
        dlg.addWindowListener(onClose(() -> System.exit(1)));
        dlg.addComponentListener(onResize(txt::repaint));
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true);
    }

    private static void loadIcon(final Consumer<Image> setter) {
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            if (in != null) setter.accept(ImageIO.read(in));
        } catch (final Exception ignored) {}
    }

    // ========== LAMBDA HELPERS ==========
    private static Canvas canvas(final Consumer<Graphics> paint, final int w, final int h) {
        return new Canvas() {
            public void update(final Graphics g) { this.paint(g); }
            public void paint(final Graphics g) { paint.accept(g); }
            public Dimension getPreferredSize() { return new Dimension(w, h); }
        };
    }

    private static Canvas canvas(final BiConsumer<Canvas, Graphics> paint, final int w, final int h) {
        return new Canvas() {
            public void update(final Graphics g) { this.paint(g); }
            public void paint(final Graphics g) { paint.accept(this, g); }
            public Dimension getPreferredSize() { return new Dimension(w, h); }
        };
    }

    private static MouseAdapter onDrag(final Consumer<MouseEvent> onPress, final Consumer<MouseEvent> onDrag) {
        final boolean[] dragging = {false};
        return new MouseAdapter() {
            public void mousePressed(final MouseEvent e) {
                dragging[0] = true;
                onPress.accept(e);
            }
            public void mouseReleased(final MouseEvent e) {
                dragging[0] = false;
            }
            public void mouseDragged(final MouseEvent e) {
                if (dragging[0]) onDrag.accept(e);
            }
        };
    }

    private static WindowAdapter onClose(final Runnable r) {
        return new WindowAdapter() {
            public void windowClosing(final WindowEvent e) { r.run(); }
        };
    }

    private static ComponentAdapter onResize(final Runnable r) {
        return new ComponentAdapter() {
            public void componentResized(final ComponentEvent e) { r.run(); }
        };
    }

    // ========== SCROLL PANEL ==========
    private static class ScrollPanel extends Panel {
        private final String[] lines;
        private final Color color;
        private final Canvas content, scrollbar;
        private int off, max = 1, vis = 1, dragY, dragOff;
        private boolean auto = true;
        private Image buf;
        private int bufW, bufH;

        ScrollPanel(final String text, final Color color) {
            super(new BorderLayout(0, 0));
            this.lines = text.split("\n", -1);
            this.color = color;
            this.setBackground(C_BLACK);

            this.content = canvas((c, g) -> {
                final int w = c.getWidth(), h = c.getHeight();
                if (w <= 0 || h <= 0) return;

                if (this.buf == null || this.bufW != w || this.bufH != h) {
                    this.buf = c.createImage(w, h);
                    this.bufW = w;
                    this.bufH = h;
                }

                final Graphics o = this.buf.getGraphics();
                o.setColor(C_BLACK);
                o.fillRect(0, 0, w, h);
                o.setColor(this.color);
                o.setFont(FONT);

                final FontMetrics fm = o.getFontMetrics();
                final int lh = fm.getHeight();
                final int ml = (h - PAD * 2) / lh;

                this.max = Math.max(1, this.lines.length);
                this.vis = Math.max(1, ml);

                final int st = this.auto ? Math.max(0, this.lines.length - ml) : this.off;
                if (this.auto) this.off = st;

                int y = PAD + fm.getAscent();
                for (int i = st; i < this.lines.length && i < st + ml; i++, y += lh) {
                    o.drawString(this.lines[i], PAD, y);
                }
                o.dispose();
                g.drawImage(this.buf, 0, 0, c);
            }, 0, 0);

            this.content.setBackground(C_BLACK);
            this.content.addMouseWheelListener(e -> {
                this.scroll(e.getWheelRotation() * 3);
                this.repaint();
            });

            this.scrollbar = canvas((c, g) -> {
                g.setColor(C_GRAY_DARK);
                g.fillRect(0, 0, c.getWidth(), c.getHeight());
                if (this.max <= this.vis) return;
                final int th = c.getHeight();
                final int hh = Math.max(20, th * this.vis / this.max);
                final int rng = this.max - this.vis;
                final int thumbY = rng > 0 ? this.off * (th - hh) / rng : 0;
                g.setColor(C_BLUE);
                g.fillRect(2, thumbY, c.getWidth() - 4, hh);
            }, SCROLL_W, 100);
            this.scrollbar.setBackground(C_GRAY_DARK);

            final MouseAdapter ma = onDrag(
                    e -> { this.dragY = e.getY(); this.dragOff = this.off; },
                    e -> {
                        final int rng = this.max - this.vis;
                        if (rng <= 0) return;
                        final int th = this.scrollbar.getHeight();
                        final int hh = Math.max(20, th * this.vis / this.max);
                        final int av = th - hh;
                        this.off = Math.max(0, Math.min(rng, this.dragOff + (av > 0 ? (e.getY() - this.dragY) * rng / av : 0)));
                        this.auto = this.off >= rng;
                        this.repaint();
                    });
            this.scrollbar.addMouseListener(ma);
            this.scrollbar.addMouseMotionListener(ma);

            this.add(this.content, BorderLayout.CENTER);
            this.add(this.scrollbar, BorderLayout.EAST);
        }

        private void scroll(final int d) {
            final int rng = this.max - this.vis;
            if (rng <= 0) return;
            this.off = Math.max(0, Math.min(rng, this.off + d));
            this.auto = this.off >= rng;
        }
    }

    // ========== BOOTSTRAP WINDOW ==========
    private static class BootstrapWindow extends Frame {
        private final StringBuilder txt = new StringBuilder();
        private final PrintStream origOut = System.out;
        private final Canvas console, scrollbar, progBar;
        private final Panel progPanel;
        private int prog, off, max = 1, vis = 1, dragY, dragOff;
        private boolean auto = true;
        private BufferedImage banner;
        private Image buf;
        private int bufW, bufH;

        BootstrapWindow() {
            super("WaterMedia: Multimedia API");
            this.setBackground(C_BLACK);
            this.setLayout(new BorderLayout(0, 0));
            loadIcon(this::setIconImage);

            try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
                if (in != null) this.banner = ImageIO.read(in);
            } catch (final Exception ignored) {}

            // HEADER
            final Panel hdr = new Panel(new BorderLayout(0, 0));
            hdr.setBackground(C_BLACK);
            hdr.add(canvas(g -> {
                g.setColor(C_BLACK);
                g.fillRect(0, 0, 9999, 9999);
                if (this.banner != null) {
                    final double a = (double) this.banner.getWidth() / this.banner.getHeight();
                    int w = (int) g.getClipBounds().getWidth();
                    int h = (int) (w / a);
                    final int ch = (int) g.getClipBounds().getHeight();
                    if (h > ch) {
                        h = ch;
                        w = (int) (h * a);
                    }
                    g.drawImage(this.banner, ((int) g.getClipBounds().getWidth() - w) / 2, (ch - h) / 2, w, h, null);
                } else {
                    g.setColor(C_BLUE);
                    g.setFont(FONT_BOLD);
                    g.drawString("WaterMedia", 20, 50);
                }
            }, 960, 120), BorderLayout.CENTER);
            hdr.add(canvas(g -> {
                g.setColor(C_BLUE);
                g.fillRect(0, 0, 9999, 9999);
            }, 960, 4), BorderLayout.SOUTH);

            // SCROLLBAR
            this.scrollbar = canvas((c, g) -> {
                g.setColor(C_GRAY_DARK);
                g.fillRect(0, 0, c.getWidth(), c.getHeight());
                if (this.max <= this.vis) return;
                final int th = c.getHeight();
                final int hh = Math.max(20, th * this.vis / this.max);
                final int rng = this.max - this.vis;
                final int thumbY = rng > 0 ? this.off * (th - hh) / rng : 0;
                g.setColor(C_BLUE);
                g.fillRect(2, thumbY, c.getWidth() - 4, hh);
            }, SCROLL_W, 100);
            this.scrollbar.setBackground(C_GRAY_DARK);

            // CONSOLE
            this.console = canvas((c, g) -> {
                final int w = c.getWidth(), h = c.getHeight();
                if (w <= 0 || h <= 0) return;

                if (this.buf == null || this.bufW != w || this.bufH != h) {
                    this.buf = c.createImage(w, h);
                    this.bufW = w;
                    this.bufH = h;
                }

                final Graphics o = this.buf.getGraphics();
                o.setColor(C_BLACK);
                o.fillRect(0, 0, w, h);
                o.setColor(C_GREEN);
                o.setFont(FONT);

                final FontMetrics fm = o.getFontMetrics();
                final int lh = fm.getHeight();
                final int ml = (h - PAD * 2) / lh;
                final String[] lines = this.txt.toString().split("\n", -1);

                this.updateScroll(lines.length, ml);
                final int st = this.auto ? Math.max(0, lines.length - ml) : this.off;

                int y = PAD + fm.getAscent();
                for (int i = st; i < lines.length && i < st + ml; i++, y += lh) {
                    o.drawString(lines[i], PAD, y);
                }
                o.dispose();
                g.drawImage(this.buf, 0, 0, c);
            }, 0, 0);
            this.console.setBackground(C_BLACK);
            this.console.addMouseWheelListener(e -> {
                this.scroll(e.getWheelRotation() * 3);
                this.console.repaint();
                this.scrollbar.repaint();
            });

            final MouseAdapter sma = onDrag(
                    e -> {
                        this.dragY = e.getY();
                        this.dragOff = this.off; },
                    e -> {
                        final int rng = this.max - this.vis;
                        if (rng <= 0) return;
                        final int th = this.scrollbar.getHeight();
                        final int hh = Math.max(20, th * this.vis / this.max);
                        final int av = th - hh;
                        this.off = Math.max(0, Math.min(rng, this.dragOff + (av > 0 ? (e.getY() - this.dragY) * rng / av : 0)));
                        this.auto = this.off >= rng;
                        this.scrollbar.repaint();
                        this.console.repaint();
                    });
            this.scrollbar.addMouseListener(sma);
            this.scrollbar.addMouseMotionListener(sma);

            final Panel cp = new Panel(new BorderLayout(0, 0));
            cp.setBackground(C_BLACK);
            cp.add(this.console, BorderLayout.CENTER);
            cp.add(this.scrollbar, BorderLayout.EAST);

            // PROGRESS
            this.progBar = canvas(g -> {
                final int w = (int) g.getClipBounds().getWidth();
                g.setColor(C_GRAY_DARK);
                g.fillRect(0, 0, w, 24);
                g.setColor(C_BLUE_DARK);
                g.fillRect(0, 0, w * this.prog / 100, 24);
                g.setColor(C_WHITE);
                g.setFont(FONT);
                final String s = this.prog + "%";
                g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, 16);
            }, 960, 24);

            this.progPanel = new Panel(new BorderLayout());
            this.progPanel.setBackground(C_BLACK);
            this.progPanel.add(this.progBar, BorderLayout.CENTER);
            this.progPanel.setVisible(false);

            this.add(hdr, BorderLayout.NORTH);
            this.add(cp, BorderLayout.CENTER);
            this.add(this.progPanel, BorderLayout.SOUTH);
            this.setSize(960, 540);
            this.setLocationRelativeTo(null);
            this.addWindowListener(onClose(() -> System.exit(0)));
            this.addComponentListener(onResize(this::repaint));

            // REDIRECT STDOUT/STDERR
            System.setOut(new PrintStream(new OutputStream() {
                private final StringBuilder ln = new StringBuilder();
                public void write(final int b) {
                    BootstrapWindow.this.origOut.write(b);
                    if (b == '\n') {
                        BootstrapWindow.this.txt.append(this.ln).append("\n");
                        this.ln.setLength(0);
                        EventQueue.invokeLater(BootstrapWindow.this.console::repaint);
                    } else {
                        this.ln.append((char) b);
                    }
                }
            }));
            System.setErr(System.out);
        }

        void setProgress(final int p) {
            this.prog = p;
            this.progBar.repaint();
        }

        void setDownloading(final boolean a) {
            this.prog = 0;
            this.progPanel.setVisible(a);
            this.validate();
        }

        private void scroll(final int d) {
            final int rng = this.max - this.vis;
            if (rng <= 0) return;
            this.off = Math.max(0, Math.min(rng, this.off + d));
            this.auto = this.off >= rng;
        }

        private void updateScroll(final int t, final int v) {
            if (this.max != t || this.vis != v) {
                this.max = Math.max(1, t);
                this.vis = Math.max(1, v);
                if (this.auto) this.off = Math.max(0, this.max - this.vis);
                this.scrollbar.repaint();
            }
        }
    }
}
