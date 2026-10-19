package org.watermedia.api.media;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link SFXEngine} implementations that the MasterClock/FFMediaPlayer sync contract
 * depends on: the channel-support gate, sample sizes and volume of both engines, {@link JSEngine}
 * line uploads and inertness after release, and the OpenAL queue ownership. Cases that need a Java
 * Sound output line or an OpenAL device skip when the machine has none.
 */
@DisplayName("SFXEngine")
public class SFXEngineTest {
    // STREAMING LAYOUT OF THE JAVA SOUND LINE CASES
    private static final int RATE_HZ = 48_000;
    private static final int MS_BYTES = RATE_HZ / 1_000 * 4; // ONE MILLISECOND OF S16 STEREO

    @BeforeAll
    static void boot() {
        // ENGINE CONSTRUCTION IS CLIENT-GATED BY THE SFXEngine BASE — BOOT A CLIENT ENVIRONMENT ONCE
        MediaBootstrap.client();
    }

    @Test
    @DisplayName("Every sample type reports its single-channel size in bytes")
    void sampleTypeBytes() {
        for (final SFXEngine.SampleType type: SFXEngine.SampleType.values()) {
            final int expected = switch (type) {
                case U8 -> 1;
                case S16 -> 2;
                case S32, FLT -> 4;
                case DBL -> 8;
            };
            assertEquals(expected, type.bytes(), type.name());
        }
    }

    @Nested
    @DisplayName("JSEngine (Java Sound, no OpenAL context)")
    class JavaSound {

        @Test
        @DisplayName("Capability table is self-consistent and U8-first")
        void capabilityTable() {
            final JSEngine engine = MediaAPI.jsEngine();
            assertEquals(SFXEngine.SampleType.U8, engine.supportedTypes()[0]);
            assertEquals(2, engine.supportedTypes().length);
            assertTableConsistent(engine);
        }

