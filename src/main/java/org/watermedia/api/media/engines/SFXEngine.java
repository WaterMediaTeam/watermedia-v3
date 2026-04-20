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
    /** Minimum reasonable sample rate (sub-telephony threshold, below is garbage). */
    public static final int MIN_SAMPLE_RATE = 4000;
    /** Maximum reasonable sample rate (covers DXD and any hi-res consumer content). */
    public static final int MAX_SAMPLE_RATE = 384_000;

    /**
     * Canonical PCM sample type — backend-independent.
     * Planar/packed layout is handled separately via {@link #supportsPlanar()}/{@link #supportsInterleaved()}.
     */
    public enum SampleType {
        /** Unsigned 8-bit integer. */
        U8,
        /** Signed 16-bit integer, little-endian. */
        S16,
        /** Signed 32-bit integer, little-endian. */
        S32,
        /** IEEE 754 32-bit float, little-endian. Expected range [-1.0, +1.0]. */
        FLT,
        /** IEEE 754 64-bit float, little-endian. Expected range [-1.0, +1.0]. */
        DBL
    }

    protected int source;
    protected SampleType sampleType;
    protected int channels;
    protected int sampleRate;

    /**
     * Returns the channel-support table as a packed {@code long[]}, one entry per supported channel count.
     * <p>
     * Each long encodes:
     * <ul>
     *   <li>byte 7 (MSB): channel count (1..8)</li>
     *   <li>byte 6: support flag for {@code supportedTypes()[0]} — {@code 0xFF} = supported, {@code 0x00} = not</li>
     *   <li>byte 5: support flag for {@code supportedTypes()[1]}</li>
     *   <li>... (up to 7 types total, in the order declared by {@link #supportedTypes()})</li>
     * </ul>
     * Use {@code org.watermedia.tools.DataTool#bytesAt(long, int)} to decode positions.
     * <p>
     * This encoding expresses non-rectangular support (e.g. {@link SampleType#DBL} working for mono/stereo
     * but not for multichannel in OpenAL). The caller reads both tables to pick an exact passthrough
     * combination or fall back to the closest supported channel count.
     * @return a defensive copy of the channel-support entries
     */
    public abstract long[] supportedChannels();

    /**
     * Returns the canonical sample types this backend can play directly for at least one channel count.
     * A type appearing here is not guaranteed to work at every channel count in {@link #supportedChannels()} —
     * consult the packed support flags per entry to confirm.
     * <p>
     * The order of this array defines the byte positions used in {@link #supportedChannels()} entries.
     * @return a defensive copy of the supported sample types
     */
    public abstract SampleType[] supportedTypes();

    /**
     * Reconfigures the engine for a new audio format.
     * Must be called before the first upload and whenever the audio format changes.
     * <p>
     * Stream-based backends (e.g. hypothetical JavaSound) may close and reopen internal
     * resources on reconfiguration. Buffer-based backends (OpenAL) simply update state.
     * Mirrors the contract of {@code GFXEngine#setVideoFormat} for consistency.
     * @param type       canonical sample type
     * @param channels   audio channel count (1=mono, 2=stereo, 6=5.1, 8=7.1, ...)
     * @param sampleRate sample rate in Hz (expected range: {@link #MIN_SAMPLE_RATE}..{@link #MAX_SAMPLE_RATE})
     * @return {@code true} if the format is accepted, {@code false} if unsupported
     */
    public abstract boolean setAudioFormat(final SampleType type, final int channels, final int sampleRate);

    /** Current source handle. */
    public int source() { return this.source; }

    /** Current sample type, or {@code null} before {@link #setAudioFormat}. */
    public SampleType sampleType() { return this.sampleType; }

    /** Audio channels. */
    public int channels() { return this.channels; }

    /** Sample rate. */
    public int sampleRate() { return this.sampleRate; }

    /**
     * Generates audio buffers and initializes the internal source handle.
     */
    protected abstract int genSource();

    /**
     * Pauses audio playback.
     */
    public abstract void pause();

    /**
     * Starts or resumes audio playback.
     */
    public abstract void play();

    /**
     * Sets the playback speed (pitch).
     * @param speed playback speed multiplier (1.0 = normal)
     */
    public abstract void speed(final float speed);

    /**
     * Sets the volume (gain).
     * @param volume volume level (0.0 = silent, 1.0 = full)
     */
    public abstract void volume(final float volume);

    /**
     * Releases all resources associated with the source.
     * The engine is unusable after this call.
     */
    public abstract void release();

    /**
     * Uploads audio data to the playback system. Non-blocking: returns false if no buffer
     * is available (caller should retry later).
     * <p>
     * The data is interpreted using the type/channels/rate last passed to
     * {@link #setAudioFormat(SampleType, int, int)}.
     * @param data direct ByteBuffer with audio sample data
     * @return true if the data was queued, false if no buffer was available
     */
    public abstract boolean upload(final ByteBuffer data);

    /**
     * Returns the playback position of the sample currently reaching the listener, in milliseconds.
     * <p>
     * When {@code AL_SOFT_source_latency} (or the equivalent in other backends) is available,
     * this compensates for device output latency, giving sub-sample precision suitable for
     * A/V synchronization. When the extension is not available, returns the raw source offset
     * without latency compensation.
     * @return playback position in ms within the queued buffers, or {@code -1} if playback hasn't started
     */
    public abstract long playbackMs();
}