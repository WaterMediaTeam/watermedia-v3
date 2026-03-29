package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.openal.AL10;
import org.lwjgl.opengl.GL12;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.media.MRL;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public abstract sealed class MediaPlayer permits ServerMediaPlayer, FFMediaPlayer, TxMediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(MediaPlayer.class.getSimpleName());
    protected static final int NO_SIZE = 0;
    protected static final int NO_TEXTURE = -1; // THIS IS -1 BECAUSE ZERO REPRESENTS NULL
    protected static final int NO_DURATION = 0;

    // Basic Properties
    protected final MRL.Source source;
    protected final Thread renderThread;
    protected final Executor renderThreadEx;
    protected final GLEngine gl;
    protected final ALEngine al;
    protected MRL.Quality quality = WaterMediaConfig.media.defaultQuality;
    protected boolean video;
    protected boolean audio;

    // Video Properties
    private int width = NO_SIZE;
    private int height = NO_SIZE;
    private final int glTexture;
    private int glChroma; // Default to BGRA
    private boolean firstFrame = true;

    // Audio Properties
    protected final int alSources;
    private boolean repeat;
    private float volume = 1f; // Default volume
    private float speed = 1.0f;
    private boolean muted = false;

    public MediaPlayer(final MRL.Source source, final Thread renderThread, final Executor renderThreadEx, final GLEngine gl, final ALEngine al, final boolean video, final boolean audio) {
        Objects.requireNonNull(source, "MediaPlayer must have a valid media resource locator. (mrl)");
        Objects.requireNonNull(renderThread, "MediaPlayer must have a valid render thread.");
        Objects.requireNonNull(renderThreadEx, "MediaPlayer must have a valid render thread executor.");

        // INIT PROPERTIES
        this.source = source;
        this.renderThread = renderThread;
        this.renderThreadEx = renderThreadEx;
        this.gl = gl == null ? new GLEngine.Builder().build() : gl;
        this.al = al == null ? new ALEngine.Builder().build() : al;
        this.video = video;
        this.audio = audio;

        // VALIDATE THREAD
        renderThreadEx.execute(() -> {
            if (Thread.currentThread() != renderThread) {
                throw new IllegalStateException("MediaPlayer must be provided with the render thread and a executor that runs task on it.");
            }
            // VALIDATE GL CONTEXT
            if (this.video) {
                if (this.gl == null) {
                    throw new IllegalArgumentException("MediaPlayer with video support must be provided with a valid GLEngine instance.");
                }
                if (!this.gl.hasGLContext()) {
                    throw new IllegalStateException("MediaPlayer's GLEngine context must be current on the render thread.");
                }
            }
        });

        // Initialize video (if applicable)
        this.glTexture = video ? this.gl.createTexture() : NO_TEXTURE;

        // Initialize audio (if applicable)
        this.alSources = audio ? this.al.genSource() : NO_TEXTURE;
    }

    /**
     * Sets the video format for rendering.
     *
     * @param chroma the chroma format, the only supported value is {@link GL12#GL_RGBA GL_RGBA} and {@link GL12#GL_BGRA GL_BGRA}.
     * @param width  the width of the video in pixels.
     * @param height the height of the video in pixels.
     */
    public void setVideoFormat(final int chroma, final int width, final int height) {
        if (!this.video) return;

        // TODO: test if whe should rid off of RGBA and just use BGRA
        if (chroma != GL12.GL_RGBA && chroma != GL12.GL_BGRA)
            throw new IllegalArgumentException("Unsupported chroma format: " + chroma);

        this.glChroma = chroma;
        this.width = width;
        this.height = height;
        this.firstFrame = true;
        LOGGER.debug(IT,"Set video format: chroma={}, width={}, height={}", chroma == GL12.GL_RGBA ? "RGBA" : "BGRA", width, height);
    }

    protected void upload(final ByteBuffer nativeBuffer, final int stride) {
        // Video format not set yet, skip uploading
        if (this.width == NO_SIZE || this.height == NO_SIZE) { return; }

        if (nativeBuffer != null && !nativeBuffer.isDirect()) {
            LOGGER.warn(IT, "Provided video frame buffer is not a direct ByteBuffer, Source: {}.", this.source.bestUri());
        }

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

        if (!nativeBuffer.isDirect()) {
            throw new  IllegalArgumentException("Native buffer is not a direct ByteBuffer.");
        }

        synchronized(nativeBuffer) {
            this.gl.uploadTexture(this.glTexture, nativeBuffer, stride, this.width, this.height, this.glChroma, this.firstFrame);
            this.firstFrame = false;
        }
    }

    protected boolean upload(final ByteBuffer data, final int format, final int samples, final int channels) {
        if (!this.audio)
            throw new IllegalStateException("MediaPlayer was built with no audio support");

        // TODO: test if I should keep FORMAT_STEREO8 or just use STEREO16
        if (format != AL10.AL_FORMAT_STEREO8 && format != AL10.AL_FORMAT_STEREO16)
            throw new IllegalArgumentException("Unsupported audio format: " + format);

        if (channels != 2)
            throw new IllegalArgumentException(channels == 1 ? "Mono format is not supported anymore." : "Unsupported channel count: " + channels);

        // PREPARE — non-blocking: returns false if OpenAL has no buffer available
        if (!this.al.uploadBuffer(this.alSources, data, format, samples)) {
            return false;
        }

        // PLAY IF NOT ALREADY PLAYING
        this.al.play(this.alSources);
        return true;
    }

    /**
     * Changes the selected quality.
     * The media player will detect this change in its playback loop
     * and switch to the new quality while maintaining the current timestamp.
     * @param quality the new quality to use
     */
    public void quality(final MRL.Quality quality) {
        if (quality == null) throw new IllegalArgumentException("Quality cannot be null.");
        this.quality = quality; // Player loop will detect the change
    }

    public MRL.Quality quality() { return this.quality; }

    /**
     * Indicates if the media player has video support enabled.
     * @return true if video support is enabled, false otherwise.
     */
    public boolean withVideo() { return this.video; }

    /**
     * Indicates if the media player has audio support enabled.
     * @return true if audio support is enabled, false otherwise.
     */
    public boolean withAudio() { return this.audio; }

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
     * Returns the OpenAL source ID used for audio playback.
     * @return the OpenAL source ID, or {@link MediaPlayer#NO_TEXTURE NO_TEXTURE} if audio is not supported.
     */
    public int audioSource() { return this.alSources; }

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
    public void volume(final int volume) {
        this.volume = MathUtil.clamp(volume, 0, 100) / 100f; // Convert to float between 0.0 and 1.0
        this.muted = volume < 1;
        this.al.volume(this.alSources, this.muted ? 0.0f : this.volume);
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
    public void mute(final boolean mute) {
        this.muted = mute;
        this.al.volume(this.alSources, mute ? 0.0f : this.volume);
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
     * @see #pause(boolean)
     * @return true if the operation was successful, false otherwise.
     */
    public boolean resume() { return this.pause(false); }

    /**
     * Pauses media playback at the current position.
     * @see #pause(boolean)
     * @return true if the operation was successful, false otherwise.
     */
    public boolean pause() { return this.pause(true); }

    /**
     * Pauses or resumes media playback based on the provided parameter.
     * If paused is true, the media playback will be paused.
     * If paused is false, the media playback will be resumed.
     * @param paused true to pause the media playback, false to resume
     * @return true if the operation was successful, false otherwise.
     */
    public boolean pause(final boolean paused) {
        if (!this.audio) return false; // NOT SUCCESS, NO AUDIO

        if (paused) this.al.pause(this.alSources);
        else this.al.play(this.alSources);

        return true;
    }

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
     * Provides the Frames per Second
     * Result value its a float because 29.97 framerate exists.
     * Check this <a href="https://www.youtube.com/watch?v=3GJUM6pCpew">full explanation</a>
     * @return the FPS count
     */
    public abstract float fps();

    /**
     * Returns the current playback speed.
     * A speed of 1.0f indicates normal playback speed.
     * A speed greater than 1.0f indicates faster playback,
     * while a speed less than 1.0f indicates slower playback.
     * @return the current playback speed.
     */
    public float speed() { return this.speed; }

    /**
     * Sets the playback speed.
     * A speed of 1.0f indicates normal playback speed.
     * A speed greater than 1.0f indicates faster playback,
     * while a speed less than 1.0f indicates slower playback.
     * The speed must be a positive value greater than 0.0f.
     * @param speed the desired playback speed.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean speed(final float speed) {
        if (speed <= 0 || speed > 4.0f) return false;
        this.speed = speed;
        this.al.speed(this.alSources, speed);
        return true;
    }

    /**
     * Returns the total duration of the media in milliseconds.
     * If the duration is unknown or not applicable, it returns -1.
     * @return the total duration of the media in milliseconds, or -1 if unknown.
     */
    public abstract long duration();

    /**
     * Indicates whether the media should repeat playback when it reaches the end.
     */
    public boolean repeat() { return this.repeat; }

    /**
     * Sets whether the media should repeat playback when it reaches the end.
     * @param repeat true to enable repeat playback, false to disable
     * @return the new repeat state
     */
    public boolean repeat(final boolean repeat) { return this.repeat = repeat; }

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
            this.renderThreadEx.execute(() -> {
                this.gl.deleteTexture(this.glTexture);
                this.gl.release();
            });
        }

        if (this.audio && this.alSources != NO_TEXTURE) {
            this.al.release(this.alSources);
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