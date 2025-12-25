package org.watermedia.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;

public class IOTool {

    public static String getVersion() {
        try {
            final Manifest manifest = new Manifest(jarOpenResource("/META-INF/MANIFEST.MF"));
            final String version = manifest.getMainAttributes().getValue("version");
            return version == null ? "3.0.0-unknown" : version;
        } catch (final IOException e) {
            throw new RuntimeException("Failed to read self manifest", e);
        }
    }

    public static String jarReadString(String from) {
        try (final var is = jarOpenResource(from)) {
            final byte[] bytes = readAllBytes(is);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return null;
        }
    }

    public static InputStream jarOpenResource(String source) {
        return jarOpenResource$classLoader(source, IOTool.class.getClassLoader());
    }

    private static InputStream jarOpenResource$classLoader(final String source, final ClassLoader classLoader) {
        InputStream is = classLoader.getResourceAsStream(source);
        if (is == null && source.startsWith("/")) is = classLoader.getResourceAsStream(source.substring(1));
        return is;
    }

    public static byte[] readAllBytes(final InputStream in) throws Exception {
        if (in == null)
            throw new NullPointerException("InputStream is null");
        try (in) {
            return in.readAllBytes();
        }
    }
}
