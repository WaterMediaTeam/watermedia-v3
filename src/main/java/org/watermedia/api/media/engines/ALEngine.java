package org.watermedia.api.media.engines;

import org.lwjgl.openal.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;

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

    private final SampleType[] supportedTypes;
    private final ChannelSupport[] supportedChannels;

    private final int[] buffers;
    private final boolean latencySupported;
    private final boolean spatial;
    private final boolean threadContext;
    private final long context;
    private final boolean efx;
    private final int auxiliarySends;
    private volatile SpatialAudio spatialAudio;
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

    // THE PLAYER'S UPLOAD LOOP AND HOST CONTROLS SHARE ONE SOURCE; THIS MONITOR ALSO OWNS ITS QUEUE.
    // RELEASE CANNOT RECYCLE NATIVE IDS WHILE AN UPLOAD OR AN ENVIRONMENT CALLBACK STILL USES THEM.

    public ALEngine(final int bufferCount) {
        this(bufferCount, false);
    }

    /** Creates a source; spatial mode limits decoded output to mono for positional playback. */
    public ALEngine(final int bufferCount, final boolean spatial) {
        if (bufferCount <= 0) throw new IllegalArgumentException("bufferCount must be positive, got " + bufferCount);
        final ALCapabilities caps = AL.getCapabilities();
        this.supportedChannels = channelTable(caps.AL_EXT_FLOAT32, caps.AL_EXT_DOUBLE, caps.AL_EXT_MCFORMATS, spatial);
        final EnumSet<SampleType> types = EnumSet.noneOf(SampleType.class);
        for (final ChannelSupport channels: this.supportedChannels) types.addAll(channels.types());
        this.supportedTypes = types.toArray(SampleType[]::new);
        this.threadContext = ALC.getCapabilities().ALC_EXT_thread_local_context;
        final long localContext = this.threadContext ? EXTThreadLocalContext.alcGetThreadContext() : 0;
        this.context = localContext != 0 ? localContext : ALC10.alcGetCurrentContext();
        if (this.context == 0) throw new IllegalStateException("An OpenAL context must be current before creating an ALEngine");
        this.spatial = spatial;
        this.efx = caps.ALC_EXT_EFX;
        this.auxiliarySends = this.efx
                ? ALC10.alcGetInteger(ALC10.alcGetContextsDevice(this.context), EXTEfx.ALC_MAX_AUXILIARY_SENDS) : 0;
        this.buffers = new int[bufferCount];
        this.freeIds = new int[bufferCount];
        this.queuedDur = new long[bufferCount];
        // sampleType / channels / sampleRate / alFormat ARE POPULATED BY format()
        // BEFORE FIRST UPLOAD — UNINITIALIZED STATE IS A CALLER BUG
        AL10.alGetError(); // DRAIN ANY RESIDUAL HOST ERROR (MC's SOUND ENGINE) SO THE CHECK BELOW SEES ONLY OURS
        try {
            AL10.alGenBuffers(this.buffers);
            this.source = AL10.alGenSources();
            final int err = AL10.alGetError();
            if (this.source == 0 || err != AL10.AL_NO_ERROR)
                throw new IllegalStateException("OpenAL source/buffer generation failed (err=0x" + Integer.toHexString(err) + ")");
            for (final int buffer: this.buffers) this.freeIds[this.freeCount++] = buffer;
            this.latencySupported = caps.AL_SOFT_source_latency;
            if (spatial) {
                this.clearSpatial();
                final int spatialError = AL10.alGetError();
                if (spatialError != AL10.AL_NO_ERROR)
                    throw new IllegalStateException("OpenAL spatial source setup failed (err=0x" + Integer.toHexString(spatialError) + ")");
            }
        } catch (final RuntimeException | Error failure) {
            // BOTH SOURCE GENERATION AND OPTIONAL EFFECT SETUP CAN FAIL AFTER BUFFERS WERE ALLOCATED.
            try {
                if (this.source != 0) AL10.alDeleteSources(this.source);
                for (final int buffer: this.buffers) if (buffer != 0) AL10.alDeleteBuffers(buffer);
            } catch (final RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public boolean spatial() { return this.spatial; }

    @Override
    public SpatialAudio spatialAudio() { return this.spatialAudio; }

    @Override
    public synchronized boolean spatialAudio(final SpatialAudio audio) {
        if (!this.spatial || this.source == 0) return false;
        this.requireContext();
        final SpatialAudio previous = this.spatialAudio;
        this.spatialAudio = null;
        try {
            if (previous != null && previous.environment() != null
                    && (audio == null || previous.environment() != audio.environment())) {
                previous.environment().reset(this.source);
            }
        } finally {
            // CLEAR EFFECTS EVEN IF THE ADAPTER WAS DISABLED OR ITS RESET FAILED.
            this.clearSpatial();
        }
        if (audio == null) return true;
        try {
            AL10.alSourcei(this.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(this.source, AL10.AL_POSITION, (float) audio.x(), (float) audio.y(), (float) audio.z());
            AL10.alSourcef(this.source, AL10.AL_REFERENCE_DISTANCE, audio.referenceDistance());
            AL10.alSourcef(this.source, AL10.AL_MAX_DISTANCE, audio.maxDistance());
            AL10.alSourcef(this.source, AL10.AL_ROLLOFF_FACTOR, audio.rolloff());
            // SOUND PHYSICS MAY MOVE AL_POSITION TOWARD A REFLECTION; ALWAYS RESTORE THE REAL ORIGIN FIRST.
            if (audio.environment() != null) audio.environment().process(this.source, audio);
            this.spatialAudio = audio;
            return true;
        } catch (final RuntimeException | Error failure) {
            this.clearSpatial();
            throw failure;
        }
    }

    private void requireContext() {
        final long localContext = this.threadContext ? EXTThreadLocalContext.alcGetThreadContext() : 0;
        if ((localContext != 0 ? localContext : ALC10.alcGetCurrentContext()) != this.context)
            throw new IllegalStateException("The ALEngine's original OpenAL context must be current on this thread");
    }

    private void clearSpatial() {
        AL10.alSourcei(this.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(this.source, AL10.AL_POSITION, 0, 0, 0);
        AL10.alSourcef(this.source, AL10.AL_ROLLOFF_FACTOR, 0);
        if (this.efx) {
            AL10.alSourcei(this.source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            for (int send = 0; send < this.auxiliarySends; send++) {
                AL11.alSource3i(this.source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, send, EXTEfx.AL_FILTER_NULL);
            }
            AL10.alSourcef(this.source, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 0);
        }
    }

    @Override
    public synchronized void pause() {
        if (this.source == 0) return;
        this.requireContext();
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(this.source);
        }
    }

    @Override
    public synchronized void play() {
        if (this.source == 0) return;
        this.requireContext();
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.source);
        }
    }

    // AL_PITCH IS CORE OPENAL; ONLY A RELEASED SOURCE CANNOT CHANGE SPEED. THE VOLATILE READ NEEDS NO CONTEXT
    @Override
    public boolean canSpeed() { return this.source != 0; }

    @Override
    public synchronized boolean speed(final float speed) {
        if (this.source == 0 || !Float.isFinite(speed) || speed <= 0) return false;
        this.requireContext();
        AL10.alGetError();
        AL10.alSourcef(this.source, AL10.AL_PITCH, speed); // AL_PITCH RESAMPLES THE SOURCE NATIVELY
        if (AL10.alGetError() != AL10.AL_NO_ERROR) return false;
        this.speed = speed;
        return true;
    }

    @Override
    public synchronized void volume(final float volume) {
        if (this.source == 0) return;
        if (!Float.isFinite(volume) || volume < 0) throw new IllegalArgumentException("Volume must be finite and nonnegative");
        this.requireContext();
        AL10.alSourcef(this.source, AL10.AL_GAIN, volume);
    }

    @Override
    public synchronized void release() {
        if (this.source == 0) return;
        this.requireContext();
        try {
            if (this.spatial) this.spatialAudio(null);
        } finally {
            // DELETING THE SOURCE STOPS PLAYBACK AND DETACHES EVERY BUFFER, EVEN AFTER A FAILED UPLOAD.
            AL10.alDeleteSources(this.source);
            AL10.alDeleteBuffers(this.buffers);
            this.source = 0;
            this.freeCount = 0;
            this.queuedHead = 0;
            this.queuedCount = 0;
            this.totalQueuedUs = 0;
        }
    }

    @Override
    public ChannelSupport[] supportedChannels() {
        return this.supportedChannels.clone();
    }

    @Override
    public SampleType[] supportedTypes() {
        return this.supportedTypes.clone();
    }

    @Override
    public synchronized boolean format(final SampleType type, final int channels, final int sampleRate) {
        if (type == null) return false;
        if (this.source == 0 || (this.spatial && channels != 1)) return false;
        if (channels < 1 || channels > 8) return false;
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) return false;
        final ChannelSupport support = this.channelSupport(channels);
        if (support == null || !support.supports(type)) return false;
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
    public synchronized boolean upload(final ByteBuffer data) {
        if (this.source == 0) return false;
        this.requireContext();
        if (this.alFormat == 0) return false;
        if (!data.isDirect() || !data.hasRemaining() || data.remaining() % this.bytesPerFrame != 0)
            throw new IllegalArgumentException("Audio data must be direct, nonempty and sample-frame aligned");
        AL10.alGetError();
        // DRAIN *ALL* PROCESSED BUFFERS FIRST: alSourcePlay ON A STOPPED SOURCE (UNDERRUN) REPLAYS
        // ITS QUEUE FROM THE START, SO ANY STALE BUFFER LEFT QUEUED PLAYS OLD AUDIO AGAIN.
        this.reclaimProcessed();

        // A STOPPED SOURCE MARKS EVERY QUEUED BUFFER PROCESSED — EVEN FRESH ONES — SO AN UPLOAD
        // WOULD BE RECLAIMED AND LOST ON THE NEXT CALL; REWIND TO INITIAL SO NEW AUDIO PENDS PROPERLY
        final int state = AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE);
        checkError("query source state");
        if (this.queuedCount == 0 && state == AL10.AL_STOPPED) {
            AL10.alSourceRewind(this.source);
            checkError("rewind source");
        }

        // UNCONFIGURED (NO format() YET) OR NO BUFFER AVAILABLE — SIGNAL BACKPRESSURE
        if (this.freeCount == 0) return false;
        final int buffer = this.freeIds[this.freeCount - 1];

        final long durationUs = this.bytesPerFrame > 0 && this.sampleRate > 0
                ? (data.remaining() / this.bytesPerFrame) * 1_000_000L / this.sampleRate
                : 0L;
        AL10.alBufferData(buffer, this.alFormat, data, this.sampleRate);
        checkError("upload PCM");
        AL10.alSourceQueueBuffers(this.source, buffer);
        checkError("queue PCM");
        // TRANSFER OWNERSHIP ONLY AFTER BOTH NATIVE OPERATIONS SUCCEEDED.
        this.freeCount--;
        // ENQUEUE THE DURATION AT THE FIFO TAIL
        this.queuedDur[(this.queuedHead + this.queuedCount) % this.queuedDur.length] = durationUs;
        this.queuedCount++;
        this.totalQueuedUs += durationUs;
        return true;
    }

    @Override
    public synchronized void flush() {
        if (this.source == 0) return;
        this.requireContext();
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
    public synchronized long pendingMs() {
        if (this.source == 0) return 0;
        this.requireContext();
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
    public synchronized long playbackMs() {
        if (this.source == 0) return -1;
        this.requireContext();
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
        checkError("query processed buffers");
        if (processed > this.queuedCount) throw new IllegalStateException("OpenAL processed-buffer count exceeds the owned queue");
        while (processed-- > 0) {
            // DRIVER MISCOUNT GUARD: A FULL FREE STACK MEANS EVERY OWNED BUFFER IS ALREADY RECLAIMED
            if (this.freeCount == this.freeIds.length) break;
            final int buffer = AL10.alSourceUnqueueBuffers(this.source);
            checkError("unqueue PCM");
            if (this.queuedCount > 0) {
                this.totalQueuedUs -= this.queuedDur[this.queuedHead];
                this.queuedHead = (this.queuedHead + 1) % this.queuedDur.length;
                this.queuedCount--;
            }
            this.freeIds[this.freeCount++] = buffer;
        }
    }

    private static void checkError(final String operation) {
        final int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR)
            throw new IllegalStateException("OpenAL failed to " + operation + " (err=0x" + Integer.toHexString(error) + ")");
    }

    private static ChannelSupport[] channelTable(final boolean floating, final boolean doubles, final boolean multichannel, final boolean spatial) {
        final EnumSet<SampleType> direct = EnumSet.of(SampleType.U8, SampleType.S16);
        if (floating) direct.add(SampleType.FLT);
        if (doubles) direct.add(SampleType.DBL);
        final var channels = new ArrayList<ChannelSupport>();
        channels.add(new ChannelSupport(1, direct));
        if (!spatial) {
            channels.add(new ChannelSupport(2, direct));
            if (multichannel) {
                // MCFORMATS DECLARES ITS OWN FLOAT FORMATS; EXT_FLOAT32 ONLY GATES MONO/STEREO FLOAT.
                final EnumSet<SampleType> surround = EnumSet.of(SampleType.U8, SampleType.S16, SampleType.FLT);
                for (final int count: new int[] { 4, 6, 7, 8 }) channels.add(new ChannelSupport(count, surround));
            }
        }
        return channels.toArray(ChannelSupport[]::new);
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

    // MAPS A COMBINATION ALREADY ACCEPTED BY THE CONTEXT'S CAPABILITY TABLE TO ITS NATIVE FORMAT.
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
