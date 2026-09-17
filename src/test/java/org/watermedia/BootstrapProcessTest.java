package org.watermedia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.watermedia.test.support.MediaBootstrap.runProbe;

class BootstrapProcessTest {
    @TempDir Path directory;

    @Test void serverBootWithoutClientBindings() throws Exception { runProbe(BootstrapProbe.class, this.directory, "server", true); }
    @Test void serverBootWithoutBinariesClasses() throws Exception { runProbe(WithoutBinariesProbe.class, this.directory, "server-no-binaries", false); }
    @Test void clientBootWithoutBinariesKeepsImagesAvailable() throws Exception { runProbe(WithoutBinariesProbe.class, this.directory, "client-no-binaries", false); }
    @Test void cancelledResolutionCannotReachANewSession() throws Exception { runProbe(BootstrapProbe.class, this.directory, "restart", false); }
    @Test void enabledThenDisabledNativeRestartClearsAvailability() throws Exception { runProbe(BootstrapProbe.class, this.directory, "native", false); }
    @Test void installedBinariesMustInitializeBeforeOtherServices() throws Exception { runProbe(BootstrapProbe.class, this.directory, "native-failure", false); }
    @Test void nativeAuthoritiesFollowTruststoreAndSessionLifetime() throws Exception { runProbe(BootstrapProbe.class, this.directory, "tls", false); }

}
