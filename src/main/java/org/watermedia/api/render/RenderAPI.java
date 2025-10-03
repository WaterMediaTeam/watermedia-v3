package org.watermedia.api.render;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;
import org.watermedia.api.render.support.DefaultGlManager;
import org.watermedia.api.render.support.GlManager;
import org.watermedia.videolan4j.VideoLan4J;

import java.nio.ByteBuffer;

public class RenderAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(RenderAPI.class.getSimpleName());
    private static GlManager GL_MANAGER;
    private static final int NONE = 0;
    private static final long NULL = 0L;

    /**
     * Override the default GL Manager implementation
     * This is useful for environments with custom GL context management, like Minecraft
     * @param manager the custom GL manager
     */
    public static void setCustomGlManager(final GlManager manager) {
        WaterMedia.checkIsClientSideOrThrow(RenderAPI.class);
        GL_MANAGER = manager;
    }

    public static int genTexture() {
        WaterMedia.checkIsClientSideOrThrow(RenderAPI.class);
        final int tex = GL11.glGenTextures();

        // Bind
        GL_MANAGER.bindTexture(GL11.GL_TEXTURE_2D, tex);

        // Setup wrap mode
        GL_MANAGER.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL_MANAGER.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // Setup texture scaling filtering (no dark textures)
        GL_MANAGER.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL_MANAGER.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        // Unbind
        GL_MANAGER.bindTexture(GL11.GL_TEXTURE_2D, NONE);

        return tex;
    }

    public static void delTexture(final int texture) {
        WaterMedia.checkIsClientSideOrThrow(RenderAPI.class);
        GL_MANAGER.delTexture(texture);
    }

    public static void delTexture(final int... textures) {
        WaterMedia.checkIsClientSideOrThrow(RenderAPI.class);
        GL_MANAGER.delTexture(textures);
    }

    public static void uploadTexture(final int texture, final ByteBuffer buffers, final int stride, final int width, final int height, final int format, final boolean firstFrame) {
        WaterMedia.checkIsClientSideOrThrow(RenderAPI.class);
        GL_MANAGER.bindTexture(GL11.GL_TEXTURE_2D, texture);

        GL_MANAGER.pixelStore(GL11.GL_UNPACK_ROW_LENGTH, stride);
        GL_MANAGER.pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_ZERO);
        GL_MANAGER.pixelStore(GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_ZERO);

        if (firstFrame) {
            GL_MANAGER.texImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0, format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffers);
        } else {
            GL_MANAGER.texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffers);
        }
    }

    @Override
    public boolean start(final WaterMedia instance) throws Exception {
        // GL MANAGER
        GL_MANAGER = new DefaultGlManager();

        // SET ALLOCATORS
        VideoLan4J.setBufferAllocator(MemoryUtil::memAlignedAlloc);
        VideoLan4J.setBufferDeallocator(MemoryUtil::memAlignedFree);

        return true;
    }

    @Override
    public boolean onlyClient() {
        return true;
    }

    @Override
    public void test() {

    }

    @Override
    public Priority priority() {
        return Priority.HIGH;
    }

    @Override
    public void release(final WaterMedia instance) {

    }
}
