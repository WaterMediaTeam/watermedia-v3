package org.watermedia.api.media;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.YuvFrames;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAlignedAlloc;
import static org.lwjgl.system.MemoryUtil.memAlignedFree;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/**
 * Verifies {@link VKEngine} conversions on a headless Vulkan 1.1 device, once with every feature the device offers
 * (YCbCr sampler, host import) and once forced onto the compute shader and staging copies: odd-sized planar and
 * packed frames, BT.601 and BT.709 matrices, full and limited range, chroma siting, PQ mapping and ragged strides,
 * each read back through the RGBA view {@link VKEngine#texture()} publishes. Validation-layer errors fail the test
 * when the Khronos layer is installed. Skipped without a Vulkan loader or a 1.1 device.
 */
@DisplayName("VKEngine")
public class VKEngineTest {
    private static final long FRAME_TIMEOUT_NS = 5_000_000_000L;
    private static final int READBACK_PUSH_BYTES = 8;
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    // STANDARD LUMA WEIGHTS, WRITTEN OUT SO A WRONG MATRIX CONSTANT CANNOT HIDE BEHIND THE SAME CONSTANT HERE
    private static final double KR_601 = 0.299, KB_601 = 0.114, KR_709 = 0.2126, KB_709 = 0.0722;
    private static final Colorimetry BT601_LIMITED = new Colorimetry(Colorimetry.Matrix.BT601, false, true, false, Colorimetry.Transfer.SDR, false);

    // SAMPLES THE PUBLISHED VIEW THE WAY A RENDERER DOES AND PACKS EACH TEXEL INTO A HOST-VISIBLE BUFFER; A COPY WOULD NEED
    // TRANSFER_SRC USAGE, WHICH THE ENGINE'S MANAGED IMAGES DO NOT CARRY
    private static final String READBACK_GLSL = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D frame;
            layout(binding = 1, std430) writeonly buffer Texels { uint texels[]; };
            layout(push_constant) uniform PC { int width; int height; } pc;

