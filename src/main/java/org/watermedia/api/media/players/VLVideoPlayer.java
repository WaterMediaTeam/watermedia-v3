package org.watermedia.api.media.players;

import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL32;
import org.watermedia.api.render.RenderAPI;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.Kernel32;
import org.watermedia.videolan4j.binding.lib.LibC;
import org.watermedia.videolan4j.binding.lib.LibVlc;
import org.watermedia.videolan4j.binding.lib.size_t;
import org.watermedia.videolan4j.tools.Buffers;
import org.watermedia.videolan4j.tools.Chroma;

import java.awt.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class VLVideoPlayer extends VLMediaPlayer {
    private ByteBuffer[] buffers = new ByteBuffer[0];
    private Pointer[] pointers = new Pointer[0];
    private final int texture;
    private final Chroma chroma;
    private final boolean lockBuffers = true; // TODO: Make this configurable
    private final Semaphore semaphore = new Semaphore(1);
    private final Thread renderThread;

    private boolean uploadFrame = false;
    private boolean firstFrame = true;
    private boolean callbacks= false;
    
    private int width = NO_SIZE;
    private int height = NO_SIZE;
    private List<Object> events = new ArrayList<>();
    private VideoFormatCallback videoFormatCB;
    private VideoCleanupCallback cleanupCB;
    private LockCallback lockCallback;
    private UnlockCallback unlockCallback;
    private DisplayCallback displayCallback;

    public VLVideoPlayer(final URI mrl, final Chroma chroma, final Thread renderThread, final Executor renderThreadEx) {
        super(mrl, renderThreadEx);
        this.chroma = chroma;
        this.renderThread = renderThread;
        this.texture = RenderAPI.createTexture();
        libvlc_event_manager_t eventManager = LibVlc.libvlc_media_player_event_manager(this.rawPlayer);

//        LibVlc.libvlc_event_attach(eventManager, libvlc_event_e.libvlc_MediaPlayerOpening.intValue(), (event, userData) -> {
//            executor.execute(() -> {
//                if (this.callbacks) return; // CALLBACKS ALREADY SET
//
//                this.callbacks = true; // CALLBACKS SET
//            });
//        }, null);
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    @Override
    public void start() {
        LibVlc.libvlc_video_set_format_callbacks(this.rawPlayer, this.videoFormatCB = new VideoFormatCallback(this), this.cleanupCB = new VideoCleanupCallback(this));
        LibVlc.libvlc_video_set_callbacks(this.rawPlayer,
                this.lockCallback = new LockCallback(this),
                this.unlockCallback = new UnlockCallback(this),
                this.displayCallback = new DisplayCallback(this),
                Pointer.NULL);
        super.start();
    }

    public int preRender() {
        if (Thread.currentThread() != this.renderThread) {
            throw new IllegalStateException("This method must be called from the render thread!");
        }
        if (this.buffers.length == 0) return 0;

        // LOCK RENDER THREAD
        if (this.semaphore.tryAcquire()) {
            return 0; // RENDER THREAD IS BUSY
        }

        // UPDATE TEXTURE
        if (this.uploadFrame) {
            final int GL_FORMAT = switch (this.chroma) {
                case RGBA -> GL11.GL_RGBA;
                case RV32 -> GL12.GL_BGRA;
                case Rv24 -> GL12.GL_BGR;
                case GRAW -> GL12.GL_LUMINANCE; // TODO: ensure is just luminance
                case I420 -> throw new UnsupportedOperationException("I420 is unsupported for OpenGL");
                case YUYV -> throw new UnsupportedOperationException("YUYV is unsupported for OpenGL");
                case NV12 -> throw new UnsupportedOperationException("NV12 is unsupported for OpenGL");
                case UYVY -> throw new UnsupportedOperationException("UYVY is unsupported for OpenGL");
                default -> -1;
            };
            RenderAPI.updateTexture(this.texture, this.buffers, this.width(), this.height(), GL_FORMAT, this.firstFrame);
            this.uploadFrame = false;
        }


        // RELEASE RENDER THREAD
        this.semaphore.release();
        return this.texture;
    }

    @Override
    public int texture() {
        return this.texture;
    }

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        return this.height;
    }

    @Override
    public void release() {
        RenderAPI.releaseTexture(this.texture);
        super.release();
    }

    private record VideoFormatCallback(VLVideoPlayer player) implements libvlc_video_format_cb {
        @Override public int format(final PointerByReference op, final PointerByReference chroma, final IntByReference w, final IntByReference h, final PointerByReference pitch, final PointerByReference lines) { return this.player.displayFormat(op, chroma, w, h, pitch, lines); }
    }

    private record VideoCleanupCallback(VLVideoPlayer player) implements libvlc_video_cleanup_cb {
        @Override public void cleanup(final Pointer opaque) { this.player.cleanup(opaque); }
    }

    private record DisplayCallback(VLVideoPlayer player) implements libvlc_display_callback_t {
        @Override public void display(final Pointer opaque, final Pointer picture) { this.player.display(opaque, picture); }
    }

    private record LockCallback(VLVideoPlayer player) implements libvlc_lock_callback_t {
        @Override public Pointer lock(final Pointer opaque, final PointerByReference planes) { return this.player.preDisplay(opaque, planes); }
    }

    private record UnlockCallback(VLVideoPlayer player) implements libvlc_unlock_callback_t {
        @Override public void unlock(final Pointer opaque, final Pointer picture, final Pointer plane) { this.player.postDisplay(opaque, picture, plane); }
    }

    private Pointer preDisplay(final Pointer opaque, final PointerByReference planes) {
        try {
            if (this.semaphore.tryAcquire(5, TimeUnit.SECONDS)) { // WAIT FOR RENDER THREAD TO RELEASE
                planes.getPointer().write(0, this.pointers, 0, this.pointers.length);
                this.semaphore.release();
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private void display(final Pointer opaque, final Pointer picture) {
        this.uploadFrame = true;
    }

    private void postDisplay(final Pointer opaque, final Pointer picture, final Pointer plane) {

    }

    public int displayFormat(final PointerByReference opaque, final PointerByReference chromaPointer, final IntByReference widthPointer, final IntByReference heightPointer, final PointerByReference pitchesPointer, final PointerByReference linesPointer) {
        // APPLY CHROMF
        final byte[] chromaBytes = this.chroma.chroma();
        chromaPointer.getPointer().write(0, chromaBytes, 0, Math.min(chromaBytes.length, 4));
        // TODO: PERFORM FUTHER INVESTIGATION OF SET CUSTOM WIDTH AND HEIGHT VALUES PURPOSE.
        // width.setValue(chroma.getWidth());
        // height.setValue(chroma.getHeight());|
        this.width = widthPointer.getValue();
        this.height = heightPointer.getValue();
        widthPointer.setValue(this.width);
        heightPointer.setValue(this.height);
        final int[] pitchValues = this.chroma.getPitches(this.width);
        final int[] lineValues = this.chroma.getLines(this.height);
        final int planeCount = pitchValues.length;

        pitchesPointer.getPointer().write(0, pitchValues, 0, pitchValues.length);
        linesPointer.getPointer().write(0, lineValues, 0, lineValues.length);

        // ALLOCATE NATIVE BUFFERS
        this.buffers = new ByteBuffer[planeCount];
        this.pointers = new Pointer[planeCount];
        for (int i = 0; i < planeCount; i ++ ) {
            final ByteBuffer buffer = Buffers.alloc(pitchValues[i] * lineValues[i]);
            this.buffers[i] = buffer;
            this.pointers[i] = Pointer.createConstant(Buffers.address(buffer));
            if (this.lockBuffers) {
                if (!Platform.isWindows()) {
                    LibC.INSTANCE.mlock(this.pointers[i], new NativeLong(buffer.capacity()));
                } else {
                    Kernel32.INSTANCE.VirtualLock(this.pointers[i], new size_t(buffer.capacity()));
                }
            }
        }

        this.firstFrame = true; // RESET FIRST FRAME FLAG
        return this.buffers.length;
    }

    public void cleanup(final Pointer opaque) {
        if (this.buffers.length == 0) return;

        if (this.lockBuffers) {
            for (int i = 0; i < this.buffers.length; i++) {
                if (!Platform.isWindows()) {
                    LibC.INSTANCE.munlock(this.pointers[i], new NativeLong(this.buffers[i].capacity()));
                } else {
                    Kernel32.INSTANCE.VirtualUnlock(this.pointers[i], new size_t(this.buffers[i].capacity()));
                }
            }
        }
        this.buffers = new ByteBuffer[0];
        this.pointers = new Pointer[0];
    }
}
