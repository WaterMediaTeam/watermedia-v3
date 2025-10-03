package org.watermedia.api.render.support;

import org.lwjgl.opengl.GL11;
import org.watermedia.api.render.RenderAPI;

import java.nio.ByteBuffer;

/**
 * Custom state manager to abstract the OpenGL calls
 * @see RenderAPI for the default implementation
 *
 */
public interface GlManager {
    /**
     * Generates a new texture id
     * @see GL11#glGenTextures()
     * @return the generated texture id
     */
    int genTexture();

    /**
     * Binds a texture to a target
     * @see GL11#glBindTexture(int, int)
     * @param target the target to bind the texture to, e.g. GL11.GL_TEXTURE_2D
     * @param texture the texture id to bind
     */
    void bindTexture(int target, int texture);

    /**
     * Set texture parameters
     * @see GL11#glTexParameteri(int, int, int)
     * @param target The target to which the texture is bound, e.g. GL11.GL_TEXTURE_2D
     * @param name The symbolic name of a single-valued texture parameter, e.g. GL11.GL_TEXTURE_MIN_FILTER
     * @param param The value of name parameter, e.g. GL11.GL_LINEAR
     */
    void texParameter(int target, int name, int param);

    /**
     * Set pixel storage modes
     * @see GL11#glPixelStorei(int, int)
     * @param name The symbolic name of the parameter to be set, e.g. GL11.GL_UNPACK_ROW_LENGTH
     * @param param The value to which name is set, e.g. 0
     */
    void pixelStore(int name, int param);

    /**
     * Specify a two-dimensional texture image
     * @see GL11#glTexImage2D(int, int, int, int, int, int, int, int, java.nio.ByteBuffer)
     * @param target the target texture, e.g. GL11.GL_TEXTURE_2D
     * @param level the level-of-detail number, e.g. 0
     * @param internalformat the number of color components in the texture, e.g. GL11.GL_BGRA
     * @param width width of the texture image
     * @param height height of the texture image
     * @param border the width of the border, must be 0
     * @param format the format of the pixel data, e.g. GL12.GL_UNSIGNED_INT_8_8_8_8_REV
     * @param type the data type of the pixel data, e.g. GL11.GL_BGRA
     * @param pixels the pixel data
     */
    void texImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels);

    /**
     * Specify a sub-region of an existing two-dimensional texture image
     * @see GL11#glTexSubImage2D(int, int, int, int, int, int, int, int, java.nio.ByteBuffer)
     * @param target the target texture, e.g. GL11.GL_TEXTURE_2D
     * @param level the level-of-detail number, e.g. 0
     * @param xoffset the x offset of the sub-region
     * @param yoffset the y offset of the sub-region
     * @param width width of the sub-region
     * @param height height of the sub-region
     * @param format the format of the pixel data, e.g. GL11.GL_BGRA
     * @param type the data type of the pixel data, e.g. GL12.GL_UNSIGNED_INT_8_8_8_8_REV
     * @param pixels the pixel data
     */
    void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels);

    /**
     * Deletes a texture
     * @see GL11#glDeleteTextures(int)
     * @param textureId the texture id to delete
     */
    void delTexture(int textureId);

    /**
     * Deletes multiple textures
     * @see GL11#glDeleteTextures(int)
     * @param textureIds the texture ids to delete
     */
    void delTexture(int... textureIds);
}
