package org.watermedia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.watermedia.test.support.MediaBootstrap.runProbe;

class ServiceLifecycleProcessTest {
    @TempDir Path directory;

    @Test void searchesRemainIsolatedAcrossCancellationAndRestart() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "platform", false); }
    @Test void pendingUploadsBecomeTerminalBeforeRestart() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "uploads", false); }
    @Test void reloadCannotMutateAMrlRetiredWhileWaitingForItsOwner() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "reload", false); }
    @Test void evictedMrlReloadReturnsACanonicalHandleOwnedByShutdown() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "evicted", false); }
    @Test void statusCannotExpireAHandleWhileItsSessionRetiresIt() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "status", false); }
    @Test void failedProbeCannotCompleteBeforeOtherProbesFinish() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "failed-probe", false); }

}
