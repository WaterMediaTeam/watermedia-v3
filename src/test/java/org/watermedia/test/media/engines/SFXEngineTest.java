package org.watermedia.test.media.engines;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.JSEngine;
import org.watermedia.api.media.engines.SFXEngine;
import org.watermedia.test.support.MediaBootstrap;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertFalse(engine.speed(), "Java Sound reports no speed control");
            assertEquals(0, engine.source(), "Java Sound has no source handle");
            engine.speed(2.0f); // NO-OP, MUST NOT THROW
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
                assertEquals(4, engine.supportedTypes().length);
                assertTableConsistent(engine);
                assertNotEquals(0, engine.source(), "a source handle is generated under a live context");
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

                assertTrue(engine.format(SFXEngine.SampleType.S16, 6, 48_000), "S16 5.1");
                assertTrue(engine.format(SFXEngine.SampleType.DBL, 2, 48_000), "DBL stereo");

                assertFalse(engine.format(SFXEngine.SampleType.DBL, 6, 48_000), "DBL multichannel unsupported");
                assertFalse(engine.format(SFXEngine.SampleType.S32, 2, 48_000), "no native S32 PCM");
                assertFalse(engine.format(null, 2, 48_000), "null type");
            } finally {
                engine.release();
            }
        }
    }
}
