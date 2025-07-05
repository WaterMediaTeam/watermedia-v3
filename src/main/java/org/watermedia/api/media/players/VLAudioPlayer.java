package org.watermedia.api.media.players;

import com.sun.jna.Pointer;
import org.lwjgl.openal.AL10;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.LibVlc;
import org.watermedia.videolan4j.tools.AudioFormat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

public class VLAudioPlayer extends VLMediaPlayer {
    protected static final AudioFormat AUDIO_FORMAT = AudioFormat.S16N_STEREO_96;

    private final libvlc_audio_play_cb vlcPlayCb = this::onNativePlay;
    private final libvlc_audio_pause_cb vlcPauseCb = this::onNativePause;
    private final libvlc_audio_resume_cb vlcResumeCb = this::onNativeResume;
    private final libvlc_audio_flush_cb vlcFlushCb = this::onNativeFlush;
    private final libvlc_audio_drain_cb vlcDrainCb = this::onNativeDrain;
    private final libvlc_audio_set_volume_cb vlcVolumeSetCb = this::onNativeVolumeSet;

    private final int alSources;
    private final int[] alBuffers = new int[4];
    private int alBufferIndex = 0;
    private AudioFormat currentFormat = AUDIO_FORMAT;

    public VLAudioPlayer(URI mrl, Executor renderThreadEx) {
        super(mrl, renderThreadEx);
        // Generate OpenAL sources and buffers
        this.alSources = AL10.alGenSources();
        AL10.alGenBuffers(this.alBuffers);
        // Revokes to VLC audio output and volume control, delegating to OpenAL
        this.setCurrentFormat(AUDIO_FORMAT);
        LibVlc.libvlc_audio_set_callbacks(this.rawPlayer, this.vlcPlayCb, this.vlcPauseCb, this.vlcResumeCb, this.vlcFlushCb, this.vlcDrainCb, Pointer.NULL);
        LibVlc.libvlc_audio_set_volume_callback(this.rawPlayer, this.vlcVolumeSetCb);
    }

    public void setCurrentFormat(final AudioFormat currentFormat) {
        this.currentFormat = currentFormat;
        LibVlc.libvlc_audio_set_format(this.rawPlayer, currentFormat.getFormatName(), currentFormat.getSampleRate(), currentFormat.getChannelCount());
    }

    private void onNativePlay(final Pointer pointer /* POINTER OF WHAT? IDK*/, final Pointer samples, final int count, final long pts) {
        // READ
        final ByteBuffer data = samples.getByteBuffer(0L, AUDIO_FORMAT.calculateBufferSize(count));

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

        final var alFormat = switch (this.currentFormat) {
            case U8_MONO_44, U8_STEREO_44, U8_SURROUND_44, U8_SURROUND71_44 -> AL10.AL_FORMAT_MONO8;
            case S16N_MONO_44, S16N_MONO_48, S16N_MONO_96 -> AL10.AL_FORMAT_MONO16;
            case S16N_STEREO_44, S16N_STEREO_48, S16N_STEREO_96 -> AL10.AL_FORMAT_STEREO16;
            default -> throw new IllegalArgumentException("Unsupported audio format: " + this.currentFormat);
        };

        AL10.alBufferData(buffer, alFormat, data, this.currentFormat.getSampleRate());
        AL10.alSourceQueueBuffers(this.alSources, buffer);

        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.alSources);
        }
    }

    private void onNativePause(final Pointer data, final long pts) {
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PAUSED) {
            AL10.alSourcePause(this.alSources);
        }
    }

    private void onNativeResume(final Pointer data, final long pts) {
        if (AL10.alGetSourcei(this.alSources, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.alSources);
        }
    }

    private void onNativeFlush(final Pointer data, final long pts) {
        // Handle audio flush
    }

    private void onNativeDrain(final Pointer data) {
        // Handle audio drain
    }

    private void onNativeVolumeSet(final Pointer data, final float volume, final int mute) {
        AL10.alSourcef(this.alSources, AL10.AL_GAIN, volume);
    }
}
