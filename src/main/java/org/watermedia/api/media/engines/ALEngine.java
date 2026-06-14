package org.watermedia.api.media.engines;

import org.lwjgl.openal.*;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class ALEngine extends SFXEngine {
    // DEFAULTS
    // 8 BUFFERS × ~43ms (2048 SAMPLES @ 48kHz) ≈ 340ms OF DEPTH. THE QUEUE DEPTH IS
    // WHAT ABSORBS GAME HITCHES (GC, CHUNK LOADS) WITHOUT UNDERRUNNING — THE MEDIA
    // CLOCK FOLLOWS THE AUDIBLE POSITION VIA pendingMs(), SO DEPTH DOESN'T DESYNC A/V.
    private static final int DEFAULT_BUFFER_COUNT = 8;

    // CAPABILITY TABLES.
    // SUPPORTED_TYPES DEFINES THE BYTE ORDER USED IN SUPPORTED_CHANNELS ENTRIES.
    // SUPPORTED_CHANNELS ENCODING PER long (MSB → LSB):
    //   byte 7 = channel count
    //   byte 6 = U8  support flag (0xFF or 0x00)
    //   byte 5 = S16 support flag
    //   byte 4 = FLT support flag
    //   byte 3 = DBL support flag
    //   bytes 2..0 = reserved for future types
    // DBL MULTICHANNEL IS NOT SUPPORTED BECAUSE AL_EXT_DOUBLE HAS NO MULTICHANNEL VARIANTS AND
    // AL_EXT_MCFORMATS ONLY DEFINES 8/16-BIT INT + 32-BIT FLOAT FORMATS (NO 64-BIT).
    private static final SampleType[] SUPPORTED_TYPES = { SampleType.U8, SampleType.S16, SampleType.FLT, SampleType.DBL };
    private static final long[] SUPPORTED_CHANNELS = {
            //    ch   U8  S16 FLT DBL --  --  --
            0x01_FF_FF_FF_FF_00_00_00L, // mono:   all four types
            0x02_FF_FF_FF_FF_00_00_00L, // stereo: all four types
            0x04_FF_FF_FF_00_00_00_00L, // quad:   no DBL
            0x06_FF_FF_FF_00_00_00_00L, // 5.1:    no DBL
            0x07_FF_FF_FF_00_00_00_00L, // 6.1:    no DBL
            0x08_FF_FF_FF_00_00_00_00L, // 7.1:    no DBL
    };

    private final int[] buffers;
    private final boolean latencySupported;
    // BUFFER OWNERSHIP — FREE BUFFERS READY TO FILL, AND DURATION BOOKKEEPING FOR
    // BUFFERS CURRENTLY QUEUED ON THE SOURCE (USED BY pendingMs()).
    private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();
    private final Map<Integer, Long> queuedDurationUs = new HashMap<>();
    private long totalQueuedUs;
    // PRECOMPUTED AL FORMAT CONSTANT — UPDATED BY setAudioFormat, READ BY upload
    private int alFormat;
    private int bytesPerFrame; // BYTES PER SAMPLE-FRAME (channels × sample size)

    public ALEngine(final int bufferCount) {
        this.buffers = new int[bufferCount];
        // sampleType / channels / sampleRate / alFormat ARE POPULATED BY setAudioFormat
        // BEFORE FIRST UPLOAD — UNINITIALIZED STATE IS A CALLER BUG
        // FINAL INIT
        this.source = this.genSource();
        for (final int buffer: this.buffers) this.freeBuffers.push(buffer);
        // DETECT AL_SOFT_source_latency ONCE — CONTEXT MUST BE CURRENT (alGenSources SUCCEEDED ABOVE)
        final ALCapabilities caps = AL.getCapabilities();
        this.latencySupported = caps.AL_SOFT_source_latency;
    }

    @Override
    protected int genSource() {
        AL10.alGenBuffers(this.buffers);
        return AL10.alGenSources();
    }

    @Override
    public void pause() {
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(this.source);
        }
    }

    @Override
    public void play() {
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.source);
        }
    }

    @Override
    public void speed(final float speed) {
        AL10.alSourcef(this.source, AL10.AL_PITCH, speed);
    }

    @Override
    public void volume(final float volume) {
        AL10.alSourcef(this.source, AL10.AL_GAIN, volume);
    }

    @Override
    public void release() {
        this.flush();
        AL10.alDeleteSources(this.source);
        AL10.alDeleteBuffers(this.buffers);
    }

    @Override
    public long[] supportedChannels() {
        return SUPPORTED_CHANNELS.clone();
    }

    @Override
    public SampleType[] supportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean setAudioFormat(final SampleType type, final int channels, final int sampleRate) {
        if (type == null) return false;
        if (channels < 1 || channels > 8) return false;
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) return false;
        final int al = alFormatFor(type, channels);
        if (al == -1) return false;
        this.sampleType = type;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.alFormat = al;
        this.bytesPerFrame = channels * bytesPerSample(type);
        return true;
    }

    @Override
    public boolean upload(final ByteBuffer data) {
        // RECLAIM FINISHED BUFFERS FIRST. THIS MUST DRAIN *ALL* PROCESSED BUFFERS:
        // AFTER AN UNDERRUN THE SOURCE STOPS WITH ITS WHOLE QUEUE MARKED PROCESSED,
        // AND alSourcePlay ON A STOPPED SOURCE REPLAYS THE QUEUE FROM THE START —
        // LEAVING STALE BUFFERS QUEUED MAKES OLD AUDIO PLAY AGAIN.
        this.reclaimProcessed();

        final Integer buffer = this.freeBuffers.poll();
        if (buffer == null) return false; // NO BUFFER AVAILABLE — TRY LATER

        final long durationUs = this.bytesPerFrame > 0 && this.sampleRate > 0
                ? (data.remaining() / this.bytesPerFrame) * 1_000_000L / this.sampleRate
                : 0L;
        AL10.alBufferData(buffer, this.alFormat, data, this.sampleRate);
        AL10.alSourceQueueBuffers(this.source, buffer);
        this.queuedDurationUs.put(buffer, durationUs);
        this.totalQueuedUs += durationUs;
        return true;
    }

    @Override
    public void flush() {
        AL10.alSourceStop(this.source);
        // STOPPING MARKS EVERY QUEUED BUFFER AS PROCESSED — RECLAIM THEM ALL
        this.reclaimProcessed();
        // SAFETY NET FOR BUFFERS THE DRIVER DIDN'T REPORT YET
        final int queued = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) {
            final int buffer = AL10.alSourceUnqueueBuffers(this.source);
            this.queuedDurationUs.remove(buffer);
            this.freeBuffers.push(buffer);
        }
        this.queuedDurationUs.clear();
        this.totalQueuedUs = 0;
    }

    @Override
    public long pendingMs() {
        final int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
        // STOPPED (UNDERRUN/FLUSH) MEANS EVERYTHING QUEUED ALREADY PLAYED; THE
        // SAMPLE OFFSET RESETS TO 0 IN THAT STATE, SO THE SUBTRACTION BELOW
        // WOULD WRONGLY REPORT THE WHOLE QUEUE AS PENDING.
        if (state == AL10.AL_STOPPED) return 0;

        // PLAYBACK OFFSET WITHIN THE CURRENT QUEUE, LATENCY-COMPENSATED WHEN
        // AL_SOFT_source_latency IS AVAILABLE (THE SAMPLE AT THE LISTENER IS
        // offset − deviceLatency, SO THE PENDING WINDOW GROWS BY THE LATENCY).
        final double offsetSec;
        if (this.latencySupported) {
            final double[] values = new double[2];
            SOFTSourceLatency.alGetSourcedvSOFT(this.source, SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, values);
            offsetSec = values[0] - values[1];
        } else {
            offsetSec = AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET);
        }

        final long playedUs = (long) (Math.max(0.0, offsetSec) * 1_000_000.0);
        return Math.max(0L, (this.totalQueuedUs - playedUs) / 1000L);
    }

    // UNQUEUES EVERY PROCESSED BUFFER AND RETURNS IT TO THE FREE LIST,
    // KEEPING THE QUEUED-DURATION BOOKKEEPING IN SYNC.
    private void reclaimProcessed() {
        int processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            final int buffer = AL10.alSourceUnqueueBuffers(this.source);
            final Long durationUs = this.queuedDurationUs.remove(buffer);
            if (durationUs != null) this.totalQueuedUs -= durationUs;
            this.freeBuffers.push(buffer);
        }
    }

    // BYTES PER SINGLE-CHANNEL SAMPLE FOR EACH CANONICAL TYPE
    private static int bytesPerSample(final SampleType type) {
        return switch (type) {
            case U8 -> 1;
            case S16 -> 2;
            case S32, FLT -> 4;
            case DBL -> 8;
        };
    }

    // MAPS A CANONICAL SAMPLE TYPE + CHANNEL COUNT TO AN OPENAL FORMAT CONSTANT.
    // RETURNS -1 IF THE COMBINATION IS NOT SUPPORTED.
    // ASSUMES OPENAL SOFT — AL_EXT_MCFORMATS, AL_EXT_FLOAT32, AND AL_EXT_DOUBLE ARE ALWAYS AVAILABLE THERE.
    private static int alFormatFor(final SampleType type, final int channels) {
        if (type == null) return -1;
        return switch (type) {
            case U8 -> switch (channels) {
                case 1 -> AL10.AL_FORMAT_MONO8;
                case 2 -> AL10.AL_FORMAT_STEREO8;
                case 4 -> EXTMCFormats.AL_FORMAT_QUAD8;
                case 6 -> EXTMCFormats.AL_FORMAT_51CHN8;
                case 7 -> EXTMCFormats.AL_FORMAT_61CHN8;
                case 8 -> EXTMCFormats.AL_FORMAT_71CHN8;
                default -> -1;
            };
            case S16 -> switch (channels) {
                case 1 -> AL10.AL_FORMAT_MONO16;
                case 2 -> AL10.AL_FORMAT_STEREO16;
                case 4 -> EXTMCFormats.AL_FORMAT_QUAD16;
                case 6 -> EXTMCFormats.AL_FORMAT_51CHN16;
                case 7 -> EXTMCFormats.AL_FORMAT_61CHN16;
                case 8 -> EXTMCFormats.AL_FORMAT_71CHN16;
                default -> -1;
            };
            // OPENAL HAS NO NATIVE S32 INTEGER PCM FORMAT — CALLER MUST RESAMPLE TO S16 OR FLT
            case S32 -> -1;
            case FLT -> switch (channels) {
                case 1 -> EXTFloat32.AL_FORMAT_MONO_FLOAT32;
                case 2 -> EXTFloat32.AL_FORMAT_STEREO_FLOAT32;
                case 4 -> EXTMCFormats.AL_FORMAT_QUAD32;
                case 6 -> EXTMCFormats.AL_FORMAT_51CHN32;
                case 7 -> EXTMCFormats.AL_FORMAT_61CHN32;
                case 8 -> EXTMCFormats.AL_FORMAT_71CHN32;
                default -> -1;
            };
            // DBL COVERS MONO/STEREO ONLY — AL_EXT_DOUBLE HAS NO MULTICHANNEL VARIANTS
            case DBL -> switch (channels) {
                case 1 -> EXTDouble.AL_FORMAT_MONO_DOUBLE_EXT;
                case 2 -> EXTDouble.AL_FORMAT_STEREO_DOUBLE_EXT;
                default -> -1;
            };
        };
    }

    @Override
    public long playbackMs() {
        final int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_INITIAL) return -1;

        if (this.latencySupported) {
            // [0] = offset seconds (fractional), [1] = device output latency seconds
            final double[] values = new double[2];
            SOFTSourceLatency.alGetSourcedvSOFT(this.source, SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, values);
            return (long) ((values[0] - values[1]) * 1000.0);
        }
        return (long) (AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET) * 1000f);
    }

    public int[] buffers() {
        return this.buffers;
    }

    public int[] buffersCopy() {
        return this.buffers.clone();
    }

    /**
     * Creates an ALEngine with default parameters: {@value DEFAULT_BUFFER_COUNT} buffers.
     * The caller must invoke {@link #setAudioFormat(SampleType, int, int)} before uploading.
     */
    public static ALEngine buildDefault() {
        return new Builder().build();
    }

    /**
     * Builder for {@link ALEngine}. Kept for consistency with the {@code GFXEngine} construction pattern.
     * Audio format (sample type, channels, sample rate) is not configurable at construction — it is
     * always declared later via {@link ALEngine#setAudioFormat(SampleType, int, int)}.
     */
    public static final class Builder {
        private int bufferCount = DEFAULT_BUFFER_COUNT;

        public Builder bufferCount(final int bufferCount) {
            this.bufferCount = bufferCount;
            return this;
        }

        public ALEngine build() {
            if (this.bufferCount <= 0) {
                throw new IllegalArgumentException("bufferCount must be positive, got " + this.bufferCount);
            }
            return new ALEngine(this.bufferCount);
        }
    }
}