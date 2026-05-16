package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;

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
}
