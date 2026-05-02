package org.watermedia.bootstrap;

import org.watermedia.bootstrap.app.WaterMediaApp;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// BOOTSTRAP LAUNCHER FOR WATERMEDIA STANDALONE APPLICATION
// THE JVM LOCKS THE CLASSPATH AT STARTUP. SPAWNING A FRESH JVM WITH THE FULL CLASSPATH IS THE ONLY RELIABLE SOLUTION.
public class AppBootstrap {
    private static final Path LIBS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "watermedia/libs");
    private static final String MAVEN = "https://repo1.maven.org/maven2/";
    private static final String APP_FLAG = "watermedia.app";
    private static final String OS = IOTool.getPlatformClassifier();

    private static final String[][] DEPS = {
            {"log4j-api-2.25.0.jar", "org/apache/logging/log4j/log4j-api/2.25.0/log4j-api-2.25.0.jar"},
            {"log4j-core-2.25.0.jar", "org/apache/logging/log4j/log4j-core/2.25.0/log4j-core-2.25.0.jar"},
            {"lwjgl-3.3.6.jar", "org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6.jar"},
            {"lwjgl-glfw-3.3.6.jar", "org/lwjgl/lwjgl-glfw/3.3.6/lwjgl-glfw-3.3.6.jar"},
            {"lwjgl-opengl-3.3.6.jar", "org/lwjgl/lwjgl-opengl/3.3.6/lwjgl-opengl-3.3.6.jar"},
            {"lwjgl-stb-3.3.6.jar", "org/lwjgl/lwjgl-stb/3.3.6/lwjgl-stb-3.3.6.jar"},
            {"lwjgl-openal-3.3.6.jar", "org/lwjgl/lwjgl-openal/3.3.6/lwjgl-openal-3.3.6.jar"},
            {"gson-2.10.1.jar", "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"},
            {"joml-1.10.8.jar", "org/joml/joml/1.10.8/joml-1.10.8.jar"},

    };
    private static final String[] NATIVES = {"lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb", "lwjgl-openal"};

    // ANSI ESCAPE CODES
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_GREEN = "\033[32m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_BLUE = "\033[36m";
    private static final Pattern ANSI_PATTERN = Pattern.compile("\033\\[(\\d*)(m)");

    // UI CONSTANTS
    private static final String TITLE_MAIN = "WATERMeDIA: Multimedia API";
    private static final String TITLE_ERROR = "WATERMeDIA: Fatal Error";
    private static final int WIN_W = 960, WIN_H = 540;
    private static final int BANNER_H = 120, HDR_LINE_H = 4;
    private static final int ERR_W = 768, ERR_H = 390;
    private static final int BTN_W = 160, BTN_H = 40;
    private static final int PAD = 15, SCROLL_W = 12, SCROLL_THUMB_MIN = 20;
    private static final Font FONT = new Font("Consolas", Font.PLAIN, 14);
    private static final Font FONT_BOLD = new Font("Consolas", Font.BOLD, 24);
    private static final Color C_BLACK = Color.BLACK, C_WHITE = Color.WHITE;
    private static final Color C_GREEN = new Color(0, 255, 0);
    private static final Color C_RED = new Color(255, 100, 100);
    private static final Color C_YELLOW = new Color(255, 200, 0);
    private static final Color C_BLUE = new Color(79, 181, 255);
    private static final Color C_GRAY = new Color(60, 60, 60);
    private static final Color C_GRAY_DARK = new Color(30, 30, 30);

    // NETWORK
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final int DOWNLOAD_BUF = 8192;

    // LAUNCH
    private static final int LAUNCH_DELAY_S = 5;

    private static BootstrapWindow window;
    private static String[] launchArgs = {};

    // LOGGER — \r clears the GUI console line; ANSI codes color both GUI and terminal
    private static void info(final String msg) { System.out.println("\r" + ANSI_GREEN + msg + ANSI_RESET); }
    private static void warn(final String msg) { System.out.println("\r" + ANSI_YELLOW + msg + ANSI_RESET); }
    private static void error(final String msg) { System.out.println("\r" + ANSI_RED + msg + ANSI_RESET); }
    private static void live(final String msg) { System.out.print("\r" + ANSI_BLUE + msg + ANSI_RESET); }

    public interface Extension {
        void load();
    }

    public static void main(final String... args) {
        launchArgs = args;
        if (System.getProperty(APP_FLAG) != null) {
            try {
                final int command = WaterMediaApp.start(() -> {
                    WaterMediaApp.log("Launched with embedded WaterMedia App Bootstrap");
                    WaterMediaApp.log("Searching for sideloadable extensions...");
                    ServiceLoader.load(Extension.class).forEach(Extension::load);
                });
                switch (command) {
                    case 1 -> {
                        // DELETE ALL JARS
                    }
                }
            } catch (final Throwable e) {
                showError(e);
            }
            return;
        }

        try {
            window = new BootstrapWindow();
            info("WATERMeDIA: App Bootstrap - On: " + OS);
            info("Dependencies directory: " + LIBS_DIR);
            info("=============================================");
            Files.createDirectories(LIBS_DIR);

            final List<Path> jars = new ArrayList<>();
            final List<String[]> toDownload = new ArrayList<>();

            // FIND BINARIES
            final Path binaries = findLocalJar("wm_binaries");
            boolean classpath = false;
            try {
                Class.forName("org.watermedia.binaries.WaterMediaBinaries");
                classpath = true;
            } catch (final ClassNotFoundException ignored) {}

            if (binaries == null && !classpath) {
                showError("WaterMedia Binaries JAR not found.\nDownload the latest version from CurseForge.");
                return;
            }
            if (binaries != null) jars.add(binaries);
            info("[OK] WaterMedia Binaries found");

            // COLLECT DEPS
            for (final String[] dep : DEPS) {
                final Path p = LIBS_DIR.resolve(dep[0]);
                if (Files.exists(p)) {
                    jars.add(p);
                    info("[FOUND] " + dep[0]);
                } else {
                    toDownload.add(dep);
                }
            }

            // COLLECT NATIVES
            for (final String mod: NATIVES) {
                final String fn = mod + "-3.3.6-natives-" + OS + ".jar";
                final Path p = LIBS_DIR.resolve(fn);
                if (Files.exists(p)) {
                    jars.add(p);
                    info("[FOUND] " + fn);
                } else {
                    toDownload.add(new String[]{fn, "org/lwjgl/" + mod + "/3.3.6/" + fn});
                }
            }

            // DOWNLOAD MISSING
            for (final String[] dep: toDownload) {
                final Path d = LIBS_DIR.resolve(dep[0]);
                download(MAVEN + dep[1], d);
                jars.add(d);
            }

            jars.add(Path.of(AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

            // SEARCH FOR EXTENSIONS
            final File[] files = new File("").getAbsoluteFile().listFiles();
            if (files != null) {
                info("=== Searching for extensions in " + new File("").getAbsolutePath());
                for (final File f : files) {
                    final String name = f.getName().toLowerCase();
                    if ((name.startsWith("watermedia_") || name.startsWith("wm_") || name.startsWith("waterm_") || name.startsWith("wmedia_")) && f.getName().endsWith(".jar")) {
                        jars.add(f.getAbsoluteFile().toPath());
                        info("[FOUND] Extension: " + f.getName());
                    }
                }
            }

            waitForLaunch();
            info("Launching...");
            window.dispose();
            relaunch(jars, args);
        } catch (final Exception e) {
            showError(e);
        }
    }

    private static void waitForLaunch() {
        final Object lock = new Object();
        final boolean[] skip = {false};
        final KeyEventDispatcher dispatcher = e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                synchronized (lock) {
                    skip[0] = true;
                    lock.notifyAll();
                }
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
        synchronized (lock) {
            for (int i = LAUNCH_DELAY_S; i > 0 && !skip[0]; i--) {
                live("[WAIT] Launching in " + i + "s... Press any key to skip");
                try { lock.wait(1000); } catch (final InterruptedException ignored) {}
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
    }

    private static void relaunch(final List<Path> jars, final String[] args) throws Exception {
        final StringJoiner cp = new StringJoiner(File.pathSeparator);
        jars.forEach(j -> cp.add(j.toAbsolutePath().toString()));

        final String cur = System.getProperty("java.class.path");
        if (cur != null && !cur.isBlank()) cp.add(cur);

        for (ClassLoader cl = Thread.currentThread().getContextClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof final URLClassLoader u) {
                for (final URL url : u.getURLs()) {
                    try {
                        cp.add(Path.of(url.toURI()).toString());
                    } catch (final Exception ignored) {}
                }
            }
        }

        final List<String> cmd = new ArrayList<>(Arrays.asList(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + APP_FLAG + "=true", "-Dlog4j2.StatusLogger.level=WARN",
                "-cp", cp.toString(), AppBootstrap.class.getName()));
        cmd.addAll(Arrays.asList(args));
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

    private static void download(final String url, final Path dest) throws Exception {
        final String name = dest.getFileName().toString();
        final URLConnection c = URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "WaterMedia/3.0.0");
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);

        final long total = c.getContentLengthLong();
        try (final InputStream in = new BufferedInputStream(c.getInputStream());
             final OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {
            final byte[] buf = new byte[DOWNLOAD_BUF];
            long dl = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                ThreadTool.sleep(5); // SLEEP TO SLOWDOWN DOWNLOAD SPEED, FOR FANCYNESS
                dl += r;
                if (total > 0) {
                    live(String.format("[DOWNLOADING] %s %d%% %.1f/%.1fMB", name,
                            (int) (dl * 100 / total), dl / 1_048_576.0, total / 1_048_576.0));
                } else {
                    live(String.format("[DOWNLOADING] %s %.1fMB", name, dl / 1_048_576.0));
                }
            }
        }
        info("[DONE] " + name);
    }

    private static void showError(final Throwable e) {
        final StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        showError(sw.toString());
    }

    private static void showError(final String msg) {
        System.err.println("ERROR: " + msg);

        final Dialog dlg = new Dialog((Frame) null, TITLE_ERROR, true);
        dlg.setLayout(new BorderLayout(0, 0));
        dlg.setBackground(C_BLACK);
        loadIcon(dlg::setIconImage);

        final String[] lines = msg.replace("\t", "    ").split("\n", -1);
        final Canvas txt = canvas((c, g) -> {
            final int w = c.getWidth(), h = c.getHeight();
            if (w <= 0 || h <= 0) return;
            g.setColor(C_BLACK);
            g.fillRect(0, 0, w, h);
            g.setColor(C_RED);
            g.setFont(FONT);
            final int lh = g.getFontMetrics().getHeight();
            int y = PAD + g.getFontMetrics().getAscent();
            for (final String line : lines) {
                g.drawString(line, PAD, y);
                y += lh;
            }
        }, 0, 0);
        txt.setBackground(C_BLACK);

        final Button copy = new Button("Copy & Close");
        copy.setFont(FONT.deriveFont(Font.BOLD));
        copy.setPreferredSize(new Dimension(BTN_W, BTN_H));
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(msg), null);
            dlg.dispose();
            if (window != null) window.dispose();
            System.exit(1);
        });
        copy.setBackground(C_GRAY);
        copy.setForeground(C_WHITE);

        final Button close = new Button("Close");
        close.setFont(FONT.deriveFont(Font.BOLD));
        close.setPreferredSize(new Dimension(BTN_W, BTN_H));
        close.addActionListener(e -> {
            dlg.dispose();
            System.exit(1);
        });
        close.setBackground(C_GRAY);
        close.setForeground(C_WHITE);

        final Button relaunch = new Button("Relaunch");
        relaunch.setFont(FONT.deriveFont(Font.BOLD));
        relaunch.setPreferredSize(new Dimension(BTN_W, BTN_H));
        relaunch.addActionListener(e -> {
            dlg.dispose();
            if (window != null) window.dispose();
            main(launchArgs);
        });
        relaunch.setBackground(C_GRAY);
        relaunch.setForeground(C_WHITE);

        final Panel leftBtns = new Panel(new FlowLayout(FlowLayout.LEFT, 10, 15));
        leftBtns.setBackground(C_BLACK);
        leftBtns.add(relaunch);

        final Panel rightBtns = new Panel(new FlowLayout(FlowLayout.RIGHT, 10, 15));
        rightBtns.setBackground(C_BLACK);
        rightBtns.add(copy);
        rightBtns.add(close);

        final Panel btn = new Panel(new BorderLayout());
        btn.setBackground(C_BLACK);
        btn.add(leftBtns, BorderLayout.WEST);
        btn.add(rightBtns, BorderLayout.EAST);

        dlg.add(txt, BorderLayout.CENTER);
        dlg.add(btn, BorderLayout.SOUTH);
        dlg.setSize(ERR_W, ERR_H);
        dlg.setLocationRelativeTo(null);
        dlg.addWindowListener(onClose(() -> System.exit(1)));
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true);
    }

    private static void loadIcon(final Consumer<Image> setter) {
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            if (in != null) setter.accept(ImageIO.read(in));
        } catch (final Exception ignored) {}
    }

    // ANSI RENDERING — parses escape codes and draws colored segments
    private static void drawAnsiLine(final Graphics g, final String line, int x, final int y, final Color defaultColor) {
        final FontMetrics fm = g.getFontMetrics();
        final Matcher m = ANSI_PATTERN.matcher(line);
        Color current = defaultColor;
        int last = 0;
        while (m.find()) {
            final String before = line.substring(last, m.start());
            if (!before.isEmpty()) {
                g.setColor(current);
                g.drawString(before, x, y);
                x += fm.stringWidth(before);
            }
            final String code = m.group(1);
            current = switch (code.isEmpty() ? 0 : Integer.parseInt(code)) {
                case 31 -> C_RED;
                case 32 -> C_GREEN;
                case 33 -> C_YELLOW;
                case 34, 36 -> C_BLUE;
                default -> defaultColor;
            };
            last = m.end();
        }
        final String rem = line.substring(last);
        if (!rem.isEmpty()) {
            g.setColor(current);
            g.drawString(rem, x, y);
        }
    }

    // CANVAS FACTORY
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

    private static WindowAdapter onClose(final Runnable r) {
        return new WindowAdapter() {
            public void windowClosing(final WindowEvent e) { r.run(); }
        };
    }

    // BOOTSTRAP WINDOW
    private static class BootstrapWindow extends Frame {
        private final List<String> lines = new ArrayList<>();
        private final StringBuilder currentLine = new StringBuilder();
        private final PrintStream origOut = System.out;
        private final Canvas console, scrollbar;
        private int off, max = 1, vis = 1, dragY, dragOff;
        private boolean auto = true;
        private BufferedImage banner;
        private Image buf;
        private int bufW, bufH;

        BootstrapWindow() {
            super(TITLE_MAIN);
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
                    if (h > ch) { h = ch; w = (int) (h * a); }
                    g.drawImage(this.banner, ((int) g.getClipBounds().getWidth() - w) / 2, (ch - h) / 2, w, h, null);
                } else {
                    g.setColor(C_BLUE);
                    g.setFont(FONT_BOLD.deriveFont(48f));
                    g.drawString("WATERMeDIA", (int) ((g.getClipBounds().getWidth() / 2) - 120), 75);
                }
            }, WIN_W, BANNER_H), BorderLayout.CENTER);
            hdr.add(canvas(g -> {
                g.setColor(C_BLUE);
                g.fillRect(0, 0, 9999, 9999);
            }, WIN_W, HDR_LINE_H), BorderLayout.SOUTH);

            // SCROLLBAR
            this.scrollbar = canvas((c, g) -> {
                g.setColor(C_GRAY_DARK);
                g.fillRect(0, 0, c.getWidth(), c.getHeight());
                if (this.max <= this.vis) return;
                final int th = c.getHeight();
                final int hh = Math.max(SCROLL_THUMB_MIN, th * this.vis / this.max);
                final int rng = this.max - this.vis;
                final int thumbY = rng > 0 ? this.off * (th - hh) / rng : 0;
                g.setColor(C_BLUE);
                g.fillRect(2, thumbY, c.getWidth() - 4, hh);
            }, SCROLL_W, 100);
            this.scrollbar.setBackground(C_GRAY_DARK);

            // CONSOLE — supports ANSI colors and \r for live-line replacement
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
                o.setFont(FONT);

                final FontMetrics fm = o.getFontMetrics();
                final int lh = fm.getHeight();
                final int ml = (h - PAD * 2) / lh;

                final String[] displayLines;
                synchronized (BootstrapWindow.this) {
                    final List<String> all = new ArrayList<>(BootstrapWindow.this.lines);
                    final String cur = BootstrapWindow.this.currentLine.toString();
                    if (!cur.isEmpty()) all.add(cur);
                    displayLines = all.toArray(new String[0]);
                }

                this.updateScroll(displayLines.length, ml);
                final int st = this.auto ? Math.max(0, displayLines.length - ml) : this.off;

                int y = PAD + fm.getAscent();
                for (int i = st; i < displayLines.length && i < st + ml; i++, y += lh) {
                    drawAnsiLine(o, displayLines[i], PAD, y, C_WHITE);
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

            final boolean[] dragging = {false};
            final MouseAdapter sma = new MouseAdapter() {
                public void mousePressed(final MouseEvent e) {
                    dragging[0] = true;
                    BootstrapWindow.this.dragY = e.getY();
                    BootstrapWindow.this.dragOff = BootstrapWindow.this.off;
                }
                public void mouseReleased(final MouseEvent e) { dragging[0] = false; }
                public void mouseDragged(final MouseEvent e) {
                    if (!dragging[0]) return;
                    final int rng = BootstrapWindow.this.max - BootstrapWindow.this.vis;
                    if (rng <= 0) return;
                    final int th = BootstrapWindow.this.scrollbar.getHeight();
                    final int hh = Math.max(SCROLL_THUMB_MIN, th * BootstrapWindow.this.vis / BootstrapWindow.this.max);
                    final int av = th - hh;
                    BootstrapWindow.this.off = Math.max(0, Math.min(rng, BootstrapWindow.this.dragOff + (av > 0 ? (e.getY() - BootstrapWindow.this.dragY) * rng / av : 0)));
                    BootstrapWindow.this.auto = BootstrapWindow.this.off >= rng;
                    BootstrapWindow.this.scrollbar.repaint();
                    BootstrapWindow.this.console.repaint();
                }
            };
            this.scrollbar.addMouseListener(sma);
            this.scrollbar.addMouseMotionListener(sma);

            final Panel cp = new Panel(new BorderLayout(0, 0));
            cp.setBackground(C_BLACK);
            cp.add(this.console, BorderLayout.CENTER);
            cp.add(this.scrollbar, BorderLayout.EAST);

            this.add(hdr, BorderLayout.NORTH);
            this.add(cp, BorderLayout.CENTER);
            this.setSize(WIN_W, WIN_H);
            this.setLocationRelativeTo(null);
            this.addWindowListener(onClose(() -> System.exit(0)));
            this.addComponentListener(new ComponentAdapter() {
                public void componentResized(final ComponentEvent e) {
                    BootstrapWindow.this.repaint(); }
            });
            this.setVisible(true);

            // REDIRECT STDOUT/STDERR
            // \r deferred: if followed by \n it's a Windows line ending (commit line),
            // otherwise it's a real carriage return (clear line for live overwrite)
            System.setOut(new PrintStream(new OutputStream() {
                private boolean pendingCR = false;

                @Override
                public void write(final int b) {
                    BootstrapWindow.this.origOut.write(b);
                    synchronized (BootstrapWindow.this) {
                        this.processChar(b); }
                    if (b == '\n' || b == '\r') this.scheduleRepaint();
                }

                @Override
                public void write(final byte[] b, final int off, final int len) throws IOException {
                    BootstrapWindow.this.origOut.write(b, off, len);
                    boolean repaint = false;
                    synchronized (BootstrapWindow.this) {
                        for (int i = off; i < off + len; i++) {
                            final int ch = b[i] & 0xFF;
                            this.processChar(ch);
                            if (ch == '\n' || ch == '\r') repaint = true;
                        }
                    }
                    if (repaint) this.scheduleRepaint();
                }

                private void processChar(final int b) {
                    if (this.pendingCR) {
                        this.pendingCR = false;
                        if (b == '\n') {
                            BootstrapWindow.this.lines.add(BootstrapWindow.this.currentLine.toString());
                            BootstrapWindow.this.currentLine.setLength(0);
                            return;
                        }
                        BootstrapWindow.this.currentLine.setLength(0);
                    }
                    if (b == '\r') {
                        this.pendingCR = true;
                    } else if (b == '\n') {
                        BootstrapWindow.this.lines.add(BootstrapWindow.this.currentLine.toString());
                        BootstrapWindow.this.currentLine.setLength(0);
                    } else {
                        BootstrapWindow.this.currentLine.append((char) b);
                    }
                }

                private void scheduleRepaint() {
                    EventQueue.invokeLater(() -> {
                        BootstrapWindow.this.console.repaint();
                        BootstrapWindow.this.scrollbar.repaint();
                    });
                }
            }));
            System.setErr(System.out);
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
