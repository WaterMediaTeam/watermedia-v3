package org.watermedia.api.media.engines;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Native, dependency-free {@link SFXEngine} backed by the Java Sound API
 * ({@code javax.sound.sampled}). It plays decoded PCM straight through the operating-system
 * mixer (WASAPI/DirectSound, ALSA/PulseAudio, CoreAudio), so it needs no OpenAL context and
 * no external library.
 * <p>
 * A first-class, user-selectable alternative to {@link ALEngine} that needs no OpenAL context.
 * Unlike it, this engine is stream-based: {@link #format(SampleType, int, int)} opens a fresh
 * {@link SourceDataLine} and reconfiguration reopens it. Uploads are non-blocking — the line's
 * internal buffer is the backpressure, mirroring {@code ALEngine}'s buffer pool, so the media
 * clock keeps tracking the audible position via {@link #pendingMs()}.
 * <p>
 * Java Sound backend limitations: playback speed has no portable equivalent — {@link #speed(float)}
 * refuses and audio stays at 1.0×; there is no per-source spatialization, so {@link #source()}
 * reports no handle.
 */
public final class JSEngine extends SFXEngine {
    private static final Marker IT = MarkerManager.getMarker(JSEngine.class.getSimpleName());

    // INTERNAL LINE BUFFER DEPTH IN MS — RIDES OVER GAME HITCHES WITHOUT UNDERRUNS; THE CLOCK
    // FOLLOWS THE AUDIBLE POSITION VIA pendingMs() (SAME RATIONALE AS ALEngine's BUFFER POOL).
    /** Default internal line buffer depth used by {@code MediaAPI.jsEngine()}. */
    public static final int DEFAULT_BUFFER_MS = 300;

    // CAPABILITY TABLES: JAVA SOUND SUPPORTS U8/S16 PCM MONO/STEREO ON EVERY PLATFORM; IT
    // DOESN'T SUPPORT FLOAT, S32 NOR MULTICHANNEL THROUGH THE DEFAULT MIXER, SO NEGOTIATION
    // ALWAYS FALLS BACK INSIDE THIS SET (THERE IS NO SECOND FALLBACK IF format() REJECTS).
    // formatFor() STILL MAPS S32/FLT SO A DIRECT CALLER MAY USE THEM WHEN ITS MIXER ALLOWS.
    private static final SampleType[] SUPPORTED_TYPES = { SampleType.U8, SampleType.S16 };
    private static final ChannelSupport[] SUPPORTED_CHANNELS = {
            new ChannelSupport(1, SampleType.U8, SampleType.S16), // MONO
            new ChannelSupport(2, SampleType.U8, SampleType.S16), // STEREO
    };

    private final int bufferMs;
    // LINE + CONTROL ARE REPLACED ON RECONFIGURE (format()) FROM THE PLAYER'S LIFECYCLE
    // THREAD AND READ FROM THE CONTROL THREAD (volume/pause) — HENCE volatile.
    private volatile SourceDataLine line;
    private volatile FloatControl gainControl;
    private volatile float gain = 1.0f; // LAST REQUESTED LINEAR VOLUME, RE-APPLIED ON REOPEN
    private volatile boolean started;
    // PENDING-AUDIO BOOKKEEPING (pendingMs) — SINGLE-THREADED WITH upload/flush ON THE LIFECYCLE
    // THREAD. framesWritten - line.getLongFramePosition() = FRAMES WRITTEN BUT NOT YET AUDIBLE.
    private long framesWritten;
    private long playedBaseUs; // PLAYBACK EPOCH BASE — REBASED ON flush() SO playbackMs() IS PER-QUEUE LIKE ALEngine
    private int bytesPerFrame;
    private byte[] scratch = new byte[0]; // REUSABLE COPY TARGET FOR ByteBuffer → line.write

    public JSEngine(final int bufferMs) {
        if (bufferMs <= 0) throw new IllegalArgumentException("bufferMs must be positive, got " + bufferMs);
        this.bufferMs = bufferMs;
        // NO NATIVE SOURCE HANDLE — JAVA SOUND HAS NO OPENAL-STYLE SOURCE ID (source() STAYS 0)
    }

    @Override
    public void pause() {
        final SourceDataLine l = this.line;
        if (l != null && l.isActive()) l.stop();
    }

    @Override
    public void play() {
        final SourceDataLine l = this.line;
        if (l == null) return;
        // A STARTED LINE RENDERS WHATEVER IS WRITTEN AND AUTO-RESUMES AFTER AN UNDERRUN, SO
        // START ONCE PER PAUSE CYCLE. isActive() IS true FROM start() UNTIL stop().
        if (!l.isActive()) l.start();
        this.started = true;
    }

    @Override
    public boolean speed(final float speed) {
        return false; // JAVA SOUND DOESN'T SUPPORT SPEED — REFUSED, PLAYBACK STAYS AT 1.0×
    }

    @Override
    public void volume(final float volume) {
        this.gain = volume;
        this.applyGain();
    }

    @Override
    public void release() {
        final SourceDataLine l = this.line;
        if (l != null) {
            l.stop();
            l.flush();
            l.close();
            this.line = null;
        }
        // DROP THE CONTROL AND PLAYBACK FLAG SO A LATE volume()/playbackMs() CAN'T DRIVE A CLOSED LINE
        this.gainControl = null;
        this.started = false;
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
        final AudioFormat fmt = formatFor(type, channels, sampleRate);
        if (fmt == null) return false;

        // REUSE THE OPEN LINE ON AN IDENTICAL RECONFIGURE (MIRRORS ALEngine's STATE-ONLY UPDATE)
        final SourceDataLine cur = this.line;
        if (cur != null && cur.isOpen() && fmt.matches(cur.getFormat())) {
            this.sampleType = type;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bytesPerFrame = fmt.getFrameSize();
            return true;
        }

        // STREAM-BASED BACKEND: OPEN A FRESH LINE FOR THE NEW FORMAT. BUFFER DEPTH ≈ bufferMs.
        final int frameSize = fmt.getFrameSize();
        final SourceDataLine fresh;
        try {
            fresh = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            int depth = (int) ((long) sampleRate * frameSize * this.bufferMs / 1000L);
            depth -= depth % frameSize; // FRAME-ALIGN THE REQUESTED BUFFER
            fresh.open(fmt, Math.max(frameSize, depth));
        } catch (final Exception e) {
            // NO OPENABLE LINE FOR THIS FORMAT (NO DEVICE, UNSUPPORTED COMBINATION, ...) — REPORT
            // NO AUDIO INSTEAD OF CRASHING PLAYER CREATION
            LOGGER.error(IT, "Java Sound line unavailable for type={} ch={} rate={}", type, channels, sampleRate, e);
            return false;
        }

        if (cur != null) {
            cur.stop();
            cur.flush();
            cur.close();
        }
        this.line = fresh;
        this.gainControl = fresh.isControlSupported(FloatControl.Type.MASTER_GAIN)
                ? (FloatControl) fresh.getControl(FloatControl.Type.MASTER_GAIN) : null;
        this.sampleType = type;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.bytesPerFrame = frameSize;
        this.framesWritten = 0L;
        this.playedBaseUs = 0L; // FRESH LINE, FRESH PLAYBACK EPOCH
        this.started = false;
        this.applyGain(); // RE-APPLY THE LAST REQUESTED VOLUME TO THE NEW LINE
        return true;
    }

    @Override
    public boolean upload(final ByteBuffer data) {
        final SourceDataLine l = this.line;
        if (l == null) return false; // NOT CONFIGURED — CALLER BUG (LIKE ALEngine's UNINITIALIZED STATE)
        final int len = data.remaining();
        if (len == 0) return true;
        // NON-BLOCKING: available() < len MEANS THE LINE IS FULL — BACKPRESSURE, LIKE ALEngine's
        // EXHAUSTED POOL. THE MIXER ONLY FREES SPACE, SO A len ≤ available() WRITE CANNOT BLOCK.
        if (l.available() < len) return false;
        if (this.scratch.length < len) this.scratch = new byte[len];
        data.get(this.scratch, 0, len);
        l.write(this.scratch, 0, len);
        if (this.bytesPerFrame > 0) this.framesWritten += len / this.bytesPerFrame;
        return true;
    }

    @Override
    public void flush() {
        final SourceDataLine l = this.line;
        if (l == null) return;
        // STOP + DISCARD (SEEK/QUALITY SWITCH): FLUSHED FRAMES NEVER ADVANCE getLongFramePosition(),
        // SO ALIGNING framesWritten TO IT ZEROES pendingMs() CORRECTLY.
        l.stop();
        l.flush();
        this.framesWritten = l.getLongFramePosition();
        // NEW PLAYBACK EPOCH: playbackMs() MEASURES WITHIN THE POST-FLUSH QUEUE AND RETURNS -1
        // UNTIL play() RESTARTS IT, MIRRORING ALEngine's REWOUND SOURCE
        this.playedBaseUs = l.getMicrosecondPosition();
        this.started = false;
    }

    @Override
    public long pendingMs() {
        final SourceDataLine l = this.line;
        if (l == null || this.sampleRate <= 0) return 0;
        final long pendingFrames = this.framesWritten - l.getLongFramePosition();
        return pendingFrames <= 0 ? 0 : pendingFrames * 1000L / this.sampleRate;
    }

    @Override
    public long playbackMs() {
        final SourceDataLine l = this.line;
        if (l == null || !this.started) return -1;
        // RENDERED (AUDIBLE) POSITION WITHIN THE CURRENT EPOCH (SINCE OPEN OR LAST flush()).
        // JAVA SOUND EXPOSES NO DEVICE-LATENCY OFFSET, SO THIS IS NOT LATENCY-COMPENSATED.
        return (l.getMicrosecondPosition() - this.playedBaseUs) / 1000L;
    }

    // APPLIES THE LAST REQUESTED LINEAR GAIN TO THE CURRENT LINE VIA MASTER_GAIN (IN DECIBELS).
    // NO-OP WHEN THE MIXER EXPOSES NO GAIN CONTROL.
    private void applyGain() {
        final FloatControl c = this.gainControl;
        if (c == null) return;
        if (this.gain <= 0.0f) {
            c.setValue(c.getMinimum()); // SILENCE
            return;
        }
        // MASTER_GAIN IS LOGARITHMIC: dB = 20·log10(linearGain), CLAMPED TO THE CONTROL RANGE
        final float db = (float) (20.0 * Math.log10(this.gain));
        c.setValue(Math.min(c.getMaximum(), Math.max(c.getMinimum(), db)));
    }

    // MAPS TYPE + CHANNELS + RATE TO A LITTLE-ENDIAN AudioFormat; RETURNS null FOR DBL,
    // WHICH HAS NO PORTABLE 64-BIT PCM ENCODING.
    private static AudioFormat formatFor(final SampleType type, final int channels, final int rate) {
        final AudioFormat.Encoding enc;
        final int bits;
        switch (type) {
            case U8  -> { enc = AudioFormat.Encoding.PCM_UNSIGNED; bits = 8; }
            case S16 -> { enc = AudioFormat.Encoding.PCM_SIGNED;   bits = 16; }
            case S32 -> { enc = AudioFormat.Encoding.PCM_SIGNED;   bits = 32; }
            case FLT -> { enc = AudioFormat.Encoding.PCM_FLOAT;    bits = 32; }
            default  -> { return null; } // DBL — NO PORTABLE JAVA SOUND ENCODING
        }
        final int frameSize = channels * (bits / 8);
        return new AudioFormat(enc, rate, bits, channels, frameSize, rate, false);
    }

}
