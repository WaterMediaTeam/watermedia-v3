package org.watermedia.api.media.players;

import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.lwjgl.opengl.*;
import org.watermedia.api.render.RenderAPI;
import org.watermedia.tools.ThreadTool;
import org.watermedia.videolan4j.binding.internal.*;
import org.watermedia.videolan4j.binding.lib.Kernel32;
import org.watermedia.videolan4j.binding.lib.LibC;
import org.watermedia.videolan4j.binding.lib.LibVlc;
import org.watermedia.videolan4j.binding.lib.size_t;
import org.watermedia.videolan4j.tools.Buffers;
import org.watermedia.videolan4j.tools.Chroma;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class VLVideoPlayer extends VLAudioPlayer {
    // VIDEO
    private ByteBuffer[] nativeBuffers = new ByteBuffer[0];
    private Pointer[] nativePointers = new Pointer[0];
    private final int texture;

    // CONCURRENCY
    private final Semaphore semaphore = new Semaphore(1);
    private final Thread renderThread;
    private final boolean lockBuffers = true; // TODO: Make this configurable

    // STATE
    private boolean uploadFrame = false;
    private boolean firstFrame = true;

    // METADATA
    private int width = NO_SIZE;
    private int height = NO_SIZE;

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
        if (this.nativeBuffers.length == 0) return 0;

        // LOCK RENDER THREAD
        if (this.semaphore.tryAcquire()) {
            return 0; // RENDER THREAD IS BUSY
        }

        // UPDATE TEXTURE
        if (this.uploadFrame) {
            RenderAPI.updateTexture(this.texture, this.nativeBuffers, this.width, this.height, GL12.GL_BGRA, this.firstFrame);
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

    private final libvlc_video_format_cb videoFormatCB = (opaque, chromaPointer,widthPointer,heightPointer, pitchesPointer,linesPointer) -> {
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
        this.nativeBuffers = new ByteBuffer[planeCount];
        this.nativePointers = new Pointer[planeCount];
        for (int i = 0; i < planeCount; i++) {
            final ByteBuffer buffer = Buffers.alloc(pitchValues[i] * lineValues[i]);
            this.nativeBuffers[i] = buffer;
            this.nativePointers[i] = Pointer.createConstant(Buffers.address(buffer));
            if (this.lockBuffers) {
                if (!Platform.isWindows()) {
                    LibC.INSTANCE.mlock(this.nativePointers[i], new NativeLong(buffer.capacity()));
                } else {
                    Kernel32.INSTANCE.VirtualLock(this.nativePointers[i], new size_t(buffer.capacity()));
                }
            }
        }

        this.firstFrame = true; // RESET FIRST FRAME FLAG
        return this.nativeBuffers.length;
    };
    private final libvlc_video_cleanup_cb cleanupCB = opaque -> {
        if (this.nativeBuffers.length == 0) return;

        if (this.lockBuffers) {
            for (int i = 0; i < this.nativeBuffers.length; i++) {
                if (!Platform.isWindows()) {
                    LibC.INSTANCE.munlock(this.nativePointers[i], new NativeLong(this.nativeBuffers[i].capacity()));
                } else {
                    Kernel32.INSTANCE.VirtualUnlock(this.nativePointers[i], new size_t(this.nativeBuffers[i].capacity()));
                }
            }
        }
        this.nativeBuffers = new ByteBuffer[0];
        this.nativePointers = new Pointer[0];
    };
    private final libvlc_lock_callback_t lockCallback = (opaque, planes) -> {
        if (ThreadTool.tryAdquireLock(this.semaphore, 5, TimeUnit.SECONDS)) {
            planes.getPointer().write(0, this.nativePointers, 0, this.nativePointers.length);
            this.semaphore.release();
        }
        return null;
    };
    private final libvlc_unlock_callback_t unlockCallback = (opaque, picture, plane) -> {};
    private final libvlc_display_callback_t displayCallback = (opaque, picture) -> this.uploadFrame = true;
}