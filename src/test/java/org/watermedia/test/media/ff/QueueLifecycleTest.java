package org.watermedia.test.media.ff;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.util.FrameQueue;
import org.watermedia.api.media.players.util.PacketQueue;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class QueueLifecycleTest {
    @BeforeAll
    static void natives() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
    }

    @Test
    void invalidCapacitiesFailBeforeAllocatingNativeSlots() {
        assertThrows(IllegalArgumentException.class, () -> new FrameQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameQueue(-1));
        assertThrows(IllegalArgumentException.class, () -> new PacketQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new PacketQueue(-1));
    }

    @Test
    void abortWakesFrameProducersAndConsumers() throws Exception {
        final FrameQueue queue = new FrameQueue(1);
        Thread worker = null;
        try {
            assertNotNull(queue.peekWritable());
            queue.push();
            final FutureTask<FrameQueue.Slot> producer = new FutureTask<>(queue::peekWritable);
            worker = new Thread(producer, "FrameQueue-Test-Producer");
            worker.start();
            final Thread waitingProducer = worker;
            assertTrue(PlayerWait.awaitCondition(() -> waitingProducer.getState() == Thread.State.WAITING, 2000));
            queue.abort();
            assertNull(producer.get(2, TimeUnit.SECONDS));
            worker.join();
            queue.reset();
            final FutureTask<FrameQueue.Slot> consumer = new FutureTask<>(() -> queue.peekBlocking(0));
            worker = new Thread(consumer, "FrameQueue-Test-Consumer");
            worker.start();
            final Thread waitingConsumer = worker;
            assertTrue(PlayerWait.awaitCondition(() -> waitingConsumer.getState() == Thread.State.TIMED_WAITING, 2000));
            queue.abort();
            assertNull(consumer.get(2, TimeUnit.SECONDS));
        } finally {
            queue.abort();
            if (worker != null) { worker.interrupt(); worker.join(2000); assertFalse(worker.isAlive()); }
            queue.free();
        }
    }

    @Test
    void abortAndFinishWakePacketWaitersAndFlushAdvancesSerial() throws Exception {
        final PacketQueue queue = new PacketQueue(8);
        final AVPacket packet = avcodec.av_packet_alloc();
        assertNotNull(packet);
        assertEquals(0, avcodec.av_new_packet(packet, 8));
        Thread worker = null;
        try {
            assertTrue(queue.put(packet));
            for (final boolean finish: new boolean[] { false, true }) {
                final FutureTask<Boolean> producer = new FutureTask<>(() -> queue.put(packet));
                worker = new Thread(producer, "PacketQueue-Test-Producer");
                worker.start();
                final Thread waiting = worker;
                assertTrue(PlayerWait.awaitCondition(() -> waiting.getState() == Thread.State.WAITING, 2000));
                if (finish) queue.finish(); else queue.abort();
                assertFalse(producer.get(2, TimeUnit.SECONDS));
                assertFalse(queue.tryPut(packet));
                worker.join();
                queue.reset();
                if (!finish) assertTrue(queue.put(packet));
            }
            final int before = queue.serial();
            assertTrue(queue.put(packet));
            queue.flush();
            assertEquals(before + 1, queue.serial());
            packet.pts(123);
            assertTrue(queue.put(packet));
            final int[] serial = new int[1];
            final AVPacket current = queue.get(serial);
            try {
                assertEquals(123, current.pts());
                assertEquals(queue.serial(), serial[0]);
            } finally { avcodec.av_packet_free(current); }
            final FutureTask<AVPacket> consumer = new FutureTask<>(() -> queue.get(new int[1]));
            worker = new Thread(consumer, "PacketQueue-Test-Consumer");
            worker.start();
            final Thread waiting = worker;
            assertTrue(PlayerWait.awaitCondition(() -> waiting.getState() == Thread.State.WAITING, 2000));
            queue.abort();
            assertNull(consumer.get(2, TimeUnit.SECONDS));
        } finally {
            queue.abort();
            if (worker != null) { worker.interrupt(); worker.join(2000); assertFalse(worker.isAlive()); }
            queue.free();
            avcodec.av_packet_free(packet);
        }
    }
}
