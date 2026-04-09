package org.watermedia.api.media.engines;

import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;

public final class ALEngine extends SFXEngine {
    // SAMPLE FORMATS — PCM
    public static final int FORMAT_MONO8 = AL10.AL_FORMAT_MONO8;
    public static final int FORMAT_MONO16 = AL10.AL_FORMAT_MONO16;
    public static final int FORMAT_STEREO8 = AL10.AL_FORMAT_STEREO8;
    public static final int FORMAT_STEREO16 = AL10.AL_FORMAT_STEREO16;

    // SAMPLE FORMATS — IEEE FLOAT 32-BIT (AL_EXT_float32)
    public static final int FORMAT_MONO_FLOAT32 = 0x10010;
    public static final int FORMAT_STEREO_FLOAT32 = 0x10011;

    // DEFAULTS
    private static final int DEFAULT_BUFFER_COUNT = 4;
    private static final int DEFAULT_FORMAT = FORMAT_STEREO16;
    private static final int DEFAULT_CHANNELS = STEREO;
    private static final int DEFAULT_SAMPLE_RATE = SAMPLE_RATE_48000;

    private final int[] buffers;
    private int index = 0;

    public ALEngine(final int bufferCount, final int sampleFormat, final int channels, final int sampleRate) {
        this.buffers = new int[bufferCount];
        this.sampleFormat = sampleFormat;
        this.channels = channels;
        this.sampleRate = sampleRate;
    }

    @Override
    public int genSource() {
        AL10.alGenBuffers(this.buffers);
        return AL10.alGenSources();
    }

    @Override
    public void pause(final int source) {
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(source);
        }
    }

    @Override
    public void play(final int source) {
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(source);
        }
    }

    @Override
    public void speed(final int source, final float speed) {
        AL10.alSourcef(source, AL10.AL_PITCH, speed);
    }

    @Override
    public void volume(final int source, final float volume) {
        AL10.alSourcef(source, AL10.AL_GAIN, volume);
    }

    @Override
    public void release(final int source) {
        AL10.alSourceStop(source);
        // UNQUEUE ALL PROCESSED BUFFERS BEFORE DELETING
        final int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            AL10.alSourceUnqueueBuffers(source);
        }
        AL10.alDeleteSources(source);
        AL10.alDeleteBuffers(this.buffers);
    }

    @Override
    public boolean upload(final int source, final ByteBuffer data, final int format, final int samples) {
        final int buffer;
        if (this.index < this.buffers.length) {
            buffer = this.buffers[this.index++];
        } else {
            if (AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED) <= 0) {
                return false; // NO BUFFER AVAILABLE — TRY LATER
            }
            buffer = AL10.alSourceUnqueueBuffers(source);
        }
        AL10.alBufferData(buffer, format, data, samples);
        AL10.alSourceQueueBuffers(source, buffer);
        return true;
    }

    public int[] buffers() {
        return this.buffers;
    }

    public int[] buffersCopy() {
        return this.buffers.clone();
    }

    /**
     * Creates an ALEngine with default parameters:
     * 4 buffers, STEREO16, 2 channels, 48000 Hz.
     */
    public static ALEngine buildDefault() {
        return new Builder().build();
    }

    public static final class Builder {
        private int bufferCount = DEFAULT_BUFFER_COUNT;
        private int sampleFormat = DEFAULT_FORMAT;
        private int channels = DEFAULT_CHANNELS;
        private int sampleRate = DEFAULT_SAMPLE_RATE;

        public Builder bufferCount(final int bufferCount) {
            this.bufferCount = bufferCount;
            return this;
        }

        public Builder sampleFormat(final int sampleFormat) {
            this.sampleFormat = sampleFormat;
            return this;
        }

        public Builder channels(final int channels) {
            this.channels = channels;
            return this;
        }

        public Builder sampleRate(final int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public ALEngine build() {
            return new ALEngine(this.bufferCount, this.sampleFormat, this.channels, this.sampleRate);
        }
    }
}
