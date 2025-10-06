package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.api.audio.AudioAPI;
import org.watermedia.api.math.MathAPI;
import org.watermedia.api.render.RenderAPI;
import org.watermedia.tools.ThreadTool;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public abstract sealed class MediaPlayer permits FFMediaPlayer, PicturePlayer, VLMediaPlayer {
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
        // Validate the media player configuration
        if (!video && !audio) throw new IllegalArgumentException("media player must support at least one media playback.");

        Objects.requireNonNull(mrl, "media player must have a valid media resource locator. (mrl)");
        Objects.requireNonNull(renderThread, "media player must have a valid render thread.");
        Objects.requireNonNull(renderThreadEx, "media player must have a valid render thread executor.");

        // Initialize properties
        this.mrl = mrl;
        this.renderThread = renderThread;
        this.renderThreadEx = renderThreadEx;
        this.video = video;
        this.audio = audio;

        // Initialize video (if applicable)
        this.glTexture = video ? RenderAPI.genTexture() : NO_TEXTURE;

        // Initialize audio (if applicable)
        this.alSources = audio ? AudioAPI.genSource(this.alBuffers) : NO_TEXTURE;
    }

    /**
     * Sets the video format for rendering.
     *
     * @param chroma the chroma format, the only supported value is {@link GL12#GL_RGBA GL_RGBA} and {@link GL12#GL_BGRA GL_BGRA}.
     * @param width  the width of the video in pixels.
     * @param height the height of the video in pixels.
     */
    public void setVideoFormat(final int chroma, final int width, final int height) {
        if (chroma != GL12.GL_RGBA && chroma != GL12.GL_BGRA)
            throw new IllegalArgumentException("Unsupported chroma format: " + chroma);

        this.glChroma = chroma;
        this.width = width;
        this.height = height;
        this.firstFrame = true;
        LOGGER.debug(IT,"Set video format: chroma={}, width={}, height={}", chroma, width, height);
    }

    protected void upload(final ByteBuffer data, final int alFormat, final int sampleRate, final int channels) {
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

    protected void upload(final ByteBuffer nativeBuffer, final int stride) {
        // Video format not set yet, skip uploading
        if (this.width == NO_SIZE || this.height == NO_SIZE) { return; }

        if (this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.upload(nativeBuffer, stride));
            return;
        }

        if (!this.video)
            throw new IllegalStateException("MediaPlayer was built with no video support");

        if (this.glChroma != GL12.GL_RGBA && this.glChroma != GL12.GL_BGRA)
            throw new IllegalArgumentException("Unsupported chroma format: " + this.glChroma);

        if (nativeBuffer == null)
            throw new IllegalArgumentException("Native buffers cannot be null or empty.");

        synchronized(nativeBuffer) {
            RenderAPI.uploadTexture(this.glTexture, nativeBuffer, stride, this.width, this.height, this.glChroma, this.firstFrame);
            this.firstFrame = false;
        }
    }

    /**
     * Indicates if the media player has video support enabled.
     * @return true if video support is enabled, false otherwise.
     */
    public boolean isVideo() { return this.video; }

    /**
     * Indicates if the media player has audio support enabled.
     * @return true if audio support is enabled, false otherwise.
     */
    public boolean isAudio() { return this.audio; }

    /**
     * Returns the width of the video in pixels.
     * @return the width of the video, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not available.
     */
    public final int width() { return this.width; }

    /**
     * Returns the height of the video in pixels.
     * @return the height of the video, or {@link MediaPlayer#NO_SIZE NO_SIZE} if not available.
     */
    public final int height() { return this.height; }

    /**
     * Returns the OpenGL texture ID used for rendering the video frames.
     * @return the OpenGL texture ID, or {@link MediaPlayer#NO_TEXTURE NO_TEXTURE} if video is not supported.
     */
    public int texture() { return this.glTexture; }

    /**
     * Moves to the previous frame of the video.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean previousFrame();

    /**
     * Moves to the next frame of the video.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean nextFrame();

    /**
     * Sets the volume of the audio playback.
     * The volume is clamped between 0 (mute) and 100 (maximum volume).
     * Setting the volume to 0 will also mute the audio.
     * @param volume the desired volume level (0-100), mute status is set to false if volume is greater than 0
     *               but will not be set mute to true if volume is set to 0
     */
    public void volume(int volume) {
        this.volume = MathAPI.clamp(0, 100, volume) / 100f; // Convert to float between 0.0 and 1.0
        this.muted = this.volume < 1;
        AL10.alSourcef(this.alSources, AL10.AL_GAIN, this.muted ? 0 : this.volume);
    }

    /**
     * Returns the current volume level as a percentage (0-100).
     * @return the current volume level (0-100), mute state doesn't affect the volume level
     */
    public int volume() { return (int) (this.volume * 100); }

    /**
     * Mutes or unmutes the audio playback.
     * When muted, the volume is internally set to 0.0f,
     * and when unmuted, the volume is restored to the previous level.
     * @param mute true to mute the audio, false to unmute
     */
    public void mute(boolean mute) {
        this.muted = mute;
        // Restore volume
        AL10.alSourcef(this.alSources, AL10.AL_GAIN, mute ? 0.0f : this.volume); // Mute audio
    }

    /**
     * Returns the current mute status of the audio playback.
     * @see MediaPlayer#mute(boolean)
     * @return true if the audio is muted, false otherwise.
     */
    public boolean mute() { return this.muted; }

    /**
     * Starts media playback from the beginning.
     * <p>If the media is already playing, it will restart from the beginning.</p>
     * If the media is paused, it will resume playback from the current position.
     * <p>If the media is stopped, it will start playback from the beginning.</p>
     * If the media is in an error state, it will attempt to recover and start playback.
     * <p>If the media is loading or buffering, it will wait until the media is ready before starting playback.</p>
     * If the media is ended, it will restart playback from the beginning.
     * <p>If the media source is invalid, it will log an error and not start playback.</p>
     * This method is non-blocking and returns immediately.
     */
    public abstract void start();

    /**
     * Starts media playback in a paused state from the beginning.
     * <p>If the media is already playing, it will restart from the beginning and pause
     * immediately.</p>
     * If the media is paused, it will restart playback from the beginning and remain paused.
     * <p>If the media is stopped, it will start playback from the beginning and remain paused.</p>
     * If the media is in an error state, it will attempt to recover and start playback in a paused state.
     * <p>If the media is loading or buffering,
     * it will wait until the media is ready before starting playback in a paused state.</p>
     * If the media is ended, it will restart playback from the beginning and remain paused.
     * <p>If the media source is invalid, it will log an error and not start playback.</p>
     * This method is non-blocking and returns immediately.
     * @implNote This method is equivalent to calling {@link #start()} followed by {@link #pause()}.
     * @see MediaPlayer#start()
     */
    public abstract void startPaused();

    /**
     * Resumes media playback from the current position.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean resume();

    /**
     * Pauses media playback at the current position.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean pause();

    /**
     * Pauses or resumes media playback based on the provided parameter.
     * If paused is true, the media playback will be paused.
     * If paused is false, the media playback will be resumed.
     * @param paused true to pause the media playback, false to resume
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean pause(boolean paused);

    /**
     * Stops media playback and resets the position to the beginning.
     * This method is non-blocking and returns immediately.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean stop();

    /**
     * Toggles media playback between play and pause states.
     * If the media is currently playing, it will be paused.
     * If the media is currently paused, it will be resumed.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean togglePlay();

    /**
     * Seeks to a specific time in the media.
     * The time is specified in milliseconds.
     * @param time the time to seek to, in milliseconds.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean seek(long time);

    /**
     * Returns the current playback time in milliseconds.
     * If the current time is unknown or not applicable, it returns -1.
     * @return the current playback time in milliseconds, or -1 if unknown.
     */
    public abstract long time();

    /**
     * Skips forward or backward by a specific time in the media.
     * The time is specified in milliseconds.
     * A positive value skips forward, while a negative value skips backward.
     * @param time the time to skip, in milliseconds.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean skipTime(long time);

    /**
     * Quickly seeks to a specific time in the media without precise accuracy.
     * This method is useful for fast seeking operations where exact frame accuracy is not required.
     * The time is specified in milliseconds.
     * @param time the time to seek to, in milliseconds.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean seekQuick(long time);

    /**
     * Skips forward 5 seconds in the media.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean foward();

    /**
     * Skips backward 5 seconds in the media.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean rewind();

    /**
     * Returns the current playback speed.
     * A speed of 1.0f indicates normal playback speed.
     * A speed greater than 1.0f indicates faster playback,
     * while a speed less than 1.0f indicates slower playback.
     * @return the current playback speed.
     */
    public abstract float speed();

    /**
     * Sets the playback speed.
     * A speed of 1.0f indicates normal playback speed.
     * A speed greater than 1.0f indicates faster playback,
     * while a speed less than 1.0f indicates slower playback.
     * The speed must be a positive value greater than 0.0f.
     * @param speed the desired playback speed.
     * @return true if the operation was successful, false otherwise.
     */
    public abstract boolean speed(float speed);

    /**
     * Returns the total duration of the media in milliseconds.
     * If the duration is unknown or not applicable, it returns -1.
     * @return the total duration of the media in milliseconds, or -1 if unknown.
     */
    public abstract long duration();

    /**
     * Indicates whether the media should repeat playback when it reaches the end.
     */
    public boolean repeat() {
        return this.repeat;
    }

    /**
     * Sets whether the media should repeat playback when it reaches the end.
     * @param repeat true to enable repeat playback, false to disable
     * @return the new repeat state
     */
    public boolean repeat(final boolean repeat) {
        return this.repeat = repeat;
    }

    /**
     * Returns the current status of the media player.
     * @return the current status of the media player.
     */
    public abstract Status status();

    /**
     * Check if the media player equals to {@link Status#WAITING WAITING}
     * @return true if the media player is in WAITING status, false otherwise.
     */
    public boolean waiting() { return this.status() == Status.WAITING; }

    /**
     * Check if the media player equals to {@link Status#LOADING LOADING}
     * @return true if the media player is in LOADING status, false otherwise.
     */
    public boolean loading() { return this.status() == Status.LOADING; }

    /**
     * Check if the media player equals to {@link Status#BUFFERING BUFFERING}
     * @return true if the media player is in BUFFERING status, false otherwise.
     */
    public boolean buffering() { return this.status() == Status.BUFFERING; }

    /**
     * Check if the media player equals to {@link Status#PAUSED PAUSED}
     * @return true if the media player is in PAUSED status, false otherwise.
     */
    public boolean paused() { return this.status() == Status.PAUSED; }

    /**
     * Check if the media player equals to {@link Status#PLAYING PLAYING}
     * @return true if the media player is in PLAYING status, false otherwise.
     */
    public boolean playing() { return this.status() == Status.PLAYING; }

    /**
     * Check if the media player equals to {@link Status#STOPPED STOPPED}
     * @return true if the media player is in STOPPED status, false otherwise.
     */
    public boolean stopped() { return this.status() == Status.STOPPED; }

    /**
     * Check if the media player equals to {@link Status#ENDED ENDED}
     * @return true if the media player is in ENDED status, false otherwise.
     */
    public boolean ended() { return this.status() == Status.ENDED; }

    /**
     * Check if the media player equals to {@link Status#ERROR ERROR}
     * @return true if the media player is in ERROR status, false otherwise.
     */
    public boolean error() { return this.status() == Status.ERROR; }

    /**
     * @deprecated i couldn't find a better way to name this method
     * and has no specific purpose, canPlay is better
     * @return
     */
    @Deprecated
    public abstract boolean validSource();

    /**
     * Indicates if the media source is a live stream.
     * Live streams typically do not support seeking and have an indefinite duration.
     * @return true if the media source is a live stream, false otherwise.
     */
    public abstract boolean liveSource();

    /**
     * Indicates if the media player supports seeking operations.
     * Some media formats or live streams may not support seeking.
     * @return true if seeking is supported, false otherwise.
     */
    public abstract boolean canSeek();

    /**
     * Indicates if the media player supports pausing operations.
     * Some media formats or live streams may not support pausing.
     * @return true if pausing is supported, false otherwise.
     */
    public abstract boolean canPause();

    /**
     * Indicates if the media player is ready to start playback.
     * This typically means that the media has been loaded and buffered sufficiently.
     * @return true if the media player can start playback, false otherwise.
     */
    public abstract boolean canPlay();

    /**
     * Releases all resources associated with the media player.
     * This includes OpenGL textures and OpenAL sources and buffers.
     * After calling this method, the media player should not be used again.
     */
    public void release() {
        if (this.video && this.glTexture != NO_TEXTURE) {
            RenderAPI.delTexture(this.glTexture);
        }

        if (this.audio && this.alSources != NO_TEXTURE) {
            AL10.alSourceStop(this.alSources);
            AL10.alDeleteSources(this.alSources);
            AL10.alDeleteBuffers(this.alBuffers);
        }
    }

    /**
     * Represents the various states of the media player during its lifecycle.
     * Each state indicates a specific phase of media playback or an error condition.
     * @see MediaPlayer#status()
     */
    public enum Status {
        /**
         * The media player is waiting for resources or conditions to start loading the media.
         */
        WAITING,
        /**
         * The media player is in the process of loading the media.
         */
        LOADING,
        /**
         * The media player is buffering data to ensure smooth playback.
         */
        BUFFERING,
        /**
         * The media player is currently playing the media.
         */
        PLAYING,
        /**
         * The media player is paused and can be resumed.
         */
        PAUSED,
        /**
         * The media player is stopped and can be started from the beginning.
         */
        STOPPED,
        /**
         * The media player has reached the end of the media.
         */
        ENDED,
        /**
         * The media player has encountered an error and cannot continue playback.
         */
        ERROR;

        private static final MediaPlayer.Status[] VALUES = values();

        /**
         * Returns the Status corresponding to the given integer value.
         * The value should be between 0 and 6, inclusive.
         * If the value is out of range, it may throw an ArrayIndexOutOfBoundsException.
         * @param value the integer value representing the status (0-6)
         * @return the corresponding Status enum value
         * @throws ArrayIndexOutOfBoundsException if the value is out of range
         */
        public static Status of(final int value) { return VALUES[value]; }
    }
}
