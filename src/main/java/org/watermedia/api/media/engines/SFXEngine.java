package org.watermedia.api.media.engines;

import java.nio.ByteBuffer;

/**
 * Sound engine abstraction for uploading decoded audio data to playback systems.
 * <p>
 * WATERMeDIA decodes audio, uploads sample data, and exposes a source handle.
 * The developer controls playback through that handle.
 * <p>
 * Implementations are backend-specific (OpenAL, etc.).
 */
public abstract class SFXEngine {
    // AUDIO CHANNELS
    public static final int MONO = 1;
    public static final int STEREO = 2;

    // COMMON SAMPLE RATES
    public static final int SAMPLE_RATE_8000 = 8000;
    public static final int SAMPLE_RATE_11025 = 11025;
    public static final int SAMPLE_RATE_16000 = 16000;
    public static final int SAMPLE_RATE_22050 = 22050;
    public static final int SAMPLE_RATE_44100 = 44100;
    public static final int SAMPLE_RATE_48000 = 48000;
    public static final int SAMPLE_RATE_96000 = 96000;

    protected int sampleFormat;
    protected int channels;
    protected int sampleRate;

    /**
     * Reconfigures the engine for a new audio format.
     * Must be called before the first upload and whenever the audio format changes.
     * @param sampleFormat sample format identifier (backend-specific)
     * @param channels     number of audio channels
     * @param sampleRate   sample rate in Hz
     */
    public void setAudioFormat(final int sampleFormat, final int channels, final int sampleRate) {
        this.sampleFormat = sampleFormat;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    /** Current sample format. */
    public int sampleFormat() { return this.sampleFormat; }

    /** Number of audio channels. */
    public int channels() { return this.channels; }

    /** Sample rate in Hz. */
    public int sampleRate() { return this.sampleRate; }

    /**
     * Generates audio buffers and returns a new source handle.
     * @return the source handle for audio playback
     */
    public abstract int genSource();

    /**
     * Pauses audio playback on the given source.
     * @param source the source handle
     */
    public abstract void pause(final int source);

    /**
     * Starts or resumes audio playback on the given source.
     * @param source the source handle
     */
    public abstract void play(final int source);

    /**
     * Sets the playback speed (pitch) of the given source.
     * @param source the source handle
     * @param speed  playback speed multiplier (1.0 = normal)
     */
    public abstract void speed(final int source, final float speed);

    /**
     * Sets the volume (gain) of the given source.
     * @param source the source handle
     * @param volume volume level (0.0 = silent, 1.0 = full)
     */
    public abstract void volume(final int source, final float volume);

    /**
     * Releases all resources associated with the given source.
     * The source is unusable after this call.
     * @param source the source handle
     */
    public abstract void release(final int source);

    /**
     * Uploads audio data to the playback system. Non-blocking: returns false if no buffer
     * is available (caller should retry later).
     * @param source  the source handle
     * @param data    direct ByteBuffer with audio sample data
     * @param format  sample format identifier
     * @param samples sample rate of the data
     * @return true if the data was queued, false if no buffer was available
     */
    public abstract boolean upload(final int source, final ByteBuffer data, final int format, final int samples);
}