            void main() {
                ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
                if (gid.x >= pc.width || gid.y >= pc.height) return;
                texels[gid.y * pc.width + gid.x] = packUnorm4x8(texelFetch(frame, gid, 0));
            }
            """;

    // ONE QUEUE SHARED BY THE ENGINES AND THE READBACK, SERIALIZED LIKE A RENDERER'S
    private static final Object QUEUE_LOCK = new Object();
    // DESTRUCTION CALLBACKS THE ENGINES HAND TO retire(), RUN ONCE THE DEVICE IS IDLE
    private static final List<Runnable> RETIRED = new ArrayList<>();
    // ERRORS THE VALIDATION LAYER REPORTED SINCE THE LAST CHECK
    private static final List<String> ERRORS = Collections.synchronizedList(new ArrayList<>());
    // ALIGNED PLANE COPIES AN ENGINE MAY HAVE IMPORTED; FREED ONLY AFTER ITS RELEASE
    private static final List<ByteBuffer> ALIGNED = new ArrayList<>();

    private static VkInstance instance;
    private static VkDebugUtilsMessengerCallbackEXT callback;
    private static long messenger;
    private static VkPhysicalDevice physical;
    private static VkDevice device;
    private static VkQueue queue;
    private static int family;
    private static VkPhysicalDeviceMemoryProperties memory;
    private static boolean ycbcr, hostImport, validation;
    private static long alignment;
    private static String name;

    // READBACK OBJECTS
    private static VkCommandBuffer cmd;
    private static long cmdPool, fence, sampler, setLayout, descPool, descSet, pipeLayout, pipeline;

    @BeforeAll
    static void open() {
        MediaBootstrap.client();
        try (final MemoryStack stack = stackPush()) {
            if (VK.getInstanceVersionSupported() < VK_API_VERSION_1_1) throw new IllegalStateException("the Vulkan loader predates 1.1");
            // INSTANCE: KHRONOS VALIDATION AND ITS ERROR MESSENGER WHEN INSTALLED, PORTABILITY ENUMERATION FOR MoltenVK.
            // EXTENSION LISTS GO ON THE HEAP: A DRIVER CAN ADVERTISE MORE THAN THE DEFAULT 64 KiB STACK HOLDS
            final IntBuffer count = stack.mallocInt(1);
            check(vkEnumerateInstanceLayerProperties(count, null), "count layers");
            final VkLayerProperties.Buffer layers = VkLayerProperties.create(count.get(0));
            check(vkEnumerateInstanceLayerProperties(count, layers), "enumerate layers");
            for (final VkLayerProperties layer: layers) validation |= VALIDATION_LAYER.equals(layer.layerNameString());
            check(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null), "count instance extensions");
            final VkExtensionProperties.Buffer instanceExtensions = VkExtensionProperties.create(count.get(0));
            check(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, instanceExtensions), "enumerate instance extensions");
            boolean debug = false, portability = false;
            for (final VkExtensionProperties extension: instanceExtensions) {
                debug |= VK_EXT_DEBUG_UTILS_EXTENSION_NAME.equals(extension.extensionNameString());
                portability |= VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME.equals(extension.extensionNameString());
            }
            debug &= validation;
            final PointerBuffer instanceEnabled = stack.mallocPointer(2);
            if (debug) instanceEnabled.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            if (portability) instanceEnabled.put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));
            instanceEnabled.flip();
            final VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .flags(portability ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("VKEngineTest")).apiVersion(VK_API_VERSION_1_1))
                    .ppEnabledLayerNames(validation ? stack.pointers(stack.UTF8(VALIDATION_LAYER)) : null)
                    .ppEnabledExtensionNames(instanceEnabled);
            final PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, handle), "create instance");
            instance = new VkInstance(handle.get(0), ici);
            final LongBuffer p = stack.mallocLong(1);
            if (debug) {
                callback = VkDebugUtilsMessengerCallbackEXT.create((final int severity, final int types, final long data, final long user) -> {
                    ERRORS.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                    return VK_FALSE;
                });
                check(vkCreateDebugUtilsMessengerEXT(instance, VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(callback), null, p), "create debug messenger");
                messenger = p.get(0);
            }

            // THE FIRST 1.1 DEVICE WITH A GRAPHICS AND COMPUTE FAMILY: THE ENGINE'S BARRIERS NAME THE FRAGMENT STAGE, WHICH A
            // COMPUTE-ONLY QUEUE REJECTS, SO IT GETS THE KIND OF QUEUE A RENDERER LENDS
            check(vkEnumeratePhysicalDevices(instance, count, null), "count devices");
            final PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate devices");
            final VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            final int queueFlags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
            for (int i = 0; i < devices.capacity() && physical == null; i++) {
                final VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                vkGetPhysicalDeviceProperties(candidate, props);
                if (props.apiVersion() < VK_API_VERSION_1_1) continue;
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                final VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int f = 0; f < families.capacity() && physical == null; f++) {
                    if ((families.get(f).queueFlags() & queueFlags) != queueFlags) continue;
                    physical = candidate;
                    family = f;
                    name = props.deviceNameString();
                }
            }
            if (physical == null) throw new IllegalStateException("no Vulkan 1.1 device with a graphics and compute queue");

            // OPTIONAL FEATURES THE ENGINE CAN USE: THE YCBCR SAMPLER AND ZERO-COPY HOST IMPORT
            final VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures = VkPhysicalDeviceSamplerYcbcrConversionFeatures.calloc(stack).sType$Default();
            vkGetPhysicalDeviceFeatures2(physical, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(ycbcrFeatures));
            ycbcr = ycbcrFeatures.samplerYcbcrConversion();
            check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, null), "count device extensions");
            final VkExtensionProperties.Buffer deviceExtensions = VkExtensionProperties.create(count.get(0));
            check(vkEnumerateDeviceExtensionProperties(physical, (ByteBuffer) null, count, deviceExtensions), "enumerate device extensions");
            boolean subset = false;
            for (final VkExtensionProperties extension: deviceExtensions) {
                hostImport |= VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME.equals(extension.extensionNameString());
                subset |= VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME.equals(extension.extensionNameString());
            }
            if (hostImport) {
                final VkPhysicalDeviceExternalMemoryHostPropertiesEXT host = VkPhysicalDeviceExternalMemoryHostPropertiesEXT.calloc(stack).sType$Default();
                vkGetPhysicalDeviceProperties2(physical, VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(host.address()));
                alignment = host.minImportedHostPointerAlignment();
            }
            final VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1.0f));
            final PointerBuffer deviceEnabled = stack.mallocPointer(2);
            if (hostImport) deviceEnabled.put(stack.UTF8(VK_EXT_EXTERNAL_MEMORY_HOST_EXTENSION_NAME));
            if (subset) deviceEnabled.put(stack.UTF8(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME));
            deviceEnabled.flip();
            final VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                    .pNext(VkPhysicalDeviceSamplerYcbcrConversionFeatures.calloc(stack).sType$Default().samplerYcbcrConversion(ycbcr))
                    .pQueueCreateInfos(queues).ppEnabledExtensionNames(deviceEnabled);
            check(vkCreateDevice(physical, dci, null, handle), "create device");
            device = new VkDevice(handle.get(0), physical, dci);
            vkGetDeviceQueue(device, family, 0, handle);
            queue = new VkQueue(handle.get(0), device);
            memory = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physical, memory);

            // READBACK: ONE COMMAND BUFFER AND FENCE, A NEAREST SAMPLER AND THE PACKING PIPELINE
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, p), "create command pool");
            cmdPool = p.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), handle), "allocate command buffer");
            cmd = new VkCommandBuffer(handle.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, p), "create fence");
            fence = p.get(0);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, p), "create sampler");
            sampler = p.get(0);
            final VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, p), "create set layout");
            setLayout = p.get(0);
            final VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, p), "create descriptor pool");
            descPool = p.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descPool).pSetLayouts(stack.longs(setLayout)), p), "allocate descriptor set");
            descSet = p.get(0);
            final VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(READBACK_PUSH_BYTES);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, p), "create pipeline layout");
            pipeLayout = p.get(0);
            // SPIR-V FROM shaderc, THE COMPILER THE ENGINE ITSELF USES
            final long compiler = shaderc_compiler_initialize();
            if (compiler == NULL) throw new IllegalStateException("shaderc did not initialize");
            final long result = shaderc_compile_into_spv(compiler, READBACK_GLSL, shaderc_compute_shader, "readback.comp", "main", NULL);
            try {
                if (result == NULL || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                    throw new IllegalStateException("the readback shader did not compile: " + (result == NULL ? "no result" : shaderc_result_get_error_message(result)));
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(shaderc_result_get_bytes(result)), null, p), "create shader module");
            } finally {
                if (result != NULL) shaderc_result_release(result);
                shaderc_compiler_release(compiler);
            }
            final long module = p.get(0);
            final VkComputePipelineCreateInfo.Buffer pipelines = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelines.get(0).sType$Default().layout(pipeLayout)
                    .stage(s -> s.sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
            final int created = vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelines, null, p);
            vkDestroyShaderModule(device, module, null);
            check(created, "create readback pipeline");
            pipeline = p.get(0);
        } catch (final RuntimeException | LinkageError e) {
            close();
            assumeTrue(false, "No usable Vulkan 1.1 device — skipping: " + e);
        } catch (final OutOfMemoryError e) {
            // LWJGL REPORTS AN EXHAUSTED THREAD STACK AS AN OOM, WHICH ABORTS THE WHOLE TEST RUN: new VkInstance LISTS EVERY
            // DEVICE EXTENSION ON THAT STACK, AND SOME DRIVERS ADVERTISE MORE THAN ITS DEFAULT 64 KiB HOLD
            close();
            throw new AssertionError("Vulkan is present but the LWJGL stack cannot list its extensions; raise org.lwjgl.system.stackSize", e);
        }
        System.out.println("[VKEngineTest] " + name + ": samplerYcbcrConversion=" + ycbcr + ", hostImport=" + hostImport
                + " (alignment " + alignment + "), validation layer " + (validation ? "on" : "not installed"));
    }

    // UNWINDS WHATEVER open() BUILT, NEWEST FIRST; THE DEVICE DRAINS AND RUNS THE RETIRED CALLBACKS BEFORE IT DIES
    @AfterAll
    static void close() {
        if (device != null) {
            idle();
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, null);
            if (pipeLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipeLayout, null);
            if (descPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descPool, null);
            if (setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (sampler != VK_NULL_HANDLE) vkDestroySampler(device, sampler, null);
            if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, null);
            if (cmdPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, cmdPool, null);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (memory != null) memory.free();
        memory = null;
        if (messenger != VK_NULL_HANDLE) vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        messenger = VK_NULL_HANDLE;
        if (instance != null) vkDestroyInstance(instance, null);
        instance = null;
        if (callback != null) callback.free();
        callback = null;
    }

    @AfterEach
    void validation() {
        final List<String> errors = List.copyOf(ERRORS);
        ERRORS.clear();
        assertTrue(errors.isEmpty(), "Vulkan validation errors: " + errors);
    }

    // THE TEST DEVICE AS A RENDERER LENDS IT: DEVICE OFFERS EVERY FEATURE IT HAS, FALLBACK HIDES THE YCBCR SAMPLER AND HOST
    // IMPORT SO EVERY FRAME TAKES THE COMPUTE SHADER AND STAGING COPIES
    enum Features implements VKContext {
        DEVICE, FALLBACK;

        @Override public VkInstance vkInstance() { return instance; }
        @Override public VkPhysicalDevice physicalDevice() { return physical; }
        @Override public VkDevice vkDevice() { return device; }
        @Override public VkQueue queue() { return queue; }
        @Override public int queueFamily() { return family; }
        @Override public Object queueLock() { return QUEUE_LOCK; }
        @Override public VkPhysicalDeviceMemoryProperties memoryProperties() { return memory; }
        @Override public boolean hostImportSupported() { return this == DEVICE && hostImport; }
        @Override public long minImportedHostPointerAlignment() { return this.hostImportSupported() ? alignment : 0L; }
        @Override public boolean ycbcrSampler() { return this == DEVICE && ycbcr; }
        @Override public void retire(final Runnable destroy) { RETIRED.add(destroy); }
    }

    @Nested
    @DisplayName("Planar frames")
    class Planar {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("An odd-width YUV420P frame keeps its last chroma column, with padded planes ending on a tight row")
        void oddWidthKeepsLastChromaColumn(final Features features) {
            final int w = 853;
            final int h = 3;
            final int cw = 427; // FFMPEG ROUNDS 4:2:0 CHROMA UP: A 853x3 FRAME CARRIES 427x2 CHROMA SAMPLES
            final int ch = 2;
            // Y AND U ROWS ARE PADDED BUT EACH PLANE STOPS RIGHT AFTER ITS LAST ROW; U MARKS ONLY ITS LAST COLUMN (STRONG BLUE)
            final ByteBuffer y = YuvFrames.plane(864, w, h, (row, col) -> 128);
            final ByteBuffer u = YuvFrames.plane(448, cw, ch, (row, col) -> col == cw - 1 ? 255 : 128);
            final ByteBuffer v = YuvFrames.plane(0, cw, ch, (row, col) -> 128);
            final VKEngine engine = MediaAPI.vkEngine(features);
            try {
                assertTrue(engine.format(PixelFormat.YUV420P, w, h, 8, BT601_LIMITED));
                final ByteBuffer rgba = frame(engine, new ByteBuffer[] { y, u, v }, new int[] { 864, 448, 0 });
                final int gray = YuvFrames.rgb(KR_601, KB_601, false, 8, 128, 128, 128)[2];
                final int first = rgba.get(2) & 0xFF;
                final int middle = rgba.get(400 * 4 + 2) & 0xFF;
                final int last = rgba.get((w - 1) * 4 + 2) & 0xFF;
                assertTrue(Math.abs(first - gray) <= 3, "the minimum-size planes were accepted and converted: " + first + " vs " + gray);
                assertTrue(Math.abs(middle - first) <= 3, "neutral chroma stays neutral: " + first + " vs " + middle);
                assertTrue(last > first + 80, "the last pixel samples the last chroma column: blue " + last + " vs " + first);
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("A colored YUV420P, NV12, NV21 and YUVA420P frame shows the BT.601 and BT.709 colors of its samples")
        void matrices(final Features features) {
            final int w = 64;
            final int h = 32;
            final ByteBuffer luma = YuvFrames.plane(0, w, h, (row, col) -> 120);
            final ByteBuffer cb = YuvFrames.plane(0, w / 2, h / 2, (row, col) -> 90);
            final ByteBuffer cr = YuvFrames.plane(0, w / 2, h / 2, (row, col) -> 200);
            final ByteBuffer alpha = YuvFrames.plane(0, w, h, (row, col) -> 77);
            final ByteBuffer uv = YuvFrames.plane(0, w, h / 2, (row, col) -> col % 2 == 0 ? 90 : 200);
            final ByteBuffer vu = YuvFrames.plane(0, w, h / 2, (row, col) -> col % 2 == 0 ? 200 : 90);
            final VKEngine engine = MediaAPI.vkEngine(features);
            try {
                for (final PixelFormat format: new PixelFormat[] { PixelFormat.YUV420P, PixelFormat.NV12, PixelFormat.NV21, PixelFormat.YUVA420P }) {
                    final ByteBuffer[] planes = switch (format) {
                        case NV12 -> new ByteBuffer[] { luma, uv };
                        case NV21 -> new ByteBuffer[] { luma, vu };
                        case YUVA420P -> new ByteBuffer[] { luma, cb, cr, alpha };
                        default -> new ByteBuffer[] { luma, cb, cr };
                    };
                    final int a = format == PixelFormat.YUVA420P ? 77 : 255;
                    assertTrue(engine.format(format, w, h, 8, BT601_LIMITED));
                    final int[] bt601 = YuvFrames.rgb(KR_601, KB_601, false, 8, 120, 90, 200);
                    assertUniform(frame(engine, planes, new int[planes.length]), new int[] { bt601[0], bt601[1], bt601[2], a }, 3, format + " BT.601");
                    assertTrue(engine.format(format, w, h, 8, new Colorimetry(Colorimetry.Matrix.BT709, false, true, false, Colorimetry.Transfer.SDR, false)));
                    final int[] bt709 = YuvFrames.rgb(KR_709, KB_709, false, 8, 120, 90, 200);
                    assertUniform(frame(engine, planes, new int[planes.length]), new int[] { bt709[0], bt709[1], bt709[2], a }, 3, format + " BT.709");
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("Luma 16 with neutral chroma shows 16 in full range and black in limited range")
        void range(final Features features) {
            final ByteBuffer[] planes = {
                    YuvFrames.plane(0, 16, 16, (row, col) -> 16),
                    YuvFrames.plane(0, 8, 8, (row, col) -> 128),
                    YuvFrames.plane(0, 8, 8, (row, col) -> 128)
            };
            final VKEngine engine = MediaAPI.vkEngine(features);
            try {
                for (final boolean full: new boolean[] { true, false }) {
                    assertTrue(engine.format(PixelFormat.YUV420P, 16, 16, 8, new Colorimetry(Colorimetry.Matrix.BT601, full, true, false, Colorimetry.Transfer.SDR, false)));
                    final int gray = YuvFrames.rgb(KR_601, KB_601, full, 8, 16, 128, 128)[0];
                    assertEquals(full ? 16 : 0, gray, "the reference itself");
                    assertUniform(frame(engine, planes, new int[3]), new int[] { gray, gray, gray, 255 }, 2, full ? "full range" : "limited range");
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("Chroma lands on the even luma columns when co-sited and between them when centered")
        void chromaSiting(final Features features) {
            // Cb ALTERNATES 128/200 PER CHROMA COLUMN: CO-SITED, PIXEL 2 SITS ON COLUMN 1 (200); CENTERED, COLUMN 1 SITS BETWEEN
            // PIXELS 2 AND 3, SO PIXEL 2 BLENDS 3/4 OF IT WITH 1/4 OF COLUMN 0 (182)
            final ByteBuffer[] planes = {
                    YuvFrames.plane(0, 8, 2, (row, col) -> 60),
                    YuvFrames.plane(0, 4, 1, (row, col) -> col % 2 == 1 ? 200 : 128),
                    YuvFrames.plane(0, 4, 1, (row, col) -> 128)
            };
            final VKEngine engine = MediaAPI.vkEngine(features);
            try {
                for (final boolean cosited: new boolean[] { true, false }) {
                    assertTrue(engine.format(PixelFormat.YUV420P, 8, 2, 8, new Colorimetry(Colorimetry.Matrix.BT601, false, cosited, false, Colorimetry.Transfer.SDR, false)));
                    final ByteBuffer rgba = frame(engine, planes, new int[3]);
                    // A YCBCR SAMPLER WITHOUT LINEAR CHROMA FILTERING TAKES THE NEAREST COLUMN INSTEAD OF BLENDING
                    final boolean nearest = (int) field(engine, "ycbcrFmt") != 0 && (int) field(engine, "ycbcrFilter") == VK_FILTER_NEAREST;
                    final int blue = YuvFrames.rgb(KR_601, KB_601, false, 8, 60, cosited || nearest ? 200 : 182, 128)[2];
                    final int shown = rgba.get(2 * 4 + 2) & 0xFF;
                    assertTrue(Math.abs(shown - blue) <= 3, (cosited ? "co-sited" : "centered") + " chroma: pixel 2 blue " + shown + " vs " + blue);
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("A 10 or 8-bit PQ gray shows its SDR level at 100 nits and peak white at 1000 nits")
        void pq(final Features features) {
            // THE 8-BIT FRAME QUALIFIES FOR THE YCBCR SAMPLER BY SIZE AND DEPTH, WHICH HAS NO PQ CURVE, SO IT MUST STAY ON COMPUTE
            final VKEngine engine = MediaAPI.vkEngine(features);
            try {
                for (final int bits: new int[] { 10, 8 }) {
                    for (final int nits: new int[] { 100, 1_000 }) {
                        final int unit = 1 << (bits - 8);
                        final int code = (int) Math.round(16 * unit + YuvFrames.pq(nits) * 219 * unit);
                        final ByteBuffer[] planes = bits == 8
                                ? new ByteBuffer[] { YuvFrames.plane(0, 16, 16, (row, col) -> code), YuvFrames.plane(0, 8, 8, (row, col) -> 128),
                                        YuvFrames.plane(0, 8, 8, (row, col) -> 128) }
                                : new ByteBuffer[] { YuvFrames.plane16(16, 16, (row, col) -> code), YuvFrames.plane16(8, 8, (row, col) -> 512),
                                        YuvFrames.plane16(8, 8, (row, col) -> 512) };
                        assumeTrue(engine.format(PixelFormat.YUV420P, 16, 16, bits, new Colorimetry(Colorimetry.Matrix.BT2020, false, true, false, Colorimetry.Transfer.PQ, true)),
                                "the device cannot sample " + bits + "-bit planes");
                        final int gray = nits == 100 ? YuvFrames.hdrGray(nits) : 255;
                        assertUniform(frame(engine, planes, new int[3]), new int[] { gray, gray, gray, 255 }, 3, bits + "-bit " + nits + " nits");
                    }
                }
            } finally {
                release(engine);
            }
        }
    }

    @Nested
    @DisplayName("Packed frames")
    class Packed {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("An odd-width YUYV or UYVY frame gives every pixel the luma of its own pair")
        void oddWidthPackedLuma(final Features features) {
            // FIVE PIXELS ARE THREE PAIRS; THE LAST SECOND LUMA IS PADDING NO PIXEL SHOWS
            final int[] luma = { 50, 100, 150, 200, 250 };
            final VKEngine engine = MediaAPI.vkEngine(features);
            try {
                for (final PixelFormat format: new PixelFormat[] { PixelFormat.YUYV, PixelFormat.UYVY }) {
                    // YUYV CARRIES LUMA ON THE EVEN BYTES, UYVY ON THE ODD ONES
                    final int lumaByte = format == PixelFormat.YUYV ? 0 : 1;
                    final ByteBuffer packed = YuvFrames.plane(0, 12, 1, (row, col) -> col % 2 != lumaByte ? 128 : col / 2 < luma.length ? luma[col / 2] : 0);
                    assertTrue(engine.format(format, 5, 1, 8, BT601_LIMITED));
                    final ByteBuffer rgba = frame(engine, new ByteBuffer[] { packed }, new int[] { 0 });
                    for (int x = 0; x < luma.length; x++) {
                        final int gray = YuvFrames.rgb(KR_601, KB_601, false, 8, luma[x], 128, 128)[0];
                        final int shown = rgba.get(x * 4) & 0xFF;
                        assertTrue(Math.abs(shown - gray) <= 3, format + " pixel " + x + " shows " + shown + " instead of " + gray);
                    }
                }
            } finally {
                release(engine);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Features.class)
        @DisplayName("A YUYV stride that is not a whole number of texels drops the frame and keeps the last one")
        void raggedStrideDropsTheFrame(final Features features) {
            // FOUR PIXELS ARE TWO 4-BYTE TEXELS; A 10-BYTE STRIDE IS NO WHOLE ROW LENGTH IN TEXELS, WHICH A BUFFER-TO-IMAGE COPY
            // CANNOT EXPRESS, SO THE ENGINE DROPS IT INSTEAD OF SHEARING THE ROWS
            final VKEngine engine = MediaAPI.vkEngine(features);
            try {
                assertTrue(engine.format(PixelFormat.YUYV, 4, 2, 8, BT601_LIMITED));
                final ByteBuffer shown = frame(engine, new ByteBuffer[] { YuvFrames.plane(0, 8, 2, (row, col) -> col % 2 == 1 ? 128 : 60) }, new int[] { 0 });
                final long view = engine.texture();
                engine.upload(new ByteBuffer[] { YuvFrames.plane(10, 8, 2, (row, col) -> col % 2 == 1 ? 128 : 200) }, new int[] { 10 });
                idle();
                assertEquals(view, engine.texture(), "no new frame was published");
                assertEquals(shown, readback(view, 4, 2), "the last frame stays on screen");
            } finally {
                release(engine);
            }
        }
    }

    // UTILITY

    // UPLOADS ONE FRAME AS A PRODUCER DOES (ALIGNED COPIES WHEN THE ENGINE CAN IMPORT THEM), WAITS FOR texture() TO PUBLISH
    // IT AND READS THE PUBLISHED VIEW BACK AS RGBA BYTES, ROW 0 FIRST
    private static ByteBuffer frame(final VKEngine engine, final ByteBuffer[] planes, final int[] strides) {
        final long before = engine.texture();
        final int align = engine.alignment();
        final ByteBuffer[] sent = planes.clone();
        for (int i = 0; i < planes.length && align > 0; i++) {
            sent[i] = memAlignedAlloc(align, (planes[i].remaining() + align - 1) / align * align);
            memCopy(planes[i], sent[i]);
            ALIGNED.add(sent[i]);
        }
        engine.upload(sent, strides);
        final long deadline = System.nanoTime() + FRAME_TIMEOUT_NS;
        long view = before;
        while (view == before && System.nanoTime() < deadline) view = engine.texture();
        assertNotEquals(before, view, "the frame was published within the timeout");

        final String route = ((int) field(engine, "ycbcrFmt") != 0 ? "YCbCr sampler" : "compute") + ", "
                + (((Map<?, ?>) field(engine, "importCache")).isEmpty() ? "staging" : "host import");
        final Object features = field(engine, "ctx");
        System.out.println("[VKEngineTest] " + features + " " + engine.format() + " " + engine.width() + "x" + engine.height()
                + " " + engine.bits() + "-bit " + engine.color + " -> " + route);
        if (features == Features.FALLBACK) assertEquals("compute, staging", route, "the fallback context forces the compute shader and staging copies");
        return readback(view, engine.width(), engine.height());
    }

    // SAMPLES THE VIEW WITH THE READBACK PIPELINE INTO A HOST-VISIBLE BUFFER AND RETURNS ITS RGBA BYTES
    private static ByteBuffer readback(final long view, final int width, final int height) {
        final int bytes = width * height * 4;
        try (final MemoryStack stack = stackPush()) {
            final LongBuffer p = stack.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, p), "create readback buffer");
            final long buffer = p.get(0);
            long mem = VK_NULL_HANDLE;
            try {
                final VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, req);
                final int wanted = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                int type = -1;
                for (int i = 0; i < memory.memoryTypeCount() && type < 0; i++)
                    if ((req.memoryTypeBits() & 1 << i) != 0 && (memory.memoryTypes(i).propertyFlags() & wanted) == wanted) type = i;
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(req.size()).memoryTypeIndex(type), null, p), "allocate readback memory");
                mem = p.get(0);
                check(vkBindBufferMemory(device, buffer, mem, 0), "bind readback memory");
                final PointerBuffer mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, mem, 0, bytes, 0, mapped), "map readback memory");

                // BINDING 0 = THE PUBLISHED VIEW IN THE LAYOUT THE ENGINE HANDS OVER, BINDING 1 = THE HOST-VISIBLE BUFFER
                final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                writes.get(0).sType$Default().dstSet(descSet).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                writes.get(1).sType$Default().dstSet(descSet).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(0).range(VK_WHOLE_SIZE));
                vkUpdateDescriptorSets(device, writes, null);

                check(vkResetCommandBuffer(cmd, 0), "reset readback commands");
                check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin readback");
                // THE ENGINE HANDED ITS WRITES TO FRAGMENT SAMPLING; THIS MAKES THEM VISIBLE TO THE READBACK COMPUTE STAGE TOO
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                        VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT), null, null);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeLayout, 0, stack.longs(descSet), null);
                vkCmdPushConstants(cmd, pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(width, height));
                vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                        VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT), null, null);
                check(vkEndCommandBuffer(cmd), "end readback");
                synchronized (QUEUE_LOCK) {
                    check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)), fence), "submit readback");
                }
                check(vkWaitForFences(device, fence, true, FRAME_TIMEOUT_NS), "wait for the readback");
                check(vkResetFences(device, fence), "reset readback fence");
                final ByteBuffer rgba = BufferUtils.createByteBuffer(bytes);
                memCopy(mapped.get(0), memAddress(rgba), bytes);
                return rgba;
            } finally {
                vkDestroyBuffer(device, buffer, null);
                if (mem != VK_NULL_HANDLE) vkFreeMemory(device, mem, null); // FREEING ALSO UNMAPS
            }
        }
    }

    // RELEASES AN ENGINE LIKE ITS RENDERER: RETIRED OBJECTS DIE ONCE THE DEVICE IS IDLE, AND ONLY THEN THE IMPORTED PLANES
    private static void release(final VKEngine engine) {
        engine.release();
        idle();
        for (final ByteBuffer plane: ALIGNED) memAlignedFree(plane);
        ALIGNED.clear();
    }

    // WAITS FOR EVERY SUBMISSION ON THE SHARED QUEUE, THEN RUNS THE DESTRUCTION CALLBACKS THE ENGINES RETIRED
    private static void idle() {
        synchronized (QUEUE_LOCK) {
            check(vkDeviceWaitIdle(device), "wait for the device");
        }
        for (final Runnable destroy: RETIRED) destroy.run();
        RETIRED.clear();
    }

    // ASSERTS EVERY PIXEL SHOWS rgba (RED, GREEN, BLUE, ALPHA) WITHIN tolerance
    private static void assertUniform(final ByteBuffer frame, final int[] rgba, final int tolerance, final String what) {
        for (int i = 0; i < frame.capacity(); i++) {
            final int shown = frame.get(i) & 0xFF;
            if (Math.abs(shown - rgba[i % 4]) > tolerance)
                fail(what + ": pixel " + i / 4 + " channel " + i % 4 + " shows " + shown + " instead of " + rgba[i % 4]);
        }
    }

    // READS A PRIVATE FIELD OF THE ENGINE: ITS ROUTE AND IMPORT STATE ARE NOT PART OF THE API
    private static Object field(final VKEngine engine, final String member) {
        try {
            final Field field = VKEngine.class.getDeclaredField(member);
            field.setAccessible(true);
            return field.get(engine);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("VKEngine has no field " + member, e);
        }
    }

    private static void check(final int result, final String op) {
        if (result != VK_SUCCESS) throw new IllegalStateException(op + " failed (VkResult " + result + ")");
    }
}
