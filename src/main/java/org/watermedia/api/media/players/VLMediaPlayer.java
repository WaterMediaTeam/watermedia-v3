package org.watermedia.api.media.players;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.LibVlc;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public abstract class VLMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker("VLMediaPlayer");
    protected static final libvlc_instance_t MEDIA_INSTANCE = VideoLan4J.createInstance("--no-quiet", "--verbose", "--file-logging", "--logfile=/logs/vlc.log", "--vout=direct3d11");


    // STATUS
    protected libvlc_media_player_t rawPlayer;
    protected libvlc_media_t rawMedia;
    protected libvlc_media_stats_t rawStats;

    // LISTENER
    private final List<libvlc_callback_t> nativeEvents = new ArrayList<>();

    public VLMediaPlayer(final URI mrl, final Executor renderThreadEx) {
        super(mrl, renderThreadEx);
        this.rawPlayer = VideoLan4J.createMediaPlayer(MEDIA_INSTANCE);
        final libvlc_media_t media = VideoLan4J.createMediaInstance(MEDIA_INSTANCE, this.mrl);
        LibVlc.libvlc_media_player_set_media(this.rawPlayer, media);

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
            for (final libvlc_event_e ev: libvlc_event_e.values()) {
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


}
