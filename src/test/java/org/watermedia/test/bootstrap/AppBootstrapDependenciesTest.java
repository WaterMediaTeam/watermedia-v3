package org.watermedia.test.bootstrap;

import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.tools.IOTool;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppBootstrapDependenciesTest {
    @Test
    void processedLauncherVersionsMatchTheBuildPins() throws Exception {
        final Properties build = new Properties();
        try (final InputStream source = Files.newInputStream(Path.of("gradle.properties"))) { build.load(source); }
        final Properties launcher = new Properties();
        try (final InputStream source = AppBootstrap.class.getResourceAsStream("launcher.properties")) {
            assertNotNull(source, "The launcher must carry its processed version resource");
            launcher.load(source);
        }
        for (final String key: List.of("log4j_version", "gson_version", "opengl_version", "openal_version",
                "vulkan_version", "joml_version", "javafx_version")) {
            assertNotNull(build.getProperty(key), key);
            assertEquals(build.getProperty(key), launcher.getProperty(key), key);
        }
    }

    @Test
    void launcherClasspathUsesTheProcessedVersionsForBindingsAndNatives() throws Exception {
        final Properties versions = new Properties();
        try (final InputStream source = AppBootstrap.class.getResourceAsStream("launcher.properties")) {
            assertNotNull(source);
            versions.load(source);
        }
        final String os = IOTool.platformClassifier();
        final var field = AppBootstrap.class.getDeclaredField("DEPS");
        field.setAccessible(true);
        final List<String[]> required = Arrays.asList((String[][]) field.get(null));
        assertEquals(14, required.size());
        expect(required, "org/apache/logging/log4j", "log4j-api", versions.getProperty("log4j_version"), null);
        expect(required, "org/apache/logging/log4j", "log4j-core", versions.getProperty("log4j_version"), null);
        expect(required, "com/google/code/gson", "gson", versions.getProperty("gson_version"), null);
        expect(required, "org/joml", "joml", versions.getProperty("joml_version"), null);
        for (final String artifact: List.of("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb")) {
            expect(required, "org/lwjgl", artifact, versions.getProperty("opengl_version"), null);
            expect(required, "org/lwjgl", artifact, versions.getProperty("opengl_version"), "natives-" + os);
        }
        expect(required, "org/lwjgl", "lwjgl-openal", versions.getProperty("openal_version"), null);
        expect(required, "org/lwjgl", "lwjgl-openal", versions.getProperty("openal_version"), "natives-" + os);

        final var vulkanMethod = AppBootstrap.class.getDeclaredMethod("vulkanDeps");
        vulkanMethod.setAccessible(true);
        final List<?> vulkan = (List<?>) vulkanMethod.invoke(null);
        assertEquals(os.startsWith("macos") ? 4 : 3, vulkan.size());
        expect(vulkan, "org/lwjgl", "lwjgl-vulkan", versions.getProperty("vulkan_version"), null);
        expect(vulkan, "org/lwjgl", "lwjgl-shaderc", versions.getProperty("vulkan_version"), null);
        expect(vulkan, "org/lwjgl", "lwjgl-shaderc", versions.getProperty("vulkan_version"), "natives-" + os);
        if (os.startsWith("macos"))
            expect(vulkan, "org/lwjgl", "lwjgl-vulkan", versions.getProperty("vulkan_version"), "natives-" + os);

        final var javafxMethod = AppBootstrap.class.getDeclaredMethod("javafxDeps");
        javafxMethod.setAccessible(true);
        final List<?> javafx = (List<?>) javafxMethod.invoke(null);
        assertEquals(3, javafx.size());
        final String classifier = switch (os) {
            case "macos" -> "mac";
            case "macos-arm64" -> "mac-aarch64";
            case "linux-arm64" -> "linux-aarch64";
            case "windows", "windows-arm64" -> "win";
            default -> "linux";
        };
        for (final String artifact: List.of("javafx-base", "javafx-graphics", "javafx-swing"))
            expect(javafx, "org/openjfx", artifact, versions.getProperty("javafx_version"), classifier);
    }

    private static void expect(final List<?> dependencies, final String group, final String artifact,
                               final String version, final String classifier) {
        final String filename = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        final String[] actual = dependencies.stream().map(String[].class::cast)
                .filter(item -> item[0].equals(filename)).findFirst().orElse(null);
        assertNotNull(actual, filename);
        assertArrayEquals(new String[] { filename, group + "/" + artifact + "/" + version + "/" + filename }, actual);
    }
}
