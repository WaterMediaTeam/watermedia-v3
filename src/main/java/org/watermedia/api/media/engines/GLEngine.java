package org.watermedia.api.media.engines;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * OpenGL implementation of {@link GFXEngine} with asynchronous frame submission and YUV shader
 * conversion.
 * <p>
 * All GL work happens on the render thread (the OpenGL context is thread-bound). Frames submitted
 * from other threads go through a latest-wins slot: at most one upload task is queued per engine,
 * and submitting a newer frame before the previous one was consumed replaces it. This bounds
 * render-thread work to one upload per engine per render frame regardless of how fast the
 * producer decodes.
 * <p>
 * When {@code ARB_buffer_storage} (OpenGL 4.4) is available, pixel data is written by the
 * <em>producer</em> thread directly into a persistently-mapped PBO ring, so the render thread only
 * issues {@code glTexSubImage2D} from buffer offsets and a fence. Without it, the engine falls
 * back to the classic double-buffered PBO upload performed on the render thread.
 * <p>
 * Supports direct upload (BGRA, RGBA, RGB), packed YUV (YUYV/UYVY), semi-planar (NV12/NV21/P010),
 * planar YUV (420P/422P/444P), planar YUVA (with alpha), and grayscale (GRAY).
 * All YUV/GRAY formats are converted to RGBA on the GPU via FBO + shader pass.
 * High bit depths (10/12/16/32-bit) are supported via GL_R16/GL_RG16/GL_R32F textures.
 * The developer always sees a single RGBA texture from {@link #texture()}.
 */
public final class GLEngine extends GFXEngine {
    private static final Marker IT = MarkerManager.getMarker(GLEngine.class.getSimpleName());

    // HOT-PATH glGetError CALLS FORCE DRIVER SYNCHRONIZATION (NOTABLY WITH NVIDIA THREADED
    // OPTIMIZATION). THEY ARE DISABLED UNLESS THE DEBUG FLAG IS SET; COLD PATHS ALWAYS CHECK.
    private static final boolean GL_CHECKS = Boolean.getBoolean("watermedia.glchecks");

    // PBO CONFIGURATION
    private static final int NUM_PBOS = 2;   // LEGACY DOUBLE BUFFER PER PLANE
    private static final int MAX_PLANES = 4;
    private static final int RING_SLOTS = 4; // PERSISTENT RING DEPTH (1 WRITING + 1 PENDING + GPU IN-FLIGHT)
    private static final int[] EMPTY_TEXTURES = new int[0];

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
    private final IntConsumer activeTexture;
    private final IntConsumer bindVertexArray;
    private final BindConsumer bindFrameBuffer;
    private final BindConsumer bindBuffer;

    // THREAD CONTEXT (OPENGL-SPECIFIC)
    private final Thread renderThread;
    private final Executor renderThreadEx;

    // MANAGED TEXTURE (WHAT THE DEV BINDS)
    private int managedTexture = 0;
    private int managedTextureW = 0;
    private int managedTextureH = 0;

    // PRELOADED FRAME TEXTURES (ANIMATED IMAGE FAST PATH). UPLOADS ARE SPREAD ACROSS RENDER
    // TICKS; frameTexReady PUBLISHES HOW MANY FRAMES ARE ALREADY USABLE SO useFrameTexture()
    // CAN CLAMP WHILE THE TAIL IS STILL UPLOADING. frameTexGen INVALIDATES STALE UPLOAD TASKS.
    private volatile int[] frameTextures = EMPTY_TEXTURES;
    private volatile int activeFrameTexture = -1;
    private volatile int frameTexReady = 0;
    private int frameTexGen = 0;

    // LATEST-WINS SUBMISSION SLOT (PRODUCER WRITES, RENDER THREAD DRAINS).
    // AT MOST ONE DRAIN TASK IS QUEUED; A NEWER SUBMISSION REPLACES AN UNDRAINED OLDER ONE.
    private volatile Submission pending;
    private volatile boolean drainQueued;

    // PERSISTENT-MAPPED PBO RING (ARB_buffer_storage). THE PRODUCER THREAD MEMCPYS PIXELS INTO
    // THE MAPPED REGION SO THE RENDER THREAD NEVER TOUCHES CLIENT MEMORY. SLOT LIFECYCLE:
    // PRODUCER CLAIMS slot = ringProduced WHEN ringProduced - ringRetired < RING_SLOTS;
    // THE RENDER THREAD CONSUMES THE LATEST SLOT (OLDER ONES ARE SKIPPED), FENCES IT, AND
    // RETIRES SLOTS IN ORDER ONCE THEIR FENCES SIGNAL. ringLock GUARDS ARM/DESTROY VS MEMCPY.
    private final Object ringLock = new Object();
    private volatile long ringAddr;       // 0 = NOT ARMED. PUBLISHED LAST ON ARM
    private volatile long ringRetired;    // RENDER THREAD WRITER, PRODUCER READER
    private volatile int ringEra;         // BUMPED ON ARM/DESTROY TO INVALIDATE STALE SLOTS
    private long ringSlotBytes;           // GUARDED BY ringLock
    private long ringProduced;            // GUARDED BY ringLock
    private long ringConsumed;            // RENDER THREAD ONLY
    private int ringBufferId;             // RENDER THREAD ONLY
    private ByteBuffer ringMapped;        // RENDER THREAD ONLY (KEEPS THE MAPPING REACHABLE)
    private final long[] ringFences = new long[RING_SLOTS];
    private Boolean ringCapable;          // LAZY ARB_buffer_storage PROBE (RENDER THREAD)

    // FBO FOR YUV TO RGBA CONVERSION
    private int fbo = 0;

    // FULLSCREEN QUAD (CORE PROFILE — NO IMMEDIATE MODE)
    private int quadVAO = 0;
    private int quadVBO = 0;

    // PLANE LAYOUT FOR THE CURRENT FORMAT (BUILT IN setVideoFormat ON THE RENDER THREAD)
    private Plane[] planes = new Plane[0];
    private boolean convert;              // TRUE WHEN AN FBO SHADER PASS PRODUCES managedTexture

    // LEGACY PER-PLANE PBO STATE — pbos[PLANE * NUM_PBOS + BUFFER_INDEX]
    private final int[] pbos = new int[MAX_PLANES * NUM_PBOS];
    private int pboWriteIdx = 0;
    private boolean pboInitialized = false;
    private boolean pboReady = false;
    private int activePlanes = 0;
    private final long[] planeBytes = new long[MAX_PLANES]; // CURRENTLY ALLOCATED BYTES PER PLANE
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

    private GLEngine(final Thread renderThread, final Executor renderThreadEx,
                     final IntSupplier genTexture, final BindConsumer bindTexture,
                     final TexParamConsumer texParameter, final BindConsumer pixelStore,
                     final IntConsumer delTexture, final IntConsumer activeTexture,
                     final IntConsumer bindVertexArray, final BindConsumer bindFrameBuffer,
                     final BindConsumer bindBuffer) {
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
        this.activeTexture = activeTexture;
        this.bindVertexArray = bindVertexArray;
        this.bindFrameBuffer = bindFrameBuffer;
        this.bindBuffer = bindBuffer;

        // VALIDATE THREAD PAIR
        if (renderThreadEx != null) {
            renderThreadEx.execute(() -> {
                if (renderThread != null && Thread.currentThread() != renderThread) {
                    throw new IllegalStateException("GLEngine: renderThreadEx must dispatch to renderThread");
                }
            });
        }
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    @Override
    public long texture() {
        final int[] textures = this.frameTextures;
        final int frame = this.activeFrameTexture;
        if (frame >= 0 && frame < textures.length) return textures[frame];
        return this.managedTexture;
    }

    @Override
    public boolean supportsFrameTextures() {
        return true;
    }

    @Override
    public boolean uploadFrameTextures(final ByteBuffer[] frames, final int stride) {
        if (frames == null || frames.length == 0) return false;
        for (final ByteBuffer frame: frames) {
            if (frame == null || !frame.isDirect()) return false;
        }
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.scheduleFrameTextures(frames, stride);
            return true;
        }
        if (this.width <= 0 || this.height <= 0) return false;
        if (!this.directTextureUploadSupported()) return false;

        // SYNCHRONOUS PATH (ALREADY ON RENDER THREAD): UPLOAD EVERYTHING NOW.
        this.beginFrameTextures();
        final int[] textures = new int[frames.length];
        for (int i = 0; i < frames.length; i++) {
            textures[i] = this.newTexture();
            if (!this.uploadDirectTexture(textures[i], frames[i], stride)) {
                this.deleteTextures(textures);
                return false;
            }
        }
        this.frameTextures = textures;
        this.frameTexReady = textures.length;
        this.activeFrameTexture = 0;
        return true;
    }

    // ASYNC FRAME-TEXTURE UPLOAD: ONE RENDER TASK PER FRAME SO A LONG ANIMATION NEVER STALLS A
    // SINGLE RENDER TICK. FRAME 0 IS PUBLISHED IMMEDIATELY; useFrameTexture() CLAMPS TO THE
    // UPLOADED PREFIX UNTIL THE TAIL COMPLETES. genBox INVALIDATES THE BATCH IF THE ENGINE IS
    // RELEASED OR REFORMATTED MID-UPLOAD.
    private void scheduleFrameTextures(final ByteBuffer[] frames, final int stride) {
        final int[] genBox = new int[1];
        final int[][] texBox = new int[1][];
        this.renderThreadEx.execute(() -> {
            if (this.width <= 0 || this.height <= 0 || !this.directTextureUploadSupported()) {
                genBox[0] = -1;
                LOGGER.warn(IT, "Frame textures rejected: format {} not direct-uploadable", this.pixelFormat);
                return;
            }
            this.beginFrameTextures();
            genBox[0] = this.frameTexGen;
            final int[] textures = new int[frames.length];
            textures[0] = this.newTexture();
            if (!this.uploadDirectTexture(textures[0], frames[0], stride)) {
                this.deleteTextures(textures);
                genBox[0] = -1;
                return;
            }
            texBox[0] = textures;
            this.frameTextures = textures;
            this.frameTexReady = 1;
            this.activeFrameTexture = 0;
        });
        for (int i = 1; i < frames.length; i++) {
            final int index = i;
            this.renderThreadEx.execute(() -> {
                if (genBox[0] != this.frameTexGen || texBox[0] == null) return; // STALE BATCH
                final int[] textures = texBox[0];
                textures[index] = this.newTexture();
                if (this.uploadDirectTexture(textures[index], frames[index], stride)) {
                    this.frameTexReady = index + 1;
                }
            });
        }
    }

    // PREPARES THE ENGINE FOR A FRESH FRAME-TEXTURE SET: DROPS STREAMING RESOURCES (RING, PBOS)
    // AND ANY PREVIOUS SET, THEN CLAIMS A NEW GENERATION.
    private void beginFrameTextures() {
        this.releaseFrameTextures();
        this.releasePBOs();
        this.destroyRing();
        this.firstFrame = true;
        this.frameTexGen++;
    }

    @Override
    public void useFrameTexture(final int frameIndex) {
        final int ready = this.frameTexReady;
        if (ready <= 0) return;
        // CLAMP TO THE UPLOADED PREFIX WHILE THE TAIL OF THE ANIMATION IS STILL UPLOADING
        this.activeFrameTexture = Math.min(Math.max(frameIndex, 0), ready - 1);
    }

    public boolean hasGLContext() {
        return GLFW.glfwGetCurrentContext() != 0L;
    }

    /**
     * Resets the entire pipeline and prepares for a new format.
     * Releases plane textures, PBOs, the persistent ring, and recompiles shaders if the pixel
     * format changed. The managed texture is kept but reallocated on next upload if dimensions
     * changed.
     */
    @Override
    public void setVideoFormat(final PixelFormat pixelFormat, final int width, final int height, final int bitsPerComponent) {
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.setVideoFormat(pixelFormat, width, height, bitsPerComponent));
            return;
        }

        // RELEASE FORMAT-SPECIFIC RESOURCES
        this.releaseFrameTextures();
        this.releasePlaneTextures();
        this.releasePBOs();
        this.destroyRing();

        // RESET UPLOAD STATE
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

        // UPDATE BASE STATE THEN BUILD THE PLANE LAYOUT FOR THE NEW FORMAT
        super.setVideoFormat(pixelFormat, width, height, bitsPerComponent);
        this.buildPlanes();
        this.activePlanes = this.planes.length;

        // COMPILE SHADERS FOR NEW FORMAT (LAZY — ONLY IF NOT ALREADY COMPILED)
        switch (pixelFormat) {
            case GRAY -> {
                if (this.shaderGray == 0) {
                    this.shaderGray = this.compileShader(VERTEX_SHADER, FRAGMENT_GRAY);
                    if (this.shaderGray != 0) {
                        this.uniformGrayTex = GL20.glGetUniformLocation(this.shaderGray, "yTex");
                        this.uniformGrayBitScale = GL20.glGetUniformLocation(this.shaderGray, "bitScale");
                        LOGGER.info(IT, "Successfully compiled Gray shader (program={})", this.shaderGray);
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
                        LOGGER.info(IT, "Successfully compiled NV shader (program={})", this.shaderNV);
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
                        LOGGER.info(IT, "Successfully compiled YUV3 shader (program={})", this.shaderYUV3);
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
                        LOGGER.info(IT, "Successfully compiled YUVA shader (program={})", this.shaderYUVA);
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
                        LOGGER.info(IT, "Successfully compiled YUYV shader (program={})", this.shaderYUYV);
                    }
                }
            }
            default -> {} // BGRA, RGBA, RGB — NO SHADER NEEDED
        }

        LOGGER.info(IT, "Format set: {} {}x{} ({} planes, {}bpc)", pixelFormat, width, height, this.planes.length, bitsPerComponent);
    }

    // ==========================================================================
    // UPLOAD ENTRY POINTS — THIN WRAPPERS OVER submit()
    // ==========================================================================
    @Override
    public void upload(final ByteBuffer buffer, final int stride) {
        this.submit(new ByteBuffer[]{buffer}, new int[]{stride});
    }

    @Override
    public void upload(final ByteBuffer yBuffer, final int yStride, final ByteBuffer uvBuffer, final int uvStride) {
        this.submit(new ByteBuffer[]{yBuffer, uvBuffer}, new int[]{yStride, uvStride});
    }

    @Override
    public void upload(final ByteBuffer yBuffer, final int yStride,
                       final ByteBuffer uBuffer, final int uStride,
                       final ByteBuffer vBuffer, final int vStride) {
        this.submit(new ByteBuffer[]{yBuffer, uBuffer, vBuffer}, new int[]{yStride, uStride, vStride});
    }

    @Override
    public void upload(final ByteBuffer yBuffer, final int yStride,
                       final ByteBuffer uBuffer, final int uStride,
                       final ByteBuffer vBuffer, final int vStride,
                       final ByteBuffer aBuffer, final int aStride) {
        this.submit(new ByteBuffer[]{yBuffer, uBuffer, vBuffer, aBuffer}, new int[]{yStride, uStride, vStride, aStride});
    }

    // ==========================================================================
    // SUBMISSION — PRODUCER SIDE
    // ==========================================================================
    // VALIDATES THE FRAME, TRIES TO WRITE IT INTO THE PERSISTENT RING (PRODUCER-SIDE MEMCPY),
    // AND OTHERWISE PARKS THE CLIENT BUFFERS IN THE LATEST-WINS PENDING SLOT. AT MOST ONE
    // DRAIN TASK IS QUEUED AT ANY TIME.
    private void submit(final ByteBuffer[] bufs, final int[] strides) {
        for (final ByteBuffer buf: bufs) {
            if (buf == null || !buf.isDirect()) return;
        }
        if (this.renderThread == null || this.renderThread == Thread.currentThread()) {
            // SYNCHRONOUS PATH: ALREADY ON THE RENDER THREAD (OR NO THREAD CONTRACT AT ALL)
            this.renderSubmission(new Submission(bufs, strides, this.sizesOf(bufs), -1L, 0));
            return;
        }

        // RING CONGESTION (DROPPED) SKIPS THE FRAME BUT STILL SCHEDULES A DRAIN SO FENCES
        // RETIRE AND THE RING RECOVERS.
        final int[] sizes = this.sizesOf(bufs);
        final Submission ring = this.ringWrite(bufs, strides, sizes);
        if (ring != DROPPED) {
            this.pending = ring != null ? ring : new Submission(bufs, strides, sizes, -1L, 0);
        }
        if (!this.drainQueued) {
            this.drainQueued = true;
            try {
                this.renderThreadEx.execute(this::drain);
            } catch (final RuntimeException e) {
                this.drainQueued = false; // KEEP THE PIPELINE RECOVERABLE IF THE EXECUTOR REJECTS
                throw e;
            }
        }
    }

    private int[] sizesOf(final ByteBuffer[] bufs) {
        final int[] sizes = new int[bufs.length];
        for (int i = 0; i < bufs.length; i++) sizes[i] = bufs[i].remaining();
        return sizes;
    }

    // COPIES ALL PLANES INTO THE NEXT FREE RING SLOT. RETURNS THE SLOT SUBMISSION, NULL WHEN
    // THE RING IS UNAVAILABLE/TOO SMALL (CALLER FALLS BACK TO CLIENT BUFFERS), OR DROPPED WHEN
    // ALL SLOTS ARE STILL IN FLIGHT.
    private Submission ringWrite(final ByteBuffer[] bufs, final int[] strides, final int[] sizes) {
        long total = 0L;
        for (final int size: sizes) total += size;
        synchronized (this.ringLock) {
            if (this.ringAddr == 0L || total > this.ringSlotBytes) return null;
            if (this.ringProduced - this.ringRetired >= RING_SLOTS) return DROPPED;
            final long slot = this.ringProduced;
            long dst = this.ringAddr + (slot % RING_SLOTS) * this.ringSlotBytes;
            for (int i = 0; i < bufs.length; i++) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(bufs[i]), dst, sizes[i]);
                dst += sizes[i];
            }
            this.ringProduced = slot + 1;
            return new Submission(null, strides, sizes, slot, this.ringEra);
        }
    }

    // ==========================================================================
    // SUBMISSION — RENDER THREAD SIDE
    // ==========================================================================
    private void drain() {
        this.drainQueued = false;
        final Submission s = this.pending;
        this.pending = null;
        if (s != null) this.renderSubmission(s);
        this.retireRing(this.ringConsumed);
    }

    private void renderSubmission(final Submission s) {
        if (this.width <= 0 || this.height <= 0 || this.planes.length == 0) return;
        if (s.sizes.length != this.planes.length) return;                          // STALE FORMAT
        if (s.slot >= 0L && (s.era != this.ringEra || this.ringAddr == 0L)) return; // STALE RING

        if (this.frameTextures.length > 0) this.releaseFrameTextures();
        this.ensureTargets();

        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (s.slot >= 0L) {
            // FREE SLOTS THE LATEST-WINS POLICY SKIPPED, THEN CONSUME THIS ONE
            this.retireRing(s.slot);
            this.uploadRing(s);
            this.ringConsumed = s.slot + 1;
        } else {
            this.uploadClient(s);
        }

        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        this.firstFrame = false;
        if (this.convert) this.convertToRGBA();
    }

    // UPLOAD FROM CLIENT MEMORY: FIRST FRAME (OR SIZE CHANGE) RE-SPECS THE TEXTURES DIRECTLY,
    // STEADY STATE GOES THROUGH THE LEGACY DOUBLE-BUFFERED PBO PAIR. AFTERWARDS THE PERSISTENT
    // RING IS ARMED (OR REGROWN) SO SUBSEQUENT FRAMES SKIP THE RENDER-THREAD MEMCPY.
    private void uploadClient(final Submission s) {
        long total = 0L;
        boolean respec = this.firstFrame || !this.pboReady;
        for (int i = 0; i < this.planes.length; i++) {
            final Plane p = this.planes[i];
            final long needed = (long) p.effStride(s.strides[i]) * p.h;
            if (s.sizes[i] < needed) {
                LOGGER.warn(IT, "Plane {} buffer too small: {} < {}", i, s.sizes[i], needed);
                return;
            }
            respec |= this.planeBytes[i] != needed;
            // RING SLOTS ARE SIZED FOR WHAT PRODUCERS ACTUALLY COPY (remaining()), WHICH MAY
            // EXCEED THE TIGHT PLANE BYTES WHEN BUFFERS CARRY ALIGNMENT PADDING
            total += s.sizes[i];
        }

        if (!this.pboInitialized) this.initPBOs(this.planes.length);

        if (respec) {
            this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < this.planes.length; i++) {
                final Plane p = this.planes[i];
                final long needed = (long) p.effStride(s.strides[i]) * p.h;
                final long addr = MemoryUtil.memAddress(s.bufs[i]);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, p.tex);
                this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
                GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, p.internal, p.w, p.h, 0, p.format, p.type, addr);
                this.checkGLError("plane texImage");
                this.seedPBO(i * NUM_PBOS, needed, addr);
                this.planeBytes[i] = needed;
            }
            this.pboWriteIdx = 0;
            this.pboReady = true;
        } else {
            final int readIdx = this.pboWriteIdx;
            final int writeIdx = (readIdx + 1) % NUM_PBOS;
            for (int i = 0; i < this.planes.length; i++) {
                final Plane p = this.planes[i];
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, p.tex);
                this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
                this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[i * NUM_PBOS + readIdx]);
                GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, p.w, p.h, p.format, p.type, 0L);
                if (GL_CHECKS) this.checkGLError("plane PBO texSub");
            }
            this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < this.planes.length; i++) {
                this.seedPBO(i * NUM_PBOS + writeIdx, this.planeBytes[i], MemoryUtil.memAddress(s.bufs[i]));
            }
            this.pboWriteIdx = writeIdx;
        }

        // ARM (OR REGROW) THE PERSISTENT RING SO THE PRODUCER THREAD TAKES OVER THE MEMCPY
        if (this.ringAddr == 0L || total > this.ringSlotBytes) {
            this.armRing(total);
        }
    }

    // UPLOAD FROM A RING SLOT: PIXELS ARE ALREADY IN GPU-VISIBLE MEMORY, SO THIS ONLY ISSUES
    // texSubImage FROM BUFFER OFFSETS AND FENCES THE SLOT. PLANE SIZE CHANGES RE-SPEC VIA
    // texImage SOURCED FROM THE SAME OFFSETS.
    private void uploadRing(final Submission s) {
        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, this.ringBufferId);
        long offset = (s.slot % RING_SLOTS) * this.ringSlotBytes;
        for (int i = 0; i < this.planes.length; i++) {
            final Plane p = this.planes[i];
            final long needed = (long) p.effStride(s.strides[i]) * p.h;
            if (s.sizes[i] < needed) {
                LOGGER.warn(IT, "Ring plane {} too small: {} < {}", i, s.sizes[i], needed);
                break;
            }
            this.bindTexture.accept(GL11.GL_TEXTURE_2D, p.tex);
            this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
            if (this.planeBytes[i] != needed) {
                GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, p.internal, p.w, p.h, 0, p.format, p.type, offset);
                this.checkGLError("ring plane texImage");
                this.planeBytes[i] = needed;
            } else {
                GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, p.w, p.h, p.format, p.type, offset);
                if (GL_CHECKS) this.checkGLError("ring plane texSub");
            }
            offset += s.sizes[i];
        }
        this.ringFences[(int) (s.slot % RING_SLOTS)] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    // RETIRES RING SLOTS STRICTLY IN ORDER UP TO cap. SKIPPED SLOTS (NO FENCE) RETIRE
    // IMMEDIATELY; CONSUMED SLOTS RETIRE ONCE THE GPU SIGNALED THEIR FENCE.
    private void retireRing(final long cap) {
        long retired = this.ringRetired;
        while (retired < cap) {
            final int idx = (int) (retired % RING_SLOTS);
            final long fence = this.ringFences[idx];
            if (fence != 0L) {
                final int state = GL32.glClientWaitSync(fence, 0, 0L);
                if (state != GL32.GL_ALREADY_SIGNALED && state != GL32.GL_CONDITION_SATISFIED) break;
                GL32.glDeleteSync(fence);
                this.ringFences[idx] = 0L;
            }
            retired++;
        }
        this.ringRetired = retired;
    }

    // ==========================================================================
    // PERSISTENT RING LIFECYCLE (RENDER THREAD)
    // ==========================================================================
    private void armRing(final long slotBytes) {
        if (this.ringCapable == null) {
            final GLCapabilities caps = GL.getCapabilities();
            this.ringCapable = caps.GL_ARB_buffer_storage || caps.OpenGL44;
            if (!this.ringCapable) LOGGER.info(IT, "ARB_buffer_storage unavailable — using legacy PBO uploads");
        }
        if (!this.ringCapable || slotBytes <= 0L) return;
        this.destroyRing();

        final int flags = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
        final int id = GL15.glGenBuffers();
        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, id);
        GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, slotBytes * RING_SLOTS, flags);
        final ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0, slotBytes * RING_SLOTS, flags);
        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        this.checkGLError("armRing");
        if (mapped == null) {
            GL15.glDeleteBuffers(id);
            this.ringCapable = false;
            return;
        }
        synchronized (this.ringLock) {
            this.ringBufferId = id;
            this.ringMapped = mapped;
            this.ringSlotBytes = slotBytes;
            this.ringProduced = 0L;
            this.ringRetired = 0L;
            this.ringConsumed = 0L;
            this.ringEra++;
            this.ringAddr = MemoryUtil.memAddress(mapped); // PUBLISH LAST
        }
        LOGGER.debug(IT, "Persistent PBO ring armed: {} slots x {} bytes", RING_SLOTS, slotBytes);
    }

    private void destroyRing() {
        synchronized (this.ringLock) {
            this.ringAddr = 0L; // UNPUBLISH FIRST SO PRODUCERS STOP WRITING
            if (this.ringBufferId != 0) {
                this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, this.ringBufferId);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
                this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL15.glDeleteBuffers(this.ringBufferId);
                this.ringBufferId = 0;
            }
            for (int i = 0; i < RING_SLOTS; i++) {
                if (this.ringFences[i] != 0L) {
                    GL32.glDeleteSync(this.ringFences[i]);
                    this.ringFences[i] = 0L;
                }
            }
            this.ringMapped = null;
            this.ringSlotBytes = 0L;
            this.ringProduced = 0L;
            this.ringRetired = 0L;
            this.ringConsumed = 0L;
            this.ringEra++;
        }
    }

    // ==========================================================================
    // RELEASE
    // ==========================================================================
    @Override
    public void release() {
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(this::release);
            return;
        }
        this.destroyRing();
        if (this.fbo != 0) { GL30.glDeleteFramebuffers(this.fbo); this.fbo = 0; }
        if (this.quadVAO != 0) { GL30.glDeleteVertexArrays(this.quadVAO); this.quadVAO = 0; }
        if (this.quadVBO != 0) { GL15.glDeleteBuffers(this.quadVBO); this.quadVBO = 0; }
        this.releaseFrameTextures();
        this.releasePlaneTextures();
        if (this.managedTexture != 0) { this.delTexture.accept(this.managedTexture); this.managedTexture = 0; }
        this.managedTextureW = 0;
        this.managedTextureH = 0;
        this.releasePBOs();
        this.releaseShaders();
        this.firstFrame = true;
    }

    // ==========================================================================
    // FBO CONVERSION — RENDERS PLANE TEXTURES THROUGH SHADER INTO managedTexture
    // ==========================================================================
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

        this.bindFrameBuffer.accept(GL30.GL_FRAMEBUFFER, this.fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.managedTexture, 0);
        GL11.glViewport(0, 0, this.width, this.height);

        switch (this.pixelFormat) {
            case GRAY -> {
                GL20.glUseProgram(this.shaderGray);
                this.activeTexture.accept(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformGrayTex, 0);
                GL20.glUniform1f(this.uniformGrayBitScale, this.bitScale);
            }
            case NV12, NV21 -> {
                GL20.glUseProgram(this.shaderNV);
                this.activeTexture.accept(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformNVYTex, 0);
                this.activeTexture.accept(GL13.GL_TEXTURE1);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[1].tex);
                GL20.glUniform1i(this.uniformNVUVTex, 1);
                GL20.glUniform1f(this.uniformNVSwap, this.pixelFormat == PixelFormat.NV21 ? 1.0f : 0.0f);
                GL20.glUniform1f(this.uniformNVBitScale, this.bitScale);
            }
            case YUV420P, YUV422P, YUV444P -> {
                GL20.glUseProgram(this.shaderYUV3);
                this.activeTexture.accept(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformYUV3YTex, 0);
                this.activeTexture.accept(GL13.GL_TEXTURE1);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[1].tex);
                GL20.glUniform1i(this.uniformYUV3UTex, 1);
                this.activeTexture.accept(GL13.GL_TEXTURE2);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[2].tex);
                GL20.glUniform1i(this.uniformYUV3VTex, 2);
                GL20.glUniform1f(this.uniformYUV3BitScale, this.bitScale);
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                GL20.glUseProgram(this.shaderYUVA);
                this.activeTexture.accept(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformYUVAYTex, 0);
                this.activeTexture.accept(GL13.GL_TEXTURE1);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[1].tex);
                GL20.glUniform1i(this.uniformYUVAUTex, 1);
                this.activeTexture.accept(GL13.GL_TEXTURE2);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[2].tex);
                GL20.glUniform1i(this.uniformYUVAVTex, 2);
                this.activeTexture.accept(GL13.GL_TEXTURE3);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[3].tex);
                GL20.glUniform1i(this.uniformYUVAATex, 3);
                GL20.glUniform1f(this.uniformYUVABitScale, this.bitScale);
            }
            case YUYV, YUYV2 -> {
                GL20.glUseProgram(this.shaderYUYV);
                this.activeTexture.accept(GL13.GL_TEXTURE0);
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformYUYVTex, 0);
                GL20.glUniform1f(this.uniformYUYVWidth, (float) this.width);
                GL20.glUniform1f(this.uniformYUYVSwap, this.pixelFormat == PixelFormat.YUYV2 ? 1.0f : 0.0f);
            }
            default -> {}
        }

        this.bindVertexArray.accept(this.quadVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        this.bindVertexArray.accept(0);
        if (GL_CHECKS) this.checkGLError("FBO convert quad");

        GL20.glUseProgram(savedProgram);
        this.activeTexture.accept(GL13.GL_TEXTURE0);
        this.bindFrameBuffer.accept(GL30.GL_FRAMEBUFFER, savedFbo);
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        this.bindVertexArray.accept(savedVAO);
    }

    // ==========================================================================
    // PLANE LAYOUT
    // ==========================================================================
    // BUILDS THE PER-PLANE TEXTURE LAYOUT FOR THE CURRENT FORMAT. DIRECT FORMATS (BGRA/RGBA/RGB)
    // TARGET managedTexture WITHOUT A SHADER PASS; EVERYTHING ELSE RENDERS PLANE TEXTURES
    // THROUGH AN FBO CONVERT.
    private void buildPlanes() {
        final int w = this.width;
        final int h = this.height;
        final int bps = this.bytesPerSample;
        this.convert = switch (this.pixelFormat) {
            case BGRA, RGBA, GBRA, RGB -> false;
            default -> true;
        };
        this.planes = switch (this.pixelFormat) {
            case BGRA -> new Plane[]{
                    new Plane(w, h, GL11.GL_RGBA, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 4)};
            case RGB -> this.bitsPerComponent > 8
                    ? new Plane[]{new Plane(w, h, GL11.GL_RGBA, GL11.GL_RGB, GL11.GL_UNSIGNED_SHORT, 6)}
                    : new Plane[]{new Plane(w, h, GL11.GL_RGBA, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 3)};
            case GRAY -> new Plane[]{
                    new Plane(w, h, this.glInternalR, GL11.GL_RED, this.glType, bps)};
            case YUYV, YUYV2 -> new Plane[]{
                    new Plane(w / 2, h, GL11.GL_RGBA, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4)};
            case NV12, NV21 -> new Plane[]{
                    new Plane(w, h, this.glInternalR, GL11.GL_RED, this.glType, bps),
                    new Plane(w / 2, h / 2, this.glInternalRG, GL30.GL_RG, this.glType, 2 * bps)};
            case YUV420P -> threePlanes(w, h, w / 2, h / 2, this.glInternalR, this.glType, bps);
            case YUV422P -> threePlanes(w, h, w / 2, h, this.glInternalR, this.glType, bps);
            case YUV444P -> threePlanes(w, h, w, h, this.glInternalR, this.glType, bps);
            case YUVA420P -> fourPlanes(w, h, w / 2, h / 2, this.glInternalR, this.glType, bps);
            case YUVA422P -> fourPlanes(w, h, w / 2, h, this.glInternalR, this.glType, bps);
            case YUVA444P -> fourPlanes(w, h, w, h, this.glInternalR, this.glType, bps);
            default -> this.bitsPerComponent == 16 // RGBA / GBRA
                    ? new Plane[]{new Plane(w, h, GL11.GL_RGBA, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, 8)}
                    : new Plane[]{new Plane(w, h, GL11.GL_RGBA, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 4)};
        };
    }

    private static Plane[] threePlanes(final int w, final int h, final int cw, final int ch,
                                       final int internal, final int type, final int texelBytes) {
        return new Plane[]{
                new Plane(w, h, internal, GL11.GL_RED, type, texelBytes),
                new Plane(cw, ch, internal, GL11.GL_RED, type, texelBytes),
                new Plane(cw, ch, internal, GL11.GL_RED, type, texelBytes)};
    }

    private static Plane[] fourPlanes(final int w, final int h, final int cw, final int ch,
                                      final int internal, final int type, final int texelBytes) {
        return new Plane[]{
                new Plane(w, h, internal, GL11.GL_RED, type, texelBytes),
                new Plane(cw, ch, internal, GL11.GL_RED, type, texelBytes),
                new Plane(cw, ch, internal, GL11.GL_RED, type, texelBytes),
                new Plane(w, h, internal, GL11.GL_RED, type, texelBytes)};
    }

    // CREATES TARGET TEXTURES LAZILY. DIRECT FORMATS ALIAS PLANE 0 TO managedTexture; PACKED
    // YUYV NEEDS NEAREST FILTERING SO PIXEL PAIRS ARE NOT INTERPOLATED.
    private void ensureTargets() {
        if (!this.convert) {
            if (this.managedTexture == 0) this.managedTexture = this.newTexture();
            this.planes[0].tex = this.managedTexture;
            return;
        }
        final boolean packed = this.pixelFormat == PixelFormat.YUYV || this.pixelFormat == PixelFormat.YUYV2;
        for (final Plane p: this.planes) {
            if (p.tex != 0) continue;
            p.tex = this.newTexture();
            if (packed) {
                this.bindTexture.accept(GL11.GL_TEXTURE_2D, p.tex);
                this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                this.texParameter.accept(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            }
        }
    }

    // ==========================================================================
    // INTERNAL HELPERS
    // ==========================================================================
    private boolean directTextureUploadSupported() {
        return switch (this.pixelFormat) {
            case BGRA, RGBA, RGB -> true;
            default -> false;
        };
    }

    private boolean uploadDirectTexture(final int texture, final ByteBuffer buffer, final int stride) {
        final int glFormat;
        final int glType;
        final int bytesPerTexel;
        switch (this.pixelFormat) {
            case BGRA -> { glFormat = GL12.GL_BGRA; glType = GL12.GL_UNSIGNED_INT_8_8_8_8_REV; bytesPerTexel = 4; }
            case RGB -> {
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
        if (buffer.remaining() < dataSize) {
            LOGGER.warn(IT, "Preloaded frame buffer too small: {} < {}", buffer.remaining(), dataSize);
            return false;
        }

        final long addr = MemoryUtil.memAddress(buffer);
        if (addr == 0L) return false;

        this.bindTexture.accept(GL11.GL_TEXTURE_2D, texture);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 1);
        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, rowLengthPixels);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_SKIP_ROWS, 0);
        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

        GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height, 0, glFormat, glType, addr);
        this.checkGLError("preloaded frame glTexImage2D");

        this.pixelStore.accept(GL11.GL_UNPACK_ROW_LENGTH, 0);
        this.pixelStore.accept(GL11.GL_UNPACK_ALIGNMENT, 4);
        return true;
    }

    private void deleteTextures(final int[] textures) {
        if (textures == null) return;
        for (final int texture: textures) {
            if (texture != 0) this.delTexture.accept(texture);
        }
    }

    private void releaseFrameTextures() {
        final int[] textures = this.frameTextures;
        this.frameTexGen++;
        if (textures.length == 0) return;
        this.deleteTextures(textures);
        this.frameTextures = EMPTY_TEXTURES;
        this.frameTexReady = 0;
        this.activeFrameTexture = -1;
    }

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
        this.bindVertexArray.accept(this.quadVAO);

        this.quadVBO = GL15.glGenBuffers();
        this.bindBuffer.accept(GL15.GL_ARRAY_BUFFER, this.quadVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);

        this.bindVertexArray.accept(0);
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
        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, pboId);
        GL15.nglBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, sizeBytes, dataAddress, GL15.GL_STREAM_DRAW);
        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    private int compileShader(final String vertSrc, final String fragSrc) {
        final int vert = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vert, vertSrc);
        GL20.glCompileShader(vert);
        if (GL20.glGetShaderi(vert, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "Failed to compile vertex shader: {}", GL20.glGetShaderInfoLog(vert, 1024));
            GL20.glDeleteShader(vert);
            return 0;
        }

        final int frag = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(frag, fragSrc);
        GL20.glCompileShader(frag);
        if (GL20.glGetShaderi(frag, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error(IT, "Failed to compile fragment shader: {}", GL20.glGetShaderInfoLog(frag, 1024));
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
            LOGGER.error(IT, "Failed to link shader program: {}", GL20.glGetProgramInfoLog(prog, 1024));
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
        for (final Plane p: this.planes) {
            // PLANE 0 ALIASES managedTexture FOR DIRECT FORMATS — RELEASED SEPARATELY
            if (p.tex != 0 && p.tex != this.managedTexture) this.delTexture.accept(p.tex);
            p.tex = 0;
        }
    }

    private void releasePBOs() {
        if (!this.pboInitialized) return;
        this.bindBuffer.accept(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        final int count = this.activePlanes * NUM_PBOS;
        if (count > 0) {
            final int[] toDelete = new int[count];
            System.arraycopy(this.pbos, 0, toDelete, 0, count);
            GL15.glDeleteBuffers(toDelete);
        }
        Arrays.fill(this.pbos, 0);
        Arrays.fill(this.planeBytes, 0);
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
            LOGGER.warn(IT, "GL error after '{}': {}", op, glErrorName(err));
        }
    }

    private static String glErrorName(final int err) {
        return switch (err) {
            case GL11.GL_INVALID_ENUM -> "INVALID_ENUM";
            case GL11.GL_INVALID_VALUE -> "INVALID_VALUE";
            case GL11.GL_INVALID_OPERATION -> "INVALID_OPERATION";
            case GL11.GL_STACK_OVERFLOW -> "STACK_OVERFLOW";
            case GL11.GL_STACK_UNDERFLOW -> "STACK_UNDERFLOW";
            case GL11.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
            default -> "0x" + Integer.toHexString(err);
        };
    }

    // ==========================================================================
    // RECORDS + PLANE DESCRIPTOR
    // ==========================================================================
    // ONE SUBMITTED FRAME. slot >= 0 MEANS PIXELS LIVE IN THE PERSISTENT RING (bufs IS NULL);
    // OTHERWISE bufs HOLDS THE CALLER'S DIRECT BUFFERS UNTIL THE DRAIN CONSUMES OR REPLACES IT.
    private record Submission(ByteBuffer[] bufs, int[] strides, int[] sizes, long slot, int era) {}

    // SENTINEL FOR "RING FULL — FRAME DROPPED"
    private static final Submission DROPPED = new Submission(null, null, new int[0], -2L, 0);

    // PER-PLANE TEXTURE LAYOUT FOR THE CURRENT FORMAT
    private static final class Plane {
        final int w;
        final int h;
        final int internal;
        final int format;
        final int type;
        final int texelBytes;
        int tex;

        Plane(final int w, final int h, final int internal, final int format, final int type, final int texelBytes) {
            this.w = w;
            this.h = h;
            this.internal = internal;
            this.format = format;
            this.type = type;
            this.texelBytes = texelBytes;
        }

        int rowLen(final int stride) {
            return stride == 0 ? 0 : stride / this.texelBytes;
        }

        int effStride(final int stride) {
            return stride == 0 ? this.w * this.texelBytes : stride;
        }
    }

    // ==========================================================================
    // BUILDER + INTERFACES
    // ==========================================================================
    public static class Builder {
        private final Thread renderThread;
        private final Executor renderThreadEx;
        private IntSupplier genTexture = GL11::glGenTextures;
        private BindConsumer bindTexture = GL11::glBindTexture;
        private TexParamConsumer texParameter = GL11::glTexParameteri;
        private BindConsumer pixelStore = GL11::glPixelStorei;
        private IntConsumer delTexture = GL11::glDeleteTextures;
        private IntConsumer activeTexture = GL13::glActiveTexture;
        private IntConsumer bindVertexArray = GL30::glBindVertexArray;
        private BindConsumer bindFrameBuffer = GL30::glBindFramebuffer;
        private BindConsumer bindBuffer = GL15::glBindBuffer;

        public Builder(final Thread renderThread, final Executor renderThreadEx) {
            this.renderThread = renderThread;
            this.renderThreadEx = renderThreadEx;
        }

        public Builder setGenTexture(final IntSupplier f) { this.genTexture = f; return this; }
        public Builder setBindTexture(final BindConsumer f) { this.bindTexture = f; return this; }
        public Builder setTexParameter(final TexParamConsumer f) { this.texParameter = f; return this; }
        public Builder setPixelStore(final BindConsumer f) { this.pixelStore = f; return this; }
        public Builder setDelTexture(final IntConsumer f) { this.delTexture = f; return this; }
        public Builder setActiveTexture(final IntConsumer f) { this.activeTexture = f; return this; }
        public Builder setBindVertexArray(final IntConsumer f) { this.bindVertexArray = f; return this; }
        public Builder setBindFrameBuffer(final BindConsumer f) { this.bindFrameBuffer = f; return this; }
        public Builder setBindBuffer(final BindConsumer f) { this.bindBuffer = f; return this; }


        public GLEngine build() {
            return new GLEngine(this.renderThread, this.renderThreadEx,
                    this.genTexture, this.bindTexture,
                    this.texParameter, this.pixelStore,
                    this.delTexture, this.activeTexture,
                    this.bindVertexArray, this.bindFrameBuffer,
                    this.bindBuffer);
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
