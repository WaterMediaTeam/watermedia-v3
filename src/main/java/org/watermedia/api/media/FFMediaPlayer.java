package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB.Callback_Pointer;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swresample.*;
import org.bytedeco.ffmpeg.swscale.*;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.Track;
import org.watermedia.tools.*;
import org.lwjgl.system.MemoryUtil;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ThreadFactory;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * MediaPlayer implementation using FFmpeg with multi-threaded architecture.
 * <p>Four long-lived threads: a lifecycle/consumption thread plus demux, video decode and
 * audio decode threads that produce decoded frames into thread-safe queues, the lifecycle
 * thread consuming those frames itself. HLS inputs add a short-lived probe thread that
 * classifies the playlist as live or VOD while playback already runs.
 * <p>Video frames are uploaded to GFXEngine as native YUV planes whenever the pixel
 * format is directly supported (YUV420P, NV12, etc.), avoiding CPU-side sws_scale.
 * For unsupported formats, sws_scale to BGRA is used as a fallback.
 * <p>When {@link #scale(int, int)} or a {@link LodLevel} below MAX is active, frames
 * are downscaled with sws_scale before upload — in the native pixel format when
 * supported as sws output, otherwise as BGRA. The target is resolved per frame, so
 * LOD changes apply on the fly without disturbing playback.
 */
public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());
    // PIPELINE THREADS ARE OWNED WORKERS (THEIR CALLBACKS CANNOT RUN LIFECYCLE OPERATIONS) NAMED FFThread-<PLAYER>-<ROLE>
    private static final ThreadFactory THREADS = ThreadTool.workerFactory("FFThread", Thread.NORM_PRIORITY);
    private static int players; // GUARDED BY THREADS

    // AUDIO OUTPUT FORMAT
    private static final int AUDIO_SAMPLES = 2048;

    // THREADING
    private static final int MAX_DECODE_THREADS = ThreadTool.processors() / 2;

    // QUEUE CAPACITIES
    private static final long VIDEO_PACKET_QUEUE_BYTES = 16L * 1024 * 1024;
    private static final long AUDIO_PACKET_QUEUE_BYTES = 8L * 1024 * 1024;
    private static final int VIDEO_FRAME_QUEUE_SLOTS = 3;
    private static final int AUDIO_FRAME_QUEUE_SLOTS = 9;

    // A/V SYNC THRESHOLDS (SECONDS)
    private static final double AV_SYNC_TOO_EARLY_SEC = 0.040;
    // AUDIO IS FED TO THE SFX ENGINE EAGERLY — THE ENGINE'S OWN BUFFER POOL IS THE
    // BACKPRESSURE (upload() RETURNS FALSE WHEN FULL) AND ITS QUEUE DEPTH IS WHAT
    // ABSORBS GAME HITCHES. THIS LEAD IS ONLY A SAFETY CAP AGAINST BOGUS PTS JUMPS;
    // IT MUST STAY WELL BELOW PTS_DISCONTINUITY_SEC.
    private static final double AUDIO_MAX_LEAD_SEC = 0.5;

    // HW DECODE HEALTH — FALL BACK TO SOFTWARE WHEN THE GPU PATH MISBEHAVES.
    // SOME DRIVERS (NOTABLY AMD D3D11VA) DECODE FINE BUT STALL ON THE GPU→CPU
    // FRAME TRANSFER, MAKING HW DECODE SLOWER THAN SOFTWARE.
    private static final int HW_TRANSFER_FAIL_LIMIT = 30;       // CONSECUTIVE av_hwframe_transfer_data FAILURES
    private static final int HW_TRANSFER_PERF_WINDOW = 120;     // FRAMES PER PERFORMANCE EVALUATION
    private static final double HW_TRANSFER_BUDGET_RATIO = 0.75; // MAX AVG TRANSFER TIME VS FRAME DURATION

    // PERFORMANCE MONITORING: THE CLOCK RATE IS SAMPLED AND CHECKED EVERY INTERVAL; SLOW CONSUMPTION ITERATIONS ARE
    // COUNTED AND REPORTED ONCE WHEN THE RUN ENDS
    private static final long PERF_CHECK_INTERVAL_MS = 2000;
    private static final double PERF_RATE_WARN_THRESHOLD = 0.90;
    private static final String SLOW_REPORT = "Slow consumption iterations: {} in {}ms (worst={}ms, audio={}ms, video={}ms, frame={}ms)";

    // STARVATION DETECTION
    private static final long STARVATION_THRESHOLD_MS = 500;
    private static final int[] VIDEO_HW_CODECS = {
            AV_HWDEVICE_TYPE_CUDA,          // NVIDIA
            AV_HWDEVICE_TYPE_QSV,           // INTEL
            AV_HWDEVICE_TYPE_AMF,           // AMD
            // DEDICATED HW LIBRARY
            AV_HWDEVICE_TYPE_OHCODEC,
            // OS DEDICATED HARDWARE
            AV_HWDEVICE_TYPE_D3D11VA,       // WINDOWS DIRECTX 11
            AV_HWDEVICE_TYPE_D3D12VA,       // WINDOWS DIRECTX 12
            AV_HWDEVICE_TYPE_VIDEOTOOLBOX,  // MACOS
            AV_HWDEVICE_TYPE_VAAPI,         // LINUX VA-API
            AV_HWDEVICE_TYPE_VDPAU,         // LINUX VDPAU
            AV_HWDEVICE_TYPE_DRM,           // LINUX DRM
            AV_HWDEVICE_TYPE_MEDIACODEC,    // ANDROID MEDIACODEC
            // GENERIC GPU PATH, KEPT LAST ON PURPOSE AS THE LAST RESORT
            AV_HWDEVICE_TYPE_VULKAN         // VULKAN
    };

    // NATIVE TLS — THE CA BUNDLE IS PUBLISHED WHEN FFMPEG STARTS AND KEPT FOR THE LIFE OF THE PROCESS
    private static volatile Certificates certificates;

    private record Certificates(String file, String protocols) {}

    // FFMPEG COMPONENTS
    private AVFormatContext formatContext;
    private AVFormatContext trackFormatContext;
    private AVCodecContext videoCodecContext;
    private AVCodecContext audioCodecContext;
    private SwsContext swsContext;        // FORMAT CONVERSION AND/OR maxSize/LOD DOWNSCALE
    private SwrContext swrContext;

    // HW
    private volatile AVBufferRef hwDeviceCtx;
    private int hwPixelFormat = AV_PIX_FMT_NONE;
    private int swsInputFormat = AV_PIX_FMT_NONE;
    private int swsInputWidth;
    private int swsInputHeight;
    private Colorimetry swsColor; // COLORIMETRY THE CURRENT CONTEXT CONVERTS TO BGRA WITH, null UNTIL SET

    // STREAM INDEXES
    private int videoStreamIndex = -1;
    private int audioStreamIndex = -1;
    private boolean useAudioTrack = false;

    // THREADS — lifecycleLock SERIALIZES start()/stop()/release() AGAINST EACH OTHER; IT IS NEVER
    // HELD ACROSS A JOIN, SO A LIFECYCLE PUBLISHING ITS LAST STATUS CANNOT DEADLOCK A RELEASE
    private final String group;
    private final Object lifecycleLock = new Object();
    private final Object decodeLock = new Object();
    private final Object releaseLock = new Object();
    private volatile Thread lifecycleThread;
    private volatile Thread demuxThread;
    private volatile Thread videoDecodeThread;
    private volatile Thread audioDecodeThread;

    // QUEUES — VOLATILE: buffered() READS THEM FROM THE GAME/RENDER THREAD WHILE THE
    // LIFECYCLE THREAD CREATES AND FREES THEM (THE QUEUES THEMSELVES ARE SYNCHRONIZED)
    private volatile PacketQueue videoPacketQueue;
    private volatile PacketQueue audioPacketQueue;
    private volatile FrameQueue videoFrameQueue;
    private volatile FrameQueue audioFrameQueue;

    // STATUS + SYNCHRONIZATION
    private final MasterClock clock = new MasterClock();
    private volatile boolean qualityRequest = false;
    private volatile boolean ioAbortRequested = false;
    private volatile boolean released = false; // ONCE release() RUNS THE ENGINES ARE FREED — REJECT NEW start()s
    private volatile boolean releaseDone; // SET ONCE THE ENGINES ARE FREED, SO A REPEATED release() IS A NO-OP
    private volatile boolean releasing; // A release() IS JOINING THE PIPELINE: STATUS DELIVERY WAITS FOR IT (SEE holdStatus)
    private volatile boolean playable; // THE SOURCE OPENED AT LEAST ONCE AND NOTHING FAILED SINCE — canPlay()
    private volatile float fps; // 0 UNTIL THE VIDEO STREAM REPORTS A PLAUSIBLE RATE; AUDIO-ONLY STAYS 0
    private volatile long displayTimeMs; // HOW LONG A SINGLE-FRAME SOURCE STAYS UP BEFORE ENDED (0 = FOREVER)
    private volatile int runSerial; // BUMPED PER lifecycle() RUN SO ASYNC PROBES CANNOT WRITE INTO A LATER RUN
    private volatile Boolean hlsLiveSource;
    private Callback_Pointer interruptCallback;

    // NATIVE-FREE SNAPSHOTS FOR PUBLIC GETTERS
    // duration()/live()/canPlay() ARE CALLED FROM THE GAME/RENDER THREAD WHILE
    // THE LIFECYCLE THREAD MAY BE FREEING formatContext IN cleanup() — READING THE
    // NATIVE STRUCT THERE IS A USE-AFTER-FREE (JVM CRASH). CACHE THE VALUES INSTEAD.
    private volatile boolean opened;
    private volatile long mediaDurationMs = NO_DURATION;

    // SWS DESTINATION FRAME (LAZY-INIT — ONLY WHEN sws_scale IS NEEDED)
    private AVFrame scaledFrame;

    // ROTATING PLANE BUFFER POOL FOR UPLOADS
    // FRAME DATA FROM FrameQueue IS EPHEMERAL — RECYCLED AFTER next() — SO PLANE
    // DATA IS COPIED HERE BEFORE PASSING TO gfx.upload(). THE UPLOAD MAY BE
    // DISPATCHED ASYNC TO THE RENDER THREAD, AND THE CONSUMPTION LOOP CAN UPLOAD
    // SEVERAL FRAMES (~1ms APART) BEFORE THE RENDER THREAD RUNS THE QUEUED TASKS;
    // A SINGLE BUFFER SET WOULD BE OVERWRITTEN MID-READ (TORN FRAMES). ROTATING
    // SETS COVER THE REALISTIC BURST DEPTH (POST-HITCH CATCH-UP ≈ skipThreshold).
    private static final int PLANE_POOL_SETS = 4;
    private final ByteBuffer[][] planePool = new ByteBuffer[PLANE_POOL_SETS][];
    private int planePoolIdx;
    // BASE-ADDRESS ALIGNMENT THE GFXENGINE WANTS FOR ZERO-COPY HOST IMPORT (-1 = NOT YET QUERIED, 0 = NONE).
    // WHEN > 0 THE POOLED BUFFERS ARE PAGE-ALIGNED VIA MemoryUtil AND MUST BE FREED EXPLICITLY.
    private int planeAlign = -1;

    // TIMES
    private double videoTimeBase;
    private double audioTimeBase;

    // STATS
    private long totalSkippedFrames = 0;
    private long totalRenderedFrames = 0;

    // PTS DISCONTINUITY HANDLING (HLS AD STITCHING)
    // TWITCH AND SIMILAR SERVICES INSERT ADS VIA #EXT-X-DISCONTINUITY.
    // FFMPEG'S HLS DEMUXER DOESN'T ALWAYS NORMALIZE PTS ACROSS THESE
    // BOUNDARIES — RAW PTS CAN JUMP BY HOURS (STREAM ELAPSED TIME).
    // WE TRACK AN OFFSET PER STREAM TO KEEP PTS CONTINUOUS.
    private static final double PTS_DISCONTINUITY_SEC = 10.0;
    private volatile double videoPtsOffset;
    private volatile double lastRawVideoPts = Double.NaN;
    private volatile double audioPtsOffset;
    private volatile double lastRawAudioPts = Double.NaN;

    // FORMAT CHANGE DETECTION (RENDER THREAD ONLY)
    private int lastFrameWidth;
    private int lastFrameHeight;
    private PixelFormat lastFormat;
    private int lastBitsPerComponent;
    private Colorimetry lastColor;
    // TAGS, FORMAT AND SIZE OF THE LAST MAPPED FRAME: THE COLOR MAPPING RE-RUNS ONLY WHEN ONE OF THEM CHANGES
    private long colorTags = -1L;
    private int colorWidth;
    private int colorHeight;
    private Colorimetry frameColor;
    private boolean uploadRefused; // THE ENGINE REFUSED THE LAST LAYOUT: FRAMES DROP UNTIL IT CHANGES
    // FFTool.Pixels CACHE (CONSUME THREAD ONLY): THE STREAM PIXEL FORMAT IS STABLE, SO MAP ONCE PER
    // FORMAT CHANGE INSTEAD OF ALLOCATING A MAPPING EVERY FRAME IN THE UPLOAD HOT PATH.
    private int cachedUploadFmt = Integer.MIN_VALUE;
    private FFTool.Pixels cachedUploadMapping;

    // AUDIO FORMAT NEGOTIATION
    private boolean audioPassthrough;
    private int audioOutputChannels;
    private int audioOutputSampleRate;
    private int audioOutputAvFormat;   // FFmpeg AV_SAMPLE_FMT_* for the resample output

    // AUDIO INPUT PARAMETERS AS NEGOTIATED — COMPARED PER-FRAME TO DETECT MID-STREAM
    // CHANGES (CHAINED OGG / ICECAST CAN SWITCH SAMPLE RATE OR LAYOUT MID-STREAM;
    // FEEDING THE OLD RESAMPLER PLAYS AUDIO AT THE WRONG SPEED).
    private int audioInputSampleRate;
    private int audioInputAvFormat = AV_SAMPLE_FMT_NONE;
    private int audioInputChannels;

    // AUDIO DECODE CONTINUITY (DECODE THREAD ONLY) — END PTS OF THE LAST ENQUEUED
    // AUDIO FRAME, USED FOR RESAMPLER-FLUSH PTS AND AS NOPTS FALLBACK.
    private double audioNextPtsSec = Double.NaN;

    // SERIAL OF THE LAST FRAME UPLOADED TO THE SFX ENGINE — WHEN IT CHANGES (SEEK,
    // QUALITY SWITCH) THE ENGINE QUEUE STILL HOLDS PRE-SEEK AUDIO AND MUST BE FLUSHED.
    private int lastAudioUploadSerial = -1;

    // ADAPTIVE FRAME DROPPING
    private double renderDebtSec;

    // PERFORMANCE COUNTERS
    private long perfLastCheckMs;
    private long perfLastClockMs;
    private double perfLastSpeed = 1.0;
    private int perfAudioUploads;
    private int perfVideoRenders;

    // ANOMALY LATCHES: EACH REPEATABLE ANOMALY IS LOGGED ONCE PER PLAYER
    private boolean rateWarned;
    private boolean refusedWarned;
    private boolean starvationWarned;
    private boolean slotWarned;
    private boolean audioUnusableWarned;
    private boolean audioChangeWarned;

    // NOTHING IS OPENED UNTIL start(); THE HEAVY FFMPEG SETUP RUNS ON THE INTERNAL THREADS SPAWNED THEN.
    // A NULL gfx MEANS AUDIO-ONLY AND A NULL sfx VIDEO-ONLY PLAYBACK
    FFMediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfx, final SFXEngine sfx) {
        this(mrl, sourceIndex, gfx, sfx, null);
    }

    // A NON-NULL bridge FOLLOWS A SERVER-SIDE AUTHORITY (SEE MediaPlayer)
    FFMediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfx, final SFXEngine sfx, final Bridge bridge) {
        super(mrl, sourceIndex, gfx, sfx, bridge);
        synchronized (THREADS) {
            this.group = "FFThread-" + players++;
        }
        // EVERY CLOCK TRANSITION (INCLUDING THE ONES THE PIPELINE MAKES INTERNALLY) REACHES THE LISTENERS, HELD WHILE A RELEASE
        // JOINS THE PIPELINE (SEE holdStatus)
        this.clock.listener((previous, current) -> {
            this.queueStatus(previous, current);
            this.deliverStatus();
        });
        this.arm();
    }

    // MEDIAPLAYER OVERRIDES
    // LAUNCHES A LIFECYCLE RUN, ABORTING THE ONE IN FLIGHT. THE PAUSE INTENT AND THE INITIAL SEEK TRAVEL
    // WITH THE RUN (THE CLOCK IS RESET BY IT); THE THREAD STARTS UNDER THE LOCK SO release() ALWAYS JOINS IT
    @Override
    boolean launch(final boolean paused, final long seekMs) {
        synchronized (this.lifecycleLock) {
            // release() FREES gfx/sfx — REBUILDING THE PIPELINE ON DEAD ENGINES WOULD CRASH ON UPLOAD
            if (this.released) {
                LOGGER.warn(IT, "start() ignored — player already released");
                return false;
            }
            LOGGER.debug(IT, "Start requested (quality={}, paused={}, at={}ms)", this.quality, paused, seekMs);
            this.exception = null;
            final Thread oldThread = this.lifecycleThread;
            // A RUN THAT ALREADY ENDED OR FAILED IS ONLY TEARING DOWN: THE NEW RUN JOINS IT INSTEAD OF ABORTING IT, SO NO
            // STRAY STOPPED CONSUMES THE LISTENERS A TERMINAL CALLBACK JUST REGISTERED
            final Status current = this.clock.status();
            if (oldThread != null && oldThread.isAlive() && !oldThread.isInterrupted() && current != Status.ENDED && current != Status.ERROR)
                this.abort();
            final Thread thread = this.thread("lifecycle", () -> {
                // join RETURNS false WHEN THIS THREAD IS INTERRUPTED WHILE WAITING — THE RESTART WAS
                // DROPPED, SO LEAVE A CLEAN TERMINAL STATE INSTEAD OF THE PREVIOUS RUN'S STALE STATUS
                if (oldThread != null && !ThreadTool.join(oldThread)) {
                    LOGGER.warn(IT, "Restart aborted — interrupted while joining the previous lifecycle");
                    this.publishTransition(Status.STOPPED);
                    return;
                }
                // THE PREVIOUS RUN MAY HAVE RECORDED A FAILURE WHILE IT TORE DOWN; IT IS GONE NOW, SO ITS FAILURE IS TOO
                this.exception = null;
                this.lifecycle(paused, seekMs);
            });
            this.lifecycleThread = thread;
            thread.start();
        }
        return true;
    }

    @Override
    public void release() {
        // FAIL FAST FROM THE PLAYER'S OWN THREADS BEFORE TOUCHING ANY STATE, SO THE HOST CAN RETRY LATER
        final Thread caller = Thread.currentThread();
        if (caller == this.lifecycleThread || caller == this.demuxThread || caller == this.videoDecodeThread || caller == this.audioDecodeThread)
            throw new IllegalStateException("Release the player from its host thread, not from a playback callback");
        // ONE RELEASE AT A TIME: A CONCURRENT SECOND CALL WAITS AND THEN FINDS THE WORK DONE. STOPPED AND WHATEVER THE PIPELINE
        // PUBLISHED WHILE IT WAS JOINED ARE HEARD AFTER THE LOCK, SO A LISTENER CAN WAIT FOR A THREAD THAT CALLS INTO THIS PLAYER
        try {
            synchronized (this.releaseLock) {
                this.releasing = true;
                this.disarm();
                final Thread lifecycle;
                synchronized (this.lifecycleLock) {
                    if (this.releaseDone) return;
                    LOGGER.debug(IT, "Release requested");
                    this.released = true; // GATE OUT ANY LATER start() — THE ENGINES ARE ABOUT TO BE FREED
                    // TEAR DOWN DIRECTLY, NEVER THROUGH stop(): ON A BRIDGED PLAYER THAT WOULD ASK THE SYNC
                    // AUTHORITY TO STOP THE MEDIA FOR EVERY FOLLOWER JUST BECAUSE ONE CLIENT WENT AWAY.
                    this.abort();
                    lifecycle = this.lifecycleThread;
                }
                // WAIT FOR THE PIPELINE TO EXIT BEFORE FREEING ENGINES — THE CONSUMPTION LOOP KEEPS TOUCHING sfx/gfx
                // AND NATIVE CONTEXTS UNTIL IT FINISHES; ioAbortRequested (SET BY abort()) MAKES NATIVE I/O RETURN FAST
                if (lifecycle != null) ThreadTool.join(lifecycle);
                // RETIRE EACH GPU IMPORT BEFORE FREEING ITS HOST BUFFER, WHILE THE GRAPHICS ENGINE IS LIVE.
                // AUDIO ADAPTER CLEANUP MAY THROW, SO THE DECODED PLANE POOL MUST ALREADY BE RELEASED.
                this.freePlanePool();
                this.playable = false;
                this.clock.halt(); // EVERY PLAYER LANDS STOPPED ON RELEASE, EVEN FROM ERROR
                this.releaseDone = true;
                super.release();
            }
        } finally {
            this.releasing = false;
            this.clock.drain();
            this.deliverStatus();
        }
    }

    @Override
    boolean holdStatus() { return this.releasing; }

    // FREES THE ROTATING PLANE POOL. PAGE-ALIGNED BUFFERS (memAlignedAlloc, FOR ZERO-COPY VULKAN IMPORT) ARE
    // NOT GC-MANAGED AND MUST BE FREED EXPLICITLY; allocateDirect SETS ARE LEFT TO THE GC.
    private void freePlanePool() {
        if (this.planeAlign > 0) {
            for (final ByteBuffer[] set: this.planePool) {
                if (set == null) continue;
                for (int i = 0; i < set.length; i++) {
                    if (set[i] != null) {
                        this.gfx.release(set[i]); // DRAINS GPU READS AND DROPS THE IMPORT BEFORE FREEING HOST MEMORY
                        MemoryUtil.memAlignedFree(set[i]);
                    }
                    set[i] = null;
                }
            }
        }
        Arrays.fill(this.planePool, null);
        this.planePoolIdx = 0;
        this.planeAlign = -1;
    }

    @Override
    public boolean pause(final boolean paused) {
        if (!super.pause(paused)) return false;
        final boolean changed = this.clock.paused(paused);
        // ALSO PAUSE/RESUME THE AUDIO ENGINE — WITHOUT THIS, OPENAL KEEPS PLAYING
        // ITS QUEUED BUFFERS UNTIL IT UNDERRUNS AND STOPS, AND THE NEXT RESUME
        // STARTS FROM A STOPPED SOURCE (AUDIBLE GAP) INSTEAD OF A PAUSED ONE.
        // ONLY ON A REAL TRANSITION: play() ON A TERMINAL STATE (ENDED/STOPPED)
        // WOULD REPLAY THE RESIDUAL OPENAL QUEUE.
        if (changed && this.sfx != null) {
            if (paused) this.sfx.pause();
            else this.sfx.play();
        }
        if (changed) LOGGER.debug(IT, "Pause -> {}", paused);
        return changed;
    }

    @Override
    public boolean stop() {
        if (!super.stop()) return false;
        synchronized (this.lifecycleLock) {
            final Thread lifecycle = this.lifecycleThread;
            if (lifecycle != null && lifecycle.isAlive() && !lifecycle.isInterrupted()) return this.abort();
        }
        // AN ENDED OR FAILED PIPELINE IS ALREADY GONE: stop() ONLY MOVES THE STATUS AND REWINDS THE CLOCK.
        // OUTSIDE THE LOCK BECAUSE THE TRANSITION NOTIFIES THE LISTENER; A RESTART RACING IT RE-CHECKS LOADING
        final Status status = this.clock.status();
        return (status == Status.ENDED || status == Status.ERROR) && this.clock.transition(Status.STOPPED);
    }

    // INTERRUPTS THE PIPELINE AND ABORTS BLOCKING NATIVE I/O. SHARED BY stop(), THE RESTART PATH IN
    // launch() AND release() — THE LATTER TWO ARE LOCAL TEARDOWNS THAT MUST NOT REACH THE AUTHORITY.
    private boolean abort() {
        final Thread lifecycle = this.lifecycleThread;
        if (lifecycle != null) {
            LOGGER.debug(IT, "Stop requested (status={}, clock={}ms)", this.clock.status(), this.clock.timeMs());
            this.ioAbortRequested = true; // ABORT BLOCKING NATIVE I/O (OPEN/READ) ASAP
            lifecycle.interrupt();
            final Thread demux = this.demuxThread;
            if (demux != null) demux.interrupt();
            return true;
        }
        return false;
    }

    @Override
    public boolean togglePlay() {
        if (!super.togglePlay()) return false;
        return switch (this.clock.status()) {
            case ENDED, STOPPED -> this.launch(false, -1L);
            case WAITING, ERROR -> false;
            default -> this.pause(!this.clock.pauseRequested());
        };
    }

    @Override
    public boolean seek(final long timeMs) {
        return super.seek(timeMs) && this.requestClampedSeek(timeMs, true);
    }

    @Override
    public boolean seekQuick(final long timeMs) {
        return super.seekQuick(timeMs) && this.requestClampedSeek(timeMs, false);
    }

    // CLAMP ONLY AGAINST A KNOWN DURATION — duration() RETURNS NO_DURATION (0)
    // FOR UNPROBED/UNKNOWN MEDIA, AND CLAMPING AGAINST THAT SENDS EVERY SEEK TO 0.
    private boolean requestClampedSeek(final long timeMs, final boolean precise) {
        if (!this.canSeek()) {
            LOGGER.debug(IT, "Seek to {}ms ignored — source is not seekable", timeMs);
            return false;
        }
        final long duration = this.duration();
        final long target = Math.max(0, duration > 0 ? Math.min(timeMs, duration) : timeMs);
        LOGGER.debug(IT, "Seek requested -> {}ms (precise={}, clamped from {}ms)", target, precise, timeMs);
        // A FINISHED OR STOPPED PIPELINE IS GONE: SCRUBBING IT MEANS A PAUSED RESTART AT THE TARGET
        final Status status = this.clock.status();
        if (status == Status.ENDED || status == Status.STOPPED) return this.launch(true, target);
        return this.clock.requestSeek(target, precise);
    }

    // FRAME STEPPING IS A PAUSED-ONLY CONTROL ON EVERY PLAYER
    @Override
    public boolean previousFrame() {
        if (!super.previousFrame() || !this.paused() || !this.canSeek()) return false;
        return this.clock.requestSeek(Math.max(0, this.time() - this.clock.frameDurationMs()), true);
    }

    @Override
    public boolean nextFrame() {
        if (!super.nextFrame() || !this.paused() || !this.canSeek()) return false;
        final long duration = this.duration();
        final long target = this.time() + this.clock.frameDurationMs();
        return this.clock.requestSeek(duration > 0 ? Math.min(target, duration) : target, true);
    }

    @Override
    public float fps() { return this.fps; }

    @Override
    public Status status() { return this.clock.status(); }

    @Override
    public void displayTime(final long ms) { this.displayTimeMs = ms; }

    @Override
    public long displayTime() { return this.displayTimeMs; }

    // TERMINAL TRANSITION: THE CLOCK NOTIFIES THE LISTENER ITSELF (SEE THE CONSTRUCTOR); A FAILURE
    // ALSO ENDS THE MEDIA'S PLAYABLE LIFE UNTIL THE NEXT start()
    private void publishTransition(final Status next) {
        if (next == Status.ERROR) this.playable = false;
        this.clock.transition(next);
    }

    @Override
    public boolean live() {
        final Boolean hls = this.hlsLiveSource;
        if (hls != null) return hls;
        return this.opened && this.mediaDurationMs == NO_DURATION;
    }

    @Override
    public boolean canSeek() { return this.playable && !this.live(); }

    @Override
    public boolean canPlay() { return this.playable; }

    @Override
    public long duration() { return this.live() ? NO_DURATION : this.mediaDurationMs; }

    @Override
    public int buffered() {
        // AFTER DEMUX EOF THERE IS NOTHING LEFT TO READ AHEAD, AND A PIPELINE THAT IS PLAYING OR
        // PAUSED IS KEEPING UP — ONLY LOADING/BUFFERING REPORT THE REAL QUEUE FILL
        final Status status = this.clock.status();
        if (this.opened && (this.clock.demuxFinished() || status == Status.PLAYING || status == Status.PAUSED)) return 100;
        final PacketQueue video = this.videoPacketQueue;
        final PacketQueue audio = this.audioPacketQueue;
        long sum = 0;
        int parts = 0;
        if (video != null && this.videoStreamIndex >= 0) {
            sum += Math.min(100, video.byteSize() * 100 / VIDEO_PACKET_QUEUE_BYTES);
            parts++;
        }
        if (audio != null && this.audioStreamIndex >= 0) {
            sum += Math.min(100, audio.byteSize() * 100 / AUDIO_PACKET_QUEUE_BYTES);
            parts++;
        }
        return parts == 0 ? 0 : (int) (sum / parts);
    }

    @Override
    public void quality(final MediaQuality quality) {
        super.quality(quality);
        final Thread lifecycle = this.lifecycleThread;
        if (lifecycle != null && lifecycle.isAlive() && !lifecycle.isInterrupted()) {
            this.qualityRequest = true;
            LOGGER.debug(IT, "Quality switch requested -> {}", quality);
        }
    }

    @Override
    public long time() { return this.clock.timeMs(); }

    @Override
    public boolean speed(final float speed) {
        if (super.speed(speed)) {
            this.clock.speed(speed);
            LOGGER.debug(IT, "Speed -> {}x", speed);
            return true;
        }
        return false;
    }

    /**
     * Reports whether the active video pipeline is decoding on the GPU.
     * @return {@code true} while a hardware decoder is in use, {@code false} for software decoding
     */
    public boolean hwAccel() { return !FFTool.isNull(this.hwDeviceCtx); }

    // MAPS AN FFMPEG SAMPLE FORMAT (PACKED OR PLANAR) TO THE CANONICAL SFXEngine.SampleType.
    // PLANARNESS IS HANDLED SEPARATELY — THE CALLER CHECKS av_sample_fmt_is_planar DIRECTLY.
    // RETURNS null IF THE FFMPEG FORMAT HAS NO PCM EQUIVALENT.
    private static SFXEngine.SampleType toSampleType(final int avSampleFormat) {
        if (avSampleFormat == AV_SAMPLE_FMT_U8  || avSampleFormat == AV_SAMPLE_FMT_U8P)  return SFXEngine.SampleType.U8;
        if (avSampleFormat == AV_SAMPLE_FMT_S16 || avSampleFormat == AV_SAMPLE_FMT_S16P) return SFXEngine.SampleType.S16;
        if (avSampleFormat == AV_SAMPLE_FMT_S32 || avSampleFormat == AV_SAMPLE_FMT_S32P) return SFXEngine.SampleType.S32;
        if (avSampleFormat == AV_SAMPLE_FMT_FLT || avSampleFormat == AV_SAMPLE_FMT_FLTP) return SFXEngine.SampleType.FLT;
        if (avSampleFormat == AV_SAMPLE_FMT_DBL || avSampleFormat == AV_SAMPLE_FMT_DBLP) return SFXEngine.SampleType.DBL;
        return null;
    }

    // INVERSE OF toSampleType — MAPS CANONICAL SampleType BACK TO AN FFMPEG PACKED AV_SAMPLE_FMT_*
    // USED TO CONFIGURE swr's out_sample_fmt WHEN RESAMPLING TO A CHOSEN TARGET TYPE.
    private static int sampleTypeToAvFormat(final SFXEngine.SampleType type) {
        return switch (type) {
            case U8  -> avutil.AV_SAMPLE_FMT_U8;
            case S16 -> avutil.AV_SAMPLE_FMT_S16;
            case S32 -> avutil.AV_SAMPLE_FMT_S32;
            case FLT -> avutil.AV_SAMPLE_FMT_FLT;
            case DBL -> avutil.AV_SAMPLE_FMT_DBL;
        };
    }

    // PLAUSIBLE REAL-WORLD VIDEO FRAME RATE. REJECTS 0/NaN/INFINITY AND TIMEBASE
    // ARTIFACTS LIKE THE 90kHz MPEG-TS PCR CLOCK (90000), WHICH WOULD OTHERWISE
    // DRIVE frameDuration TO 0ms AND BREAK PACING. 1000fps IS WELL ABOVE ANY REAL
    // CONTENT WHILE STILL EXCLUDING CLOCK-RATE VALUES.
    private static boolean isPlausibleFps(final double fps) {
        return fps >= 1.0 && fps <= 1000.0;
    }

    // PICKS A FALLBACK TYPE SUPPORTED AT entry, WALKING THE ENGINE'S PREFERENCE ORDER.
    // PREFERS S16 (UNIVERSAL) IF AVAILABLE.
    private static SFXEngine.SampleType fallbackType(final SFXEngine.SampleType[] types, final SFXEngine.ChannelSupport entry) {
        if (entry.supports(SFXEngine.SampleType.S16)) return SFXEngine.SampleType.S16;
        for (final SFXEngine.SampleType type: types) {
            if (entry.supports(type)) return type;
        }
        return null;
    }

    // MAPS THE COLOR TAGS OF A FRAME OR STREAM; WHAT IT LEAVES UNSPECIFIED FALLS BACK TO Colorimetry.guess. GRAY OF ANY DEPTH
    // (ALPHA INCLUDED) AND THE LEGACY YUVJ LAYOUTS DEFAULT TO FULL RANGE, AS SWSCALE TREATS THEM
    private static Colorimetry colorimetry(final int avFormat, final int width, final int height, final int space,
                                           final int range, final int location, final int transfer, final int primaries) {
        final AVPixFmtDescriptor desc = av_pix_fmt_desc_get(avFormat);
        final boolean gray = !FFTool.isNull(desc) && desc.nb_components() > 0 && desc.nb_components() <= 2
                && (desc.flags() & (AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_PAL | AV_PIX_FMT_FLAG_HWACCEL)) == 0;
        final Colorimetry guess = Colorimetry.guess(gray ? PixelFormat.GRAY : null, width, height);
        final Colorimetry.Matrix matrix = switch (space) {
            case AVCOL_SPC_BT709, AVCOL_SPC_SMPTE240M -> Colorimetry.Matrix.BT709;
            case AVCOL_SPC_BT470BG, AVCOL_SPC_SMPTE170M, AVCOL_SPC_FCC -> Colorimetry.Matrix.BT601;
            case AVCOL_SPC_BT2020_NCL, AVCOL_SPC_BT2020_CL -> Colorimetry.Matrix.BT2020;
            default -> guess.matrix();
        };
        final boolean jpeg = avFormat == AV_PIX_FMT_YUVJ420P || avFormat == AV_PIX_FMT_YUVJ422P || avFormat == AV_PIX_FMT_YUVJ444P
                || avFormat == AV_PIX_FMT_YUVJ411P || avFormat == AV_PIX_FMT_YUVJ440P;
        final boolean full = range == AVCOL_RANGE_JPEG || range != AVCOL_RANGE_MPEG && (jpeg || guess.full());
        final boolean tagged = location != AVCHROMA_LOC_UNSPECIFIED;
        final boolean cositedX = tagged
                ? location == AVCHROMA_LOC_LEFT || location == AVCHROMA_LOC_TOPLEFT || location == AVCHROMA_LOC_BOTTOMLEFT
                : guess.cositedX();
        final boolean cositedY = tagged ? location == AVCHROMA_LOC_TOPLEFT || location == AVCHROMA_LOC_TOP : guess.cositedY();
        final Colorimetry.Transfer curve = transfer == AVCOL_TRC_SMPTE2084 ? Colorimetry.Transfer.PQ
                : transfer == AVCOL_TRC_ARIB_STD_B67 ? Colorimetry.Transfer.HLG : Colorimetry.Transfer.SDR;
        final boolean wide = primaries == AVCOL_PRI_BT2020 || primaries == AVCOL_PRI_UNSPECIFIED && matrix == Colorimetry.Matrix.BT2020;
        return new Colorimetry(matrix, full, cositedX, cositedY, curve, wide);
    }

    // COPIES AVFRAME PLANE DATA TO A POOLED BUFFER SET, THEN UPLOADS TO GFXENGINE. THE COPY IS NECESSARY BECAUSE GLENGINE MAY DISPATCH THE UPLOAD TO THE RENDER THREAD ASYNCHRONOUSLY, BUT THE AVFRAME DATA IS RECYCLED BY FRAMEQUEUE.NEXT(). STRIDES ARE PASSED IN BYTES (FFMPEG LINESIZE CONVENTION).
    private void uploadNativePlanes(final AVFrame frame, final PixelFormat cs, final int height) {
        // QUERY THE ENGINE'S BUFFER ALIGNMENT ONCE — NON-ZERO ASKS FOR PAGE-ALIGNED POOLS SO A ZERO-COPY ENGINE CAN IMPORT THE HOST POINTER
        if (this.gfx == null) return;
        if (this.planeAlign < 0) this.planeAlign = this.gfx.alignment();

        // GRAB THE NEXT BUFFER SET FROM THE ROTATING POOL (SEE planePool), ALLOCATING ON FIRST USE
        ByteBuffer[] planes = this.planePool[this.planePoolIdx];
        if (planes == null) {
            planes = new ByteBuffer[4];
            this.planePool[this.planePoolIdx] = planes;
        }
        this.planePoolIdx = (this.planePoolIdx + 1) % PLANE_POOL_SETS;
        // EACH PLANE COPIES ITS linesize TIMES ITS ROWS; PixelFormat ROUNDS SUBSAMPLED ROWS UP LIKE FFMPEG ALLOCATES THEM
        final ByteBuffer[] bufs = new ByteBuffer[cs.planes()];
        final int[] strides = new int[bufs.length];
        for (int i = 0; i < bufs.length; i++) {
            strides[i] = frame.linesize(i);
            final long size = (long) strides[i] * cs.planeHeight(i, height);
            planes[i] = this.ensurePlane(planes[i], size);
            copyPlane(frame.data(i), size, planes[i]);
            bufs[i] = planes[i];
        }
        this.gfx.upload(bufs, strides);
    }

    private ByteBuffer ensurePlane(final ByteBuffer buf, final long needed) {
        if (buf != null && buf.capacity() >= needed) return buf;
        if (this.planeAlign > 0) {
            // PAGE-ALIGNED DIRECT BUFFER SO A VULKAN ENGINE CAN IMPORT THE HOST POINTER (VK_EXT_external_memory_host).
            // ROUND THE SIZE UP TO THE ALIGNMENT — THE IMPORT REQUIRES BOTH BASE AND SIZE TO BE MULTIPLES OF IT.
            if (buf != null) {
                // DROP THE ENGINE'S IMPORT OF THE OLD POINTER FIRST — FREEING HOST MEMORY UNDER A
                // LIVE IMPORT (OR RECYCLING ITS ADDRESS) CRASHES THE DRIVER ON THE LATER vkFreeMemory.
                this.gfx.release(buf);
                MemoryUtil.memAlignedFree(buf);
            }
            final long rounded = (needed + this.planeAlign - 1) / this.planeAlign * this.planeAlign;
            return MemoryUtil.memAlignedAlloc(this.planeAlign, (int) rounded);
        }
        return ByteBuffer.allocateDirect((int) needed);
    }

    private static void copyPlane(final BytePointer src, final long bytes, final ByteBuffer dst) {
        // DIRECT NATIVE-TO-NATIVE COPY — AVOIDS ALLOCATING A WRAPPER ByteBuffer PER PLANE PER FRAME
        // (asByteBuffer). CLEAR FIRST SO memAddress RESOLVES TO THE BUFFER BASE (POSITION 0).
        dst.clear();
        MemoryUtil.memCopy(src.address() + src.position(), MemoryUtil.memAddress(dst), bytes);
        dst.limit((int) bytes);
    }

    // LAZY-INIT SWS RESOURCES FOR FORMAT CONVERSION AND/OR DOWNSCALE. RETURNS FALSE IF INIT FAILS.
    private boolean ensureScaler(final int srcWidth, final int srcHeight, final int srcFormat,
                                 final int dstWidth, final int dstHeight, final int dstFormat, final Colorimetry color) {
        // ALLOCATE THE DESTINATION FRAME ON FIRST USE (OR TARGET CHANGE).
        // TRACK THE REALLOCATION — COMPARING scaledFrame PROPERTIES BELOW WOULD
        // ALWAYS MATCH AFTER THE REALLOC, LEAVING A STALE SWS CONTEXT CONFIGURED
        // FOR THE OLD TARGET (HEAP CORRUPTION IN sws_scale).
        boolean frameRecreated = false;
        if (this.scaledFrame == null
                || this.scaledFrame.width() != dstWidth
                || this.scaledFrame.height() != dstHeight
                || this.scaledFrame.format() != dstFormat) {
            if (this.scaledFrame != null) avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = avutil.av_frame_alloc();
            this.scaledFrame.format(dstFormat);
            this.scaledFrame.width(dstWidth);
            this.scaledFrame.height(dstHeight);
            if (avutil.av_frame_get_buffer(this.scaledFrame, 32) < 0) {
                LOGGER.error(IT, "Failed to allocate scaled frame buffer");
                avutil.av_frame_free(this.scaledFrame);
                this.scaledFrame = null;
                return false;
            }
            frameRecreated = true;
        }

        // (RE)CREATE SWS CONTEXT ON SOURCE FORMAT/DIMENSION CHANGE
        if (this.swsContext == null || this.swsInputFormat != srcFormat
                || this.swsInputWidth != srcWidth || this.swsInputHeight != srcHeight || frameRecreated) {
            this.recreateSwsContext(srcWidth, srcHeight, srcFormat, dstWidth, dstHeight, dstFormat);
        }

        // BGRA OUTPUT NEEDS THE SOURCE MATRIX AND RANGE, OR SWS CONVERTS EVERY YUV FRAME AS BT.601; IT CANNOT TONE MAP, SO
        // HDR REACHES SOFTWARE ENGINES UNMAPPED. YUV OUTPUT KEEPS ITS SAMPLES AND THE ENGINE CONVERTS THEM
        if (this.swsContext != null && dstFormat == AV_PIX_FMT_BGRA && !color.equals(this.swsColor)) {
            final IntPointer coefficients = swscale.sws_getCoefficients(switch (color.matrix()) {
                case BT601 -> swscale.SWS_CS_ITU601;
                case BT709 -> swscale.SWS_CS_ITU709;
                case BT2020 -> swscale.SWS_CS_BT2020;
            });
            final int details = swscale.sws_setColorspaceDetails(this.swsContext, coefficients, color.full() ? 1 : 0, coefficients, 1, 0, 1 << 16, 1 << 16);
            if (details < 0) LOGGER.warn(IT, "The scaler refused {} (code {}) and keeps its default colorimetry", color, details);
            this.swsColor = color;
        }
        return this.swsContext != null;
    }

    // EAGER AUDIO UPLOADS, LIFECYCLE LOOP ONLY (A SECOND CONSUMER RACES THE PIPELINE). THE ENGINE
    // POOL IS THE BACKPRESSURE AND THE CLOCK TRACKS THE AUDIBLE POSITION — A JUST-IN-TIME GATE
    // UNDERRUNS ON EVERY HICCUP; AUDIO_MAX_LEAD_SEC ONLY CAPS RUNAWAY PTS JUMPS.
    private boolean drainAudio(final Status current) {
        if (this.sfx == null || this.audioStreamIndex < 0 || this.audioFrameQueue == null) return false;
        boolean didWork = false;
        while (true) {
            final FrameQueue.Slot slot = this.audioFrameQueue.peek();
            if (slot == null) break;

            // SERIAL CHECK — DISCARD PRE-SEEK FRAMES
            if (slot.serial != this.clock.serial()) {
                this.audioFrameQueue.next();
                didWork = true;
                continue;
            }
            if (current != Status.BUFFERING) {
                final double lead = (slot.ptsMs / 1000.0) - this.clock.time();
                if (lead > AUDIO_MAX_LEAD_SEC) break;
            }

            final long framePtsMs = slot.ptsMs;
            final long frameDurationMs = slot.durationMs;
            final int frameSerial = slot.serial;

            final int bytesPerSample = av_get_bytes_per_sample(slot.frame.format());
            final int dataSize = slot.frame.nb_samples() * this.audioOutputChannels * bytesPerSample;
            final ByteBuffer audioData = slot.frame.data(0)
                    .limit(dataSize)
                    .asBuffer()
                    .clear();

            // SERIAL HANDOFF (SEEK/QUALITY SWITCH): DROP THE ENGINE'S QUEUED AUDIO FROM THE
            // PREVIOUS POSITION BEFORE THE FIRST POST-SEEK UPLOAD SO IT NEITHER FINISHES NOR REPLAYS.
            if (frameSerial != this.lastAudioUploadSerial) {
                this.sfx.flush();
                this.lastAudioUploadSerial = frameSerial;
            }

            // ENGINE FULL — ITS BUFFER POOL IS THE BACKPRESSURE; STOP UNTIL IT FREES SLOTS
            if (!this.sfx.upload(audioData)) {
                if (this.clock.status() == Status.BUFFERING) {
                    LOGGER.debug(IT, "drainAudio: SFX engine full during BUFFERING — can't upload PTS={}ms", framePtsMs);
                }
                // RE-ASSERT PLAY EVEN ON FAILURE: A STALE MAINTENANCE pause() RACING A RESUME WOULD
                // OTHERWISE DEADLOCK — A PAUSED ENGINE NEVER FREES BUFFERS, SO UPLOADS FAIL FOREVER
                if (!this.clock.pauseRequested()) this.sfx.play();
                break;
            }

            // DON'T START THE SOURCE WHEN A PAUSE IS REQUESTED — WARM-UP UPLOADS
            // DURING BUFFERING→PAUSED WOULD OTHERWISE LEAK A FEW MS OF AUDIO
            if (!this.clock.pauseRequested()) this.sfx.play();
            this.audioFrameQueue.next();
            this.perfAudioUploads++;

            // CLOCK = AUDIBLE POSITION: THE FRAME PTS ALONE WOULD RUN AHEAD BY THE ENGINE'S QUEUE
            // DEPTH, SO SUBTRACT THE PENDING (LATENCY-COMPENSATED) AUDIO FROM THIS FRAME'S END.
            final long audiblePtsMs = framePtsMs + frameDurationMs - this.sfx.pendingMs();

            final Status beforeUpdate = this.clock.status();
            this.clock.updateMs(audiblePtsMs, frameSerial);
            final Status afterUpdate = this.clock.status();
            if (beforeUpdate != afterUpdate) {
                LOGGER.debug(IT, "Clock update: {} → {} (PTS={}ms, audible={}ms, serial={}, clockSerial={})",
                        beforeUpdate, afterUpdate, framePtsMs, audiblePtsMs, frameSerial, this.clock.serial());
            }

            didWork = true;
        }
        return didWork;
    }

    // LIFECYCLE (SUPERVISOR THREAD). paused LATCHES THE startPaused() INTENT; seekMs >= 0 IS THE POSITION A
    // RESTART FROM ENDED/STOPPED RESUMES AT (PAUSED), -1 STARTS FROM THE BEGINNING
    private void lifecycle(final boolean paused, final long seekMs) {
        final long started = System.nanoTime();
        long slowCount = 0;
        long slowWorstMs = 0;
        long slowAudioMs = 0;
        long slowVideoMs = 0;
        long slowFrameMs = 0;
        try {
            this.runSerial++;
            this.ioAbortRequested = false; // ONE RESET PER RUN, BEFORE THE DEMUXER CAN OPEN ANYTHING
            this.clock.reset();
            // A stop() THAT RACED THIS RESTART ALREADY MOVED THE FRESH CLOCK TO STOPPED: THE USER WINS
            if (!this.clock.transition(Status.LOADING)) return;
            // THE PAUSE INTENT AND THE RESTART POSITION LATCH INTO LOADING; start() LANDS PAUSED AND, WITH A
            // PENDING SEEK, IN BUFFERING AT THE TARGET
            if (paused) this.clock.paused(true);
            if (seekMs >= 0) this.clock.requestSeek(seekMs, true);
            this.totalSkippedFrames = 0;
            this.totalRenderedFrames = 0;
            this.renderDebtSec = 0;
            this.qualityRequest = false; // this.quality IS ALREADY THE LATEST — A STALE SWITCH WOULD REBUILD THE FRESH PIPELINE
            // STARVATION DETECTION
            long starvationStartMs = 0;
            long lastStarvationRecoveryMs = Long.MIN_VALUE / 4; // "NEVER", FAR ENOUGH FROM ANY MONOTONIC STAMP
            this.videoPtsOffset = 0;
            this.lastRawVideoPts = Double.NaN;
            this.audioPtsOffset = 0;
            this.lastRawAudioPts = Double.NaN;
            this.audioNextPtsSec = Double.NaN;
            this.lastAudioUploadSerial = -1;
            this.hlsLiveSource = null;
            this.opened = false;
            this.fps = 0f;
            this.mediaDurationMs = NO_DURATION;

            this.videoPacketQueue = new PacketQueue(VIDEO_PACKET_QUEUE_BYTES);
            this.audioPacketQueue = new PacketQueue(AUDIO_PACKET_QUEUE_BYTES);
            this.videoFrameQueue = new FrameQueue(VIDEO_FRAME_QUEUE_SLOTS);
            this.audioFrameQueue = new FrameQueue(AUDIO_FRAME_QUEUE_SLOTS);

            this.demuxThread = this.thread("demux", this::demuxLoop);
            this.demuxThread.start();

            final Status postInit = this.clock.awaitNotStatus(Status.LOADING, 0);

            if (postInit == Status.ERROR) {
                return;
            }

            // THE DEMUXER MAY ALREADY HAVE STARTED THE DECODERS WHILE SERVING AN INITIAL SEEK — NEVER
            // RUN TWO DECODERS ON ONE CODEC CONTEXT
            this.ensureDecodeThreads();

            // CONSUMPTION LOOP
            while (!Thread.currentThread().isInterrupted()) {
                final Status current = this.clock.status();

                if (this.qualityRequest) {
                    this.qualityRequest = false;
                    this.switchQuality();
                    if (this.clock.status() == Status.ERROR) break;
                    continue;
                }

                if (this.clock.demuxFinished()) {
                    final boolean vDone = this.videoDecodeThread == null || !this.videoDecodeThread.isAlive();
                    final boolean aDone = this.audioDecodeThread == null || !this.audioDecodeThread.isAlive();
                    final boolean vEmpty = this.videoFrameQueue == null || this.videoFrameQueue.empty();
                    final boolean aEmpty = this.audioFrameQueue == null || this.audioFrameQueue.empty();
                    if (vDone && aDone && vEmpty && aEmpty) {
                        // A VIDEO STREAM THAT DRAINED WITHOUT EVER EMITTING A FRAME (NEITHER
                        // RENDERED NOR SKIPPED) IS A DECODE FAILURE, NOT A NORMAL END — E.G. AN
                        // AV1 STREAM THE SELECTED DECODER CANNOT HANDLE. REPORTING ENDED THERE
                        // LOOKS LIKE AN INSTANT 0-LENGTH PLAYBACK; SURFACE ERROR INSTEAD SO THE
                        // FAILURE IS VISIBLE AND NOT RETRIED FOREVER BY repeat().
                        if (this.videoStreamIndex >= 0 && this.totalRenderedFrames == 0 && this.totalSkippedFrames == 0) {
                            LOGGER.error(IT, "Video decoder emitted no frames for {} — failing instead of reporting ENDED", this.source.uri(this.quality));
                            this.exception(new IOException("Video decoder emitted no frames — unsupported or broken stream"));
                            this.publishTransition(Status.ERROR);
                            break;
                        }
                        // A STILL (ONE VIDEO FRAME, NO AUDIO) BEHAVES LIKE THE IMAGE PLAYER'S: SHOWN UNTIL displayTime
                        // ENDS IT, FOREVER WHEN UNTIMED, NEVER LOOPED (REPLAYING THE PIPELINE FOR THE SAME PIXELS BUYS NOTHING)
                        if (this.audioStreamIndex < 0 && this.totalRenderedFrames + this.totalSkippedFrames <= 1) {
                            this.hlsLiveSource = false; // A FULLY DRAINED FINITE SOURCE IS NEVER LIVE
                            final long shown = this.displayTimeMs;
                            if (shown > 0) {
                                this.mediaDurationMs = shown; // THE TIMED STILL EXPOSES ITS DISPLAY WINDOW AS DURATION
                                if (this.clock.timeMs() >= shown) {
                                    this.publishTransition(Status.ENDED);
                                    break;
                                }
                            }
                            this.clock.awaitChange(shown > 0 ? MathUtil.clamp(shown - this.clock.timeMs(), 1L, 500L) : 500L);
                            continue;
                        }
                        if (this.repeat()) {
                            // ALL DATA CONSUMED — SEEK TO BEGINNING FOR REPEAT.
                            // THIS TRIGGERS AFTER FULL DRAIN, NOT AT DEMUX EOF,
                            // SO NO UNPROCESSED PACKETS ARE THROWN AWAY.
                            this.clock.requestSeek(0, false);
                            continue;
                        }
                        this.publishTransition(Status.ENDED);
                        break;
                    }
                }

                if (current == Status.BUFFERING && this.demuxThread != null && !this.demuxThread.isAlive()) {
                    LOGGER.error(IT, "Demux thread died during BUFFERING — setting ERROR");
                    this.exception(new IOException("Demux thread died during BUFFERING"));
                    this.publishTransition(Status.ERROR);
                    break;
                }

                if (current == Status.PAUSED) {
                    // KEEP THE ENGINE PAUSED EVEN WHEN PAUSED WAS REACHED INTERNALLY
                    // (BUFFERING→PAUSED VIA pauseIntent) AND NOT THROUGH pause(true)
                    if (this.sfx != null) this.sfx.pause();
                    this.clock.awaitChange(50);
                    continue;
                }

                if (current == Status.STOPPED || current == Status.ERROR) {
                    break;
                }

                if (current != Status.PLAYING && current != Status.BUFFERING) {
                    break;
                }

                boolean didWork = false;

                // PLAYBACK RATE MONITOR
                final long nowMs = nowMs();
                if (current == Status.PLAYING && nowMs - this.perfLastCheckMs > PERF_CHECK_INTERVAL_MS && this.perfLastClockMs > 0) {
                    final long wallElapsed = nowMs - this.perfLastCheckMs;
                    final long clockElapsed = this.clock.timeMs() - this.perfLastClockMs;
                    final double speed = this.clock.speed();
                    // THE CLOCK ADVANCES AT speed TIMES WALL TIME BY DESIGN, SO THE DEVIATION IS MEASURED AGAINST THAT;
                    // A SPEED CHANGE INSIDE THE WINDOW MAKES THE SAMPLE MEANINGLESS, SO IT IS SKIPPED
                    final double rate = clockElapsed / (wallElapsed * speed);
                    if (!this.rateWarned && speed == this.perfLastSpeed && (rate < PERF_RATE_WARN_THRESHOLD || rate > (2.0 - PERF_RATE_WARN_THRESHOLD))) {
                        this.rateWarned = true;
                        LOGGER.warn(IT, "Playback rate {} (clock {}ms over {}ms at {}x, vQ={}, aQ={}, aUploads={}, vRenders={})",
                                String.format("%.3f", rate), clockElapsed, wallElapsed, speed,
                                this.videoFrameQueue != null ? this.videoFrameQueue.remaining() : -1,
                                this.audioFrameQueue != null ? this.audioFrameQueue.remaining() : -1,
                                this.perfAudioUploads, this.perfVideoRenders);
                    }
                    this.perfAudioUploads = 0;
                    this.perfVideoRenders = 0;
                    this.perfLastCheckMs = nowMs;
                    this.perfLastClockMs = this.clock.timeMs();
                    this.perfLastSpeed = speed;
                } else if (this.perfLastCheckMs == 0 || current != Status.PLAYING) {
                    this.perfLastCheckMs = nowMs;
                    this.perfLastClockMs = this.clock.timeMs();
                    this.perfLastSpeed = this.clock.speed();
                    this.perfAudioUploads = 0;
                    this.perfVideoRenders = 0;
                }

                final long iterStart = System.nanoTime();

                // AUDIO: FILL THE ENGINE QUEUE EAGERLY (SEE drainAudio)
                didWork |= this.drainAudio(current);
                final long afterAudio = System.nanoTime();

                // VIDEO: ONE FRAME PER ITERATION (INLINED POLL — A/V SYNC, ADAPTIVE DROP, THEN
                // UPLOAD VIA NATIVE PLANES OR sws_scale). A SECOND CONSUMER ON THIS QUEUE WOULD RACE.
                if (this.gfx != null && this.videoStreamIndex >= 0 && this.videoFrameQueue != null) {
                    while (true) {
                        final FrameQueue.Slot slot = this.videoFrameQueue.peek();
                        if (slot == null) break;

                        // SERIAL CHECK — DISCARD PRE-SEEK FRAMES
                        if (slot.serial != this.clock.serial()) {
                            this.videoFrameQueue.next();
                            continue;
                        }

                        // A/V SYNC — SKIP TIMING CHECKS DURING BUFFERING SO THAT THE
                        // FIRST AVAILABLE FRAME CAN RECALIBRATE THE CLOCK (VIA clock.update)
                        // AND RESOLVE BUFFERING. WITHOUT THIS BYPASS, A TIMESTAMP GAP CAUSES
                        // FRAMES TO BE REJECTED AS "TOO EARLY" INDEFINITELY (CLOCK IS FROZEN).
                        final boolean buffering = this.clock.status() == Status.BUFFERING;
                        final double clockSec = this.clock.time();
                        final double frameSec = slot.ptsMs / 1000.0;
                        final double diff = frameSec - clockSec;

                        if (!buffering && diff > AV_SYNC_TOO_EARLY_SEC) break;
                        if (!buffering && diff < -(this.clock.skipThresholdMs() / 1000.0)) {
                            this.videoFrameQueue.next();
                            this.totalSkippedFrames++;
                            continue;
                        }

                        // ADAPTIVE FRAME DROP
                        final double frameDurSec = this.clock.frameDurationSec();
                        if (this.renderDebtSec >= frameDurSec) {
                            final FrameQueue.Slot nextSlot = this.videoFrameQueue.peekNext();
                            if (nextSlot != null) {
                                this.renderDebtSec -= frameDurSec;
                                this.videoFrameQueue.next();
                                this.totalSkippedFrames++;
                                continue;
                            }
                        }

                        // DETERMINE UPLOAD PATH — maxSize/LOD MAY REQUIRE A DOWNSCALE BEFORE UPLOAD.
                        // THE TARGET IS RESOLVED PER FRAME, SO HOT LOD CHANGES APPLY WITHOUT
                        // TOUCHING THE DECODE PIPELINE OR THE CLOCK.
                        if (slot.format != this.cachedUploadFmt) {
                            this.cachedUploadFmt = slot.format;
                            this.cachedUploadMapping = FFTool.pixels(slot.format);
                        }
                        FFTool.Pixels mapping = this.cachedUploadMapping;
                        // ENGINE CAN'T TAKE THIS PLANAR FORMAT DIRECTLY (E.G. VULKAN WITHOUT GPU YUV CONVERSION) —
                        // DROP TO null SO THE BLOCK BELOW CONVERTS TO BGRA VIA sws BEFORE UPLOAD
                        if (mapping != null && !this.gfx.supports(mapping.format(), mapping.bits())) mapping = null;
                        final int targetW = scaled(slot.width, this.scaleWidth, this.lod.percent());
                        final int targetH = scaled(slot.height, this.scaleHeight, this.lod.percent());

                        AVFrame uploadFrame = slot.frame;
                        int uploadW = slot.width;
                        int uploadH = slot.height;
                        // THE TAGS RARELY CHANGE: PACKED WITH THE FORMAT (12 BITS EACH, 4 FOR RANGE AND SITING) THEY GATE THE REMAP
                        final long tags = (slot.format & 0xFFFL) | (uploadFrame.colorspace() & 0xFFFL) << 12
                                | (uploadFrame.color_range() & 0xFL) << 24 | (uploadFrame.chroma_location() & 0xFL) << 28
                                | (uploadFrame.color_trc() & 0xFFFL) << 32 | (uploadFrame.color_primaries() & 0xFFFL) << 44;
                        if (tags != this.colorTags || slot.width != this.colorWidth || slot.height != this.colorHeight) {
                            this.frameColor = colorimetry(slot.format, slot.width, slot.height, uploadFrame.colorspace(),
                                    uploadFrame.color_range(), uploadFrame.chroma_location(), uploadFrame.color_trc(), uploadFrame.color_primaries());
                            this.colorTags = tags;
                            this.colorWidth = slot.width;
                            this.colorHeight = slot.height;
                        }
                        final Colorimetry color = this.frameColor;

                        final long workStart = System.nanoTime();
                        if (mapping == null || targetW != slot.width || targetH != slot.height) {
                            // SWS PATH: DOWNSCALE AND/OR CONVERT. THE NATIVE FORMAT IS KEPT WHEN SWS
                            // CAN WRITE IT (SKIPS THE BGRA EXPANSION AND UPLOADS FEWER BYTES);
                            // UNSUPPORTED OUTPUTS CONVERT TO BGRA INSTEAD.
                            final int dstFormat = mapping != null && swscale.sws_isSupportedOutput(slot.format) != 0
                                    ? slot.format : AV_PIX_FMT_BGRA;
                            if (this.ensureScaler(slot.width, slot.height, slot.format, targetW, targetH, dstFormat, color)) {
                                final int result = swscale.sws_scale(
                                        this.swsContext,
                                        slot.frame.data(),
                                        slot.frame.linesize(),
                                        0,
                                        slot.height,
                                        this.scaledFrame.data(),
                                        this.scaledFrame.linesize()
                                );

                                if (result <= 0) {
                                    LOGGER.error(IT, "sws_scale failed: result={}, fmt={}, size={}x{} -> {}x{}",
                                            result, slot.format, slot.width, slot.height, targetW, targetH);
                                    // COUNT AS SKIPPED: THE FRAME WAS DECODED BUT DROPPED HERE, SO THE
                                    // ZERO-FRAME → ERROR CHECK MUST NOT MISTAKE THIS FOR A DEAD DECODER
                                    this.videoFrameQueue.next();
                                    this.totalSkippedFrames++;
                                    break;
                                }

                                uploadFrame = this.scaledFrame;
                                uploadW = targetW;
                                uploadH = targetH;
                                if (dstFormat == AV_PIX_FMT_BGRA) mapping = FFTool.BGRA;
                            } else if (mapping == null) {
                                // NO CONVERTER AND NO NATIVE UPLOAD — THE FRAME IS UNUSABLE.
                                // COUNT AS SKIPPED SO THE ZERO-FRAME → ERROR CHECK STAYS ACCURATE.
                                this.videoFrameQueue.next();
                                this.totalSkippedFrames++;
                                break;
                            }
                            // SCALER UNAVAILABLE BUT THE FORMAT UPLOADS NATIVELY — DEGRADE TO SOURCE SIZE
                        }

                        // FORMAT CHANGE DETECTION — RECONFIGURE GFXEngine; A REFUSED LAYOUT DROPS FRAMES UNTIL IT CHANGES. RGB
                        // LAYOUTS UPLOAD AS THEY ARE, SO ONLY YUV AND GRAY RECONFIGURE ON A COLOR CHANGE
                        final boolean tinted = mapping.format() != PixelFormat.BGRA && mapping.format() != PixelFormat.RGBA
                                && mapping.format() != PixelFormat.RGB;
                        if (mapping.format() != this.lastFormat || mapping.bits() != this.lastBitsPerComponent
                                || uploadW != this.lastFrameWidth || uploadH != this.lastFrameHeight || tinted && !color.equals(this.lastColor)) {
                            this.uploadRefused = !this.gfx.format(mapping.format(), uploadW, uploadH, mapping.bits(), color);
                            this.lastFormat = mapping.format();
                            this.lastBitsPerComponent = mapping.bits();
                            this.lastFrameWidth = uploadW;
                            this.lastFrameHeight = uploadH;
                            this.lastColor = color;
                            if (!this.uploadRefused)
                                LOGGER.debug(IT, "GFX format: {} {}x{} {}bit {} (source={}x{})",
                                        mapping.format(), uploadW, uploadH, mapping.bits(), color, slot.width, slot.height);
                            else if (!this.refusedWarned) {
                                this.refusedWarned = true;
                                LOGGER.error(IT, "Graphics engine refused {} {}x{} {}bit; frames are dropped until the layout changes",
                                        mapping.format(), uploadW, uploadH, mapping.bits());
                            }
                        }
                        // A REFUSED LAYOUT SKIPS ONLY THE UPLOAD: THE FRAME IS STILL CONSUMED AND THE CLOCK ADVANCES, SO
                        // PLAYBACK AND LOOPING KEEP THEIR PACE (COUNTED AS SKIPPED FOR THE ZERO-FRAME → ERROR CHECK)
                        if (this.uploadRefused) this.totalSkippedFrames++;
                        else this.uploadNativePlanes(uploadFrame, mapping.format(), uploadH);

                        // TRACK RENDER DEBT (CONVERT+UPLOAD TIME VS FRAME BUDGET)
                        final double workSec = (System.nanoTime() - workStart) / 1_000_000_000.0;
                        if (workSec > frameDurSec) {
                            this.renderDebtSec += (workSec - frameDurSec);
                        } else {
                            this.renderDebtSec = Math.max(0, this.renderDebtSec - (frameDurSec - workSec));
                        }

                        // CONSUME (frameSec ALREADY HOLDS THE PTS — slot IS RECYCLED BY next())
                        this.videoFrameQueue.next();
                        if (!this.uploadRefused) {
                            this.totalRenderedFrames++;
                            this.perfVideoRenders++;
                        }

                        // CLOCK UPDATE (ONLY WHEN VIDEO DRIVES THE CLOCK: NO AUDIO, OR DURING BUFFERING)
                        if (this.audioStreamIndex < 0 || this.clock.status() == Status.BUFFERING) {
                            this.clock.update(frameSec, this.videoPacketQueue.serial());
                        }

                        didWork = true;
                        break;
                    }
                }
                final long afterVideo = System.nanoTime();

                // AUDIO AGAIN AFTER VIDEO (REFILL WHAT THE ENGINE CONSUMED DURING VIDEO UPLOAD)
                didWork |= this.drainAudio(current);
                final long afterWork = System.nanoTime();

                // PERFORMANCE MONITOR
                final long audioMs = (afterAudio - iterStart + afterWork - afterVideo) / 1_000_000;
                final long videoMs = (afterVideo - afterAudio) / 1_000_000;
                final long totalMs = (afterWork - iterStart) / 1_000_000;
                if (totalMs > this.clock.frameDurationMs() && didWork) {
                    slowCount++;
                    if (totalMs > slowWorstMs) {
                        slowWorstMs = totalMs;
                        slowAudioMs = audioMs;
                        slowVideoMs = videoMs;
                        slowFrameMs = this.clock.frameDurationMs();
                    }
                }

                // TRACK BUFFERING→PLAYING RECOVERY FOR STARVATION COOLDOWN
                if (current == Status.BUFFERING && this.clock.status() == Status.PLAYING) {
                    lastStarvationRecoveryMs = nowMs();
                }

                if (!didWork) {
                    // STARVATION DETECTION — TRANSITION TO BUFFERING INSTEAD OF SEEKING.
                    // A FULL requestSeek IS DESTRUCTIVE: IT INCREMENTS SERIAL, FLUSHES QUEUES,
                    // AND CALLS av_seek_frame, WHICH CAUSES A FEEDBACK LOOP WHEN FRAMES ARRIVE
                    // IN BURSTS (E.G. GC PAUSES, RENDER LAG IN MINECRAFT). INSTEAD JUST GO TO
                    // BUFFERING — THE PIPELINE IS STILL RUNNING, AND clock.update() WILL RETURN
                    // TO PLAYING WHEN FRAMES ARRIVE.
                    if (current == Status.PLAYING && !this.clock.demuxFinished()) {
                        final long starvNowMs = nowMs();
                        final boolean queuesEmpty =
                                (this.videoFrameQueue == null || this.videoFrameQueue.empty()) &&
                                        (this.audioFrameQueue == null || this.audioFrameQueue.empty());

                        // ALSO DETECT UNREACHABLE FRAMES: QUEUES HAVE DATA BUT ALL
                        // FRAMES ARE >10s AHEAD OF THE CLOCK. THIS HAPPENS FOR LIVE
                        // HLS STREAMS WHERE PTS STARTS AT THE STREAM'S ELAPSED TIME
                        // (E.G., 14890s) BUT THE CLOCK STARTS AT 0 AFTER clock.start().
                        // WITHOUT THIS, THE PLAYER DEADLOCKS: FRAMES ARE "TOO EARLY"
                        // BUT QUEUES AREN'T EMPTY SO STARVATION NEVER FIRES.
                        boolean unreachable = false;
                        if (!queuesEmpty) {
                            final double clockSec = this.clock.time();
                            unreachable = true;
                            if (this.audioFrameQueue != null) {
                                final FrameQueue.Slot a = this.audioFrameQueue.peek();
                                if (a != null && a.serial == this.clock.serial()
                                        && (a.ptsMs / 1000.0 - clockSec) <= PTS_DISCONTINUITY_SEC) {
                                    unreachable = false;
                                }
                            }
                            if (unreachable && this.videoFrameQueue != null) {
                                final FrameQueue.Slot v = this.videoFrameQueue.peek();
                                if (v != null && v.serial == this.clock.serial()
                                        && (v.ptsMs / 1000.0 - clockSec) <= PTS_DISCONTINUITY_SEC) {
                                    unreachable = false;
                                }
                            }
                        }

                        final boolean starving = queuesEmpty || unreachable;
                        if (starving && starvNowMs - lastStarvationRecoveryMs > STARVATION_THRESHOLD_MS * 4) {
                            if (starvationStartMs == 0) {
                                starvationStartMs = starvNowMs;
                            } else if (starvNowMs - starvationStartMs > STARVATION_THRESHOLD_MS) {
                                if (!this.starvationWarned) LOGGER.warn(IT, "Starvation — BUFFERING (clock={}ms)", this.clock.timeMs());
                                this.starvationWarned = true;
                                this.clock.transition(Status.BUFFERING);
                                starvationStartMs = 0;
                            }
                        } else {
                            starvationStartMs = 0;
                        }
                    } else {
                        starvationStartMs = 0;
                    }
                    Thread.sleep(1);
                } else {
                    starvationStartMs = 0;
                }
            }

            LOGGER.debug(IT, "Consumption loop exited — R: {}, S: {}, clock: {}ms, status: {}",
                    this.totalRenderedFrames, this.totalSkippedFrames, this.clock.timeMs(), this.clock.status());

            this.stopThreads();

            // AN INTERRUPTED EXIT (stop()/RESTART RACING THE LOOP) MUST STILL LAND ON A TERMINAL STATE — LEAVING
            // PLAYING/BUFFERING BEHIND WOULD REPORT A LIVE STATUS, AND A stop() DURING THE ENDED TEARDOWN MUST WIN
            final Status finalStatus = this.clock.status();
            final boolean interrupted = Thread.currentThread().isInterrupted();
            if (finalStatus != Status.ERROR && finalStatus != Status.STOPPED && (finalStatus != Status.ENDED || interrupted)) {
                this.publishTransition(interrupted ? Status.STOPPED : Status.ENDED);
            }

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.stopThreads();
            this.publishTransition(Status.STOPPED);
        } catch (final Throwable e) {
            LOGGER.error(IT, "Error in lifecycle for URI {}", this.source.uri(this.quality), e);
            this.exception(e);
            this.stopThreads();
            this.publishTransition(Status.ERROR);
        } finally {
            if (slowCount > 0)
                LOGGER.warn(IT, SLOW_REPORT, slowCount, (System.nanoTime() - started) / 1_000_000, slowWorstMs, slowAudioMs, slowVideoMs, slowFrameMs);
            this.freeQueues();
            this.cleanup();
        }
    }

    // DEMUX THREAD
    private void demuxLoop() {
        try {
            if (!this.init()) {
                // AN OPEN CUT SHORT BY stop()/RESTART/RELEASE IS NOT A FAILURE: THE INTERRUPTED
                // LIFECYCLE LANDS ON STOPPED BY ITSELF AND NO EXCEPTION LEAKS INTO THE NEXT RUN
                if (!this.ioAbortRequested) this.publishTransition(Status.ERROR);
                return;
            }
        } catch (final Throwable e) {
            LOGGER.error(IT, "Init failed with exception", e);
            this.exception(e);
            this.publishTransition(Status.ERROR);
            return;
        }
        this.clock.start(this.clock.pauseRequested());

        final AVPacket packet = avcodec.av_packet_alloc();
        final AVPacket trackPacket = this.useAudioTrack ? avcodec.av_packet_alloc() : null;
        long demuxVideoPackets = 0;
        long demuxAudioPackets = 0;
        boolean mainEof = false;
        boolean trackEof = false;

        LOGGER.debug(IT, "Demux loop starting — videoIdx={}, audioIdx={}, useAudioTrack={}, nb_streams={}",
                this.videoStreamIndex, this.audioStreamIndex, this.useAudioTrack,
                this.formatContext != null ? this.formatContext.nb_streams() : -1);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // SEEK HANDLING
                final MasterClock.SeekRequest seekReq = this.clock.consumeSeek();
                if (seekReq != null) {
                    final long targetMs = seekReq.targetMs();
                    final boolean precise = seekReq.precise();

                    this.clock.signalDemuxResumed();

                    if (precise) {
                        this.stopDecodeThreads();
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                        if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);
                    } else {
                        // MUST USE reset() NOT flush() — flush() DOESN'T CLEAR THE 'finished'
                        // FLAG. AFTER EOF→repeat, finish() WAS CALLED, AND flush() ALONE LEAVES
                        // finished=true. DECODE THREADS THEN CALL get() ON AN EMPTY+FINISHED
                        // QUEUE → null → EXIT IMMEDIATELY, PRODUCING 0 FRAMES. reset() CLEARS
                        // BOTH 'finished' AND 'aborted', MAKING THE QUEUE REUSABLE.
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                    }

                    this.clock.serial(this.videoPacketQueue.serial());
                    LOGGER.debug(IT, "Seek serial sync: clockSerial={}, vQueueSerial={}, aQueueSerial={}",
                            this.clock.serial(), this.videoPacketQueue.serial(), this.audioPacketQueue.serial());
                    mainEof = false;
                    trackEof = false;

                    if (this.clock.seekPending()) {
                        LOGGER.debug(IT, "Seek to {}ms superseded; skipping", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    // A TARGET WITHOUT A DURATION TO CLAMP IT (LIVE, RELOAD) IS SATURATED, NEVER WRAPPED NEGATIVE
                    final long ffTs = Math.min(targetMs, Long.MAX_VALUE / 1_000L) * 1_000L;
                    final int seekFlags = avformat.AVSEEK_FLAG_BACKWARD;
                    boolean reopened = false;

                    int seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, seekFlags);
                    if (seekResult < 0) {
                        seekResult = avformat.avformat_seek_file(this.formatContext, -1, Long.MIN_VALUE, ffTs, ffTs, seekFlags);
                    }
                    if (seekResult < 0) {
                        seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, 0);
                    }
                    if (seekResult < 0) {
                        // REOPEN NEEDS EXCLUSIVE CODEC ACCESS — ON NON-PRECISE SEEKS THE
                        // DECODE THREADS ARE STILL RUNNING AND reopenFormat() FLUSHES THE
                        // CODECS THEY ARE USING (NATIVE RACE). STOP THEM AND MAKE THE
                        // QUEUES REUSABLE AGAIN (CLEAR ABORT FLAG + RESYNC SERIAL).
                        this.stopDecodeThreads();
                        final boolean reopenOk = this.reopenFormat();
                        this.videoPacketQueue.reset();
                        this.audioPacketQueue.reset();
                        this.clock.serial(this.videoPacketQueue.serial());
                        if (!reopenOk) {
                            // reopenFormat ALREADY CLOSED THE OLD CONTEXT — THERE IS NOTHING LEFT TO READ FROM, THE
                            // PIPELINE CANNOT RECOVER; A REOPEN CUT SHORT BY stop() IS THE CALLER'S OWN DOING, NOT A FAILURE
                            if (this.ioAbortRequested) return;
                            LOGGER.error(IT, "Seek to {}ms failed and the input could not be reopened — stopping pipeline", targetMs);
                            this.exception(new IOException("Seek to " + targetMs + "ms failed and the input could not be reopened"));
                            this.publishTransition(Status.ERROR);
                            return;
                        }
                        LOGGER.warn(IT, "Seek to {}ms failed; reopened input from beginning", targetMs);
                        reopened = true;
                        seekResult = 0;
                        if (targetMs > 0) {
                            seekResult = avformat.av_seek_frame(this.formatContext, -1, ffTs, 0);
                            if (seekResult < 0) seekResult = avformat.avformat_seek_file(this.formatContext, -1, Long.MIN_VALUE, ffTs, ffTs, 0);
                        }
                    }
                    if (seekResult < 0) {
                        LOGGER.warn(IT, "Seek to {}ms not honored by reopened input; resuming from beginning", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    if (this.clock.seekPending()) {
                        LOGGER.debug(IT, "Seek to {}ms superseded after main seek; skipping drain", targetMs);
                        this.ensureDecodeThreads();
                        continue;
                    }

                    if (this.useAudioTrack && this.trackFormatContext != null) {
                        avformat.av_seek_frame(this.trackFormatContext, -1, ffTs, seekFlags);
                    }

                    if (precise && !(reopened && targetMs == 0)) {
                        this.syncDrain(targetMs, packet, trackPacket);
                    }

                    this.ensureDecodeThreads();

                    LOGGER.debug(IT, "Seek completed to {}ms (precise={}, serial={}, clockMs={})",
                            targetMs, precise, this.videoPacketQueue.serial(), this.clock.timeMs());
                    continue;
                }

                // PAUSE HANDLING
                if (this.clock.pauseRequested() && this.clock.status() == Status.PAUSED) {
                    this.clock.awaitChange(0);
                    continue;
                }

                // SEPARATE AUDIO TRACK
                if (this.useAudioTrack && !trackEof && this.trackFormatContext != null && trackPacket != null) {
                    final int reads = WaterMediaConfig.media.ffmpeg.trackPacketReads;
                    for (int i = 0; i < reads; i++) {
                        final int trackResult = avformat.av_read_frame(this.trackFormatContext, trackPacket);
                        if (trackResult >= 0) {
                            try {
                                if (trackPacket.stream_index() == this.audioStreamIndex && this.sfx != null) {
                                    if (!this.audioPacketQueue.put(trackPacket)) break;
                                    demuxAudioPackets++;
                                }
                            } finally {
                                avcodec.av_packet_unref(trackPacket);
                            }
                        } else {
                            trackEof = true;
                            LOGGER.debug(IT, "Audio track reached EOF after {} packets", demuxAudioPackets);
                            if (this.audioPacketQueue != null) this.audioPacketQueue.finish();
                            break;
                        }
                    }
                }

                // READ PACKET (MAIN CONTEXT)
                if (!mainEof) {
                    final int result = avformat.av_read_frame(this.formatContext, packet);
                    if (result < 0) {
                        mainEof = true;
                        LOGGER.debug(IT, "Main context EOF after {} video packets (R: {}, S: {})",
                                demuxVideoPackets, this.totalRenderedFrames, this.totalSkippedFrames);
                        if (this.videoPacketQueue != null) this.videoPacketQueue.finish();
                        // WITHOUT A SEPARATE TRACK, AUDIO PACKETS COME FROM THE SAME FORMAT
                        // CONTEXT — FINISH THE AUDIO QUEUE SO DECODE THREADS DRAIN CLEANLY.
                        if (!this.useAudioTrack && this.audioPacketQueue != null) this.audioPacketQueue.finish();
                    }
                }

                // CHECK BOTH EOF — SIGNAL DEMUX FINISHED AND WAIT FOR SEEK.
                // THE CONSUMPTION LOOP HANDLES BOTH REPEAT AND ENDED AFTER
                // ALL DECODED FRAMES HAVE BEEN DRAINED AND RENDERED.
                // PREVIOUSLY, repeat() TRIGGERED requestSeek(0) HERE IMMEDIATELY,
                // WHICH FLUSHED ALL UNPROCESSED PACKETS — CAUSING A RAPID
                // EOF→SEEK→EOF LOOP WITH ALMOST NO FRAMES RENDERED.
                if (mainEof && (trackEof || !this.useAudioTrack)) {
                    LOGGER.debug(IT, "All contexts EOF (video: {}, audio: {})", demuxVideoPackets, demuxAudioPackets);

                    this.clock.signalDemuxFinished();
                    LOGGER.debug(IT, "Demux finished; consumption loop will handle ENDED/repeat after drain");

                    while (!this.clock.seekPending() && !Thread.currentThread().isInterrupted()) {
                        this.clock.awaitChange(0);
                    }
                    if (!this.clock.seekPending()) break;

                    LOGGER.debug(IT, "Seek after EOF; restarting pipeline");
                    mainEof = false;
                    trackEof = false;
                    if (this.videoPacketQueue != null) this.videoPacketQueue.reset();
                    if (this.audioPacketQueue != null) this.audioPacketQueue.reset();
                    continue;
                }

                if (mainEof) {
                    continue;
                }

                try {
                    final int streamIndex = packet.stream_index();
                    if (streamIndex == this.videoStreamIndex && this.gfx != null) {
                        while (!this.videoPacketQueue.tryPut(packet)) {
                            if (Thread.currentThread().isInterrupted() || this.clock.seekPending()) break;
                            if (this.useAudioTrack && this.trackFormatContext != null && trackPacket != null) {
                                final int sr = avformat.av_read_frame(this.trackFormatContext, trackPacket);
                                if (sr >= 0) {
                                    try {
                                        if (trackPacket.stream_index() == this.audioStreamIndex && this.sfx != null) {
                                            this.audioPacketQueue.put(trackPacket);
                                            demuxAudioPackets++;
                                        }
                                    } finally {
                                        avcodec.av_packet_unref(trackPacket);
                                    }
                                }
                            }
                            Thread.sleep(1);
                        }
                        demuxVideoPackets++;
                    } else if (!this.useAudioTrack
                            && streamIndex == this.audioStreamIndex && this.sfx != null) {
                        if (!this.audioPacketQueue.put(packet)) break;
                        demuxAudioPackets++;
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LOGGER.debug(IT, "Demux loop exiting — video: {}, audio: {}, interrupted: {}",
                    demuxVideoPackets, demuxAudioPackets, Thread.currentThread().isInterrupted());

            if (this.videoPacketQueue != null) this.videoPacketQueue.finish();
            if (this.audioPacketQueue != null) this.audioPacketQueue.finish();

            avcodec.av_packet_free(packet);
            if (trackPacket != null) avcodec.av_packet_free(trackPacket);
        }
    }

    // VIDEO DECODE THREAD
    private void videoDecodeLoop() {
        final AVFrame tempFrame = avutil.av_frame_alloc();
        final AVFrame hwTransfer = (this.hwDeviceCtx != null) ? avutil.av_frame_alloc() : null;
        final int again = avutil.AVERROR_EAGAIN();
        final int[] serialOut = new int[1];
        int lastSerial = -1;
        long packetsProcessed = 0;
        long framesProduced = 0;
        long sendFailures = 0;
        long receiveFailures = 0;

        // HW DECODE HEALTH TRACKING (SEE HW_TRANSFER_* CONSTANTS)
        int hwFailStreak = 0;
        long hwTransferNs = 0;
        int hwTransferCount = 0;
        boolean hwGaveUp = false;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final AVPacket packet = this.videoPacketQueue.get(serialOut);

                // A null PACKET MEANS THE QUEUE IS DONE: A CLEAN EOF (DRAIN THE DECODER'S
                // BUFFERED BACKLOG WITH A null FLUSH PACKET) OR AN abort() FROM stop()/seek()
                // (THE BACKLOG IS STALE — JUST EXIT). DELAYED/THREADED DECODERS (libdav1d,
                // THE NATIVE av1 DECODER) HOLD A REORDER WINDOW THAT ONLY COMES OUT ON FLUSH;
                // WITHOUT THIS THE TAIL IS LOST, AND A DECODER THAT BUFFERS THE WHOLE STREAM
                // EMITS NOTHING AT ALL — ENDING PLAYBACK WITH ZERO RENDERED FRAMES.
                final boolean eof = packet == null;
                if (eof && (lastSerial < 0 || !this.videoPacketQueue.endOfFile())) break;

                final int packetSerial = eof ? lastSerial : serialOut[0];

                try {
                    if (!eof && packetSerial != lastSerial) {
                        if (lastSerial >= 0) avcodec.avcodec_flush_buffers(this.videoCodecContext);
                        lastSerial = packetSerial;
                        this.lastRawVideoPts = Double.NaN;
                        this.videoPtsOffset = 0;
                    }

                    if (!eof && packetSerial != this.videoPacketQueue.serial()) continue;

                    // A null PACKET PUTS THE DECODER INTO DRAIN MODE; A REAL send FAILURE
                    // SKIPS ONLY THAT PACKET.
                    final int sent = avcodec.avcodec_send_packet(this.videoCodecContext, packet);
                    if (sent < 0 && sent != again && sent != AVERROR_EOF && ++sendFailures == 1)
                        logCodecError("video", "send packet", this.videoCodecContext, this.videoStreamIndex, sent);
                    if (sent < 0 && !eof) continue;

                    while (true) {
                        final int received = avcodec.avcodec_receive_frame(this.videoCodecContext, tempFrame);
                        if (received < 0) {
                            if (received != again && received != AVERROR_EOF && ++receiveFailures == 1)
                                logCodecError("video", "receive frame", this.videoCodecContext, this.videoStreamIndex, received);
                            break;
                        }
                        AVFrame frameToQueue = tempFrame;

                        if (!hwGaveUp && hwTransfer != null && tempFrame.format() == this.hwPixelFormat) {
                            final long transferStart = System.nanoTime();
                            if (av_hwframe_transfer_data(hwTransfer, tempFrame, 0) < 0) {
                                if (hwFailStreak++ == 0) LOGGER.warn(IT, "Failed to transfer frame from GPU");
                                if (hwFailStreak >= HW_TRANSFER_FAIL_LIMIT) {
                                    LOGGER.error(IT, "GPU frame transfer failed {} times in a row — falling back to software decoding", hwFailStreak);
                                    hwGaveUp = this.reinitVideoCodecSoftware();
                                    hwFailStreak = 0;
                                }
                                continue;
                            }
                            hwFailStreak = 0;
                            frameToQueue = hwTransfer;
                            // THE GPU COPY CARRIES PIXELS ONLY, SO THE COLOR TAGS COME FROM THE DECODED FRAME
                            hwTransfer.colorspace(tempFrame.colorspace()).color_range(tempFrame.color_range())
                                    .chroma_location(tempFrame.chroma_location()).color_trc(tempFrame.color_trc())
                                    .color_primaries(tempFrame.color_primaries());

                            // SOME DRIVERS (NOTABLY AMD D3D11VA) DECODE FINE BUT STALL ON
                            // THE GPU→CPU COPY — IF THE TRANSFER ALONE EATS THE FRAME
                            // BUDGET, SOFTWARE DECODING IS FASTER. EVALUATE PERIODICALLY.
                            hwTransferNs += System.nanoTime() - transferStart;
                            if (++hwTransferCount >= HW_TRANSFER_PERF_WINDOW) {
                                final double avgSec = hwTransferNs / 1_000_000_000.0 / hwTransferCount;
                                if (avgSec > this.clock.frameDurationSec() * HW_TRANSFER_BUDGET_RATIO) {
                                    LOGGER.warn(IT, "GPU frame transfer too slow (avg {}ms vs {}ms frame budget) — falling back to software decoding",
                                            String.format("%.1f", avgSec * 1000.0), this.clock.frameDurationMs());
                                    hwGaveUp = this.reinitVideoCodecSoftware();
                                }
                                hwTransferNs = 0;
                                hwTransferCount = 0;
                            }
                        }

                        // PREFER BEST-EFFORT TIMESTAMPS; SYNTHESIZE FROM THE PREVIOUS FRAME
                        // WHEN MISSING. A RAW AV_NOPTS_VALUE WOULD PRODUCE AN ASTRONOMICAL
                        // PTS THAT LATCHES A GARBAGE DISCONTINUITY OFFSET AND WRECKS THE CLOCK.
                        long rawPts = tempFrame.best_effort_timestamp();
                        if (rawPts == avutil.AV_NOPTS_VALUE) rawPts = tempFrame.pts();
                        final double rawPtsSec;
                        if (rawPts == avutil.AV_NOPTS_VALUE) {
                            rawPtsSec = Double.isNaN(this.lastRawVideoPts)
                                    ? 0.0 : this.lastRawVideoPts + this.clock.frameDurationSec();
                        } else {
                            rawPtsSec = rawPts * this.videoTimeBase;
                        }

                        // PTS DISCONTINUITY DETECTION (HLS AD STITCHING)
                        if (!Double.isNaN(this.lastRawVideoPts)) {
                            final double jump = rawPtsSec - this.lastRawVideoPts;
                            if (Math.abs(jump) > PTS_DISCONTINUITY_SEC) {
                                final double expected = this.lastRawVideoPts + this.videoPtsOffset + this.clock.frameDurationSec();
                                this.videoPtsOffset = expected - rawPtsSec;
                                LOGGER.debug(IT, "Video PTS discontinuity: jump={}s, new offset={}s", String.format("%.1f", jump), String.format("%.1f", this.videoPtsOffset));
                            }
                        }
                        this.lastRawVideoPts = rawPtsSec;

                        final double ptsSec = rawPtsSec + this.videoPtsOffset;
                        final long ptsMs = (long) (ptsSec * 1000.0);

                        final FrameQueue.Slot slot = this.videoFrameQueue.peekWritable();
                        if (slot == null) break;

                        av_frame_unref(slot.frame);
                        av_frame_move_ref(slot.frame, frameToQueue);
                        slot.ptsMs = ptsMs;
                        slot.durationMs = this.clock.frameDurationMs();
                        slot.serial = packetSerial;
                        slot.width = slot.frame.width();
                        slot.height = slot.frame.height();
                        slot.format = slot.frame.format();

                        this.videoFrameQueue.push();
                        framesProduced++;
                    }

                    packetsProcessed++;
                } finally {
                    if (packet != null) avcodec.av_packet_free(packet);
                }

                if (eof) break; // DECODER FULLY DRAINED — NOTHING MORE TO PRODUCE
            }
        } finally {
            if (sendFailures > 1 || receiveFailures > 1)
                LOGGER.warn(IT, "Video decoder suppressed repeated errors: send={}, receive={}, stream={}",
                        Math.max(0L, sendFailures - 1), Math.max(0L, receiveFailures - 1), this.videoStreamIndex);
            LOGGER.debug(IT, "Video decode exiting — packets: {}, frames produced: {}, interrupted: {}",
                    packetsProcessed, framesProduced, Thread.currentThread().isInterrupted());
            // RESET THE CODEC FOR THE NEXT RUN. AT A CLEAN EOF THIS THREAD DRAINS THE DECODER
            // (null FLUSH PACKET ABOVE), LEAVING IT IN DRAINED/EOF STATE THAT REJECTS NEW PACKETS.
            // A repeat() RESTARTS THIS THREAD WITH lastSerial=-1, WHICH SKIPS THE PER-SEEK FLUSH —
            // WITHOUT FLUSHING HERE THE DECODER WOULD STAY DRAINED AND EMIT ZERO FRAMES ON REPEAT,
            // TRIPPING THE ZERO-FRAME → ERROR CHECK AND FREEZING VIDEO.
            if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
            avutil.av_frame_free(tempFrame);
            if (hwTransfer != null) avutil.av_frame_free(hwTransfer);
        }
    }

    // AUDIO DECODE THREAD
    private void audioDecodeLoop() {
        final AVFrame tempFrame = avutil.av_frame_alloc();
        final int again = avutil.AVERROR_EAGAIN();
        final int[] serialOut = new int[1];
        int lastSerial = -1;
        long packetsProcessed = 0;
        long framesProduced = 0;
        long sendFailures = 0;
        long receiveFailures = 0;

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final AVPacket packet = this.audioPacketQueue.get(serialOut);

                // SEE videoDecodeLoop: a null PACKET IS A CLEAN EOF (FLUSH THE DECODER'S
                // BUFFERED BACKLOG) OR AN abort() TEARDOWN (DROP IT).
                final boolean eof = packet == null;
                if (eof && (lastSerial < 0 || !this.audioPacketQueue.endOfFile())) break;

                final int packetSerial = eof ? lastSerial : serialOut[0];

                try {
                    if (!eof && packetSerial != lastSerial) {
                        if (lastSerial >= 0) {
                            avcodec.avcodec_flush_buffers(this.audioCodecContext);
                            // DROP SAMPLES BUFFERED INSIDE THE RESAMPLER — THEY BELONG
                            // TO THE PRE-SEEK POSITION AND WOULD LEAK INTO POST-SEEK AUDIO
                            if (!this.audioPassthrough && this.swrContext != null) {
                                swresample.swr_close(this.swrContext);
                                swresample.swr_init(this.swrContext);
                            }
                        }
                        lastSerial = packetSerial;
                        this.lastRawAudioPts = Double.NaN;
                        this.audioPtsOffset = 0;
                        this.audioNextPtsSec = Double.NaN;
                    }

                    if (!eof && packetSerial != this.audioPacketQueue.serial()) continue;

                    // A null PACKET PUTS THE DECODER INTO DRAIN MODE; A REAL send FAILURE
                    // SKIPS ONLY THAT PACKET.
                    final int sent = avcodec.avcodec_send_packet(this.audioCodecContext, packet);
                    if (sent < 0 && sent != again && sent != AVERROR_EOF && ++sendFailures == 1)
                        logCodecError("audio", "send packet", this.audioCodecContext, this.audioStreamIndex, sent);
                    if (sent < 0 && !eof) continue;

                    while (true) {
                        final int received = avcodec.avcodec_receive_frame(this.audioCodecContext, tempFrame);
                        if (received < 0) {
                            if (received != again && received != AVERROR_EOF && ++receiveFailures == 1)
                                logCodecError("audio", "receive frame", this.audioCodecContext, this.audioStreamIndex, received);
                            break;
                        }
                        // MID-STREAM PARAMETER CHANGES (CHAINED OGG / ICECAST) NEED A NEW
                        // RESAMPLER — FEEDING THE OLD ONE PLAYS AUDIO AT THE WRONG SPEED
                        if (!this.ensureAudioInputConfig(tempFrame)) continue;

                        // PREFER BEST-EFFORT TIMESTAMPS; SYNTHESIZE CONTINUITY WHEN MISSING
                        // (THE OGG DEMUXER IS A COMMON SOURCE OF AV_NOPTS_VALUE PACKETS)
                        long rawPts = tempFrame.best_effort_timestamp();
                        if (rawPts == avutil.AV_NOPTS_VALUE) rawPts = tempFrame.pts();
                        final double rawPtsSec;
                        if (rawPts == avutil.AV_NOPTS_VALUE) {
                            rawPtsSec = Double.isNaN(this.audioNextPtsSec)
                                    ? 0.0 : this.audioNextPtsSec - this.audioPtsOffset;
                        } else {
                            rawPtsSec = rawPts * this.audioTimeBase;
                        }

                        // PTS DISCONTINUITY DETECTION (HLS AD STITCHING)
                        if (!Double.isNaN(this.lastRawAudioPts)) {
                            final double jump = rawPtsSec - this.lastRawAudioPts;
                            if (Math.abs(jump) > PTS_DISCONTINUITY_SEC) {
                                final double audioDur = (double) tempFrame.nb_samples() / tempFrame.sample_rate();
                                final double expected = this.lastRawAudioPts + this.audioPtsOffset + audioDur;
                                this.audioPtsOffset = expected - rawPtsSec;
                                LOGGER.debug(IT, "Audio PTS discontinuity: jump={}s, new offset={}s", String.format("%.1f", jump), String.format("%.1f", this.audioPtsOffset));
                            }
                        }
                        this.lastRawAudioPts = rawPtsSec;

                        final double ptsSec = rawPtsSec + this.audioPtsOffset;

                        if (!this.enqueueResampledAudio(tempFrame, ptsSec, packetSerial)) break;
                        framesProduced++;

                        if (!this.audioPassthrough) {
                            final long delay = swresample.swr_get_delay(this.swrContext, this.audioOutputSampleRate);
                            if (delay > AUDIO_SAMPLES / 2) {
                                if (!this.enqueueResamplerFlush(packetSerial)) break;
                                framesProduced++;
                            }
                        }
                    }

                    packetsProcessed++;
                } finally {
                    if (packet != null) avcodec.av_packet_free(packet);
                }

                if (eof) break; // DECODER FULLY DRAINED — NOTHING MORE TO PRODUCE
            }
        } finally {
            if (sendFailures > 1 || receiveFailures > 1)
                LOGGER.warn(IT, "Audio decoder suppressed repeated errors: send={}, receive={}, stream={}",
                        Math.max(0L, sendFailures - 1), Math.max(0L, receiveFailures - 1), this.audioStreamIndex);
            LOGGER.debug(IT, "Audio decode exiting — packets: {}, frames produced: {}, interrupted: {}",
                    packetsProcessed, framesProduced, Thread.currentThread().isInterrupted());
            // RESET THE CODEC FOR THE NEXT RUN (SEE THE VIDEO DECODER): AT A CLEAN EOF THIS THREAD
            // DRAINS THE DECODER, LEAVING IT IN DRAINED/EOF STATE. A repeat() RESTARTS THIS THREAD
            // WITH lastSerial=-1 AND SKIPS ITS PER-SEEK FLUSH, SO WITHOUT THIS FLUSH THE DECODER
            // WOULD STAY DRAINED AND REJECT NEW PACKETS. AUDIO RARELY BUFFERS, BUT IT KEEPS THE INVARIANT.
            if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);
            avutil.av_frame_free(tempFrame);
        }
    }

    private boolean enqueueResampledAudio(final AVFrame srcFrame, final double ptsSec, final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);

        if (this.audioPassthrough) {
            // AN EMPTY FRAME HAS NOTHING TO PLAY, LIKE AN EMPTY RESAMPLE BELOW; THE ENGINE REFUSES EMPTY CHUNKS
            if (srcFrame.nb_samples() <= 0) return true;
            av_frame_ref(slot.frame, srcFrame);
            final double durationSec = (double) srcFrame.nb_samples() / srcFrame.sample_rate();
            slot.ptsMs = (long) (ptsSec * 1000.0);
            slot.durationMs = (long) (durationSec * 1000.0);
            slot.serial = serial;
            this.audioNextPtsSec = ptsSec + durationSec;
            this.audioFrameQueue.push();
            return true;
        }

        slot.frame.format(this.audioOutputAvFormat);
        slot.frame.ch_layout().nb_channels(this.audioOutputChannels);
        av_channel_layout_default(slot.frame.ch_layout(), this.audioOutputChannels);
        slot.frame.sample_rate(this.audioOutputSampleRate);
        slot.frame.nb_samples(AUDIO_SAMPLES);

        if (av_frame_get_buffer(slot.frame, 0) < 0) {
            if (!this.slotWarned) LOGGER.warn(IT, "Failed to allocate audio buffer in slot");
            this.slotWarned = true;
            return true;
        }

        // SAMPLES BUFFERED INSIDE THE RESAMPLER COME OUT FIRST, SO THE OUTPUT
        // STARTS EARLIER THAN THE INPUT PTS BY THE BUFFERED DELAY
        final long delaySamples = swresample.swr_get_delay(this.swrContext, this.audioOutputSampleRate);
        final double outPtsSec = ptsSec - (delaySamples > 0 ? (double) delaySamples / this.audioOutputSampleRate : 0.0);

        final int samplesConverted = swresample.swr_convert(
                this.swrContext,
                slot.frame.data(), slot.frame.nb_samples(),
                srcFrame.data(), srcFrame.nb_samples()
        );

        if (samplesConverted <= 0) {
            av_frame_unref(slot.frame);
            return true;
        }

        final double durationSec = (double) samplesConverted / this.audioOutputSampleRate;
        slot.frame.nb_samples(samplesConverted);
        slot.ptsMs = (long) (outPtsSec * 1000.0);
        slot.durationMs = (long) (durationSec * 1000.0);
        slot.serial = serial;
        this.audioNextPtsSec = outPtsSec + durationSec;

        this.audioFrameQueue.push();
        return true;
    }

    private boolean enqueueResamplerFlush(final int serial) {
        final FrameQueue.Slot slot = this.audioFrameQueue.peekWritable();
        if (slot == null) return false;

        av_frame_unref(slot.frame);
        slot.frame.format(this.audioOutputAvFormat);
        slot.frame.ch_layout().nb_channels(this.audioOutputChannels);
        av_channel_layout_default(slot.frame.ch_layout(), this.audioOutputChannels);
        slot.frame.sample_rate(this.audioOutputSampleRate);
        slot.frame.nb_samples(AUDIO_SAMPLES);

        if (av_frame_get_buffer(slot.frame, 0) < 0) return true;

        final int flushed = swresample.swr_convert(
                this.swrContext,
                slot.frame.data(), slot.frame.nb_samples(),
                null, 0
        );

        if (flushed <= 0) {
            av_frame_unref(slot.frame);
            return true;
        }

        // THE FLUSHED SAMPLES CONTINUE THE LAST ENQUEUED FRAME — STAMPING THEM
        // WITH THE CONSUMER CLOCK (WHICH LAGS DECODE BY THE QUEUE DEPTH) WOULD
        // YANK THE CLOCK BACKWARD ON UPLOAD AND STALL PLAYBACK
        final double ptsSec = Double.isNaN(this.audioNextPtsSec) ? 0.0 : this.audioNextPtsSec;
        final double durationSec = (double) flushed / this.audioOutputSampleRate;
        slot.frame.nb_samples(flushed);
        slot.ptsMs = (long) (ptsSec * 1000.0);
        slot.durationMs = (long) (durationSec * 1000.0);
        slot.serial = serial;
        this.audioNextPtsSec = ptsSec + durationSec;

        this.audioFrameQueue.push();
        return true;
    }

    // VERIFIES THE DECODED FRAME STILL MATCHES THE NEGOTIATED INPUT PARAMETERS,
    // REBUILDING THE RESAMPLER WHEN A MID-STREAM CHANGE IS DETECTED. THE OUTPUT
    // SIDE (SFX ENGINE FORMAT) NEVER CHANGES — RATE CHANGES BECOME REAL RESAMPLING.
    // RETURNS FALSE IF THE FRAME CANNOT BE PLAYED (CALLER SKIPS IT).
    private boolean ensureAudioInputConfig(final AVFrame frame) {
        final int rate = frame.sample_rate();
        final int format = frame.format();
        final int channels = frame.ch_layout().nb_channels();
        if (rate == this.audioInputSampleRate && format == this.audioInputAvFormat && channels == this.audioInputChannels) {
            return true;
        }
        if (rate <= 0 || channels <= 0 || format == AV_SAMPLE_FMT_NONE) {
            if (!this.audioUnusableWarned) LOGGER.warn(IT, "Audio frame with unusable parameters: {}ch {}Hz fmt={}", channels, rate, format);
            this.audioUnusableWarned = true;
            return false;
        }

        if (!this.audioChangeWarned)
            LOGGER.warn(IT, "Audio parameters changed mid-stream: {}ch {}Hz fmt={} -> {}ch {}Hz fmt={} — rebuilding resampler",
                    this.audioInputChannels, this.audioInputSampleRate, this.audioInputAvFormat, channels, rate, format);
        this.audioChangeWarned = true;

        final SwrContext fresh = swresample.swr_alloc();
        if (fresh == null) return false;

        final AVChannelLayout inputLayout = new AVChannelLayout();
        final AVChannelLayout outputLayout = new AVChannelLayout();
        try {
            avutil.av_channel_layout_copy(inputLayout, frame.ch_layout());
            avutil.av_channel_layout_default(outputLayout, this.audioOutputChannels);

            avutil.av_opt_set_chlayout(fresh, "in_chlayout", inputLayout, 0);
            avutil.av_opt_set_int(fresh, "in_sample_rate", rate, 0);
            avutil.av_opt_set_sample_fmt(fresh, "in_sample_fmt", format, 0);

            avutil.av_opt_set_chlayout(fresh, "out_chlayout", outputLayout, 0);
            avutil.av_opt_set_int(fresh, "out_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(fresh, "out_sample_fmt", this.audioOutputAvFormat, 0);

            if (swresample.swr_init(fresh) < 0) {
                LOGGER.error(IT, "Failed to initialize resampler for new audio parameters");
                swresample.swr_free(fresh);
                return false;
            }
        } finally {
            av_channel_layout_uninit(inputLayout);
            av_channel_layout_uninit(outputLayout);
        }

        if (this.swrContext != null) swresample.swr_free(this.swrContext);
        this.swrContext = fresh;
        this.audioPassthrough = false;
        this.audioInputSampleRate = rate;
        this.audioInputAvFormat = format;
        this.audioInputChannels = channels;
        return true;
    }

    // THREAD MANAGEMENT — THE DECODERS ARE (RE)STARTED BY THE LIFECYCLE AND BY THE DEMUXER'S SEEK PATH,
    // SO THEIR CREATION AND TEARDOWN SHARE decodeLock: TWO DECODERS ON ONE CODEC CONTEXT CRASH NATIVELY
    private void stopThreads() {
        this.ioAbortRequested = true;
        if (this.videoPacketQueue != null) this.videoPacketQueue.abort();
        if (this.audioPacketQueue != null) this.audioPacketQueue.abort();
        if (this.videoFrameQueue != null) this.videoFrameQueue.abort();
        if (this.audioFrameQueue != null) this.audioFrameQueue.abort();
        final boolean wasInterrupted = Thread.interrupted();
        // THE DEMUXER GOES FIRST: ONCE IT IS GONE NOBODY ELSE CAN SPAWN A DECODER BEHIND THIS TEARDOWN
        final Thread demux = this.demuxThread;
        if (demux != null) {
            demux.interrupt();
            ThreadTool.join(demux);
        }
        this.demuxThread = null;
        this.stopDecodeThreads();
        if (wasInterrupted) Thread.currentThread().interrupt();
    }

    private void stopDecodeThreads() {
        if (this.videoPacketQueue != null) this.videoPacketQueue.abort();
        if (this.audioPacketQueue != null) this.audioPacketQueue.abort();
        final boolean wasInterrupted = Thread.interrupted();
        synchronized (this.decodeLock) {
            if (this.videoDecodeThread != null) {
                this.videoDecodeThread.interrupt();
                ThreadTool.join(this.videoDecodeThread);
            }
            if (this.audioDecodeThread != null) {
                this.audioDecodeThread.interrupt();
                ThreadTool.join(this.audioDecodeThread);
            }
            this.videoDecodeThread = null;
            this.audioDecodeThread = null;
        }
        if (wasInterrupted) Thread.currentThread().interrupt();
    }

    private void ensureDecodeThreads() {
        synchronized (this.decodeLock) {
            if ((this.videoDecodeThread == null || !this.videoDecodeThread.isAlive()) && this.gfx != null && this.videoStreamIndex >= 0 && this.videoCodecContext != null) {
                this.videoDecodeThread = this.thread("video", this::videoDecodeLoop);
                this.videoDecodeThread.start();
            }
            if ((this.audioDecodeThread == null || !this.audioDecodeThread.isAlive()) && this.sfx != null && this.audioStreamIndex >= 0 && this.audioCodecContext != null) {
                this.audioDecodeThread = this.thread("audio", this::audioDecodeLoop);
                this.audioDecodeThread.start();
            }
        }
    }

    private void syncDrain(final long targetMs, final AVPacket packet, final AVPacket trackPacket) {
        final AVFrame drainFrame = avutil.av_frame_alloc();
        final long threshold = targetMs - this.clock.frameDurationMs();
        int maxDrain = 500;
        boolean videoReached = this.gfx == null || this.videoCodecContext == null;
        boolean audioReached = this.sfx == null || this.audioCodecContext == null;

        while (maxDrain-- > 0 && !Thread.currentThread().isInterrupted()
                && !this.clock.seekPending() && (!videoReached || !audioReached)) {

            if (this.useAudioTrack) {
                if (!videoReached) {
                    videoReached = this.drainRead(this.formatContext, packet,
                            this.videoStreamIndex, this.videoCodecContext, drainFrame,
                            this.videoTimeBase, threshold);
                }
                if (!audioReached && trackPacket != null && this.trackFormatContext != null) {
                    audioReached = this.drainRead(this.trackFormatContext, trackPacket,
                            this.audioStreamIndex, this.audioCodecContext, drainFrame,
                            this.audioTimeBase, threshold);
                }
            } else {
                final int readRes = avformat.av_read_frame(this.formatContext, packet);
                if (readRes < 0) break;
                try {
                    final int idx = packet.stream_index();
                    if (idx == this.videoStreamIndex) {
                        if (!videoReached) {
                            videoReached = this.drainDecode(this.videoCodecContext, packet, drainFrame,
                                    this.videoTimeBase, threshold);
                        } else {
                            // THRESHOLD ALREADY MET — STILL FEED THE CODEC TO KEEP THE DPB INTACT
                            avcodec.avcodec_send_packet(this.videoCodecContext, packet);
                            while (avcodec.avcodec_receive_frame(this.videoCodecContext, drainFrame) >= 0) { }
                        }
                    } else if (idx == this.audioStreamIndex) {
                        if (!audioReached) {
                            audioReached = this.drainDecode(this.audioCodecContext, packet, drainFrame,
                                    this.audioTimeBase, threshold);
                        } else {
                            avcodec.avcodec_send_packet(this.audioCodecContext, packet);
                            while (avcodec.avcodec_receive_frame(this.audioCodecContext, drainFrame) >= 0) { }
                        }
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }
        }
        avutil.av_frame_free(drainFrame);
    }

    private boolean drainRead(final AVFormatContext ctx, final AVPacket pkt,
                              final int streamIndex, final AVCodecContext codec,
                              final AVFrame frame, final double timeBase, final long threshold) {
        final int res = avformat.av_read_frame(ctx, pkt);
        if (res < 0) return false;
        try {
            if (pkt.stream_index() == streamIndex) {
                return this.drainDecode(codec, pkt, frame, timeBase, threshold);
            }
        } finally {
            avcodec.av_packet_unref(pkt);
        }
        return false;
    }

    private boolean drainDecode(final AVCodecContext codec, final AVPacket pkt,
                                final AVFrame frame, final double timeBase, final long threshold) {
        avcodec.avcodec_send_packet(codec, pkt);
        while (avcodec.avcodec_receive_frame(codec, frame) >= 0) {
            if ((long) (frame.pts() * timeBase * 1000) >= threshold) return true;
        }
        return false;
    }

    private void freeQueues() {
        if (this.videoFrameQueue != null) { this.videoFrameQueue.free(); this.videoFrameQueue = null; }
        if (this.audioFrameQueue != null) { this.audioFrameQueue.free(); this.audioFrameQueue = null; }
        if (this.videoPacketQueue != null) { this.videoPacketQueue.free(); this.videoPacketQueue = null; }
        if (this.audioPacketQueue != null) { this.audioPacketQueue.free(); this.audioPacketQueue = null; }
    }

    // QUALITY SWITCH
    private void switchQuality() {
        final long currentMs = this.clock.timeMs();
        final boolean wasPaused = this.clock.pauseRequested();
        final boolean live = this.live();

        LOGGER.info(IT, "Switching quality to {} at {}ms (live={})", this.quality, currentMs, live);

        this.clock.transition(Status.BUFFERING);
        this.stopThreads();
        this.cleanup();

        this.totalSkippedFrames = 0;
        this.totalRenderedFrames = 0;

        // RESET PTS DISCONTINUITY STATE FOR NEW PIPELINE
        this.videoPtsOffset = 0;
        this.lastRawVideoPts = Double.NaN;
        this.audioPtsOffset = 0;
        this.lastRawAudioPts = Double.NaN;
        this.audioNextPtsSec = Double.NaN;
        this.lastAudioUploadSerial = -1;
        if (this.sfx != null) this.sfx.flush();

        this.videoPacketQueue.reset();
        this.audioPacketQueue.reset();
        this.videoFrameQueue.reset();
        this.audioFrameQueue.reset();

        this.clock.transition(Status.LOADING);

        this.ioAbortRequested = false; // stopThreads() RAISED IT TO DRAIN THE OLD DEMUXER; THE NEW ONE MUST OPEN
        this.demuxThread = this.thread("demux", this::demuxLoop);
        this.demuxThread.start();

        try {
            final Status postInit = this.clock.awaitNotStatus(Status.LOADING, 0);
            if (postInit == Status.ERROR) {
                return;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            // THE SWITCH WAS CUT SHORT BY stop()/RESTART — THE LIFECYCLE LANDS ON STOPPED, NOT ERROR
            return;
        }

        if (live) {
            // LIVE STREAMS: DON'T SEEK — FFMPEG STARTS AT THE LIVE EDGE.
            // SEEKING TO currentMs FAILS ON LIVE HLS AND CAUSES A FEEDBACK LOOP.
            // TRANSITION TO BUFFERING SO THE FIRST DECODED FRAME CALIBRATES THE CLOCK
            // VIA clock.update() → BUFFERING→PLAYING. WITHOUT THIS, clock.start() SETS
            // PLAYING AT PTS=0 AND STARVATION DETECTION FIRES BEFORE FRAMES ARRIVE.
            this.clock.transition(Status.BUFFERING);
            this.ensureDecodeThreads();
        } else {
            this.clock.requestSeek(currentMs, true);
        }

        if (wasPaused) {
            this.clock.paused(true);
        }

        LOGGER.info(IT, "Successfully switched quality to {}", this.quality);
    }

    private String resolveInputUrl(final URI uri, final boolean allowCache) {
        // PLAYLISTS ARE NEVER CACHED BY EXTENSION (THE CACHE ALSO REJECTS THEM BY CONTENT TYPE, BUT ONLY
        // AFTER DOWNLOADING); THE SCHEME AND CONFIG GATES LIVE INSIDE NetworkCache.readFile
        final String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (allowCache && !path.endsWith(".m3u8") && !path.endsWith(".mpd")) {
            try {
                final NetworkCache.CachedFile cached = NetworkCache.readFile(
                        uri,
                        this.source.headers(),
                        "video/*,audio/*,application/octet-stream,*/*",
                        DataTool.megabytes(Math.max(1L, WaterMediaConfig.media.ffmpeg.cacheMaxResponse)),
                        WaterMediaConfig.media.ffmpeg.cache);
                if (cached != null) {
                    LOGGER.debug(IT, "{} FFMediaPlayer cache entry for {}", cached.cached() ? "Using" : "Stored", uri);
                    return cached.path().toAbsolutePath().toString();
                }
            } catch (final IOException e) {
                LOGGER.debug(IT, "FFMediaPlayer cache bypass for {}", uri, e);
            }
        }
        // FILE URIS MUST GO THROUGH Path — STRIPPING THE LEADING SLASH ONLY WORKS
        // FOR WINDOWS DRIVE PATHS (/C:/x) AND BREAKS UNIX ABSOLUTE PATHS (/home/x)
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            try {
                return Path.of(uri).toString();
            } catch (final Exception e) {
                return uri.getPath();
            }
        }
        return uri.toString();
    }

    // OPENS THE ALREADY-ALLOCATED MAIN INPUT; FFMPEG FREES THE CONTEXT WHEN OPENING FAILS.
    // CLEAR ITS JAVA REFERENCE SO CLEANUP AND READS CANNOT TOUCH FREED MEMORY.
    private boolean openInput(final String url) {
        final AVDictionary options = new AVDictionary();
        try {
            this.applyInputOptions(options);

            final int ret = avformat.avformat_open_input(this.formatContext, url, null, options);
            if (ret < 0) {
                this.formatContext = null;
                // AN OPEN CUT SHORT BY THE INTERRUPT CALLBACK IS THE CALLER'S OWN stop(), NOT A FAILURE
                if (this.ioAbortRequested) {
                    LOGGER.debug(IT, "Open aborted by stop/restart: {}", url);
                    return false;
                }
                final String reason = FFTool.error(ret);
                LOGGER.error(IT, "Failed to open input ({}): {}", reason, url);
                this.exception(new IOException("Failed to open input (" + reason + "): " + url));
                return false;
            }
            return true;
        } finally {
            av_dict_free(options);
        }
    }

    private boolean reopenFormat() {
        final var uri = this.source.uri(this.quality);
        super.quality(this.source.qualityOf(uri));

        final String url = this.resolveInputUrl(uri, true);

        if (this.formatContext != null) {
            avformat.avformat_close_input(this.formatContext);
        }

        this.formatContext = avformat.avformat_alloc_context();
        if (this.interruptCallback != null) {
            this.formatContext.interrupt_callback().callback(this.interruptCallback);
        }

        if (!this.openInput(url)) return false;

        if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
            if (!this.ioAbortRequested) LOGGER.error(IT, "reopenFormat: failed to find stream info");
            return false;
        }

        this.cacheDurationSnapshot();

        // RE-RESOLVE STREAM INDICES — A REOPENED INPUT MAY EXPOSE STREAMS IN A
        // DIFFERENT ORDER (COMMON WITH HLS). THE CODEC CONTEXTS ARE KEPT, SO THE
        // NEW STREAMS MUST MATCH THE CODECS THEY WERE OPENED FOR.
        int newVideoIndex = -1;
        int newAudioIndex = -1;
        for (int i = 0; i < this.formatContext.nb_streams(); i++) {
            final int codecType = this.formatContext.streams(i).codecpar().codec_type();
            if (codecType == avutil.AVMEDIA_TYPE_VIDEO && newVideoIndex < 0) newVideoIndex = i;
            else if (codecType == avutil.AVMEDIA_TYPE_AUDIO && newAudioIndex < 0) newAudioIndex = i;
        }
        if (this.videoCodecContext != null) {
            if (newVideoIndex < 0 || this.formatContext.streams(newVideoIndex).codecpar().codec_id() != this.videoCodecContext.codec_id()) {
                LOGGER.error(IT, "reopenFormat: video stream missing or codec mismatch (index={})", newVideoIndex);
                return false;
            }
            this.videoStreamIndex = newVideoIndex;
            this.videoTimeBase = av_q2d(this.formatContext.streams(newVideoIndex).time_base());
        }
        if (!this.useAudioTrack && this.audioCodecContext != null) {
            if (newAudioIndex < 0 || this.formatContext.streams(newAudioIndex).codecpar().codec_id() != this.audioCodecContext.codec_id()) {
                LOGGER.error(IT, "reopenFormat: audio stream missing or codec mismatch (index={})", newAudioIndex);
                return false;
            }
            this.audioStreamIndex = newAudioIndex;
            this.audioTimeBase = av_q2d(this.formatContext.streams(newAudioIndex).time_base());
        }

        if (this.videoCodecContext != null) avcodec.avcodec_flush_buffers(this.videoCodecContext);
        if (this.audioCodecContext != null) avcodec.avcodec_flush_buffers(this.audioCodecContext);

        return true;
    }

    // ==========================================================================
    // NATIVE TLS
    // ==========================================================================
    // EXPORTS THE JVM TRUST ANCHORS AS A PEM BUNDLE AFTER CHECKING THE PATCHED TRANSPORT OPTIONS EVERY INPUT RELIES ON
    static void startTLS() throws Exception {
        // HTTP MUST RETAIN TLS OPTIONS AND CREDENTIAL ORIGINS FOR NESTED MEDIA REQUESTS.
        for (final String protocol: new String[] { "http", "https", "hls", "dash" }) {
            final boolean http = protocol.equals("http") || protocol.equals("https");
            final AVClass type;
            if (http) {
                type = avformat.avio_protocol_get_class(protocol);
            } else {
                final var format = avformat.av_find_input_format(protocol);
                type = FFTool.isNull(format) ? null : format.priv_class();
            }
            if (FFTool.isNull(type)) throw new IllegalStateException("Required native transport is unavailable: " + protocol);
            try (final var object = new PointerPointer<AVClass>(1).put(type)) {
                for (final String option: http ? new String[] { "tls_verify", "ca_file", "header_origin" } : new String[] { "protocol_opts" }) {
                    final var definition = av_opt_find(object, option, null, 0, AV_OPT_SEARCH_FAKE_OBJ);
                    if (FFTool.isNull(definition))
                        throw new IllegalStateException("FFmpeg lacks the required transport security patches; install the WaterMedia native build");
                }
            }
        }

        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        final var anchors = new TreeSet<String>();
        final var encoder = Base64.getMimeEncoder(64, new byte[] { '\n' });
        for (final var manager: factory.getTrustManagers()) {
            if (manager instanceof final X509TrustManager trust)
                for (final var certificate: trust.getAcceptedIssuers())
                    anchors.add(encoder.encodeToString(certificate.getEncoded()));
        }
        if (anchors.isEmpty()) throw new CertificateException("The JVM truststore exposes no certificate authorities");
        final var pem = new StringBuilder();
        for (final String certificate: anchors)
            pem.append("-----BEGIN CERTIFICATE-----\n").append(certificate).append("\n-----END CERTIFICATE-----\n");

        // THE BUNDLE LIVES AS LONG AS THE PROCESS; ONLY A COMPLETE ONE BECOMES AVAILABLE TO PLAYERS
        final Path bundle = Files.createTempFile("watermedia-ca-", ".pem");
        bundle.toFile().deleteOnExit();
        Files.writeString(bundle, pem, StandardCharsets.US_ASCII);
        final String file = bundle.toString();
        final var nested = new AVDictionary(null);
        final var serialized = new BytePointer((Pointer) null);
        try {
            // NATIVE ESCAPING PRESERVES WINDOWS DRIVE LETTERS AND SPECIAL CHARACTERS IN THE CA PATH.
            if (av_dict_set(nested, "tls_verify", "1", 0) < 0 || av_dict_set(nested, "ca_file", file, 0) < 0
                    || av_dict_get_string(nested, serialized, (byte) '=', (byte) ':') < 0)
                throw new IllegalStateException("Cannot configure nested HTTPS verification");
            certificates = new Certificates(file, serialized.getString(StandardCharsets.UTF_8));
        } finally {
            av_free(serialized);
            av_dict_free(nested);
        }
    }

    // REQUIRES CERTIFICATE AND PEER-NAME VERIFICATION FOR THE INPUT AND ITS NESTED CONNECTIONS
    static void configureTLS(final AVDictionary options) {
        if (options == null) throw new IllegalArgumentException("Options are required");
        final Certificates current = certificates;
        if (current == null) throw new IllegalStateException("Native HTTPS verification has not initialized");
        if (av_dict_set(options, "verify", null, 0) < 0 || av_dict_set(options, "cafile", null, 0) < 0
                || av_dict_set(options, "verifyhost", null, 0) < 0
                || av_dict_set(options, "tls_verify", "1", 0) < 0 || av_dict_set(options, "ca_file", current.file(), 0) < 0
                || av_dict_set(options, "protocol_opts", current.protocols(), 0) < 0)
            throw new IllegalStateException("Cannot configure native HTTPS verification");
    }

    // MAIN AND SEPARATE AUDIO INPUTS SHARE TRANSPORT AND PROBING POLICY.
    // FFMPEG EXPECTS MICROSECONDS/BYTES WHERE CONFIG USES MILLISECONDS/MEBIBYTES.
    private void applyInputOptions(final AVDictionary options) {
        configureTLS(options);
        av_dict_set(options, "headers", this.source.headers().toRawString(), 0);
        final long analyzeMs = WaterMediaConfig.media.ffmpeg.analyzeDuration;
        if (analyzeMs > 0) {
            av_dict_set(options, "analyzeduration", String.valueOf(analyzeMs * 1000L), 0);
        }
        av_dict_set(options, "probesize", String.valueOf(DataTool.megabytes(WaterMediaConfig.media.ffmpeg.probeSize)), 0);
        av_dict_set(options, "buffer_size", "33554432", 0);
        av_dict_set(options, "rtbufsize", "15000000", 0);
        av_dict_set(options, "http_persistent", "1", 0);
        av_dict_set(options, "multiple_requests", "0", 0);
        av_dict_set(options, "reconnect", "1", 0);
        av_dict_set(options, "reconnect_streamed", "1", 0);
        av_dict_set(options, "reconnect_delay_max", "5", 0);
        av_dict_set(options, "timeout", "10000000", 0);
        av_dict_set(options, "rtsp_transport", "tcp", 0);
        av_dict_set(options, "max_delay", "5000000", 0);
    }

    // INIT
    private boolean init() {
        try {
            final var uri = this.source.uri(this.quality);
            super.quality(this.source.qualityOf(uri));
            LOGGER.debug(IT, "Resolved quality {}", this.quality);
            final String url = this.resolveInputUrl(uri, true);

            final var audioTracks = this.source.audioTracks();
            Track audioTrack = null;
            if (this.sfx != null && !audioTracks.isEmpty()) {
                audioTrack = audioTracks.get(0);
            }

            this.formatContext = avformat.avformat_alloc_context();
            this.interruptCallback = new Callback_Pointer() {
                @Override
                public int call(final Pointer opaque) {
                    return FFMediaPlayer.this.ioAbortRequested ? 1 : 0;
                }
            };
            this.formatContext.interrupt_callback().callback(this.interruptCallback);

            LOGGER.debug(IT, "Target origin {}://{}", uri.getScheme(), uri.getHost());
            if (!this.openInput(url)) return false;

            if (avformat.avformat_find_stream_info(this.formatContext, (PointerPointer<?>) null) < 0) {
                if (this.ioAbortRequested) {
                    LOGGER.debug(IT, "Stream probe aborted by stop/restart: {}", url);
                    return false;
                }
                LOGGER.error(IT, "Failed to find stream info");
                this.exception(new IOException("Failed to find stream info: " + url));
                return false;
            }

            this.cacheDurationSnapshot();

            final String fmtName = this.formatContext.iformat() != null ? this.formatContext.iformat().name().getString() : "";
            if (url.contains(".m3u8") || fmtName.contains("hls")) {
                // ASYNC ON PURPOSE: THE PROBE COSTS 1-2 EXTRA HTTP ROUNDTRIPS AND ONLY REFINES
                // live() — UNTIL IT LANDS, THE DURATION HEURISTIC (HLS VOD PLAYLISTS CARRY A
                // DURATION, LIVE ONES DON'T) ANSWERS CORRECTLY, SO PLAYBACK NEEDN'T WAIT FOR IT.
                // IT FETCHES WITH THE SOURCE'S OWN HEADERS (AN AUTHENTICATED PLAYLIST IS COMMON) AND
                // ONLY WRITES INTO THE RUN THAT STARTED IT; A FAILED PROBE LEAVES THE HEURISTIC IN CHARGE
                final int run = this.runSerial;
                final Thread probe = this.thread("hls-probe", () -> {
                    try {
                        M3UTool.Playlist hlsResult = M3UTool.fetch(uri, this.source.headers());
                        // MASTER PLAYLIST: FOLLOW THE FIRST VARIANT (URL ALREADY ABSOLUTE) DOWN TO A REAL MEDIA PLAYLIST
                        if (hlsResult instanceof final M3UTool.Master master && !master.variants().isEmpty()) {
                            hlsResult = M3UTool.fetch(master.variants().get(0).uri(), this.source.headers());
                        }
                        if (run != this.runSerial) return;
                        if (hlsResult instanceof final M3UTool.Media media) {
                            this.hlsLiveSource = media.live();
                            LOGGER.debug(IT, "HLS probe: live={}, vod={}, totalDuration={}s", media.live(), media.vod(), media.totalDuration());
                        } else {
                            LOGGER.warn(IT, "HLS probe: inconclusive ({}), keeping the duration heuristic", hlsResult.kind());
                        }
                    } catch (final IOException e) {
                        LOGGER.warn(IT, "HLS probe failed, keeping the duration heuristic", e);
                    }
                });
                probe.start();
            }

            for (int i = 0; i < this.formatContext.nb_streams(); i++) {
                final AVStream stream = this.formatContext.streams(i);
                final int codecType = stream.codecpar().codec_type();

                if (codecType == avutil.AVMEDIA_TYPE_VIDEO && this.videoStreamIndex < 0 && this.gfx != null) {
                    this.videoStreamIndex = i;
                    this.videoTimeBase = av_q2d(stream.time_base());
                } else if (codecType == avutil.AVMEDIA_TYPE_AUDIO && this.audioStreamIndex < 0 && this.sfx != null && audioTrack == null) {
                    this.audioStreamIndex = i;
                    this.audioTimeBase = av_q2d(stream.time_base());
                }
            }

            if (audioTrack != null) {
                this.useAudioTrack = this.initAudioTrack(audioTrack);
                if (!this.useAudioTrack) {
                    LOGGER.warn(IT, "Audio track failed to open, falling back to main audio stream");
                    for (int i = 0; i < this.formatContext.nb_streams(); i++) {
                        final AVStream stream = this.formatContext.streams(i);
                        if (stream.codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO && this.audioStreamIndex < 0) {
                            this.audioStreamIndex = i;
                            this.audioTimeBase = av_q2d(stream.time_base());
                        }
                    }
                }
            }

            final boolean videoInit = this.initVideo();
            final boolean audioInit = this.initAudio();

            // FULLY DISABLE A STREAM WHOSE INIT FAILED. THE DECODE THREADS ONLY CHECK
            // streamIndex >= 0 && codecContext != null TO START, BUT A PARTIALLY-OPENED
            // PIPELINE LEAVES codecContext NON-NULL (E.G. avcodec_open2 SUCCEEDS WHILE
            // swr_init FAILS). RUNNING ON THAT HALF-INITIALIZED RESAMPLER CALLS
            // swr_get_delay ON AN UNINITIALIZED SwrContext WHOSE in_sample_rate IS 0,
            // CAUSING A NATIVE INTEGER DIVIDE-BY-ZERO (JVM CRASH). THIS HAPPENS ON HLS
            // VARIANTS WHOSE AUDIO PARAMS CANNOT BE PROBED (sample_rate=0, 0 channels).
            if (!videoInit) {
                if (this.videoCodecContext != null) {
                    avcodec.avcodec_free_context(this.videoCodecContext);
                    this.videoCodecContext = null;
                }
                this.videoStreamIndex = -1;
            }
            if (!audioInit) {
                if (this.audioCodecContext != null) {
                    avcodec.avcodec_free_context(this.audioCodecContext);
                    this.audioCodecContext = null;
                }
                if (this.swrContext != null) {
                    swresample.swr_free(this.swrContext);
                    this.swrContext = null;
                }
                this.audioStreamIndex = -1;
            }

            if (!videoInit && !audioInit)
                throw new IllegalStateException("Video and Audio failed to initialize");

            this.opened = true;
            this.playable = true;
            LOGGER.info(IT, "FFMediaPlayer started - video: {} (hw: {}), audio: {}", videoInit, this.hwAccel(), audioInit);
            return true;
        } catch (final Exception e) {
            // A SETUP CUT SHORT BY stop()/RESTART IS NOT A FAILURE WORTH RECORDING
            if (this.ioAbortRequested) return false;
            LOGGER.error(IT, "Failed to initialize FFMediaPlayer", e);
            this.exception(e);
            return false;
        }
    }

    // SNAPSHOTS THE NATIVE DURATION INTO A JAVA FIELD — PUBLIC GETTERS MUST NOT
    // TOUCH NATIVE MEMORY THAT cleanup() MAY BE FREEING CONCURRENTLY.
    private void cacheDurationSnapshot() {
        final long duration = this.formatContext.duration();
        this.mediaDurationMs = (duration == avutil.AV_NOPTS_VALUE || duration < 0) ? NO_DURATION : duration / 1000;
    }

    private boolean initAudioTrack(final Track track) {
        final var trackUri = track.uri();
        if (trackUri == null) {
            LOGGER.error(IT, "Audio track has no URI for quality {}", this.quality);
            return false;
        }

        final String trackUrl = this.resolveInputUrl(trackUri, true);

        this.trackFormatContext = avformat.avformat_alloc_context();
        if (this.interruptCallback != null) {
            this.trackFormatContext.interrupt_callback().callback(this.interruptCallback);
        }
        final AVDictionary trackOptions = new AVDictionary();

        try {
            this.applyInputOptions(trackOptions);

            if (avformat.avformat_open_input(this.trackFormatContext, trackUrl, null, trackOptions) < 0) {
                LOGGER.error(IT, "Failed to open audio track input: {}", trackUrl);
                this.trackFormatContext = null;
                return false;
            }
        } finally {
            av_dict_free(trackOptions);
        }

        if (avformat.avformat_find_stream_info(this.trackFormatContext, (PointerPointer<?>) null) < 0) {
            LOGGER.error(IT, "Failed to find stream info for audio track");
            avformat.avformat_close_input(this.trackFormatContext);
            this.trackFormatContext = null;
            return false;
        }

        for (int i = 0; i < this.trackFormatContext.nb_streams(); i++) {
            final AVStream stream = this.trackFormatContext.streams(i);
            if (stream.codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                this.audioStreamIndex = i;
                this.audioTimeBase = av_q2d(stream.time_base());
                LOGGER.debug(IT, "Audio track opened: {}", trackUrl);
                return true;
            }
        }

        LOGGER.error(IT, "No audio stream found in audio track: {}", trackUrl);
        avformat.avformat_close_input(this.trackFormatContext);
        this.trackFormatContext = null;
        return false;
    }

    private boolean initVideo() {
        if (this.gfx == null || this.videoStreamIndex < 0) {
            LOGGER.warn(IT, "No video stream found or video disabled");
            return true;
        }

        final AVStream videoStream = this.formatContext.streams(this.videoStreamIndex);

        // DETERMINE FRAME RATE — PREFER avg_frame_rate, FALL BACK TO r_frame_rate.
        // REJECT IMPLAUSIBLE VALUES: MPEG-TS (COMMON INSIDE HLS) REPORTS THE 90kHz PCR
        // CLOCK (90000fps) AS r_frame_rate UNTIL THE REAL RATE IS PROBED. FEEDING THAT
        // TO THE CLOCK COLLAPSES FRAME PACING — frameDuration AND skipThreshold ROUND
        // TO 0ms — SO WE KEEP THE CLOCK'S SANE DEFAULT WHEN NO REAL RATE IS AVAILABLE.
        final double avgFps = av_q2d(videoStream.avg_frame_rate());
        final double rFps = av_q2d(videoStream.r_frame_rate());
        if (isPlausibleFps(avgFps)) {
            this.clock.fps((float) avgFps);
            this.fps = (float) avgFps;
        } else if (isPlausibleFps(rFps)) {
            this.clock.fps((float) rFps);
            this.fps = (float) rFps;
        } else {
            // THE CLOCK KEEPS ITS PACING DEFAULT; fps() STAYS 0 BECAUSE THE RATE IS GENUINELY UNKNOWN
            LOGGER.warn(IT, "No plausible frame rate (avg={}, r={}) — keeping default {}fps", avgFps, rFps, this.clock.fps());
        }

        LOGGER.debug(IT, "Video timing: {}fps, frame duration: {}ms, skip threshold: {}ms",
                this.clock.fps(), this.clock.frameDurationMs(), this.clock.skipThresholdMs());

        final AVCodecParameters codecpar = videoStream.codecpar();
        final int codecId = codecpar.codec_id();

        final String codecName = FFTool.string(avcodec_get_name(codecId), null);
        final AVCodecDescriptor descriptor = avcodec_descriptor_get(codecId);
        final String codecLongName = descriptor != null ? FFTool.string(descriptor.long_name(), "unknown") : "unknown";
        final long bitrate = codecpar.bit_rate();
        final int profile = codecpar.profile();
        final int level = codecpar.level();
        final int pixFmt = codecpar.format();

        final var profilePointer = avcodec_profile_name(codecId, profile);
        final var fmtNamePointer = av_get_pix_fmt_name(pixFmt);

        final AVCodec decoder = avcodec_find_decoder(codecId);
        if (decoder == null)
            LOGGER.error(IT, "Failed to find video codec with id {} for videoIndex {}", codecId, this.videoStreamIndex);

        // REPORT THE ACTUAL DECODER (e.g. libdav1d vs libaom-av1 vs THE NATIVE av1 DECODER):
        // THE SAME CODEC ID RESOLVES TO DIFFERENT DECODERS PER PLATFORM BUILD, AND THEY DIFFER
        // IN REORDER DELAY AND STREAM SUPPORT — KEY CONTEXT WHEN A DECODE YIELDS NO FRAMES.
        LOGGER.debug(IT, "Video codec: {} ({}) via {}, bitrate: {} kbps, profile: {}, level: {}, pixel format: {}",
                codecName, codecLongName, decoder != null ? FFTool.string(decoder.name(), codecName) : "no decoder",
                bitrate > 0 ? bitrate / 1000 : "N/A", FFTool.string(profilePointer, profile), level, FFTool.string(fmtNamePointer, pixFmt));

        final boolean hwAllowed = WaterMediaConfig.media.ffmpeg.hardwareAccel;
        // THE DEFAULT DECODER IS KEPT FOR THE HARDWARE PATH (THE NATIVE av1 DECODER IS THE
        // HWACCEL HOST). THE SOFTWARE PATH NEEDS A REAL SOFTWARE DECODER INSTEAD — SEE
        // softwareVideoDecoder. GUARD decoder == null: initHwDecoder WOULD DEREFERENCE IT
        // NATIVELY (avcodec_get_hw_config) AND SEGFAULT THE JVM — LET THE SOFTWARE PATH FAIL CLEANLY.
        if (!hwAllowed || decoder == null || !this.initHwDecoder(decoder, videoStream)) {
            if (!hwAllowed) LOGGER.debug(IT, "Hardware acceleration disabled by config — using software decoding");

            final AVCodec swDecoder = softwareVideoDecoder(codecId, decoder);
            if (swDecoder == null) {
                LOGGER.error(IT, "No software decoder available for codec id {}", codecId);
                return false;
            }
            if (decoder == null || !Objects.equals(FFTool.string(swDecoder.name(), ""), FFTool.string(decoder.name(), "")))
                LOGGER.debug(IT, "Software decode using {}", FFTool.string(swDecoder.name(), "?"));

            this.videoCodecContext = avcodec.avcodec_alloc_context3(swDecoder);
            if (avcodec_parameters_to_context(this.videoCodecContext, videoStream.codecpar()) < 0) {
                LOGGER.error(IT, "Failed to copy video codec parameters to context");
                return false;
            }

            this.configureSwDecoderThreads(this.videoCodecContext);

            if (avcodec.avcodec_open2(this.videoCodecContext, swDecoder, (PointerPointer<?>) null) < 0) {
                LOGGER.error(IT, "Failed to open video codec");
                return false;
            }
        }

        final int w = this.videoCodecContext.width();
        final int h = this.videoCodecContext.height();
        this.sourceWidth = w;
        this.sourceHeight = h;
        this.resolveQuality(w, h);

        final FFTool.Pixels initialMapping = FFTool.pixels(this.videoCodecContext.pix_fmt());
        if (initialMapping != null) {
            // PRE-CONFIGURE THE ENGINE WITH THE UPLOAD TARGET (maxSize/LOD APPLIED) SO
            // THE FIRST FRAME DOESN'T PAY AN EXTRA RECONFIGURATION
            final int targetW = scaled(w, this.scaleWidth, this.lod.percent());
            final int targetH = scaled(h, this.scaleHeight, this.lod.percent());
            final AVCodecContext ctx = this.videoCodecContext;
            final Colorimetry color = colorimetry(ctx.pix_fmt(), w, h, ctx.colorspace(), ctx.color_range(),
                    ctx.chroma_sample_location(), ctx.color_trc(), ctx.color_primaries());
            this.uploadRefused = !this.gfx.format(initialMapping.format(), targetW, targetH, initialMapping.bits(), color);
            this.lastFormat = initialMapping.format();
            this.lastBitsPerComponent = initialMapping.bits();
            this.lastFrameWidth = targetW;
            this.lastFrameHeight = targetH;
            this.lastColor = color;
        }

        return true;
    }

    private boolean initHwDecoder(final AVCodec decoder, final AVStream videoStream) {
        for (final int hw: VIDEO_HW_CODECS) {
            for (int i = 0; ; i++) {
                final AVCodecHWConfig config = avcodec.avcodec_get_hw_config(decoder, i);
                if (config == null) break;
                if (config.device_type() != hw || (config.methods() & avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) == 0) continue;

                this.hwDeviceCtx = new AVBufferRef();
                if (av_hwdevice_ctx_create(this.hwDeviceCtx, hw, (String) null, null, 0) >= 0) {
                    this.videoCodecContext = avcodec.avcodec_alloc_context3(decoder);
                    if (avcodec_parameters_to_context(this.videoCodecContext, videoStream.codecpar()) < 0) {
                        this.cleanupHwContext();
                        continue;
                    }

                    this.videoCodecContext.hw_device_ctx(av_buffer_ref(this.hwDeviceCtx));
                    this.hwPixelFormat = config.pix_fmt();

                    if (avcodec.avcodec_open2(this.videoCodecContext, decoder, (PointerPointer<?>) null) >= 0) {
                        final BytePointer hwName = av_hwdevice_get_type_name(hw);
                        LOGGER.debug(IT, "Hardware decoder initialized: {}", FFTool.string(hwName, "unknown (" + hw + ")"));
                        IOTool.closeQuietly(hwName);
                        return true;
                    }

                    this.cleanupHwContext();
                } else {
                    this.hwDeviceCtx.releaseReference();
                    this.hwDeviceCtx = null;
                }
            }
        }

        return false;
    }

    private void cleanupHwContext() {
        if (this.videoCodecContext != null) {
            avcodec.avcodec_free_context(this.videoCodecContext);
            this.videoCodecContext = null;
        }
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;
    }

    // RESOLVES A SOFTWARE-CAPABLE VIDEO DECODER FOR THE SOFTWARE DECODE PATH.
    // avcodec_find_decoder CAN HAND BACK A HWACCEL-ONLY DECODER: THE NATIVE FFmpeg "av1"
    // DECODER HAS NO SOFTWARE DECODE PATH AND EMITS ZERO FRAMES WHEN NO HARDWARE ACCELERATOR
    // IS ACTIVE (IT ERRORS ON MOST PACKETS), WHICH SURFACES AS A VIDEO THAT INSTANTLY "ENDS".
    // FOR AV1 ON THE SOFTWARE PATH, PREFER A DEDICATED SOFTWARE DECODER (libdav1d, THEN
    // libaom-av1); FALL BACK TO THE DEFAULT WHEN THE BUILD SHIPS NEITHER.
    //
    // AV1 IS THE ONLY VIDEO CODEC THAT NEEDS THIS. ENUMERATING THE BUNDLED FFMPEG CONFIRMS
    // EVERY OTHER CODEC WITH MULTIPLE DECODERS (h264, hevc, vp8, vp9, vc1, mpeg2, mjpeg, vvc)
    // DEFAULTS TO ITS NATIVE *SOFTWARE* DECODER — ONLY AV1 DEFAULTS TO/CAN LAND ON A NON-SW
    // STUB BECAUSE FFmpeg NEVER WROTE A NATIVE SOFTWARE AV1 DECODER. THE GENERAL BACKSTOP FOR
    // ANY FUTURE STUB IS THE ZERO-FRAME → ERROR CHECK IN THE CONSUMPTION LOOP.
    private static AVCodec softwareVideoDecoder(final int codecId, final AVCodec fallback) {
        if (codecId == AV_CODEC_ID_AV1) {
            final AVCodec dav1d = avcodec_find_decoder_by_name("libdav1d");
            if (dav1d != null) return dav1d;
            final AVCodec aom = avcodec_find_decoder_by_name("libaom-av1");
            if (aom != null) return aom;
        }
        return fallback;
    }

    // SIZES THE SOFTWARE DECODER THREAD POOL TO THE FRAME AREA
    private void configureSwDecoderThreads(final AVCodecContext ctx) {
        final int width = ctx.width();
        final int height = ctx.height();
        final int pixels = width * height;

        final int threads = pixels <= 921_600
                ? Math.min(2, MAX_DECODE_THREADS) : pixels <= 2_073_600
                ? Math.min(4, MAX_DECODE_THREADS) : pixels <= 3_686_400
                ? Math.min(6, MAX_DECODE_THREADS) : MAX_DECODE_THREADS;

        ctx.thread_count(threads);
        ctx.thread_type(AVCodecContext.FF_THREAD_FRAME | AVCodecContext.FF_THREAD_SLICE);

        LOGGER.debug(IT, "Decoder threading: {} threads for {}x{}", threads, width, height);
    }

    // REPLACES A MISBEHAVING HARDWARE DECODER WITH A SOFTWARE ONE, IN PLACE.
    // CALLED FROM THE VIDEO DECODE THREAD. ONLY SWAPS this.videoCodecContext ON
    // FULL SUCCESS — ON FAILURE THE HW CONTEXT STAYS USABLE. FRAMES OF THE
    // CURRENT GOP ARE LOST UNTIL THE NEXT KEYFRAME, WHICH IS ACCEPTABLE ON AN
    // ALREADY-FAILING PIPELINE.
    private boolean reinitVideoCodecSoftware() {
        final AVStream stream = this.formatContext.streams(this.videoStreamIndex);
        final int codecId = stream.codecpar().codec_id();
        // ROUTE AROUND THE HWACCEL-ONLY NATIVE av1 DECODER ON THIS SOFTWARE FALLBACK — SEE
        // softwareVideoDecoder. WITHOUT THIS A FAILED AV1 HWACCEL WOULD FALL BACK TO A
        // DECODER THAT PRODUCES NO FRAMES.
        final AVCodec decoder = softwareVideoDecoder(codecId, avcodec_find_decoder(codecId));
        if (decoder == null) return false;

        final AVCodecContext fresh = avcodec.avcodec_alloc_context3(decoder);
        if (fresh == null) return false;
        if (avcodec_parameters_to_context(fresh, stream.codecpar()) < 0) {
            avcodec.avcodec_free_context(fresh);
            LOGGER.error(IT, "SW fallback: failed to copy codec parameters");
            return false;
        }

        this.configureSwDecoderThreads(fresh);

        if (avcodec.avcodec_open2(fresh, decoder, (PointerPointer<?>) null) < 0) {
            avcodec.avcodec_free_context(fresh);
            LOGGER.error(IT, "SW fallback: failed to open software codec");
            return false;
        }

        final AVCodecContext old = this.videoCodecContext;
        this.videoCodecContext = fresh;
        avcodec.avcodec_free_context(old);
        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;

        LOGGER.info(IT, "Software decoding fallback active");
        return true;
    }

    private boolean initAudio() {
        if (this.sfx == null || this.audioStreamIndex < 0)
            return false;

        final AVFormatContext audioContext = this.useAudioTrack ? this.trackFormatContext : this.formatContext;
        final AVStream audioStream = audioContext.streams(this.audioStreamIndex);
        final AVCodecParameters codecParams = audioStream.codecpar();
        final int codecId = codecParams.codec_id();
        final AVCodec audioCodec = avcodec.avcodec_find_decoder(codecId);

        int errorCode;
        if (audioCodec == null) {
            LOGGER.error(IT, "Failed to find audio codec with id {} for audioIndex {}", codecId, this.audioStreamIndex);
            return false;
        }

        final var audioCodecPointer = avcodec_get_name(codecId);
        final var sampleNamePointer = av_get_sample_fmt_name(codecParams.format());
        final String audioCodecName = FFTool.string(audioCodecPointer, null);
        LOGGER.debug(IT, "Audio codec: {} (id={}), channels: {}, sample_rate: {}, format: {}",
                audioCodecName, codecId,
                codecParams.ch_layout().nb_channels(),
                codecParams.sample_rate(),
                FFTool.string(sampleNamePointer, codecParams.format()));

        this.audioCodecContext = avcodec.avcodec_alloc_context3(audioCodec);
        if (this.audioCodecContext == null) {
            LOGGER.error(IT, "Failed to allocate audio codec context");
            return false;
        }

        if ((errorCode = avcodec_parameters_to_context(this.audioCodecContext, codecParams)) < 0) {
            LOGGER.error(IT, "Failed to copy audio codec parameters to context - err: {}", errorCode);
            return false;
        }

        if ((errorCode = avcodec.avcodec_open2(this.audioCodecContext, audioCodec, (PointerPointer<?>) null)) < 0) {
            LOGGER.error(IT, "Failed to open audio codec - err: {}", errorCode);
            return false;
        }

        // NEGOTIATE AUDIO FORMAT WITH SFX ENGINE
        final int srcFormat = codecParams.format();
        final int srcChannels = codecParams.ch_layout().nb_channels();
        final boolean srcPlanar = av_sample_fmt_is_planar(srcFormat) != 0;
        final SFXEngine.SampleType srcType = toSampleType(srcFormat);

        final SFXEngine.SampleType[] supTypes = this.sfx.supportedTypes();
        final SFXEngine.ChannelSupport exactEntry = this.sfx.channelSupport(srcChannels);
        final boolean exactCombinationOk = exactEntry != null && exactEntry.supports(srcType);

        this.audioOutputSampleRate = codecParams.sample_rate();

        // RECORD NEGOTIATED INPUT PARAMETERS FOR MID-STREAM CHANGE DETECTION
        this.audioInputSampleRate = codecParams.sample_rate();
        this.audioInputAvFormat = srcFormat;
        this.audioInputChannels = srcChannels;

        // PASSTHROUGH REQUIRES: INTERLEAVED SOURCE + (TYPE,CHANNELS) COMBINATION HONESTLY SUPPORTED
        if (!srcPlanar && exactCombinationOk) {
            this.audioPassthrough = true;
            this.audioOutputChannels = srcChannels;
            // KEEP A VALID OUTPUT AV FORMAT EVEN IN PASSTHROUGH — A MID-STREAM
            // PARAMETER CHANGE DEMOTES PASSTHROUGH TO THE RESAMPLE PATH, WHICH
            // CONVERTS TOWARD THIS FORMAT (THE ENGINE FORMAT CANNOT CHANGE).
            this.audioOutputAvFormat = sampleTypeToAvFormat(srcType);
            LOGGER.debug(IT, "Audio passthrough: type={}, channels={}, rate={}", srcType, srcChannels, this.audioOutputSampleRate);
            if (!this.sfx.format(srcType, srcChannels, this.audioOutputSampleRate)) {
                LOGGER.error(IT, "SFX engine rejected passthrough format: type={}, ch={}, rate={}", srcType, srcChannels, this.audioOutputSampleRate);
                return false;
            }
            return true;
        }

        // RESAMPLE — PICK TARGET FROM THE SUPPORT TABLE:
        //   CHANNELS: exact match if possible, else closest supported count
        //   TYPE:     preserve if supported AT TARGET CHANNEL COUNT, else fallback (prefers S16)
        this.audioPassthrough = false;
        // UNKNOWN LAYOUT (0 CHANNELS) NEGOTIATES AS THE STEREO THE INPUT LAYOUT DEFAULTS TO BELOW —
        // closestChannelSupport(0) WOULD PICK MONO AGAINST AN ASSUMED-STEREO INPUT
        final SFXEngine.ChannelSupport targetEntry = exactEntry != null ? exactEntry
                : this.sfx.closestChannelSupport(srcChannels > 0 ? srcChannels : 2);
        if (targetEntry == null) {
            LOGGER.error(IT, "SFX engine exposes an empty channel-support table");
            return false;
        }
        this.audioOutputChannels = targetEntry.channels();

        final SFXEngine.SampleType outType;
        if (targetEntry.supports(srcType)) {
            outType = srcType;
        } else {
            outType = fallbackType(supTypes, targetEntry);
            if (outType == null) {
                LOGGER.error(IT, "SFX engine exposes no supported types for {} channels", this.audioOutputChannels);
                return false;
            }
        }
        this.audioOutputAvFormat = sampleTypeToAvFormat(outType);

        LOGGER.debug(IT, "Audio resample ({}): {} {}ch {}Hz → {} {}ch {}Hz", srcPlanar ? "de-planarize only" : "format conversion",
                FFTool.string(av_get_sample_fmt_name(srcFormat), srcFormat),
                srcChannels, this.audioOutputSampleRate,
                outType, this.audioOutputChannels, this.audioOutputSampleRate);

        this.swrContext = swresample.swr_alloc();
        if (this.swrContext == null) {
            LOGGER.error(IT, "Failed to allocate SWResampler context");
            return false;
        }

        final AVChannelLayout inputLayout = new AVChannelLayout();
        final AVChannelLayout outputLayout = new AVChannelLayout();

        try {
            if (srcChannels > 0) {
                avutil.av_channel_layout_copy(inputLayout, codecParams.ch_layout());
            } else {
                LOGGER.warn(IT, "Audio codec has no channel layout info, defaulting to stereo");
                avutil.av_channel_layout_default(inputLayout, 2);
            }

            avutil.av_channel_layout_default(outputLayout, this.audioOutputChannels);

            avutil.av_opt_set_chlayout(this.swrContext, "in_chlayout", inputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "in_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "in_sample_fmt", srcFormat, 0);

            avutil.av_opt_set_chlayout(this.swrContext, "out_chlayout", outputLayout, 0);
            avutil.av_opt_set_int(this.swrContext, "out_sample_rate", this.audioOutputSampleRate, 0);
            avutil.av_opt_set_sample_fmt(this.swrContext, "out_sample_fmt", sampleTypeToAvFormat(outType), 0);

            if (swresample.swr_init(this.swrContext) < 0) {
                LOGGER.error(IT, "Failed to initialize SWResampler context");
                return false;
            }
        } finally {
            av_channel_layout_uninit(inputLayout);
            av_channel_layout_uninit(outputLayout);
        }

        if (!this.sfx.format(outType, this.audioOutputChannels, this.audioOutputSampleRate)) {
            LOGGER.error(IT, "SFX engine rejected resample output format: type={}, ch={}, rate={}", outType, this.audioOutputChannels, this.audioOutputSampleRate);
            return false;
        }

        return true;
    }

    // SWS CONTEXT (FORMAT CONVERSION AND/OR DOWNSCALE)
    private void recreateSwsContext(final int srcWidth, final int srcHeight, final int srcFormat,
                                    final int dstWidth, final int dstHeight, final int dstFormat) {
        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }
        this.swsColor = null;

        // SWS_AREA AVERAGES THE WHOLE SOURCE BLOCK PER OUTPUT PIXEL — THE RIGHT
        // QUALITY/COST TRADE FOR STRONG LOD DOWNSCALES; PURE FORMAT CONVERSIONS
        // KEEP THE CHEAPER FAST BILINEAR.
        final int flags = dstWidth != srcWidth || dstHeight != srcHeight
                ? swscale.SWS_AREA : swscale.SWS_FAST_BILINEAR;
        this.swsContext = swscale.sws_getContext(
                srcWidth, srcHeight, srcFormat,
                dstWidth, dstHeight, dstFormat,
                flags, null, null, (double[]) null
        );

        final String srcName = FFTool.string(av_get_pix_fmt_name(srcFormat), srcFormat);
        final String dstName = FFTool.string(av_get_pix_fmt_name(dstFormat), dstFormat);

        if (this.swsContext == null) {
            LOGGER.error(IT, "Failed to create SwsContext: {} ({}x{}) -> {} ({}x{})",
                    srcName, srcWidth, srcHeight, dstName, dstWidth, dstHeight);
        } else {
            this.swsInputFormat = srcFormat;
            this.swsInputWidth = srcWidth;
            this.swsInputHeight = srcHeight;
            LOGGER.debug(IT, "SwsContext: {} ({}x{}) -> {} ({}x{})",
                    srcName, srcWidth, srcHeight, dstName, dstWidth, dstHeight);
        }
    }

    // CLEANUP
    private void cleanup() {
        LOGGER.debug(IT, "Cleaning up FFMPEG resources...");

        if (this.hwDeviceCtx != null) {
            av_buffer_unref(this.hwDeviceCtx);
            this.hwDeviceCtx = null;
        }
        this.hwPixelFormat = AV_PIX_FMT_NONE;

        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }
        this.swsColor = null;
        if (this.swrContext != null) {
            swresample.swr_free(this.swrContext);
            this.swrContext = null;
        }
        if (this.videoCodecContext != null) {
            // FLUSH BEFORE FREE: A FRAME-THREADED DECODER (E.G. THE MULTI-THREAD SOFTWARE FALLBACK)
            // INTERRUPTED MID-DECODE CAN LEAVE ITS ASYNC PIPELINE UNBALANCED, WHICH TRIPS
            // av_assert0(fctx->async_lock) IN pthread_frame.c ON avcodec_free_context. FLUSHING DRAINS
            // THE WORKER THREADS AND RESETS THAT STATE FIRST.
            avcodec.avcodec_flush_buffers(this.videoCodecContext);
            avcodec.avcodec_free_context(this.videoCodecContext);
            this.videoCodecContext = null;
        }
        if (this.audioCodecContext != null) {
            avcodec.avcodec_free_context(this.audioCodecContext);
            this.audioCodecContext = null;
        }
        if (this.formatContext != null) {
            try {
                avformat.avformat_close_input(this.formatContext);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Error closing format context", e);
            }
            this.formatContext = null;
        }
        if (this.trackFormatContext != null) {
            try {
                avformat.avformat_close_input(this.trackFormatContext);
            } catch (final Exception e) {
                LOGGER.warn(IT, "Error closing track format context", e);
            }
            this.trackFormatContext = null;
        }
        this.useAudioTrack = false;
        this.interruptCallback = null;

        if (this.scaledFrame != null) {
            avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = null;
        }

        // FREE THE PLANE POOL ON EVERY TEARDOWN SO A STOPPED-BUT-KEPT PLAYER RETAINS NO FRAME MEMORY
        // (UP TO 4 FULL FRAME SETS). SAFE FOR VULKAN: release(buffer) DROPS THE ENGINE'S HOST IMPORTS
        // (DRAINING PENDING GPU READS) BEFORE EACH BUFFER IS FREED. release() CALLS THIS AGAIN AS A
        // SAFETY NET — IDEMPOTENT.
        this.freePlanePool();
        this.lastFormat = null;
        this.lastColor = null;
        this.colorTags = -1L;
        this.uploadRefused = false;
        this.lastBitsPerComponent = 0;

        this.opened = false;
        this.fps = 0f;
        this.videoStreamIndex = -1;
        this.audioStreamIndex = -1;
        this.swsInputFormat = AV_PIX_FMT_NONE;
        this.swsInputWidth = 0;
        this.swsInputHeight = 0;
        this.audioInputSampleRate = 0;
        this.audioInputAvFormat = AV_SAMPLE_FMT_NONE;
        this.audioInputChannels = 0;

        LOGGER.debug(IT, "Cleanup completed");
    }

    // UTILITY
    // AN UNSTARTED PIPELINE THREAD OF THIS PLAYER'S GROUP, SO A CALLER CAN PUBLISH IT IN ITS FIELD BEFORE IT RUNS
    private Thread thread(final String role, final Runnable task) {
        final Thread thread = THREADS.newThread(task);
        thread.setName(this.group + "-" + role);
        return thread;
    }

    // LOGS THE FIRST UNEXPECTED send/receive FAILURE OF A DECODE LOOP WITH ITS CODEC AND NATIVE DETAIL
    private static void logCodecError(final String media, final String operation, final AVCodecContext codec, final int stream, final int code) {
        LOGGER.warn(IT, "{} decoder {} failed: codec={}, stream={}, code={} ({})",
                media, operation, FFTool.string(avcodec_get_name(codec.codec_id()), "unknown"), stream, code, FFTool.error(code));
    }
}
