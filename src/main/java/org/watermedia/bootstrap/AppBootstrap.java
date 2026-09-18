package org.watermedia.bootstrap;

import org.watermedia.bootstrap.app.WaterMediaApp;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.tools.IOTool;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Prepares the standalone application's libraries and supervises its JVM. */
public final class AppBootstrap {
    public static final int RELAUNCH_EXIT = 42;
    private static final String APP_FLAG = "watermedia.app";
    private static final String ENGINE_PROP = "watermedia.engine";
    private static final String MAVEN = "https://repo1.maven.org/maven2/";
    private static final Path LIBS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "watermedia", "libs");
    // THE RENDER SYSTEM READS AND WRITES THESE SAME PREFERENCES IN THE CHILD JVM.
    private static final Path ENGINE_FILE = Path.of("watermedia", "engine.cfg");
    private static final Path PLAYER_MODE_FILE = Path.of("watermedia", "playermode.cfg");
    private BootstrapWindow window;

    public interface Extension {
        default String name() { return this.getClass().getSimpleName(); }
        void load();
    }

    public static void main(final String... args) {
        final AppBootstrap bootstrap = new AppBootstrap();
        final boolean application = System.getProperty(APP_FLAG) != null;
        boolean firstLaunch = true;
        while (true) {
            try {
                if (application) {
                    WaterMediaApp.start(() -> {
                        WaterMediaApp.log("Searching for extensions...");
                        ServiceLoader.load(Extension.class).forEach(extension -> {
                            WaterMediaApp.log("Loading extension: " + extension.name());
                            extension.load();
                        });
                    });
                    return;
                }
                String engine = engine(System.getProperty(ENGINE_PROP));
                if (engine == null) engine = engine(IOTool.read(ENGINE_FILE.toFile()));
                final boolean choose = firstLaunch && (engine == null || Arrays.stream(args)
                        .anyMatch(arg -> arg.equalsIgnoreCase("--engine") || arg.equalsIgnoreCase("--select-engine")));
                if (choose && !GraphicsEnvironment.isHeadless()) {
                    final String preferred = "vulkan".equals(engine) ? "Vulkan" : "OpenGL";
                    engine = bootstrap.window().prompt("Starting with " + preferred + " in 5 seconds...", 5,
                            preferred, preferred.equals("OpenGL") ? "Vulkan" : "OpenGL").toLowerCase(Locale.ROOT);
                    try {
                        Files.createDirectories(ENGINE_FILE.getParent());
                        Files.writeString(ENGINE_FILE, engine);
                    } catch (final IOException failure) {
                        bootstrap.log("Cannot save the render engine: " + failure.getMessage());
                    }
                }
                final Set<Path> jars = bootstrap.prepare();
                if (bootstrap.window != null) {
                    EventQueue.invokeAndWait(bootstrap.window::dispose);
                    bootstrap.window = null;
                }
                final int code = relaunch(jars, args, engine == null ? "opengl" : engine);
                if (code != RELAUNCH_EXIT) System.exit(code);
                firstLaunch = false;
            } catch (final Throwable failure) {
                failure.printStackTrace();
                if (GraphicsEnvironment.isHeadless()) System.exit(1);
                try {
                    bootstrap.window().error(failure);
                    // A FAILED APP MAY HAVE PARTIALLY INITIALIZED NATIVES; RETRY IN A FRESH JVM.
                    if (application) System.exit(RELAUNCH_EXIT);
                } catch (final Exception displayFailure) {
                    displayFailure.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }

    private static String engine(final String value) {
        if (value == null) return null;
        final String name = value.trim().toLowerCase(Locale.ROOT);
        return name.equals("opengl") || name.equals("vulkan") ? name : null;
    }

    private static List<Dependency> dependencies() throws IOException {
        final Properties versions = new Properties();
        try (final InputStream source = AppBootstrap.class.getResourceAsStream("/bootstrap.properties")) {
            if (source == null) throw new IOException("Missing bootstrap.properties");
            versions.load(source);
        }
        final String platform = IOTool.platformClassifier();
        if (platform.equals("unsupported")) throw new IOException("Unsupported operating system or CPU architecture");
        final String natives = "natives-" + platform;
        final String opengl = version(versions, "opengl");
        final String openal = version(versions, "openal");
        final String vulkan = version(versions, "vulkan");
        final String javafx = version(versions, "javafx");
        final List<Dependency> dependencies = new ArrayList<>();
        for (final String artifact: List.of("log4j-api", "log4j-core"))
            dependencies.add(new Dependency(MAVEN, "org/apache/logging/log4j", artifact, version(versions, "log4j"), null, false));
        dependencies.add(new Dependency(MAVEN, "com/google/code/gson", "gson", version(versions, "gson"), null, false));
        dependencies.add(new Dependency(MAVEN, "org/joml", "joml", version(versions, "joml"), null, false));
        dependencies.add(new Dependency("https://jitpack.io/", "com/github/SrRapero720", "waterconfig", version(versions, "waterconfig"), null, false));
        for (final String artifact: List.of("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb", "lwjgl-openal")) {
            final String version = artifact.equals("lwjgl-openal") ? openal : opengl;
            dependencies.add(new Dependency(MAVEN, "org/lwjgl", artifact, version, null, false));
            dependencies.add(new Dependency(MAVEN, "org/lwjgl", artifact, version, natives, false));
        }
        // KEEP VULKAN AVAILABLE FOR RUNTIME ENGINE SWITCHES, EVEN WHEN STARTING WITH OPENGL.
        for (final String artifact: List.of("lwjgl-vulkan", "lwjgl-shaderc")) {
            dependencies.add(new Dependency(MAVEN, "org/lwjgl", artifact, vulkan, null, true));
            if (artifact.equals("lwjgl-shaderc") || platform.startsWith("macos"))
                dependencies.add(new Dependency(MAVEN, "org/lwjgl", artifact, vulkan, natives, true));
        }
        final String classifier = switch (platform) {
            case "windows", "windows-arm64" -> "win";
            case "macos" -> "mac";
            case "macos-arm64" -> "mac-aarch64";
            case "linux-arm64" -> "linux-aarch64";
            default -> "linux";
        };
        for (final String artifact: List.of("javafx-base", "javafx-graphics", "javafx-swing"))
            dependencies.add(new Dependency(MAVEN, "org/openjfx", artifact, javafx, classifier, true));
        return dependencies;
    }

    private static String version(final Properties versions, final String name) throws IOException {
        final String value = versions.getProperty(name + "_version");
        if (value == null || !value.matches("[0-9][A-Za-z0-9_.-]*"))
            throw new IOException("Invalid bootstrap dependency version: " + name);
        return value;
    }

    private record Dependency(String repository, String group, String artifact, String version, String classifier, boolean optional) {
        String name() {
            return this.artifact + "-" + this.version + (this.classifier == null ? "" : "-" + this.classifier) + ".jar";
        }

        URI source() {
            return URI.create(this.repository + this.group + "/" + this.artifact + "/" + this.version + "/" + this.name());
        }
    }

    private Set<Path> prepare() throws Exception {
        final Set<Path> jars = new LinkedHashSet<>();
        // OUR RESOURCES MUST WIN OVER IDENTICALLY NAMED RESOURCES IN EXTENSION JARS.
        jars.add(Path.of(AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        Files.createDirectories(LIBS_DIR);
        final String player = IOTool.read(PLAYER_MODE_FILE.toFile());
        final boolean javafx = player != null && player.trim().equalsIgnoreCase("JFX");
        for (final Dependency dependency: dependencies()) {
            final Path jar = LIBS_DIR.resolve(dependency.name());
            if (!Files.isRegularFile(jar)) {
                // CACHED JAVAFX IS ALWAYS INCLUDED; DOWNLOAD IT ONLY WHEN ITS PLAYER IS SELECTED.
                if (dependency.group().equals("org/openjfx") && !javafx) continue;
                if (!GraphicsEnvironment.isHeadless()) this.window();
                this.log("Downloading " + dependency.name());
                try {
                    download(dependency.source(), jar, this.window);
                } catch (final IOException failure) {
                    if (!dependency.optional()) throw failure;
                    this.log("Optional library unavailable: " + dependency.name() + " (" + failure.getMessage() + ")");
                    continue;
                }
            }
            jars.add(jar);
        }
        final File[] files = Path.of("").toAbsolutePath().toFile().listFiles();
        boolean binaries = AppBootstrap.class.getResource("/org/watermedia/binaries/WaterMediaBinaries.class") != null;
        if (files != null) {
            for (final File file: files) {
                final String name = file.getName().toLowerCase(Locale.ROOT);
                if (file.isFile() && name.endsWith(".jar") && (name.startsWith("watermedia_") || name.startsWith("wm_")
                        || name.startsWith("waterm_") || name.startsWith("wmedia_"))) {
                    jars.add(file.toPath());
                    binaries |= name.startsWith("watermedia_binaries");
                    this.log("Extension: " + file.getName());
                }
            }
        }
        if (!binaries) this.log("WaterMedia Binaries is not installed; video decoding is unavailable.");
        return jars;
    }

    private static void download(final URI source, final Path destination, final BootstrapWindow window) throws IOException {
        final String name = destination.getFileName().toString();
        if (window != null) window.progress("Downloading " + name, -1);
        final URLConnection connection = source.toURL().openConnection();
        connection.setRequestProperty("User-Agent", "WaterMedia");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        final long total = connection.getContentLengthLong();
        // UNIQUE PARTIAL FILES PREVENT CONCURRENT LAUNCHERS FROM OVERWRITING EACH OTHER'S TRANSFERS.
        final Path partial = Files.createTempFile(destination.getParent(), name + ".", ".part");
        long received = 0;
        try {
            try (final InputStream input = connection.getInputStream();
                 final OutputStream output = new BufferedOutputStream(Files.newOutputStream(partial))) {
                final byte[] buffer = new byte[IOTool.BUFFER_SIZE];
                long lastUpdate = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    received += count;
                    final long now = System.nanoTime();
                    if (window != null && now - lastUpdate >= 50_000_000L) {
                        lastUpdate = now;
                        window.progress(name, total > 0 ? (int) (received * 100 / total) : -1);
                    }
                }
            }
            if (total >= 0 && received != total)
                throw new IOException("Incomplete download of " + name + ": " + received + " of " + total + " bytes");
            IOTool.move(partial, destination);
        } catch (final IOException failure) {
            try { Files.deleteIfExists(partial); }
            catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        if (window != null) window.progress(name, 100);
    }

    private static int relaunch(final Set<Path> jars, final String[] args, final String engine) throws Exception {
        final Set<String> classpath = new LinkedHashSet<>();
        jars.forEach(jar -> classpath.add(jar.toAbsolutePath().toString()));
        classpath.addAll(Arrays.asList(System.getProperty("java.class.path", "").split(File.pathSeparator)));
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof final URLClassLoader urls) {
                for (final URL url: urls.getURLs()) {
                    if (url.getProtocol().equals("file")) classpath.add(Path.of(url.toURI()).toString());
                }
            }
        }
        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + APP_FLAG + "=true", "-D" + ENGINE_PROP + "=" + engine, "-Dlog4j2.StatusLogger.level=WARN",
                "-cp", String.join(File.pathSeparator, classpath), AppBootstrap.class.getName()));
        command.addAll(Arrays.asList(args));
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    private BootstrapWindow window() throws Exception {
        if (this.window == null) EventQueue.invokeAndWait(() -> this.window = new BootstrapWindow());
        return this.window;
    }

    private void log(final String message) {
        System.out.println(message);
        if (this.window != null) {
            final BootstrapWindow window = this.window;
            EventQueue.invokeLater(() -> window.details.append(message + "\n"));
        }
    }

    // ALL COMPONENT ACCESS RUNS ON THE EDT; DOWNLOADS AND CHILD PROCESS WAITS STAY ON THE MAIN THREAD.
    private static final class BootstrapWindow extends JFrame {
        private final JTextArea details = new JTextArea();
        private final JProgressBar progress = new JProgressBar();
        private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        private int exitCode;

        private BootstrapWindow() {
            super("WATERMeDIA: App Bootstrap");
            final JPanel content = new JPanel(new BorderLayout(12, 12));
            content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            content.setBackground(AppTheme.BG_0);
            this.setContentPane(content);
            final JLabel banner = new JLabel("WATERMeDIA", SwingConstants.CENTER);
            banner.setForeground(AppTheme.NEON);
            banner.setFont(banner.getFont().deriveFont(Font.BOLD, 28f));
            for (final String resource: List.of("icon.png", "banner.png")) {
                try (final InputStream input = IOTool.jarOpenFile(resource)) {
                    final Image image = input == null ? null : ImageIO.read(input);
                    if (image == null) continue;
                    if (resource.equals("icon.png")) this.setIconImage(image);
                    else {
                        final double scale = Math.min(800.0 / image.getWidth(null), 100.0 / image.getHeight(null));
                        banner.setIcon(new ImageIcon(image.getScaledInstance((int) (image.getWidth(null) * scale),
                                (int) (image.getHeight(null) * scale), Image.SCALE_SMOOTH)));
                        banner.setText(null);
                    }
                } catch (final IOException ignored) {}
            }
            content.add(banner, BorderLayout.NORTH);
            this.details.setEditable(false);
            this.details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            this.details.setBackground(AppTheme.BG_1);
            this.details.setForeground(AppTheme.TEXT);
            this.details.setCaretColor(AppTheme.TEXT);
            content.add(new JScrollPane(this.details), BorderLayout.CENTER);
            this.progress.setStringPainted(true);
            this.progress.setForeground(AppTheme.NEON);
            this.progress.setBackground(AppTheme.BG_2);
            this.actions.setOpaque(false);
            final JButton copy = new JButton("Copy details");
            copy.addActionListener(event -> {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(this.details.getText()), null);
                } catch (final IllegalStateException failure) {
                    this.progress.setString("Clipboard unavailable; select the text and copy it manually.");
                }
            });
            final JButton close = new JButton("Close");
            close.addActionListener(event -> System.exit(this.exitCode));
            final JPanel buttons = new JPanel(new BorderLayout());
            buttons.setOpaque(false);
            final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
            controls.setOpaque(false);
            controls.add(copy);
            controls.add(close);
            buttons.add(controls, BorderLayout.WEST);
            buttons.add(this.actions, BorderLayout.EAST);
            final JPanel footer = new JPanel(new BorderLayout(0, 8));
            footer.setOpaque(false);
            footer.add(this.progress, BorderLayout.NORTH);
            footer.add(buttons, BorderLayout.SOUTH);
            content.add(footer, BorderLayout.SOUTH);
            this.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            this.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent event) { System.exit(BootstrapWindow.this.exitCode); }
            });
            this.setMinimumSize(new Dimension(640, 360));
            this.setSize(900, 520);
            this.setLocationRelativeTo(null);
            this.setVisible(true);
        }

        private void progress(final String message, final int percent) {
            EventQueue.invokeLater(() -> {
                this.progress.setIndeterminate(percent < 0);
                this.progress.setValue(Math.max(0, percent));
                this.progress.setString(message + (percent < 0 ? "" : " — " + percent + "%"));
            });
        }

        private String prompt(final String message, final int seconds, final String... choices) throws Exception {
            final CompletableFuture<String> result = new CompletableFuture<>();
            final Timer timer = new Timer(seconds * 1000, event -> result.complete(choices[0]));
            timer.setRepeats(false);
            EventQueue.invokeAndWait(() -> {
                this.progress.setIndeterminate(false);
                this.progress.setValue(0);
                this.progress.setString(message);
                for (final String choice: choices) {
                    final JButton button = new JButton(choice);
                    button.addActionListener(event -> result.complete(choice));
                    this.actions.add(button);
                }
                this.actions.revalidate();
                if (seconds > 0) timer.start();
            });
            try {
                return result.get();
            } finally {
                EventQueue.invokeAndWait(() -> {
                    timer.stop();
                    this.actions.removeAll();
                    this.actions.revalidate();
                    this.actions.repaint();
                });
            }
        }

        private void error(final Throwable failure) throws Exception {
            final StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            EventQueue.invokeAndWait(() -> {
                this.exitCode = 1;
                this.setTitle("WATERMeDIA: Startup failed");
                this.details.setForeground(AppTheme.RED);
                final int start = this.details.getDocument().getLength();
                this.details.append("\n" + trace);
                this.details.setCaretPosition(start);
                this.toFront();
            });
            this.prompt("Startup failed. Copy the details or relaunch to try again.", 0, "Relaunch");
            EventQueue.invokeAndWait(() -> {
                this.exitCode = 0;
                this.setTitle("WATERMeDIA: App Bootstrap");
                this.details.setForeground(AppTheme.TEXT);
                this.details.setText("");
            });
        }
    }
}
