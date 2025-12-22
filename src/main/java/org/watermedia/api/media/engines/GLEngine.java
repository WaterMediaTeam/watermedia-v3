package org.watermedia.api.media.engines;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.tools.functions.BiIntConsumer;
import org.watermedia.tools.functions.TriIntConsumer;

import java.nio.ByteBuffer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * OpenGL engine with PBO double-buffering.
 *
 * V6.1 - STRIDE FIX
 * Corrección crítica: el tamaño de memoria debe usar stride, no width.
 * stride >= width siempre, y cuando stride > width hay padding por fila.
 */
public final class GLEngine {

    private static final int NUM_PBOS = 4;
    private static final int BYTES_PER_PIXEL = 4; // RGBA/BGRA

    private final IntSupplier genTexture;
    private final BiIntConsumer bindTexture;
    private final TriIntConsumer texParameter;
    private final BiIntConsumer pixelStore;
    private final IntConsumer delTexture;

    // PBO state
    private final int[] pboIds = new int[NUM_PBOS];
    private int pboWriteIndex = 0;
    private int pboReadIndex = 1;
    private boolean pboInitialized = false;
    private boolean usePboPath = false;

    public GLEngine(final IntSupplier genTexture, final BiIntConsumer bindTexture, final TriIntConsumer texParameter, final BiIntConsumer pixelStore, final IntConsumer delTexture) {
        WaterMedia.checkIsClientSideOrThrow(GLEngine.class);
        if (genTexture == null || bindTexture == null || texParameter == null
                || pixelStore == null || delTexture == null) {
            throw new IllegalArgumentException("All parameters must be non-null");
        }
        this.genTexture = genTexture;
        this.bindTexture = bindTexture;
        this.texParameter = texParameter;
        this.pixelStore = pixelStore;
        this.delTexture = delTexture;
    }

    public int createTexture() {
        final int texture = this.genTexture.getAsInt();
        this.bindTexture.accept(GL11.GL_TEXTURE_2D, texture);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        this.bindTexture.accept(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    public void uploadTexture(final int texture, final ByteBuffer buffer, final int stride, final int width, final int height, final int format, final boolean firstFrame) {
        if (!this.pboInitialized) {
            this.initializePBOs();
            this.usePboPath = false;
        }

        // Obtener puntero nativo
        final long memoryAddress = MemoryUtil.memAddress(buffer);

        // CRÍTICO: El tamaño de memoria debe usar STRIDE, no WIDTH
        // stride = pixels por fila en memoria (incluyendo padding)
        // width = pixels visibles por fila
        // Cuando stride > width, hay (stride - width) pixels de padding por fila
        final long dataSize = (long) stride * height * BYTES_PER_PIXEL;

        this.bindTexture.accept(GL11.GL_TEXTURE_2D, texture);

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, stride);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (firstFrame || !this.usePboPath) {
            // Camino directo - primer frame
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                    format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, memoryAddress);

            // Preparar PBO para siguiente frame
            this.preparePboNative(this.pboWriteIndex, dataSize, memoryAddress);

            this.swapPboIndices();
            this.usePboPath = true;
        } else {
            // Camino PBO - frames subsecuentes

            // GPU lee del PBO de lectura
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pboIds[this.pboReadIndex]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);

            // CPU escribe al PBO de escritura
            this.preparePboNative(this.pboWriteIndex, dataSize, memoryAddress);

            this.swapPboIndices();
        }

        // Cleanup
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    private void preparePboNative(final int pboIndex, final long sizeBytes, final long dataAddress) {
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pboIds[pboIndex]);
        GL15.nglBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, sizeBytes, dataAddress, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    private void swapPboIndices() {
        this.pboReadIndex++;
        this.pboWriteIndex++;
        if (this.pboReadIndex >= NUM_PBOS) this.pboReadIndex = 0;
        if (this.pboWriteIndex >= NUM_PBOS) this.pboWriteIndex = 0;
    }

    private void initializePBOs() {
        if (this.pboInitialized) this.release();
        GL15.glGenBuffers(this.pboIds);
        this.pboInitialized = true;
        this.pboWriteIndex = 0;
        this.pboReadIndex = 1;
    }

    public void deleteTexture(final int texture) {
        this.delTexture.accept(texture);
    }

    public void release() {
        if (this.pboInitialized) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(this.pboIds);
            this.pboInitialized = false;
            this.usePboPath = false;
        }
    }

    public void reset() {
        this.usePboPath = false;
        this.pboWriteIndex = 0;
        this.pboReadIndex = 1;
    }

    public static class Builder {
        private IntSupplier genTexture = GL11::glGenTextures;
        private BiIntConsumer bindTexture = GL11::glBindTexture;
        private TriIntConsumer texParameter = GL11::glTexParameteri;
        private BiIntConsumer pixelStore = GL11::glPixelStorei;
        private IntConsumer delTexture = GL11::glDeleteTextures;

        public Builder setGenTexture(final IntSupplier f) { this.genTexture = f; return this; }
        public Builder setBindTexture(final BiIntConsumer f) { this.bindTexture = f; return this; }
        public Builder setTexParameter(final TriIntConsumer f) { this.texParameter = f; return this; }
        public Builder setPixelStore(final BiIntConsumer f) { this.pixelStore = f; return this; }
        public Builder setDelTexture(final IntConsumer f) { this.delTexture = f; return this; }
        public GLEngine build() {
            return new GLEngine(this.genTexture, this.bindTexture, this.texParameter, this.pixelStore, this.delTexture);
        }
    }
}