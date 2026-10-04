package org.watermedia.test.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.jar.JarFile;

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
            final Method catalog = bootstrap.getDeclaredMethod("dependencies", Properties.class);
            catalog.setAccessible(true);
            for (final String[] platform: List.of(
                    new String[] { "Windows 10", "amd64", "windows", "win" },
                    new String[] { "Linux", "amd64", "linux", "linux" },
                    new String[] { "Linux", "aarch64", "linux-arm64", "linux-aarch64" },
                    new String[] { "Mac OS X", "amd64", "macos", "mac" },
                    new String[] { "Mac OS X", "aarch64", "macos-arm64", "mac-aarch64" })) {
                System.setProperty("os.name", platform[0]);
                System.setProperty("os.arch", platform[1]);
                final List<?> dependencies = (List<?>) catalog.invoke(null, versions);
                assertEquals(platform[2].startsWith("macos") ? 21 : 20, dependencies.size());
                expect(dependencies, versions, "log4j-api", "log4j", null, false);
                expect(dependencies, versions, "log4j-core", "log4j", null, false);
                expect(dependencies, versions, "gson", "gson", null, false);
                expect(dependencies, versions, "joml", "joml", null, false);
                for (final Object dependency: dependencies)
                    assertFalse(value(dependency, "name").toString().startsWith("waterconfig-"));
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
            final Method catalog = bootstrap.getDeclaredMethod("versions");
            catalog.setAccessible(true);
            final var failure = assertThrows(InvocationTargetException.class, () -> catalog.invoke(null));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("bootstrap.properties"));
        }
    }

    @Test
    void bundledConfigLoadsWithoutExternalLibrariesAndSharesLoaderMetadata(@TempDir final Path directory) throws Exception {
        final URL classes = AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        final URL resources = AppBootstrap.class.getResource("/bootstrap.properties").toURI().resolve(".").toURL();
        final JsonObject metadata;
        try (final var reader = new InputStreamReader(AppBootstrap.class.getResourceAsStream("/META-INF/jarjar/metadata.json"), StandardCharsets.UTF_8)) {
            metadata = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("jars").get(0).getAsJsonObject();
        }
        final String resource = "/" + metadata.get("path").getAsString();
        final String version = metadata.getAsJsonObject("version").get("artifactVersion").getAsString();
        try (final var reader = new InputStreamReader(AppBootstrap.class.getResourceAsStream("/fabric.mod.json"), StandardCharsets.UTF_8)) {
            assertEquals(resource.substring(1), JsonParser.parseReader(reader).getAsJsonObject()
                    .getAsJsonArray("jars").get(0).getAsJsonObject().get("file").getAsString());
        }
        try (final URLClassLoader loader = new URLClassLoader(new URL[] { classes, resources }, ClassLoader.getPlatformClassLoader())) {
            final Class<?> bootstrap = Class.forName(AppBootstrap.class.getName(), true, loader);
            final Method extract = bootstrap.getDeclaredMethod("extract", String.class, Path.class);
            extract.setAccessible(true);
            final Path first = (Path) extract.invoke(null, resource, directory);
            final Path second = (Path) extract.invoke(null, resource, directory);
            assertNotEquals(first, second, "Concurrent applications must not replace each other's open jars");
            assertEquals(-1, Files.mismatch(first, second));
            try (final InputStream input = loader.getResourceAsStream(resource.substring(1))) {
                assertNotNull(input);
                assertArrayEquals(input.readAllBytes(), Files.readAllBytes(first));
            }
            try (final JarFile jar = new JarFile(first.toFile())) {
                assertEquals("GAMELIBRARY", jar.getManifest().getMainAttributes().getValue("FMLModType"));
                assertEquals("waterconfig", jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
                assertNotNull(jar.getEntry("META-INF/services/me.srrapero720.waterconfig.api.ICodec"));
                assertNotNull(jar.getEntry("META-INF/services/me.srrapero720.waterconfig.api.formats.IFormatCodec"));
                try (final var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                    final JsonObject fabric = JsonParser.parseReader(reader).getAsJsonObject();
                    assertEquals("com_github_srrapero720_waterconfig", fabric.get("id").getAsString());
                    assertEquals(version, fabric.get("version").getAsString());
                }
            }
            try (final URLClassLoader config = new URLClassLoader(new URL[] { first.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
                assertSame(config, Class.forName("me.srrapero720.waterconfig.WaterConfig", true, config).getClassLoader());
            }
        }
    }

    @Test
    void missingBundledLibrariesDoNotLeaveExtractedFiles(@TempDir final Path directory) throws Exception {
        final Method extract = AppBootstrap.class.getDeclaredMethod("extract", String.class, Path.class);
        extract.setAccessible(true);
        final var failure = assertThrows(InvocationTargetException.class,
                () -> extract.invoke(null, "/META-INF/jarjar/missing.jar", directory));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("Missing bundled library"));
        try (final var files = Files.list(directory)) { assertEquals(0, files.count()); }
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
                    : artifact.equals("gson") ? "com/google/code/gson" : "org/joml";
            assertEquals(URI.create("https://repo1.maven.org/maven2/" + group + "/" + artifact + "/" + version + "/" + name), value(dependency, "source"));
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
