package org.watermedia.api.media.engines;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.engines.vk.VKContext;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * Vulkan implementation of {@link GFXEngine}: it uploads decoded video/image planes into an
 * engine-owned RGBA texture and exposes a ready-to-sample {@code VkImageView}.
 * <p>
 * The engine never owns a device — it borrows the consumer's {@link VKContext} (instance, physical
 * device, logical device, a queue and its serialization lock). Every object the engine produces is
 * sampleable by the consumer's own pipeline, and the engine destroys only what it created.
 * <p>
 * <b>Zero-copy ingestion.</b> When the device exposes {@code VK_EXT_external_memory_host} and the
 * decoder's plane buffer is aligned to {@code minImportedHostPointerAlignment}, the engine imports
 * that host pointer straight into a {@code VkBuffer} (no CPU copy of the pixels) and issues a
 * {@code vkCmdCopyBufferToImage} from it. Imported {@code {VkDeviceMemory, VkBuffer}} pairs are
 * cached by host pointer because the producer rotates over a small fixed buffer pool. When import is
 * unavailable or a buffer is unaligned, the engine falls back to a persistent host-visible staging
 * buffer per plane per slot, copies the bytes in, and copies that to the image.
 * <p>
 * <b>Conversion.</b> Passthrough formats (BGRA/RGBA) are copied directly into the managed image.
 * When the device enables {@code samplerYcbcrConversion} and the format qualifies, 8-bit NV12 and
 * planar YUV420/422/444 upload their planes into one multi-planar image and an immutable
 * {@code VkSamplerYcbcrConversion} sampler (BT.709 narrow range) converts in sampler hardware; a
 * trivial compute pass then samples it once into the managed {@code R8G8B8A8_UNORM} storage image,
 * so {@link #texture()} still returns a ready-to-sample RGBA view. Every other format (GRAY, NV21,
 * planar YUVA, packed YUYV/UYVY, 10/12-bit) runs one arithmetic compute pass that samples the plane
 * images and writes the managed image, using the exact BT.709 limited-range coefficients and
 * bit-depth scaling of {@code GLEngine}.
 * <p>
 * <b>Triple buffering &amp; synchronization.</b> Three slots are used round-robin; each owns its plane
 * images, managed image+view, command buffer and fence. Before reusing a slot the engine waits the
 * fence of the frame submitted two calls earlier, which honors the {@code upload} buffer-retention
 * contract for the zero-copy path regardless of producer pool size. Submission and conversion run on
 * the producer thread; the engine and the consumer submit to the same queue serialized by
 * {@link VKContext#queueLock()}, so a single same-queue image barrier (to {@code FRAGMENT_SHADER} /
 * {@code SHADER_READ}) makes each frame visible to the consumer — no cross-thread semaphores.
 * <p>
 * <b>Threading.</b> {@link #upload} and {@link #setVideoFormat} are called from the producer/decode
 * thread; {@link #texture()} from the render thread. {@code texture()} is cheap: it returns the view
 * of the most-recently-submitted slot whose fence has signaled (or the last one it returned), reading
 * published {@code volatile} state and polling fences under the shared queue lock.
 */
public final class VKEngine extends GFXEngine {
    private static final Marker IT = MarkerManager.getMarker(VKEngine.class.getSimpleName());

    // TRIPLE BUFFERING: 1 RECORDING + UP TO 2 IN FLIGHT. ALSO LETS texture() ALWAYS FIND A
    // COMPLETED FRAME WHILE NEWER ONES ARE STILL ON THE GPU.
    private static final int SLOTS = 3;
    private static final int MAX_PLANES = 4;
    private static final int PUSH_BYTES = 16; // { int mode; float bitScale; int width; int height; }
    private static final int YCBCR_PUSH_BYTES = 8; // { int width; int height; }
    private static final long WAIT_TIMEOUT = 5_000_000_000L; // 5s — GUARD AGAINST A WEDGED GPU

    // PROCESS-WIDE CACHES OF THE COMPILED SHADERS (SPIR-V IS DEVICE-INDEPENDENT). SEE compiledSpirv().
    private static volatile byte[] SPIRV;
    private static volatile byte[] YCBCR_SPIRV;

    // COMPUTE CONVERSION MODES (MUST MATCH THE GLSL BELOW)
    private static final int MODE_GRAY = 0;
    private static final int MODE_NV12 = 1;
    private static final int MODE_NV21 = 2;
    private static final int MODE_YUV3 = 3;
    private static final int MODE_YUVA = 4;
    private static final int MODE_YUYV = 5; // PACKED [Y0,U,Y1,V]
    private static final int MODE_UYVY = 6; // PACKED [U,Y0,V,Y1]

    // ONE COMPUTE SHADER FOR EVERY NON-PASSTHROUGH FORMAT. PLANES ARE SAMPLED WITH NORMALIZED
    // COORDS SO THE SAMPLER HANDLES CHROMA UPSAMPLING (PLANAR/NV); PACKED YUV USES texelFetch SO
    // PIXEL PAIRS ARE NOT INTERPOLATED. COEFFICIENTS AND bitScale MIRROR GLEngine EXACTLY
    // (BT.709 STUDIO RANGE).
    private static final String COMPUTE_GLSL = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D plane0;
            layout(binding = 1) uniform sampler2D plane1;
            layout(binding = 2) uniform sampler2D plane2;
            layout(binding = 3) uniform sampler2D plane3;
            layout(binding = 4, rgba8) uniform writeonly image2D outImg;
            layout(push_constant) uniform PC {
                int mode;
                float bitScale;
                int width;
                int height;
            } pc;

            const int MODE_GRAY = 0;
            const int MODE_NV12 = 1;
            const int MODE_NV21 = 2;
            const int MODE_YUV3 = 3;
            const int MODE_YUVA = 4;
            const int MODE_YUYV = 5;
            const int MODE_UYVY = 6;

            void main() {
                ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
                if (gid.x >= pc.width || gid.y >= pc.height) return;
                vec2 uv = (vec2(gid) + 0.5) / vec2(float(pc.width), float(pc.height));
                float bs = pc.bitScale;
                vec4 rgba;
                // COMPUTE SHADERS HAVE NO DERIVATIVES, SO IMPLICIT-LOD texture() IS ILLEGAL HERE.
                // EVERY PLANE IS SINGLE-MIP, SO textureLod(..., 0.0) GIVES THE SAME BILINEAR RESULT
                // AS THE FRAGMENT-STAGE texture() CALLS IN GLEngine (LINEAR SAMPLER, MIP 0).
                if (pc.mode == MODE_GRAY) {
                    float l = textureLod(plane0, uv, 0.0).r * bs;
                    rgba = vec4(l, l, l, 1.0);
                } else if (pc.mode == MODE_NV12 || pc.mode == MODE_NV21) {
                    float Y  = textureLod(plane0, uv, 0.0).r * bs * 1.16438 - 0.07306;
                    vec2 raw = textureLod(plane1, uv, 0.0).rg * bs;
                    float Cb = (pc.mode == MODE_NV12 ? raw.x : raw.y) * 1.13839 - 0.57143;
                    float Cr = (pc.mode == MODE_NV12 ? raw.y : raw.x) * 1.13839 - 0.57143;
                    rgba = vec4(Y + 1.5748 * Cr, Y - 0.1873 * Cb - 0.4681 * Cr, Y + 1.8556 * Cb, 1.0);
                } else if (pc.mode == MODE_YUV3) {
                    float Y  = textureLod(plane0, uv, 0.0).r * bs * 1.16438 - 0.07306;
                    float Cb = textureLod(plane1, uv, 0.0).r * bs * 1.13839 - 0.57143;
                    float Cr = textureLod(plane2, uv, 0.0).r * bs * 1.13839 - 0.57143;
                    rgba = vec4(Y + 1.5748 * Cr, Y - 0.1873 * Cb - 0.4681 * Cr, Y + 1.8556 * Cb, 1.0);
                } else if (pc.mode == MODE_YUVA) {
                    float Y  = textureLod(plane0, uv, 0.0).r * bs * 1.16438 - 0.07306;
                    float Cb = textureLod(plane1, uv, 0.0).r * bs * 1.13839 - 0.57143;
                    float Cr = textureLod(plane2, uv, 0.0).r * bs * 1.13839 - 0.57143;
                    float A  = textureLod(plane3, uv, 0.0).r * bs;
                    rgba = vec4(Y + 1.5748 * Cr, Y - 0.1873 * Cb - 0.4681 * Cr, Y + 1.8556 * Cb, A);
                } else {
                    // PACKED YUV422: ONE RGBA TEXEL COVERS A PIXEL PAIR AT (width/2, height)
                    ivec2 t = ivec2(gid.x >> 1, gid.y);
                    vec4 p = texelFetch(plane0, t, 0);
                    float Y0, Cb, Y1, Cr;
                    if (pc.mode == MODE_YUYV) { Y0 = p.r; Cb = p.g; Y1 = p.b; Cr = p.a; }
                    else { Cb = p.r; Y0 = p.g; Cr = p.b; Y1 = p.a; }
                    float Y = ((gid.x & 1) == 0 ? Y0 : Y1) * 1.16438 - 0.07306;
                    Cb = Cb * 1.13839 - 0.57143;
                    Cr = Cr * 1.13839 - 0.57143;
                    rgba = vec4(Y + 1.5748 * Cr, Y - 0.1873 * Cb - 0.4681 * Cr, Y + 1.8556 * Cb, 1.0);
                }
                imageStore(outImg, gid, rgba);
            }
            """;

    // TRIVIAL SHADER FOR THE SAMPLER-YCBCR PATH: THE IMMUTABLE YCBCR SAMPLER DOES THE ACTUAL
    // YUV->RGB CONVERSION IN HARDWARE, SO THIS PASS JUST SAMPLES ONCE PER PIXEL AND STORES TO THE
    // MANAGED RGBA IMAGE. textureLod (EXPLICIT LOD) IS THE ONLY LEGAL FETCH THROUGH A YCBCR
    // SAMPLER — texelFetch (OpImageFetch) IS FORBIDDEN BY THE SPEC FOR CONVERSION-ENABLED SAMPLERS.
    private static final String YCBCR_GLSL = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D srcYuv;
            layout(binding = 1, rgba8) uniform writeonly image2D outImg;
            layout(push_constant) uniform PC {
                int width;
                int height;
            } pc;

            void main() {
                ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
                if (gid.x >= pc.width || gid.y >= pc.height) return;
                vec2 uv = (vec2(gid) + 0.5) / vec2(float(pc.width), float(pc.height));
                imageStore(outImg, gid, textureLod(srcYuv, uv, 0.0));
            }
            """;

    // CONTEXT (BORROWED — NEVER DESTROYED)
    private final VKContext ctx;
    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final int minAlign; // 0 WHEN HOST IMPORT IS UNAVAILABLE

    // STATIC ENGINE-OWNED OBJECTS (LIVE FOR THE WHOLE ENGINE)
    private long cmdPool;
    private long descPool;
    private long descLayout;
    private long pipeLayout;
    private long pipeline;
    private long shaderModule;
    private long sampler;

    private final Slot[] slots = new Slot[SLOTS];

    // IMPORT CACHE: HOST POINTER -> IMPORTED {VkDeviceMemory, VkBuffer}. PRODUCER-THREAD ONLY.
    private final Map<Long, Imported> importCache = new HashMap<>();

    // FORMAT-DERIVED STATE (BUILT IN setVideoFormat)
    private boolean fmtOk;       // FALSE FOR FORMATS THE ENGINE DOES NOT HANDLE
    private boolean convert;     // TRUE WHEN A COMPUTE PASS PRODUCES THE MANAGED IMAGE
    private int mode;            // COMPUTE MODE WHEN convert
    private int outFormat;       // MANAGED IMAGE FORMAT
    private int outUsage;        // MANAGED IMAGE USAGE
    private int outTexelBytes;   // BYTES PER PIXEL FOR THE PASSTHROUGH COPY
    private float bitScale = 1.0f;
    private int planeCount;
    private final PlaneSpec[] planeSpecs = new PlaneSpec[MAX_PLANES];
    private int formatGen;       // BUMPED ON EVERY FORMAT CHANGE; SLOTS REBUILD WHEN STALE

    // SAMPLER-YCBCR FAST PATH (FORMAT-SPECIFIC — OBJECTS ARE LAZILY BUILT ON FIRST USE AND
    // DESTROYED ON EVERY FORMAT CHANGE AND IN destroyResources)
    private int ycbcrFmt;        // MULTIPLANAR VkFormat, 0 = COMPUTE/PASSTHROUGH PATH
    private int ycbcrLoc;        // CHOSEN VkChromaLocation (X AND Y)
    private int ycbcrFilter;     // CHOSEN VkFilter FOR min/mag/chroma (ALWAYS THE SAME THREE)
    private long ycbcrConv;      // VkSamplerYcbcrConversion
    private long ycbcrSamp;      // IMMUTABLE SAMPLER WRAPPING THE CONVERSION
    private long ycbcrLayout;    // SET LAYOUT: IMMUTABLE COMBINED SAMPLER + STORAGE IMAGE
    private long ycbcrPipeLayout;
    private long ycbcrPipeline;
    private long ycbcrPool;      // SLOTS SETS FOR THE YCBCR LAYOUT
    private final Map<Integer, Integer> ycbcrFeats = new HashMap<>(); // vkFormat -> optimalTilingFeatures (CACHED QUERY)

    // SUBMISSION BOOKKEEPING (PRODUCER-THREAD ONLY)
    private long head;           // NUMBER OF FRAMES SUBMITTED
    private long submitSeq;      // MONOTONIC SEQUENCE STAMPED ONTO EACH SLOT ON SUBMIT

    // PUBLISHED TO texture() (RENDER THREAD)
    private volatile long lastView;
    private volatile long lastSeq;
    private volatile boolean released;

    private VKEngine(final VKContext ctx) {
        WaterMedia.checkIsClientSideOrThrow(VKEngine.class);
        if (ctx == null) throw new IllegalArgumentException("VKContext must be non-null");
        this.ctx = ctx;
        this.device = ctx.device();
        this.memProps = ctx.memoryProperties();
        this.minAlign = ctx.hostImportSupported() ? (int) ctx.minImportedHostPointerAlignment() : 0;
        this.init();
        LOGGER.info(IT, "VKEngine initialized (hostImport={}, minAlign={})", ctx.hostImportSupported(), this.minAlign);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    @Override
    public long texture() {
        if (this.released) return 0L;
        // FENCE STATUS IS A FENCE READER; vkResetFences/vkQueueSubmit ON THE SAME FENCE ARE
        // WRITERS. SERIALIZE THEM THROUGH THE SHARED QUEUE LOCK SO THE POLL IS RACE-FREE.
        // THE lastSeq/lastView READ AND WRITE-BACK STAY INSIDE THE LOCK TOO: setVideoFormat ZEROES
        // THEM UNDER THIS LOCK BEFORE DESTROYING THE VIEWS, SO A STALE PAIR CAN NEVER BE
        // REPUBLISHED AFTER THE VIEW IT POINTS TO IS GONE.
        synchronized (this.ctx.queueLock()) {
            long bestSeq = this.lastSeq;
            long bestView = this.lastView;
            for (final Slot s: this.slots) {
                final long seq = s.seq;
                if (seq > bestSeq && s.submitted && vkGetFenceStatus(this.device, s.fence) == VK_SUCCESS) {
                    bestSeq = seq;
                    bestView = s.view;
                }
            }
            this.lastSeq = bestSeq;
            this.lastView = bestView;
            return bestView;
        }
    }

    @Override
    public int requiredBufferAlignment() {
        return this.minAlign;
    }

    @Override
    public boolean supportsFormat(final PixelFormat format) {
        return switch (format) {
            // RGB (3-BYTE, NO ALPHA) AND GBRA ARE DECLINED: 3-CHANNEL OPTIMAL-TILING SUPPORT IS
            // OPTIONAL IN VULKAN, SO THE PRODUCER PRE-CONVERTS THEM TO BGRA UPSTREAM (ROBUST).
            case RGB, GBRA -> false;
            default -> true;
        };
    }

    /**
     * Reconfigures the engine for a new format, fully resetting per-format GPU state.
     * <p>
     * All engine work is drained, the publish state and the managed/plane images of every slot are
     * released (rebuilt lazily on the next upload), and the host-import cache is cleared so no stale
     * host pointer survives a resolution change. The static command pool, pipeline, sampler and
     * fences are kept.
     */
    @Override
    public void setVideoFormat(final PixelFormat pixelFormat, final int width, final int height, final int bitsPerComponent) {
        if (this.released) return;
        // DRAIN ENGINE WORK SO PER-FORMAT RESOURCES CAN BE DESTROYED SAFELY.
        this.waitAllIdle();
        synchronized (this.ctx.queueLock()) {
            for (final Slot s: this.slots) {
                s.seq = 0L;
                s.submitted = false;
            }
            this.lastSeq = 0L;
            this.lastView = 0L;
        }
        for (final Slot s: this.slots) {
            // THE MANAGED IMAGE/VIEW IS SAMPLED BY THE CONSUMER: AN IN-FLIGHT UI FRAME (UP TO
            // MAX_FRAMES_IN_FLIGHT) MAY STILL REFERENCE THE VIEW texture() RETURNED, AND waitAllIdle
            // DRAINED ONLY THE ENGINE'S OWN FENCES. DEFER ITS DESTRUCTION LIKE release() DOES;
            // PLANE/STAGING RESOURCES ARE ENGINE-PRIVATE AND ALREADY DRAINED, SO THEY DIE NOW.
            final long view = s.view, img = s.outImg, mem = s.outMem;
            if (view != 0L || img != 0L || mem != 0L) {
                s.view = 0L;
                s.outImg = 0L;
                s.outMem = 0L;
                this.ctx.retire(() -> {
                    if (view != 0L) vkDestroyImageView(this.device, view, null);
                    if (img != 0L) vkDestroyImage(this.device, img, null);
                    if (mem != 0L) vkFreeMemory(this.device, mem, null);
                });
            }
            this.destroySlotFormatRes(s);
            s.gen = -1L;
        }
        this.destroyImportCache();
        // THE YCBCR OBJECTS ARE FORMAT-SPECIFIC AND ENGINE-PRIVATE (NEVER REFERENCED BY CONSUMER
        // FRAMES — THE CONSUMER ONLY SAMPLES THE RGBA VIEW), SO AFTER THE waitAllIdle DRAIN ABOVE
        // THEY DIE SYNCHRONOUSLY AND ARE LAZILY REBUILT IF THE NEW FORMAT USES THE YCBCR PATH.
        this.destroyYcbcrRes();

        super.setVideoFormat(pixelFormat, width, height, bitsPerComponent);
        this.buildFormat();
        this.formatGen++;
        this.head = 0L;
        if (this.ycbcrFmt != 0) {
            LOGGER.info(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> YCbCr sampler path (vkFormat={}, chromaLoc={}, filter={})",
                    pixelFormat, width, height, this.planeCount, bitsPerComponent,
                    this.ycbcrFmt, this.ycbcrLoc == VK_CHROMA_LOCATION_MIDPOINT ? "midpoint" : "cosited-even",
                    this.ycbcrFilter == VK_FILTER_LINEAR ? "linear" : "nearest");
        } else {
            LOGGER.info(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> {} path",
                    pixelFormat, width, height, this.planeCount, bitsPerComponent,
                    this.convert ? "compute" : "passthrough");
        }
    }

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

    @Override
    public void releaseBuffer(final ByteBuffer buffer) {
        if (this.released || buffer == null || !buffer.isDirect()) return;
        // THE CACHE IS KEYED BY THE BUFFER'S BASE ADDRESS (POSITION-0 IMPORTS ONLY).
        final Imported imp = this.importCache.remove(memAddress(buffer) - buffer.position());
        if (imp == null) return;
        // AN IN-FLIGHT UPLOAD COMMAND BUFFER MAY STILL READ THE IMPORT — DRAIN THE ENGINE'S OWN
        // WORK FIRST, THEN DESTROY IT WHILE THE HOST MEMORY IS STILL VALID.
        this.waitAllIdle();
        vkDestroyBuffer(this.device, imp.buffer, null);
        vkFreeMemory(this.device, imp.memory, null);
    }

    @Override
    public void release() {
        if (this.released) return;
        this.released = true; // texture()/upload() BECOME NO-OPS
        this.waitAllIdle();   // THE ENGINE'S OWN UPLOAD/COMPUTE WORK IS DONE BEFORE WE HAND OFF
        // DESTROY THE HOST IMPORTS NOW (SYNCHRONOUSLY): THEY WRAP THE PRODUCER'S PLANE BUFFERS, WHICH THE
        // PRODUCER FREES RIGHT AFTER release() RETURNS. FREEING AN IMPORTED VkDeviceMemory WHOSE HOST
        // POINTER WAS ALREADY FREED CRASHES THE DRIVER. waitAllIdle GUARANTEES NO UPLOAD STILL READS THEM.
        this.destroyImportCache();
        // DEFER THE REST: A CONSUMER RENDER FRAME IN FLIGHT MAY STILL SAMPLE THE LAST texture() VIEW. THE
        // BACKEND RUNS THIS ONCE ITS IN-FLIGHT FRAMES RETIRE, SO NO COMMAND BUFFER REFERENCES A DESTROYED VIEW.
        this.ctx.retire(this::destroyResources);
    }

    // RUNS ON THE CONSUMER'S RENDER THREAD AFTER ITS IN-FLIGHT FRAMES RETIRE (SEE VKContext#retire).
    private void destroyResources() {
        for (final Slot s: this.slots) {
            this.destroySlotFormatRes(s);
            if (s.fence != 0L) { vkDestroyFence(this.device, s.fence, null); s.fence = 0L; }
        }
        this.destroyYcbcrRes();

        if (this.pipeline != 0L) { vkDestroyPipeline(this.device, this.pipeline, null); this.pipeline = 0L; }
        if (this.shaderModule != 0L) { vkDestroyShaderModule(this.device, this.shaderModule, null); this.shaderModule = 0L; }
        if (this.pipeLayout != 0L) { vkDestroyPipelineLayout(this.device, this.pipeLayout, null); this.pipeLayout = 0L; }
        if (this.descLayout != 0L) { vkDestroyDescriptorSetLayout(this.device, this.descLayout, null); this.descLayout = 0L; }
        if (this.sampler != 0L) { vkDestroySampler(this.device, this.sampler, null); this.sampler = 0L; }
        if (this.descPool != 0L) { vkDestroyDescriptorPool(this.device, this.descPool, null); this.descPool = 0L; } // FREES SETS
        if (this.cmdPool != 0L) { vkDestroyCommandPool(this.device, this.cmdPool, null); this.cmdPool = 0L; }       // FREES CMD BUFFERS
        LOGGER.info(IT, "VKEngine released");
    }

    // ==========================================================================
    // SUBMISSION (PRODUCER THREAD)
    // ==========================================================================
    private void submit(final ByteBuffer[] bufs, final int[] strides) {
        if (this.released || !this.fmtOk || this.width <= 0 || this.height <= 0) return;
        final int sources = this.convert ? this.planeCount : 1;
        if (bufs.length != sources) return; // STALE FORMAT / WRONG OVERLOAD
        for (final ByteBuffer b: bufs) {
            if (b == null || !b.isDirect()) return;
        }

        final int cur = (int) (this.head % SLOTS);
        final Slot slot = this.slots[cur];

        try (MemoryStack stack = stackPush()) {
            // RETENTION: BLOCK UNTIL THE FRAME SUBMITTED TWO CALLS AGO HAS FINISHED READING THE
            // PRODUCER'S BUFFERS. (head + 1) % SLOTS IS THE SLOT OF FRAME head-2. WAITING IT HERE
            // GUARANTEES THAT BY THE TIME upload(N+2) RUNS, FRAME N IS COMPLETE — EXACTLY THE
            // GFXEngine "FINISH READING BEFORE THE SECOND SUBSEQUENT UPLOAD" CONTRACT, WHICH MATTERS
            // FOR THE ZERO-COPY PATH WHERE THE GPU READS THE DECODER'S MEMORY DIRECTLY.
            final Slot twoAgo = this.slots[(int) ((this.head + 1) % SLOTS)];
            if (this.head >= 2 && twoAgo.submitted) {
                vkWaitForFences(this.device, twoAgo.fence, true, WAIT_TIMEOUT);
            }
            // THE SLOT WE REUSE LAST HELD FRAME head-SLOTS; ITS WORK MUST BE DONE BEFORE WE RESET
            // ITS COMMAND BUFFER AND OVERWRITE ITS IMAGES.
            if (slot.submitted) {
                vkWaitForFences(this.device, slot.fence, true, WAIT_TIMEOUT);
            }
            synchronized (this.ctx.queueLock()) {
                vkResetFences(this.device, slot.fence);
            }
            slot.submitted = false; // texture() SKIPS THIS SLOT WHILE WE RE-RECORD IT

            // LAZILY (RE)CREATE PLANE/OUTPUT IMAGES FOR THE CURRENT FORMAT (NO-OP IN STEADY STATE).
            this.ensureSlot(slot);

            // RESOLVE EACH SOURCE TO A VkBuffer BEFORE RECORDING. THE STAGING FALLBACK COPIES HOST
            // BYTES NOW (SYNCHRONOUSLY), SO THE PRODUCER BUFFER IS FULLY READ ON RETURN; THE IMPORT
            // PATH DEFERS THE READ TO THE GPU (COVERED BY THE FENCE WAITS ABOVE).
            final long[] src = new long[sources];
            final int[] rowLen = new int[sources];
            for (int i = 0; i < sources; i++) {
                final int texelBytes = this.convert ? this.planeSpecs[i].texelBytes : this.outTexelBytes;
                final int w = this.convert ? this.planeSpecs[i].w : this.width;
                final int h = this.convert ? this.planeSpecs[i].h : this.height;
                final int effStride = strides[i] == 0 ? w * texelBytes : strides[i];
                final long needed = (long) effStride * h;
                if (bufs[i].remaining() < needed) {
                    LOGGER.warn(IT, "Plane {} buffer too small: {} < {}", i, bufs[i].remaining(), needed);
                    return;
                }
                rowLen[i] = strides[i] == 0 ? 0 : strides[i] / texelBytes;

                // IMPORT ONLY POSITION-0 BUFFERS WHOSE CAPACITY IS A MULTIPLE OF minAlign:
                // memAddress() INCLUDES position AND THE IMPORTED SIZE IS ROUNDED UP TO minAlign,
                // SO ANYTHING ELSE COULD IMPORT PAST THE END OF THE UNDERLYING ALLOCATION.
                final long ptr = memAddress(bufs[i]);
                long buffer = 0L;
                if (this.minAlign > 0 && bufs[i].position() == 0 && (ptr % this.minAlign) == 0L
                        && (bufs[i].capacity() % this.minAlign) == 0) {
                    buffer = this.importBuffer(ptr, bufs[i].capacity());
                }
                if (buffer == 0L) {
                    buffer = this.stageInto(slot, i, bufs[i], needed);
                }
                src[i] = buffer;
            }

            // RECORD
            vkResetCommandBuffer(slot.cmd, 0);
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(slot.cmd, begin);

            if (this.ycbcrFmt != 0) {
                // SAMPLER-YCBCR PATH: EVERY PLANE LIVES IN ONE MULTIPLANAR IMAGE. WHOLE-IMAGE LAYOUT
                // BARRIERS MUST USE THE COLOR ASPECT — IT IS THE ONLY VALID ASPECT (AND COVERS ALL
                // PLANES) FOR A NON-DISJOINT MULTIPLANAR IMAGE.
                this.barrier(stack, slot.cmd, slot.planeImg[0],
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                for (int i = 0; i < this.planeCount; i++) {
                    // EACH SOURCE PLANE TARGETS ITS OWN PLANE ASPECT; EXTENT AND ROW LENGTH ARE IN
                    // PLANE TEXELS (NV12 CHROMA TEXEL = 2 BYTES, SO rowLen = stride / 2).
                    final PlaneSpec spec = this.planeSpecs[i];
                    this.copyToImage(stack, slot.cmd, src[i], slot.planeImg[0], spec.w, spec.h,
                            rowLen[i], VK_IMAGE_ASPECT_PLANE_0_BIT << i);
                }
                this.barrier(stack, slot.cmd, slot.planeImg[0],
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
                // srcStage FRAGMENT_SHADER: SAME WRITE-AFTER-READ REASONING AS THE COMPUTE PATH BELOW.
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.ycbcrPipeline);
                vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.ycbcrPipeLayout, 0,
                        stack.longs(slot.ycbcrSet), null);
                final ByteBuffer push = stack.malloc(YCBCR_PUSH_BYTES);
                push.putInt(0, this.width);
                push.putInt(4, this.height);
                vkCmdPushConstants(slot.cmd, this.ycbcrPipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                vkCmdDispatch(slot.cmd, (this.width + 7) / 8, (this.height + 7) / 8, 1);
                // HAND THE RESULT TO THE CONSUMER'S FRAGMENT SAMPLING (SAME-QUEUE BARRIER).
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            } else if (this.convert) {
                this.ensureComputePipeline(); // LAZY: THE FIRST CONVERSION FRAME COMPILES (CACHED) + BUILDS THE PIPELINE
                for (int i = 0; i < this.planeCount; i++) {
                    final PlaneSpec spec = this.planeSpecs[i];
                    this.barrier(stack, slot.cmd, slot.planeImg[i],
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                    this.copyToImage(stack, slot.cmd, src[i], slot.planeImg[i], spec.w, spec.h,
                            rowLen[i], VK_IMAGE_ASPECT_COLOR_BIT);
                    this.barrier(stack, slot.cmd, slot.planeImg[i],
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
                }
                // STORAGE IMAGE MUST BE IN GENERAL WHILE THE COMPUTE PASS WRITES IT. srcStage IS
                // FRAGMENT_SHADER: A PRIOR CONSUMER FRAME ON THIS SHARED QUEUE MAY STILL SAMPLE THIS
                // SLOT'S VIEW (texture() FALLBACK), AND THE ENGINE FENCE DOES NOT COVER IT — THE
                // EXECUTION DEPENDENCY ALONE RESOLVES THE WRITE-AFTER-READ HAZARD (srcAccess 0).
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
                vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeLayout, 0,
                        stack.longs(slot.descSet), null);
                final ByteBuffer push = stack.malloc(PUSH_BYTES);
                push.putInt(0, this.mode);
                push.putFloat(4, this.bitScale);
                push.putInt(8, this.width);
                push.putInt(12, this.height);
                vkCmdPushConstants(slot.cmd, this.pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                vkCmdDispatch(slot.cmd, (this.width + 7) / 8, (this.height + 7) / 8, 1);
                // HAND THE RESULT TO THE CONSUMER'S FRAGMENT SAMPLING (SAME-QUEUE BARRIER).
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            } else {
                // srcStage FRAGMENT_SHADER FOR THE SAME WRITE-AFTER-READ REASON AS THE CONVERT PATH.
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                this.copyToImage(stack, slot.cmd, src[0], slot.outImg, this.width, this.height,
                        rowLen[0], VK_IMAGE_ASPECT_COLOR_BIT);
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            vkEndCommandBuffer(slot.cmd);

            // SUBMIT UNDER THE SHARED QUEUE LOCK SIGNALING THIS SLOT'S FENCE.
            final VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(slot.cmd));
            synchronized (this.ctx.queueLock()) {
                final int r = vkQueueSubmit(this.ctx.queue(), si, slot.fence);
                if (r != VK_SUCCESS) {
                    LOGGER.error(IT, "vkQueueSubmit failed: {}", r);
                    return;
                }
            }
            // PUBLISH: seq FIRST, THEN submitted, SO texture() READING submitted=true SEES A VALID seq.
            slot.seq = ++this.submitSeq;
            slot.submitted = true;
            this.head++;
        }
    }

    // ==========================================================================
    // HOST-POINTER IMPORT (ZERO-COPY) — CACHED BY HOST POINTER
    // ==========================================================================
    // IMPORTS THE DECODER'S HOST MEMORY AS A TRANSFER-SOURCE VkBuffer SO THE GPU READS IT IN PLACE.
    // RETURNS THE VkBuffer, OR 0 IF THE IMPORT IS REJECTED (CALLER FALLS BACK TO STAGING).
    private long importBuffer(final long ptr, final long size) {
        final Imported cached = this.importCache.get(ptr);
        if (cached != null && cached.size >= size) return cached.buffer;
        if (cached != null) { // A LARGER REGION IS NEEDED — DROP THE STALE IMPORT FIRST
            vkDestroyBuffer(this.device, cached.buffer, null);
            vkFreeMemory(this.device, cached.memory, null);
            this.importCache.remove(ptr);
        }
        try (MemoryStack stack = stackPush()) {
            // WHICH MEMORY TYPES MAY THIS HOST POINTER BE IMPORTED AS.
            final VkMemoryHostPointerPropertiesEXT props = VkMemoryHostPointerPropertiesEXT.calloc(stack).sType$Default();
            if (vkGetMemoryHostPointerPropertiesEXT(this.device,
                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, ptr, props) != VK_SUCCESS) {
                return 0L;
            }
            int type = this.memType(props.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (type < 0) type = this.memType(props.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            if (type < 0) type = this.memType(props.memoryTypeBits(), 0);
            if (type < 0) return 0L;

            // THE IMPORTED SIZE MUST BE A MULTIPLE OF minAlign; THE PRODUCER ALREADY ROUNDS ITS
            // ALLOCATIONS UP, SO capacity IS A MULTIPLE OF minAlign AND THE REGION IS FULLY VALID.
            final long importSize = ((size + this.minAlign - 1) / this.minAlign) * this.minAlign;

            final VkExternalMemoryBufferCreateInfo ext = VkExternalMemoryBufferCreateInfo.calloc(stack)
                    .sType$Default().handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT);
            final VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .pNext(ext).size(importSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            final LongBuffer pBuf = stack.mallocLong(1);
            if (vkCreateBuffer(this.device, bci, null, pBuf) != VK_SUCCESS) return 0L;
            final long buffer = pBuf.get(0);

            final VkImportMemoryHostPointerInfoEXT imp = VkImportMemoryHostPointerInfoEXT.calloc(stack)
                    .sType$Default().handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT)
                    .pHostPointer(ptr);
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .pNext(imp).allocationSize(importSize).memoryTypeIndex(type);
            final LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(this.device, mai, null, pMem) != VK_SUCCESS) {
                vkDestroyBuffer(this.device, buffer, null);
                return 0L;
            }
            final long memory = pMem.get(0);
            if (vkBindBufferMemory(this.device, buffer, memory, 0) != VK_SUCCESS) {
                vkFreeMemory(this.device, memory, null);
                vkDestroyBuffer(this.device, buffer, null);
                return 0L;
            }
            this.importCache.put(ptr, new Imported(memory, buffer, importSize));
            return buffer;
        }
    }

    // ==========================================================================
    // HOST-VISIBLE STAGING FALLBACK — PERSISTENT PER PLANE PER SLOT
    // ==========================================================================
    // COPIES THE PLANE BYTES INTO THIS SLOT'S MAPPED STAGING BUFFER AND RETURNS IT AS THE COPY
    // SOURCE. THE BUFFER GROWS ON DEMAND AND IS MAPPED ONCE (HOST_COHERENT — NO EXPLICIT FLUSH).
    private long stageInto(final Slot slot, final int i, final ByteBuffer src, final long needed) {
        Stage st = slot.stage[i];
        if (st == null || st.size < needed) {
            if (st != null) {
                vkUnmapMemory(this.device, st.memory);
                vkDestroyBuffer(this.device, st.buffer, null);
                vkFreeMemory(this.device, st.memory, null);
            }
            try (MemoryStack stack = stackPush()) {
                final VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(needed).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                final LongBuffer pBuf = stack.mallocLong(1);
                check(vkCreateBuffer(this.device, bci, null, pBuf), "create staging buffer");
                final long buffer = pBuf.get(0);

                final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(this.device, buffer, req);
                int type = this.memType(req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                if (type < 0) type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
                if (type < 0) throw new IllegalStateException("VKEngine: no host-visible memory type for staging");

                final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(req.size()).memoryTypeIndex(type);
                final LongBuffer pMem = stack.mallocLong(1);
                check(vkAllocateMemory(this.device, mai, null, pMem), "alloc staging memory");
                final long memory = pMem.get(0);
                check(vkBindBufferMemory(this.device, buffer, memory, 0), "bind staging memory");

                final PointerBuffer pp = stack.mallocPointer(1);
                check(vkMapMemory(this.device, memory, 0, req.size(), 0, pp), "map staging memory");
                st = new Stage(buffer, memory, pp.get(0), needed);
                slot.stage[i] = st;
            }
        }
        memCopy(memAddress(src), st.mapped, needed);
        return st.buffer;
    }

    // ==========================================================================
    // PER-SLOT FORMAT RESOURCES
    // ==========================================================================
    // (RE)BUILDS THIS SLOT'S MANAGED IMAGE, PLANE IMAGES AND DESCRIPTOR SET FOR THE CURRENT FORMAT.
    // SAFE WITHOUT EXTRA SYNC: A FORMAT CHANGE DRAINS ALL FENCES, SO THE SLOT IS IDLE HERE.
    private void ensureSlot(final Slot slot) {
        if (slot.gen == this.formatGen) return;
        this.destroySlotFormatRes(slot);
        try (MemoryStack stack = stackPush()) {
            final Img out = this.image(stack, this.outFormat, this.width, this.height, this.outUsage, 0L);
            slot.outImg = out.image;
            slot.outMem = out.memory;
            slot.view = out.view;
            if (this.ycbcrFmt != 0) {
                this.ensureYcbcr(); // CONVERSION/SAMPLER/PIPELINE/SETS MUST EXIST BEFORE THE VIEW CHAINS THEM
                // ONE MULTIPLANAR IMAGE HOLDS EVERY PLANE; ITS SAMPLED VIEW CHAINS THE SAME CONVERSION
                // THE IMMUTABLE SAMPLER WAS CREATED WITH (REQUIRED TO MATCH).
                final Img mp = this.image(stack, this.ycbcrFmt, this.width, this.height,
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, this.ycbcrConv);
                slot.planeImg[0] = mp.image;
                slot.planeMem[0] = mp.memory;
                slot.planeView[0] = mp.view;
                // BINDING 0 = MULTIPLANAR VIEW (SAMPLER FIELD IGNORED — THE LAYOUT'S IMMUTABLE SAMPLER
                // WINS), BINDING 1 = THIS SLOT'S RGBA STORAGE VIEW.
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                final VkDescriptorImageInfo.Buffer srcInfo = VkDescriptorImageInfo.calloc(1, stack);
                srcInfo.get(0).sampler(this.ycbcrSamp).imageView(mp.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                writes.get(0).sType$Default().dstSet(slot.ycbcrSet).dstBinding(0).dstArrayElement(0)
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(srcInfo);
                final VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
                outInfo.get(0).imageView(out.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                writes.get(1).sType$Default().dstSet(slot.ycbcrSet).dstBinding(1).dstArrayElement(0)
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outInfo);
                vkUpdateDescriptorSets(this.device, writes, null);
            } else if (this.convert) {
                for (int i = 0; i < this.planeCount; i++) {
                    final PlaneSpec spec = this.planeSpecs[i];
                    final Img p = this.image(stack, spec.format, spec.w, spec.h,
                            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 0L);
                    slot.planeImg[i] = p.image;
                    slot.planeMem[i] = p.memory;
                    slot.planeView[i] = p.view;
                }
                this.updateDescriptorSet(stack, slot);
            }
        }
        slot.gen = this.formatGen;
    }

    private void updateDescriptorSet(final MemoryStack stack, final Slot slot) {
        final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
        for (int j = 0; j < MAX_PLANES; j++) {
            // UNUSED PLANE SLOTS BIND PLANE 0 (ALWAYS PRESENT FOR CONVERT) — A VALID VIEW THE SHADER NEVER READS
            final long view = j < this.planeCount ? slot.planeView[j] : slot.planeView[0];
            final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).sampler(this.sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            writes.get(j).sType$Default().dstSet(slot.descSet).dstBinding(j).dstArrayElement(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
        }
        final VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
        outInfo.get(0).imageView(slot.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        writes.get(4).sType$Default().dstSet(slot.descSet).dstBinding(4).dstArrayElement(0)
                .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outInfo);
        vkUpdateDescriptorSets(this.device, writes, null);
    }

    private void destroySlotFormatRes(final Slot s) {
        for (int j = 0; j < MAX_PLANES; j++) {
            if (s.planeView[j] != 0L) { vkDestroyImageView(this.device, s.planeView[j], null); s.planeView[j] = 0L; }
            if (s.planeImg[j] != 0L) { vkDestroyImage(this.device, s.planeImg[j], null); s.planeImg[j] = 0L; }
            if (s.planeMem[j] != 0L) { vkFreeMemory(this.device, s.planeMem[j], null); s.planeMem[j] = 0L; }
            if (s.stage[j] != null) {
                vkUnmapMemory(this.device, s.stage[j].memory);
                vkDestroyBuffer(this.device, s.stage[j].buffer, null);
                vkFreeMemory(this.device, s.stage[j].memory, null);
                s.stage[j] = null;
            }
        }
        if (s.view != 0L) { vkDestroyImageView(this.device, s.view, null); s.view = 0L; }
        if (s.outImg != 0L) { vkDestroyImage(this.device, s.outImg, null); s.outImg = 0L; }
        if (s.outMem != 0L) { vkFreeMemory(this.device, s.outMem, null); s.outMem = 0L; }
    }

    private void destroyImportCache() {
        for (final Imported imp: this.importCache.values()) {
            vkDestroyBuffer(this.device, imp.buffer, null);
            vkFreeMemory(this.device, imp.memory, null);
        }
        this.importCache.clear();
    }

    private void waitAllIdle() {
        for (final Slot s: this.slots) {
            if (s.fence != 0L && s.submitted) {
                vkWaitForFences(this.device, s.fence, true, WAIT_TIMEOUT);
            }
        }
    }

    // ==========================================================================
    // RECORDING HELPERS
    // ==========================================================================
    // aspect IS COLOR FOR SINGLE-PLANE IMAGES AND PLANE_0/1/2 WHEN COPYING INTO A MULTIPLANAR IMAGE.
    private void copyToImage(final MemoryStack stack, final VkCommandBuffer cmd, final long srcBuffer,
                             final long image, final int w, final int h, final int rowLenTexels, final int aspect) {
        final VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).bufferOffset(0).bufferRowLength(rowLenTexels).bufferImageHeight(0);
        region.get(0).imageSubresource(s -> s.aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1));
        region.get(0).imageOffset(o -> o.x(0).y(0).z(0));
        region.get(0).imageExtent(e -> e.width(w).height(h).depth(1));
        vkCmdCopyBufferToImage(cmd, srcBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private void barrier(final MemoryStack stack, final VkCommandBuffer cmd, final long image,
                         final int oldLayout, final int newLayout,
                         final int srcStage, final int srcAccess, final int dstStage, final int dstAccess) {
        final VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default()
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        b.get(0).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
    }

    // CREATES AN OPTIMAL-TILING 2D IMAGE WITH A DEVICE-LOCAL ALLOCATION AND A COLOR VIEW. WHEN conv
    // IS NON-ZERO THE VIEW CHAINS VkSamplerYcbcrConversionInfo — MANDATORY TO SAMPLE A MULTIPLANAR
    // FORMAT THROUGH THE COLOR ASPECT, AND IT MUST BE THE SAME CONVERSION AS THE IMMUTABLE SAMPLER.
    private Img image(final MemoryStack stack, final int format, final int w, final int h, final int usage, final long conv) {
        final VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        ici.extent().width(w).height(h).depth(1);
        final LongBuffer p = stack.mallocLong(1);
        check(vkCreateImage(this.device, ici, null, p), "create image");
        final long img = p.get(0);

        final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
        vkGetImageMemoryRequirements(this.device, img, req);
        final int type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type < 0) throw new IllegalStateException("VKEngine: no device-local memory type for image");
        final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(req.size()).memoryTypeIndex(type);
        final LongBuffer pm = stack.mallocLong(1);
        check(vkAllocateMemory(this.device, mai, null, pm), "alloc image memory");
        final long mem = pm.get(0);
        check(vkBindImageMemory(this.device, img, mem, 0), "bind image memory");

        final VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
        // COMPONENTS STAY IDENTITY (calloc): REQUIRED WHEN A CONVERSION IS CHAINED.
        if (conv != 0L) vci.pNext(VkSamplerYcbcrConversionInfo.calloc(stack).sType$Default().conversion(conv));
        vci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        final LongBuffer pv = stack.mallocLong(1);
        check(vkCreateImageView(this.device, vci, null, pv), "create image view");
        return new Img(img, mem, pv.get(0));
    }

    // FINDS A MEMORY TYPE INDEX IN typeBits WITH ALL props SET, OR -1. props == 0 MATCHES ANY TYPE.
    private int memType(final int typeBits, final int props) {
        final int count = this.memProps.memoryTypeCount();
        for (int i = 0; i < count; i++) {
            if ((typeBits & (1 << i)) != 0 && (this.memProps.memoryTypes(i).propertyFlags() & props) == props) return i;
        }
        return -1;
    }

    // ==========================================================================
    // FORMAT TABLE
    // ==========================================================================
    private void buildFormat() {
        final int w = this.width;
        final int h = this.height;
        final int bps = this.bitsPerComponent == 32 ? 4 : (this.bitsPerComponent > 8 ? 2 : 1);
        this.bitScale = switch (this.bitsPerComponent) {
            case 10 -> 65535.0f / 1023.0f;
            case 12 -> 65535.0f / 4095.0f;
            default -> 1.0f; // 8, 16, 32
        };
        final int rF = bps == 1 ? VK_FORMAT_R8_UNORM : (bps == 2 ? VK_FORMAT_R16_UNORM : VK_FORMAT_R32_SFLOAT);
        final int rgF = bps == 1 ? VK_FORMAT_R8G8_UNORM : (bps == 2 ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R32G32_SFLOAT);

        this.fmtOk = true;
        this.convert = true;
        this.planeCount = 0;
        this.ycbcrFmt = 0;
        switch (this.pixelFormat) {
            case BGRA -> { this.convert = false; this.outFormat = VK_FORMAT_B8G8R8A8_UNORM; this.outTexelBytes = 4; }
            case RGBA -> { this.convert = false; this.outFormat = VK_FORMAT_R8G8B8A8_UNORM; this.outTexelBytes = 4; }
            case GRAY -> {
                // GRAY IS NOT A YCBCR FORMAT AT ALL — THE SINGLE-PLANE COMPUTE BROADCAST STAYS.
                this.mode = MODE_GRAY;
                this.plane(0, rF, bps, w, h);
                this.planeCount = 1;
            }
            case NV12, NV21 -> {
                this.mode = this.pixelFormat == PixelFormat.NV21 ? MODE_NV21 : MODE_NV12;
                this.plane(0, rF, bps, w, h);
                this.plane(1, rgF, 2 * bps, w / 2, h / 2);
                this.planeCount = 2;
                // NV21 STAYS ON COMPUTE: VULKAN HAS NO VU-ORDER 2-PLANE FORMAT AND COMPONENT
                // SWIZZLES ON MULTIPLANAR CONVERSIONS ARE DRIVER-FRAGILE. >8BIT STAYS ON COMPUTE:
                // FFMPEG PLANAR 10/12-BIT IS LSB-ALIGNED WHILE VULKAN G10X6/G12X4 FORMATS ARE
                // MSB-ALIGNED — INCOMPATIBLE MEMORY LAYOUTS. 420 MULTIPLANAR IMAGES ALSO REQUIRE
                // EVEN WIDTH AND HEIGHT (VUID-VkImageCreateInfo-format-04712/04713).
                if (this.pixelFormat == PixelFormat.NV12 && bps == 1 && (w & 1) == 0 && (h & 1) == 0) {
                    this.tryYcbcr(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM);
                }
            }
            case YUV420P, YUV422P, YUV444P -> {
                this.mode = MODE_YUV3;
                final int cw = this.pixelFormat == PixelFormat.YUV444P ? w : w / 2;
                final int ch = this.pixelFormat == PixelFormat.YUV420P ? h / 2 : h;
                this.plane(0, rF, bps, w, h);
                this.plane(1, rF, bps, cw, ch);
                this.plane(2, rF, bps, cw, ch);
                this.planeCount = 3;
                // 8-BIT ONLY (SEE THE NV12 NOTE ON 10/12-BIT ALIGNMENT). SUBSAMPLED MULTIPLANAR
                // IMAGES REQUIRE EVEN EXTENTS ALONG THE SUBSAMPLED AXES: 420 NEEDS EVEN WxH,
                // 422 NEEDS EVEN W, 444 HAS NO RESTRICTION.
                if (bps == 1) {
                    switch (this.pixelFormat) {
                        case YUV420P -> { if ((w & 1) == 0 && (h & 1) == 0) this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM); }
                        case YUV422P -> { if ((w & 1) == 0) this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_422_UNORM); }
                        default -> this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_444_UNORM);
                    }
                }
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                // YUVA STAYS ON COMPUTE: NO VULKAN MULTIPLANAR FORMAT CARRIES AN ALPHA PLANE.
                this.mode = MODE_YUVA;
                final int cw = this.pixelFormat == PixelFormat.YUVA444P ? w : w / 2;
                final int ch = this.pixelFormat == PixelFormat.YUVA420P ? h / 2 : h;
                this.plane(0, rF, bps, w, h);
                this.plane(1, rF, bps, cw, ch);
                this.plane(2, rF, bps, cw, ch);
                this.plane(3, rF, bps, w, h);
                this.planeCount = 4;
            }
            // PACKED YUYV/UYVY STAYS ON COMPUTE: THE EXACT PIXEL-PAIR texelFetch DECODE BELOW IS
            // ILLEGAL THROUGH A YCBCR SAMPLER (NO OpImageFetch), AND SINGLE-PLANE 422 PACKED
            // FORMATS (G8B8G8R8_422) HAVE SPOTTY OPTIMAL-TILING SUPPORT ACROSS DRIVERS.
            case YUYV, YUYV2 -> {
                this.mode = this.pixelFormat == PixelFormat.YUYV2 ? MODE_UYVY : MODE_YUYV;
                // CEIL: FOR ODD WIDTHS THE SHADER FETCHES TEXEL gid.x>>1 == w/2 ON THE LAST COLUMN,
                // SO A FLOOR-SIZED PLANE WOULD BE READ ONE TEXEL OUT OF BOUNDS.
                this.plane(0, VK_FORMAT_R8G8B8A8_UNORM, 4, (w + 1) / 2, h);
                this.planeCount = 1;
            }
            default -> { // RGB, GBRA — DECLINED BY supportsFormat; NEVER UPLOADED
                this.fmtOk = false;
                this.convert = false;
                this.outFormat = VK_FORMAT_R8G8B8A8_UNORM;
                this.outTexelBytes = 4;
            }
        }
        if (this.convert) {
            this.outFormat = VK_FORMAT_R8G8B8A8_UNORM;
            this.outUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        } else {
            this.outUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        }
    }

    private void plane(final int i, final int format, final int texelBytes, final int w, final int h) {
        PlaneSpec spec = this.planeSpecs[i];
        if (spec == null) { spec = new PlaneSpec(); this.planeSpecs[i] = spec; }
        spec.format = format;
        spec.texelBytes = texelBytes;
        spec.w = Math.max(1, w);
        spec.h = Math.max(1, h);
    }

    // ROUTES THE CURRENT FORMAT THROUGH THE SAMPLER-YCBCR PATH WHEN THE DEVICE FEATURE IS ON AND
    // THE MULTIPLANAR FORMAT CARRIES THE REQUIRED OPTIMAL-TILING BITS; OTHERWISE ycbcrFmt STAYS 0
    // AND THE ARITHMETIC COMPUTE PATH ALREADY CONFIGURED BY THE CALLER REMAINS ACTIVE.
    private void tryYcbcr(final int fmt) {
        if (!this.ctx.ycbcrSampler()) {
            LOGGER.info(IT, "YCbCr sampler path declined for {}: samplerYcbcrConversion feature off", this.pixelFormat);
            return;
        }
        Integer feats = this.ycbcrFeats.get(fmt);
        if (feats == null) {
            try (MemoryStack stack = stackPush()) {
                final VkFormatProperties props = VkFormatProperties.malloc(stack);
                vkGetPhysicalDeviceFormatProperties(this.ctx.physicalDevice(), fmt, props);
                feats = props.optimalTilingFeatures();
            }
            this.ycbcrFeats.put(fmt, feats);
        }
        // THE IMAGE IS SAMPLED BY THE TRIVIAL PASS AND FILLED VIA BUFFER->IMAGE COPIES, AND THE
        // CONVERSION NEEDS A SUPPORTED CHROMA SITING (EVEN 444 FORMATS MUST ADVERTISE ONE BIT —
        // OTHERWISE NO VALID xChromaOffset/yChromaOffset EXISTS AND THE ENGINE FALLS BACK).
        final int need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        final boolean mid = (feats & VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT) != 0;
        final boolean cos = (feats & VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT) != 0;
        if ((feats & need) != need || (!mid && !cos)) {
            LOGGER.info(IT, "YCbCr sampler path declined for {}: format {} unsupported (optimalTilingFeatures=0x{})",
                    this.pixelFormat, fmt, Integer.toHexString(feats));
            return;
        }
        this.ycbcrFmt = fmt;
        this.ycbcrLoc = mid ? VK_CHROMA_LOCATION_MIDPOINT : VK_CHROMA_LOCATION_COSITED_EVEN;
        // LINEAR ONLY WHEN THE FORMAT ADVERTISES BOTH YCBCR LINEAR CHROMA RECONSTRUCTION
        // (VUID-VkSamplerYcbcrConversionCreateInfo-chromaFilter-01657) AND PLAIN LINEAR SAMPLING
        // (VUID-vkCmdDispatch-magFilter-04553). min/mag/chroma ALWAYS SHARE ONE FILTER BECAUSE
        // WITHOUT SEPARATE-RECONSTRUCTION SUPPORT THEY MUST BE EQUAL (VUID-VkSamplerCreateInfo-minFilter-01645).
        final int linNeed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT;
        this.ycbcrFilter = (feats & linNeed) == linNeed ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    }

    // ==========================================================================
    // INIT — STATIC ENGINE OBJECTS
    // ==========================================================================
    private void init() {
        for (int i = 0; i < SLOTS; i++) this.slots[i] = new Slot();
        try (MemoryStack stack = stackPush()) {
            // COMMAND POOL + PRIMARY COMMAND BUFFERS (RESETTABLE INDIVIDUALLY).
            final VkCommandPoolCreateInfo cpci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(this.ctx.queueFamily());
            final LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(this.device, cpci, null, pPool), "create command pool");
            this.cmdPool = pPool.get(0);

            final VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(this.cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOTS);
            final PointerBuffer pCmd = stack.mallocPointer(SLOTS);
            check(vkAllocateCommandBuffers(this.device, cbai, pCmd), "alloc command buffers");

            final VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType$Default(); // UNSIGNALED
            for (int i = 0; i < SLOTS; i++) {
                this.slots[i].cmd = new VkCommandBuffer(pCmd.get(i), this.device);
                final LongBuffer pf = stack.mallocLong(1);
                check(vkCreateFence(this.device, fci, null, pf), "create fence");
                this.slots[i].fence = pf.get(0);
            }

            // SAMPLER (LINEAR, CLAMP) — PLANAR/NV CHROMA UPSAMPLING; PACKED YUV USES texelFetch.
            final VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false);
            final LongBuffer ps = stack.mallocLong(1);
            check(vkCreateSampler(this.device, sci, null, ps), "create sampler");
            this.sampler = ps.get(0);

            // DESCRIPTOR SET LAYOUT: 4 COMBINED IMAGE SAMPLERS (PLANES) + 1 STORAGE IMAGE (OUTPUT).
            final VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(5, stack);
            for (int j = 0; j < MAX_PLANES; j++) {
                binds.get(j).binding(j).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            binds.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            final VkDescriptorSetLayoutCreateInfo dlci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            final LongBuffer pl = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(this.device, dlci, null, pl), "create descriptor set layout");
            this.descLayout = pl.get(0);

            // DESCRIPTOR POOL + ONE SET PER SLOT.
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_PLANES * SLOTS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SLOTS);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SLOTS).pPoolSizes(sizes);
            final LongBuffer pdp = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.device, dpci, null, pdp), "create descriptor pool");
            this.descPool = pdp.get(0);

            final LongBuffer layouts = stack.mallocLong(SLOTS);
            for (int i = 0; i < SLOTS; i++) layouts.put(i, this.descLayout);
            final VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.descPool).pSetLayouts(layouts);
            final LongBuffer pSets = stack.mallocLong(SLOTS);
            check(vkAllocateDescriptorSets(this.device, dsai, pSets), "alloc descriptor sets");
            for (int i = 0; i < SLOTS; i++) this.slots[i].descSet = pSets.get(i);

            // PIPELINE LAYOUT (DESCRIPTOR SET + PUSH CONSTANTS).
            final VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            final VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(this.descLayout)).pPushConstantRanges(pcr);
            final LongBuffer ppl = stack.mallocLong(1);
            check(vkCreatePipelineLayout(this.device, plci, null, ppl), "create pipeline layout");
            this.pipeLayout = ppl.get(0);
        }
        // THE COMPUTE SHADER + PIPELINE ARE BUILT LAZILY ON THE FIRST CONVERSION UPLOAD (ensureComputePipeline):
        // PASSTHROUGH-ONLY ENGINES (E.G. BGRA/RGBA IMAGE THUMBNAILS) NEVER COMPILE, AND THE SPIR-V IS CACHED
        // ACROSS EVERY ENGINE — SO CREATING MANY ENGINES NO LONGER RECOMPILES THE SHADER EACH TIME.
    }

    // BUILDS THIS ENGINE'S COMPUTE SHADER MODULE + PIPELINE ON FIRST USE, FROM THE PROCESS-CACHED SPIR-V.
    private void ensureComputePipeline() {
        if (this.pipeline != 0L) return;
        final byte[] spirv = compiledSpirv();
        try (MemoryStack stack = stackPush()) {
            final ByteBuffer code = stack.malloc(spirv.length).put(spirv).flip();
            final VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            final LongBuffer pMod = stack.mallocLong(1);
            check(vkCreateShaderModule(this.device, smci, null, pMod), "create shader module");
            this.shaderModule = pMod.get(0);

            final VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(this.shaderModule).pName(stack.UTF8("main"));
            final VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().stage(stage).layout(this.pipeLayout);
            final LongBuffer pp = stack.mallocLong(1);
            check(vkCreateComputePipelines(this.device, 0, ci, null, pp), "create compute pipeline");
            this.pipeline = pp.get(0);
        }
    }

    // ==========================================================================
    // SAMPLER-YCBCR OBJECTS (FORMAT-SPECIFIC, LAZY)
    // ==========================================================================
    // BUILDS EVERY YCBCR OBJECT FOR THE CURRENT ycbcrFmt ON FIRST USE: CONVERSION -> IMMUTABLE
    // SAMPLER -> SET LAYOUT -> PIPELINE LAYOUT -> PIPELINE -> POOL + ONE SET PER SLOT. SAFE WITHOUT
    // EXTRA SYNC: ONLY THE PRODUCER THREAD CALLS THIS, AND A FORMAT CHANGE DRAINED EVERYTHING FIRST.
    private void ensureYcbcr() {
        if (this.ycbcrConv != 0L) return;
        try (MemoryStack stack = stackPush()) {
            // CONVERSION: BT.709 NARROW RANGE, IDENTITY COMPONENTS (calloc), SITING/FILTER FROM THE
            // FORMAT QUERY. NO FORCED EXPLICIT RECONSTRUCTION — LET THE IMPLEMENTATION PICK.
            final VkSamplerYcbcrConversionCreateInfo cci = VkSamplerYcbcrConversionCreateInfo.calloc(stack).sType$Default()
                    .format(this.ycbcrFmt)
                    .ycbcrModel(VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709)
                    .ycbcrRange(VK_SAMPLER_YCBCR_RANGE_ITU_NARROW)
                    .xChromaOffset(this.ycbcrLoc).yChromaOffset(this.ycbcrLoc)
                    .chromaFilter(this.ycbcrFilter)
                    .forceExplicitReconstruction(false);
            final LongBuffer p = stack.mallocLong(1);
            check(vkCreateSamplerYcbcrConversion(this.device, cci, null, p), "create ycbcr conversion");
            this.ycbcrConv = p.get(0);

            // SAMPLER: min/mag == chromaFilter (LEGAL WITHOUT SEPARATE-RECONSTRUCTION SUPPORT), AND
            // CLAMP/NO-ANISO/NO-COMPARE/NORMALIZED — ALL MANDATORY WITH A YCBCR CONVERSION.
            final VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .pNext(VkSamplerYcbcrConversionInfo.calloc(stack).sType$Default().conversion(this.ycbcrConv))
                    .magFilter(this.ycbcrFilter).minFilter(this.ycbcrFilter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false);
            check(vkCreateSampler(this.device, sci, null, p), "create ycbcr sampler");
            this.ycbcrSamp = p.get(0);

            // SET LAYOUT: BINDING 0 = COMBINED IMAGE SAMPLER WITH THE IMMUTABLE YCBCR SAMPLER
            // (IMMUTABILITY IS MANDATORY FOR CONVERSION-ENABLED SAMPLING), BINDING 1 = STORAGE IMAGE.
            final VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .pImmutableSamplers(stack.longs(this.ycbcrSamp));
            binds.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            final VkDescriptorSetLayoutCreateInfo dlci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            check(vkCreateDescriptorSetLayout(this.device, dlci, null, p), "create ycbcr set layout");
            this.ycbcrLayout = p.get(0);

            // PIPELINE LAYOUT: JUST { int width; int height; } PUSH CONSTANTS.
            final VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(YCBCR_PUSH_BYTES);
            final VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(this.ycbcrLayout)).pPushConstantRanges(pcr);
            check(vkCreatePipelineLayout(this.device, plci, null, p), "create ycbcr pipeline layout");
            this.ycbcrPipeLayout = p.get(0);

            // PIPELINE FROM THE PROCESS-CACHED TRIVIAL SHADER; THE MODULE DIES RIGHT AFTER (LEGAL).
            final byte[] spirv = compiledYcbcrSpirv();
            final ByteBuffer code = stack.malloc(spirv.length).put(spirv).flip();
            final VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            check(vkCreateShaderModule(this.device, smci, null, p), "create ycbcr shader module");
            final long module = p.get(0);
            final VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            final VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().stage(stage).layout(this.ycbcrPipeLayout);
            final int r = vkCreateComputePipelines(this.device, 0, ci, null, p);
            vkDestroyShaderModule(this.device, module, null);
            check(r, "create ycbcr pipeline");
            this.ycbcrPipeline = p.get(0);

            // POOL + ONE SET PER SLOT (THE LEGACY PER-SLOT SETS KEEP THE LEGACY LAYOUT).
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SLOTS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SLOTS);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SLOTS).pPoolSizes(sizes);
            check(vkCreateDescriptorPool(this.device, dpci, null, p), "create ycbcr descriptor pool");
            this.ycbcrPool = p.get(0);

            final LongBuffer layouts = stack.mallocLong(SLOTS);
            for (int i = 0; i < SLOTS; i++) layouts.put(i, this.ycbcrLayout);
            final VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.ycbcrPool).pSetLayouts(layouts);
            final LongBuffer pSets = stack.mallocLong(SLOTS);
            check(vkAllocateDescriptorSets(this.device, dsai, pSets), "alloc ycbcr descriptor sets");
            for (int i = 0; i < SLOTS; i++) this.slots[i].ycbcrSet = pSets.get(i);
        }
    }

    // CALLERS GUARANTEE THE ENGINE'S OWN WORK IS DRAINED. THE SAMPLER AND CONVERSION DIE LAST:
    // THE SET LAYOUT AND THE POOL'S SETS REFERENCE THE IMMUTABLE SAMPLER UNTIL THEY ARE GONE.
    private void destroyYcbcrRes() {
        for (final Slot s: this.slots) s.ycbcrSet = 0L;
        if (this.ycbcrPipeline != 0L) { vkDestroyPipeline(this.device, this.ycbcrPipeline, null); this.ycbcrPipeline = 0L; }
        if (this.ycbcrPipeLayout != 0L) { vkDestroyPipelineLayout(this.device, this.ycbcrPipeLayout, null); this.ycbcrPipeLayout = 0L; }
        if (this.ycbcrPool != 0L) { vkDestroyDescriptorPool(this.device, this.ycbcrPool, null); this.ycbcrPool = 0L; } // FREES SETS
        if (this.ycbcrLayout != 0L) { vkDestroyDescriptorSetLayout(this.device, this.ycbcrLayout, null); this.ycbcrLayout = 0L; }
        if (this.ycbcrSamp != 0L) { vkDestroySampler(this.device, this.ycbcrSamp, null); this.ycbcrSamp = 0L; }
        if (this.ycbcrConv != 0L) { vkDestroySamplerYcbcrConversion(this.device, this.ycbcrConv, null); this.ycbcrConv = 0L; }
    }

    // COMPILES GLSL TO SPIR-V VIA shaderc ONCE PER PROCESS AND CACHES THE (DEVICE-INDEPENDENT)
    // BYTECODE. shaderc COMPILATION IS THE EXPENSIVE STEP, SO SHARING IT ACROSS ENGINES IS A BIG WIN.
    private static synchronized byte[] compiledSpirv() {
        if (SPIRV == null) SPIRV = compileSpirv(COMPUTE_GLSL, "convert.comp");
        return SPIRV;
    }

    private static synchronized byte[] compiledYcbcrSpirv() {
        if (YCBCR_SPIRV == null) YCBCR_SPIRV = compileSpirv(YCBCR_GLSL, "ycbcr.comp");
        return YCBCR_SPIRV;
    }

    private static byte[] compileSpirv(final String glsl, final String name) {
        final long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("VKEngine: shaderc_compiler_initialize failed");
        long result = 0L;
        try {
            result = shaderc_compile_into_spv(compiler, glsl, shaderc_compute_shader, name, "main", 0L);
            if (result == 0L) throw new IllegalStateException("VKEngine: shaderc compile returned null");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException("VKEngine: shader compile failed: " + shaderc_result_get_error_message(result));
            }
            final ByteBuffer spv = shaderc_result_get_bytes(result);
            if (spv == null) throw new IllegalStateException("VKEngine: shaderc produced no SPIR-V");
            final byte[] bytes = new byte[spv.remaining()];
            spv.duplicate().get(bytes);
            return bytes;
        } finally {
            if (result != 0L) shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    private static void check(final int result, final String op) {
        if (result != VK_SUCCESS) throw new IllegalStateException("VKEngine: " + op + " failed (VkResult " + result + ")");
    }

    // ==========================================================================
    // STATE HOLDERS
    // ==========================================================================
    // ONE TRIPLE-BUFFER SLOT: ITS OWN PLANE IMAGES, MANAGED IMAGE/VIEW, COMMAND BUFFER AND FENCE.
    private static final class Slot {
        VkCommandBuffer cmd;
        long fence;
        volatile long seq;       // MONOTONIC SUBMIT STAMP (0 = NEVER SUBMITTED). PUBLISHED TO texture()
        volatile boolean submitted;
        volatile long view;      // MANAGED IMAGE VIEW (RETURNED BY texture())
        long outImg, outMem;
        final long[] planeImg = new long[MAX_PLANES];
        final long[] planeMem = new long[MAX_PLANES];
        final long[] planeView = new long[MAX_PLANES];
        final Stage[] stage = new Stage[MAX_PLANES];
        long descSet;
        long ycbcrSet;           // SET FOR THE YCBCR LAYOUT (0 UNTIL THE YCBCR OBJECTS EXIST)
        long gen = -1L;          // FORMAT GENERATION THIS SLOT'S RESOURCES MATCH
    }

    // A PERSISTENT MAPPED HOST-VISIBLE STAGING BUFFER (FALLBACK COPY SOURCE).
    private static final class Stage {
        final long buffer, memory, mapped, size;
        Stage(final long buffer, final long memory, final long mapped, final long size) {
            this.buffer = buffer; this.memory = memory; this.mapped = mapped; this.size = size;
        }
    }

    // A HOST POINTER IMPORTED AS A VkBuffer (ZERO-COPY COPY SOURCE).
    private static final class Imported {
        final long memory, buffer, size;
        Imported(final long memory, final long buffer, final long size) {
            this.memory = memory; this.buffer = buffer; this.size = size;
        }
    }

    // AN IMAGE + ITS BACKING MEMORY + ITS VIEW.
    private static final class Img {
        final long image, memory, view;
        Img(final long image, final long memory, final long view) {
            this.image = image; this.memory = memory; this.view = view;
        }
    }

    // PER-PLANE GPU IMAGE DESCRIPTOR FOR THE CURRENT FORMAT.
    private static final class PlaneSpec {
        int format, texelBytes, w, h;
    }

    // ==========================================================================
    // BUILDER
    // ==========================================================================
    public static final class Builder {
        private final VKContext ctx;

        public Builder(final VKContext ctx) {
            this.ctx = ctx;
        }

        public VKEngine build() {
            return new VKEngine(this.ctx);
        }
    }
}
