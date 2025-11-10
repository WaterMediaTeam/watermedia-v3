package org.watermedia.api.media.platforms;

import org.lwjgl.openal.AL10;
import org.watermedia.tools.ThreadTool;

import java.nio.ByteBuffer;

public record ALEngine() {

    public int genSource(final int[] buffers) {
        AL10.alGenBuffers(buffers);
        return AL10.alGenSources();
    }

    public void queueBuffer(int source, int buffer, ByteBuffer data, int alFormat, int sampleRate) {
        AL10.alBufferData(buffer, alFormat, data, sampleRate);
        AL10.alSourceQueueBuffers(source, buffer);
    }

    public int dequeueBuffers(int source) {
        while (AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED) <= 0) {
            ThreadTool.sleep(1);
        }
        return AL10.alSourceUnqueueBuffers(source);
    }

    public void pause(int source) {
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(source);
        }
    }

    public void play(int source) {
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(source);
        }
    }

    public static final class Builder {


        public ALEngine build() {
            return new ALEngine();
        }
    }
}
