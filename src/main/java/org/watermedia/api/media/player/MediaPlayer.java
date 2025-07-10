package org.watermedia.api.media.player;

import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.api.render.RenderAPI;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

public abstract class MediaPlayer {
    public static final int NO_SIZE = -1;
    public static final int NO_TEXTURE = -1;

    // Basic Properties
    protected final URI mrl;
    protected final Thread renderThread;
    protected final Executor renderThreadEx;
    protected boolean video;
    protected boolean audio;

    // Video Properties
    private int glChroma = GL12.GL_BGRA; // Default to BGRA
    private int width = NO_SIZE;
    private int height = NO_SIZE;
    private final int glTexture;
    private boolean firstFrame = true;
    protected final Semaphore glSemaphore = new Semaphore(1);

    // Audio Properties
    protected final int alSources;
    private final int[] alBuffers = new int[3];
    private int alBufferIndex = 0;
    private boolean repeat;
    private float volume = 1f; // Default volume
    private boolean muted = false;

    public MediaPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx, final boolean video, final boolean audio) {
        this.mrl = mrl;
        this.renderThread = renderThread;
        this.renderThreadEx = renderThreadEx;
        this.video = video;
        this.audio = audio;

        // Validate the media player configuration
        if (!video && !audio) {
            throw new IllegalArgumentException("media player must support at least one media playback.");
        }
        if (mrl == null) {
            throw new IllegalArgumentException("media player must have a valid media resource locator (mrl).");
        }
        if (renderThread == null) {
            throw new IllegalArgumentException("media player must have a valid render thread.");
        }
        if (renderThreadEx == null) {
            throw new IllegalArgumentException("media player must have a valid render thread executor.");
        }

        // Initialize video (if applicable)
        if (video) {
            this.glTexture = RenderAPI.createTexture();
        } else {
            this.glTexture = NO_TEXTURE; // No texture if video is not supported
        }

        // Initialize audio (if applicable)
        if (audio) {
            this.alSources = AL10.alGenSources();
            AL10.alGenBuffers(this.alBuffers);
        } else {
            this.alSources = NO_TEXTURE; // No audio sources if audio is not supported
        }
    }

    protected void uploadAudioBuffer(final ByteBuffer data, final int alFormat, final int sampleRate, final int channels) {
        if (alFormat != AL10.AL_FORMAT_MONO8 && alFormat != AL10.AL_FORMAT_MONO16 && alFormat != AL10.AL_FORMAT_STEREO8 && alFormat != AL10.AL_FORMAT_STEREO16) {
            throw new IllegalArgumentException("Unsupported audio format: " + alFormat);
        }

        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("Unsupported number of channels: " + channels);
        }

        if (channels == 1 && (alFormat != AL10.AL_FORMAT_MONO8 && alFormat != AL10.AL_FORMAT_MONO16)) {
            throw new IllegalArgumentException("Mono audio format expected for single channel audio.");
        } else if (channels == 2 && (alFormat != AL10.AL_FORMAT_STEREO8 && alFormat != AL10.AL_FORMAT_STEREO16)) {
            throw new IllegalArgumentException("Stereo audio format expected for dual channel audio.");
        }

        // PREPARE
        final int buffer;
        if (this.alBufferIndex < this.alBuffers.length) {
            buffer = this.alBuffers[this.alBufferIndex++];
        } else {
            int processed = AL10.alGetSourcei(this.alSources, AL10.AL_BUFFERS_PROCESSED);
            while (processed <= 0) {
                processed = AL10.alGetSourcei(this.alSources, AL10.AL_BUFFERS_PROCESSED);
            }
            buffer = AL10.alSourceUnqueueBuffers(this.alSources);
        }
        // UPLOAD
        AL10.alBufferData(buffer, alFormat, data, sampleRate);
        AL10.alSourceQueueBuffers(this.alSources, buffer);

        // PLAY IF NOT ALREADY PLAYING
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.alSources);
        }
    }

    protected void uploadVideoFrame(final ByteBuffer[] nativeBuffers) {
        if (this.glChroma != GL12.GL_RGBA && this.glChroma != GL12.GL_BGRA) {
            throw new IllegalArgumentException("Unsupported chroma format: " + this.glChroma);
        }

        if (nativeBuffers == null || nativeBuffers.length == 0) {
            throw new IllegalArgumentException("Native buffers cannot be null or empty.");
        }

        if (this.renderThread != Thread.currentThread()) {
            throw new IllegalStateException("This method must be called from the render thread!");
        }

        // LOCK RENDER THREAD
        if (this.glSemaphore.tryAcquire()) {
            // UPDATE TEXTURE
            RenderAPI.updateTexture(this.glTexture, nativeBuffers, this.width, this.height, this.glChroma, this.firstFrame);
            this.firstFrame = false;
            // RELEASE RENDER THREAD
            this.glSemaphore.release();
        }
    }

    /**
     * Sets the video format for rendering.
     *
     * @param chroma the chroma format, the only supported value is {@link org.lwjgl.opengl.GL12#GL_RGBA GL_RGBA} and {@link org.lwjgl.opengl.GL12#GL_BGRA GL_BGRA}.
     * @param width  the width of the video in pixels.
     * @param height the height of the video in pixels.
     */
    public void setVideoFormat(final int chroma, final int width, final int height) {
        if (chroma != GL12.GL_RGBA && chroma != GL12.GL_BGRA) {
            throw new IllegalArgumentException("Unsupported chroma format: " + chroma);
        }
        this.glChroma = chroma;
        this.width = width;
        this.height = height;
        this.firstFrame = true;
    }

    public boolean isVideo() {
        return this.video;
    }

    public boolean isAudio() {
        return this.audio;
    }

    // VIDEO

    public final int width() {
        return this.width;
    }

    public final int height() {
        return this.height;
    }

    /**
     * Returns the OpenGL texture ID for rendering the video.
     *
     * @return the texture ID, or NO_TEXTURE if not available.
     */
    public int texture() {
        return this.glTexture;
    }

    /**
     * Moves to the previous frame of the video.
     */
    public abstract void previousFrame();

    /**
     * Moves to the next frame of the video.
     */
    public abstract void nextFrame();

    // AUDIO
    public void volume(int volume) {
        if (volume < 0 || volume > 120) {
            throw new IllegalArgumentException("Volume must be between 0 and 120.");
        }
        this.volume = volume / 100f; // Convert to float between 0.0 and 1.0
        AL10.alSourcef(this.alSources, AL10.AL_GAIN, this.volume);
    }

    public int volume() {
        return (int) (this.volume * 100); // Convert to percentage
    }

    public void mute(boolean mute) {
        this.muted = mute;
        if (mute) {
            AL10.alSourcef(this.alSources, AL10.AL_GAIN, 0.0f); // Mute audio
        } else {
            AL10.alSourcef(this.alSources, AL10.AL_GAIN, this.volume); // Restore volume
        }
    }

    public boolean mute() {
        return this.muted;
    }


    // CONTROL
    public abstract void start();

    public abstract void startPaused();

    public abstract boolean startSync();

    public abstract boolean startSyncPaused();

    public abstract boolean resume();

    public abstract boolean pause();

    public abstract boolean pause(boolean paused);

    public abstract boolean stop();

    public abstract boolean togglePlay();

    public abstract boolean seek(long time);

    public abstract boolean skipTime(long time);

    public abstract boolean seekQuick(long time);

    public abstract boolean foward();

    public abstract boolean rewind();

    public abstract float speed();

    public abstract boolean speed(float speed);

    public boolean repeat() {
        return this.repeat;
    }

    public boolean repeat(final boolean repeat) {
        return this.repeat = repeat;
    }

    // STATE
    public abstract Status status();

    @Deprecated
    public abstract boolean usable();

    public abstract boolean loading();

    public abstract boolean buffering();

    public abstract boolean ready();

    public abstract boolean paused();

    public abstract boolean playing();

    public abstract boolean stopped();

    public abstract boolean ended();

    public abstract boolean validSource();

    public abstract boolean liveSource();

    public abstract boolean canSeek();

    public abstract boolean canPause();

    public abstract boolean canPlay();

    public abstract long duration();

    public abstract long time();

    public void release() {
        if (this.video && this.glTexture != NO_TEXTURE) {
            RenderAPI.releaseTexture(this.glTexture);
        }

        if (this.audio && this.alSources != NO_TEXTURE) {
            AL10.alSourceStop(this.alSources);
            AL10.alDeleteSources(this.alSources);
            AL10.alDeleteBuffers(this.alBuffers);
        }
    }

    public enum Status {
        WAITING,
        LOADING,
        BUFFERING,
        PLAYING,
        PAUSED,
        STOPPED,
        ENDED,
        ERROR;

        public static final MediaPlayer.Status[] VALUES = values();

        public static Status of(final int value) {
            return VALUES[value];
        }
    }
}
