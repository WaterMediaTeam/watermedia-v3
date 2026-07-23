package org.watermedia.api.media.engines;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Sound engine abstraction for uploading decoded audio data to playback systems.
 * <p>
 * WATERMeDIA decodes audio, uploads sample data, and exposes a source handle.
 * The developer controls playback through that handle.
 * <p>
 * Implementations are backend-specific (OpenAL, etc.).
 */
public abstract sealed class SFXEngine permits ALEngine, JSEngine {
    /** Minimum reasonable sample rate (sub-telephony threshold, below is garbage). */
    public static final int MIN_SAMPLE_RATE = 4000;
    /** Maximum reasonable sample rate (covers DXD and any hi-res consumer content). */
    public static final int MAX_SAMPLE_RATE = 384_000;

    /**
     * Canonical PCM sample type — backend-independent.
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

    /**
     * One supported channel count and the sample types playable at it. Declared per channel
     * count because support is not rectangular (e.g. OpenAL plays {@link SampleType#DBL} only
     * in mono/stereo, never multichannel).
     *
     * @param channels audio channel count (1..8)
     * @param types    sample types playable at this channel count
     */
    public record ChannelSupport(int channels, Set<SampleType> types) {

        public ChannelSupport {
            types = Collections.unmodifiableSet(EnumSet.copyOf(types));
        }

        public ChannelSupport(final int channels, final SampleType... types) {
            this(channels, EnumSet.copyOf(List.of(types)));
        }

        /**
         * Whether {@code type} is playable at this channel count.
         */
        public boolean supports(final SampleType type) {
            return type != null && this.types.contains(type);
        }
    }

    protected int source;
    protected SampleType sampleType;
    protected int channels;
    protected int sampleRate;

    /**
     * Returns the channel-support table, one entry per supported channel count. Callers pick an
     * exact passthrough combination or fall back to the {@link #closestChannelSupport(int)
     * closest} supported channel count.
     * @return a defensive copy of the channel-support entries
     */
    public abstract ChannelSupport[] supportedChannels();

    /**
     * Returns the canonical sample types this backend can play directly for at least one channel
     * count, in preference order for fallbacks. A type appearing here is not guaranteed to work
     * at every channel count — consult {@link ChannelSupport#supports(SampleType)} to confirm.
     * @return a defensive copy of the supported sample types
     */
    public abstract SampleType[] supportedTypes();

    /**
     * Support entry for exactly {@code channels}, or {@code null} when this backend does not
     * expose that channel count.
     */
    public final ChannelSupport channelSupport(final int channels) {
        for (final ChannelSupport entry: this.supportedChannels()) {
            if (entry.channels() == channels) return entry;
        }
        return null;
    }

    /**
     * Support entry with the channel count closest to {@code channels}; ties prefer the lower
     * count (downmix is more predictable than upmix). Returns {@code null} on an empty table.
     */
    public final ChannelSupport closestChannelSupport(final int channels) {
        ChannelSupport best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (final ChannelSupport entry: this.supportedChannels()) {
            final int diff = Math.abs(entry.channels() - channels);
            if (diff < bestDiff || (diff == bestDiff && best != null && entry.channels() < best.channels())) {
                best = entry;
                bestDiff = diff;
            }
        }
        return best;
    }

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
     * Pauses audio playback.
     */
    public abstract void pause();

    /**
     * Starts or resumes audio playback.
     */
    public abstract void play();

    /**
     * Indicates if this backend can change the playback speed (pitch).
     * When {@code false}, {@link #speed(float)} is a no-op and playback stays at 1.0×;
     * callers driving an A/V clock must not scale their timeline against this engine.
     * @return {@code true} if {@link #speed(float)} takes effect, {@code false} otherwise
     */
    public abstract boolean speed();

    /**
     * Sets the playback speed (pitch).
     * No-op on backends where {@link #speed()} reports {@code false}.
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
     * Discards all audio queued in the engine that has not finished playing.
     * Used on seeks and quality switches so stale audio from the previous
     * position doesn't keep playing (or get replayed) after the jump.
     * Playback stops; the next {@link #upload(ByteBuffer)} + {@link #play()} resumes it.
     */
    public abstract void flush();

    /**
     * Returns the amount of queued audio that has not yet reached the listener, in milliseconds.
     * <p>
     * This is the complement of {@link #playbackMs()} for A/V synchronization: a media clock can
     * derive the audible position as {@code endPtsOfLastUploadedFrame - pendingMs()}. When
     * {@code AL_SOFT_source_latency} (or the equivalent in other backends) is available, the value
     * includes audio still buffered inside the output device.
     * @return milliseconds of uploaded audio not yet audible; {@code 0} when nothing is pending
     *         (e.g. after an underrun or before the first upload)
     */
    public abstract long pendingMs();

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

    // GENERATES BACKEND BUFFERS AND INITIALIZES THE INTERNAL SOURCE HANDLE
    protected abstract int genSource();
}