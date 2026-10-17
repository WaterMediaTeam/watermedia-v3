package org.watermedia;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.codecs.CodecsAPI;

/** Verifies client and server bootstrap without the optional binaries module. */
public final class WithoutBinariesProbe {
    private WithoutBinariesProbe() {}

    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 1 || !("server-no-binaries".equals(arguments[0]) || "client-no-binaries".equals(arguments[0])))
            throw new IllegalArgumentException("Unknown bootstrap scenario");
        final boolean client = arguments[0].startsWith("client");
        final var classpath = new ArrayList<URL>();
        for (final String entry: System.getProperty("java.class.path").split(File.pathSeparator))
            classpath.add(Path.of(entry).toAbsolutePath().toUri().toURL());
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (final URLClassLoader isolated = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.watermedia.binaries.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
            @Override
            public URL getResource(final String name) {
                return name.startsWith("org/watermedia/binaries/") ? null : super.getResource(name);
            }
        }) {
            Thread.currentThread().setContextClassLoader(isolated);
            try {
                Class.forName("org.watermedia.binaries.WaterMediaBinaries", false, isolated);
                throw new AssertionError("Binaries remained visible to the isolated server");
            } catch (final ClassNotFoundException expected) {}
            final Class<?> config = Class.forName(WaterMediaConfig.class.getName(), true, isolated);
            final Object network = config.getField("network").get(null);
            // OFF IS THE FIRST CONSTANT OF THE ISOLATED Server ENUM
            final Object[] servers = Class.forName(WaterMediaConfig.Network.Server.class.getName(), true, isolated).getEnumConstants();
            network.getClass().getField("server").set(network, servers[0]);
            final Class<?> waterMedia = Class.forName(WaterMedia.class.getName(), true, isolated);
            final Path root = Path.of("").toAbsolutePath();
            waterMedia.getMethod("start", String.class, Path.class, Path.class, boolean.class)
                    .invoke(null, "WITHOUT_BINARIES", root.resolve("temp"), root, client);
            final Object status = waterMedia.getMethod("status").invoke(null);
            if (!"READY".equals(status.getClass().getMethod("state").invoke(status).toString()))
                throw new AssertionError("Server startup failed: " + status);
            for (final Object service: (List<?>) status.getClass().getMethod("services").invoke(status)) {
                final String id = service.getClass().getMethod("id").invoke(service).toString();
                if ((id.equals("BINARIES") || (!client && (id.equals("MEDIA") || id.equals("PLATFORMS"))))
                        && !"SKIPPED".equals(service.getClass().getMethod("outcome").invoke(service).toString()))
                    throw new AssertionError("Client service was loaded on server: " + service);
            }
            if (client) {
                final Class<?> media = Class.forName(MediaAPI.class.getName(), true, isolated);
                if ((boolean) media.getMethod("ffmpegLoaded").invoke(null) || (boolean) media.getMethod("ffmpegError").invoke(null))
                    throw new AssertionError("Absent binaries must disable video without a native initialization error");
                final Class<?> codecs = Class.forName(CodecsAPI.class.getName(), true, isolated);
                final ByteBuffer image = ByteBuffer.wrap("P6\n1 1\n255\nABC".getBytes(StandardCharsets.US_ASCII));
                final Object reader = codecs.getMethod("reader", ByteBuffer.class).invoke(null, image);
                try (final AutoCloseable closeable = (AutoCloseable) reader) {
                    final Object decoded = reader.getClass().getMethod("readAll").invoke(reader);
                    if ((int) decoded.getClass().getMethod("width").invoke(decoded) != 1)
                        throw new AssertionError("Image decoding failed without binaries");
                }
            }
            waterMedia.getMethod("stop").invoke(null);
            final Object stopped = waterMedia.getMethod("status").invoke(null);
            if (!"STOPPED".equals(stopped.getClass().getMethod("state").invoke(stopped).toString()))
                throw new AssertionError("Server did not stop: " + stopped);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        System.out.println("PROBE_OK " + arguments[0]);
    }
}
