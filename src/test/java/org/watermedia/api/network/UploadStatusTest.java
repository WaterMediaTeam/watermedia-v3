package org.watermedia.api.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UploadStatusTest {
    @Test
    void shutdownFailureWinsAgainstLateCompletionAndProgress() {
        final var status = new NetworkServer.UploadStatus(100);
        status.uploadedBytes(20);
        status.speed(5);
        status.fail("stopped");
        status.uploadedBytes(80);
        status.speed(40);
        status.complete("late");
        assertTrue(status.failed());
        assertFalse(status.completed());
        assertEquals("stopped", status.error());
        assertNull(status.id());
        assertEquals(20, status.uploadedBytes());
        assertEquals(5, status.speed());
    }

    @Test
    void completedUploadCannotBeChangedByLateFailure() {
        final var status = new NetworkServer.UploadStatus(100);
        status.complete("stored");
        status.fail("stopped");
        status.uploadedBytes(0);
        assertTrue(status.completed());
        assertFalse(status.failed());
        assertEquals("stored", status.id());
        assertEquals(100, status.uploadedBytes());
        assertNull(status.error());
    }
}
