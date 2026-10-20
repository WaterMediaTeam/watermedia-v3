package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;
import org.watermedia.api.media.GFXEngine;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Low-level renderer contract implemented by the OpenGL and Vulkan backends. */
public interface RenderBackend {

    void init();

    void cleanup();

    void configureFrameState();

    void clear(float r, float g, float b, float a);

    void viewport(int width, int height);

    TextureHandle createTexture(int width, int height, ByteBuffer rgba);

    void deleteTexture(TextureHandle texture);

    void bindTexture(int textureId);

    void useProjection(Matrix4f projection);

    void draw(DrawMode mode, float[] vertices, int vertexCount, boolean textured);

    /** Returns whether {@link #softRect} draws in one pass; otherwise the engine stacks translucent fills. */
    default boolean supportsSoftRect() {
        return false;
    }

    /** Fills a rounded rectangle whose alpha fades to zero over {@code softness} pixels outside its edge. */
    default void softRect(final float x, final float y, final float w, final float h, final float radius,
                          final float r, final float g, final float b, final float a, final float softness) {
    }

    void lineWidth(float width);

    void clip(int x, int y, int width, int height, int canvasHeight);

    void clearClip();

    /** Builds the media engine of this backend ({@code GLEngine} or {@code VKEngine}); only OpenGL uses the thread and executor. */
    Supplier<GFXEngine> mediaEngineSupplier(Thread renderThread, Executor renderExecutor);

    /** Begins a frame; Vulkan acquires the next swapchain image here. */
    default void beginFrame() {
    }

    /** Ends and presents the frame. */
    default void present() {
    }

    /** Binds a player's {@code texture()} handle for the next blit; 64-bit to fit a {@code VkImageView}, 0 without a frame. */
    default void bindMediaTexture(final long handle) {
        this.bindTexture((int) handle);
    }

    /** Returns the GPU or renderer name, captured at init so any thread can read it. */
    default String deviceName() {
        return "Unknown";
    }

    /** Returns the highest API version as {@code "major.minor"}, captured at init; empty when unknown. */
    default String deviceVersion() {
        return "";
    }
}
