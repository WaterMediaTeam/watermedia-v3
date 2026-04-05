package org.watermedia.api.media.engines;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * OpenGL implementation of {@link GFXEngine} with PBO double-buffering and YUV shader conversion.
 * <p>
 * All uploads must happen on the render thread (OpenGL context is thread-bound).
 * If called from another thread, the work is dispatched via {@code renderThreadEx}.
 * <p>
 * Supports BGRA direct upload, NV12/NV21 (2-planar), and YUV420P/YUV422P/YUV444P (3-planar).
 * YUV formats are converted to RGBA on the GPU via FBO + shader pass.
 * The developer always sees a single RGBA texture from {@link #texture()}.
 */
public final class GLEngine extends GFXEngine {
    private static final Marker IT = MarkerManager.getMarker(GLEngine.class.getSimpleName());

    // PBO CONFIGURATION
    private static final int NUM_PBOS = 2;  // DOUBLE BUFFER PER PLANE
    private static final int MAX_PLANES = 3;

    // GLSL 150 CORE (OPENGL 3.2+, REQUIRED BY MINECRAFT 1.17+)
    private static final String VERTEX_SHADER = """
            #version 150 core
            in vec2 position;
            in vec2 uv;
            out vec2 texCoord;
            void main() {
                texCoord = uv;
                gl_Position = vec4(position, 0.0, 1.0);
            }
            """;

    // NV12/NV21 → RGB — BT.709 STUDIO RANGE (Y:16-235, UV:16-240)
    // Y PLANE:  GL_R8    — FULL RESOLUTION   — .r = Y
    // UV PLANE: GL_RG8   — HALF RES           — .r = first byte, .g = second byte
    // uvSwap: 0.0 = NV12 (BYTE ORDER U,V), 1.0 = NV21 (BYTE ORDER V,U)
    private static final String FRAGMENT_NV = """
            #version 150 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform sampler2D yTex;
            uniform sampler2D uvTex;
            uniform float uvSwap;
            void main() {
                float Y  = texture(yTex, texCoord).r * 1.16438 - 0.07306;
                vec2 raw = texture(uvTex, texCoord).rg;
                float Cb = mix(raw.x, raw.y, uvSwap) * 1.13839 - 0.57143;
                float Cr = mix(raw.y, raw.x, uvSwap) * 1.13839 - 0.57143;
                fragColor = vec4(
                    Y + 1.5748 * Cr,
                    Y - 0.1873 * Cb - 0.4681 * Cr,
                    Y + 1.8556 * Cb,
                    1.0);
            }
            """;

    // YUV 3-PLANAR → RGB — BT.709 STUDIO RANGE
    // Y PLANE: GL_R8 — FULL RESOLUTION
    // U PLANE: GL_R8 — CHROMA SUBSAMPLED
    // V PLANE: GL_R8 — CHROMA SUBSAMPLED
    private static final String FRAGMENT_YUV3 = """
            #version 150 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform sampler2D yTex;
            uniform sampler2D uTex;
            uniform sampler2D vTex;
            void main() {
                float Y  = texture(yTex, texCoord).r * 1.16438 - 0.07306;
                float Cb = texture(uTex, texCoord).r * 1.13839 - 0.57143;
                float Cr = texture(vTex, texCoord).r * 1.13839 - 0.57143;
                fragColor = vec4(
                    Y + 1.5748 * Cr,
                    Y - 0.1873 * Cb - 0.4681 * Cr,
                    Y + 1.8556 * Cb,
                    1.0);
            }
            """;

    // GL CALLBACKS (ALLOW MOD FRAMEWORKS TO INTERCEPT GL CALLS)
    private final IntSupplier genTexture;
    private final BindConsumer bindTexture;
    private final TexParamConsumer texParameter;
    private final BindConsumer pixelStore;
    private final IntConsumer delTexture;

    // THREAD CONTEXT (OPENGL-SPECIFIC)
    private final Thread renderThread;
    private final Executor renderThreadEx;

    // MANAGED TEXTURE (WHAT THE DEV BINDS)
    private int managedTexture = 0;
    private int managedTextureW = 0;
    private int managedTextureH = 0;

    // FBO FOR YUV TO RGBA CONVERSION
    private int fbo = 0;

    // FULLSCREEN QUAD (CORE PROFILE — NO IMMEDIATE MODE)
    private int quadVAO = 0;
    private int quadVBO = 0;

    // PER-PLANE PBO STATE
    // pbos[PLANE * NUM_PBOS + BUFFER_INDEX]
    private final int[] pbos = new int[MAX_PLANES * NUM_PBOS];
    private int pboWriteIdx = 0;
    private boolean pboInitialized = false;
    private boolean pboReady = false;
    private int activePlanes = 0;
    private final long[] pboAllocSizes = new long[MAX_PLANES];
    private boolean firstFrame = true;

    // NV SHADER (NV12/NV21)
    private int shaderNV = 0;
    private int uniformNVYTex = -1;
    private int uniformNVUVTex = -1;
    private int uniformNVSwap = -1;

    // YUV3 SHADER (3-PLANAR)
    private int shaderYUV3 = 0;
    private int uniformYUV3YTex = -1;
    private int uniformYUV3UTex = -1;
    private int uniformYUV3VTex = -1;

    // PLANE TEXTURES (YUV ONLY — BGRA WRITES DIRECTLY TO managedTexture)
    private int yTexture = 0;
    private int uvTexture = 0;  // NV12/NV21
    private int uTexture = 0;   // 3-PLANAR
    private int vTexture = 0;   // 3-PLANAR

    private GLEngine(final Thread renderThread, final Executor renderThreadEx,
                     final IntSupplier genTexture, final BindConsumer bindTexture,
                     final TexParamConsumer texParameter, final BindConsumer pixelStore,
                     final IntConsumer delTexture) {
        WaterMedia.checkIsClientSideOrThrow(GLEngine.class);
        if (genTexture == null || bindTexture == null || texParameter == null
                || pixelStore == null || delTexture == null) {
            throw new IllegalArgumentException("All GL callback parameters must be non-null");
        }
        this.renderThread = renderThread;
        this.renderThreadEx = renderThreadEx;
        this.genTexture = genTexture;
        this.bindTexture = bindTexture;
        this.texParameter = texParameter;
        this.pixelStore = pixelStore;
        this.delTexture = delTexture;
        // VALIDATE THREAD PAIR
        if (renderThreadEx != null) {
            renderThreadEx.execute(() -> {
                if (renderThread != null && Thread.currentThread() != renderThread) {
                    throw new IllegalStateException("GLEngine: renderThreadEx must dispatch to renderThread");
                }
            });
        }
    }

    // PUBLIC API
    @Override
    public long texture() {
        return this.managedTexture;
    }

    public boolean hasGLContext() {
        return GLFW.glfwGetCurrentContext() != 0L;
    }

    /**
     * Resets the entire pipeline and prepares for a new format.
     * Releases plane textures, PBOs, and recompiles shaders if the color space changed.
     * The managed texture is kept but reallocated on next upload if dimensions changed.
     */
    @Override
    public void setVideoFormat(final ColorSpace colorSpace, final int width, final int height) {
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.setVideoFormat(colorSpace, width, height));
            return;
        }

        // RELEASE PLANE-SPECIFIC RESOURCES
        this.releasePlaneTextures();
        this.releasePBOs();

        // RESET PBO STATE
        this.pboWriteIdx = 0;
        this.pboReady = false;
        this.firstFrame = true;

        // DETERMINE PLANE COUNT FOR NEW FORMAT
        this.activePlanes = switch (colorSpace) {
            case NV12, NV21 -> 2;
            case YUV420P, YUV422P, YUV444P -> 3;
            default -> 1; // BGRA, YUYV
        };

        // UPDATE BASE STATE
        super.setVideoFormat(colorSpace, width, height);

        // COMPILE SHADERS FOR NEW FORMAT (LAZY — ONLY IF NOT ALREADY COMPILED)
        switch (colorSpace) {
            case NV12, NV21 -> {
                if (this.shaderNV == 0) {
                    this.shaderNV = this.compileShader(VERTEX_SHADER, FRAGMENT_NV);
                    if (this.shaderNV != 0) {
                        this.uniformNVYTex = GL20.glGetUniformLocation(this.shaderNV, "yTex");
                        this.uniformNVUVTex = GL20.glGetUniformLocation(this.shaderNV, "uvTex");
                        this.uniformNVSwap = GL20.glGetUniformLocation(this.shaderNV, "uvSwap");
                        LOGGER.info(IT, "NV shader compiled (program={})", this.shaderNV);
                    }
                }
            }
            case YUV420P, YUV422P, YUV444P -> {
                if (this.shaderYUV3 == 0) {
                    this.shaderYUV3 = this.compileShader(VERTEX_SHADER, FRAGMENT_YUV3);
                    if (this.shaderYUV3 != 0) {
                        this.uniformYUV3YTex = GL20.glGetUniformLocation(this.shaderYUV3, "yTex");
                        this.uniformYUV3UTex = GL20.glGetUniformLocation(this.shaderYUV3, "uTex");
                        this.uniformYUV3VTex = GL20.glGetUniformLocation(this.shaderYUV3, "vTex");
                        LOGGER.info(IT, "YUV3 shader compiled (program={})", this.shaderYUV3);
                    }
                }
            }
            default -> {} // BGRA — NO SHADER NEEDED
        }

        LOGGER.info(IT, "Format set: {} {}x{} ({} planes)", colorSpace, width, height, this.activePlanes);
    }

    // UPLOAD — SINGLE PLANE (BGRA)
    @Override
    public void upload(final ByteBuffer buffer, final int stride) {
        if (buffer == null) return;
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.upload(buffer, stride));
            return;
        }
        // DIMENSION CHECK MUST BE HERE — ON THE RENDER THREAD.
        // setVideoFormat DISPATCHES TO THIS THREAD TOO; IF CALLED FROM ANOTHER THREAD
        // BEFORE US, width/height ARE 0 UNTIL THE RENDER THREAD PROCESSES setVideoFormat.
        if (this.width <= 0 || this.height <= 0) return;

        if (this.managedTexture == 0) this.managedTexture = this.newTexture();
        final int glFormat = (this.colorSpace == ColorSpace.BGRA) ? GL12.GL_BGRA : GL12.GL_RGBA;
        final int bytesPerTexel = 4; // BGRA/RGBA
        final int rowLengthPixels = (stride == 0) ? 0 : stride / bytesPerTexel;
        final int effectiveStride = (stride == 0) ? this.width * bytesPerTexel : stride;
        final long dataSize = (long) effectiveStride * this.height;

        if (!buffer.isDirect() && buffer.remaining() < dataSize) {
            LOGGER.warn(IT, "BGRA heap buffer too small: {} < {}", buffer.remaining(), dataSize);
            return;
        }
        final long memAddr = MemoryUtil.memAddress(buffer);
        if (memAddr == 0L) return;

        this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.managedTexture);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, rowLengthPixels);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        // INIT PBOs LAZILY
        if (!this.pboInitialized) {
            this.initPBOs(1);
        }

        if (this.firstFrame || !this.pboReady || this.pboAllocSizes[0] != dataSize) {
            // FIRST FRAME OR SIZE CHANGE: DIRECT SYNC UPLOAD + SEED PBO
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height,
                    0, glFormat, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, memAddr);
            this.checkGLError("BGRA glTexImage2D");

            // SEED CURRENT PBO
            this.seedPBO(0, dataSize, memAddr);
            this.pboAllocSizes[0] = dataSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            // PBO PATH: GPU READS FROM CURRENT, CPU FILLS NEXT
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height,
                    glFormat, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
            this.checkGLError("BGRA PBO texSubImage");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.seedPBO(writeIdx, dataSize, memAddr);
            this.pboWriteIdx = writeIdx;
        }

        // CLEANUP
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
    }

    // UPLOAD — TWO PLANES (NV12/NV21)
    @Override
    public void upload(final ByteBuffer yBuffer, final int yStride, final ByteBuffer uvBuffer, final int uvStride) {
        if (yBuffer == null || uvBuffer == null) return;
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.upload(yBuffer, yStride, uvBuffer, uvStride));
            return;
        }
        if (this.width <= 0 || this.height <= 0) return;
        if (this.shaderNV == 0) return;

        if (this.yTexture == 0) this.yTexture = this.newTexture();
        if (this.uvTexture == 0) this.uvTexture = this.newTexture();

        final int uvW = this.width / 2;
        final int uvH = this.height / 2;

        // Y: GL_LUMINANCE, 1 byte/texel → rowLength = stride (bytes = texels)
        // UV: GL_LUMINANCE_ALPHA, 2 bytes/texel → rowLength = stride / 2
        final int uvRowLen = (uvStride == 0) ? 0 : uvStride / 2;

        final int effectiveYStride = (yStride == 0) ? this.width : yStride;
        final int effectiveUVStride = (uvStride == 0) ? this.width : uvStride; // NV12 UV row = width bytes

        final long ySize = (long) effectiveYStride * this.height;
        final long uvSize = (long) effectiveUVStride * uvH;

        final long yAddr = MemoryUtil.memAddress(yBuffer);
        final long uvAddr = MemoryUtil.memAddress(uvBuffer);
        if (yAddr == 0L || uvAddr == 0L) return;

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        // INIT PBOs LAZILY
        if (!this.pboInitialized) {
            this.initPBOs(2);
        }

        if (this.firstFrame || !this.pboReady
                || this.pboAllocSizes[0] != ySize || this.pboAllocSizes[1] != uvSize) {
            // DIRECT SYNC UPLOAD + SEED PBOs
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yStride);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, this.width, this.height, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, yAddr);
            this.checkGLError("NV Y texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uvTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uvRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, uvW, uvH, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, uvAddr);
            this.checkGLError("NV UV texImage");

            // SEED PBOs FOR NEXT FRAME
            this.seedPBO(0, ySize, yAddr);
            this.seedPBO(NUM_PBOS, uvSize, uvAddr);
            this.pboAllocSizes[0] = ySize;
            this.pboAllocSizes[1] = uvSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            // PBO PATH
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            // Y PLANE: GPU READ FROM PBO
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yStride);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
            this.checkGLError("NV Y PBO texSub");

            // UV PLANE: GPU READ FROM PBO
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uvTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uvRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, uvW, uvH,
                    GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, 0L);
            this.checkGLError("NV UV PBO texSub");

            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            // CPU FILL NEXT PBOs
            this.seedPBO(writeIdx, ySize, yAddr);
            this.seedPBO(NUM_PBOS + writeIdx, uvSize, uvAddr);
            this.pboWriteIdx = writeIdx;
        }

        // CLEANUP + FBO CONVERT
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        this.convertYUVToRGBA();
    }

    // UPLOAD — THREE PLANES (YUV420P/422P/444P)
    @Override
    public void upload(final ByteBuffer yBuffer, final int yStride,
                       final ByteBuffer uBuffer, final int uStride,
                       final ByteBuffer vBuffer, final int vStride) {
        if (yBuffer == null || uBuffer == null || vBuffer == null) return;
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.upload(yBuffer, yStride, uBuffer, uStride, vBuffer, vStride));
            return;
        }
        if (this.width <= 0 || this.height <= 0) return;
        if (this.shaderYUV3 == 0) return;

        if (this.yTexture == 0) this.yTexture = this.newTexture();
        if (this.uTexture == 0) this.uTexture = this.newTexture();
        if (this.vTexture == 0) this.vTexture = this.newTexture();

        // COMPUTE CHROMA DIMENSIONS
        final int chromaW, chromaH;
        switch (this.colorSpace) {
            case YUV422P -> { chromaW = this.width / 2; chromaH = this.height; }
            case YUV444P -> { chromaW = this.width; chromaH = this.height; }
            default /* YUV420P */ -> { chromaW = this.width / 2; chromaH = this.height / 2; }
        }

        // LUMINANCE: 1 byte/texel → rowLength = stride in bytes
        final int effY = (yStride == 0) ? this.width : yStride;
        final int effU = (uStride == 0) ? chromaW : uStride;
        final int effV = (vStride == 0) ? chromaW : vStride;

        final long ySize = (long) effY * this.height;
        final long uSize = (long) effU * chromaH;
        final long vSize = (long) effV * chromaH;

        final long yAddr = MemoryUtil.memAddress(yBuffer);
        final long uAddr = MemoryUtil.memAddress(uBuffer);
        final long vAddr = MemoryUtil.memAddress(vBuffer);
        if (yAddr == 0L || uAddr == 0L || vAddr == 0L) return;

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (!this.pboInitialized) {
            this.initPBOs(3);
        }

        if (this.firstFrame || !this.pboReady
                || this.pboAllocSizes[0] != ySize
                || this.pboAllocSizes[1] != uSize
                || this.pboAllocSizes[2] != vSize) {
            // DIRECT SYNC UPLOAD + SEED PBOs
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yStride);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, this.width, this.height, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, yAddr);
            this.checkGLError("YUV3 Y texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uStride);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, chromaW, chromaH, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, uAddr);
            this.checkGLError("YUV3 U texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, vStride);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, chromaW, chromaH, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, vAddr);
            this.checkGLError("YUV3 V texImage");

            this.seedPBO(0, ySize, yAddr);
            this.seedPBO(NUM_PBOS, uSize, uAddr);
            this.seedPBO(2 * NUM_PBOS, vSize, vAddr);
            this.pboAllocSizes[0] = ySize;
            this.pboAllocSizes[1] = uSize;
            this.pboAllocSizes[2] = vSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            // PBO PATH
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            // Y
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yStride);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);

            // U
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uStride);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);

            // V
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, vStride);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[2 * NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);

            this.checkGLError("YUV3 PBO texSub");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            // CPU FILL NEXT PBOs
            this.seedPBO(writeIdx, ySize, yAddr);
            this.seedPBO(NUM_PBOS + writeIdx, uSize, uAddr);
            this.seedPBO(2 * NUM_PBOS + writeIdx, vSize, vAddr);
            this.pboWriteIdx = writeIdx;
        }

        // CLEANUP + FBO CONVERT
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        this.convertYUVToRGBA();
    }

    // RELEASE
    @Override
    public void release() {
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(this::release);
            return;
        }
        if (this.fbo != 0) { GL30.glDeleteFramebuffers(this.fbo); this.fbo = 0; }
        if (this.quadVAO != 0) { GL30.glDeleteVertexArrays(this.quadVAO); this.quadVAO = 0; }
        if (this.quadVBO != 0) { GL15.glDeleteBuffers(this.quadVBO); this.quadVBO = 0; }
        if (this.managedTexture != 0) { this.delTexture.accept(this.managedTexture); this.managedTexture = 0; }
        this.managedTextureW = 0;
        this.managedTextureH = 0;
        this.releasePlaneTextures();
        this.releasePBOs();
        this.releaseShaders();
        this.firstFrame = true;
    }

    // YUV TO RGBA FBO CONVERSION
    // RENDERS YUV PLANE TEXTURES THROUGH THE APPROPRIATE SHADER INTO MANAGEDTEXTURE VIA FBO. AFTER THIS CALL, MANAGEDTEXTURE CONTAINS THE RGBA RESULT READY FOR NORMAL BINDING.
    private void convertYUVToRGBA() {
        if (this.managedTexture == 0) this.managedTexture = this.newTexture();
        if (this.fbo == 0) this.fbo = GL30.glGenFramebuffers();
        this.initQuad();

        // REALLOCATE MANAGED TEXTURE ON SIZE CHANGE
        if (this.managedTextureW != this.width || this.managedTextureH != this.height) {
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.managedTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.checkGLError("managed texture alloc");
            this.managedTextureW = this.width;
            this.managedTextureH = this.height;
        }

        // SAVE GL STATE
        final int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int[] savedViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        final int savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        final int savedVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        // BIND FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.managedTexture, 0);
        GL11.glViewport(0, 0, this.width, this.height);

        // ACTIVATE SHADER + BIND PLANE TEXTURES
        switch (this.colorSpace) {
            case NV12, NV21 -> {
                GL20.glUseProgram(this.shaderNV);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
                GL20.glUniform1i(this.uniformNVYTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uvTexture);
                GL20.glUniform1i(this.uniformNVUVTex, 1);
                GL20.glUniform1f(this.uniformNVSwap, this.colorSpace == ColorSpace.NV21 ? 1.0f : 0.0f);
            }
            case YUV420P, YUV422P, YUV444P -> {
                GL20.glUseProgram(this.shaderYUV3);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
                GL20.glUniform1i(this.uniformYUV3YTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
                GL20.glUniform1i(this.uniformYUV3UTex, 1);
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
                GL20.glUniform1i(this.uniformYUV3VTex, 2);
            }
            default -> {}
        }

        // DRAW FULLSCREEN QUAD VIA VAO
        GL30.glBindVertexArray(this.quadVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
        this.checkGLError("FBO convert quad");

        // RESTORE GL STATE
        GL20.glUseProgram(savedProgram);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GL30.glBindVertexArray(savedVAO);
    }

    // INTERNAL HELPERS
    private int newTexture() {
        final int tex = this.genTexture.getAsInt();
        this.bindTexture.accept(GL11.GL_TEXTURE_2D, tex);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        this.bindTexture.accept(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private void initQuad() {
        if (this.quadVAO != 0) return;
        // POSITION (xy) + UV (st) — CLIP-SPACE FULLSCREEN QUAD (TWO TRIANGLES)
        final float[] verts = {
                // pos      uv
                -1, -1,   0, 0,
                1, -1,   1, 0,
                1,  1,   1, 1,
                -1, -1,   0, 0,
                1,  1,   1, 1,
                -1,  1,   0, 1,
        };

        this.quadVAO = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.quadVAO);

        this.quadVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.quadVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);

        // ATTRIBUTE 0 = position (vec2)
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        // ATTRIBUTE 1 = uv (vec2)
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        GL30.glBindVertexArray(0);
        this.checkGLError("initQuad");
    }

    private void initPBOs(final int planeCount) {
        // GENERATE PBOs FOR active planes × NUM_PBOS
        final int count = planeCount * NUM_PBOS;
        final int[] ids = new int[count];
        GL15.glGenBuffers(ids);
        System.arraycopy(ids, 0, this.pbos, 0, count);
        this.checkGLError("initPBOs");
        this.pboInitialized = true;
        this.pboWriteIdx = 0;
        this.pboReady = false;
    }

    private void seedPBO(final int pboArrayIndex, final long sizeBytes, final long dataAddress) {
        final int pboId = this.pbos[pboArrayIndex];
        if (pboId == 0) return;
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboId);
        GL15.nglBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, sizeBytes, dataAddress, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    private int compileShader(final String vertSrc, final String fragSrc) {
        final int vert = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vert, vertSrc);
        GL20.glCompileShader(vert);
        if (GL20.glGetShaderi(vert, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "VERTEX COMPILE FAILED: {}", GL20.glGetShaderInfoLog(vert, 1024));
            GL20.glDeleteShader(vert);
            return 0;
        }

        final int frag = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(frag, fragSrc);
        GL20.glCompileShader(frag);
        if (GL20.glGetShaderi(frag, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "FRAGMENT COMPILE FAILED: {}", GL20.glGetShaderInfoLog(frag, 1024));
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);
            return 0;
        }

        final int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vert);
        GL20.glAttachShader(prog, frag);
        // BIND ATTRIBUTE LOCATIONS BEFORE LINKING (MUST MATCH initQuad LAYOUT)
        GL20.glBindAttribLocation(prog, 0, "position");
        GL20.glBindAttribLocation(prog, 1, "uv");
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "LINK FAILED: {}", GL20.glGetProgramInfoLog(prog, 1024));
            GL20.glDeleteProgram(prog);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);
            return 0;
        }

        GL20.glDetachShader(prog, vert);
        GL20.glDetachShader(prog, frag);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);
        return prog;
    }

    private void releasePlaneTextures() {
        if (this.yTexture != 0) { this.delTexture.accept(this.yTexture); this.yTexture = 0; }
        if (this.uvTexture != 0) { this.delTexture.accept(this.uvTexture); this.uvTexture = 0; }
        if (this.uTexture != 0) { this.delTexture.accept(this.uTexture); this.uTexture = 0; }
        if (this.vTexture != 0) { this.delTexture.accept(this.vTexture); this.vTexture = 0; }
    }

    private void releasePBOs() {
        if (!this.pboInitialized) return;
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        // DELETE ALL PBOs THAT WERE GENERATED
        final int count = this.activePlanes * NUM_PBOS;
        if (count > 0) {
            final int[] toDelete = new int[count];
            System.arraycopy(this.pbos, 0, toDelete, 0, count);
            GL15.glDeleteBuffers(toDelete);
        }
        Arrays.fill(this.pbos, 0);
        Arrays.fill(this.pboAllocSizes, 0);
        this.pboInitialized = false;
        this.pboReady = false;
    }

    private void releaseShaders() {
        if (this.shaderNV != 0) { GL20.glDeleteProgram(this.shaderNV); this.shaderNV = 0; }
        if (this.shaderYUV3 != 0) { GL20.glDeleteProgram(this.shaderYUV3); this.shaderYUV3 = 0; }
    }

    private void checkGLError(final String op) {
        final int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            final String name = switch (err) {
                case GL11.GL_INVALID_ENUM -> "INVALID_ENUM";
                case GL11.GL_INVALID_VALUE -> "INVALID_VALUE";
                case GL11.GL_INVALID_OPERATION -> "INVALID_OPERATION";
                case GL11.GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
                case GL11.GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
                case GL11.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
                default -> "0x" + Integer.toHexString(err);
            };
            LOGGER.warn(IT, "GL error after '{}': {}", op, name);
        }
    }

    // BUILDER + INTERFACES
    public static class Builder {
        private final Thread renderThread;
        private final Executor renderThreadEx;
        private IntSupplier genTexture = GL11::glGenTextures;
        private BindConsumer bindTexture = GL11::glBindTexture;
        private TexParamConsumer texParameter = GL11::glTexParameteri;
        private BindConsumer pixelStore = GL11::glPixelStorei;
        private IntConsumer delTexture = GL11::glDeleteTextures;

        public Builder(final Thread renderThread, final Executor renderThreadEx) {
            this.renderThread = renderThread;
            this.renderThreadEx = renderThreadEx;
        }

        public Builder setGenTexture(final IntSupplier f) { this.genTexture = f; return this; }
        public Builder setBindTexture(final BindConsumer f) { this.bindTexture = f; return this; }
        public Builder setTexParameter(final TexParamConsumer f) { this.texParameter = f; return this; }
        public Builder setPixelStore(final BindConsumer f) { this.pixelStore = f; return this; }
        public Builder setDelTexture(final IntConsumer f) { this.delTexture = f; return this; }

        public GLEngine build() {
            return new GLEngine(this.renderThread, this.renderThreadEx,
                    this.genTexture, this.bindTexture, this.texParameter,
                    this.pixelStore, this.delTexture);
        }
    }

    @FunctionalInterface
    public interface BindConsumer {
        void accept(int a, int b);
    }

    @FunctionalInterface
    public interface TexParamConsumer {
        void accept(int a, int b, int c);
    }
}