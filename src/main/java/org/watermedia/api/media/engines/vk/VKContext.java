package org.watermedia.api.media.engines.vk;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;

/**
 * Bridge a Vulkan consumer implements to lend {@link org.watermedia.api.media.engines.VKEngine}
 * the device it renders with.
 * <p>
 * The engine never creates or owns a Vulkan device — it borrows the consumer's
 * {@link VkInstance}/{@link VkDevice} so the {@code VkImageView} it produces is sampleable by the
 * consumer's own pipeline. This mirrors how {@code GLEngine} borrows the host's GL context through
 * callbacks: ownership stays with the consumer, and the engine must never destroy any object
 * returned here. Every object must outlive every {@code VKEngine} built over this context.
 */
public interface VKContext {

    /** The Vulkan instance {@link #device()} was created from. */
    VkInstance instance();

    /** The physical device backing {@link #device()}. */
    VkPhysicalDevice physicalDevice();

    /** The logical device the engine uploads and converts through. */
    VkDevice device();

    /**
     * A queue the engine submits its transfer and compute work to. It may be shared with the
     * consumer's render queue; in that case {@link #queueLock()} serializes the submissions.
     */
    VkQueue queue();

    /** Queue-family index of {@link #queue()} — the engine creates its command pool on it. */
    int queueFamily();

    /**
     * Monitor that serializes submissions to {@link #queue()}. Vulkan requires queue submission to
     * be externally synchronized, so the engine holds this lock around every {@code vkQueueSubmit};
     * a consumer sharing the queue must hold the same lock around its own submits and presents.
     */
    Object queueLock();

    /** Memory properties of {@link #physicalDevice()}, used to pick memory types for buffers and images. */
    VkPhysicalDeviceMemoryProperties memoryProperties();

    /**
     * Whether {@code VK_EXT_external_memory_host} is enabled on {@link #device()}, letting the engine
     * import the decoder's host buffers directly (zero-copy) instead of staging a copy through
     * device-local memory.
     */
    boolean hostImportSupported();

    /**
     * Required base-address and size alignment for host-pointer imports
     * ({@code minImportedHostPointerAlignment}), or 0 when {@link #hostImportSupported()} is false.
     */
    long minImportedHostPointerAlignment();

    /**
     * Whether the {@code samplerYcbcrConversion} feature (Vulkan 1.1) was enabled on
     * {@link #device()}. When true the engine may convert multi-planar YUV content through a
     * {@code VkSamplerYcbcrConversion} immutable sampler instead of its arithmetic compute shader.
     */
    boolean ycbcrSampler();

    /**
     * Schedules a destruction callback to run once the consumer's in-flight frames that might still
     * reference the engine's resources have completed. The engine calls this from {@code release()}
     * instead of destroying its images/views immediately, so a {@code VkImageView} is never destroyed
     * while a render command buffer still samples it. The callback runs on the consumer's render
     * thread; the consumer must also run any still-pending callbacks when it tears down the device.
     */
    void retire(Runnable destroy);
}
