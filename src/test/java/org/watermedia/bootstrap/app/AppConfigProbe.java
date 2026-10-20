package org.watermedia.bootstrap.app;

import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.bootstrap.app.render.RenderSystem;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Runs the launcher and the app spec against one {@code config/app.properties} in a fresh working directory. */
public final class AppConfigProbe {
    public static void main(final String[] arguments) throws Exception {
        // FIRST LAUNCH: THE LAUNCHER SAVES THE PROMPTED ENGINE BEFORE WATERCONFIG EVER RAN
        final Method save = AppBootstrap.class.getDeclaredMethod("save", Properties.class, String.class);
        save.setAccessible(true);
        save.invoke(null, new Properties(), "vulkan");

        WaterConfig.init();
        final ConfigSpec spec = WaterConfig.registerBlocking(AppConfig.class);
        final Field preferences = AppBootstrap.class.getDeclaredField("PREFERENCES");
        preferences.setAccessible(true);
        final Path file = ((Path) preferences.get(null)).toAbsolutePath();
        if (!spec.path().equals(file)) throw new AssertionError("The launcher reads " + file + " but WaterConfig writes " + spec.path());
        if (AppConfig.engine != RenderSystem.Engine.VULKAN || AppConfig.scale != UiScale.AUTO || !AppConfig.crt || !AppConfig.sound)
            throw new AssertionError("The spec did not load the launcher's engine over its defaults");

        // THE SETTINGS SCREEN SAVES THROUGH THE SAME PACKAGE-PRIVATE METHOD
        AppConfig.engine = RenderSystem.Engine.OPENGL;
        AppConfig.scale = UiScale.LARGE;
        final Method write = ConfigSpec.class.getDeclaredMethod("save");
        write.setAccessible(true);
        write.invoke(spec);
        final Properties written = new Properties();
        try (final InputStream input = Files.newInputStream(file)) { written.load(input); }
        if (!written.stringPropertyNames().equals(Set.of(AppConfig.ENGINE, "scale", "crt", "sound")))
            throw new AssertionError("Unexpected keys: " + written.stringPropertyNames());
        if (!"LARGE".equals(written.getProperty("scale"))) throw new AssertionError("Scale not saved: " + written.getProperty("scale"));
        final Method engine = AppBootstrap.class.getDeclaredMethod("engine", String.class);
        engine.setAccessible(true);
        if (!"opengl".equals(engine.invoke(null, written.getProperty(AppConfig.ENGINE))))
            throw new AssertionError("The launcher cannot read the saved engine: " + written.getProperty(AppConfig.ENGINE));
        System.out.println("PROBE_OK " + arguments[0]);
        System.exit(0);
    }
}
