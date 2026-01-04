package org.watermedia.bootstrap;

import org.watermedia.bootstrap.app.WaterMediaApp;
import org.watermedia.tools.IOTool;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class AppBootstrap {
    private static final Path LIBS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "watermedia/libs");
    private static final String MAVEN = "https://repo1.maven.org/maven2/";
    private static final String BOOTSTRAPPED_FLAG = "watermedia.app";
    private static final Font CONSOLE_FONT = new Font("Consolas", Font.PLAIN, 18);

    private static final String[][] DEPENDENCIES = {
            {"log4j-api-2.25.0.jar", "org/apache/logging/log4j/log4j-api/2.25.0/log4j-api-2.25.0.jar"},
            {"log4j-core-2.25.0.jar", "org/apache/logging/log4j/log4j-core/2.25.0/log4j-core-2.25.0.jar"},
            {"lwjgl-3.3.6.jar", "org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6.jar"},
            {"lwjgl-glfw-3.3.6.jar", "org/lwjgl/lwjgl-glfw/3.3.6/lwjgl-glfw-3.3.6.jar"},
            {"lwjgl-opengl-3.3.6.jar", "org/lwjgl/lwjgl-opengl/3.3.6/lwjgl-opengl-3.3.6.jar"},
            {"lwjgl-stb-3.3.6.jar", "org/lwjgl/lwjgl-stb/3.3.6/lwjgl-stb-3.3.6.jar"},
            {"lwjgl-openal-3.3.6.jar", "org/lwjgl/lwjgl-openal/3.3.6/lwjgl-openal-3.3.6.jar"},
            {"gson-2.10.1.jar", "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"},
    };

    private static final String[] LWJGL_NATIVES = {"lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb", "lwjgl-openal"};

    private static BootstrapWindow window;

    public static void main(final String... args) {
        if (System.getProperty(BOOTSTRAPPED_FLAG) != null) {
            launchApp(args);
            return;
        }

        try {
            window = new BootstrapWindow();
            window.setVisible(true);

            System.out.println("WaterMedia App Bootstrap");
            System.out.println("========================");

            Files.createDirectories(LIBS_DIR);
            final List<Path> jars = new ArrayList<>();

            // FIND BINARIES
            final Path binaries = findLocalJar("wm_binaries");
            boolean inClassPath = false;
            try {
                Class.forName("org.watermedia.binaries.WaterMediaBinaries");
                inClassPath = true;
            } catch (ClassNotFoundException ignored) {}

            if (binaries == null && !inClassPath) {
                showError("WaterMedia Binaries JAR not found.\nDownload the latest version from CurseForge.");
                return;
            }
            if (binaries != null)
                jars.add(binaries);

            System.out.println("[OK] WaterMedia Binaries found");

            // COLLECT DEPENDENCIES
            final List<String[]> toDownload = new ArrayList<>();
            for (final String[] dep : DEPENDENCIES) {
                final Path path = LIBS_DIR.resolve(dep[0]);
                if (Files.exists(path)) {
                    jars.add(path);
                    System.out.println("[CACHED] " + dep[0]);
                } else {
                    toDownload.add(dep);
                }
            }

            // NATIVES
            final String natives = "natives-" + IOTool.getPlatformClassifier();
            System.out.println("Platform: " + natives);
            for (final String module : LWJGL_NATIVES) {
                final String filename = module + "-3.3.6-" + natives + ".jar";
                final Path path = LIBS_DIR.resolve(filename);
                if (Files.exists(path)) {
                    jars.add(path);
                    System.out.println("[CACHED] " + filename);
                } else {
                    toDownload.add(new String[]{filename, "org/lwjgl/" + module + "/3.3.6/" + filename});
                }
            }

            // DOWNLOAD
            for (final String[] dep : toDownload) {
                final Path dest = LIBS_DIR.resolve(dep[0]);
                download(MAVEN + dep[1], dest);
                jars.add(dest);
            }

            // ADD CURRENT JAR
            jars.add(Path.of(AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

            System.out.println("\n[OK] Dependencies ready - " + jars.size() + " JARs");
            System.out.println("Relaunching...");

            // CLOSE AFTER 3 SECONDS
            Thread.sleep(3000);
            window.dispose();

            // RELAUNCH
            relaunch(jars, args);

        } catch (final Exception e) {
            showError(e);
        }
    }

    private static void launchApp(final String[] args) {
        try {
            WaterMediaApp.main(args);
        } catch (final Throwable e) {
            showError(e);
        }
    }

    private static void relaunch(final List<Path> jars, final String[] args) throws Exception {
        final StringJoiner cp = new StringJoiner(File.pathSeparator);
        for (final Path jar: jars) cp.add(jar.toAbsolutePath().toString());

        final String currentCp = System.getProperty("java.class.path");
        if (currentCp != null && !currentCp.isBlank()) cp.add(currentCp);

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        while (cl != null) {
            if (cl instanceof final URLClassLoader ucl) {
                for (final URL url : ucl.getURLs()) {
                    try { cp.add(Path.of(url.toURI()).toString()); } catch (final Exception ignored) {}
                }
            }
            cl = cl.getParent();
        }

        final List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-D" + BOOTSTRAPPED_FLAG + "=true");
        cmd.add("-cp");
        cmd.add(cp.toString());
        cmd.add(AppBootstrap.class.getName());
        cmd.addAll(Arrays.asList(args));

        final Process process = new ProcessBuilder(cmd).inheritIO().start();
        System.exit(process.waitFor());
    }

    private static Path findLocalJar(final String prefix) {
        final File[] files = new File("").getAbsoluteFile().listFiles();
        if (files == null) return null;
        for (final File f: files) {
            if (f.getName().startsWith(prefix) && f.getName().endsWith(".jar")) return f.toPath();
        }
        return null;
    }

    private static void download(final String url, final Path dest) throws Exception {
        System.out.println("[DOWNLOADING] " + dest.getFileName());
        window.setDownloading(true);

        final URLConnection conn = URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "WaterMedia/3.0.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        final long total = conn.getContentLengthLong();
        try (final InputStream in = new BufferedInputStream(conn.getInputStream());
             final OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest))) {

            final byte[] buf = new byte[8192];
            long downloaded = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloaded += read;
                if (total > 0) window.setProgress((int) ((downloaded * 100) / total));
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

        final Dialog dialog = new Dialog((Frame) null, "WaterMedia: Error", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setBackground(Color.BLACK);
        try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
            dialog.setIconImage(ImageIO.read(in));
        } catch (final Exception ignored) {}


        final TextArea text = new TextArea(msg, 20, 80, TextArea.SCROLLBARS_VERTICAL_ONLY);
        text.setEditable(false);
        text.setBackground(Color.BLACK);
        text.setForeground(new Color(255, 100, 100));
        text.setFont(CONSOLE_FONT);

        final Button ok = new Button("    OK    ");
        ok.setFont(new Font("Consolas", Font.BOLD, 18));
        ok.setPreferredSize(new Dimension(120, 40));
        ok.addActionListener(e -> { dialog.dispose(); System.exit(1); });
        ok.setBackground(new Color(60, 60, 60));
        ok.setForeground(Color.WHITE);

        final Panel btnPanel = new Panel(new FlowLayout(FlowLayout.CENTER, 20, 15));
        btnPanel.setBackground(Color.BLACK);
        btnPanel.add(ok);

        dialog.add(text, BorderLayout.CENTER);
        dialog.add(btnPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(final java.awt.event.WindowEvent e) { System.exit(1); }
        });
        dialog.requestFocusInWindow();
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    // ========== BOOTSTRAP WINDOW ==========
    private static class BootstrapWindow extends Frame {
        private final TextArea console;
        private final Panel progressPanel;
        private final Canvas progressBar;
        private final PrintStream originalOut = System.out;
        private volatile int progress = 0;
        private BufferedImage bannerImg;

        BootstrapWindow() {
            super("WaterMedia: Multimedia API");
            this.setBackground(Color.BLACK);
            this.setLayout(new BorderLayout(0, 0));

            try (final InputStream in = IOTool.jarOpenFile("icon.png")) {
                this.setIconImage(ImageIO.read(in));
            } catch (final Exception ignored) {}

            try (final InputStream in = IOTool.jarOpenFile("banner.png")) {
                if (in != null) this.bannerImg = ImageIO.read(in);
            } catch (final Exception ignored) {}

            // BANNER
            final Canvas banner = new Canvas() {
                @Override
                public void paint(final Graphics g) {
                    g.setColor(Color.BLACK);
                    g.fillRect(0, 0, this.getWidth(), this.getHeight());
                    if (BootstrapWindow.this.bannerImg != null) {
                        final double aspect = (double) BootstrapWindow.this.bannerImg.getWidth() / BootstrapWindow.this.bannerImg.getHeight();
                        int w = this.getWidth(), h = (int) (w / aspect);
                        if (h > this.getHeight()) { h = this.getHeight(); w = (int) (h * aspect); }
                        g.drawImage(BootstrapWindow.this.bannerImg, (this.getWidth() - w) / 2, (this.getHeight() - h) / 2, w, h, this);
                    } else {
                        g.setColor(new Color(0, 180, 255));
                        g.setFont(new Font("Consolas", Font.BOLD, 24));
                        g.drawString("WaterMedia", 20, 50);
                    }
                }
                @Override public Dimension getPreferredSize() { return new Dimension(960, 120); }
            };

            // CONSOLE
            this.console = new TextArea("", 20, 100, TextArea.SCROLLBARS_VERTICAL_ONLY);
            this.console.setEditable(false);
            this.console.setBackground(Color.BLACK);
            this.console.setForeground(new Color(0, 255, 0));
            this.console.setFont(CONSOLE_FONT);

            // PROGRESS BAR
            this.progressBar = new Canvas() {
                @Override
                public void paint(final Graphics g) {
                    g.setColor(new Color(30, 30, 30));
                    g.fillRect(0, 0, this.getWidth(), this.getHeight());
                    g.setColor(new Color(0, 150, 255));
                    g.fillRect(0, 0, (int) (this.getWidth() * BootstrapWindow.this.progress / 100.0), this.getHeight());
                    g.setColor(Color.WHITE);
                    g.setFont(CONSOLE_FONT);
                    final String txt = BootstrapWindow.this.progress + "%";
                    g.drawString(txt, (this.getWidth() - g.getFontMetrics().stringWidth(txt)) / 2, 16);
                }
                @Override public Dimension getPreferredSize() { return new Dimension(960, 24); }
            };

            this.progressPanel = new Panel(new BorderLayout());
            this.progressPanel.setBackground(Color.BLACK);
            this.progressPanel.add(this.progressBar, BorderLayout.CENTER);
            this.progressPanel.setVisible(false);

            this.add(banner, BorderLayout.NORTH);
            this.add(this.console, BorderLayout.CENTER);
            this.add(this.progressPanel, BorderLayout.SOUTH);

            this.setSize(960, 540);
            this.setLocationRelativeTo(null);
            this.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(final java.awt.event.WindowEvent e) { System.exit(0); }
            });
            this.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(final java.awt.event.ComponentEvent e) {
                    BootstrapWindow.this.repaint(); }
            });

            // REDIRECT STDOUT/STDERR
            final PrintStream redirect = new PrintStream(new OutputStream() {
                private final StringBuilder line = new StringBuilder();
                @Override
                public void write(final int b) {
                    BootstrapWindow.this.originalOut.write(b);
                    if (b == '\n') {
                        final String text = this.line.toString();
                        this.line.setLength(0);
                        EventQueue.invokeLater(() -> {
                            BootstrapWindow.this.console.append(text + "\n");
                            BootstrapWindow.this.console.setCaretPosition(BootstrapWindow.this.console.getText().length());
                        });
                    } else {
                        this.line.append((char) b);
                    }
                }
            });
            System.setOut(redirect);
            System.setErr(redirect);
        }

        void setProgress(final int percent) {
            this.progress = percent;
            this.progressBar.repaint();
        }

        void setDownloading(final boolean active) {
            this.progress = 0;
            this.progressPanel.setVisible(active);
            this.validate();
        }
    }
}