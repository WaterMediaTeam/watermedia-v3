package org.watermedia.test.tools;

import org.junit.jupiter.api.Test;
import org.watermedia.tools.ThreadTool;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThreadToolTest {
    @Test
    void groupIdentitySurvivesAllocationOfLaterGroups() {
        final ThreadTool.ThreadGroupFactory groups = ThreadTool.createThreadGroupFactory("decode", Thread.MAX_PRIORITY + 1);
        final var first = groups.newFactory();
        final var second = groups.newFactory();
        final Thread firstThread = first.apply("audio", () -> {});
        assertEquals("decode-1-audio", firstThread.getName());
        assertEquals("decode-2-video", second.apply("video", () -> {}).getName());
        assertEquals("decode-1-demux", first.apply("demux", () -> {}).getName());
        assertTrue(firstThread.isDaemon());
        assertEquals(Thread.MAX_PRIORITY, firstThread.getPriority());
    }

    @Test
    void workerMarkerCoversCallbacksWithoutMarkingUnrelatedThreads() throws Exception {
        assertFalse(ThreadTool.workerThread());
        final var factory = ThreadTool.workerFactory("owned", Thread.NORM_PRIORITY);
        final FutureTask<Boolean> task = new FutureTask<>(ThreadTool::workerThread);
        final Thread worker = factory.newThread(task);
        worker.start();
        assertTrue(task.get(2, TimeUnit.SECONDS));
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertFalse(ThreadTool.workerThread());
        factory.newThread(() -> {
            assertTrue(ThreadTool.workerThread());
            factory.newThread(() -> assertTrue(ThreadTool.workerThread())).run();
            assertTrue(ThreadTool.workerThread());
        }).run();
        assertFalse(ThreadTool.workerThread());
        final FutureTask<Boolean> ordinary = new FutureTask<>(ThreadTool::workerThread);
        final Thread host = ThreadTool.createFactory("host", Thread.NORM_PRIORITY).newThread(ordinary);
        host.start();
        assertFalse(ordinary.get(2, TimeUnit.SECONDS));
        host.join(2000);
    }
}