        @Test
        @DisplayName("format() refuses every combination outside U8/S16 mono/stereo without opening a line")
        void formatFollowsTheTable() {
            final Set<Line> before = openLines();
            final JSEngine engine = MediaAPI.jsEngine();
            try {
                for (final SFXEngine.SampleType type: SFXEngine.SampleType.values()) {
                    for (int channels = 1; channels <= 8; channels++) {
                        final boolean listed = (type == SFXEngine.SampleType.U8 || type == SFXEngine.SampleType.S16) && channels <= 2;
                        final SFXEngine.ChannelSupport support = engine.channelSupport(channels);
                        assertEquals(listed, support != null && support.supports(type), type + " x" + channels + " table entry");
                        if (!listed)
                            assertFalse(engine.format(type, channels, RATE_HZ), type + " x" + channels + " is outside the channel-support table");
                    }
                }
                assertNull(engine.sampleType(), "a refused combination never configures a layout");
                assertEquals(0, engine.channels());
                assertEquals(before, openLines(), "a refused combination never reaches the mixer");
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("format() rejects invalid arguments without opening a line")
        void formatValidation() {
            final JSEngine engine = MediaAPI.jsEngine();
            assertFalse(engine.format(null, 2, RATE_HZ), "null type");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 0, RATE_HZ), "channels < 1");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 9, RATE_HZ), "channels > 8");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 2, 100), "rate below MIN");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 2, 10_000_000), "rate above MAX");
        }

        @Test
        @DisplayName("No pitch control and no source handle")
        void capabilityContract() {
            final JSEngine engine = MediaAPI.jsEngine();
            assertFalse(engine.speed(2.0f), "Java Sound refuses speed control");
            assertEquals(1.0f, engine.speed(), "a refused speed keeps the 1.0x default");
            assertEquals(0, engine.source(), "Java Sound has no source handle");
        }

        @Test
        @DisplayName("volume(float) rejects negative and non-finite gains and keeps the last one")
        void volumeValidation() {
            final JSEngine engine = MediaAPI.jsEngine();
            engine.volume(0.5f);
            for (final float gain: new float[] { -1.0f, -0.1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
                assertThrows(IllegalArgumentException.class, () -> engine.volume(gain), "gain " + gain);
            assertEquals(0.5f, engine.volume(), "a refused gain keeps the last accepted one");
            engine.release();
        }

        @Test
        @DisplayName("volume() starts at full gain, follows volume(float) and ignores changes once released")
        void volumeTracksTheLastGain() {
            final JSEngine engine = MediaAPI.jsEngine();
            assertEquals(1.0f, engine.volume(), "full gain by default");
            engine.volume(0.25f);
            assertEquals(0.25f, engine.volume());
            engine.volume(0.0f);
            assertEquals(0.0f, engine.volume(), "zero is silence, not an error");
            engine.release();
            engine.volume(0.75f);
            assertEquals(0.0f, engine.volume(), "a released engine keeps its last gain");
        }

        @Test
        @DisplayName("An unconfigured engine refuses uploads, even malformed ones, without throwing")
        void unconfiguredUploadIsRefused() {
            final JSEngine engine = MediaAPI.jsEngine();
            try {
                assertFalse(engine.upload(ByteBuffer.allocateDirect(4 * MS_BYTES)), "no layout, nothing to queue");
                assertFalse(engine.upload(ByteBuffer.allocate(3)), "the caller-bug checks wait for a layout");
                assertEquals(0, engine.pendingMs());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A released engine refuses formats and uploads instead of reopening a line")
        void releasedEngineStaysInert() {
            final JSEngine engine = MediaAPI.jsEngine();
            engine.release();
            assertTrue(engine.released());
            assertFalse(engine.format(SFXEngine.SampleType.S16, 2, RATE_HZ), "no line is reopened after release");
            assertFalse(engine.upload(ByteBuffer.allocateDirect(4)));
            assertEquals(0, engine.pendingMs(), "nothing pends on a released engine");
            engine.flush();
            engine.pause();
            engine.play();
            engine.release(); // IDEMPOTENT
        }

        @Nested
        @DisplayName("With an open line (skips without an output device)")
        class OpenLine {

            @Test
            @DisplayName("A combination outside the table keeps the open line and its layout")
            void refusalKeepsTheLine() {
                final Set<Line> before = openLines();
                final JSEngine engine = open(JSEngine.DEFAULT_BUFFER_MS);
                try {
                    final SourceDataLine line = openedSince(before);
                    assertFalse(engine.format(SFXEngine.SampleType.FLT, 2, RATE_HZ), "FLT is outside the channel-support table");
                    assertFalse(engine.format(SFXEngine.SampleType.S16, 6, RATE_HZ), "5.1 is outside the channel-support table");
                    assertTrue(line.isOpen(), "a refusal never reaches the line");
                    assertEquals(SFXEngine.SampleType.S16, engine.sampleType());
                    assertEquals(2, engine.channels());
                    assertTrue(engine.format(SFXEngine.SampleType.S16, 2, RATE_HZ));
                    assertSame(line, openedSince(before), "an identical format reuses the open line");
                } finally {
                    engine.release();
                }
            }

            @Test
            @DisplayName("MASTER_GAIN follows volume() and is re-applied on every line the engine reopens")
            void gainFollowsVolume() {
                final Set<Line> before = openLines();
                final JSEngine engine = open(20);
                try {
                    final SourceDataLine first = openedSince(before);
                    Assumptions.assumeTrue(first.isControlSupported(FloatControl.Type.MASTER_GAIN), "the mixer exposes no MASTER_GAIN");
                    assertGain(first, 1.0f);
                    engine.volume(0.5f);
                    assertGain(first, 0.5f);
                    engine.volume(0.0f);
                    assertGain(first, 0.0f);
                    engine.volume(0.25f);

                    // A NEW LAYOUT OPENS A FRESH LINE
                    assertTrue(engine.format(SFXEngine.SampleType.S16, 1, 44_100));
                    final SourceDataLine second = openedSince(before);
                    assertFalse(first.isOpen(), "the previous layout's line is closed");
                    assertGain(second, 0.25f);

                    // ONE SECOND OF S16 MONO CANNOT FIT A 20 MS LINE, SO THE ENGINE REOPENS A WIDER ONE
                    final ByteBuffer wide = ByteBuffer.allocateDirect(2 * 44_100);
                    assertTrue(wide.capacity() > second.getBufferSize(), "the chunk exceeds the line");
                    assertTrue(engine.upload(wide));
                    final SourceDataLine third = openedSince(before);
                    assertFalse(second.isOpen(), "the narrow line is closed");
                    assertGain(third, 0.25f);
                } finally {
                    engine.release();
                }
            }

            @Test
            @DisplayName("upload() is non-blocking: a full line refuses chunks, positions never move and flush() empties it")
            void uploadBackpressure() {
                final JSEngine engine = open(100);
                try {
                    // TEN MILLISECONDS OF SILENCE BEHIND A 4-BYTE OFFSET; THE LINE IS NEVER STARTED, SO NOTHING DRAINS
                    final ByteBuffer chunk = ByteBuffer.allocateDirect(4 + 10 * MS_BYTES).position(4);
                    long pending = engine.pendingMs();
                    assertEquals(0, pending, "a fresh line has nothing pending");
                    int accepted = 0;
                    while (accepted < 1_000 && engine.upload(chunk)) {
                        accepted++;
                        assertEquals(4, chunk.position(), "an accepted chunk keeps its position");
                        final long now = engine.pendingMs();
                        assertTrue(now > pending, "pendingMs() grows with every accepted chunk");
                        pending = now;
                    }
                    assertTrue(accepted > 0 && accepted < 1_000, "the line fills up, accepted " + accepted + " chunks");

                    final long start = System.nanoTime();
                    assertFalse(engine.upload(chunk), "a full line keeps refusing");
                    final long refusedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    assertTrue(refusedMs < 100, "a refusal returns at once instead of waiting for room, took " + refusedMs + " ms");
                    assertEquals(4, chunk.position(), "a refused chunk keeps its position");
                    assertEquals(pending, engine.pendingMs(), "a refused chunk adds nothing");

                    engine.flush();
                    assertEquals(0, engine.pendingMs(), "flush() drops the queued audio");
                    assertTrue(engine.upload(chunk), "a flushed line has room again");
                } finally {
                    engine.release();
                }
            }

            @Test
            @DisplayName("A chunk wider than the line reopens a wider one, accepts it and keeps playing")
            void wideChunkReopens() {
                final Set<Line> before = openLines();
                final JSEngine engine = open(20);
                try {
                    final SourceDataLine narrow = openedSince(before);
                    final ByteBuffer second = ByteBuffer.allocateDirect(1_000 * MS_BYTES); // ONE SECOND OF SILENCE
                    assertTrue(second.capacity() > narrow.getBufferSize(), "the chunk exceeds the narrow line");
                    assertTrue(engine.upload(ByteBuffer.allocateDirect(5 * MS_BYTES)));
                    engine.play();
                    assertTrue(narrow.isActive(), "the narrow line is playing");

                    assertTrue(engine.upload(second), "the wide chunk is accepted instead of refused forever");
                    final SourceDataLine wide = openedSince(before);
                    assertFalse(narrow.isOpen(), "the narrow line is closed");
                    assertTrue(wide.getBufferSize() >= second.capacity(), "the new line holds the whole chunk");
                    assertTrue(wide.isActive(), "a playing line resumes after the reopen");
                    assertEquals(0, second.position());
                    assertTrue(engine.pendingMs() > 20, "the chunk pends beyond the narrow line's depth");
                } finally {
                    engine.release();
                }
            }

            @Test
            @DisplayName("pause() stops a playing line and play() restarts it")
            void pauseAndPlay() {
                final Set<Line> before = openLines();
                final JSEngine engine = open(JSEngine.DEFAULT_BUFFER_MS);
                try {
                    final SourceDataLine line = openedSince(before);
                    assertTrue(engine.upload(ByteBuffer.allocateDirect(250 * MS_BYTES)));
                    engine.play();
                    assertTrue(line.isRunning(), "play() starts a line holding audio");
                    final long queued = engine.pendingMs();
                    Assumptions.assumeTrue(PlayerWait.awaitCondition(() -> engine.pendingMs() < queued, 2_000), "the output device never consumes audio");
                    engine.pause();
                    assertFalse(line.isRunning(), "pause() stops the line");
                    final long held = engine.pendingMs();
                    assertTrue(held > 0, "a paused line keeps its queued audio");
                    // JAVA SOUND RAISES isRunning() ONLY ONCE NEW DATA FLOWS, SO THE DRAIN IS THE RESTART SIGNAL
                    engine.play();
                    assertTrue(PlayerWait.awaitCondition(() -> engine.pendingMs() < held, 2_000), "play() restarts the line");
                } finally {
                    engine.release();
                }
            }

            @Test
            @DisplayName("pause() after a play() that saw no audio keeps the next chunks from playing")
            void pauseBeforeAudio() {
                final JSEngine engine = open(JSEngine.DEFAULT_BUFFER_MS);
                try {
                    // JAVA SOUND REPORTS A STARTED LINE INACTIVE UNTIL DATA FLOWS, SO THE ENGINE MUST TRACK THE START ITSELF
                    engine.play();
                    engine.pause();
                    assertTrue(engine.upload(ByteBuffer.allocateDirect(250 * MS_BYTES)));
                    final long queued = engine.pendingMs();
                    assertFalse(PlayerWait.awaitCondition(() -> engine.pendingMs() < queued, 500), "a paused line plays nothing");
                    engine.play();
                    Assumptions.assumeTrue(PlayerWait.awaitCondition(() -> engine.pendingMs() < queued, 2_000), "the output device never consumes audio");
                } finally {
                    engine.release();
                }
            }

            @Test
            @DisplayName("upload() rejects heap, empty and frame-misaligned chunks like every backend")
            void malformedChunks() {
                final JSEngine engine = open(JSEngine.DEFAULT_BUFFER_MS);
                try {
                    assertMalformedChunksRejected(engine);
                } finally {
                    engine.release();
                }
            }

            @Test
            @DisplayName("release() closes the line and leaves every later call harmless")
            void releaseClosesTheLine() {
                final Set<Line> before = openLines();
                final JSEngine engine = open(JSEngine.DEFAULT_BUFFER_MS);
                final SourceDataLine line = openedSince(before);
                final ByteBuffer chunk = ByteBuffer.allocateDirect(10 * MS_BYTES);
                assertTrue(engine.upload(chunk));
                engine.volume(0.5f);
                engine.release();
                assertFalse(line.isOpen(), "release closes the line");
                assertFalse(engine.format(SFXEngine.SampleType.S16, 2, RATE_HZ), "a released engine never reopens a line");
                assertFalse(engine.upload(chunk), "uploads are refused");
                assertEquals(before, openLines(), "no engine line stays open");
                assertEquals(0, engine.pendingMs(), "nothing pends on a released engine");
                engine.flush();
                engine.pause();
                engine.play();
                engine.volume(0.25f);
                assertEquals(0.5f, engine.volume(), "a released engine keeps its last gain");
                engine.release(); // IDEMPOTENT
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS) // NON-STATIC @BeforeAll/@AfterAll HOLD THE OPENAL CONTEXT
    @DisplayName("ALEngine (OpenAL, requires a current context)")
    class OpenAL {
        private long device;
        private long context;

        @BeforeAll
        void openContext() {
            try {
                this.device = ALC10.alcOpenDevice((ByteBuffer) null);
                Assumptions.assumeTrue(this.device != 0L, "no OpenAL device available");
                this.context = ALC10.alcCreateContext(this.device, (IntBuffer) null);
                Assumptions.assumeTrue(this.context != 0L, "no OpenAL context available");
                ALC10.alcMakeContextCurrent(this.context);
                AL.createCapabilities(ALC.createCapabilities(this.device));
            } catch (final Throwable t) {
                // NO NATIVES / NO AUDIO STACK — SKIP THE OPENAL CASES INSTEAD OF FAILING
                Assumptions.abort("OpenAL unavailable: " + t.getMessage());
            }
        }

        @AfterAll
        void closeContext() {
            if (this.context != 0L) {
                ALC10.alcMakeContextCurrent(0L);
                ALC10.alcDestroyContext(this.context);
            }
            if (this.device != 0L) ALC10.alcCloseDevice(this.device);
        }

        @Test
        @DisplayName("Capability table is self-consistent and U8-first")
        void capabilityTable() {
            final ALEngine engine = MediaAPI.alEngine();
            try {
                assertEquals(SFXEngine.SampleType.U8, engine.supportedTypes()[0]);
                final var capabilities = AL.getCapabilities();
                assertEquals(2 + (capabilities.AL_EXT_FLOAT32 || capabilities.AL_EXT_MCFORMATS ? 1 : 0)
                        + (capabilities.AL_EXT_DOUBLE ? 1 : 0), engine.supportedTypes().length);
                assertTableConsistent(engine);
                assertNotEquals(0, engine.source(), "a source handle is generated under a live context");
                assertTrue(engine.speed(2.0f), "AL_PITCH applies speed natively");
                assertEquals(2.0f, engine.speed(), "the applied speed is reflected by the getter");
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("format() accepts supported combos and rejects the rest")
        void formatNegotiation() {
            final ALEngine engine = MediaAPI.alEngine();
            try {
                assertTrue(engine.format(SFXEngine.SampleType.S16, 2, RATE_HZ), "S16 stereo");
                assertEquals(2, engine.channels());
                assertEquals(SFXEngine.SampleType.S16, engine.sampleType());

                assertEquals(AL.getCapabilities().AL_EXT_MCFORMATS, engine.format(SFXEngine.SampleType.S16, 6, RATE_HZ), "S16 5.1");
                assertEquals(AL.getCapabilities().AL_EXT_DOUBLE, engine.format(SFXEngine.SampleType.DBL, 2, RATE_HZ), "DBL stereo");

                assertFalse(engine.format(SFXEngine.SampleType.DBL, 6, RATE_HZ), "DBL multichannel is outside the channel-support table");
                assertFalse(engine.format(SFXEngine.SampleType.S32, 2, RATE_HZ), "S32 is outside the channel-support table");
                assertFalse(engine.format(null, 2, RATE_HZ), "null type");
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("format() accepts exactly the channel-support table, mono alone in spatial mode, and never S32")
        void formatFollowsTheTable() {
            for (final boolean spatial: new boolean[] { false, true }) {
                final ALEngine engine = MediaAPI.alEngine(spatial);
                try {
                    for (final SFXEngine.SampleType type: SFXEngine.SampleType.values()) {
                        for (int channels = 1; channels <= 8; channels++) {
                            final SFXEngine.ChannelSupport support = engine.channelSupport(channels);
                            final boolean listed = support != null && support.supports(type);
                            final String combination = type + " x" + channels + (spatial ? " (spatial)" : "");
                            assertEquals(listed, engine.format(type, channels, RATE_HZ), combination);
                            if (!listed) continue;
                            assertEquals(type, engine.sampleType(), combination);
                            assertEquals(channels, engine.channels(), combination);
                        }
                    }
                    for (int channels = 1; channels <= 8; channels++) {
                        assertFalse(engine.format(SFXEngine.SampleType.S32, channels, RATE_HZ), "OpenAL has no native S32 PCM");
                        if (spatial && channels > 1) assertNull(engine.channelSupport(channels), "a spatial source is mono");
                    }
                    // A REFUSAL NEVER REACHES THE BACKEND, SO THE LAST ACCEPTED LAYOUT STAYS
                    assertTrue(engine.format(SFXEngine.SampleType.S16, 1, RATE_HZ));
                    assertFalse(engine.format(SFXEngine.SampleType.S32, 1, RATE_HZ));
                    assertEquals(SFXEngine.SampleType.S16, engine.sampleType());
                    assertEquals(1, engine.channels());
                    assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
                } finally {
                    engine.release();
                }
            }
        }

        @Test
        @DisplayName("volume() drives AL_GAIN and a released source keeps its last gain")
        void volumeDrivesGain() {
            final ALEngine engine = MediaAPI.alEngine();
            try {
                assertEquals(1.0f, engine.volume(), "full gain by default");
                assertEquals(1.0f, AL10.alGetSourcef(engine.source(), AL10.AL_GAIN));
                engine.volume(0.25f);
                assertEquals(0.25f, engine.volume());
                assertEquals(0.25f, AL10.alGetSourcef(engine.source(), AL10.AL_GAIN), 1e-6f);
                assertThrows(IllegalArgumentException.class, () -> engine.volume(Float.NaN));
                assertEquals(0.25f, AL10.alGetSourcef(engine.source(), AL10.AL_GAIN), 1e-6f, "a refused gain never reaches the source");
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
            engine.volume(0.75f);
            assertEquals(0.25f, engine.volume(), "a released engine keeps its last gain");
        }

        @Test
        @DisplayName("upload() rejects heap, empty and frame-misaligned chunks like every backend")
        void malformedChunks() {
            final ALEngine engine = MediaAPI.alEngine();
            try {
                assertTrue(engine.format(SFXEngine.SampleType.S16, 2, RATE_HZ));
                assertMalformedChunksRejected(engine);
                assertEquals(0, AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED), "no buffer was queued");
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A failed native upload keeps the buffer pool and the queue owned by the engine")
        void nativeUploadErrorsPreserveQueueOwnership() throws Exception {
            final ALEngine engine = MediaAPI.alEngine(2);
            final ByteBuffer pcm = ByteBuffer.allocateDirect(64);
            final Field nativeFormat = ALEngine.class.getDeclaredField("alFormat");
            final Field free = ALEngine.class.getDeclaredField("freeCount");
            nativeFormat.setAccessible(true);
            free.setAccessible(true);
            try {
                assertTrue(engine.format(SFXEngine.SampleType.S16, 1, RATE_HZ));
                nativeFormat.setInt(engine, 0x7FFFFFFF);
                assertThrows(IllegalStateException.class, () -> engine.upload(pcm));
                assertEquals(2, free.getInt(engine));
                assertEquals(0, AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED));
                assertTrue(engine.format(SFXEngine.SampleType.S16, 1, RATE_HZ));
                assertTrue(engine.upload(pcm));
                // A LAYOUT CHANGE WITH QUEUED AUDIO FLUSHES THE QUEUE, SO THE NEXT UPLOAD IS ACCEPTED
                assertTrue(engine.format(SFXEngine.SampleType.S16, 2, RATE_HZ));
                assertEquals(2, free.getInt(engine));
                assertEquals(0, AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED));
                assertTrue(engine.upload(pcm));
                assertEquals(1, free.getInt(engine));
                assertEquals(1, AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED));
                engine.flush();
                assertTrue(engine.format(SFXEngine.SampleType.S16, 1, RATE_HZ));
                assertTrue(engine.upload(pcm));
                assertTrue(engine.upload(pcm));
                assertFalse(engine.upload(pcm));
                assertFalse(engine.speed(Float.NaN));
                assertFalse(engine.speed(0));
                assertEquals(1, engine.speed());
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("release() waits for a host callback still using the source")
        void releaseWaitsForAHostCallbackUsingTheSource() throws Exception {
            final ALEngine engine = MediaAPI.alEngine(true);
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch resume = new CountDownLatch(1);
            final CountDownLatch releasing = new CountDownLatch(1);
            final var executor = Executors.newFixedThreadPool(2);
            try {
                final var update = executor.submit(() -> engine.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false,
                        (source, state) -> {
                            entered.countDown();
                            try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test callback timed out"); }
                            catch (final InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                            assertTrue(AL10.alIsSource(source));
                        })));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                final var release = executor.submit(() -> { releasing.countDown(); engine.release(); });
                assertTrue(releasing.await(2, TimeUnit.SECONDS));
                assertFalse(release.isDone());
                resume.countDown();
                assertTrue(update.get(2, TimeUnit.SECONDS));
                release.get(2, TimeUnit.SECONDS);
                assertEquals(0, engine.source());
            } finally {
                resume.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                engine.release();
            }
        }

        @Test
        @DisplayName("Speed capability needs no context while the setter still guards it")
        void speedCapabilityNeedsNoContext() {
            final ALEngine engine = MediaAPI.alEngine();
            final long other = ALC10.alcCreateContext(this.device, (IntBuffer) null);
            assertNotEquals(0, other);
            try {
                assertTrue(ALC10.alcMakeContextCurrent(other));
                assertTrue(engine.canSpeed(), "the capability is a pure query");
                assertThrows(IllegalStateException.class, () -> engine.speed(2));
                assertTrue(ALC10.alcMakeContextCurrent(this.context));
                assertEquals(1, engine.speed(), "a guarded call leaves the rate untouched");
            } finally {
                ALC10.alcMakeContextCurrent(this.context);
                ALC10.alcDestroyContext(other);
                engine.release();
            }
            assertFalse(engine.canSpeed(), "a released source cannot change speed");
            assertFalse(MediaAPI.jsEngine().canSpeed(), "Java Sound has no rate control");
        }

        @Test
        @DisplayName("Players query speed on any context and keep their rate when the engine refuses")
        void playerSpeedFollowsTheEngine() {
            Assumptions.assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            // A THREAD-LOCAL CONTEXT MOVES ONLY THIS THREAD, SO THE PLAYER'S AUDIO THREAD KEEPS ITS OWN
            Assumptions.assumeTrue(ALC.getCapabilities().ALC_EXT_thread_local_context, "thread-local contexts unavailable");
            final MRL mrl = MediaAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(3000));
            final ALEngine engine = MediaAPI.alEngine();
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, null, engine);
            final long other = ALC10.alcCreateContext(this.device, (IntBuffer) null);
            assertNotEquals(0, other);
            try {
                player.mute(true);
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitCondition(player::canSpeed, 15_000), () -> "Player never became seekable: " + player.status());
                assertTrue(EXTThreadLocalContext.alcSetThreadContext(other));
                assertTrue(player.canSpeed(), "a query from another context must not throw");
                assertTrue(EXTThreadLocalContext.alcSetThreadContext(0));
                assertTrue(player.speed(2.0f));
                assertEquals(2.0f, engine.speed());
                player.pause(true);
                engine.release();
                assertFalse(player.canSpeed());
                assertFalse(player.speed(3.0f), "a released engine refuses the new rate");
                assertEquals(2.0f, player.speed(), "the player keeps the last accepted rate");
            } finally {
                EXTThreadLocalContext.alcSetThreadContext(0);
                player.release();
                ALC10.alcDestroyContext(other);
            }
        }
    }

    // UTILITY

    // VERIFIES A SUPPORTED_CHANNELS TABLE: CHANNEL COUNTS IN RANGE, AT LEAST ONE TYPE PER ENTRY,
    // AND EVERY ENTRY TYPE DECLARED IN supportedTypes().
    private static void assertTableConsistent(final SFXEngine engine) {
        final List<SFXEngine.SampleType> declared = List.of(engine.supportedTypes());
        assertFalse(declared.isEmpty(), "at least one supported type must be declared");

        for (final SFXEngine.ChannelSupport entry: engine.supportedChannels()) {
            assertTrue(entry.channels() >= 1 && entry.channels() <= 8, "channel count out of range: " + entry.channels());
            assertFalse(entry.types().isEmpty(), "entry must support at least one type");
            for (final SFXEngine.SampleType type: entry.types()) {
                assertTrue(declared.contains(type), "entry type not declared in supportedTypes: " + type);
            }
        }
    }

    // OPENS AN S16 STEREO LINE OF bufferMs, SKIPPING THE CASE WHEN JAVA SOUND HAS NO OUTPUT LINE (HEADLESS CI)
    private static JSEngine open(final int bufferMs) {
        final JSEngine engine = MediaAPI.jsEngine(bufferMs);
        final boolean opened = engine.format(SFXEngine.SampleType.S16, 2, RATE_HZ);
        if (!opened) engine.release();
        Assumptions.assumeTrue(opened, "no Java Sound output line for S16 stereo");
        return engine;
    }

    // SOURCE LINES OPEN ON EVERY MIXER: THE ENGINE'S LINE AS THE PLATFORM AUDIO STACK SEES IT
    private static Set<Line> openLines() {
        final Set<Line> lines = new HashSet<>();
        for (final Mixer.Info info: AudioSystem.getMixerInfo()) Collections.addAll(lines, AudioSystem.getMixer(info).getSourceLines());
        return lines;
    }

    // THE ONE SOURCE LINE OPEN NOW THAT WAS NOT OPEN IN before
    private static SourceDataLine openedSince(final Set<Line> before) {
        final Set<Line> opened = openLines();
        opened.removeAll(before);
        assertEquals(1, opened.size(), () -> "exactly one engine line must be open: " + opened);
        return (SourceDataLine) opened.iterator().next();
    }

    // MASTER_GAIN IS IN DECIBELS: 20·log10(volume), OR THE CONTROL MINIMUM FOR SILENCE
    private static void assertGain(final SourceDataLine line, final float volume) {
        final FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        final float expected = volume <= 0 ? control.getMinimum() : (float) (20.0 * Math.log10(volume));
        assertEquals(expected, control.getValue(), 0.01f, "MASTER_GAIN for volume " + volume);
    }

    // AN ENGINE CONFIGURED FOR S16 STEREO REFUSES HEAP, EMPTY AND FRAME-MISALIGNED CHUNKS AS THE SAME CALLER BUG
    private static void assertMalformedChunksRejected(final SFXEngine engine) {
        assertThrows(IllegalArgumentException.class, () -> engine.upload(ByteBuffer.allocate(4 * MS_BYTES)), "a heap chunk");
        assertThrows(IllegalArgumentException.class, () -> engine.upload(ByteBuffer.allocateDirect(0)), "an empty chunk");
        assertThrows(IllegalArgumentException.class, () -> engine.upload(ByteBuffer.allocateDirect(MS_BYTES + 2)), "half a stereo frame");
        assertEquals(0, engine.pendingMs(), "nothing was queued");
    }
}
