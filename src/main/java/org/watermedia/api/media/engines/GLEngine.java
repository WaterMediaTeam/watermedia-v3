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
 * Supports direct upload (BGRA, RGBA, RGB), packed YUV (YUYV/UYVY), semi-planar (NV12/NV21/P010),
 * planar YUV (420P/422P/444P), planar YUVA (with alpha), and grayscale (GRAY).
 * All YUV/GRAY formats are converted to RGBA on the GPU via FBO + shader pass.
 * High bit depths (10/12/16/32-bit) are supported via GL_R16/GL_RG16/GL_R32F textures.
 * The developer always sees a single RGBA texture from {@link #texture()}.
 */
public final class GLEngine extends GFXEngine {
    private static final Marker IT = MarkerManager.getMarker(GLEngine.class.getSimpleName());

    // PBO CONFIGURATION
    private static final int NUM_PBOS = 2;  // DOUBLE BUFFER PER PLANE
    private static final int MAX_PLANES = 4;

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

    // NV12/NV21/P010/P016 → RGB — BT.709 STUDIO RANGE
    // Y PLANE:  GL_R8/R16    — FULL RESOLUTION   — .r = Y
    // UV PLANE: GL_RG8/RG16  — HALF RES           — .r = first byte, .g = second byte
    // uvSwap: 0.0 = NV12 (BYTE ORDER U,V), 1.0 = NV21 (BYTE ORDER V,U)
    private static final String FRAGMENT_NV = """
            #version 150 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform sampler2D yTex;
            uniform sampler2D uvTex;
            uniform float uvSwap;
            uniform float bitScale;
            void main() {
                float Y  = texture(yTex, texCoord).r * bitScale * 1.16438 - 0.07306;
                vec2 raw = texture(uvTex, texCoord).rg * bitScale;
                float Cb = mix(raw.x, raw.y, uvSwap) * 1.13839 - 0.57143;
                float Cr = mix(raw.y, raw.x, uvSwap) * 1.13839 - 0.57143;
                fragColor = vec4(
                    Y + 1.5748 * Cr,
                    Y - 0.1873 * Cb - 0.4681 * Cr,
                    Y + 1.8556 * Cb,
                    1.0);
            }
            """;

    // GRAY → RGB — SINGLE LUMA PLANE TO GRAYSCALE RGBA
    private static final String FRAGMENT_GRAY = """
            #version 150 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform sampler2D yTex;
            uniform float bitScale;
            void main() {
                float luma = texture(yTex, texCoord).r * bitScale;
                fragColor = vec4(luma, luma, luma, 1.0);
            }
            """;

    // YUV 3-PLANAR → RGB — BT.709 STUDIO RANGE
    private static final String FRAGMENT_YUV3 = """
            #version 150 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform sampler2D yTex;
            uniform sampler2D uTex;
            uniform sampler2D vTex;
            uniform float bitScale;
            void main() {
                float Y  = texture(yTex, texCoord).r * bitScale * 1.16438 - 0.07306;
                float Cb = texture(uTex, texCoord).r * bitScale * 1.13839 - 0.57143;
                float Cr = texture(vTex, texCoord).r * bitScale * 1.13839 - 0.57143;
                fragColor = vec4(
                    Y + 1.5748 * Cr,
                    Y - 0.1873 * Cb - 0.4681 * Cr,
                    Y + 1.8556 * Cb,
                    1.0);
            }
            """;

    // YUVA 4-PLANAR → RGBA — BT.709 STUDIO RANGE + ALPHA
    private static final String FRAGMENT_YUVA = """
            #version 150 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform sampler2D yTex;
            uniform sampler2D uTex;
            uniform sampler2D vTex;
            uniform sampler2D aTex;
            uniform float bitScale;
            void main() {
                float Y  = texture(yTex, texCoord).r * bitScale * 1.16438 - 0.07306;
                float Cb = texture(uTex, texCoord).r * bitScale * 1.13839 - 0.57143;
                float Cr = texture(vTex, texCoord).r * bitScale * 1.13839 - 0.57143;
                float A  = texture(aTex, texCoord).r * bitScale;
                fragColor = vec4(
                    Y + 1.5748 * Cr,
                    Y - 0.1873 * Cb - 0.4681 * Cr,
                    Y + 1.8556 * Cb,
                    A);
            }
            """;

    // YUYV/UYVY PACKED YUV422 → RGB — BT.709 STUDIO RANGE
    // PACKED TEXTURE IS RGBA8 AT (WIDTH/2, HEIGHT): EACH TEXEL = ONE PIXEL PAIR
    private static final String FRAGMENT_YUYV = """
            #version 150 core
            in vec2 texCoord;
            out vec4 fragColor;
            uniform sampler2D packedTex;
            uniform float outputWidth;
            uniform float uvSwap;
            void main() {
                vec4 packed = texture(packedTex, texCoord);
                float pixelX = texCoord.x * outputWidth;
                float isOdd = mod(floor(pixelX), 2.0);
                float Y0 = mix(packed.r, packed.g, uvSwap);
                float Cb = mix(packed.g, packed.r, uvSwap);
                float Y1 = mix(packed.b, packed.a, uvSwap);
                float Cr = mix(packed.a, packed.b, uvSwap);
                float Y = mix(Y0, Y1, isOdd) * 1.16438 - 0.07306;
                Cb = Cb * 1.13839 - 0.57143;
                Cr = Cr * 1.13839 - 0.57143;
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

    // BIT DEPTH DERIVED STATE (SET IN setVideoFormat)
    private int bytesPerSample = 1;
    private float bitScale = 1.0f;
    private int glInternalR = GL30.GL_R8;    // R8 / R16 / R32F
    private int glInternalRG = GL30.GL_RG8;  // RG8 / RG16
    private int glType = GL11.GL_UNSIGNED_BYTE; // UNSIGNED_BYTE / UNSIGNED_SHORT / FLOAT

    // GRAY SHADER
    private int shaderGray = 0;
    private int uniformGrayTex = -1;
    private int uniformGrayBitScale = -1;

    // NV SHADER (NV12/NV21/P010/P016)
    private int shaderNV = 0;
    private int uniformNVYTex = -1;
    private int uniformNVUVTex = -1;
    private int uniformNVSwap = -1;
    private int uniformNVBitScale = -1;

    // YUV3 SHADER (3-PLANAR)
    private int shaderYUV3 = 0;
    private int uniformYUV3YTex = -1;
    private int uniformYUV3UTex = -1;
    private int uniformYUV3VTex = -1;
    private int uniformYUV3BitScale = -1;

    // YUVA SHADER (4-PLANAR WITH ALPHA)
    private int shaderYUVA = 0;
    private int uniformYUVAYTex = -1;
    private int uniformYUVAUTex = -1;
    private int uniformYUVAVTex = -1;
    private int uniformYUVAATex = -1;
    private int uniformYUVABitScale = -1;

    // YUYV SHADER (PACKED YUV422)
    private int shaderYUYV = 0;
    private int uniformYUYVTex = -1;
    private int uniformYUYVWidth = -1;
    private int uniformYUYVSwap = -1;

    // PLANE TEXTURES (YUV ONLY — BGRA/RGBA/RGB WRITE DIRECTLY TO managedTexture)
    private int yTexture = 0;
    private int uvTexture = 0;  // NV12/NV21
    private int uTexture = 0;   // 3/4-PLANAR
    private int vTexture = 0;   // 3/4-PLANAR
    private int aTexture = 0;   // 4-PLANAR (YUVA)

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
    public void setVideoFormat(final ColorSpace colorSpace, final int width, final int height, final int bitsPerComponent) {
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.setVideoFormat(colorSpace, width, height, bitsPerComponent));
            return;
        }

        // RELEASE PLANE-SPECIFIC RESOURCES
        this.releasePlaneTextures();
        this.releasePBOs();

        // RESET PBO STATE
        this.pboWriteIdx = 0;
        this.pboReady = false;
        this.firstFrame = true;

        // DERIVE BIT DEPTH STATE
        switch (bitsPerComponent) {
            case 10 -> {
                this.bytesPerSample = 2;
                this.bitScale = 65535.0f / 1023.0f;
                this.glInternalR = GL30.GL_R16;
                this.glInternalRG = GL30.GL_RG16;
                this.glType = GL11.GL_UNSIGNED_SHORT;
            }
            case 12 -> {
                this.bytesPerSample = 2;
                this.bitScale = 65535.0f / 4095.0f;
                this.glInternalR = GL30.GL_R16;
                this.glInternalRG = GL30.GL_RG16;
                this.glType = GL11.GL_UNSIGNED_SHORT;
            }
            case 16 -> {
                this.bytesPerSample = 2;
                this.bitScale = 1.0f;
                this.glInternalR = GL30.GL_R16;
                this.glInternalRG = GL30.GL_RG16;
                this.glType = GL11.GL_UNSIGNED_SHORT;
            }
            case 32 -> {
                this.bytesPerSample = 4;
                this.bitScale = 1.0f;
                this.glInternalR = GL30.GL_R32F;
                this.glInternalRG = GL30.GL_RG16F; // CLOSEST FOR 32-BIT FLOAT PAIRS
                this.glType = GL11.GL_FLOAT;
            }
            default -> { // 8-BIT
                this.bytesPerSample = 1;
                this.bitScale = 1.0f;
                this.glInternalR = GL30.GL_R8;
                this.glInternalRG = GL30.GL_RG8;
                this.glType = GL11.GL_UNSIGNED_BYTE;
            }
        }

        // DETERMINE PLANE COUNT FOR NEW FORMAT
        this.activePlanes = switch (colorSpace) {
            case NV12, NV21 -> 2;
            case YUV420P, YUV422P, YUV444P -> 3;
            case YUVA420P, YUVA422P, YUVA444P -> 4;
            default -> 1;
        };

        // UPDATE BASE STATE
        super.setVideoFormat(colorSpace, width, height, bitsPerComponent);

        // COMPILE SHADERS FOR NEW FORMAT (LAZY — ONLY IF NOT ALREADY COMPILED)
        switch (colorSpace) {
            case GRAY -> {
                if (this.shaderGray == 0) {
                    this.shaderGray = this.compileShader(VERTEX_SHADER, FRAGMENT_GRAY);
                    if (this.shaderGray != 0) {
                        this.uniformGrayTex = GL20.glGetUniformLocation(this.shaderGray, "yTex");
                        this.uniformGrayBitScale = GL20.glGetUniformLocation(this.shaderGray, "bitScale");
                        LOGGER.info(IT, "Gray shader compiled (program={})", this.shaderGray);
                    }
                }
            }
            case NV12, NV21 -> {
                if (this.shaderNV == 0) {
                    this.shaderNV = this.compileShader(VERTEX_SHADER, FRAGMENT_NV);
                    if (this.shaderNV != 0) {
                        this.uniformNVYTex = GL20.glGetUniformLocation(this.shaderNV, "yTex");
                        this.uniformNVUVTex = GL20.glGetUniformLocation(this.shaderNV, "uvTex");
                        this.uniformNVSwap = GL20.glGetUniformLocation(this.shaderNV, "uvSwap");
                        this.uniformNVBitScale = GL20.glGetUniformLocation(this.shaderNV, "bitScale");
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
                        this.uniformYUV3BitScale = GL20.glGetUniformLocation(this.shaderYUV3, "bitScale");
                        LOGGER.info(IT, "YUV3 shader compiled (program={})", this.shaderYUV3);
                    }
                }
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                if (this.shaderYUVA == 0) {
                    this.shaderYUVA = this.compileShader(VERTEX_SHADER, FRAGMENT_YUVA);
                    if (this.shaderYUVA != 0) {
                        this.uniformYUVAYTex = GL20.glGetUniformLocation(this.shaderYUVA, "yTex");
                        this.uniformYUVAUTex = GL20.glGetUniformLocation(this.shaderYUVA, "uTex");
                        this.uniformYUVAVTex = GL20.glGetUniformLocation(this.shaderYUVA, "vTex");
                        this.uniformYUVAATex = GL20.glGetUniformLocation(this.shaderYUVA, "aTex");
                        this.uniformYUVABitScale = GL20.glGetUniformLocation(this.shaderYUVA, "bitScale");
                        LOGGER.info(IT, "YUVA shader compiled (program={})", this.shaderYUVA);
                    }
                }
            }
            case YUYV, YUYV2 -> {
                if (this.shaderYUYV == 0) {
                    this.shaderYUYV = this.compileShader(VERTEX_SHADER, FRAGMENT_YUYV);
                    if (this.shaderYUYV != 0) {
                        this.uniformYUYVTex = GL20.glGetUniformLocation(this.shaderYUYV, "packedTex");
                        this.uniformYUYVWidth = GL20.glGetUniformLocation(this.shaderYUYV, "outputWidth");
                        this.uniformYUYVSwap = GL20.glGetUniformLocation(this.shaderYUYV, "uvSwap");
                        LOGGER.info(IT, "YUYV shader compiled (program={})", this.shaderYUYV);
                    }
                }
            }
            default -> {} // BGRA, RGBA, RGB — NO SHADER NEEDED
        }

        LOGGER.info(IT, "Format set: {} {}x{} ({} planes, {}bpc)", colorSpace, width, height, this.activePlanes, bitsPerComponent);
    }

    // UPLOAD — SINGLE PLANE (BGRA, RGBA, RGB, GRAY, YUYV)
    @Override
    public void upload(final ByteBuffer buffer, final int stride) {
        if (buffer == null) return;
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.upload(buffer, stride));
            return;
        }
        if (this.width <= 0 || this.height <= 0) return;

        // GRAY: R-FORMAT PLANE TEXTURE + FBO CONVERT
        if (this.colorSpace == ColorSpace.GRAY) {
            this.uploadGray(buffer, stride);
            return;
        }

        // YUYV/UYVY: PACKED RGBA HALF-WIDTH TEXTURE + FBO CONVERT
        if (this.colorSpace == ColorSpace.YUYV || this.colorSpace == ColorSpace.YUYV2) {
            this.uploadPacked(buffer, stride);
            return;
        }

        // DIRECT TO MANAGED TEXTURE (BGRA, RGBA, RGB)
        if (this.managedTexture == 0) this.managedTexture = this.newTexture();

        final int glFormat;
        final int glType;
        final int bytesPerTexel;
        switch (this.colorSpace) {
            case BGRA -> { glFormat = GL12.GL_BGRA; glType = GL12.GL_UNSIGNED_INT_8_8_8_8_REV; bytesPerTexel = 4; }
            case RGB  -> {
                if (this.bitsPerComponent > 8) {
                    glFormat = GL11.GL_RGB; glType = GL11.GL_UNSIGNED_SHORT; bytesPerTexel = 6;
                } else {
                    glFormat = GL11.GL_RGB; glType = GL11.GL_UNSIGNED_BYTE; bytesPerTexel = 3;
                }
            }
            default -> {
                if (this.bitsPerComponent == 16) {
                    glFormat = GL11.GL_RGBA; glType = GL11.GL_UNSIGNED_SHORT; bytesPerTexel = 8;
                } else {
                    glFormat = GL11.GL_RGBA; glType = GL12.GL_UNSIGNED_INT_8_8_8_8_REV; bytesPerTexel = 4;
                }
            }
        }

        final int rowLengthPixels = (stride == 0) ? 0 : stride / bytesPerTexel;
        final int effectiveStride = (stride == 0) ? this.width * bytesPerTexel : stride;
        final long dataSize = (long) effectiveStride * this.height;

        if (!buffer.isDirect() && buffer.remaining() < dataSize) {
            LOGGER.warn(IT, "Single-plane heap buffer too small: {} < {}", buffer.remaining(), dataSize);
            return;
        }
        final long addr = MemoryUtil.memAddress(buffer);
        if (addr == 0L) return;

        this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.managedTexture);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, rowLengthPixels);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (!this.pboInitialized) {
            this.initPBOs(1);
        }

        if (this.firstFrame || !this.pboReady || this.pboAllocSizes[0] != dataSize) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height, 0, glFormat, glType, addr);
            this.checkGLError("single-plane glTexImage2D");

            this.seedPBO(0, dataSize, addr);
            this.pboAllocSizes[0] = dataSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, glFormat, glType, 0L);
            this.checkGLError("single-plane PBO texSubImage");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.seedPBO(writeIdx, dataSize, addr);
            this.pboWriteIdx = writeIdx;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
    }

    // UPLOAD — GRAY (R-FORMAT PLANE TEXTURE + FBO SHADER CONVERT)
    private void uploadGray(final ByteBuffer buffer, final int stride) {
        if (this.shaderGray == 0) return;
        if (this.yTexture == 0) this.yTexture = this.newTexture();

        final int rowLen = (stride == 0) ? this.width : stride / this.bytesPerSample;
        final int effectiveStride = (stride == 0) ? this.width * this.bytesPerSample : stride;
        final long dataSize = (long) effectiveStride * this.height;

        final long addr = MemoryUtil.memAddress(buffer);
        if (addr == 0L) return;

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (!this.pboInitialized) {
            this.initPBOs(1);
        }

        if (this.firstFrame || !this.pboReady || this.pboAllocSizes[0] != dataSize) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, rowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, this.width, this.height, 0, GL11.GL_RED, this.glType, addr);
            this.checkGLError("GRAY texImage");

            this.seedPBO(0, dataSize, addr);
            this.pboAllocSizes[0] = dataSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, rowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, GL11.GL_RED, this.glType, 0L);
            this.checkGLError("GRAY PBO texSub");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.seedPBO(writeIdx, dataSize, addr);
            this.pboWriteIdx = writeIdx;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        this.convertToRGBA();
    }

    // UPLOAD — YUYV/UYVY PACKED (RGBA8 HALF-WIDTH TEXTURE + FBO CONVERT)
    private void uploadPacked(final ByteBuffer buffer, final int stride) {
        if (this.shaderYUYV == 0) return;
        if (this.yTexture == 0) {
            this.yTexture = this.newTexture();
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }

        final int halfW = this.width / 2;
        final int bytesPerTexel = 4; // RGBA8 = 4 bytes per pixel pair
        final int rowLenPixels = (stride == 0) ? 0 : stride / bytesPerTexel;
        final int effectiveStride = (stride == 0) ? halfW * bytesPerTexel : stride;
        final long dataSize = (long) effectiveStride * this.height;

        final long addr = MemoryUtil.memAddress(buffer);
        if (addr == 0L) return;

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, rowLenPixels);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (!this.pboInitialized) {
            this.initPBOs(1);
        }

        if (this.firstFrame || !this.pboReady || this.pboAllocSizes[0] != dataSize) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, halfW, this.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, addr);
            this.checkGLError("YUYV texImage");

            this.seedPBO(0, dataSize, addr);
            this.pboAllocSizes[0] = dataSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, halfW, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            this.checkGLError("YUYV PBO texSub");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.seedPBO(writeIdx, dataSize, addr);
            this.pboWriteIdx = writeIdx;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        this.convertToRGBA();
    }

    // UPLOAD — TWO PLANES (NV12/NV21/P010/P016)
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

        // ROW LENGTH IN TEXELS: Y = 1 component, UV = 2 components
        final int yRowLen = (yStride == 0) ? 0 : yStride / this.bytesPerSample;
        final int uvRowLen = (uvStride == 0) ? 0 : uvStride / (2 * this.bytesPerSample);

        final int effectiveYStride = (yStride == 0) ? this.width * this.bytesPerSample : yStride;
        final int effectiveUVStride = (uvStride == 0) ? this.width * this.bytesPerSample : uvStride;

        final long ySize = (long) effectiveYStride * this.height;
        final long uvSize = (long) effectiveUVStride * uvH;

        final long yAddr = MemoryUtil.memAddress(yBuffer);
        final long uvAddr = MemoryUtil.memAddress(uvBuffer);
        if (yAddr == 0L || uvAddr == 0L) return;

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (!this.pboInitialized) {
            this.initPBOs(2);
        }

        if (this.firstFrame || !this.pboReady
                || this.pboAllocSizes[0] != ySize || this.pboAllocSizes[1] != uvSize) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, this.width, this.height, 0, GL11.GL_RED, this.glType, yAddr);
            this.checkGLError("NV Y texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uvTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uvRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalRG, uvW, uvH, 0, GL30.GL_RG, this.glType, uvAddr);
            this.checkGLError("NV UV texImage");

            this.seedPBO(0, ySize, yAddr);
            this.seedPBO(NUM_PBOS, uvSize, uvAddr);
            this.pboAllocSizes[0] = ySize;
            this.pboAllocSizes[1] = uvSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, GL11.GL_RED, this.glType, 0L);
            this.checkGLError("NV Y PBO texSub");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uvTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uvRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL30.GL_RG, this.glType, 0L);
            this.checkGLError("NV UV PBO texSub");

            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.seedPBO(writeIdx, ySize, yAddr);
            this.seedPBO(NUM_PBOS + writeIdx, uvSize, uvAddr);
            this.pboWriteIdx = writeIdx;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        this.convertToRGBA();
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

        final int chromaW, chromaH;
        switch (this.colorSpace) {
            case YUV422P -> { chromaW = this.width / 2; chromaH = this.height; }
            case YUV444P -> { chromaW = this.width; chromaH = this.height; }
            default /* YUV420P */ -> { chromaW = this.width / 2; chromaH = this.height / 2; }
        }

        final int yRowLen = (yStride == 0) ? this.width : yStride / this.bytesPerSample;
        final int uRowLen = (uStride == 0) ? chromaW : uStride / this.bytesPerSample;
        final int vRowLen = (vStride == 0) ? chromaW : vStride / this.bytesPerSample;

        final int effY = (yStride == 0) ? this.width * this.bytesPerSample : yStride;
        final int effU = (uStride == 0) ? chromaW * this.bytesPerSample : uStride;
        final int effV = (vStride == 0) ? chromaW * this.bytesPerSample : vStride;

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
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, this.width, this.height, 0, GL11.GL_RED, this.glType, yAddr);
            this.checkGLError("YUV3 Y texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, chromaW, chromaH, 0, GL11.GL_RED, this.glType, uAddr);
            this.checkGLError("YUV3 U texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, vRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, chromaW, chromaH, 0, GL11.GL_RED, this.glType, vAddr);
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
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, GL11.GL_RED, this.glType, 0L);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH, GL11.GL_RED, this.glType, 0L);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, vRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[2 * NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH, GL11.GL_RED, this.glType, 0L);

            this.checkGLError("YUV3 PBO texSub");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.seedPBO(writeIdx, ySize, yAddr);
            this.seedPBO(NUM_PBOS + writeIdx, uSize, uAddr);
            this.seedPBO(2 * NUM_PBOS + writeIdx, vSize, vAddr);
            this.pboWriteIdx = writeIdx;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        this.convertToRGBA();
    }

    // UPLOAD — FOUR PLANES (YUVA420P/422P/444P)
    @Override
    public void upload(final ByteBuffer yBuffer, final int yStride,
                       final ByteBuffer uBuffer, final int uStride,
                       final ByteBuffer vBuffer, final int vStride,
                       final ByteBuffer aBuffer, final int aStride) {
        if (yBuffer == null || uBuffer == null || vBuffer == null || aBuffer == null) return;
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.upload(yBuffer, yStride, uBuffer, uStride, vBuffer, vStride, aBuffer, aStride));
            return;
        }
        if (this.width <= 0 || this.height <= 0) return;
        if (this.shaderYUVA == 0) return;

        if (this.yTexture == 0) this.yTexture = this.newTexture();
        if (this.uTexture == 0) this.uTexture = this.newTexture();
        if (this.vTexture == 0) this.vTexture = this.newTexture();
        if (this.aTexture == 0) this.aTexture = this.newTexture();

        final int chromaW, chromaH;
        switch (this.colorSpace) {
            case YUVA422P -> { chromaW = this.width / 2; chromaH = this.height; }
            case YUVA444P -> { chromaW = this.width; chromaH = this.height; }
            default /* YUVA420P */ -> { chromaW = this.width / 2; chromaH = this.height / 2; }
        }

        final int yRowLen = (yStride == 0) ? this.width : yStride / this.bytesPerSample;
        final int uRowLen = (uStride == 0) ? chromaW : uStride / this.bytesPerSample;
        final int vRowLen = (vStride == 0) ? chromaW : vStride / this.bytesPerSample;
        final int aRowLen = (aStride == 0) ? this.width : aStride / this.bytesPerSample;

        final int effY = (yStride == 0) ? this.width * this.bytesPerSample : yStride;
        final int effU = (uStride == 0) ? chromaW * this.bytesPerSample : uStride;
        final int effV = (vStride == 0) ? chromaW * this.bytesPerSample : vStride;
        final int effA = (aStride == 0) ? this.width * this.bytesPerSample : aStride;

        final long ySize = (long) effY * this.height;
        final long uSize = (long) effU * chromaH;
        final long vSize = (long) effV * chromaH;
        final long aSize = (long) effA * this.height;

        final long yAddr = MemoryUtil.memAddress(yBuffer);
        final long uAddr = MemoryUtil.memAddress(uBuffer);
        final long vAddr = MemoryUtil.memAddress(vBuffer);
        final long aAddr = MemoryUtil.memAddress(aBuffer);
        if (yAddr == 0L || uAddr == 0L || vAddr == 0L || aAddr == 0L) return;

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (!this.pboInitialized) {
            this.initPBOs(4);
        }

        if (this.firstFrame || !this.pboReady
                || this.pboAllocSizes[0] != ySize
                || this.pboAllocSizes[1] != uSize
                || this.pboAllocSizes[2] != vSize
                || this.pboAllocSizes[3] != aSize) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, this.width, this.height, 0, GL11.GL_RED, this.glType, yAddr);
            this.checkGLError("YUVA Y texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, chromaW, chromaH, 0, GL11.GL_RED, this.glType, uAddr);
            this.checkGLError("YUVA U texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, vRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, chromaW, chromaH, 0, GL11.GL_RED, this.glType, vAddr);
            this.checkGLError("YUVA V texImage");

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.aTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, aRowLen);
            GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, this.glInternalR, this.width, this.height, 0, GL11.GL_RED, this.glType, aAddr);
            this.checkGLError("YUVA A texImage");

            this.seedPBO(0, ySize, yAddr);
            this.seedPBO(NUM_PBOS, uSize, uAddr);
            this.seedPBO(2 * NUM_PBOS, vSize, vAddr);
            this.seedPBO(3 * NUM_PBOS, aSize, aAddr);
            this.pboAllocSizes[0] = ySize;
            this.pboAllocSizes[1] = uSize;
            this.pboAllocSizes[2] = vSize;
            this.pboAllocSizes[3] = aSize;
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, yRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, GL11.GL_RED, this.glType, 0L);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, uRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH, GL11.GL_RED, this.glType, 0L);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, vRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[2 * NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, chromaW, chromaH, GL11.GL_RED, this.glType, 0L);

            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.aTexture);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, aRowLen);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[3 * NUM_PBOS + readIdx]);
            GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, GL11.GL_RED, this.glType, 0L);

            this.checkGLError("YUVA PBO texSub");
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            this.seedPBO(writeIdx, ySize, yAddr);
            this.seedPBO(NUM_PBOS + writeIdx, uSize, uAddr);
            this.seedPBO(2 * NUM_PBOS + writeIdx, vSize, vAddr);
            this.seedPBO(3 * NUM_PBOS + writeIdx, aSize, aAddr);
            this.pboWriteIdx = writeIdx;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        this.convertToRGBA();
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

    // FBO CONVERSION — RENDERS PLANE TEXTURES THROUGH SHADER INTO managedTexture
    private void convertToRGBA() {
        if (this.managedTexture == 0) this.managedTexture = this.newTexture();
        if (this.fbo == 0) this.fbo = GL30.glGenFramebuffers();
        this.initQuad();

        if (this.managedTextureW != this.width || this.managedTextureH != this.height) {
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.managedTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.checkGLError("managed texture alloc");
            this.managedTextureW = this.width;
            this.managedTextureH = this.height;
        }

        final int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int[] savedViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        final int savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        final int savedVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.managedTexture, 0);
        GL11.glViewport(0, 0, this.width, this.height);

        switch (this.colorSpace) {
            case GRAY -> {
                GL20.glUseProgram(this.shaderGray);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
                GL20.glUniform1i(this.uniformGrayTex, 0);
                GL20.glUniform1f(this.uniformGrayBitScale, this.bitScale);
            }
            case NV12, NV21 -> {
                GL20.glUseProgram(this.shaderNV);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
                GL20.glUniform1i(this.uniformNVYTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uvTexture);
                GL20.glUniform1i(this.uniformNVUVTex, 1);
                GL20.glUniform1f(this.uniformNVSwap, this.colorSpace == ColorSpace.NV21 ? 1.0f : 0.0f);
                GL20.glUniform1f(this.uniformNVBitScale, this.bitScale);
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
                GL20.glUniform1f(this.uniformYUV3BitScale, this.bitScale);
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                GL20.glUseProgram(this.shaderYUVA);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
                GL20.glUniform1i(this.uniformYUVAYTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.uTexture);
                GL20.glUniform1i(this.uniformYUVAUTex, 1);
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.vTexture);
                GL20.glUniform1i(this.uniformYUVAVTex, 2);
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.aTexture);
                GL20.glUniform1i(this.uniformYUVAATex, 3);
                GL20.glUniform1f(this.uniformYUVABitScale, this.bitScale);
            }
            case YUYV, YUYV2 -> {
                GL20.glUseProgram(this.shaderYUYV);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.yTexture);
                GL20.glUniform1i(this.uniformYUYVTex, 0);
                GL20.glUniform1f(this.uniformYUYVWidth, (float) this.width);
                GL20.glUniform1f(this.uniformYUYVSwap, this.colorSpace == ColorSpace.YUYV2 ? 1.0f : 0.0f);
            }
            default -> {}
        }

        GL30.glBindVertexArray(this.quadVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
        this.checkGLError("FBO convert quad");

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
        final float[] verts = {
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

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        GL30.glBindVertexArray(0);
        this.checkGLError("initQuad");
    }

    private void initPBOs(final int planeCount) {
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
        if (this.aTexture != 0) { this.delTexture.accept(this.aTexture); this.aTexture = 0; }
    }

    private void releasePBOs() {
        if (!this.pboInitialized) return;
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
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
        if (this.shaderGray != 0) { GL20.glDeleteProgram(this.shaderGray); this.shaderGray = 0; }
        if (this.shaderNV != 0) { GL20.glDeleteProgram(this.shaderNV); this.shaderNV = 0; }
        if (this.shaderYUV3 != 0) { GL20.glDeleteProgram(this.shaderYUV3); this.shaderYUV3 = 0; }
        if (this.shaderYUVA != 0) { GL20.glDeleteProgram(this.shaderYUVA); this.shaderYUVA = 0; }
        if (this.shaderYUYV != 0) { GL20.glDeleteProgram(this.shaderYUYV); this.shaderYUYV = 0; }
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
