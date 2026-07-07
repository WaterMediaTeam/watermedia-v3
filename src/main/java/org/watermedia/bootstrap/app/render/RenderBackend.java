package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;
import org.watermedia.api.media.engines.GFXEngine;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Low-level renderer contract. OpenGL is just one implementation; a Vulkan
 * backend can satisfy this contract without changing UI widgets.
 */
public interface RenderBackend {

    void init();

    void cleanup();

    void configureFrameState();

    void clear(float r, float g, float b, float a);

    void viewport(int width, int height);

    void disableDepthTest();

    TextureHandle createTexture(int width, int height, ByteBuffer rgba);

    void deleteTexture(TextureHandle texture);

    void bindTexture(int textureId);

    void useProjection(Matrix4f projection);

    void draw(DrawMode mode, float[] vertices, int vertexCount, boolean textured);

    void lineWidth(float width);

    void enableClip(int x, int y, int width, int height, int canvasHeight);

    void disableClip();

    /**
     * Builds the media graphics engine that matches this backend, for a {@code MediaPlayer} to upload
     * decoded frames into. OpenGL yields a {@code GLEngine}; Vulkan a {@code VKEngine} bound to this
     * backend's own device. Keeping this here is what lets callers stay engine-agnostic.
     * @param renderThread   the thread that reads the player's texture (OpenGL needs it; Vulkan ignores it)
     * @param renderExecutor dispatches work onto the render thread (OpenGL needs it; Vulkan ignores it)
     */
    Supplier<GFXEngine> mediaEngineSupplier(Thread renderThread, Executor renderExecutor);

    /**
     * Begins a frame. OpenGL renders straight to the default framebuffer, so this is a no-op; a
     * Vulkan backend acquires the next swapchain image and begins its frame command buffer here.
     */
    default void beginFrame() {
    }

    /**
     * Ends and presents the frame. OpenGL swaps buffers in the app loop, so this is a no-op; a
     * Vulkan backend ends its render pass, submits and presents the swapchain image here.
     */
    default void present() {
    }

    /**
     * Binds a media player's texture handle for the next blit. The handle is 64-bit because a
     * Vulkan {@code VkImageView} does not fit the int texture-id path; OpenGL truncates it back to
     * its {@code GLuint} name. The image is engine-owned and already sampleable.
     * @param handle the player's {@code texture()} handle, or 0 when no frame is ready
     */
    default void bindMediaTexture(final long handle) {
        this.bindTexture((int) handle);
    }

    /**
     * Human-readable name of the GPU/renderer this backend runs on ({@code GL_RENDERER} for OpenGL,
     * the physical device name for Vulkan). Captured at init so it is safe to read from any thread.
     */
    default String deviceName() {
        return "Unknown";
    }

    /**
     * Highest API version this backend runs at as {@code "major.minor"} (the GL context version for
     * OpenGL, the physical device's supported Vulkan API version). Captured at init; empty if unknown.
     */
    default String deviceVersion() {
        return "";
    }
}
