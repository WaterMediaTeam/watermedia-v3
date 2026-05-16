package org.watermedia.bootstrap.app.render;

/**
 * Texture handle owned by the current renderer backend.
 */
public record TextureHandle(int id, int width, int height) {
    public static final TextureHandle NONE = new TextureHandle(-1, 0, 0);
}
