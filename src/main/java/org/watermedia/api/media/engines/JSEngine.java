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
 * Intended as a provisional fallback for when the OpenAL device is unavailable. Unlike
 * {@link ALEngine} it is stream-based: {@link #setAudioFormat} opens a fresh
 * {@link SourceDataLine} and reconfiguration reopens it. Uploads are non-blocking — the line's
 * internal buffer is the backpressure, mirroring {@code ALEngine}'s buffer pool, so the media
 * clock keeps tracking the audible position via {@link #pendingMs()}.
 * <p>
 * Java Sound backend limitations: playback speed has no portable equivalent — {@link #speed()}
 * reports {@code false} and {@link #speed(float)} is a no-op (audio stays at 1.0×); there is no
 * per-source spatialization, so {@link #source()} reports no handle.
 */
public final class JSEngine extends SFXEngine {
    private static final Marker IT = MarkerManager.getMarker(JSEngine.class.getSimpleName());

    // INTERNAL LINE BUFFER DEPTH IN MS. THE DEPTH ABSORBS GAME HITCHES (GC, CHUNK LOADS)
    // WITHOUT UNDERRUNNING — THE MEDIA CLOCK FOLLOWS THE AUDIBLE POSITION VIA pendingMs(), SO
    // EXTRA DEPTH DOESN'T DESYNC A/V (SAME RATIONALE AS ALEngine's 8-BUFFER POOL).
    private static final int DEFAULT_BUFFER_MS = 300;

    // CAPABILITY TABLES — STATIC AND DELIBERATELY CONSERVATIVE.
    // JAVA SOUND OPENS 8/16-BIT SIGNED/UNSIGNED PCM MONO/STEREO ON EVERY PLATFORM; 32-BIT,
    // FLOAT AND MULTICHANNEL LINES ARE NOT PORTABLE THROUGH THE DEFAULT MIXER. ADVERTISING ONLY
    // THE UNIVERSAL SET GUARANTEES THE FORMAT NEGOTIATION (SEE FFMediaPlayer#initAudio) ALWAYS
    // LANDS ON A COMBINATION setAudioFormat CAN ACTUALLY OPEN — THERE IS NO NEGOTIATION FALLBACK
    // IF THE ENGINE REJECTS THE CHOSEN FORMAT. formatFor() STILL MAPS S32/FLT SO A DIRECT CALLER
    // MAY USE THEM WHEN ITS MIXER SUPPORTS THEM.
    private static final SampleType[] SUPPORTED_TYPES = { SampleType.U8, SampleType.S16 };
    private static final ChannelSupport[] SUPPORTED_CHANNELS = {
            new ChannelSupport(1, SampleType.U8, SampleType.S16), // MONO
            new ChannelSupport(2, SampleType.U8, SampleType.S16), // STEREO
    };

    private final int bufferMs;
    // LINE + CONTROL ARE REPLACED ON RECONFIGURE (setAudioFormat) FROM THE PLAYER'S LIFECYCLE
    // THREAD AND READ FROM THE CONTROL THREAD (volume/pause) — HENCE volatile.
    private volatile SourceDataLine line;
    private volatile FloatControl gainControl;
    private volatile float gain = 1.0f; // LAST REQUESTED LINEAR VOLUME, RE-APPLIED ON REOPEN
    private volatile boolean started;
    // PENDING-AUDIO BOOKKEEPING (pendingMs) — SINGLE-THREADED WITH upload/flush ON THE LIFECYCLE
    // THREAD. framesWritten - line.getLongFramePosition() = FRAMES WRITTEN BUT NOT YET AUDIBLE.
    private long framesWritten;
    private int bytesPerFrame;
    private byte[] scratch = new byte[0]; // REUSABLE COPY TARGET FOR ByteBuffer → line.write

    private JSEngine(final int bufferMs) {
        // CONSTRUCTION IS FORCED THROUGH Builder/buildDefault, WHICH ALREADY VALIDATE bufferMs > 0
        this.bufferMs = bufferMs;
        // NO NATIVE SOURCE HANDLE — JAVA SOUND HAS NO OPENAL-STYLE SOURCE ID (source() STAYS 0)
    }

    @Override
    protected int genSource() {
        return 0; // NO HANDLE
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
    public boolean speed() {
        return false; // NO PORTABLE PITCH/RATE CONTROL — speed(float) IS A NO-OP
    }

    @Override
    public void speed(final float speed) {
        // JAVA SOUND HAS NO PORTABLE PITCH/RATE CONTROL ON A SourceDataLine — HONORING SPEED
        // WOULD ALSO BREAK THE pendingMs() FRAME MATH (WHICH ASSUMES THE OPEN RATE). AS A
        // PROVISIONAL BACKEND PLAYBACK STAYS AT 1.0×; USE ALEngine FOR SPEED CONTROL.
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
    public boolean setAudioFormat(final SampleType type, final int channels, final int sampleRate) {
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
        // NON-BLOCKING: available() IS THE FREE SPACE WRITABLE WITHOUT BLOCKING. BELOW len MEANS
        // THE LINE IS FULL — SIGNAL BACKPRESSURE (ANALOGOUS TO ALEngine's EXHAUSTED BUFFER POOL)
        // SO THE CONSUMPTION THREAD DOESN'T STALL ON write(). THE MIXER ONLY FREES SPACE
        // CONCURRENTLY, SO A len ≤ available() WRITE CANNOT BLOCK.
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
        // STOP + DISCARD BUFFERED AUDIO (SEEK/QUALITY SWITCH). REBASE THE PENDING BASELINE:
        // FLUSHED FRAMES WERE COUNTED IN framesWritten BUT NEVER ADVANCE getLongFramePosition(),
        // SO ALIGNING framesWritten TO THE CURRENT POSITION ZEROES pendingMs() CORRECTLY.
        l.stop();
        l.flush();
        this.framesWritten = l.getLongFramePosition();
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
        // getMicrosecondPosition() IS THE RENDERED (AUDIBLE) POSITION SINCE THE LINE OPENED.
        // JAVA SOUND EXPOSES NO DEVICE-LATENCY OFFSET, SO THIS IS NOT LATENCY-COMPENSATED.
        return l.getMicrosecondPosition() / 1000L;
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

    // MAPS A CANONICAL SAMPLE TYPE + CHANNEL COUNT + RATE TO A JAVA SOUND AudioFormat.
    // CANONICAL TYPES ARE LITTLE-ENDIAN (ENDIANNESS IS MOOT FOR 8-BIT). RETURNS null FOR DBL,
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

    /**
     * Creates a {@code JSEngine} with the default internal buffer depth ({@value DEFAULT_BUFFER_MS} ms).
     * The caller must invoke {@link #setAudioFormat(SampleType, int, int)} before uploading.
     * @return a ready-to-configure Java Sound engine
     */
    public static JSEngine buildDefault() {
        return new Builder().build();
    }

    /**
     * Builder for {@link JSEngine}. Kept for consistency with the {@code SFXEngine}/{@code GFXEngine}
     * construction pattern. The audio format is declared later via
     * {@link JSEngine#setAudioFormat(SampleType, int, int)}.
     */
    public static final class Builder {
        private int bufferMs = DEFAULT_BUFFER_MS;

        public Builder bufferMs(final int bufferMs) {
            this.bufferMs = bufferMs;
            return this;
        }

        public JSEngine build() {
            if (this.bufferMs <= 0) {
                throw new IllegalArgumentException("bufferMs must be positive, got " + this.bufferMs);
            }
            return new JSEngine(this.bufferMs);
        }
    }
}
