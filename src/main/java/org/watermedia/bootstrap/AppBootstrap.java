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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List; // EXPLICIT: DISAMBIGUATES FROM java.awt.List (BOTH WILDCARDS ARE IMPORTED)
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// BOOTSTRAP LAUNCHER FOR WATERMEDIA STANDALONE APPLICATION
// THE JVM LOCKS THE CLASSPATH AT STARTUP. SPAWNING A FRESH JVM WITH THE FULL CLASSPATH IS THE ONLY RELIABLE SOLUTION.
public class AppBootstrap {
    // DOWNLOADED DEPENDENCY JARS ARE A REBUILDABLE CACHE — THEY LIVE IN THE SYSTEM TEMP DIR, NOT NEXT TO THE
    // USER'S PERSISTENT DATA.
    private static final Path LIBS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "watermedia/libs");
    // PERSISTENT APP DATA ROOTS AT THE PROCESS WORKING DIRECTORY (THE DEV `run/` FOLDER), ALONGSIDE config/,
    // logs/ AND THE SERVER STORAGE. THE RELATIVE PATH RESOLVES AGAINST THE CWD, AND THE APP CHILD JVM INHERITS
    // THAT SAME CWD ON RELAUNCH — SO ITS RenderSystem READS THE VERY SAME engine.cfg.
    private static final Path DATA_DIR = Path.of("watermedia");
    private static final Path ENGINE_FILE = DATA_DIR.resolve("engine.cfg");
    // POPUP PLAYER TARGET (IN_APP/AWT/JFX) — KEEP IN SYNC WITH RenderSystem.PLAYER_MODE_FILE. ONLY "JFX"
    // NEEDS EXTRA DEPS (JAVAFX); AWT IS IN THE JDK.
    private static final Path PLAYER_MODE_FILE = DATA_DIR.resolve("playermode.cfg");
    private static final String MAVEN = "https://repo1.maven.org/maven2/";
    private static final String APP_FLAG = "watermedia.app";
    private static final String ENGINE_PROP = "watermedia.engine";
    // THE APP EXITS WITH THIS CODE TO ASK THIS SUPERVISING LAUNCHER TO RE-PROVISION (E.G. PULL JAVAFX) AND SPAWN AGAIN
    public static final int RELAUNCH_EXIT = 42;
    // JAVAFX MUST MATCH THE JAVA 21 RUNTIME — A NEWER JAVAFX WOULD FAIL WITH UnsupportedClassVersionError
    private static final String JAVAFX_VERSION = "21";
    private static final String OS = IOTool.platformClassifier();

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

    private static class BootstrapScan {
        private final List<Path> jars = new ArrayList<>();
        private final List<String[]> toDownload = new ArrayList<>();
        // VULKAN + SHADERC JARS/NATIVES — PROVISIONED UNCONDITIONALLY BUT BEST-EFFORT, SO A DOWNLOAD FAILURE
        // NEVER BLOCKS THE LAUNCH (THE APP STILL RUNS ON OPENGL; RUNTIME HOT-SWAP TO VULKAN IS JUST UNAVAILABLE).
        private final List<String[]> optionalDownload = new ArrayList<>();
        private boolean binariesFound;

        // MANDATORY READINESS — VULKAN DEPS ARE BEST-EFFORT AND NEVER GATE THE LAUNCH.
        private boolean ready() {
            return this.binariesFound && this.toDownload.isEmpty();
        }

        // FULLY PROVISIONED, INCLUDING VULKAN — GATES THE FAST PATH SO A FIRST LAUNCH PULLS VULKAN ONCE.
        private boolean complete() {
            return this.ready() && this.optionalDownload.isEmpty();
        }
    }

    // LOGGER — \r clears the GUI console line; ANSI codes color both GUI and terminal
    private static void info(final String msg) { System.out.println("\r" + ANSI_GREEN + msg + ANSI_RESET); }
    private static void warn(final String msg) { System.out.println("\r" + ANSI_YELLOW + msg + ANSI_RESET); }
    private static void error(final String msg) { System.out.println("\r" + ANSI_RED + msg + ANSI_RESET); }
    private static void live(final String msg) { System.out.print("\r" + ANSI_BLUE + msg + ANSI_RESET); }

    public interface Extension {
        // DISPLAY NAME FOR LOGS AND THE ISSUE-REPORT DIAGNOSTICS; DEFAULTS TO THE CLASS SIMPLE NAME
        default String name() { return this.getClass().getSimpleName(); }
        void load();
    }

    public static void main(final String... args) {
        launchArgs = args;
        if (System.getProperty(APP_FLAG) != null) {
            try {
                WaterMediaApp.start(() -> {
                    WaterMediaApp.log("Launched with embedded WaterMediaApp Bootstrap");
                    WaterMediaApp.log("Searching for extensions...");
                    ServiceLoader.load(Extension.class).forEach(ext -> {
                        WaterMediaApp.log("Loading extension: " + ext.name());
                        ext.load();
                    });
                });
            } catch (final Throwable e) {
                showError(e);
            }
            return;
        }

        try {
            // ENGINE IS PERSISTED IN engine.cfg; --engine FORCES THE CHOOSER EVEN WHEN DEPS ARE READY. AN
            // EXPLICIT -Dwatermedia.engine ON THIS LAUNCHER JVM WINS OVER THE PERSISTED FILE (RUN CONFIGS).
            // SUPERVISE THE APP JVM: A NORMAL EXIT ENDS THE LAUNCHER; A RELAUNCH_EXIT RE-PROVISIONS (E.G. PULLS
            // JAVAFX AFTER A FRESH VK+JavaFX CHOICE) AND SPAWNS AGAIN, WITHOUT RE-PROMPTING THE ENGINE CHOOSER.
            boolean firstRun = true;
            while (true) {
                final boolean forceChooser = firstRun && (contains(args, "--engine") || contains(args, "--select-engine"));
                final String forced = normalizeEngine(System.getProperty(ENGINE_PROP));
                final String persisted = forced != null ? forced : readEngine();
                final int code;
                if (!forceChooser && persisted != null) {
                    final BootstrapScan quickScan = scanBootstrap(false);
                    // FAST PATH REQUIRES VULKAN (AND JAVAFX WHEN VK+JavaFX) FULLY CACHED, SO A FIRST LAUNCH
                    // FALLS THROUGH TO THE CONSOLE ONCE TO PULL THEM; AFTERWARDS EVERY LAUNCH FAST-PATHS.
                    code = quickScan.complete()
                            ? relaunch(quickScan.jars, args, persisted)
                            : launchWithConsole(args, persisted, firstRun);
                } else {
                    code = launchWithConsole(args, persisted, firstRun);
                }
                if (code != RELAUNCH_EXIT) System.exit(code);
                firstRun = false;
            }
        } catch (final Exception e) {
            showError(e);
        }
    }

    private static int launchWithConsole(final String[] args, final String persisted, final boolean showChooser) throws Exception {
        window = new BootstrapWindow();
        info("WATERMeDIA: App Bootstrap - On: " + OS);
        info("Dependencies directory: " + LIBS_DIR);
        info("=============================================");
        Files.createDirectories(LIBS_DIR);

        // LET THE USER PICK THE RENDER ENGINE ON A FIRST LAUNCH (DEFAULT = PERSISTED OR OPENGL); AN
        // APP-REQUESTED RELAUNCH SKIPS THE CHOOSER AND REUSES THE PERSISTED ENGINE.
        final String engine;
        if (showChooser) {
            engine = window.chooseEngine(persisted != null ? persisted : "opengl");
            writeEngine(engine);
        } else {
            engine = persisted != null ? persisted : "opengl";
        }
        info("Render engine: " + engine.toUpperCase());

        final BootstrapScan scan = scanBootstrap(true);
        if (!scan.binariesFound) {
            showError("WaterMedia Binaries JAR not found.\nDownload the latest version from CurseForge.");
            return 1;
        }

        // DOWNLOAD MANDATORY DEPS (BASE LWJGL + NATIVES) — A FAILURE HERE IS FATAL.
        for (final String[] dep: scan.toDownload) {
            final Path d = LIBS_DIR.resolve(dep[0]);
            download(MAVEN + dep[1], d);
        }

        // DOWNLOAD VULKAN DEPS BEST-EFFORT — A FAILURE DEGRADES TO OPENGL-ONLY WITHOUT ABORTING THE LAUNCH.
        for (final String[] dep: scan.optionalDownload) {
            final Path d = LIBS_DIR.resolve(dep[0]);
            try {
                download(MAVEN + dep[1], d);
            } catch (final Exception e) {
                warn("[SKIP] Optional Vulkan dependency failed: " + dep[0] + " (" + e.getMessage() + ")");
                try {
                    Files.deleteIfExists(d);
                } catch (final IOException ignored) {}
            }
        }

        final BootstrapScan launchScan = scanBootstrap(false);
        if (!launchScan.ready()) {
            showError("Some dependencies are still missing after bootstrap scan.");
            return 1;
        }

        info("Launching...");
        window.dispose();
        return relaunch(launchScan.jars, args, engine);
    }

    private static BootstrapScan scanBootstrap(final boolean log) throws Exception {
        final BootstrapScan scan = new BootstrapScan();

        // WATERMEDIA'S OWN JAR GOES FIRST ON THE CLASSPATH SO ITS RESOURCES (icon.png, pack.png, banner.png)
        // WIN OVER watermedia-binaries' COLLIDING RESOURCES DURING CLASSLOADER LOOKUPS IN THE CHILD JVM
        scan.jars.add(Path.of(AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

        // FIND BINARIES
        final Path binaries = findLocalJar("watermedia_binaries");
        final boolean classpath = hasBinariesOnClasspath();
        scan.binariesFound = binaries != null || classpath;
        if (binaries != null) scan.jars.add(binaries);
        if (log) {
            if (scan.binariesFound) info("[OK] WaterMedia Binaries found");
            else warn("[MISSING] WaterMedia Binaries JAR");
        }

        // COLLECT DEPS
        for (final String[] dep: DEPS) {
            final Path p = LIBS_DIR.resolve(dep[0]);
            if (Files.isRegularFile(p)) {
                scan.jars.add(p);
                if (log) info("[FOUND] " + dep[0]);
            } else {
                scan.toDownload.add(dep);
                if (log) warn("[MISSING] " + dep[0]);
            }
        }

        // COLLECT NATIVES
        for (final String mod: NATIVES) {
            final String fn = mod + "-3.3.6-natives-" + OS + ".jar";
            final Path p = LIBS_DIR.resolve(fn);
            if (Files.isRegularFile(p)) {
                scan.jars.add(p);
                if (log) info("[FOUND] " + fn);
            } else {
                scan.toDownload.add(new String[]{fn, "org/lwjgl/" + mod + "/3.3.6/" + fn});
                if (log) warn("[MISSING] " + fn);
            }
        }

        // COLLECT VULKAN DEPS UNCONDITIONALLY (VULKAN + SHADERC JARS/NATIVES) SO THE CHILD JVM CAN HOT-SWAP
        // TO VULKAN AT RUNTIME REGARDLESS OF THE ENGINE IT BOOTS WITH. THESE ARE OPTIONAL: A MISSING ONE GOES
        // TO optionalDownload (BEST-EFFORT) RATHER THAN toDownload, SO IT NEVER GATES THE LAUNCH.
        for (final String[] dep: vulkanDeps()) {
            final Path p = LIBS_DIR.resolve(dep[0]);
            if (Files.isRegularFile(p)) {
                scan.jars.add(p);
                if (log) info("[FOUND] " + dep[0]);
            } else {
                scan.optionalDownload.add(dep);
                if (log) warn("[MISSING] " + dep[0]);
            }
        }

        // JAVAFX — ALWAYS INCLUDE CACHED JARS SO A CACHED INSTALL NEEDS NO RESTART TO SWITCH TO VK+JavaFX;
        // DOWNLOAD (BEST-EFFORT, LIKE VULKAN) ONLY WHEN THE PLAYER MODE IS VK+JavaFX AND A JAR IS MISSING.
        final boolean needJfx = javafxMode();
        for (final String[] dep: javafxDeps()) {
            final Path p = LIBS_DIR.resolve(dep[0]);
            if (Files.isRegularFile(p)) {
                scan.jars.add(p);
                if (log) info("[FOUND] " + dep[0]);
            } else if (needJfx) {
                scan.optionalDownload.add(dep);
                if (log) warn("[MISSING] " + dep[0]);
            }
        }

        // SEARCH FOR EXTENSIONS
        final File[] files = new File("").getAbsoluteFile().listFiles();
        if (files != null) {
            if (log) info("=== Searching for extensions in " + new File("").getAbsolutePath());
            for (final File f: files) {
                final String name = f.getName().toLowerCase();
                if ((name.startsWith("watermedia_") || name.startsWith("wm_") || name.startsWith("waterm_") || name.startsWith("wmedia_")) && f.getName().endsWith(".jar")) {
                    scan.jars.add(f.getAbsoluteFile().toPath());
                    if (log) info("[FOUND] Extension: " + f.getName());
                }
            }
        }

        return scan;
    }

    private static boolean hasBinariesOnClasspath() {
        try {
            Class.forName("org.watermedia.binaries.WaterMediaBinaries", false, AppBootstrap.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        }
    }

    private static boolean contains(final String[] args, final String flag) {
        for (final String a: args) if (flag.equalsIgnoreCase(a)) return true;
        return false;
    }

    // NORMALIZES AN ENGINE STRING TO "opengl"/"vulkan", OR null IF ABSENT/UNRECOGNIZED.
    private static String normalizeEngine(final String value) {
        if (value == null) return null;
        final String v = value.trim().toLowerCase();
        return v.equals("vulkan") || v.equals("opengl") ? v : null;
    }

    // READS THE PERSISTED ENGINE CHOICE ("opengl"/"vulkan"), OR null IF NEVER CHOSEN.
    private static String readEngine() {
        try {
            if (Files.isRegularFile(ENGINE_FILE)) {
                final String s = Files.readString(ENGINE_FILE).trim().toLowerCase();
                if (s.equals("vulkan") || s.equals("opengl")) return s;
            }
        } catch (final IOException ignored) {}
        return null;
    }

    private static void writeEngine(final String engine) {
        try {
            Files.createDirectories(DATA_DIR);
            Files.writeString(ENGINE_FILE, engine);
        } catch (final IOException e) {
            warn("Failed to persist engine choice: " + e.getMessage());
        }
    }

    // TRUE WHEN THE PERSISTED PLAYER TARGET IS VK+JavaFX, THE ONLY MODE THAT NEEDS THE JAVAFX JARS PULLED.
    private static boolean javafxMode() {
        try {
            if (Files.isRegularFile(PLAYER_MODE_FILE)) {
                return Files.readString(PLAYER_MODE_FILE).trim().equalsIgnoreCase("JFX");
            }
        } catch (final IOException ignored) {}
        return false;
    }

    // MAPS THE LWJGL-STYLE PLATFORM TOKEN TO JAVAFX'S CLASSIFIER (win / mac[-aarch64] / linux[-aarch64]).
    private static String javafxClassifier() {
        return switch (OS) {
            case "macos" -> "mac";
            case "macos-arm64" -> "mac-aarch64";
            case "linux-arm64" -> "linux-aarch64";
            case "windows", "windows-arm64" -> "win";
            default -> "linux";
        };
    }

    // JAVAFX JARS (base + graphics + controls). EACH PLATFORM-CLASSIFIED JAR HOLDS BOTH THE CLASSES AND THE
    // NATIVES, WHICH JAVAFX SELF-EXTRACTS AT RUNTIME — SO NO SEPARATE NATIVES JARS LIKE LWJGL.
    private static List<String[]> javafxDeps() {
        final String clf = javafxClassifier();
        final List<String[]> deps = new ArrayList<>();
        for (final String mod: new String[]{"javafx-base", "javafx-graphics", "javafx-swing"}) {
            final String fn = mod + "-" + JAVAFX_VERSION + "-" + clf + ".jar";
            deps.add(new String[]{fn, "org/openjfx/" + mod + "/" + JAVAFX_VERSION + "/" + fn});
        }
        return deps;
    }

    // VULKAN JARS PROVISIONED ON TOP OF THE BASE DEPS/NATIVES SO A RUNTIME HOT-SWAP TO VULKAN IS ALWAYS
    // POSSIBLE: THE LWJGL VULKAN BINDINGS (PLUS MOLTENVK NATIVES ONLY ON MACOS) AND LWJGL SHADERC (BINDINGS +
    // NATIVES, EVERY PLATFORM). RETURNED UNCONDITIONALLY — THE INITIAL ENGINE CHOICE NO LONGER DECIDES THESE.
    private static List<String[]> vulkanDeps() {
        final List<String[]> deps = new ArrayList<>();
        deps.add(new String[]{"lwjgl-vulkan-3.3.6.jar", "org/lwjgl/lwjgl-vulkan/3.3.6/lwjgl-vulkan-3.3.6.jar"});
        deps.add(new String[]{"lwjgl-shaderc-3.3.6.jar", "org/lwjgl/lwjgl-shaderc/3.3.6/lwjgl-shaderc-3.3.6.jar"});
        final String shaderc = "lwjgl-shaderc-3.3.6-natives-" + OS + ".jar";
        deps.add(new String[]{shaderc, "org/lwjgl/lwjgl-shaderc/3.3.6/" + shaderc});
        if (OS.startsWith("macos")) {
            final String vk = "lwjgl-vulkan-3.3.6-natives-" + OS + ".jar";
            deps.add(new String[]{vk, "org/lwjgl/lwjgl-vulkan/3.3.6/" + vk});
        }
        return deps;
    }

    private static int relaunch(final List<Path> jars, final String[] args, final String engine) throws Exception {
        final StringJoiner cp = new StringJoiner(File.pathSeparator);
        jars.forEach(j -> cp.add(j.toAbsolutePath().toString()));

        final String cur = System.getProperty("java.class.path");
        if (cur != null && !cur.isBlank()) cp.add(cur);

        for (ClassLoader cl = Thread.currentThread().getContextClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof final URLClassLoader u) {
                for (final URL url: u.getURLs()) {
                    try {
                        cp.add(Path.of(url.toURI()).toString());
                    } catch (final Exception ignored) {}
                }
            }
        }

        final List<String> cmd = new ArrayList<>(Arrays.asList(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + APP_FLAG + "=true", "-D" + ENGINE_PROP + "=" + engine, "-Dlog4j2.StatusLogger.level=WARN",
                "-cp", cp.toString(), AppBootstrap.class.getName()));
        cmd.addAll(Arrays.asList(args));
        // SUPERVISE THE CHILD: RETURN ITS EXIT CODE SO THE LAUNCHER CAN RE-PROVISION ON A RELAUNCH REQUEST
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    private static Path findLocalJar(final String prefix) {
        final File[] files = new File("").getAbsoluteFile().listFiles();
        if (files != null) {
            for (final File f: files) {
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
        // STREAM INTO A SIBLING .part FILE AND PROMOTE IT ATOMICALLY ONLY ON A VERIFIED-COMPLETE TRANSFER,
        // SO A MID-STREAM FAILURE NEVER LEAVES A TRUNCATED JAR THAT scanBootstrap WOULD TRUST AS CACHED.
        final Path part = dest.resolveSibling(name + ".part");
        long dl = 0;
        try {
            try (final InputStream in = new BufferedInputStream(c.getInputStream());
                 final OutputStream out = new BufferedOutputStream(Files.newOutputStream(part))) {
                final byte[] buf = new byte[DOWNLOAD_BUF];
                // TRANSFER AT LINE SPEED; THROTTLE ONLY THE PROGRESS REPAINT (~20/s) SO THE LIVE LINE
                // NEVER BECOMES THE BOTTLENECK LIKE THE OLD PER-READ SLEEP (CAPPED THROUGHPUT AT ~1.6MB/s).
                long lastRepaint = 0;
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                    dl += r;
                    final long now = System.currentTimeMillis();
                    if (now - lastRepaint >= 50 || dl == total) {
                        lastRepaint = now;
                        if (total > 0) {
                            live(String.format("[DOWNLOADING] %s %d%% %.1f/%.1fMB", name,
                                    (int) (dl * 100 / total), dl / 1_048_576.0, total / 1_048_576.0));
                        } else {
                            live(String.format("[DOWNLOADING] %s %.1fMB", name, dl / 1_048_576.0));
                        }
                    }
                }
            }
            // VALIDATE THE TRANSFERRED SIZE AGAINST Content-Length WHEN THE SERVER DECLARED ONE (>= 0).
            if (total >= 0 && dl != total)
                throw new IOException("Truncated download of " + name + ": got " + dl + " of " + total + " bytes");
            // ATOMIC_MOVE GUARANTEES THE FINAL PATH ONLY EVER SEES A COMPLETE JAR; FALL BACK TO A PLAIN
            // REPLACE WHEN THE FILESYSTEM CAN'T DO IT ATOMICALLY.
            try {
                Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException unsupported) {
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final Exception e) {
            // DROP THE PARTIAL ON ANY FAILURE (MANDATORY DEPS INCLUDED) SO THE NEXT SCAN RE-DOWNLOADS IT.
            try { Files.deleteIfExists(part); } catch (final IOException ignored) {}
            throw e;
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
            for (final String line: lines) {
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
            // RE-ENTER main() OFF THE EDT: THE LAUNCHER PARKS ITS THREAD (chooseEngine's lock.wait,
            // ProcessBuilder.waitFor) AND WOULD DEADLOCK THE UI IF DRIVEN ON THE EVENT DISPATCH THREAD.
            ThreadTool.createStarted("WaterMedia-Relaunch", () -> main(launchArgs));
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

        // SHOWS TWO ENGINE BUTTONS DURING A COUNTDOWN. A CLICK COMMITS IMMEDIATELY; A TIMEOUT COMMITS
        // THE DEFAULT (THE PERSISTED ENGINE, OR OPENGL ON FIRST RUN). RETURNS THE CHOSEN ENGINE.
        String chooseEngine(final String defaultEngine) {
            final Object lock = new Object();
            final String[] choice = {null};
            final Panel bar = new Panel(new FlowLayout(FlowLayout.CENTER, 20, 12));
            bar.setBackground(C_BLACK);
            bar.add(engineButton("OpenGL", "opengl", choice, lock));
            bar.add(engineButton("Vulkan", "vulkan", choice, lock));
            this.add(bar, BorderLayout.SOUTH);
            this.validate();

            synchronized (lock) {
                for (int i = LAUNCH_DELAY_S; i > 0 && choice[0] == null; i--) {
                    live("[ENGINE] Launching with " + defaultEngine.toUpperCase() + " in " + i + "s... click OpenGL or Vulkan to choose");
                    try { lock.wait(1000); } catch (final InterruptedException ignored) {}
                }
            }
            final String selected;
            synchronized (lock) { selected = choice[0] != null ? choice[0] : defaultEngine; }
            EventQueue.invokeLater(() -> {
                this.remove(bar);
                this.validate();
                this.repaint();
            });
            return selected;
        }

        private static Button engineButton(final String label, final String value, final String[] choice, final Object lock) {
            final Button b = new Button(label);
            b.setFont(FONT.deriveFont(Font.BOLD));
            b.setPreferredSize(new Dimension(BTN_W, BTN_H));
            b.setBackground(C_GRAY);
            b.setForeground(C_WHITE);
            b.addActionListener(e -> {
                synchronized (lock) {
                    if (choice[0] == null) choice[0] = value;
                    lock.notifyAll();
                }
            });
            return b;
        }
    }
}
