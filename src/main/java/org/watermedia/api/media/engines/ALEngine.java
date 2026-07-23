package org.watermedia.api.media.engines;

import org.lwjgl.openal.*;

import java.nio.ByteBuffer;

/**
 * Default OpenAL-backed {@link SFXEngine}, driven through LWJGL.
 * <p>
 * Decoded PCM is uploaded into a small fixed pool of AL buffers streamed onto a single AL source;
 * the pool depth is what absorbs game hitches (GC, chunk loads) without underrunning, while the
 * media clock follows the audible position via {@link #pendingMs()} so depth never desyncs A/V.
 * When {@code AL_SOFT_source_latency} is present, latency queries compensate for device output
 * latency for sub-sample A/V precision.
 * <p>
 * <b>Precondition:</b> a current OpenAL device/context must already exist when an ALEngine is
 * constructed — in a mod the sound system owns that context, so it must be initialized first.
 * Construction fails fast with {@link IllegalStateException} when no context is current.
 * Create instances through {@code MediaAPI.alEngine}.
 */
public final class ALEngine extends SFXEngine {
    // 8 BUFFERS × ~43ms (2048 SAMPLES @ 48kHz) ≈ 340ms OF DEPTH. THE QUEUE DEPTH IS
    // WHAT ABSORBS GAME HITCHES (GC, CHUNK LOADS) WITHOUT UNDERRUNNING — THE MEDIA
    // CLOCK FOLLOWS THE AUDIBLE POSITION VIA pendingMs(), SO DEPTH DOESN'T DESYNC A/V.
    /** Default AL buffer pool depth used by {@code MediaAPI.alEngine()}. */
    public static final int DEFAULT_BUFFER_COUNT = 8;

    // CAPABILITY TABLES.
    // DBL MULTICHANNEL IS NOT SUPPORTED BECAUSE AL_EXT_DOUBLE HAS NO MULTICHANNEL VARIANTS AND
    // AL_EXT_MCFORMATS ONLY DEFINES 8/16-BIT INT + 32-BIT FLOAT FORMATS (NO 64-BIT).
    private static final SampleType[] SUPPORTED_TYPES = { SampleType.U8, SampleType.S16, SampleType.FLT, SampleType.DBL };
    private static final ChannelSupport[] SUPPORTED_CHANNELS = {
            new ChannelSupport(1, SampleType.U8, SampleType.S16, SampleType.FLT, SampleType.DBL), // MONO
            new ChannelSupport(2, SampleType.U8, SampleType.S16, SampleType.FLT, SampleType.DBL), // STEREO
            new ChannelSupport(4, SampleType.U8, SampleType.S16, SampleType.FLT),                 // QUAD
            new ChannelSupport(6, SampleType.U8, SampleType.S16, SampleType.FLT),                 // 5.1
            new ChannelSupport(7, SampleType.U8, SampleType.S16, SampleType.FLT),                 // 6.1
            new ChannelSupport(8, SampleType.U8, SampleType.S16, SampleType.FLT),                 // 7.1
    };

    private final int[] buffers;
    private final boolean latencySupported;
    // BUFFER OWNERSHIP AS PRIMITIVE RINGS — NO BOXING IN THE UPLOAD/CLOCK HOT PATH.
    // freeIds IS A LIFO STACK OF BUFFER IDS READY TO FILL. queuedDur IS A FIFO RING OF PER-BUFFER
    // DURATIONS MIRRORING THE SOURCE QUEUE ORDER, SO reclaimProcessed POPS DURATIONS IN THE SAME
    // ORDER AL RETURNS THE PROCESSED BUFFERS (USED BY pendingMs()).
    private final int[] freeIds;
    private int freeCount;
    private final long[] queuedDur;
    private int queuedHead;
    private int queuedCount;
    private long totalQueuedUs;
    // PRECOMPUTED AL FORMAT CONSTANT — UPDATED BY format(), READ BY upload
    private int alFormat;
    private int bytesPerFrame; // BYTES PER SAMPLE-FRAME (channels × sample size)
    // REUSED SCRATCH FOR AL_SOFT_source_latency QUERIES: [0] = offset seconds, [1] = device latency seconds
    private final double[] latencyValues = new double[2];

