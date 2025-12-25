package org.watermedia.api.media.engines;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
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

import static org.watermedia.WaterMedia.LOGGER;

/**
 * OpenGL engine with PBO double-buffering.
 * V7.1 - SIMPLE DOUBLE-BUFFER
 * - Frame 0: Direct upload (sync) + prepare PBO[0]
 * - Frame N: Read from PBO[index], prepare PBO[nextIndex]
 * - 1 frame latency after first frame (expected behavior)
 */
public final class GLEngine {
    private static final Marker IT = MarkerManager.getMarker(GLEngine.class.getSimpleName());

    private static final int NUM_PBOS = 3;
    private static final int BYTES_PER_PIXEL = 4; // RGBA/BGRA

    private final IntSupplier genTexture;
    private final BiIntConsumer bindTexture;
    private final TriIntConsumer texParameter;
    private final BiIntConsumer pixelStore;
    private final IntConsumer delTexture;

    // PBO state
    private final int[] pboIds = new int[NUM_PBOS];
    private int index = 0;
    private boolean pboInitialized = false;
    private boolean pboReady = false;
    private long allocatedPboSize = 0;

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

        this.checkGLError("createTexture");
        return texture;
    }

    private int nextIndex() {
        return (this.index + 1) % NUM_PBOS;
    }

    public void uploadTexture(final int texture, final ByteBuffer buffer, final int stride, final int width, final int height, final int format, final boolean firstFrame) {
        // VALIDATE INPUT PARAMETERS
        if (buffer == null) {
            LOGGER.warn(IT, "uploadTexture called with null buffer - skipping");
            return;
        }

        if (width <= 0 || height <= 0) {
            LOGGER.warn(IT, "Invalid dimensions: width={}, height={}", width, height);
            return;
        }

        if (stride != 0 && stride < width) {
            LOGGER.warn(IT, "Invalid stride: stride ({}) < width ({})", stride, width);
            return;
        }

        if (!this.pboInitialized) {
            this.initializePBOs();
        }

        final int effectiveStride = (stride == 0) ? width : stride;
        final long dataSize = (long) effectiveStride * height * BYTES_PER_PIXEL;
        final int bufferCapacity = buffer.remaining();

        if (!buffer.isDirect() && bufferCapacity < dataSize) {
            LOGGER.warn(IT, "Buffer too small: capacity={} bytes, required={} bytes (stride={}, height={}, bpp={})",
                    bufferCapacity, dataSize, stride, height, BYTES_PER_PIXEL);
            return;
        }

        final long memoryAddress = MemoryUtil.memAddress(buffer);
        if (memoryAddress == 0L) {
            LOGGER.warn(IT, "Failed to get native memory address from buffer");
            return;
        }

        this.bindTexture.accept(GL11.GL_TEXTURE_2D, texture);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, stride);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        // SIZE CHANGED - RESET PBO STATE
        if (this.pboReady && this.allocatedPboSize != dataSize) {
            this.pboReady = false;
        }

        if (firstFrame || !this.pboReady) {
            // FIRST FRAME: DIRECT SYNC UPLOAD + PREPARE PBO FOR NEXT FRAME
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                    format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, memoryAddress);
            this.checkGLError("glTexImage2D direct");

            // PREPARE PBO[0] WITH CURRENT DATA FOR NEXT FRAME
            this.fillPbo(0, dataSize, memoryAddress);

            this.index = 0;
            this.allocatedPboSize = dataSize;
            this.pboReady = true;
        } else {
            // PBO PATH: READ FROM CURRENT, PREPARE NEXT
            final int readIndex = this.index;
            final int writeIndex = this.nextIndex();

            // GPU READS FROM CURRENT PBO
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pboIds[readIndex]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
            this.checkGLError("glTexSubImage2D from PBO");

            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            // CPU PREPARES NEXT PBO WITH CURRENT FRAME DATA
            this.fillPbo(writeIndex, dataSize, memoryAddress);

            // SWAP INDEX
            this.index = writeIndex;
        }

        // CLEANUP
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    private void fillPbo(final int pboIndex, final long sizeBytes, final long dataAddress) {
        final int pboId = this.pboIds[pboIndex];
        if (pboId == 0) {
            LOGGER.warn(IT, "PBO[{}] has invalid ID (0)", pboIndex);
            return;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboId);
        GL15.nglBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, sizeBytes, dataAddress, GL15.GL_STREAM_DRAW);
        this.checkGLError("fillPbo");
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    private void initializePBOs() {
        if (this.pboInitialized) this.release();

        GL15.glGenBuffers(this.pboIds);
        this.checkGLError("glGenBuffers");

        this.pboInitialized = true;
        this.index = 0;
        this.pboReady = false;
        this.allocatedPboSize = 0;
    }

    public void deleteTexture(final int texture) {
        this.delTexture.accept(texture);
    }

    public void release() {
        if (this.pboInitialized) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(this.pboIds);
            this.pboInitialized = false;
            this.pboReady = false;
            this.allocatedPboSize = 0;
        }
    }

    public void reset() {
        this.index = 0;
        this.pboReady = false;
        this.allocatedPboSize = 0;
    }

    private void checkGLError(final String operation) {
        final int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            final String errorName = switch (error) {
                case GL11.GL_INVALID_ENUM -> "GL_INVALID_ENUM";
                case GL11.GL_INVALID_VALUE -> "GL_INVALID_VALUE";
                case GL11.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
                case GL11.GL_STACK_OVERFLOW -> "GL_STACK_OVERFLOW";
                case GL11.GL_STACK_UNDERFLOW -> "GL_STACK_UNDERFLOW";
                case GL11.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
                default -> "UNKNOWN(0x" + Integer.toHexString(error) + ")";
            };
            LOGGER.warn(IT, "GL error after '{}': {}", operation, errorName);
        }
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