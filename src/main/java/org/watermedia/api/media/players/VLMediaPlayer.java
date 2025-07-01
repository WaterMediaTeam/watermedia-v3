package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import com.sun.jna.Pointer;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.LibVlc;
import org.watermedia.videolan4j.tools.AudioFormat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public abstract class VLMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker("VLMediaPlayer");
    protected static final libvlc_instance_t MEDIA_INSTANCE = VideoLan4J.createInstance("--no-quiet", "--verbose", "--file-logging", "--logfile=/logs/vlc.log", "--vout=direct3d11");
    protected static final AudioFormat AUDIO_FORMAT = AudioFormat.S16N_STEREO_96;

    // STATUS
    protected libvlc_media_player_t rawPlayer;
    protected libvlc_media_t rawMedia;
    protected libvlc_media_stats_t rawStats;
    private final int source = AL10.alGenSources();
    private final int[] buffers = new int[4];
    private int bufferIndex = 0;

    // LISTENER
    private final List<libvlc_callback_t> nativeEvents = new ArrayList<>();
    private final Map<Integer, List<libvlc_callback_t>> events = new HashMap<>();

    // EVENTS
    private final libvlc_audio_play_cb audioPlayCallback = this::audioPlayCallback;
    private final libvlc_audio_pause_cb audioPauseCallback = this::audioPauseCallback;
    private final libvlc_audio_resume_cb audioResumeCallback = this::audioResumeCallback;
    private final libvlc_audio_flush_cb audioFlushCallback = this::audioFlushCallback;
    private final libvlc_audio_drain_cb audioDrainCallback = this::audioDrainCallback;
    private final libvlc_audio_set_volume_cb setVolumeCallback = this::setVolumeCallback;

    public VLMediaPlayer(final URI mrl, final Executor renderThread) {
        super(mrl, renderThread);
        this.rawPlayer = VideoLan4J.createMediaPlayer(MEDIA_INSTANCE);
        final libvlc_media_t media = VideoLan4J.createMediaInstance(MEDIA_INSTANCE, this.mrl);
        LibVlc.libvlc_media_player_set_media(this.rawPlayer, media);

        // REGISTER AUDIO
        AL10.alGenBuffers(this.buffers);
        LibVlc.libvlc_audio_set_format(this.rawPlayer, AUDIO_FORMAT.getFormatName(), AUDIO_FORMAT.getSampleRate(), AUDIO_FORMAT.getChannelCount());
        LibVlc.libvlc_audio_set_callbacks(this.rawPlayer, this.audioPlayCallback, this.audioPauseCallback, this.audioResumeCallback, this.audioFlushCallback, this.audioDrainCallback, Pointer.NULL);
        // NOTE: do this revokes to VLC's default volume control
        LibVlc.libvlc_audio_set_volume_callback(this.rawPlayer, this.setVolumeCallback);

        // REGISTER EVENTS
        final libvlc_event_manager_t eventManager = LibVlc.libvlc_media_player_event_manager(this.rawPlayer);
//        LibVlc.libvlc_event_attach(eventManager, libvlc_event_e.libvlc_MediaPlayerPlaying.intValue(), this.registerListener(this::onPlaying), null);
    }

    private libvlc_callback_t registerListener(final libvlc_callback_t listener) {
        if (listener != null && !this.nativeEvents.contains(listener)) {
            this.nativeEvents.add(listener);
        }
        return listener;
    }

    @Override
    public void start() {
        LibVlc.libvlc_media_player_play(this.rawPlayer);
    }

    @Override
    public void startPaused() {

    }

    @Override
    public boolean startSync() {
        return false;
    }

    @Override
    public boolean startSyncPaused() {
        return false;
    }

    @Override
    public boolean resume() {
        return this.pause(false);
    }

    @Override
    public boolean pause() {
        return this.pause(true);
    }

    @Override
    public boolean pause(final boolean paused) {
        LibVlc.libvlc_media_player_set_pause(this.rawPlayer, paused ? 1 : 0);
        return false;
    }

    @Override
    public boolean stop() {
        LibVlc.libvlc_media_player_stop(this.rawPlayer);
        return true;
    }

    @Override
    public boolean togglePlay() {
        return false;
    }

    @Override
    public boolean seek(final long time) {
        LibVlc.libvlc_media_player_set_time(this.rawPlayer, Math.max(time, 0));
        return true;
    }

    @Override
    public boolean skipTime(final long time) {
        final long current = this.time();
        if (current != -1) {
            return this.seek(current + time);
        }
        return false;
    }

    @Override
    public boolean seekQuick(final long time) {
        return this.seek(time);
    }

    @Override
    public boolean foward() {
        this.seek(this.time() + 5000);
        return true;
    }

    @Override
    public boolean rewind() {
        this.seek(this.time() - 5000);
        return true;
    }

    @Override
    public void nextFrame() {
        LibVlc.libvlc_media_player_next_frame(this.rawPlayer);
    }

    @Override
    public void prevFrame() {
        LOGGER.warn(IT, "Prev frame is not supported by VLC Media Player");
    }

    @Override
    public float speed() {
        return LibVlc.libvlc_media_player_get_rate(this.rawPlayer);
    }

    @Override
    public boolean speed(final float speed) {
        return LibVlc.libvlc_media_player_set_rate(this.rawPlayer, Math.max(speed, 0.1f)) != -1;
    }

    @Override
    public boolean repeat() {
        return this.repeat;
    }

    @Override
    public boolean repeat(final boolean repeat) {
        return this.repeat = repeat;
    }

    @Override
    public boolean usable() {
        return false;
    }

    @Override
    public boolean loading() {
        return this.status() == Status.LOADING;
    }

    @Override
    public boolean buffering() {
        return this.status() == Status.BUFFERING;
    }

    @Override
    public boolean ready() {
        return LibVlc.libvlc_media_player_will_play(this.rawPlayer) == 1;
    }

    @Override
    public boolean paused() {
        return this.status() == Status.PAUSED;
    }

    @Override
    public boolean playing() {
        return LibVlc.libvlc_media_player_is_playing(this.rawPlayer) == 1;
    }

    @Override
    public boolean stopped() {
        return this.status() == Status.STOPPED;
    }

    @Override
    public boolean ended() {
        return this.status() == Status.ENDED;
    }

    @Override
    public boolean validSource() {
        return this.rawMedia != null;
    }

    @Override
    public boolean liveSource() {
        return false;
    }

    @Override
    public boolean canSeek() {
        return LibVlc.libvlc_media_player_is_seekable(this.rawPlayer) == 1;
    }

    @Override
    public boolean canPause() {
        return LibVlc.libvlc_media_player_can_pause(this.rawPlayer) == 1;
    }

    @Override
    public boolean canPlay() {
        return true;
    }

    @Override
    public long duration() {
        return LibVlc.libvlc_media_player_get_length(this.rawPlayer);
    }

    @Override
    public long time() {
        // LibVlc.libvlc_media_player_get_time(mediaPlayerInstance)
        return LibVlc.libvlc_media_player_get_time(this.rawPlayer);
    }

    @Override
    public void release() {
        for (final libvlc_callback_t listener : this.nativeEvents) {
            final libvlc_event_manager_t eventManager = LibVlc.libvlc_media_player_event_manager(this.rawPlayer);
            for (final libvlc_event_e ev : libvlc_event_e.values()) {
                LibVlc.libvlc_event_detach(eventManager, ev.intValue(), listener, null);
            }
        }
        LibVlc.libvlc_media_player_release(this.rawPlayer);
        LibVlc.libvlc_media_release(this.rawMedia);
    }

    @Override
    public void volume(final int volume) {
        LibVlc.libvlc_audio_set_volume(this.rawPlayer, volume) /*== 0*/;
    }

    @Override
    public int volume() {
        return LibVlc.libvlc_audio_get_volume(this.rawPlayer);
    }

    @Override
    public void mute(final boolean mute) {
        LibVlc.libvlc_audio_set_mute(this.rawPlayer, mute ? 1 : 0);
    }

    @Override
    public boolean mute() {
        return LibVlc.libvlc_audio_get_mute(this.rawPlayer) != 0;
    }

    @Override
    public int texture() {
        return NO_TEXTURE;
    }

    @Override
    public Status status() {
        return Status.of(LibVlc.libvlc_media_player_get_state(this.rawPlayer));
    }

    public libvlc_media_stats_t getMediaStats() {
        if (LibVlc.libvlc_media_get_stats(this.rawMedia, this.rawStats) != 0)
            throw new IllegalStateException("Failed to get media stats");
        return this.rawStats = new libvlc_media_stats_t();
    }

    private void audioPlayCallback(final Pointer pointer /* POINTER OF WHAT? IDK*/, final Pointer samples, final int count, final long pts) {
        // READ
        final ByteBuffer data = samples.getByteBuffer(0L, AUDIO_FORMAT.calculateBufferSize(count));

        // PREPARE
        final int buffer;
        if (this.bufferIndex < this.buffers.length) {
            buffer = this.buffers[this.bufferIndex++];
        } else {
            int processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
            while (processed <= 0) {
                processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
            }
            buffer = AL10.alSourceUnqueueBuffers(this.source);
        }
        AL10.alBufferData(buffer, getAlFormat(AUDIO_FORMAT), data, AUDIO_FORMAT.getSampleRate());
        AL10.alSourceQueueBuffers(this.source, buffer);

        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.source);
        }
    }

    private int getAlFormat(AudioFormat format) {
        return switch (format) {
            case U8_MONO_44, U8_STEREO_44, U8_SURROUND_44, U8_SURROUND71_44 -> AL10.AL_FORMAT_MONO8;
            case S16N_MONO_44, S16N_MONO_48, S16N_MONO_96 -> AL10.AL_FORMAT_MONO16;
            // case S32N_MONO_44, S32N_MONO_48, S32N_MONO_96 -> AL11.AL_FORMAT_MONO32; // Not supported by OpenAL
            case S16N_STEREO_44, S16N_STEREO_48, S16N_STEREO_96 -> AL10.AL_FORMAT_STEREO16;
            // case S32N_STEREO_44, S32N_STEREO_48, S32N_STEREO_96 -> AL11.AL_FORMAT_STEREO32; // Not supported by OpenAL
            // case S16N_SURROUND_44, S16N_SURROUND_48, S16N_SURROUND_96 -> AL10.AL_FORMAT_QUAD16; // Not supported by OpenAL
            // case S32N_SURROUND_44, S32N_SURROUND_48, S32N_SURROUND_96 -> AL11.AL_FORMAT_QUAD32; // Not supported by OpenAL
            // case S16N_SURROUND71_44, S16N_SURROUND71_48, S16N_SURROUND71_96 -> AL10.AL_FORMAT_51CHN16; // Not supported by OpenAL


            default -> throw new IllegalArgumentException("Unsupported audio format: " + format);
        };
    }

    private void audioPauseCallback(final Pointer data, final long pts) {
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(this.source);
        }
    }

    private void audioResumeCallback(final Pointer data, final long pts) {
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.source);
        }
    }

    private void audioFlushCallback(final Pointer data, final long pts) {
        // Handle audio flush
    }

    private void audioDrainCallback(final Pointer data) {
        // Handle audio drain
    }

    private void setVolumeCallback(final Pointer data, final float volume, final int mute) {
        AL10.alSourcef(this.source, AL10.AL_GAIN, volume);
    }
}