    public ALEngine(final int bufferCount) {
        if (bufferCount <= 0) throw new IllegalArgumentException("bufferCount must be positive, got " + bufferCount);
        this.buffers = new int[bufferCount];
        this.freeIds = new int[bufferCount];
        this.queuedDur = new long[bufferCount];
        // sampleType / channels / sampleRate / alFormat ARE POPULATED BY format()
        // BEFORE FIRST UPLOAD — UNINITIALIZED STATE IS A CALLER BUG
        this.source = this.genSource();
        // A CURRENT OPENAL CONTEXT IS REQUIRED: WITHOUT ONE alGenSources YIELDS 0 AND/OR SETS AN
        // ERROR, LEAVING A SILENTLY-BROKEN ENGINE. FAIL FAST INSTEAD.
        final int err = AL10.alGetError();
        if (this.source == 0 || err != AL10.AL_NO_ERROR)
            throw new IllegalStateException("OpenAL source/buffer generation failed (err=0x" + Integer.toHexString(err)
                    + "); a current OpenAL context must exist before creating an ALEngine");
        for (final int buffer: this.buffers) this.freeIds[this.freeCount++] = buffer;
        // DETECT AL_SOFT_source_latency ONCE — CONTEXT IS CURRENT (alGenSources SUCCEEDED ABOVE)
        final ALCapabilities caps = AL.getCapabilities();
        this.latencySupported = caps.AL_SOFT_source_latency;
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
    public boolean speed() {
        return true; // AL_PITCH RESAMPLES THE SOURCE NATIVELY
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
        // CLEAR OWNERSHIP SO A LATE upload() CAN'T POLL A DELETED BUFFER ID AND alBufferData A DEAD BUFFER
        this.freeCount = 0;
        this.queuedHead = 0;
        this.queuedCount = 0;
        this.totalQueuedUs = 0;
    }

    @Override
    public ChannelSupport[] supportedChannels() {
        return SUPPORTED_CHANNELS.clone();
    }

    @Override
    public SampleType[] supportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public boolean format(final SampleType type, final int channels, final int sampleRate) {
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

        if (this.freeCount == 0) return false; // NO BUFFER AVAILABLE — TRY LATER
        final int buffer = this.freeIds[--this.freeCount];

        final long durationUs = this.bytesPerFrame > 0 && this.sampleRate > 0
                ? (data.remaining() / this.bytesPerFrame) * 1_000_000L / this.sampleRate
                : 0L;
        AL10.alBufferData(buffer, this.alFormat, data, this.sampleRate);
        AL10.alSourceQueueBuffers(this.source, buffer);
        // ENQUEUE THE DURATION AT THE FIFO TAIL
        this.queuedDur[(this.queuedHead + this.queuedCount) % this.queuedDur.length] = durationUs;
        this.queuedCount++;
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
            this.freeIds[this.freeCount++] = AL10.alSourceUnqueueBuffers(this.source);
        }
        this.queuedHead = 0;
        this.queuedCount = 0;
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
            SOFTSourceLatency.alGetSourcedvSOFT(this.source, SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, this.latencyValues);
            offsetSec = this.latencyValues[0] - this.latencyValues[1];
        } else {
            offsetSec = AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET);
        }

        final long playedUs = (long) (Math.max(0.0, offsetSec) * 1_000_000.0);
        return Math.max(0L, (this.totalQueuedUs - playedUs) / 1000L);
    }

    @Override
    public long playbackMs() {
        final int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_INITIAL) return -1;

        if (this.latencySupported) {
            SOFTSourceLatency.alGetSourcedvSOFT(this.source, SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, this.latencyValues);
            return (long) ((this.latencyValues[0] - this.latencyValues[1]) * 1000.0);
        }
        return (long) (AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET) * 1000f);
    }

    @Override
    protected int genSource() {
        AL10.alGenBuffers(this.buffers);
        return AL10.alGenSources();
    }

    /** Returns a defensive copy of the AL buffer ids owned by this engine. */
    public int[] buffers() {
        return this.buffers.clone();
    }

    // UNQUEUES EVERY PROCESSED BUFFER AND RETURNS IT TO THE FREE STACK, KEEPING THE QUEUED-DURATION
    // FIFO IN SYNC (AL RETURNS PROCESSED BUFFERS IN QUEUE ORDER, SO WE POP THE FIFO FRONT).
    private void reclaimProcessed() {
        int processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            final int buffer = AL10.alSourceUnqueueBuffers(this.source);
            if (this.queuedCount > 0) {
                this.totalQueuedUs -= this.queuedDur[this.queuedHead];
                this.queuedHead = (this.queuedHead + 1) % this.queuedDur.length;
                this.queuedCount--;
            }
            this.freeIds[this.freeCount++] = buffer;
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
    // RETURNS -1 IF THE COMBINATION IS NOT SUPPORTED. type IS ALREADY NON-NULL (format() GUARDS IT).
    // ASSUMES OPENAL SOFT — AL_EXT_MCFORMATS, AL_EXT_FLOAT32, AND AL_EXT_DOUBLE ARE ALWAYS AVAILABLE THERE.
    private static int alFormatFor(final SampleType type, final int channels) {
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

}
