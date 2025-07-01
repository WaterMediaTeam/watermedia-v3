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

public final class VLVideoPlayer extends VLMediaPlayer {
    private ByteBuffer[] buffers = new ByteBuffer[0];
    private Pointer[] pointers = new Pointer[0];
    private final int texture;
    private final boolean lockBuffers = true; // TODO: Make this configurable
    private final Semaphore semaphore = new Semaphore(1);
    private final Thread renderThread;

    private boolean uploadFrame = false;
    private boolean firstFrame = true;
    private boolean callbacks = false;
    
    private int width = NO_SIZE;
    private int height = NO_SIZE;

    private final libvlc_video_format_cb videoFormatCB = this::displayFormat;
    private final libvlc_video_cleanup_cb cleanupCB = this::cleanup;
    private final libvlc_lock_callback_t lockCallback = this::preDisplay;
    private final libvlc_unlock_callback_t unlockCallback = this::postDisplay;
    private final libvlc_display_callback_t displayCallback = this::display;

    public VLVideoPlayer(final URI mrl, final Thread renderThread, final Executor renderThreadEx) {
        super(mrl, renderThreadEx);
        this.renderThread = renderThread;
        this.texture = RenderAPI.createTexture();
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
    public void start() {
        LibVlc.libvlc_video_set_format_callbacks(this.rawPlayer, this.videoFormatCB, this.cleanupCB);
        LibVlc.libvlc_video_set_callbacks(this.rawPlayer, this.lockCallback, this.unlockCallback, this.displayCallback, Pointer.NULL);
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
            RenderAPI.updateTexture(this.texture, this.buffers, this.width, this.height, GL12.GL_BGRA, this.firstFrame);
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
    public void release() {
        RenderAPI.releaseTexture(this.texture);
        super.release();
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

    private int displayFormat(final PointerByReference opaque, final PointerByReference chromaPointer, final IntByReference widthPointer, final IntByReference heightPointer, final PointerByReference pitchesPointer, final PointerByReference linesPointer) {
        // APPLY CHROMA
        final byte[] chromaBytes = Chroma.RV32.chroma();
        chromaPointer.getPointer().write(0, chromaBytes, 0, Math.min(chromaBytes.length, 4));
        // TODO: PERFORM FUTHER INVESTIGATION OF SET CUSTOM WIDTH AND HEIGHT VALUES PURPOSE.
        // width.setValue(chroma.getWidth());
        // height.setValue(chroma.getHeight());|
        this.width = widthPointer.getValue();
        this.height = heightPointer.getValue();
        widthPointer.setValue(this.width);
        heightPointer.setValue(this.height);
        final int[] pitchValues = Chroma.RV32.getPitches(this.width);
        final int[] lineValues = Chroma.RV32.getLines(this.height);
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

    private void cleanup(final Pointer opaque) {
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
