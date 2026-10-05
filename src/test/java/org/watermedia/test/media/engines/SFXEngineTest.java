package org.watermedia.test.media.engines;

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
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.JSEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.api.media.players.FFMediaPlayer;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-Java verification of the {@link SFXEngine} implementations that the MasterClock/FFMediaPlayer
 * sync contract depends on: {@link JSEngine} format validation and both engines' capability
 * tables. The OpenAL cases run only when a real device/context can be opened, skipping gracefully
 * otherwise.
 */
@DisplayName("SFXEngine")
class SFXEngineTest {

    @BeforeAll
    static void boot() {
        // ENGINE CONSTRUCTION IS CLIENT-GATED BY THE SFXEngine BASE — BOOT A CLIENT ENVIRONMENT ONCE
        MediaBootstrap.client();
    }

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
        @DisplayName("format() rejects invalid arguments without opening a line")
        void formatValidation() {
            final JSEngine engine = MediaAPI.jsEngine();
            assertFalse(engine.format(null, 2, 48_000), "null type");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 0, 48_000), "channels < 1");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 9, 48_000), "channels > 8");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 2, 100), "rate below MIN");
            assertFalse(engine.format(SFXEngine.SampleType.S16, 2, 10_000_000), "rate above MAX");
            assertFalse(engine.format(SFXEngine.SampleType.DBL, 2, 48_000), "DBL has no Java Sound encoding");
        }

        @Test
        @DisplayName("No pitch control and no source handle")
        void capabilityContract() {
            final JSEngine engine = MediaAPI.jsEngine();
            assertFalse(engine.speed(2.0f), "Java Sound refuses speed control");
            assertEquals(1.0f, engine.speed(), "a refused speed keeps the 1.0x default");
            assertEquals(0, engine.source(), "Java Sound has no source handle");
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
                assertTrue(engine.format(SFXEngine.SampleType.S16, 2, 48_000), "S16 stereo");
                assertEquals(2, engine.channels());
                assertEquals(SFXEngine.SampleType.S16, engine.sampleType());

                assertEquals(AL.getCapabilities().AL_EXT_MCFORMATS, engine.format(SFXEngine.SampleType.S16, 6, 48_000), "S16 5.1");
                assertEquals(AL.getCapabilities().AL_EXT_DOUBLE, engine.format(SFXEngine.SampleType.DBL, 2, 48_000), "DBL stereo");

                assertFalse(engine.format(SFXEngine.SampleType.DBL, 6, 48_000), "DBL multichannel unsupported");
                assertFalse(engine.format(SFXEngine.SampleType.S32, 2, 48_000), "no native S32 PCM");
                assertFalse(engine.format(null, 2, 48_000), "null type");
            } finally {
                engine.release();
            }
        }

        @Test
        void nativeUploadErrorsPreserveQueueOwnership() throws Exception {
            final ALEngine engine = MediaAPI.alEngine(2);
            final ByteBuffer pcm = ByteBuffer.allocateDirect(64);
            final Field nativeFormat = ALEngine.class.getDeclaredField("alFormat");
            final Field free = ALEngine.class.getDeclaredField("freeCount");
            nativeFormat.setAccessible(true);
            free.setAccessible(true);
            try {
                assertTrue(engine.format(SFXEngine.SampleType.S16, 1, 48_000));
                nativeFormat.setInt(engine, 0x7FFFFFFF);
                assertThrows(IllegalStateException.class, () -> engine.upload(pcm));
                assertEquals(2, free.getInt(engine));
                assertEquals(0, AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED));
                assertTrue(engine.format(SFXEngine.SampleType.S16, 1, 48_000));
                assertTrue(engine.upload(pcm));
                assertTrue(engine.format(SFXEngine.SampleType.S16, 2, 48_000));
                assertThrows(IllegalStateException.class, () -> engine.upload(pcm));
                assertEquals(1, free.getInt(engine));
                assertEquals(1, AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED));
                engine.flush();
                assertTrue(engine.format(SFXEngine.SampleType.S16, 1, 48_000));
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
}
