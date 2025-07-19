package org.watermedia.api.media.player;

import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.opengl.GL12;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.tools.ThreadTool;
import org.watermedia.tools.TimeTool;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.Kernel32;
import org.watermedia.videolan4j.binding.lib.LibC;
import org.watermedia.videolan4j.binding.lib.LibVlc;
import org.watermedia.videolan4j.binding.lib.size_t;
import org.watermedia.videolan4j.tools.AudioFormat;
import org.watermedia.videolan4j.tools.Buffers;
import org.watermedia.videolan4j.tools.Chroma;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;

public final class VLMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(VLMediaPlayer.class.getSimpleName());
    private static final Chroma CHROMA = Chroma.RV32; // Use RV32 aka BGRA format for video buffers
    private static final AudioFormat AUDIO_FORMAT = AudioFormat.S16N_STEREO_96;

    // VIDEO BUFFERS
    private ByteBuffer[] nativeBuffers = new ByteBuffer[0];
    private Pointer[] nativePointers = new Pointer[0];

    // VIDEOLAN
    private final libvlc_media_player_t rawPlayer;
    private final libvlc_media_t rawMedia;
    private final libvlc_media_stats_t rawStats = null;

    public VLMediaPlayer(URI mrl, Thread renderThread, Executor renderThreadEx, boolean video, boolean audio) {
        super(mrl, renderThread, renderThreadEx, video, audio);
        this.rawPlayer = VideoLan4J.createMediaPlayer(MediaAPI.getVlcInstance());
        this.rawMedia = VideoLan4J.createMediaInstance(MediaAPI.getVlcInstance(), this.mrl);
        LibVlc.libvlc_media_player_set_media(this.rawPlayer, this.rawMedia);

        // SETUP AUDIO
        if (this.isAudio()) {
            LibVlc.libvlc_audio_set_callbacks(this.rawPlayer, this.vlcPlayCb, this.vlcPauseCb, this.vlcResumeCb, this.vlcFlushCb, this.vlcDrainCb, Pointer.NULL);
            LibVlc.libvlc_audio_set_volume_callback(this.rawPlayer, this.vlcVolumeSetCb);
            LibVlc.libvlc_audio_set_format(this.rawPlayer, AUDIO_FORMAT.getFormatName(), AUDIO_FORMAT.getSampleRate(), AUDIO_FORMAT.getChannelCount());
        }

        // SETUP VIDEO
        if (this.isVideo()) {
            LibVlc.libvlc_video_set_format_callbacks(this.rawPlayer, this.videoFormatCB, this.cleanupCB);
            LibVlc.libvlc_video_set_callbacks(this.rawPlayer, this.lockCallback, this.unlockCallback, this.displayCallback, Pointer.NULL);
        }

        // REGISTER EVENTS
        final libvlc_event_manager_t eventManager = LibVlc.libvlc_media_player_event_manager(this.rawPlayer);
    }

    private final libvlc_video_format_cb videoFormatCB = (opaque, chromaPointer, widthPointer, heightPointer, pitchesPointer, linesPointer) -> {
        // APPLY CHROMA
        final byte[] chromaBytes = CHROMA.chroma();
        chromaPointer.getPointer().write(0, chromaBytes, 0, Math.min(chromaBytes.length, 4));
        this.setVideoFormat(GL12.GL_BGRA, widthPointer.getValue(), heightPointer.getValue());
        widthPointer.setValue(this.width());
        heightPointer.setValue(this.height());
        final int[] pitchValues = Chroma.RV32.getPitches(this.width());
        final int[] lineValues = Chroma.RV32.getLines(this.height());
        final int planeCount = pitchValues.length;

        pitchesPointer.getPointer().write(0, pitchValues, 0, pitchValues.length);
        linesPointer.getPointer().write(0, lineValues, 0, lineValues.length);

        // ALLOCATE NATIVE BUFFERS
        this.nativeBuffers = new ByteBuffer[planeCount];
        this.nativePointers = new Pointer[planeCount];
        for (int i = 0; i < planeCount; i++) {
            final ByteBuffer buffer = Buffers.alloc(pitchValues[i] * lineValues[i]);
            this.nativeBuffers[i] = buffer;
            this.nativePointers[i] = Pointer.createConstant(Buffers.address(buffer));
            // LOCK NATIVE BUFFER - OPTIONAL
            if (!Platform.isWindows()) {
                LibC.INSTANCE.mlock(this.nativePointers[i], new NativeLong(buffer.capacity()));
            } else {
                Kernel32.INSTANCE.VirtualLock(this.nativePointers[i], new size_t(buffer.capacity()));
            }
        }

        return this.nativeBuffers.length;
    };
    private final libvlc_video_cleanup_cb cleanupCB = opaque -> {
        if (this.nativeBuffers.length == 0) return;

        for (int i = 0; i < this.nativeBuffers.length; i++) {
            if (!Platform.isWindows()) {
                LibC.INSTANCE.munlock(this.nativePointers[i], new NativeLong(this.nativeBuffers[i].capacity()));
            } else {
                Kernel32.INSTANCE.VirtualUnlock(this.nativePointers[i], new size_t(this.nativeBuffers[i].capacity()));
            }
        }
        this.nativeBuffers = new ByteBuffer[0];
        this.nativePointers = new Pointer[0];
    };
    private final libvlc_lock_callback_t lockCallback = (opaque, planes) -> {
        if (ThreadTool.tryAdquireLock(this.glSemaphore, 5, TimeUnit.SECONDS)) {
            planes.getPointer().write(0, this.nativePointers, 0, this.nativePointers.length);
            this.glSemaphore.release();
        }
        return null;
    };
    private final libvlc_display_callback_t displayCallback = (opaque, picture) -> this.uploadVideoFrame(this.nativeBuffers);

    private final libvlc_unlock_callback_t unlockCallback = (opaque, picture, plane) -> {

    };

    private final libvlc_audio_play_cb vlcPlayCb = (pointer, samples, count, pts) -> {
        final ByteBuffer data = samples.getByteBuffer(0L, AUDIO_FORMAT.calculateBufferSize(count));
        this.uploadAudioBuffer(data, AL11.AL_FORMAT_STEREO16, AUDIO_FORMAT.getSampleRate(), AUDIO_FORMAT.getChannelCount());
    };

    private final libvlc_audio_pause_cb vlcPauseCb = (data, pts) -> {
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(this.alSources);
        }
    };

    private final libvlc_audio_resume_cb vlcResumeCb = (data, pts) -> {
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.alSources);
        }
    };

    private final libvlc_audio_flush_cb vlcFlushCb = (data, pts) -> {
        // Handle audio flush
    };

    private final libvlc_audio_drain_cb vlcDrainCb = data -> {
        // Handle audio drain
    };

    private final libvlc_audio_set_volume_cb vlcVolumeSetCb = (data, volume, mute) ->
        AL10.alSourcef(this.alSources, AL10.AL_GAIN, volume);

    @Override
    public boolean previousFrame() {
//        LibVlc.libvlc_media_player_previous_frame(this.rawPlayer);
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
        this.nativeBuffers = new ByteBuffer[0];
        this.nativePointers = new Pointer[0];
        super.release();
    }

    private libvlc_media_stats_t getMediaStats() {
        if (LibVlc.libvlc_media_get_stats(this.rawMedia, this.rawStats) != 0)
            throw new IllegalStateException("Failed to get media stats");
        return new libvlc_media_stats_t();
    }
}
