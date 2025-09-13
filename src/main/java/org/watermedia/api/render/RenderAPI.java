package org.watermedia.api.render;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.watermedia.WaterMedia;
import org.watermedia.api.WaterMediaAPI;

import java.nio.ByteBuffer;

import static org.watermedia.WaterMedia.LOGGER;

public class RenderAPI extends WaterMediaAPI {
    private static final Marker IT = MarkerManager.getMarker(RenderAPI.class.getSimpleName());
    private static boolean CLIENT_SIDE;
    public static final int NONE = 0;
    public static final long NULL = 0L;


    public static int createTexture() {
        if (!CLIENT_SIDE) {
            WaterMedia.throwIllegalEnvironment(RenderAPI.class);
        }
        final int tex = GL11.glGenTextures();
        // Bind
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);

        //Setup wrap mode
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        //Setup texture scaling filtering (no dark textures)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        // Unbind
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, NONE);

        return tex;
    }

    public static void releaseTexture(final int texture) {
        if (!CLIENT_SIDE) {
            WaterMedia.throwIllegalEnvironment(RenderAPI.class);
        }
        GL11.glDeleteTextures(texture);
    }

    public static void releaseTexture(final int... textures) {
        if (!CLIENT_SIDE) {
            WaterMedia.throwIllegalEnvironment(RenderAPI.class);
        }
        GL11.glDeleteTextures(textures);
    }

    public static void uploadTexture(final int texture, final ByteBuffer buffers, final int width, final int height, final int format, final boolean firstFrame) {
        if (!CLIENT_SIDE) {
            WaterMedia.throwIllegalEnvironment(RenderAPI.class);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);

        if (!GL11.glIsTexture(texture)) {
            LOGGER.warn(IT, "Attempted to update a texture that is not valid: {}", texture);
            return;
        }

        if (firstFrame) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0, format, GL11.GL_UNSIGNED_BYTE, buffers);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL11.GL_UNSIGNED_BYTE, buffers);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, NONE);

    }

    @Override
    public boolean start(WaterMedia instance) throws Exception {
        // CLIENT-SIDE CHECK
        CLIENT_SIDE = instance.clientSide;
        if (!CLIENT_SIDE) {
            LOGGER.warn(IT, "Detected server-side environment, lockdown mode enabled");
            return false;
        }

        // GL CAPABILITIES TEST

        // VULKAN CAPABILITY TESTS (v3.1)

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
    public void release(WaterMedia instance) {

    }
}
