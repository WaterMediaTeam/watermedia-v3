package org.watermedia.api.media.engines;

import org.lwjgl.openal.AL10;
import org.watermedia.tools.ThreadTool;

import java.nio.ByteBuffer;

public final class ALEngine {
    private static final int BUFFERS_COUNT = 4;

    private final int[] buffers;
    private int index = 0;

    public ALEngine(final int[] buffers) {
        this.buffers = buffers;
    }

    public int genSource() {
        AL10.alGenBuffers(this.buffers);
        return AL10.alGenSources();
    }

    public void pause(final int source) {
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(source);
        }
    }

    public void play(final int source) {
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(source);
        }
    }

    public void speed(final int source, final float speed) {
        AL10.alSourcef(source, AL10.AL_VELOCITY, speed);
    }

    public void volume(final int source, final float volume) {
        AL10.alSourcef(source, AL10.AL_GAIN, volume);
    }

    public void release(final int source) {
        AL10.alSourceStop(source);
        AL10.alDeleteSources(source);
        AL10.alDeleteBuffers(source);
    }

    public void uploadBuffer(final int source, final ByteBuffer data, final int format, final int samples) {
        final int buffer;
        if (this.index < this.buffers.length) {
            buffer = this.buffers[this.index++];
        } else {
            while (AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED) <= 0) {
                ThreadTool.sleep(1);
            }
            buffer = AL10.alSourceUnqueueBuffers(source);
        }
        AL10.alBufferData(buffer, format, data, samples);
        AL10.alSourceQueueBuffers(source, buffer);
    }

    public int[] buffers() {
        return this.buffers;
    }

    public int[] buffersCopy() {
        return this.buffers.clone();
    }

    public static final class Builder {
        public ALEngine build() {
            return new ALEngine(new int[BUFFERS_COUNT]);
        }
    }
}
