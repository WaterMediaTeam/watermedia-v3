package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.api.math.MathAPI;
import org.watermedia.api.render.RenderAPI;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public abstract class MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(MediaPlayer.class.getSimpleName());
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

    // Audio Properties
    protected final int alSources;
    private final int[] alBuffers = new int[4];
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
        if (!this.audio) {
            throw new IllegalStateException("MediaPlayer was built with no audio support");
        }

        if (alFormat != AL10.AL_FORMAT_MONO8 && alFormat != AL10.AL_FORMAT_MONO16 && alFormat != AL10.AL_FORMAT_STEREO8 && alFormat != AL10.AL_FORMAT_STEREO16) {
            throw new IllegalArgumentException("Unsupported audio format: " + alFormat);
        }

        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("Unsupported channel count: " + channels);
        }

        if (channels == 1 && (alFormat != AL10.AL_FORMAT_MONO8 && alFormat != AL10.AL_FORMAT_MONO16)) {
            throw new IllegalArgumentException("Mono format expected for single channel audio.");
        } else if (channels == 2 && (alFormat != AL10.AL_FORMAT_STEREO8 && alFormat != AL10.AL_FORMAT_STEREO16)) {
            throw new IllegalArgumentException("Stereo format expected for dual channel audio.");
        }

        // PREPARE
        final int buffer;
        if (this.alBufferIndex < this.alBuffers.length) {
            buffer = this.alBuffers[this.alBufferIndex++];
        } else {
            while (AL10.alGetSourcei(this.alSources, AL10.AL_BUFFERS_PROCESSED) <= 0) {
                ThreadTool.sleep(1);
            }
            buffer = AL10.alSourceUnqueueBuffers(this.alSources);
        }
        // UPLOAD
        AL10.alBufferData(buffer, alFormat, data, sampleRate);
        AL10.alSourceQueueBuffers(this.alSources, buffer);

        // PLAY IF NOT ALREADY PLAYING
        // TODO: move this to a event system
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.alSources);
        }
    }

    protected void uploadVideoFrame(final ByteBuffer nativeBuffer) {
        if (this.width == NO_SIZE || this.height == NO_SIZE) {
            return; // Video format not set yet, skip uploading
        }

        if (this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.uploadVideoFrame(nativeBuffer));
            return;
        }

        if (!this.video) {
            throw new IllegalStateException("MediaPlayer was built with no video support");
        }

        if (this.glChroma != GL12.GL_RGBA && this.glChroma != GL12.GL_BGRA) {
            throw new IllegalArgumentException("Unsupported chroma format: " + this.glChroma);
        }

        if (nativeBuffer == null) {
            throw new IllegalArgumentException("Native buffers cannot be null or empty.");
        }

        // LOCK RENDER THREAD
        synchronized(nativeBuffer) {
            nativeBuffer.flip();
            RenderAPI.uploadTexture(this.glTexture, nativeBuffer, this.width, this.height, this.glChroma, this.firstFrame);
            this.firstFrame = false;
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
        LOGGER.debug("Set video format: chroma={}, width={}, height={}", chroma, width, height);
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
    public abstract boolean previousFrame();

    /**
     * Moves to the next frame of the video.
     */
    public abstract boolean nextFrame();

    // AUDIO
    public void volume(int volume) {
        volume = MathAPI.clamp(0, 100, volume);
        this.volume = volume / 100f; // Convert to float between 0.0 and 1.0
        this.muted = volume < 1;
        AL10.alSourcef(this.alSources, AL10.AL_GAIN, this.muted ? 0 : this.volume);
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

    public boolean waiting() {
        return this.status() == Status.WAITING;
    }

    public boolean loading() {
        return this.status() == Status.LOADING;
    }

    public boolean buffering() {
        return this.status() == Status.BUFFERING;
    }

    public boolean paused() {
        return this.status() == Status.PAUSED;
    }

    public boolean playing() {
        return this.status() == Status.PLAYING;
    }

    public boolean stopped() {
        return this.status() == Status.STOPPED;
    }

    public boolean ended() {
        return this.status() == Status.ENDED;
    }

    public boolean error() {
        return this.status() == Status.ERROR;
    }

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
