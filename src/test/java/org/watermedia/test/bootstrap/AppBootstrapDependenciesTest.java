package org.watermedia.test.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AppBootstrapDependenciesTest {
    @Test
    void processedBootstrapVersionsMatchTheBuildPins() throws Exception {
        final Properties build = new Properties();
        try (final InputStream source = Files.newInputStream(Path.of("gradle.properties"))) { build.load(source); }
        final Properties bootstrap = new Properties();
        try (final InputStream source = AppBootstrap.class.getResourceAsStream("/bootstrap.properties")) {
            assertNotNull(source, "The bootstrap must carry its processed version resource");
            bootstrap.load(source);
        }
        for (final String key: List.of("log4j_version", "gson_version", "lwjgl_version",
                "joml_version", "javafx_version", "waterconfig_version")) {
            assertNotNull(build.getProperty(key), key);
            assertEquals(build.getProperty(key), bootstrap.getProperty(key), key);
        }
        assertNull(AppBootstrap.class.getResource("launcher.properties"));
    }

    @Test
    void dependenciesResolveWithoutDownloadedLibrariesOnEverySupportedPlatform() throws Exception {
        final String previousOs = System.getProperty("os.name");
        final String previousArch = System.getProperty("os.arch");
        final URL classes = AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        final URL resources = AppBootstrap.class.getResource("/bootstrap.properties").toURI().resolve(".").toURL();
        final Properties versions = new Properties();
        try (final InputStream source = resources.toURI().resolve("bootstrap.properties").toURL().openStream()) {
            versions.load(source);
        }
        try (final URLClassLoader loader = new URLClassLoader(new URL[] { classes, resources }, ClassLoader.getPlatformClassLoader())) {
            final Class<?> bootstrap = Class.forName(AppBootstrap.class.getName(), true, loader);
            final Method catalog = bootstrap.getDeclaredMethod("dependencies");
            catalog.setAccessible(true);
            for (final String[] platform: List.of(
                    new String[] { "Windows 10", "amd64", "windows", "win" },
                    new String[] { "Linux", "amd64", "linux", "linux" },
                    new String[] { "Linux", "aarch64", "linux-arm64", "linux-aarch64" },
                    new String[] { "Mac OS X", "amd64", "macos", "mac" },
                    new String[] { "Mac OS X", "aarch64", "macos-arm64", "mac-aarch64" })) {
                System.setProperty("os.name", platform[0]);
                System.setProperty("os.arch", platform[1]);
                final List<?> dependencies = (List<?>) catalog.invoke(null);
                assertEquals(platform[2].startsWith("macos") ? 22 : 21, dependencies.size());
                expect(dependencies, versions, "log4j-api", "log4j", null, false);
                expect(dependencies, versions, "log4j-core", "log4j", null, false);
                expect(dependencies, versions, "gson", "gson", null, false);
                expect(dependencies, versions, "joml", "joml", null, false);
                final Object config = expect(dependencies, versions, "waterconfig", "waterconfig", null, false);
                assertEquals("jitpack.io", ((URI) value(config, "source")).getHost());
                for (final String artifact: List.of("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb", "lwjgl-openal")) {
                    expect(dependencies, versions, artifact, "lwjgl", null, false);
                    expect(dependencies, versions, artifact, "lwjgl", "natives-" + platform[2], false);
                }
                expect(dependencies, versions, "lwjgl-vulkan", "lwjgl", null, true);
                expect(dependencies, versions, "lwjgl-shaderc", "lwjgl", null, true);
                expect(dependencies, versions, "lwjgl-shaderc", "lwjgl", "natives-" + platform[2], true);
                if (platform[2].startsWith("macos"))
                    expect(dependencies, versions, "lwjgl-vulkan", "lwjgl", "natives-" + platform[2], true);
                for (final String artifact: List.of("javafx-base", "javafx-graphics", "javafx-swing"))
                    expect(dependencies, versions, artifact, "javafx", platform[3], true);
            }
        } finally {
            System.setProperty("os.name", previousOs);
            System.setProperty("os.arch", previousArch);
        }
    }

    @Test
    void missingVersionsFailInsideTheRecoverableLoadingStep() throws Exception {
        final URL classes = AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        try (final URLClassLoader loader = new URLClassLoader(new URL[] { classes }, ClassLoader.getPlatformClassLoader())) {
            final Class<?> bootstrap = Class.forName(AppBootstrap.class.getName(), true, loader);
            final Method catalog = bootstrap.getDeclaredMethod("dependencies");
            catalog.setAccessible(true);
            final var failure = assertThrows(InvocationTargetException.class, () -> catalog.invoke(null));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("bootstrap.properties"));
        }
    }

    @Test
    void completeDownloadsReplaceTheCacheWithOrWithoutContentLength(@TempDir final Path directory) throws Exception {
        final byte[] payload = "complete dependency".getBytes(StandardCharsets.UTF_8);
        for (final boolean chunked: List.of(false, true)) {
            final Path destination = Files.writeString(directory.resolve("library.jar"), "previous");
            try (final LocalHttp server = LocalHttp.start("/library.jar", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, chunked ? 0 : payload.length);
                    exchange.getResponseBody().write(payload);
                }
            })) {
                download(server.uri("/library.jar"), destination);
            }
            assertArrayEquals(payload, Files.readAllBytes(destination));
            try (final var files = Files.list(directory)) { assertEquals(List.of(destination), files.toList()); }
        }
    }

    @Test
    void incompleteDownloadsPreserveTheCacheAndRemovePartialFiles(@TempDir final Path directory) throws Exception {
        final Path destination = Files.writeString(directory.resolve("library.jar"), "previous");
        try (final LocalHttp server = LocalHttp.start("/library.jar", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write(new byte[] { 1, 2, 3 });
            } finally {
                exchange.close();
            }
        })) {
            final var failure = assertThrows(InvocationTargetException.class, () -> download(server.uri("/library.jar"), destination));
            assertInstanceOf(IOException.class, failure.getCause());
        }
        assertEquals("previous", Files.readString(destination));
        try (final var files = Files.list(directory)) { assertEquals(List.of(destination), files.toList()); }
    }

    @Test
    void rejectedDownloadsDoNotCreateCacheEntries(@TempDir final Path directory) throws Exception {
        try (final LocalHttp server = LocalHttp.start("/missing.jar", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(404, -1); }
        })) {
            final var failure = assertThrows(InvocationTargetException.class,
                    () -> download(server.uri("/missing.jar"), directory.resolve("missing.jar")));
            assertInstanceOf(IOException.class, failure.getCause());
        }
        try (final var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    private static Object expect(final List<?> dependencies, final Properties versions, final String artifact,
                                 final String key, final String classifier, final boolean optional) throws Exception {
        final String version = versions.getProperty(key + "_version");
        final String name = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        for (final Object dependency: dependencies) {
            if (!name.equals(value(dependency, "name"))) continue;
            assertTrue(dependency.getClass().isRecord());
            assertEquals(optional, value(dependency, "optional"));
            final String group = artifact.startsWith("log4j") ? "org/apache/logging/log4j"
                    : artifact.startsWith("lwjgl") ? "org/lwjgl" : artifact.startsWith("javafx") ? "org/openjfx"
                    : artifact.equals("gson") ? "com/google/code/gson" : artifact.equals("joml") ? "org/joml" : "com/github/SrRapero720";
            final String repository = artifact.equals("waterconfig") ? "https://jitpack.io/" : "https://repo1.maven.org/maven2/";
            assertEquals(URI.create(repository + group + "/" + artifact + "/" + version + "/" + name), value(dependency, "source"));
            return dependency;
        }
        fail("Missing dependency: " + name);
        return null;
    }

    private static Object value(final Object dependency, final String name) throws Exception {
        final Method method = dependency.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(dependency);
    }

    private static void download(final URI source, final Path destination) throws Exception {
        final Method download = Arrays.stream(AppBootstrap.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("download")).findFirst().orElseThrow();
        download.setAccessible(true);
        download.invoke(null, source, destination, null);
    }
}
