package org.watermedia.api.render.support;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

public final class DefaultGlManager implements GlManager {
    @Override public int genTexture() { return GL11.glGenTextures(); }
    @Override public void bindTexture(final int target, final int texture) { GL11.glBindTexture(target, texture); }
    @Override public void texParameter(final int target, final int name, final int param) { GL11.glTexParameteri(target, name, param); }
    @Override public void pixelStore(final int name, final int param) { GL11.glPixelStorei(name, param); }
    @Override public void delTexture(final int textureId) { GL11.glDeleteTextures(textureId); }
    @Override public void delTexture(int... textures) { GL11.glDeleteTextures(textures); }

    @Override
    public void texImage2D(final int target, final int level, final int internalFormat, final int width, final int height, final int border, final int format, final int type, final ByteBuffer pixels) {
        GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    @Override
    public void texSubImage2D(final int target, final int level, final int xOffset, final int yOffset, final int width, final int height, final int format, final int type, final ByteBuffer pixels) {
        GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }
};