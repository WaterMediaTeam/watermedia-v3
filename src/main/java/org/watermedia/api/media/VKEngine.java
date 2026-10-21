package org.watermedia.api.media;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Every other format converts with the colorimetry of its layout: YCbCr matrix, sample range, chroma
 * siting and transfer curve. When the device enables {@code samplerYcbcrConversion}, an even-sized
 * 8-bit SDR frame without BT.2020 primaries in NV12 or planar YUV420/422/444 whose chroma siting the
 * format advertises uploads its planes into one multi-planar image, and an immutable
 * {@code VkSamplerYcbcrConversion} sampler built from that colorimetry converts in sampler hardware;
 * a trivial compute pass then samples it once into the managed {@code R8G8B8A8_UNORM} storage image,
 * so {@link #texture()} still returns a ready-to-sample RGBA view. Every other format (GRAY, NV21,
 * planar YUVA, packed YUYV/UYVY, deeper samples, PQ/HLG or BT.2020 primaries) runs one arithmetic
 * compute pass that samples the plane images and writes the managed image with the color math and
 * bit-depth scaling of {@code GLEngine}, HDR mapped to SDR included. The samplers, layouts and
 * pipelines of both paths are built once per device and shared by every engine on it until the last
 * one is released.
 * <p>
 * <b>Triple buffering &amp; synchronization.</b> Three slots are used round-robin; each owns its plane
 * images, managed image+view, command buffer and fence. Before reusing a slot the engine waits the
 * fence of the frame submitted two calls earlier, which honors the {@code upload} buffer-retention
 * contract for the zero-copy path regardless of producer pool size. Submission and conversion run on
 * the producer thread; the engine and the consumer submit to the same queue serialized by
 * {@link VKContext#queueLock()}, so a single same-queue image barrier (to {@code FRAGMENT_SHADER} /
 * {@code SHADER_READ}) makes each frame visible to the consumer — no cross-thread semaphores.
 * <p>
 * <b>Threading.</b> {@code upload} and {@code format(PixelFormat, int, int, int)} are called from the producer/decode
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
    private static final int PUSH_BYTES = 80; // { int mode; float bitScale; int width; int height; vec4 colorimetry[4]; } (std430)
    private static final int YCBCR_PUSH_BYTES = 8; // { int width; int height; }
    private static final long WAIT_TIMEOUT_NS = 5_000_000_000L; // 5s — GUARD AGAINST A WEDGED GPU

    // PROCESS-WIDE CACHES OF THE COMPILED SHADERS (SPIR-V IS DEVICE-INDEPENDENT), FILLED LAZILY
    // UNDER THE CLASS MONITOR ON THE FIRST CONVERSION FRAME. SEE compileSpirv().
    private static volatile byte[] convertSpirv;
    private static volatile byte[] ycbcrSpirv;
    // SHADER COMPILATION FAILED IN THIS PROCESS (NO shaderc, BAD GLSL): EVERY CONVERSION LAYOUT IS DECLINED FROM THEN ON
    private static volatile boolean convertBroken;
    // ONE CONVERSION KIT PER DEVICE, GUARDED BY ITS OWN MONITOR (SEE Kit). KEYED BY VkDevice IDENTITY, NOT ITS HANDLE: A
    // RECREATED DEVICE MAY REUSE THE ADDRESS OF ONE WHOSE KIT A WEDGED ENGINE STILL HOLDS
    private static final Map<VkDevice, Kit> KITS = new IdentityHashMap<>();

    // COMPUTE CONVERSION MODES (MUST MATCH THE MODE_* CONSTANTS OF vulkan/convert.comp)
    private static final int MODE_GRAY = 0;
    private static final int MODE_NV12 = 1;
    private static final int MODE_NV21 = 2;
    private static final int MODE_YUV3 = 3;
    private static final int MODE_YUVA = 4;
    private static final int MODE_YUYV = 5; // PACKED [Y0,U,Y1,V]
    private static final int MODE_UYVY = 6; // PACKED [U,Y0,V,Y1]

    // THE COMPUTE SHADERS BUNDLED UNDER assets/watermedia/shaders: ONE CONVERSION FOR EVERY NON-PASSTHROUGH FORMAT, MIRRORING
    // GLEngine'S FRAGMENTS THROUGH colorimetry.glsl, AND THE SINGLE-SAMPLE PASS BEHIND THE HARDWARE YCBCR SAMPLER
    private static final String CONVERT_SHADER = "vulkan/convert.comp";
    private static final String YCBCR_SHADER = "vulkan/ycbcr.comp";

    // CONTEXT (BORROWED — NEVER DESTROYED)
    private final VKContext ctx;
    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final int minAlign; // 0 WHEN HOST IMPORT IS UNAVAILABLE
    // THE DEVICE'S SHARED SAMPLER, LAYOUTS AND PIPELINES; LEFT ONLY AFTER EVERY OBJECT OF THIS ENGINE IS GONE
    private final Kit kit;

    // ENGINE-OWNED OBJECTS (LIVE FOR THE WHOLE ENGINE)
    private long cmdPool;
    private long descPool;

    private final Slot[] slots = new Slot[SLOTS];

    // IMPORT CACHE: HOST POINTER -> IMPORTED {VkDeviceMemory, VkBuffer}. GUARDED BY THE ENGINE MONITOR.
    private final Map<Long, Imported> importCache = new HashMap<>();

    // FORMAT-DERIVED STATE (BUILT IN apply())
    private boolean convert;     // TRUE WHEN A COMPUTE PASS PRODUCES THE MANAGED IMAGE
    private int mode;            // COMPUTE MODE WHEN convert
    private int outFormat;       // MANAGED IMAGE FORMAT
    private int outUsage;        // MANAGED IMAGE USAGE
    private float bitScale = 1.0f;
    private float[] colorimetry; // Colorimetry.constants OF THE LAYOUT, PUSHED AFTER THE FOUR SCALARS
    private int planeCount;      // SOURCE PLANES PER FRAME; PASSTHROUGH HAS ONE, COPIED STRAIGHT INTO THE MANAGED IMAGE
    private final PlaneSpec[] planeSpecs = new PlaneSpec[MAX_PLANES];
    private int formatGen;       // BUMPED ON EVERY FORMAT CHANGE; SLOTS REBUILD WHEN STALE

    // SAMPLER-YCBCR FAST PATH (FORMAT-SPECIFIC — TAKEN FROM THE KIT ON FIRST USE AND
    // LEFT ON EVERY FORMAT CHANGE AND IN destroyResources)
    private int ycbcrFmt;        // MULTIPLANAR VkFormat, 0 = COMPUTE/PASSTHROUGH PATH
    private int ycbcrX;          // CHOSEN HORIZONTAL VkChromaLocation
    private int ycbcrY;          // CHOSEN VERTICAL VkChromaLocation
    private int ycbcrFilter;     // CHOSEN VkFilter FOR min/mag/chroma (ALWAYS THE SAME THREE)
    private Ycbcr ycbcr;         // THE KIT'S CONVERSION, SAMPLER AND PIPELINE FOR THIS LAYOUT
    private long ycbcrPool;      // SLOTS SETS FOR THE YCBCR LAYOUT

    // SUBMISSION BOOKKEEPING (PRODUCER-THREAD ONLY)
    private long head;           // NUMBER OF FRAMES SUBMITTED
    private long submitSeq;      // MONOTONIC SEQUENCE STAMPED ONTO EACH SLOT ON SUBMIT

    // PUBLISHED TO texture() (RENDER THREAD). retiring HOLDS THE IMAGES OF A PREVIOUS LAYOUT WHOSE VIEW STAYS
    // EXPOSED UNTIL THE FIRST FRAME OF THE NEW ONE LANDS — GUARDED BY THE QUEUE LOCK
    private volatile long lastView;
    private volatile long lastSeq;
    private final List<Runnable> retiring = new ArrayList<>();
    // A DRIVER FAILURE PARKS THE ENGINE: UPLOADS ARE DROPPED UNTIL THE NEXT LAYOUT
    private volatile boolean failed;
    // ANOMALY LATCHES: EACH REPEATABLE UPLOAD ANOMALY IS LOGGED ONCE PER ENGINE
    private volatile boolean failWarned;
    private volatile boolean shortWarned;
    private volatile boolean strideWarned;

    // THE CONTEXT AND EVERY VULKAN OBJECT IT EXPOSES MUST OUTLIVE THIS ENGINE, WHICH ONLY CREATES ITS OWN COMMAND POOL,
    // DESCRIPTORS AND PER-SLOT IMAGES ON IT AND SHARES THE DEVICE'S KIT WITH EVERY OTHER ENGINE THERE
    VKEngine(final VKContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("VKContext must be non-null");
        this.ctx = ctx;
        this.device = ctx.vkDevice();
        this.memProps = ctx.memoryProperties();
        this.minAlign = ctx.hostImportSupported() ? (int) ctx.minImportedHostPointerAlignment() : 0;
        this.kit = Kit.acquire(ctx);

        // BUILD THE ENGINE OBJECTS (LIVE FOR THE WHOLE ENGINE LIFETIME).
        for (int i = 0; i < SLOTS; i++) this.slots[i] = new Slot();
        try (final MemoryStack stack = stackPush()) {
            // COMMAND POOL + PRIMARY COMMAND BUFFERS (RESETTABLE INDIVIDUALLY).
            final VkCommandPoolCreateInfo cpci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(ctx.queueFamily());
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

            // DESCRIPTOR POOL + ONE SET PER SLOT ON THE KIT'S LAYOUT.
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_PLANES * SLOTS);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SLOTS);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SLOTS).pPoolSizes(sizes);
            final LongBuffer pdp = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.device, dpci, null, pdp), "create descriptor pool");
            this.descPool = pdp.get(0);

            final LongBuffer layouts = stack.mallocLong(SLOTS);
            for (int i = 0; i < SLOTS; i++) layouts.put(i, this.kit.descLayout);
            final VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.descPool).pSetLayouts(layouts);
            final LongBuffer pSets = stack.mallocLong(SLOTS);
            check(vkAllocateDescriptorSets(this.device, dsai, pSets), "alloc descriptor sets");
            for (int i = 0; i < SLOTS; i++) this.slots[i].descSet = pSets.get(i);
        } catch (final RuntimeException | Error e) {
            this.destroyResources(); // NOTHING CREATED SO FAR OUTLIVES A FAILED CONSTRUCTION
            throw e;
        }
        // THE KIT BUILDS THE COMPUTE PIPELINE ON THE DEVICE'S FIRST CONVERSION UPLOAD: PASSTHROUGH-ONLY ENGINES
        // (E.G. BGRA/RGBA IMAGE THUMBNAILS) NEVER COMPILE, AND CREATING MANY ENGINES NEVER REBUILDS IT
        LOGGER.debug(IT, "VKEngine initialized (hostImport={}, minAlign={})", ctx.hostImportSupported(), this.minAlign);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /**
     * Returns the {@code VkImageView} handle of the final RGBA image for the newest completed frame.
     * <p>
     * Passthrough formats (BGRA, RGBA) are copied directly into the engine-owned managed image.
     * Every other format is resolved to RGBA by a compute pass; a subset of 8-bit SDR planar YUV is
     * instead sampled through a {@code VkSamplerYcbcrConversion} built from the frame's matrix, range
     * and chroma siting that converts in sampler hardware. Either way the handle returned here is
     * always a ready-to-sample RGBA view, never a raw luma or chroma plane.
     * <p>
     * The view is fence-gated: this returns the most recent slot whose GPU work has signalled its
     * fence, rotating across the slot ring as frames complete, and 0 until the first frame is ready.
     * <p>
     * <b>Pipeline caution.</b> Read this fresh on every draw and sample it within (or right after)
     * the engine's render cycle. The view rotates per frame; a format or resolution change keeps
     * the previous view until the first frame of the new layout completes, so the handle never
     * drops to 0 mid-stream, but caching it across frames or using it from an unrelated render
     * stage can sample a stale view or one whose backing image has already been destroyed.
     * @return the RGBA {@code VkImageView} handle, or 0 if no frame has completed yet
     */
    @Override
    public long texture() {
        if (this.released) return 0L;
        // THE QUEUE LOCK SERIALIZES THIS FENCE POLL WITH vkResetFences/vkQueueSubmit AND KEEPS THE lastSeq/lastView
        // READ-MODIFY-WRITE ATOMIC WITH apply(), WHICH ZEROES THE SLOT VIEWS UNDER THE SAME LOCK
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
            // THE FIRST FRAME OF A NEW LAYOUT REPLACES THE VIEW KEPT ALIVE ACROSS apply(): ITS IMAGES RETIRE
            // NOW, ONCE THE CONSUMER'S IN-FLIGHT FRAMES (WHICH MAY STILL SAMPLE THEM) COMPLETE
            if (bestView != this.lastView && !this.retiring.isEmpty()) {
                for (final Runnable old: this.retiring) this.ctx.retire(old);
                this.retiring.clear();
            }
            this.lastSeq = bestSeq;
            this.lastView = bestView;
            return bestView;
        }
    }

    @Override
    int alignment() {
        return this.minAlign;
    }

    @Override
    public boolean supports(final PixelFormat format, final int bits) {
        if (!super.supports(format, bits)) return false; // THE BASE DECLINES BCn: ITS SAMPLING IS NOT WIRED YET
        // WITHOUT A WORKING SHADER COMPILER ONLY THE PASSTHROUGH LAYOUTS REMAIN; THE PRODUCER CONVERTS THE REST TO BGRA
        if (convertBroken && format != PixelFormat.BGRA && format != PixelFormat.RGBA) return false;
        return switch (format) {
            // RGB (3-BYTE, NO ALPHA) IS DECLINED: 3-CHANNEL OPTIMAL-TILING SUPPORT IS OPTIONAL IN VULKAN,
            // SO THE PRODUCER PRE-CONVERTS IT TO BGRA UPSTREAM
            case RGB -> false;
            // PASSTHROUGH COPIES FOUR BYTES PER PIXEL AND PACKED 4:2:2 IS FETCHED THROUGH AN RGBA8 PLANE
            case BGRA, RGBA, YUYV, UYVY -> bits == 8;
            // PLANAR AND GRAY SAMPLE R (AND RG FOR NV) PLANES THROUGH THE LINEAR SAMPLER: 16-BIT AND FLOAT
            // SAMPLING ARE OPTIONAL IN THE SPEC, SO THE DEVICE MUST ADVERTISE THEM FOR THIS DEPTH
            default -> this.sampleable(redFormat(bits))
                    && (format != PixelFormat.NV12 && format != PixelFormat.NV21 || this.sampleable(redGreenFormat(bits)));
        };
    }

    // RESETS PER-FORMAT GPU STATE (SLOT IMAGES, IMPORT CACHE; POOL, PIPELINE, SAMPLER AND FENCES STAY) AFTER DRAINING OUR
    // WORK; THE LAST PUBLISHED VIEW STAYS EXPOSED UNTIL THE FIRST FRAME OF THE NEW LAYOUT COMPLETES (SEE texture)
    @Override
    synchronized boolean apply(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        // DRAIN ENGINE WORK SO PER-FORMAT RESOURCES CAN BE DESTROYED SAFELY; A WEDGED GPU REFUSES THE LAYOUT INSTEAD
        if (!this.waitAllIdle()) return false;
        synchronized (this.ctx.queueLock()) {
            for (final Slot s: this.slots) {
                // THE CONSUMER MAY STILL SAMPLE THE VIEW texture() RETURNED (waitAllIdle DRAINS ONLY OUR FENCES), SO IT
                // DIES AFTER THE NEXT PUBLISHED FRAME; PLANE/STAGING RESOURCES ARE PRIVATE AND DRAINED, SO THEY DIE NOW
                final long view = s.view, img = s.outImg, mem = s.outMem;
                if (view != 0L || img != 0L || mem != 0L) {
                    s.view = 0L;
                    s.outImg = 0L;
                    s.outMem = 0L;
                    final Runnable destroy = () -> {
                        if (view != 0L) vkDestroyImageView(this.device, view, null);
                        if (img != 0L) vkDestroyImage(this.device, img, null);
                        if (mem != 0L) vkFreeMemory(this.device, mem, null);
                    };
                    // ONLY THE EXPOSED VIEW WAITS FOR THE NEXT PUBLISHED FRAME; THE OTHERS RETIRE WITH THE IN-FLIGHT FRAMES
                    if (view == this.lastView) this.retiring.add(destroy);
                    else this.ctx.retire(destroy);
                }
                s.seq = 0L;
                s.submitted = false;
            }
        }
        for (final Slot s: this.slots) {
            this.destroySlotFormatRes(s);
            s.gen = -1L;
        }
        this.destroyImportCache();
        // THE YCBCR SETS ARE FORMAT-SPECIFIC AND NEVER REFERENCED BY CONSUMER FRAMES (THEY ONLY SAMPLE THE RGBA VIEW), SO
        // AFTER THE DRAIN ABOVE THEY DIE NOW AND THE KIT'S CONVERSION IS LEFT; THE NEW FORMAT TAKES ITS OWN ON FIRST USE
        this.destroyYcbcrRes();

        this.publish(format, width, height, bits, color);

        // BUILD THE FORMAT TABLE IN ONE PASS: PER-PLANE SPECS, OUTPUT FORMAT/USAGE, COMPUTE MODE
        // AND THE OPTIONAL SAMPLER-YCBCR ROUTE, ALL DERIVED FROM THE NEW FORMAT.
        this.bitScale = bitScale(bits);
        this.colorimetry = color.constants(format, width, height, bits);
        final int rF = redFormat(bits);

        this.convert = true;
        this.planeCount = format.planes();
        this.ycbcrFmt = 0;
        switch (format) {
            case BGRA -> { this.convert = false; this.outFormat = VK_FORMAT_B8G8R8A8_UNORM; this.plane(0, this.outFormat); }
            case RGBA -> { this.convert = false; this.outFormat = VK_FORMAT_R8G8B8A8_UNORM; this.plane(0, this.outFormat); }
            case GRAY -> {
                // GRAY IS NOT A YCBCR FORMAT AT ALL — THE SINGLE-PLANE COMPUTE BROADCAST STAYS.
                this.mode = MODE_GRAY;
                this.plane(0, rF);
            }
            case NV12, NV21 -> {
                this.mode = format == PixelFormat.NV21 ? MODE_NV21 : MODE_NV12;
                this.plane(0, rF);
                this.plane(1, redGreenFormat(bits));
                // ONLY 8-BIT EVEN-SIZED NV12 TAKES THE YCBCR PATH: VULKAN HAS NO VU-ORDER PLANE (NV21),
                // 10/12-BIT PLANAR IS LSB-ALIGNED VS VULKAN'S MSB-ALIGNED G10X6/G12X4, AND 420
                // MULTIPLANAR NEEDS EVEN WxH (VUID-VkImageCreateInfo-format-04712/04713).
                if (format == PixelFormat.NV12 && bits == 8 && (width & 1) == 0 && (height & 1) == 0)
                    this.tryYcbcr(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM);
            }
            case YUV420P, YUV422P, YUV444P -> {
                this.mode = MODE_YUV3;
                this.plane(0, rF);
                this.plane(1, rF);
                this.plane(2, rF);
                // 8-BIT ONLY (SEE THE NV12 NOTE ON 10/12-BIT ALIGNMENT). SUBSAMPLED MULTIPLANAR
                // IMAGES REQUIRE EVEN EXTENTS ALONG THE SUBSAMPLED AXES: 420 NEEDS EVEN WxH,
                // 422 NEEDS EVEN W, 444 HAS NO RESTRICTION.
                if (bits == 8) {
                    switch (format) {
                        case YUV420P -> { if ((width & 1) == 0 && (height & 1) == 0) this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM); }
                        case YUV422P -> { if ((width & 1) == 0) this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_422_UNORM); }
                        default -> this.tryYcbcr(VK_FORMAT_G8_B8_R8_3PLANE_444_UNORM);
                    }
                }
            }
            case YUVA420P, YUVA422P, YUVA444P -> {
                // YUVA STAYS ON COMPUTE: NO VULKAN MULTIPLANAR FORMAT CARRIES AN ALPHA PLANE.
                this.mode = MODE_YUVA;
                this.plane(0, rF);
                this.plane(1, rF);
                this.plane(2, rF);
                this.plane(3, rF);
            }
            // PACKED YUYV/UYVY STAYS ON COMPUTE: THE EXACT PIXEL-PAIR texelFetch DECODE IS ILLEGAL
            // THROUGH A YCBCR SAMPLER (NO OpImageFetch), AND SINGLE-PLANE 422 PACKED FORMATS
            // (G8B8G8R8_422) HAVE SPOTTY OPTIMAL-TILING SUPPORT ACROSS DRIVERS.
            case YUYV, UYVY -> {
                this.mode = format == PixelFormat.UYVY ? MODE_UYVY : MODE_YUYV;
                this.plane(0, VK_FORMAT_R8G8B8A8_UNORM); // ONE RGBA8 TEXEL PER PIXEL PAIR
            }
            // RGB AND BCn NEVER PASS supports(), SO THEY CANNOT REACH THIS TABLE
            default -> throw new IllegalStateException("VKEngine: " + format + " has no upload path");
        }
        if (this.convert) {
            this.outFormat = VK_FORMAT_R8G8B8A8_UNORM;
            this.outUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        } else {
            this.outUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        }

        this.formatGen++;
        this.head = 0L;
        this.failed = false; // A NEW LAYOUT IS A FRESH ATTEMPT AFTER A DRIVER FAILURE
        if (this.ycbcrFmt != 0)
            LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> YCbCr sampler path ({} {} range, chroma x {} y {}, {}; vkFormat={}, filter={})",
                    format, width, height, this.planeCount, bits, color.matrix(), color.full() ? "full" : "limited",
                    this.ycbcrX == VK_CHROMA_LOCATION_MIDPOINT ? "midpoint" : "cosited", this.ycbcrY == VK_CHROMA_LOCATION_MIDPOINT ? "midpoint" : "cosited",
                    color.transfer(), this.ycbcrFmt, this.ycbcrFilter == VK_FILTER_LINEAR ? "linear" : "nearest");
        else if (this.convert)
            LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> compute path ({} {} range, chroma x {} y {}, {}{})",
                    format, width, height, this.planeCount, bits, color.matrix(), color.full() ? "full" : "limited",
                    color.cositedX() ? "cosited" : "midpoint", color.cositedY() ? "cosited" : "midpoint",
                    color.transfer(), color.wide() ? ", BT.2020 primaries" : "");
        else
            LOGGER.debug(IT, "Format set: {} {}x{} ({} planes, {}bpc) -> passthrough path", format, width, height, this.planeCount, bits);
        return true;
    }

    @Override
    synchronized void release(final ByteBuffer buffer) {
        if (this.released || buffer == null || !buffer.isDirect()) return;
        // THE CACHE IS KEYED BY THE BUFFER'S BASE ADDRESS (POSITION-0 IMPORTS ONLY).
        final Imported imp = this.importCache.remove(memAddress(buffer) - buffer.position());
        if (imp == null) return;
        // AN IN-FLIGHT UPLOAD COMMAND BUFFER MAY STILL READ THE IMPORT — DRAIN THE ENGINE'S OWN WORK FIRST, THEN
        // DESTROY IT WHILE THE HOST MEMORY IS STILL VALID; A WEDGED GPU LEAKS THE IMPORT RATHER THAN FREEING IT IN USE
        if (!this.waitAllIdle()) return;
        vkDestroyBuffer(this.device, imp.buffer(), null);
        vkFreeMemory(this.device, imp.memory(), null);
    }

    // HOST IMPORTS DIE NOW: THE PRODUCER FREES THEIR MEMORY RIGHT AFTER release() AND AN IMPORT OUTLIVING ITS HOST POINTER
    // CRASHES THE DRIVER; THE REST IS RETIRED BY THE BACKEND ONCE ITS IN-FLIGHT FRAMES (WHICH MAY SAMPLE THE VIEW) COMPLETE
    @Override
    synchronized void destroy() {
        if (!this.waitAllIdle()) {
            // A WEDGED GPU MAY STILL READ EVERY OBJECT: LEAKING THEM BEATS DESTROYING MEMORY IN USE
            LOGGER.error(IT, "VKEngine released while the GPU is wedged; its Vulkan objects are leaked");
            return;
        }
        this.destroyImportCache();
        final List<Runnable> old;
        synchronized (this.ctx.queueLock()) {
            old = List.copyOf(this.retiring);
            this.retiring.clear();
        }
        this.ctx.retire(() -> {
            for (final Runnable r: old) r.run();
            this.destroyResources();
            LOGGER.debug(IT, "VKEngine released");
        });
    }

    // RUNS ON THE CONSUMER'S RENDER THREAD AFTER ITS IN-FLIGHT FRAMES RETIRE (SEE VKContext#retire). THE KIT GOES LAST:
    // THESE SETS WERE ALLOCATED ON ITS LAYOUTS
    private void destroyResources() {
        for (final Slot s: this.slots) {
            this.destroySlotFormatRes(s);
            if (s.fence != 0L) { vkDestroyFence(this.device, s.fence, null); s.fence = 0L; }
        }
        this.destroyYcbcrRes();
        if (this.descPool != 0L) { vkDestroyDescriptorPool(this.device, this.descPool, null); this.descPool = 0L; } // FREES SETS
        if (this.cmdPool != 0L) { vkDestroyCommandPool(this.device, this.cmdPool, null); this.cmdPool = 0L; }       // FREES CMD BUFFERS
        this.kit.release();
    }

    // ==========================================================================
    // SUBMISSION (PRODUCER THREAD)
    // ==========================================================================
    // NEVER THROWS: ANY DRIVER FAILURE PARKS THE ENGINE (SEE fail) AND THE FRAME IS DROPPED
    @Override
    synchronized void push(final ByteBuffer[] bufs, final int[] strides) {
        if (this.failed || this.width <= 0 || this.height <= 0) return;
        final int sources = this.planeCount;
        if (bufs.length != sources) return; // STALE FORMAT / WRONG PLANE COUNT

        // VALIDATE BEFORE TOUCHING A FENCE. THE LAST ROW CARRIES NO PADDING, SO THE COPY READS EXACTLY needed BYTES;
        // bufferRowLength COUNTS TEXELS, SO A STRIDE SHORTER THAN A ROW OR NOT A WHOLE NUMBER OF TEXELS CANNOT BE EXPRESSED
        final long[] needed = new long[sources];
        final int[] rowLen = new int[sources];
        for (int i = 0; i < sources; i++) {
            final PlaneSpec spec = this.planeSpecs[i];
            needed[i] = minBytes(strides[i], (long) spec.w * spec.texelBytes, spec.h);
            if (needed[i] < 0L || strides[i] % spec.texelBytes != 0) {
                if (!this.strideWarned) LOGGER.warn(IT, "Plane {} stride {} is shorter than its {}-texel row or not a multiple of its {}-byte texels; frame dropped",
                        i, strides[i], spec.w, spec.texelBytes);
                this.strideWarned = true;
            } else if (bufs[i].remaining() < needed[i]) {
                if (!this.shortWarned) LOGGER.warn(IT, "Plane {} buffer too small: {} < {}", i, bufs[i].remaining(), needed[i]);
                this.shortWarned = true;
            } else {
                rowLen[i] = strides[i] / spec.texelBytes; // A TIGHT STRIDE (0) IS ALSO VULKAN'S TIGHT ROW LENGTH
                continue;
            }
            // A DROPPED UPLOAD STILL COUNTS FOR RETENTION: THE LAST SUBMITTED FRAME FINISHES READING THE PRODUCER'S
            // BUFFERS BEFORE THIS CALL RETURNS, SO THE NEXT CALL'S head-2 WAIT CANNOT LEAVE IT IN FLIGHT
            final Slot last = this.slots[(int) ((this.head + SLOTS - 1) % SLOTS)];
            if (this.head >= 1 && last.submitted) this.await(last.fence, "Retention wait");
            return;
        }

        final int cur = (int) (this.head % SLOTS);
        final Slot slot = this.slots[cur];

        try (final MemoryStack stack = stackPush()) {
            // RETENTION: BLOCK UNTIL THE FRAME SUBMITTED TWO CALLS AGO HAS FINISHED READING THE
            // PRODUCER'S BUFFERS. (head + 1) % SLOTS IS THE SLOT OF FRAME head-2. WAITING IT HERE
            // GUARANTEES THAT BY THE TIME upload(N+2) RUNS, FRAME N IS COMPLETE — EXACTLY THE
            // GFXEngine "FINISH READING BEFORE THE SECOND SUBSEQUENT UPLOAD" CONTRACT, WHICH MATTERS
            // FOR THE ZERO-COPY PATH WHERE THE GPU READS THE DECODER'S MEMORY DIRECTLY.
            final Slot twoAgo = this.slots[(int) ((this.head + 1) % SLOTS)];
            if (this.head >= 2 && twoAgo.submitted && !this.await(twoAgo.fence, "Retention wait")) return;
            // THE SLOT WE REUSE LAST HELD FRAME head-SLOTS; ITS WORK MUST BE DONE BEFORE WE RESET
            // ITS COMMAND BUFFER AND OVERWRITE ITS IMAGES.
            if (slot.submitted && !this.await(slot.fence, "Slot wait")) return;
            synchronized (this.ctx.queueLock()) {
                check(vkResetFences(this.device, slot.fence), "reset fence");
            }
            slot.submitted = false; // texture() SKIPS THIS SLOT WHILE WE RE-RECORD IT

            // LAZILY (RE)CREATE PLANE/OUTPUT IMAGES FOR THE CURRENT FORMAT (NO-OP IN STEADY STATE).
            this.ensureSlot(slot);

            // RESOLVE EACH SOURCE TO A VkBuffer BEFORE RECORDING. THE STAGING FALLBACK COPIES HOST
            // BYTES NOW (SYNCHRONOUSLY), SO THE PRODUCER BUFFER IS FULLY READ ON RETURN; THE IMPORT
            // PATH DEFERS THE READ TO THE GPU (COVERED BY THE FENCE WAITS ABOVE).
            final long[] src = new long[sources];
            for (int i = 0; i < sources; i++) {
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
                    buffer = this.stageInto(slot, i, bufs[i], needed[i]);
                }
                src[i] = buffer;
            }
            if (this.failed) return; // A WEDGED DRAIN WHILE REPLACING AN IMPORT PARKED THE ENGINE MID-LOOP

            // RECORD
            check(vkResetCommandBuffer(slot.cmd, 0), "reset command buffer");
            final VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(slot.cmd, begin), "begin command buffer");

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
                vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.ycbcr.pipeline);
                vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.ycbcr.pipeLayout, 0,
                        stack.longs(slot.ycbcrSet), null);
                final ByteBuffer push = stack.malloc(YCBCR_PUSH_BYTES);
                push.putInt(0, this.width);
                push.putInt(4, this.height);
                vkCmdPushConstants(slot.cmd, this.ycbcr.pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                vkCmdDispatch(slot.cmd, (this.width + 7) / 8, (this.height + 7) / 8, 1);
                // HAND THE RESULT TO THE CONSUMER'S FRAGMENT SAMPLING (SAME-QUEUE BARRIER).
                this.barrier(stack, slot.cmd, slot.outImg,
                        VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            } else if (this.convert) {
                // THE DEVICE'S FIRST CONVERSION FRAME BUILDS THE KIT'S PIPELINE; EVERY LATER ENGINE REUSES IT
                final long pipeline = this.kit.pipeline();
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
                vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.kit.pipeLayout, 0,
                        stack.longs(slot.descSet), null);
                final ByteBuffer push = stack.malloc(PUSH_BYTES);
                push.putInt(0, this.mode);
                push.putFloat(4, this.bitScale);
                push.putInt(8, this.width);
                push.putInt(12, this.height);
                for (int i = 0; i < this.colorimetry.length; i++) push.putFloat(16 + i * 4, this.colorimetry[i]);
                vkCmdPushConstants(slot.cmd, this.kit.pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
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
            check(vkEndCommandBuffer(slot.cmd), "end command buffer");

            // SUBMIT UNDER THE SHARED QUEUE LOCK SIGNALING THIS SLOT'S FENCE.
            final VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(slot.cmd));
            synchronized (this.ctx.queueLock()) {
                final int r = vkQueueSubmit(this.ctx.queue(), si, slot.fence);
                if (r != VK_SUCCESS) {
                    this.fail("vkQueueSubmit", r);
                    return;
                }
            }
            // PUBLISH: seq FIRST, THEN submitted, SO texture() READING submitted=true SEES A VALID seq.
            slot.seq = ++this.submitSeq;
            slot.submitted = true;
            this.head++;
        } catch (final RuntimeException | LinkageError e) {
            this.fail(e);
        }
    }

    // ==========================================================================
    // HOST-POINTER IMPORT (ZERO-COPY) — CACHED BY HOST POINTER
    // ==========================================================================
    // IMPORTS THE DECODER'S HOST MEMORY AS A TRANSFER-SOURCE VkBuffer SO THE GPU READS IT IN PLACE.
    // RETURNS THE VkBuffer, OR 0 IF THE IMPORT IS REJECTED (CALLER FALLS BACK TO STAGING).
    private long importBuffer(final long ptr, final long size) {
        final Imported cached = this.importCache.get(ptr);
        if (cached != null && cached.size() >= size) return cached.buffer();
        if (cached != null) { // A LARGER REGION IS NEEDED — DRAIN THE READS OF THE STALE IMPORT, THEN DROP IT
            if (!this.waitAllIdle()) return 0L;
            this.importCache.remove(ptr);
            vkDestroyBuffer(this.device, cached.buffer(), null);
            vkFreeMemory(this.device, cached.memory(), null);
        }
        try (final MemoryStack stack = stackPush()) {
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
        if (st == null || st.size() < needed) {
            if (st != null) {
                vkUnmapMemory(this.device, st.memory());
                vkDestroyBuffer(this.device, st.buffer(), null);
                vkFreeMemory(this.device, st.memory(), null);
            }
            try (final MemoryStack stack = stackPush()) {
                final VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                        .size(needed).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                final LongBuffer pBuf = stack.mallocLong(1);
                check(vkCreateBuffer(this.device, bci, null, pBuf), "create staging buffer");
                final long buffer = pBuf.get(0);
                long memory = 0L;
                try {
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
                    memory = pMem.get(0);
                    check(vkBindBufferMemory(this.device, buffer, memory, 0), "bind staging memory");

                    final PointerBuffer pp = stack.mallocPointer(1);
                    check(vkMapMemory(this.device, memory, 0, req.size(), 0, pp), "map staging memory");
                    st = new Stage(buffer, memory, pp.get(0), needed);
                } catch (final RuntimeException e) {
                    // A PARTIAL FAILURE MUST NOT LEAK THE BUFFER OR ITS MEMORY
                    if (memory != 0L) vkFreeMemory(this.device, memory, null);
                    vkDestroyBuffer(this.device, buffer, null);
                    throw e;
                }
                slot.stage[i] = st;
            }
        }
        memCopy(memAddress(src), st.mapped(), needed);
        return st.buffer();
    }

    // ==========================================================================
    // PER-SLOT FORMAT RESOURCES
    // ==========================================================================
    // (RE)BUILDS THIS SLOT'S MANAGED IMAGE, PLANE IMAGES AND DESCRIPTOR SET FOR THE CURRENT FORMAT.
    // SAFE WITHOUT EXTRA SYNC: A FORMAT CHANGE DRAINS ALL FENCES, SO THE SLOT IS IDLE HERE.
    private void ensureSlot(final Slot slot) {
        if (slot.gen == this.formatGen) return;
        this.destroySlotFormatRes(slot);
        try (final MemoryStack stack = stackPush()) {
            final Img out = this.image(stack, this.outFormat, this.width, this.height, this.outUsage, 0L);
            slot.outImg = out.image();
            slot.outMem = out.memory();
            slot.view = out.view();
            if (this.ycbcrFmt != 0) {
                this.ensureYcbcr(); // CONVERSION/SAMPLER/PIPELINE/SETS MUST EXIST BEFORE THE VIEW CHAINS THEM
                // ONE MULTIPLANAR IMAGE HOLDS EVERY PLANE; ITS SAMPLED VIEW CHAINS THE SAME CONVERSION
                // THE IMMUTABLE SAMPLER WAS CREATED WITH (REQUIRED TO MATCH).
                final Img mp = this.image(stack, this.ycbcrFmt, this.width, this.height,
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, this.ycbcr.conv);
                slot.planeImg[0] = mp.image();
                slot.planeMem[0] = mp.memory();
                slot.planeView[0] = mp.view();
                // BINDING 0 = MULTIPLANAR VIEW (SAMPLER FIELD IGNORED — THE LAYOUT'S IMMUTABLE SAMPLER
                // WINS), BINDING 1 = THIS SLOT'S RGBA STORAGE VIEW.
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                final VkDescriptorImageInfo.Buffer srcInfo = VkDescriptorImageInfo.calloc(1, stack);
                srcInfo.get(0).sampler(this.ycbcr.sampler).imageView(mp.view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                writes.get(0).sType$Default().dstSet(slot.ycbcrSet).dstBinding(0).dstArrayElement(0)
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(srcInfo);
                final VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
                outInfo.get(0).imageView(out.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                writes.get(1).sType$Default().dstSet(slot.ycbcrSet).dstBinding(1).dstArrayElement(0)
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outInfo);
                vkUpdateDescriptorSets(this.device, writes, null);
            } else if (this.convert) {
                for (int i = 0; i < this.planeCount; i++) {
                    final PlaneSpec spec = this.planeSpecs[i];
                    final Img p = this.image(stack, spec.format, spec.w, spec.h,
                            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 0L);
                    slot.planeImg[i] = p.image();
                    slot.planeMem[i] = p.memory();
                    slot.planeView[i] = p.view();
                }
                // REWRITE THE SLOT'S DESCRIPTOR SET: BINDINGS 0..3 = PLANE VIEWS, BINDING 4 = OUTPUT.
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
                for (int j = 0; j < MAX_PLANES; j++) {
                    // UNUSED PLANE SLOTS BIND PLANE 0 (ALWAYS PRESENT FOR CONVERT) — A VALID VIEW THE SHADER NEVER READS
                    final long view = j < this.planeCount ? slot.planeView[j] : slot.planeView[0];
                    final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                    info.get(0).sampler(this.kit.sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    writes.get(j).sType$Default().dstSet(slot.descSet).dstBinding(j).dstArrayElement(0)
                            .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
                }
                final VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
                outInfo.get(0).imageView(slot.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                writes.get(4).sType$Default().dstSet(slot.descSet).dstBinding(4).dstArrayElement(0)
                        .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(outInfo);
                vkUpdateDescriptorSets(this.device, writes, null);
            }
        }
        slot.gen = this.formatGen;
    }

    private void destroySlotFormatRes(final Slot s) {
        for (int j = 0; j < MAX_PLANES; j++) {
            if (s.planeView[j] != 0L) { vkDestroyImageView(this.device, s.planeView[j], null); s.planeView[j] = 0L; }
            if (s.planeImg[j] != 0L) { vkDestroyImage(this.device, s.planeImg[j], null); s.planeImg[j] = 0L; }
            if (s.planeMem[j] != 0L) { vkFreeMemory(this.device, s.planeMem[j], null); s.planeMem[j] = 0L; }
            if (s.stage[j] != null) {
                vkUnmapMemory(this.device, s.stage[j].memory());
                vkDestroyBuffer(this.device, s.stage[j].buffer(), null);
                vkFreeMemory(this.device, s.stage[j].memory(), null);
                s.stage[j] = null;
            }
        }
        if (s.view != 0L) { vkDestroyImageView(this.device, s.view, null); s.view = 0L; }
        if (s.outImg != 0L) { vkDestroyImage(this.device, s.outImg, null); s.outImg = 0L; }
        if (s.outMem != 0L) { vkFreeMemory(this.device, s.outMem, null); s.outMem = 0L; }
    }

    private void destroyImportCache() {
        for (final Imported imp: this.importCache.values()) {
            vkDestroyBuffer(this.device, imp.buffer(), null);
            vkFreeMemory(this.device, imp.memory(), null);
        }
        this.importCache.clear();
    }

    // DRAINS THE ENGINE'S OWN FENCES; FALSE PARKS THE ENGINE BECAUSE A WEDGED GPU MAY STILL READ EVERY OBJECT
    private boolean waitAllIdle() {
        for (final Slot s: this.slots) {
            if (s.fence != 0L && s.submitted && !this.await(s.fence, "Idle wait")) return false;
        }
        return true;
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
        long mem = 0L;
        try {
            final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(this.device, img, req);
            final int type = this.memType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) throw new IllegalStateException("VKEngine: no device-local memory type for image");
            final VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(req.size()).memoryTypeIndex(type);
            final LongBuffer pm = stack.mallocLong(1);
            check(vkAllocateMemory(this.device, mai, null, pm), "alloc image memory");
            mem = pm.get(0);
            check(vkBindImageMemory(this.device, img, mem, 0), "bind image memory");

            final VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            // COMPONENTS STAY IDENTITY (calloc): REQUIRED WHEN A CONVERSION IS CHAINED.
            if (conv != 0L) vci.pNext(VkSamplerYcbcrConversionInfo.calloc(stack).sType$Default().conversion(conv));
            vci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            final LongBuffer pv = stack.mallocLong(1);
            check(vkCreateImageView(this.device, vci, null, pv), "create image view");
            return new Img(img, mem, pv.get(0));
        } catch (final RuntimeException e) {
            // A PARTIAL FAILURE MUST NOT LEAK THE IMAGE OR ITS MEMORY
            if (mem != 0L) vkFreeMemory(this.device, mem, null);
            vkDestroyImage(this.device, img, null);
            throw e;
        }
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
    // FORMAT TABLE HELPERS
    // ==========================================================================
    // SIZES PLANE i OF THE PUBLISHED LAYOUT. SUBSAMPLED PLANES ROUND UP LIKE THE PRODUCER'S, SO ON ODD SIZES THE
    // LAST CHROMA COLUMN/ROW (AND THE PACKED gid.x >> 1 FETCH) STAYS INSIDE THE IMAGE
    private void plane(final int i, final int vkFormat) {
        PlaneSpec spec = this.planeSpecs[i];
        if (spec == null) { spec = new PlaneSpec(); this.planeSpecs[i] = spec; }
        spec.format = vkFormat;
        spec.texelBytes = this.format.texelBytes(i, this.bits);
        spec.w = Math.max(1, this.format.planeWidth(i, this.width));
        spec.h = Math.max(1, this.format.planeHeight(i, this.height));
    }

    // PLANE FORMATS PER SAMPLE DEPTH: 8-BIT UNORM, 16-BIT UNORM FOR 10/12/16 (bitScale RESCALES), 32-BIT FLOAT
    private static int redFormat(final int bits) {
        return bits == 32 ? VK_FORMAT_R32_SFLOAT : (bits > 8 ? VK_FORMAT_R16_UNORM : VK_FORMAT_R8_UNORM);
    }

    private static int redGreenFormat(final int bits) {
        return bits == 32 ? VK_FORMAT_R32G32_SFLOAT : (bits > 8 ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R8G8_UNORM);
    }

    // WHETHER A PLANE FORMAT CAN BE COPIED INTO AND SAMPLED THROUGH THE LINEAR SAMPLER ON THIS DEVICE
    private boolean sampleable(final int vkFormat) {
        final int need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        return (this.kit.features(vkFormat) & need) == need;
    }

    // ROUTES THE CURRENT FORMAT THROUGH THE SAMPLER-YCBCR PATH WHEN THE DEVICE FEATURE IS ON, THE FRAME NEEDS NO DISPLAY
    // MAPPING AND THE MULTIPLANAR FORMAT CARRIES THE REQUIRED BITS; OTHERWISE THE CALLER'S COMPUTE PATH STAYS (ycbcrFmt 0)
    private void tryYcbcr(final int fmt) {
        if (!this.ctx.ycbcrSampler()) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: samplerYcbcrConversion feature off", this.format);
            return;
        }
        // THE SAMPLER ONLY EXPANDS THE RANGE AND APPLIES THE MATRIX: PQ, HLG AND BT.2020 PRIMARIES NEED THE COMPUTE display()
        final Colorimetry color = this.color;
        if (color.transfer() != Colorimetry.Transfer.SDR || color.wide()) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: {} transfer{} needs the compute display mapping",
                    this.format, color.transfer(), color.wide() ? " with BT.2020 primaries" : "");
            return;
        }
        // THE IMAGE IS SAMPLED BY THE TRIVIAL PASS AND FILLED VIA BUFFER->IMAGE COPIES
        final int feats = this.kit.features(fmt);
        final int need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        if ((feats & need) != need) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: format {} unsupported (optimalTilingFeatures=0x{})",
                    this.format, fmt, Integer.toHexString(feats));
            return;
        }
        // A SUBSAMPLED AXIS NEEDS THE FRAME'S OWN SITING ADVERTISED; A FULL-RESOLUTION AXIS IGNORES IT BUT STILL TAKES AN
        // ADVERTISED VALUE, SO A FORMAT WITHOUT EITHER BIT HAS NO VALID OFFSET AT ALL
        final boolean mid = (feats & VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT) != 0;
        final boolean cos = (feats & VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT) != 0;
        final PlaneSpec chroma = this.planeSpecs[1];
        final boolean cosX = chroma.w < this.width ? color.cositedX() : !mid;
        final boolean cosY = chroma.h < this.height ? color.cositedY() : !mid;
        if ((cosX || cosY) && !cos || (!cosX || !cosY) && !mid) {
            LOGGER.debug(IT, "YCbCr sampler path declined for {}: format {} lacks chroma siting x {} y {} (optimalTilingFeatures=0x{})",
                    this.format, fmt, cosX ? "cosited" : "midpoint", cosY ? "cosited" : "midpoint", Integer.toHexString(feats));
            return;
        }
        this.ycbcrFmt = fmt;
        this.ycbcrX = cosX ? VK_CHROMA_LOCATION_COSITED_EVEN : VK_CHROMA_LOCATION_MIDPOINT;
        this.ycbcrY = cosY ? VK_CHROMA_LOCATION_COSITED_EVEN : VK_CHROMA_LOCATION_MIDPOINT;
        // LINEAR ONLY WHEN THE FORMAT ADVERTISES BOTH YCBCR LINEAR CHROMA RECONSTRUCTION
        // (VUID-VkSamplerYcbcrConversionCreateInfo-chromaFilter-01657) AND PLAIN LINEAR SAMPLING
        // (VUID-vkCmdDispatch-magFilter-04553). min/mag/chroma ALWAYS SHARE ONE FILTER BECAUSE
        // WITHOUT SEPARATE-RECONSTRUCTION SUPPORT THEY MUST BE EQUAL (VUID-VkSamplerCreateInfo-minFilter-01645).
        final int linNeed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT;
        this.ycbcrFilter = (feats & linNeed) == linNeed ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    }

    // ==========================================================================
    // SAMPLER-YCBCR OBJECTS (FORMAT-SPECIFIC, LAZY)
    // ==========================================================================
    // TAKES THE KIT'S CONVERSION FOR THE CURRENT LAYOUT ON FIRST USE AND ALLOCATES THIS ENGINE'S POOL + ONE SET PER SLOT ON
    // ITS LAYOUT. ONLY THE PRODUCER THREAD CALLS THIS, AND A FORMAT CHANGE DRAINED EVERYTHING FIRST
    private void ensureYcbcr() {
        if (this.ycbcr != null) return;
        final int model = switch (this.color.matrix()) {
            case BT601 -> VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
            case BT709 -> VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
            case BT2020 -> VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020;
        };
        final int range = this.color.full() ? VK_SAMPLER_YCBCR_RANGE_ITU_FULL : VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
        final Ycbcr y = this.kit.ycbcr(new YcbcrKey(this.ycbcrFmt, model, range, this.ycbcrX, this.ycbcrY, this.ycbcrFilter));
        this.ycbcr = y;
        try (final MemoryStack stack = stackPush()) {
            // A YCBCR SAMPLER MAY COST SEVERAL COMBINED-SAMPLER DESCRIPTORS (RADV, MALI): THE POOL RESERVES WHAT THE DRIVER REPORTS
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SLOTS * y.perSet);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SLOTS);
            final VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(SLOTS).pPoolSizes(sizes);
            final LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorPool(this.device, dpci, null, p), "create ycbcr descriptor pool");
            this.ycbcrPool = p.get(0);

            final LongBuffer layouts = stack.mallocLong(SLOTS);
            for (int i = 0; i < SLOTS; i++) layouts.put(i, y.layout);
            final VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(this.ycbcrPool).pSetLayouts(layouts);
            final LongBuffer pSets = stack.mallocLong(SLOTS);
            check(vkAllocateDescriptorSets(this.device, dsai, pSets), "alloc ycbcr descriptor sets");
            for (int i = 0; i < SLOTS; i++) this.slots[i].ycbcrSet = pSets.get(i);
        }
    }

    // CALLERS GUARANTEE THE ENGINE'S OWN WORK IS DRAINED. THE POOL DIES BEFORE THE KIT MAY DESTROY THE LAYOUT AND THE
    // IMMUTABLE SAMPLER ITS SETS REFERENCE
    private void destroyYcbcrRes() {
        for (final Slot s: this.slots) s.ycbcrSet = 0L;
        if (this.ycbcrPool != 0L) { vkDestroyDescriptorPool(this.device, this.ycbcrPool, null); this.ycbcrPool = 0L; } // FREES SETS
        if (this.ycbcr != null) { this.kit.release(this.ycbcr); this.ycbcr = null; }
    }

    // COMPILES A BUNDLED SHADER TO SPIR-V VIA shaderc. RUNS ONCE PER PROCESS PER SHADER — CALLERS CACHE THE
    // (DEVICE-INDEPENDENT) BYTECODE IN convertSpirv/ycbcrSpirv UNDER THE CLASS MONITOR, SO CREATING MANY
    // ENGINES NEVER RECOMPILES; shaderc COMPILATION IS THE EXPENSIVE STEP.
    private static byte[] compileSpirv(final String name) {
        long compiler = 0L;
        long result = 0L;
        try {
            final String glsl = IOTool.shader(name);
            compiler = shaderc_compiler_initialize();
            if (compiler == 0L) throw new IllegalStateException("VKEngine: shaderc_compiler_initialize failed");
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
        } catch (final IOException e) {
            // A MISSING FILE, A MISSING shaderc OR A BROKEN SHADER NEVER RECOVERS: DECLINE CONVERSION LAYOUTS FOR THE REST OF THE PROCESS
            convertBroken = true;
            throw new IllegalStateException("VKEngine: cannot read the shader " + name, e);
        } catch (final RuntimeException | LinkageError e) {
            convertBroken = true;
            throw e;
        } finally {
            if (result != 0L) shaderc_result_release(result);
            if (compiler != 0L) shaderc_compiler_release(compiler);
        }
    }

    private static void check(final int result, final String op) {
        if (result != VK_SUCCESS) throw new IllegalStateException("VKEngine: " + op + " failed (VkResult " + result + ")");
    }

    // FENCE WAIT THAT PARKS THE ENGINE ON A TIMEOUT OR DEVICE LOSS INSTEAD OF RESETTING A FENCE STILL IN FLIGHT
    private boolean await(final long fence, final String what) {
        final int result = vkWaitForFences(this.device, fence, true, WAIT_TIMEOUT_NS);
        if (result == VK_SUCCESS) return true;
        this.fail(what, result);
        return false;
    }

    // PARKS THE ENGINE AFTER A DRIVER FAILURE: FRAMES ARE DROPPED UNTIL THE NEXT LAYOUT AND THE LAST VIEW STAYS EXPOSED
    private void fail(final String op, final int result) {
        this.failed = true;
        if (!this.failWarned) LOGGER.error(IT, "{} failed (VkResult {}); frames are dropped until the next format", op, result);
        this.failWarned = true;
    }

    private void fail(final Throwable cause) {
        this.failed = true;
        if (!this.failWarned) LOGGER.error(IT, "Upload failed; frames are dropped until the next format", cause);
        this.failWarned = true;
    }

    // ==========================================================================
    // DEVICE CONVERSION KIT
    // ==========================================================================
    // WHAT EVERY ENGINE ON ONE DEVICE SHARES: THE PLANE SAMPLER, THE COMPUTE SET AND PIPELINE LAYOUTS AND PIPELINE, ONE
    // YCBCR CONVERSION + PIPELINE PER KEY AND THE FORMAT-FEATURE QUERIES. ENGINES LEAVE IT ONLY AFTER THEIR WORK DRAINED
    private static final class Kit {
        private final VkDevice device;
        private final VkPhysicalDevice physical;
        long sampler;
        long descLayout;
        long pipeLayout;
        private volatile long pipeline; // BUILT ON THE DEVICE'S FIRST CONVERSION FRAME
        private final Map<YcbcrKey, Ycbcr> ycbcrs = new HashMap<>(); // GUARDED BY this
        private final Map<Integer, Integer> formatFeatures = new ConcurrentHashMap<>(); // vkFormat -> optimalTilingFeatures
        private int engines; // GUARDED BY KITS

        private Kit(final VkDevice device, final VkPhysicalDevice physical) {
            this.device = device;
            this.physical = physical;
            try (final MemoryStack stack = stackPush()) {
                // SAMPLER (LINEAR, CLAMP) — PLANAR/NV CHROMA UPSAMPLING; PACKED YUV USES texelFetch.
                final VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                        .unnormalizedCoordinates(false);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateSampler(device, sci, null, p), "create sampler");
                this.sampler = p.get(0);

                // DESCRIPTOR SET LAYOUT: 4 COMBINED IMAGE SAMPLERS (PLANES) + 1 STORAGE IMAGE (OUTPUT).
                final VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(5, stack);
                for (int j = 0; j < MAX_PLANES; j++) {
                    binds.get(j).binding(j).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                binds.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                final VkDescriptorSetLayoutCreateInfo dlci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
                check(vkCreateDescriptorSetLayout(device, dlci, null, p), "create descriptor set layout");
                this.descLayout = p.get(0);

                // PIPELINE LAYOUT (DESCRIPTOR SET + PUSH CONSTANTS).
                final VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
                pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
                final VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(this.descLayout)).pPushConstantRanges(pcr);
                check(vkCreatePipelineLayout(device, plci, null, p), "create pipeline layout");
                this.pipeLayout = p.get(0);
            } catch (final RuntimeException | Error e) {
                this.destroy();
                throw e;
            }
        }

        static Kit acquire(final VKContext ctx) {
            synchronized (KITS) {
                final Kit kit = KITS.computeIfAbsent(ctx.vkDevice(), device -> new Kit(device, ctx.physicalDevice()));
                kit.engines++;
                return kit;
            }
        }

        // THE LAST ENGINE OUT ALREADY LEFT ITS YCBCR CONVERSIONS, SO ONLY THE DEVICE-WIDE OBJECTS REMAIN
        void release() {
            synchronized (KITS) {
                if (--this.engines > 0) return;
                KITS.remove(this.device, this);
            }
            this.destroy();
        }

        private void destroy() {
            if (this.pipeline != 0L) vkDestroyPipeline(this.device, this.pipeline, null);
            if (this.pipeLayout != 0L) vkDestroyPipelineLayout(this.device, this.pipeLayout, null);
            if (this.descLayout != 0L) vkDestroyDescriptorSetLayout(this.device, this.descLayout, null);
            if (this.sampler != 0L) vkDestroySampler(this.device, this.sampler, null);
        }

        // PIPELINES ARE BUILT OUTSIDE THE KIT MONITOR, SO A RELEASE ON THE RENDER THREAD NEVER WAITS FOR A DRIVER COMPILE; A
        // RACING BUILD OF THE SAME PIPELINE LOSES AND DESTROYS ITS COPY
        long pipeline() {
            final long built = this.pipeline;
            if (built != 0L) return built;
            if (convertSpirv == null) {
                synchronized (VKEngine.class) {
                    if (convertSpirv == null) convertSpirv = compileSpirv(CONVERT_SHADER);
                }
            }
            final long fresh = this.compute(convertSpirv, this.pipeLayout);
            final long kept;
            synchronized (this) {
                if (this.pipeline == 0L) this.pipeline = fresh;
                kept = this.pipeline;
            }
            if (kept != fresh) vkDestroyPipeline(this.device, fresh, null);
            else LOGGER.debug(IT, "Built the conversion pipeline for device 0x{}", Long.toHexString(this.device.address()));
            return kept;
        }

        Ycbcr ycbcr(final YcbcrKey key) {
            synchronized (this) {
                final Ycbcr cached = this.ycbcrs.get(key);
                if (cached != null) {
                    cached.refs++;
                    return cached;
                }
            }
            final Ycbcr fresh = this.build(key);
            final Ycbcr kept;
            synchronized (this) {
                final Ycbcr raced = this.ycbcrs.putIfAbsent(key, fresh);
                kept = raced != null ? raced : fresh;
                kept.refs++;
            }
            if (kept != fresh) destroy(this.device, fresh);
            else LOGGER.debug(IT, "Built the YCbCr pipeline for vkFormat {} on device 0x{}", key.format(), Long.toHexString(this.device.address()));
            return kept;
        }

        void release(final Ycbcr y) {
            synchronized (this) {
                if (--y.refs > 0) return;
                this.ycbcrs.remove(y.key, y);
            }
            destroy(this.device, y);
        }

        // OPTIMAL-TILING FEATURES OF A FORMAT, QUERIED ONCE PER DEVICE AND FORMAT
        int features(final int vkFormat) {
            return this.formatFeatures.computeIfAbsent(vkFormat, fmt -> {
                try (final MemoryStack stack = stackPush()) {
                    final VkFormatProperties props = VkFormatProperties.malloc(stack);
                    vkGetPhysicalDeviceFormatProperties(this.physical, fmt, props);
                    return props.optimalTilingFeatures();
                }
            });
        }

        // CONVERSION -> IMMUTABLE SAMPLER -> SET LAYOUT -> PIPELINE LAYOUT -> PIPELINE, PLUS THE DESCRIPTOR COST OF ITS IMAGES
        private Ycbcr build(final YcbcrKey key) {
            final Ycbcr y = new Ycbcr(key);
            try (final MemoryStack stack = stackPush()) {
                // THE LAYOUT'S MATRIX, RANGE AND SITING (CHECKED BY tryYcbcr), IDENTITY COMPONENTS (calloc) AND THE QUERIED
                // FILTER. NO FORCED EXPLICIT RECONSTRUCTION — LET THE IMPLEMENTATION PICK
                final VkSamplerYcbcrConversionCreateInfo cci = VkSamplerYcbcrConversionCreateInfo.calloc(stack).sType$Default()
                        .format(key.format()).ycbcrModel(key.model()).ycbcrRange(key.range())
                        .xChromaOffset(key.x()).yChromaOffset(key.y())
                        .chromaFilter(key.filter())
                        .forceExplicitReconstruction(false);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateSamplerYcbcrConversion(this.device, cci, null, p), "create ycbcr conversion");
                y.conv = p.get(0);

                // SAMPLER: min/mag == chromaFilter (LEGAL WITHOUT SEPARATE-RECONSTRUCTION SUPPORT), AND
                // CLAMP/NO-ANISO/NO-COMPARE/NORMALIZED — ALL MANDATORY WITH A YCBCR CONVERSION.
                final VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .pNext(VkSamplerYcbcrConversionInfo.calloc(stack).sType$Default().conversion(y.conv))
                        .magFilter(key.filter()).minFilter(key.filter())
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                        .unnormalizedCoordinates(false);
                check(vkCreateSampler(this.device, sci, null, p), "create ycbcr sampler");
                y.sampler = p.get(0);

                // SET LAYOUT: BINDING 0 = COMBINED IMAGE SAMPLER WITH THE IMMUTABLE YCBCR SAMPLER
                // (IMMUTABILITY IS MANDATORY FOR CONVERSION-ENABLED SAMPLING), BINDING 1 = STORAGE IMAGE.
                final VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
                binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .pImmutableSamplers(stack.longs(y.sampler));
                binds.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                final VkDescriptorSetLayoutCreateInfo dlci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
                check(vkCreateDescriptorSetLayout(this.device, dlci, null, p), "create ycbcr set layout");
                y.layout = p.get(0);

                // PIPELINE LAYOUT: JUST { int width; int height; } PUSH CONSTANTS.
                final VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
                pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(YCBCR_PUSH_BYTES);
                final VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(y.layout)).pPushConstantRanges(pcr);
                check(vkCreatePipelineLayout(this.device, plci, null, p), "create ycbcr pipeline layout");
                y.pipeLayout = p.get(0);

                if (ycbcrSpirv == null) {
                    synchronized (VKEngine.class) {
                        if (ycbcrSpirv == null) ycbcrSpirv = compileSpirv(YCBCR_SHADER);
                    }
                }
                y.pipeline = this.compute(ycbcrSpirv, y.pipeLayout);

                final VkSamplerYcbcrConversionImageFormatProperties cost = VkSamplerYcbcrConversionImageFormatProperties.calloc(stack).sType$Default();
                final VkPhysicalDeviceImageFormatInfo2 image = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                        .format(key.format()).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                final int queried = vkGetPhysicalDeviceImageFormatProperties2(this.physical, image,
                        VkImageFormatProperties2.calloc(stack).sType$Default().pNext(cost.address()));
                y.perSet = queried == VK_SUCCESS ? Math.max(1, cost.combinedImageSamplerDescriptorCount()) : 1;
            } catch (final RuntimeException | LinkageError e) {
                destroy(this.device, y);
                throw e;
            }
            return y;
        }

        // A COMPUTE PIPELINE FROM CACHED SPIR-V; THE MODULE DIES RIGHT AFTER (LEGAL)
        private long compute(final byte[] spirv, final long layout) {
            try (final MemoryStack stack = stackPush()) {
                final ByteBuffer code = stack.malloc(spirv.length).put(spirv).flip();
                final VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                final LongBuffer p = stack.mallocLong(1);
                check(vkCreateShaderModule(this.device, smci, null, p), "create shader module");
                final long module = p.get(0);
                final VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                final VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
                ci.get(0).sType$Default().stage(stage).layout(layout);
                final int r = vkCreateComputePipelines(this.device, 0, ci, null, p);
                vkDestroyShaderModule(this.device, module, null);
                check(r, "create compute pipeline");
                return p.get(0);
            }
        }

        // THE SAMPLER AND CONVERSION DIE LAST: THE SET LAYOUT REFERENCES THE IMMUTABLE SAMPLER UNTIL IT IS GONE
        private static void destroy(final VkDevice device, final Ycbcr y) {
            if (y.pipeline != 0L) vkDestroyPipeline(device, y.pipeline, null);
            if (y.pipeLayout != 0L) vkDestroyPipelineLayout(device, y.pipeLayout, null);
            if (y.layout != 0L) vkDestroyDescriptorSetLayout(device, y.layout, null);
            if (y.sampler != 0L) vkDestroySampler(device, y.sampler, null);
            if (y.conv != 0L) vkDestroySamplerYcbcrConversion(device, y.conv, null);
        }
    }

    // ONE KEY'S SHARED YCBCR OBJECTS; perSet IS THE COMBINED-SAMPLER DESCRIPTOR COST OF ONE SET ON layout
    private static final class Ycbcr {
        final YcbcrKey key;
        long conv, sampler, layout, pipeLayout, pipeline;
        int perSet;
        int refs; // GUARDED BY THE KIT

        Ycbcr(final YcbcrKey key) {
            this.key = key;
        }
    }

    // WHAT A YCBCR CONVERSION IS BUILT FROM: MULTIPLANAR FORMAT, MODEL, RANGE, CHROMA OFFSETS AND FILTER
    private record YcbcrKey(int format, int model, int range, int x, int y, int filter) {}

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
    private record Stage(long buffer, long memory, long mapped, long size) {}

    // A HOST POINTER IMPORTED AS A VkBuffer (ZERO-COPY COPY SOURCE).
    private record Imported(long memory, long buffer, long size) {}

    // AN IMAGE + ITS BACKING MEMORY + ITS VIEW.
    private record Img(long image, long memory, long view) {}

    // PER-PLANE GPU IMAGE DESCRIPTOR FOR THE CURRENT FORMAT.
    private static final class PlaneSpec {
        int format, texelBytes, w, h;
    }

}
