package org.watermedia.api.media.player;

import com.sun.jna.Pointer;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.opengl.GL12;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.LibC;
import org.watermedia.videolan4j.binding.lib.LibVlc;
import org.watermedia.videolan4j.tools.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import static org.watermedia.WaterMedia.LOGGER;

public final class VLMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(VLMediaPlayer.class.getSimpleName());
    private static final Chroma CHROMA = Chroma.RV32; // Use RV32 aka BGRA format for video buffers
    private static final AudioFormat AUDIO_FORMAT = AudioFormat.S16N_STEREO_96;

    // VIDEO BUFFERS
    private ByteBuffer nativeBuffer = null;
    private Pointer nativePointers = null;

    // VIDEOLAN
    private final libvlc_media_player_t rawPlayer;
    private final libvlc_media_t rawMedia;
    private final libvlc_media_stats_t rawStats = null;

    public VLMediaPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx, final boolean video, final boolean audio) {
        super(mrl, renderThread, renderThreadEx, video, audio);
        this.rawPlayer = VideoLan4J.createMediaPlayer(MediaAPI.getVlcInstance());
        this.rawMedia = VideoLan4J.createMediaInstance(MediaAPI.getVlcInstance(), this.mrl);
        LibVlc.libvlc_media_player_set_media(this.rawPlayer, this.rawMedia);

        // SETUP AUDIO
        if (this.isAudio()) {
            LibVlc.libvlc_audio_set_callbacks(this.rawPlayer, this.playCB, this.pauseCB, this.resumeCB, this.flushCB, this.drainCB, Pointer.NULL);
            LibVlc.libvlc_audio_set_volume_callback(this.rawPlayer, this.volumeCB);
            LibVlc.libvlc_audio_set_format(this.rawPlayer, AUDIO_FORMAT.getFormatName(), AUDIO_FORMAT.getSampleRate(), AUDIO_FORMAT.getChannelCount());
        }

        // SETUP VIDEO
        if (this.isVideo()) {
            LibVlc.libvlc_video_set_format_callbacks(this.rawPlayer, this.formatCB, this.cleanupCB);
            LibVlc.libvlc_video_set_callbacks(this.rawPlayer, this.lockCB, this.unlockCB, this.displayCB, Pointer.NULL);
        }

        // REGISTER EVENTS
        final libvlc_event_manager_t eventManager = LibVlc.libvlc_media_player_event_manager(this.rawPlayer);
    }

    private final libvlc_video_format_cb formatCB = (opaque, chromaPointer, widthPointer, heightPointer, pitchesPointer, linesPointer) -> {
        // APPLY CHROMA
        final byte[] chromaBytes = CHROMA.chroma();
        chromaPointer.getPointer().write(0, chromaBytes, 0, Math.min(chromaBytes.length, 4));
        this.setVideoFormat(GL12.GL_BGRA, widthPointer.getValue(), heightPointer.getValue());
        widthPointer.setValue(this.width());
        heightPointer.setValue(this.height());
        final int[] pitchValues = CHROMA.getPitches(this.width());
        final int[] lineValues = CHROMA.getLines(this.height());
        final int planeCount = pitchValues.length;

        pitchesPointer.getPointer().write(0, pitchValues, 0, pitchValues.length);
        linesPointer.getPointer().write(0, lineValues, 0, lineValues.length);

        // ALLOCATE NATIVE BUFFERS - I AM ASSUMING THAT ITS ONLY ONE PLANE (AS IT IS FOR RV32)
        this.nativeBuffer = Buffers.alloc(pitchValues[0] * lineValues[0]);
        this.nativePointers = Pointer.createConstant(Buffers.address(this.nativeBuffer));
        LibC.memoryLock(this.nativePointers, this.nativeBuffer.capacity());

        return planeCount;
    };
    private final libvlc_video_cleanup_cb cleanupCB = opaque -> {
        if (this.nativeBuffer == null) return;

        LibC.memoryUnlock(this.nativePointers, this.nativeBuffer.capacity());
        this.nativeBuffer = null;
        this.nativePointers = null;
    };

    private final libvlc_lock_callback_t lockCB = (opaque, planes) -> {
        if (this.nativeBuffer == null) return null; // nativePointers are never null when nativeBuffers is not null.

        synchronized (this.nativeBuffer) { // Doesn't matter if is not synchronized a constant buffer.
            planes.getPointer().setPointer(0, this.nativePointers);
        }
        return null;
    };

    private final libvlc_display_callback_t displayCB = (opaque, picture) -> this.uploadVideoFrame(this.nativeBuffer);
    private final libvlc_unlock_callback_t unlockCB = (opaque, picture, plane) -> {};
    private final libvlc_audio_play_cb playCB = (pointer, samples, count, pts) ->
            this.uploadAudioBuffer(samples.getByteBuffer(0L, AUDIO_FORMAT.calculateBufferSize(count)), AL11.AL_FORMAT_STEREO16, AUDIO_FORMAT.getSampleRate(), AUDIO_FORMAT.getChannelCount());

    private final libvlc_audio_pause_cb pauseCB = (data, pts) -> {
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(this.alSources);
        }
    };

    private final libvlc_audio_resume_cb resumeCB = (data, pts) -> {
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.alSources);
        }
    };
    private final libvlc_audio_flush_cb flushCB = (data, pts) -> {};
    private final libvlc_audio_drain_cb drainCB = data -> {};
    private final libvlc_audio_set_volume_cb volumeCB = (data, volume, mute) -> AL10.alSourcef(this.alSources, AL10.AL_GAIN, volume);

    @Override
    public boolean previousFrame() {
        // LibVlc.libvlc_media_player_previous_frame(this.rawPlayer);
        LOGGER.warn(IT, "Prev frame is not supported by VLC Media Player");
        return false;
    }

    @Override
    public boolean nextFrame() {
        LibVlc.libvlc_media_player_next_frame(this.rawPlayer);
        return true;
    }

    @Override
    public void start() {
        LibVlc.libvlc_media_player_play(this.rawPlayer);
    }

    @Override
    public void startPaused() {
        LibVlc.libvlc_media_add_option(this.rawMedia, "start-paused");
        this.start();
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
        if (this.status() == Status.PLAYING) {
            return this.pause();
        } else if (this.status() == Status.PAUSED) {
            return this.resume();
        } else if (this.status() == Status.STOPPED) {
            this.start();
            return true;
        }
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
    public float speed() {
        return LibVlc.libvlc_media_player_get_rate(this.rawPlayer);
    }

    @Override
    public boolean speed(final float speed) {
        return LibVlc.libvlc_media_player_set_rate(this.rawPlayer, Math.max(speed, 0.1f)) != -1;
    }

    @Override
    public Status status() {
        return Status.of(LibVlc.libvlc_media_player_get_state(this.rawPlayer));
    }

    @Override
    public boolean playing() {
        // OVERRWRITE STATUS JUST FOR PLAYING
        return LibVlc.libvlc_media_player_is_playing(this.rawPlayer) == 1;
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
        return LibVlc.libvlc_media_player_will_play(this.rawPlayer) == 1;
    }

    @Override
    public long duration() {
        return LibVlc.libvlc_media_player_get_length(this.rawPlayer);
    }

    @Override
    public long time() {
        return LibVlc.libvlc_media_player_get_time(this.rawPlayer);
    }

    @Override
    public void release() {
//        for (final libvlc_callback_t listener : this.nativeEvents) {
//            final libvlc_event_manager_t eventManager = LibVlc.libvlc_media_player_event_manager(this.rawPlayer);
//            for (final libvlc_event_e ev: libvlc_event_e.values()) {
//                LibVlc.libvlc_event_detach(eventManager, ev.intValue(), listener, null);
//            }
//        }
        LibVlc.libvlc_media_release(this.rawMedia);
        LibVlc.libvlc_media_player_release(this.rawPlayer);
        this.nativeBuffer = null;
        this.nativePointers = null;
        super.release();
    }

    private libvlc_media_stats_t getMediaStats() {
        if (LibVlc.libvlc_media_get_stats(this.rawMedia, this.rawStats) != 0)
            throw new IllegalStateException("Failed to get media stats");
        return new libvlc_media_stats_t();
    }
}
