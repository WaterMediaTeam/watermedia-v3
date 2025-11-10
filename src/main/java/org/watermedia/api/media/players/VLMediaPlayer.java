package org.watermedia.api.media.players;

import com.sun.jna.Pointer;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.openal.AL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.platforms.ALEngine;
import org.watermedia.api.media.platforms.GLEngine;
import org.watermedia.videolan4j.VideoLan4J;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.LibC;
import org.watermedia.videolan4j.binding.lib.LibVlc;
import org.watermedia.videolan4j.tools.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.watermedia.WaterMedia.LOGGER;

public final class VLMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(VLMediaPlayer.class.getSimpleName());
    private static final Chroma CHROMA = Chroma.RV32;
    private static final AudioFormat AUDIO_FORMAT = AudioFormat.S16N_STEREO_96;

    // VIDEO BUFFERS
    private ByteBuffer nativeBuffer = null;
    private Pointer nativePointer = null;

    // VIDEOLAN CORE
    private final libvlc_media_player_t rawPlayer = VideoLan4J.createMediaPlayer();
    private final libvlc_media_stats_t rawStats = null;
    private final libvlc_event_manager_t rawPlayerEvents;

    // MEDIA LIST PARA MANEJAR MÚLTIPLES CALIDADES
    private libvlc_media_list_t mediaList;
    private libvlc_media_list_player_t mediaListPlayer;
    private libvlc_event_manager_t mediaListEvents;

    // MEDIA ACTUAL
    private libvlc_media_t currentMedia;
    private int currentMediaIndex = 0;

    // FLAGS DE ESTADO
    private volatile boolean loadingSources = false;
    private volatile boolean changingQuality = false;
    private long savedPosition = -1;
    private boolean wasPlaying = false;

    public VLMediaPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx,
                         GLEngine glEngine, ALEngine alEngine, final boolean video, final boolean audio) {
        super(mrl, renderThread, renderThreadEx, glEngine, alEngine, video, audio);

        // Crear media list y list player
        this.mediaList = LibVlc.libvlc_media_list_new(VideoLan4J.getDefaultInstance());
        this.mediaListPlayer = LibVlc.libvlc_media_list_player_new(VideoLan4J.getDefaultInstance());

        // Asociar el media player con el list player
        LibVlc.libvlc_media_list_player_set_media_player(this.mediaListPlayer, this.rawPlayer);

        // SETUP AUDIO
        if (this.isAudio()) {
            LibVlc.libvlc_audio_set_callbacks(this.rawPlayer, this.playCB, null, null, null, null, Pointer.NULL);
            LibVlc.libvlc_audio_set_volume_callback(this.rawPlayer, this.volumeCB);
            LibVlc.libvlc_audio_set_format(this.rawPlayer, AUDIO_FORMAT.getFormatName(), AUDIO_FORMAT.getSampleRate(), AUDIO_FORMAT.getChannelCount());
        }

        // SETUP VIDEO
        if (this.isVideo()) {
            LibVlc.libvlc_video_set_format_callbacks(this.rawPlayer, this.formatCB, this.cleanupCB);
            LibVlc.libvlc_video_set_callbacks(this.rawPlayer, this.lockCB, this.unlockCB, this.displayCB, Pointer.NULL);
        }

        // REGISTER EVENTS
        this.rawPlayerEvents = LibVlc.libvlc_media_player_event_manager(this.rawPlayer);
        LibVlc.libvlc_event_attach(this.rawPlayerEvents, libvlc_event_e.libvlc_MediaPlayerEndReached.intValue(),
                this.endReachedCallback, null);
        LibVlc.libvlc_event_attach(this.rawPlayerEvents, libvlc_event_e.libvlc_MediaPlayerPlaying.intValue(),
                this.playingCallback, null);

        // Events para media list
        this.mediaListEvents = LibVlc.libvlc_media_list_player_event_manager(this.mediaListPlayer);
        LibVlc.libvlc_event_attach(this.mediaListEvents, libvlc_event_e.libvlc_MediaListPlayerPlayed.intValue(),
                this.listPlayedCallback, null);
    }

    /**
     * Actualizar la media cuando cambian las fuentes o la calidad
     */
    @Override
    protected void updateMedia() {
        LibVlc.libvlc_media_list_player_play_item_at_index(this.mediaListPlayer, this.currentMediaIndex);
        if (this.changingQuality) {
            return; // Ya estamos cambiando, evitar recursión
        }

        this.changingQuality = true;

        // Guardar estado actual si está reproduciendo
        if (this.playing()) {
            this.savedPosition = this.time();
            this.wasPlaying = true;
            this.pause();
        } else {
            this.savedPosition = this.time();
            this.wasPlaying = false;
        }

        // Reconstruir la media list con las nuevas fuentes/calidad
        this.rebuildMediaList();

        this.changingQuality = false;
    }

    /**
     * Reconstruir la lista de media con todas las fuentes disponibles
     */
    private void rebuildMediaList() {
        // Detener reproducción actual
        LibVlc.libvlc_media_list_player_stop(this.mediaListPlayer);

        // Limpiar lista anterior
        if (this.mediaList != null) {
            final int count = LibVlc.libvlc_media_list_count(this.mediaList);
            for (int i = count - 1; i >= 0; i--) {
                LibVlc.libvlc_media_list_remove_index(this.mediaList, i);
            }
        }

        // Bloquear la lista para modificaciones
        LibVlc.libvlc_media_list_lock(this.mediaList);

        try {
            // Agregar todas las fuentes con la calidad seleccionada
            for (int i = 0; i < this.sources.length; i++) {
                final URI uri = this.sources[i].getURI(this.selectedQuality);
                final libvlc_media_t media = VideoLan4J.createMediaInstance(uri);

                // Agregar opciones específicas si es necesario
                if (i == this.sourceIndex && this.savedPosition > 0) {
                    // Para la media actual, agregar tiempo de inicio
                    LibVlc.libvlc_media_add_option(media, "start-time=" + (this.savedPosition / 1000));
                }

                // Agregar a la lista
                LibVlc.libvlc_media_list_add_media(this.mediaList, media);

                // Liberar referencia local
                LibVlc.libvlc_media_release(media);
            }

            // Establecer la lista en el player
            LibVlc.libvlc_media_list_player_set_media_list(this.mediaListPlayer, this.mediaList);

            // Saltar al índice correcto
            if (this.sourceIndex >= 0 && this.sourceIndex < this.sources.length) {
                LibVlc.libvlc_media_list_player_play_item_at_index(this.mediaListPlayer, this.sourceIndex);

                // Si estábamos reproduciendo, continuar
                if (this.wasPlaying) {
                    this.renderThreadEx.execute(() -> {
                        // Esperar un poco para que se cargue
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

                        if (this.savedPosition > 0) {
                            this.seek(this.savedPosition);
                        }
                        this.resume();
                    });
                }
            }

        } finally {
            // Desbloquear la lista
            LibVlc.libvlc_media_list_unlock(this.mediaList);
        }
    }

    // CALLBACKS
    private final libvlc_callback_t endReachedCallback = (event, userData) -> {
        if (this.repeat()) {
            this.renderThreadEx.execute(() -> {
                this.start(); // TODO: WHAT SHOULD BE THE REAL BEHAVIOR, NEXT SOURCE, RESTART, NEXT SOURCE AND RESTART?
//                if (this.sourceIndex < this.sources.length - 1) {
//                    // Ir a la siguiente fuente
//                    this.selectSource(this.sourceIndex + 1);
//                } else {
//                    // Volver al principio
//                    this.selectSource(0);
//                }
            });
        }
    };

    private final libvlc_callback_t playingCallback = (event, userData) -> {
        // Actualizar el media actual cuando empieza a reproducir
        this.currentMedia = LibVlc.libvlc_media_player_get_media(this.rawPlayer);
    };

    private final libvlc_callback_t listPlayedCallback = (event, userData) -> {
        LOGGER.debug(IT, "Media list finished playing all items");
        if (this.repeat()) {
            this.renderThreadEx.execute(() -> this.setSourceIndex(0));
        }
    };

    private final libvlc_video_format_cb formatCB = (opaque, chromaPointer, widthPointer, heightPointer,
                                                     pitchesPointer, linesPointer) -> {
        // APPLY CHROMA
        final byte[] chromaBytes = CHROMA.chroma();
        chromaPointer.getPointer().write(0, chromaBytes, 0, Math.min(chromaBytes.length, 4));
        this.setVideoFormat(GL12.GL_BGRA, widthPointer.getValue(), heightPointer.getValue());
        final int[] pitchValues = CHROMA.getPitches(this.width());
        final int[] lineValues = CHROMA.getLines(this.height());
        final int planeCount = pitchValues.length;

        pitchesPointer.getPointer().write(0, pitchValues, 0, pitchValues.length);
        linesPointer.getPointer().write(0, lineValues, 0, lineValues.length);

        // ALLOCATE NATIVE BUFFERS - I AM ASSUMING THAT ITS ONLY ONE PLANE (AS IT IS FOR RV32)
        this.nativeBuffer = Buffers.alloc(pitchValues[0] * lineValues[0]);
        this.nativePointer = Pointer.createConstant(Buffers.address(this.nativeBuffer));
        LibC.memoryLock(this.nativePointer, this.nativeBuffer.capacity());

        return planeCount;
    };
    private final libvlc_video_cleanup_cb cleanupCB = opaque -> {
        if (this.nativeBuffer == null) return;

        LibC.memoryUnlock(this.nativePointer, this.nativeBuffer.capacity());
        this.nativeBuffer = null;
        this.nativePointer = null;
    };

    private final libvlc_lock_callback_t lockCB = (opaque, planes) -> {
        if (this.nativeBuffer == null) return null; // nativePointers are never null when nativeBuffers is not null.

        synchronized (this.nativeBuffer) { // Doesn't matter if is not synchronized a constant buffer.
            planes.getPointer().setPointer(0, this.nativePointer);
        }
        return null;
    };

    private final libvlc_display_callback_t displayCB = (opaque, picture) -> this.upload(this.nativeBuffer, 0);
    private final libvlc_unlock_callback_t unlockCB = (opaque, picture, plane) -> {};
    private final libvlc_audio_play_cb playCB = (pointer, samples, count, pts) ->
            this.upload(samples.getByteBuffer(0L, AUDIO_FORMAT.calculateBufferSize(count)), AL11.AL_FORMAT_STEREO16, AUDIO_FORMAT.getSampleRate(), AUDIO_FORMAT.getChannelCount());

    private final libvlc_audio_set_volume_cb volumeCB = (data, volume, mute) -> {};

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
        if (this.loadingSources) return;

        this.loadingSources = true;
        this.openSources(() -> {
            // Construir la lista de media con todas las fuentes
            this.rebuildMediaList();

            // Comenzar reproducción desde el índice actual
            LibVlc.libvlc_media_list_player_play_item_at_index(this.mediaListPlayer, this.sourceIndex);

            this.loadingSources = false;
        });
    }

    @Override
    public void startPaused() {
        this.start();
        this.renderThreadEx.execute(() -> {
            // Esperar un poco para que inicie
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            this.pause();
        });
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
        super.pause(paused);
        return true;
    }

    @Override
    public boolean stop() {
        LibVlc.libvlc_media_list_player_stop(this.mediaListPlayer);
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
        return this.skipTime(5000);
    }

    @Override
    public boolean rewind() {
        return this.skipTime(-5000);
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
        if (this.loadingSources || this.changingQuality) {
            return Status.LOADING;
        }
        return Status.of(LibVlc.libvlc_media_player_get_state(this.rawPlayer));
    }

    @Override
    public boolean playing() {
        return LibVlc.libvlc_media_player_is_playing(this.rawPlayer) == 1;
    }

    @Override
    public boolean validSource() {
        return this.currentMedia != null || this.mediaList != null;
    }

    @Override
    public boolean liveSource() {
        // VLC puede detectar si es un stream en vivo
        if (this.currentMedia != null) {
            return LibVlc.libvlc_media_get_duration(this.currentMedia) == -1;
        }
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
        this.repeat(false);

        // Detener reproducción
        if (this.mediaListPlayer != null) {
            LibVlc.libvlc_media_list_player_stop(this.mediaListPlayer);
        }

        // Detach eventos
        if (this.rawPlayerEvents != null) {
            LibVlc.libvlc_event_detach(this.rawPlayerEvents, libvlc_event_e.libvlc_MediaPlayerEndReached.intValue(),
                    this.endReachedCallback, null);
            LibVlc.libvlc_event_detach(this.rawPlayerEvents, libvlc_event_e.libvlc_MediaPlayerPlaying.intValue(),
                    this.playingCallback, null);
        }

        if (this.mediaListEvents != null) {
            LibVlc.libvlc_event_detach(this.mediaListEvents, libvlc_event_e.libvlc_MediaListPlayerPlayed.intValue(),
                    this.listPlayedCallback, null);
        }

        // Liberar recursos de VLC
        if (this.currentMedia != null) {
            LibVlc.libvlc_media_release(this.currentMedia);
            this.currentMedia = null;
        }

        if (this.mediaListPlayer != null) {
            LibVlc.libvlc_media_list_player_release(this.mediaListPlayer);
            this.mediaListPlayer = null;
        }

        if (this.mediaList != null) {
            LibVlc.libvlc_media_list_release(this.mediaList);
            this.mediaList = null;
        }

        if (this.rawPlayer != null) {
            LibVlc.libvlc_media_player_release(this.rawPlayer);
        }

        this.nativeBuffer = null;
        this.nativePointer = null;

        super.release();
    }

    private libvlc_media_stats_t getMediaStats() {
        if (this.currentMedia != null && LibVlc.libvlc_media_get_stats(this.currentMedia, this.rawStats) != 0) {
            throw new IllegalStateException("Failed to get media stats");
        }
        return this.rawStats;
    }

    public static boolean load(WaterMedia instance) {
        LOGGER.info(IT, "Starting LibVLC...");
        if (VideoLan4J.load("--no-quiet", "--verbose=2")) {
            LOGGER.info(IT, "Created new LibVLC instance");
            VideoLan4J.setBufferAllocator(MemoryUtil::memAlignedAlloc);
            VideoLan4J.setBufferDeallocator(MemoryUtil::memAlignedFree);
            LOGGER.info(IT, "Overrided LibVLC buffer allocator/deallocator");
            LOGGER.info(IT, "LibVLC started, running version {}", VideoLan4J.getLibVersion());
            return true;
        } else {
            LOGGER.error(IT, "Failed to load LibVLC");
            return false;
        }
    }

    public static boolean loaded() {
        return VideoLan4J.isDiscovered();
    }
}