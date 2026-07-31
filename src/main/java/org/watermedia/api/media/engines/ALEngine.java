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
    // 8 BUFFERS × ~43ms ≈ 340ms OF DEPTH — WHAT RIDES OVER GAME HITCHES WITHOUT UNDERRUNS;
    // THE MEDIA CLOCK FOLLOWS THE AUDIBLE POSITION VIA pendingMs(), SO DEPTH NEVER DESYNCS A/V.
    /** Default AL buffer pool depth used by {@code MediaAPI.alEngine()}. */
    public static final int DEFAULT_BUFFER_COUNT = 8;

    // CAPABILITY TABLES: OPENAL SOFT SUPPORTS U8/S16/FLT UP TO 7.1 BUT DBL ONLY MONO/STEREO
    // (NO 64-BIT MCFORMATS); S32 HAS NO NATIVE FORMAT — THE NEGOTIATION FALLBACK IS S16/FLT.
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
    // PRIMITIVE RINGS, NO BOXING IN THE HOT PATH: freeIds IS A LIFO OF FILLABLE BUFFER IDS,
    // queuedDur A FIFO OF DURATIONS MIRRORING THE SOURCE QUEUE ORDER (POPPED BY reclaimProcessed).
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
        AL10.alGetError(); // DRAIN ANY RESIDUAL HOST ERROR (MC's SOUND ENGINE) SO THE CHECK BELOW SEES ONLY OURS
        AL10.alGenBuffers(this.buffers);
        this.source = AL10.alGenSources();
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
    public boolean speed(final float speed) {
        AL10.alSourcef(this.source, AL10.AL_PITCH, speed); // AL_PITCH RESAMPLES THE SOURCE NATIVELY
        this.speed = speed;
        return true;
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
        // DRAIN *ALL* PROCESSED BUFFERS FIRST: alSourcePlay ON A STOPPED SOURCE (UNDERRUN) REPLAYS
        // ITS QUEUE FROM THE START, SO ANY STALE BUFFER LEFT QUEUED PLAYS OLD AUDIO AGAIN.
        this.reclaimProcessed();

        // A STOPPED SOURCE MARKS EVERY QUEUED BUFFER PROCESSED — EVEN FRESH ONES — SO AN UPLOAD
        // WOULD BE RECLAIMED AND LOST ON THE NEXT CALL; REWIND TO INITIAL SO NEW AUDIO PENDS PROPERLY
        if (this.queuedCount == 0 && AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
            AL10.alSourceRewind(this.source);
        }

        // UNCONFIGURED (NO format() YET) OR NO BUFFER AVAILABLE — SIGNAL BACKPRESSURE
        if (this.alFormat == 0 || this.freeCount == 0) return false;
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
        // DETACH THE WHOLE QUEUE IN ONE CALL (AL_BUFFER=0 ON A STOPPED SOURCE) AND REBUILD THE FREE
        // STACK TO THE EXACT BUFFER SET — INCREMENTAL UNQUEUEING OVERFLOWED freeIds ON DRIVER MISCOUNTS.
        AL10.alSourcei(this.source, AL10.AL_BUFFER, 0);
        // LAND ON INITIAL, NOT STOPPED: A STOPPED SOURCE REPORTS EVERY FUTURE QUEUED BUFFER AS
        // PROCESSED, SO PAUSED WARM-UP UPLOADS AFTER A SEEK WOULD BE RECLAIMED AND LOST
        AL10.alSourceRewind(this.source);
        System.arraycopy(this.buffers, 0, this.freeIds, 0, this.buffers.length);
        this.freeCount = this.buffers.length;
        this.queuedHead = 0;
        this.queuedCount = 0;
        this.totalQueuedUs = 0;
    }

    @Override
    public long pendingMs() {
        final int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
        // STOPPED (UNDERRUN) MEANS EVERYTHING QUEUED ALREADY PLAYED AND THE OFFSET RESET TO 0,
        // SO THE SUBTRACTION BELOW WOULD WRONGLY REPORT THE WHOLE QUEUE AS PENDING.
        if (state == AL10.AL_STOPPED) return 0;

        // QUEUE PLAYBACK OFFSET, LATENCY-COMPENSATED WHEN AL_SOFT_source_latency EXISTS: THE
        // SAMPLE AT THE LISTENER IS offset − deviceLatency, GROWING THE PENDING WINDOW.
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
            // DEVICE LATENCY CAN EXCEED THE OFFSET RIGHT AFTER START — CLAMP SO -1 STAYS UNAMBIGUOUS
            return Math.max(0L, (long) ((this.latencyValues[0] - this.latencyValues[1]) * 1000.0));
        }
        return (long) (AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET) * 1000f);
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
            // DRIVER MISCOUNT GUARD: A FULL FREE STACK MEANS EVERY OWNED BUFFER IS ALREADY RECLAIMED
            if (this.freeCount == this.freeIds.length) break;
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

    // MAPS TYPE + CHANNELS TO AN OPENAL FORMAT CONSTANT, -1 WHEN UNSUPPORTED. ASSUMES OPENAL
    // SOFT, WHERE AL_EXT_MCFORMATS/AL_EXT_FLOAT32/AL_EXT_DOUBLE ARE ALWAYS PRESENT.
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
