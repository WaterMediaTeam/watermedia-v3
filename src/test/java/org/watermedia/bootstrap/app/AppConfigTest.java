package org.watermedia.bootstrap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.watermedia.test.support.MediaBootstrap.runProbe;

/** Verifies that the launcher and the app spec share {@code config/app.properties} under the working directory. */
@DisplayName("App preferences")
public class AppConfigTest {
    @TempDir Path directory;

    @Test
    @DisplayName("the engine the launcher saves loads into the spec, and the spec writes the keys the launcher reads")
    void sharesTheFile() throws Exception {
        runProbe(AppConfigProbe.class, this.directory, "shared", false);
    }
}
