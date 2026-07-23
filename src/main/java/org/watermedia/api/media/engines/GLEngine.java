package org.watermedia.api.media.engines;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

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
 * <b>The engine is self-contained — no host integration is required.</b> Every piece of GL context
 * state it touches (texture bindings, active unit, sampler bindings, pixel-store parameters,
 * buffer/VAO/framebuffer bindings, program, viewport and draw toggles) is captured before a batch
 * of work and restored to its exact prior value afterwards, so any state cache the host keeps
 * (Minecraft's {@code GlStateManager}, Sodium/Iris trackers, or none at all) stays truthful.
 * Texture names exposed through {@link #texture()} are deleted safely: storage is freed
 * immediately on release, but the name itself is only handed back to the driver once no texture
 * unit binds it anymore — which proves no host binding cache still references it — so a cached
 * binding can never point at a driver-reused name. All engines sharing a render thread drain
 * through one batched task per frame, so the capture/restore cost is constant per frame — not per
 * player — at any scale.
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

    // ONE DRAIN HUB PER RENDER THREAD BATCHES EVERY ENGINE INTO ONE TASK/FRAME IN A SINGLE STATE
    // ENVELOPE. ORPHANS (KEYED BY GLCapabilities IDENTITY, NOT THE OS-REUSABLE GLFW HANDLE) HOLDS
    // EXPOSED TEXTURE NAMES AWAITING PROVABLY-SAFE DELETION SO A DEAD CONTEXT'S NAMES NEVER LEAK.
    private static final Map<Thread, Hub> HUBS = new ConcurrentHashMap<>();
    private static final Map<GLCapabilities, ArrayDeque<Integer>> ORPHANS =
            Collections.synchronizedMap(new WeakHashMap<>());

    // TEXTURE UNITS SCANNED BY THE ORPHAN SWEEP — COVERS MINECRAFT'S 12-UNIT BINDING CACHE
    // WITH MARGIN; HOSTS BINDING BEYOND THIS USE DSA OR UNCACHED PATHS
    private static final int ORPHAN_SCAN_UNITS = 32;

    // THREAD CONTEXT (OPENGL-SPECIFIC)
    private final Thread renderThread;
    private final Executor renderThreadEx;
    private final Hub hub;
    private boolean hubQueued;            // GUARDED BY hub MONITOR
    private boolean hubReleased;          // GUARDS THE HUB REFCOUNT DECREMENT (release() MAY RUN TWICE)
    private volatile boolean released;    // TERMINAL FLAG — STOPS PRODUCERS AND STALE RENDER TASKS

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

    // LATEST-WINS SUBMISSION SLOT (PRODUCER WRITES, RENDER THREAD DRAINS). THE HUB QUEUES AT
    // MOST ONE DRAIN PER ENGINE PER WAVE; A NEWER SUBMISSION REPLACES AN UNDRAINED OLDER ONE.
    private volatile Submission pending;

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

    // FORMAT WHOSE CONVERSION SHADER FAILED TO COMPILE — DECLINED IN supportsFormat SO THE PRODUCER
    // PRE-CONVERTS IT TO BGRA INSTEAD OF RENDERING BLACK THROUGH A MISSING PROGRAM
    private volatile PixelFormat shaderFailFormat;

    private GLEngine(final Thread renderThread, final Executor renderThreadEx) {
        WaterMedia.checkIsClientSideOrThrow(GLEngine.class);
        if (renderThread != null && renderThreadEx == null) {
            throw new IllegalArgumentException("renderThreadEx is required when renderThread is set");
        }
        this.renderThread = renderThread;
        this.renderThreadEx = renderThreadEx;
        // ENGINES SHARING A RENDER THREAD SHARE ITS HUB (FIRST EXECUTOR WINS); THE HUB IS EVICTED WHEN
        // ITS LAST ENGINE RELEASES, SO A HOT-SWAPPED CONTEXT NEITHER LEAKS NOR PINS A STALE EXECUTOR.
        this.hub = renderThread != null ? acquireHub(renderThread, renderThreadEx) : null;

        // VALIDATE THE THREAD PAIR ASYNCHRONOUSLY. A MISMATCH IS LOGGED, NOT THROWN — THROWING RUNS ON
        // THE EXECUTOR AND WOULD CRASH THE HOST RENDER LOOP INSTEAD OF THE MISCONFIGURED CALLER.
        if (renderThreadEx != null && renderThread != null) {
            renderThreadEx.execute(() -> {
                if (Thread.currentThread() != renderThread) {
                    LOGGER.error(IT, "renderThreadEx does not dispatch to renderThread — uploads will corrupt the GL context");
                }
            });
        }
    }

    // REGISTERS THIS ENGINE ON ITS RENDER THREAD'S SHARED HUB, CREATING ONE ON FIRST USE. GUARDED BY
    // THE HUBS MONITOR SO ACQUIRE AND EVICT NEVER RACE.
    private static Hub acquireHub(final Thread thread, final Executor ex) {
        synchronized (HUBS) {
            final Hub hub = HUBS.computeIfAbsent(thread, t -> new Hub(ex));
            hub.engines++;
            return hub;
        }
    }

    // DEREGISTERS THIS ENGINE; THE HUB IS DROPPED ONCE ITS LAST ENGINE LEAVES SO A TORN-DOWN RENDER
    // THREAD LEAKS NEITHER ITS HUB NOR ITS EXECUTOR. IDEMPOTENT — release() MAY RUN TWICE.
    private void releaseHub() {
        if (this.hub == null || this.hubReleased) return;
        this.hubReleased = true;
        synchronized (HUBS) {
            if (--this.hub.engines <= 0) HUBS.remove(this.renderThread, this.hub);
        }
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /**
     * Returns the OpenGL name of the final RGBA texture for the active frame.
     * <p>
     * Direct formats (BGRA, RGBA, RGB) alias plane 0, so their upload lands straight in this
     * texture. Planar and packed formats (YUV 420P/422P/444P, YUVA, NV12/NV21, YUYV/UYVY, GRAY)
     * are <em>not</em> RGBA on their own: their planes are uploaded to intermediate textures and
     * resolved to RGBA through an FBO shader pass ({@link #convertToRGBA()}) during the engine's
     * render cycle. The handle returned here is always that resolved RGBA texture, never a raw
     * luma or chroma plane.
     * <p>
     * <b>Pipeline caution.</b> The handle only holds the current frame once the engine's
     * upload/convert cycle has run on the render thread. Read it fresh on every draw and sample it
     * within (or right after) that cycle: it may be reallocated on a resolution or LOD change, and
     * grabbing it earlier, from an unrelated render stage, or caching it across frames can sample a
     * stale, half-converted, or freshly allocated (undefined contents) texture.
     * @return the RGBA texture name, or 0 if no frame has been produced yet
     */
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
    public boolean supportsFormat(final PixelFormat format) {
        // A FORMAT WHOSE CONVERSION SHADER FAILED TO COMPILE IS DECLINED SO THE PRODUCER RE-ROUTES IT
        // THROUGH THE SCALER TO BGRA INSTEAD OF RENDERING BLACK THROUGH A MISSING PROGRAM.
        if (format == this.shaderFailFormat) return false;
        // GBRA IS DECLINED: THE DEFAULT PLANE PATH UPLOADS IT AS GL_RGBA, WHICH WOULD SAMPLE ITS
        // [G,B,R,A] BYTES AS R,G,B,A (CHANNELS SHUFFLED), SO THE PRODUCER PRE-CONVERTS IT TO BGRA.
        return format != PixelFormat.GBRA;
    }

    @Override
    public boolean uploadFrameTextures(final ByteBuffer[] frames, final int stride) {
        if (frames == null || frames.length == 0 || this.released) return false;
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
        final Env env = Env.save(false);
        try {
            this.beginFrameTextures();
            final int[] textures = new int[frames.length];
            for (int i = 0; i < frames.length; i++) {
                textures[i] = this.newTexture();
                if (!this.uploadDirectTexture(textures[i], frames[i], stride)) {
                    this.orphanTextures(textures);
                    return false;
                }
            }
            this.frameTextures = textures;
            this.frameTexReady = textures.length;
            this.activeFrameTexture = 0;
            return true;
        } finally {
            env.restore();
        }
    }

    // ASYNC FRAME-TEXTURE UPLOAD: ONE RENDER TASK PER FRAME SO A LONG ANIMATION NEVER STALLS A
    // SINGLE RENDER TICK. FRAME 0 IS PUBLISHED IMMEDIATELY; useFrameTexture() CLAMPS TO THE
    // UPLOADED PREFIX UNTIL THE TAIL COMPLETES. genBox INVALIDATES THE BATCH IF THE ENGINE IS
    // RELEASED OR REFORMATTED MID-UPLOAD.
    private void scheduleFrameTextures(final ByteBuffer[] frames, final int stride) {
        final int[] genBox = new int[1];
        final int[][] texBox = new int[1][];
        this.renderThreadEx.execute(() -> {
            if (this.released || this.width <= 0 || this.height <= 0 || !this.directTextureUploadSupported()) {
                genBox[0] = -1;
                if (!this.released) LOGGER.warn(IT, "Frame textures rejected: format {} not direct-uploadable", this.pixelFormat);
                return;
            }
            final Env env = Env.save(false);
            try {
                this.beginFrameTextures();
                genBox[0] = this.frameTexGen;
                final int[] textures = new int[frames.length];
                textures[0] = this.newTexture();
                if (!this.uploadDirectTexture(textures[0], frames[0], stride)) {
                    this.orphanTextures(textures);
                    genBox[0] = -1;
                    return;
                }
                texBox[0] = textures;
                this.frameTextures = textures;
                this.frameTexReady = 1;
                this.activeFrameTexture = 0;
            } finally {
                env.restore();
            }
        });
        for (int i = 1; i < frames.length; i++) {
            final int index = i;
            this.renderThreadEx.execute(() -> {
                if (this.released || genBox[0] != this.frameTexGen || texBox[0] == null) return; // STALE BATCH
                final Env env = Env.save(false);
                try {
                    final int[] textures = texBox[0];
                    textures[index] = this.newTexture();
                    if (this.uploadDirectTexture(textures[index], frames[index], stride)) {
                        this.frameTexReady = index + 1;
                    }
                } finally {
                    env.restore();
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

    /**
     * Resets the entire pipeline and prepares for a new format.
     * Releases plane textures, PBOs, the persistent ring, and recompiles shaders if the pixel
     * format changed. The managed texture is kept but reallocated on next upload if dimensions
     * changed.
     */
    @Override
    public void setVideoFormat(final PixelFormat pixelFormat, final int width, final int height, final int bitsPerComponent) {
        if (this.released) return;
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(() -> this.setVideoFormat(pixelFormat, width, height, bitsPerComponent));
            return;
        }

        // RELEASE FORMAT-SPECIFIC RESOURCES
        final Env env = Env.save(false);
        try {
            this.releaseFrameTextures();
            this.releasePlaneTextures();
            this.releasePBOs();
            this.destroyRing();
        } finally {
            env.restore();
        }

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
                    } else {
                        this.shaderFailFormat = pixelFormat; // DECLINE THIS FORMAT GOING FORWARD (SEE supportsFormat)
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
                    } else {
                        this.shaderFailFormat = pixelFormat; // DECLINE THIS FORMAT GOING FORWARD (SEE supportsFormat)
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
                    } else {
                        this.shaderFailFormat = pixelFormat; // DECLINE THIS FORMAT GOING FORWARD (SEE supportsFormat)
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
                    } else {
                        this.shaderFailFormat = pixelFormat; // DECLINE THIS FORMAT GOING FORWARD (SEE supportsFormat)
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
                    } else {
                        this.shaderFailFormat = pixelFormat; // DECLINE THIS FORMAT GOING FORWARD (SEE supportsFormat)
                    }
                }
            }
            default -> {} // BGRA, RGBA, RGB — NO SHADER NEEDED
        }

        LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc)", pixelFormat, width, height, this.planes.length, bitsPerComponent);
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
    // AND OTHERWISE PARKS THE CLIENT BUFFERS IN THE LATEST-WINS PENDING SLOT. THE SHARED HUB
    // QUEUES AT MOST ONE BATCHED DRAIN TASK PER RENDER THREAD AT ANY TIME.
    private void submit(final ByteBuffer[] bufs, final int[] strides) {
        for (final ByteBuffer buf: bufs) {
            if (buf == null || !buf.isDirect()) return;
        }
        if (this.released) return;
        if (this.renderThread == null || this.renderThread == Thread.currentThread()) {
            // SYNCHRONOUS PATH: ALREADY ON THE RENDER THREAD (OR NO THREAD CONTRACT AT ALL)
            final Env env = Env.save(this.convert);
            try {
                this.renderSubmission(new Submission(bufs, strides, this.sizesOf(bufs), -1L, 0));
            } finally {
                env.restore();
            }
            return;
        }

        // RING CONGESTION (DROPPED) SKIPS THE FRAME BUT STILL SCHEDULES A DRAIN SO FENCES
        // RETIRE AND THE RING RECOVERS.
        final int[] sizes = this.sizesOf(bufs);
        final Submission ring = this.ringWrite(bufs, strides, sizes);
        if (ring != DROPPED) {
            this.pending = ring != null ? ring : new Submission(bufs, strides, sizes, -1L, 0);
        }
        this.hub.schedule(this);
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
    // ONE ENGINE'S SHARE OF A HUB WAVE: CONSUME THE LATEST-WINS SLOT AND RETIRE RING FENCES.
    // THE CALLER OWNS THE STATE ENVELOPE.
    private void drainOne() {
        final Submission s = this.pending;
        this.pending = null;
        if (s != null) this.renderSubmission(s);
        this.retireRing(this.ringConsumed);
    }

    private void renderSubmission(final Submission s) {
        if (this.released || this.width <= 0 || this.height <= 0 || this.planes.length == 0) return;
        if (s.sizes.length != this.planes.length) return;                          // STALE FORMAT
        if (s.slot >= 0L && (s.era != this.ringEra || this.ringAddr == 0L)) return; // STALE RING

        if (this.frameTextures.length > 0) this.releaseFrameTextures();
        this.ensureTargets();

        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);

        if (s.slot >= 0L) {
            // FREE SLOTS THE LATEST-WINS POLICY SKIPPED, THEN CONSUME THIS ONE
            this.retireRing(s.slot);
            this.uploadRing(s);
            this.ringConsumed = s.slot + 1;
        } else {
            this.uploadClient(s);
        }

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
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < this.planes.length; i++) {
                final Plane p = this.planes[i];
                final long needed = (long) p.effStride(s.strides[i]) * p.h;
                final long addr = MemoryUtil.memAddress(s.bufs[i]);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
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
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pbos[i * NUM_PBOS + readIdx]);
                GL11.nglTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, p.w, p.h, p.format, p.type, 0L);
                if (GL_CHECKS) this.checkGLError("plane PBO texSub");
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
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
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.ringBufferId);
        long offset = (s.slot % RING_SLOTS) * this.ringSlotBytes;
        for (int i = 0; i < this.planes.length; i++) {
            final Plane p = this.planes[i];
            final long needed = (long) p.effStride(s.strides[i]) * p.h;
            if (s.sizes[i] < needed) {
                LOGGER.warn(IT, "Ring plane {} too small: {} < {}", i, s.sizes[i], needed);
                break;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, p.rowLen(s.strides[i]));
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
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, id);
        GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, slotBytes * RING_SLOTS, flags);
        final ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0, slotBytes * RING_SLOTS, flags);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
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
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.ringBufferId);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
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
        this.released = true; // STOP PRODUCERS IMMEDIATELY; STALE RENDER TASKS BECOME NO-OPS
        this.releaseHub();    // DROP OUR HUB REFERENCE NOW (THREAD-SAFE, IDEMPOTENT)
        if (this.renderThread != null && this.renderThread != Thread.currentThread()) {
            this.renderThreadEx.execute(this::release);
            return;
        }
        final Env env = Env.save(false);
        try {
            this.destroyRing();
            if (this.fbo != 0) { GL30.glDeleteFramebuffers(this.fbo); this.fbo = 0; }
            if (this.quadVAO != 0) { GL30.glDeleteVertexArrays(this.quadVAO); this.quadVAO = 0; }
            if (this.quadVBO != 0) { GL15.glDeleteBuffers(this.quadVBO); this.quadVBO = 0; }
            this.releaseFrameTextures();
            this.releasePlaneTextures();
            if (this.managedTexture != 0) { this.orphanTexture(this.managedTexture); this.managedTexture = 0; }
            this.managedTextureW = 0;
            this.managedTextureH = 0;
            this.releasePBOs();
            this.releaseShaders();
            this.firstFrame = true;
        } finally {
            env.restore();
        }
    }

    // ==========================================================================
    // FBO CONVERSION — RENDERS PLANE TEXTURES THROUGH SHADER INTO managedTexture
    // ==========================================================================
    // THE HUB/SUBMIT ENVELOPE ALREADY SAVED THE HOST'S PROGRAM/VIEWPORT/FBO/VAO/DRAW TOGGLES
    // AND FORCED A CLEAN DRAW STATE, SO THIS PASS ONLY SETS WHAT IT USES.
    private void convertToRGBA() {
        final int program = switch (this.pixelFormat) {
            case GRAY -> this.shaderGray;
            case NV12, NV21 -> this.shaderNV;
            case YUV420P, YUV422P, YUV444P -> this.shaderYUV3;
            case YUVA420P, YUVA422P, YUVA444P -> this.shaderYUVA;
            case YUYV, YUYV2 -> this.shaderYUYV;
            default -> 0;
        };
        // SHADER COMPILE FAILED — SKIP RATHER THAN BIND PROGRAM 0 (BLACK OUTPUT); supportsFormat NOW DECLINES IT
        if (program == 0) return;
        if (this.managedTexture == 0) this.managedTexture = this.newTexture();
        if (this.fbo == 0) this.fbo = GL30.glGenFramebuffers();
        this.initQuad();

        if (this.managedTextureW != this.width || this.managedTextureH != this.height) {
            // A BOUND UNPACK PBO WOULD TURN THE NULL POINTER INTO OFFSET 0 — UNBIND EXPLICITLY
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.managedTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.checkGLError("managed texture alloc");
            this.managedTextureW = this.width;
            this.managedTextureH = this.height;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.managedTexture, 0);
        GL11.glViewport(0, 0, this.width, this.height);

        switch (this.pixelFormat) {
            case GRAY -> {
                GL20.glUseProgram(this.shaderGray);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformGrayTex, 0);
                GL20.glUniform1f(this.uniformGrayBitScale, this.bitScale);
            }
            case NV12, NV21 -> {
                GL20.glUseProgram(this.shaderNV);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformNVYTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[1].tex);
                GL20.glUniform1i(this.uniformNVUVTex, 1);
                GL20.glUniform1f(this.uniformNVSwap, this.pixelFormat == PixelFormat.NV21 ? 1.0f : 0.0f);
                GL20.glUniform1f(this.uniformNVBitScale, this.bitScale);
            }
            case YUV420P, YUV422P, YUV444P -> {
                GL20.glUseProgram(this.shaderYUV3);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformYUV3YTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[1].tex);
                GL20.glUniform1i(this.uniformYUV3UTex, 1);
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[2].tex);
                GL20.glUniform1i(this.uniformYUV3VTex, 2);
                GL20.glUniform1f(this.uniformYUV3BitScale, this.bitScale);
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                GL20.glUseProgram(this.shaderYUVA);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformYUVAYTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[1].tex);
                GL20.glUniform1i(this.uniformYUVAUTex, 1);
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[2].tex);
                GL20.glUniform1i(this.uniformYUVAVTex, 2);
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[3].tex);
                GL20.glUniform1i(this.uniformYUVAATex, 3);
                GL20.glUniform1f(this.uniformYUVABitScale, this.bitScale);
            }
            case YUYV, YUYV2 -> {
                GL20.glUseProgram(this.shaderYUYV);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.planes[0].tex);
                GL20.glUniform1i(this.uniformYUYVTex, 0);
                GL20.glUniform1f(this.uniformYUYVWidth, (float) this.width);
                GL20.glUniform1f(this.uniformYUYVSwap, this.pixelFormat == PixelFormat.YUYV2 ? 1.0f : 0.0f);
            }
            default -> {}
        }

        GL30.glBindVertexArray(this.quadVAO);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
        if (GL_CHECKS) this.checkGLError("FBO convert quad");
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
                    // CEIL: ODD WIDTHS NEED THE LAST PACKED TEXEL (w/2) — MATCHES VKEngine
                    new Plane((w + 1) / 2, h, GL11.GL_RGBA, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4)};
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
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.tex);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
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

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLengthPixels);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

        GL11.nglTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, this.width, this.height, 0, glFormat, glType, addr);
        this.checkGLError("preloaded frame glTexImage2D");
        return true;
    }

    // ==========================================================================
    // EXPOSED TEXTURE DELETION — DEFERRED UNTIL PROVABLY UNREFERENCED
    // ==========================================================================
    // FREE STORAGE NOW (ZERO-SIZE RESPEC) BUT DEFER DELETING THE NAME: A HOST BINDING CACHE MAY STILL
    // POINT AT IT, AND A REUSED NAME WOULD SILENTLY SKIP A FUTURE REBIND. THE ENVELOPE KEEPS HOST STATE
    // EXACT, SO "UNBOUND ON EVERY UNIT" (SEE sweepOrphans) PROVES THE NAME IS FREE TO DELETE.
    private void orphanTexture(final int texture) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 0, 0, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        ORPHANS.computeIfAbsent(GL.getCapabilities(), c -> new ArrayDeque<>()).addFirst(texture);
    }

    private void orphanTextures(final int[] textures) {
        if (textures == null) return;
        for (final int texture: textures) {
            if (texture != 0) this.orphanTexture(texture);
        }
    }

    // RUNS RIGHT AFTER AN ENVELOPE RESTORE (ACTUAL STATE == HOST STATE): SCANS THE TEXTURE
    // UNITS AND DELETES EVERY ORPHAN NO UNIT STILL BINDS. LEAVES THE ACTIVE UNIT UNTOUCHED.
    private static void sweepOrphans(final int activeUnit) {
        final ArrayDeque<Integer> orphans = ORPHANS.get(GL.getCapabilities());
        if (orphans == null || orphans.isEmpty()) return;
        final int units = Math.min(GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS), ORPHAN_SCAN_UNITS);
        final int[] bound = new int[units];
        for (int u = 0; u < units; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            bound[u] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL13.glActiveTexture(activeUnit);
        orphans.removeIf(id -> {
            for (final int b: bound) {
                if (b == id) return false;
            }
            GL11.glDeleteTextures(id);
            return true;
        });
    }

    private void releaseFrameTextures() {
        final int[] textures = this.frameTextures;
        this.frameTexGen++;
        if (textures.length == 0) return;
        this.orphanTextures(textures);
        this.frameTextures = EMPTY_TEXTURES;
        this.frameTexReady = 0;
        this.activeFrameTexture = -1;
    }

    private int newTexture() {
        final int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
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
            // PLANE 0 ALIASES managedTexture FOR DIRECT FORMATS — RELEASED SEPARATELY.
            // PLANE TEXTURES ARE NEVER EXPOSED TO THE HOST, SO IMMEDIATE DELETION IS SAFE.
            if (p.tex != 0 && p.tex != this.managedTexture) GL11.glDeleteTextures(p.tex);
            p.tex = 0;
        }
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
    // DRAIN HUB + STATE ENVELOPE
    // ==========================================================================
    // BATCHES EVERY ENGINE'S PENDING DRAIN ON ONE RENDER THREAD INTO A SINGLE TASK PER FRAME,
    // WRAPPED IN ONE STATE ENVELOPE — THE glGet CAPTURE COST IS PER FRAME, NOT PER PLAYER.
    private static final class Hub {
        private final Executor ex;
        private final ArrayList<GLEngine> waiting = new ArrayList<>(); // GUARDED BY this
        private boolean queued;                                        // GUARDED BY this
        int engines;                                                   // LIVE ENGINE REFCOUNT, GUARDED BY THE HUBS MONITOR

        Hub(final Executor ex) {
            this.ex = ex;
        }

        void schedule(final GLEngine engine) {
            final boolean fire;
            synchronized (this) {
                if (!engine.hubQueued) {
                    engine.hubQueued = true;
                    this.waiting.add(engine);
                }
                fire = !this.queued;
                this.queued = true;
            }
            if (fire) {
                try {
                    this.ex.execute(this::drainAll);
                } catch (final RuntimeException e) {
                    synchronized (this) { this.queued = false; } // KEEP THE PIPELINE RECOVERABLE
                    throw e;
                }
            }
        }

        private void drainAll() {
            final GLEngine[] batch;
            synchronized (this) {
                this.queued = false;
                batch = this.waiting.toArray(new GLEngine[0]);
                this.waiting.clear();
                for (final GLEngine e: batch) e.hubQueued = false;
            }
            if (batch.length == 0) return;
            boolean convert = false;
            for (final GLEngine e: batch) convert |= e.convert;
            final Env env = Env.save(convert);
            try {
                for (final GLEngine e: batch) {
                    try {
                        e.drainOne();
                    } catch (final RuntimeException ex) {
                        // ISOLATE FAILURES — ONE BROKEN ENGINE MUST NOT STARVE THE REST OF THE WAVE
                        LOGGER.error(IT, "Engine drain failed", ex);
                    }
                }
            } finally {
                env.restore();
            }
        }
    }

    // EXACT CAPTURE/RESTORE OF EVERY PIECE OF GL CONTEXT STATE THE ENGINE TOUCHES. RESTORING
    // THE EXACT PRIOR VALUES KEEPS ANY HOST-SIDE SKIP-IF-EQUAL STATE CACHE (MINECRAFT'S
    // GlStateManager, SODIUM'S TRACKER, ...) TRUTHFUL WITHOUT KNOWING IT EXISTS. THE CONVERT
    // VARIANT ALSO SAVES THE DRAW STATE AND FORCES IT CLEAN FOR THE FULLSCREEN FBO PASS.
    private static final class Env {
        private static final int UNITS = 4; // TEXTURE0..3 — THE UNITS THE CONVERT PASS BINDS

        private final boolean convert;
        private final boolean samplers;
        private final int active;
        private final int activeTex;
        private final int pixelUnpack;
        private final int unpackAlign;
        private final int unpackRow;
        private final int unpackSkipPx;
        private final int unpackSkipRows;
        private final int[] unitTex = new int[UNITS];
        private final int[] unitSampler = new int[UNITS];
        private int program;
        private int readFbo;
        private int drawFbo;
        private int vao;
        private int arrayBuffer;
        private final int[] polyMode = new int[2]; // GL RETURNS {FRONT, BACK}
        private final int[] viewport = new int[4];
        private final int[] colorMask = new int[4];
        private boolean blend;
        private boolean scissor;
        private boolean logicOp;
        private boolean cull;

        static Env save(final boolean convert) {
            return new Env(convert);
        }

        private Env(final boolean convert) {
            final GLCapabilities caps = convert ? GL.getCapabilities() : null;
            this.convert = convert;
            this.samplers = convert && (caps.OpenGL33 || caps.GL_ARB_sampler_objects);
            this.active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            this.activeTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            this.pixelUnpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            this.unpackAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            this.unpackRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            this.unpackSkipPx = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
            this.unpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
            if (!convert) return;

            for (int i = 0; i < UNITS; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                this.unitTex[i] = GL13.GL_TEXTURE0 + i == this.active ? this.activeTex : GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                if (this.samplers) {
                    // SAMPLER OBJECTS OVERRIDE TEXTURE PARAMETERS — NEUTRALIZE THEM FOR THE PASS
                    this.unitSampler[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                    if (this.unitSampler[i] != 0) GL33.glBindSampler(i, 0);
                }
            }
            GL13.glActiveTexture(this.active);
            this.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            this.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            this.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            this.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            this.arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, this.polyMode);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, this.colorMask);
            this.blend = GL11.glIsEnabled(GL11.GL_BLEND);
            this.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            this.logicOp = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
            this.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

            // FORCE A CLEAN DRAW STATE FOR THE FULLSCREEN CONVERT PASS
            if (this.blend) GL11.glDisable(GL11.GL_BLEND);
            if (this.scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (this.logicOp) GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            if (this.cull) GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColorMask(true, true, true, true);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }

        void restore() {
            if (this.convert) {
                for (int i = 0; i < UNITS; i++) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.unitTex[i]);
                    if (this.samplers && this.unitSampler[i] != 0) GL33.glBindSampler(i, this.unitSampler[i]);
                }
                GL20.glUseProgram(this.program);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFbo);
                GL30.glBindVertexArray(this.vao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.arrayBuffer);
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, this.polyMode[0]);
                GL11.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
                GL11.glColorMask(this.colorMask[0] != 0, this.colorMask[1] != 0, this.colorMask[2] != 0, this.colorMask[3] != 0);
                if (this.blend) GL11.glEnable(GL11.GL_BLEND);
                if (this.scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                if (this.logicOp) GL11.glEnable(GL11.GL_COLOR_LOGIC_OP);
                if (this.cull) GL11.glEnable(GL11.GL_CULL_FACE);
            }
            GL13.glActiveTexture(this.active);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.activeTex);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pixelUnpack);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, this.unpackAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, this.unpackRow);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, this.unpackSkipPx);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, this.unpackSkipRows);

            // HOST STATE IS EXACT AGAIN — DELETE ANY ORPHANED NAME NO UNIT STILL BINDS
            sweepOrphans(this.active);
        }
    }

    // ==========================================================================
    // BUILDER
    // ==========================================================================
    /**
     * Builds a {@link GLEngine} bound to a render thread and the executor that dispatches onto it.
     * <p>
     * {@code renderThreadEx} <b>must</b> execute its tasks on {@code renderThread} — the thread that
     * owns the current OpenGL context — and the host must pump it every render frame; that is where
     * every GL call the engine defers is issued. Passing both {@code null} builds a
     * <em>no-thread-contract</em> engine: it makes GL calls synchronously on whatever thread invokes
     * {@code upload}/{@code setVideoFormat}/{@code release}, so the caller must invoke those methods
     * itself on a thread with the context current — never the decode thread. A non-null
     * {@code renderThread} requires a non-null {@code renderThreadEx}.
     */
    public static class Builder {
        private final Thread renderThread;
        private final Executor renderThreadEx;

        /**
         * @param renderThread   thread owning the GL context, or null for the no-thread-contract mode
         * @param renderThreadEx executor that dispatches onto {@code renderThread}, pumped every frame;
         *                       required when {@code renderThread} is non-null
         */
        public Builder(final Thread renderThread, final Executor renderThreadEx) {
            this.renderThread = renderThread;
            this.renderThreadEx = renderThreadEx;
        }

        public GLEngine build() {
            return new GLEngine(this.renderThread, this.renderThreadEx);
        }
    }
}
