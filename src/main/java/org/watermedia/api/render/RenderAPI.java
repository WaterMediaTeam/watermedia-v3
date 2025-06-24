package org.watermedia.api.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

public class RenderAPI {
    public static final int NONE = 0;
    public static final long NULL = 0L;

    public static int createTexture() {
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

    public static int[] createTextures(int count) {
        final int[] tex = new int[count];
        GL11.glGenTextures(tex);

        for (final int t: tex) {
            // Bind
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);

            //Setup wrap mode
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            //Setup texture scaling filtering (no dark textures)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

            // Unbind
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, NONE);
        }

        return tex;
    }

    public static void releaseTexture(final int texture) {
        GL11.glDeleteTextures(texture);
    }

    public static void releaseTextures(final int[] texture) {
        GL11.glDeleteTextures(texture);
    }

    public static void updateTexture(int texture, ByteBuffer[] buffers, int width, int height, int format, boolean firstFrame) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);

        if (firstFrame) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0, format, GL11.GL_UNSIGNED_BYTE, buffers[0]);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL11.GL_UNSIGNED_BYTE, buffers[0]);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, NONE);

    }
}
