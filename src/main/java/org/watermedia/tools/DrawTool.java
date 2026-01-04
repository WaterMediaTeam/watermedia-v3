package org.watermedia.tools;

import java.awt.Color;

import static org.lwjgl.opengl.GL11.*;

/**
 * Utility class for OpenGL drawing operations.
 * Contains static methods for common rendering tasks to reduce code duplication.
 */
public final class DrawTool {
    
    private DrawTool() {} // Prevent instantiation
    
    // ============================================================
    // PROJECTION MANAGEMENT
    // ============================================================
    
    public static void setupOrtho(int width, int height) {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
    }
    
    public static void restoreProjection() {
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();
    }
    
    // ============================================================
    // COLOR UTILITIES
    // ============================================================
    
    public static void color(Color c) {
        glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
    }
    
    public static void color(float r, float g, float b, float a) {
        glColor4f(r, g, b, a);
    }
    
    public static void color(float r, float g, float b) {
        glColor4f(r, g, b, 1f);
    }
    
    // ============================================================
    // FILLED SHAPES
    // ============================================================
    
    /**
     * Fills a solid rectangle.
     */
    public static void fill(float x, float y, float w, float h) {
        glBegin(GL_QUADS);
        glVertex2f(x, y);
        glVertex2f(x + w, y);
        glVertex2f(x + w, y + h);
        glVertex2f(x, y + h);
        glEnd();
    }
    
    /**
     * Fills a solid rectangle with specified color.
     */
    public static void fill(float x, float y, float w, float h, Color c) {
        color(c);
        fill(x, y, w, h);
    }
    
    /**
     * Fills a solid rectangle with specified RGBA color.
     */
    public static void fill(float x, float y, float w, float h, float r, float g, float b, float a) {
        color(r, g, b, a);
        fill(x, y, w, h);
    }
    
    /**
     * Fills a horizontal gradient rectangle (left color to right color).
     */
    public static void fillGradientH(float x, float y, float w, float h, 
                                      float r1, float g1, float b1, float a1,
                                      float r2, float g2, float b2, float a2) {
        glBegin(GL_QUADS);
        glColor4f(r1, g1, b1, a1);
        glVertex2f(x, y);
        glVertex2f(x, y + h);
        glColor4f(r2, g2, b2, a2);
        glVertex2f(x + w, y + h);
        glVertex2f(x + w, y);
        glEnd();
    }
    
    /**
     * Fills a vertical gradient rectangle (top color to bottom color).
     */
    public static void fillGradientV(float x, float y, float w, float h,
                                      float r1, float g1, float b1, float a1,
                                      float r2, float g2, float b2, float a2) {
        glBegin(GL_QUADS);
        glColor4f(r1, g1, b1, a1);
        glVertex2f(x, y);
        glVertex2f(x + w, y);
        glColor4f(r2, g2, b2, a2);
        glVertex2f(x + w, y + h);
        glVertex2f(x, y + h);
        glEnd();
    }
    
    // ============================================================
    // OUTLINED SHAPES
    // ============================================================
    
    /**
     * Draws a rectangle outline.
     */
    public static void rect(float x, float y, float w, float h) {
        glBegin(GL_LINE_LOOP);
        glVertex2f(x, y);
        glVertex2f(x + w, y);
        glVertex2f(x + w, y + h);
        glVertex2f(x, y + h);
        glEnd();
    }
    
    /**
     * Draws a rectangle outline with specified color and line width.
     */
    public static void rect(float x, float y, float w, float h, Color c, float lineWidth) {
        color(c);
        glLineWidth(lineWidth);
        rect(x, y, w, h);
    }
    
    /**
     * Draws a rectangle outline with specified RGBA color and line width.
     */
    public static void rect(float x, float y, float w, float h, float r, float g, float b, float a, float lineWidth) {
        color(r, g, b, a);
        glLineWidth(lineWidth);
        rect(x, y, w, h);
    }
    
    // ============================================================
    // LINES
    // ============================================================
    
    /**
     * Draws a horizontal line.
     */
    public static void lineH(float x, float y, float length) {
        glBegin(GL_LINES);
        glVertex2f(x, y);
        glVertex2f(x + length, y);
        glEnd();
    }
    
    /**
     * Draws a horizontal line with specified color and width.
     */
    public static void lineH(float x, float y, float length, Color c, float lineWidth) {
        color(c);
        glLineWidth(lineWidth);
        lineH(x, y, length);
    }
    
    /**
     * Draws a horizontal line with specified RGBA color and width.
     */
    public static void lineH(float x, float y, float length, float r, float g, float b, float a, float lineWidth) {
        color(r, g, b, a);
        glLineWidth(lineWidth);
        lineH(x, y, length);
    }

    /**
     * Draws a vertical line.
     */
    public static void lineV(float x, float y, float length, Color c, float lineWidth) {
        color(c);
        glLineWidth(lineWidth);
        lineV(x, y, length);
    }

    /**
     * Draws a vertical line.
     */
    public static void lineV(float x, float y, float length) {
        glBegin(GL_LINES);
        glVertex2f(x, y);
        glVertex2f(x, y + length);
        glEnd();
    }

    // ============================================================
    // TEXTURED QUADS
    // ============================================================
    
    /**
     * Renders a textured quad in ortho projection coordinates.
     */
    public static void blit(float x, float y, float w, float h) {
        glBegin(GL_QUADS);
        glTexCoord2f(0, 0); glVertex2f(x, y);
        glTexCoord2f(1, 0); glVertex2f(x + w, y);
        glTexCoord2f(1, 1); glVertex2f(x + w, y + h);
        glTexCoord2f(0, 1); glVertex2f(x, y + h);
        glEnd();
    }
    
    /**
     * Renders a textured quad with custom UV coordinates.
     */
    public static void blit(float x, float y, float w, float h,
                            float u0, float v0, float u1, float v1) {
        glBegin(GL_QUADS);
        glTexCoord2f(u0, v0); glVertex2f(x, y);
        glTexCoord2f(u1, v0); glVertex2f(x + w, y);
        glTexCoord2f(u1, v1); glVertex2f(x + w, y + h);
        glTexCoord2f(u0, v1); glVertex2f(x, y + h);
        glEnd();
    }
    
    /**
     * Renders a textured quad in NDC coordinates (for video rendering).
     * UVs are flipped vertically for typical video texture orientation.
     */
    public static void blitNDC(float x1, float y1, float x2, float y2) {
        glBegin(GL_QUADS);
        glTexCoord2f(0, 1); glVertex2f(x1, y1);
        glTexCoord2f(0, 0); glVertex2f(x1, y2);
        glTexCoord2f(1, 0); glVertex2f(x2, y2);
        glTexCoord2f(1, 1); glVertex2f(x2, y1);
        glEnd();
    }

    // ============================================================
    // DIALOG RENDERING HELPERS
    // ============================================================
    
    /**
     * Renders a dialog background with border.
     * Disables and re-enables GL_TEXTURE_2D automatically.
     */
    public static void dialogBox(float x, float y, float w, float h, Color borderColor, float borderWidth) {
        glDisable(GL_TEXTURE_2D);
        
        // Background
        fill(x, y, w, h, 0, 0, 0, 0.95f);
        
        // Border
        rect(x, y, w, h, borderColor, borderWidth);
        
        glEnable(GL_TEXTURE_2D);
    }
    
    /**
     * Renders a dialog background with border using RGBA for border.
     */
    public static void dialogBox(float x, float y, float w, float h, 
                                  float r, float g, float b, float a, float borderWidth) {
        glDisable(GL_TEXTURE_2D);
        
        // Background
        fill(x, y, w, h, 0, 0, 0, 0.95f);
        
        // Border
        rect(x, y, w, h, r, g, b, a, borderWidth);
        
        glEnable(GL_TEXTURE_2D);
    }
    
    // ============================================================
    // FADE OVERLAYS
    // ============================================================
    
    /**
     * Renders a left-side fade overlay for player UI.
     */
    public static void fadeLeft(float width, float height, float fadeWidth, float alpha) {
        glBegin(GL_QUADS);
        glColor4f(0, 0, 0, alpha);
        glVertex2f(0, 0);
        glVertex2f(0, height);
        glColor4f(0, 0, 0, 0);
        glVertex2f(fadeWidth, height);
        glVertex2f(fadeWidth, 0);
        glEnd();
    }
    
    /**
     * Renders a bottom fade overlay for controls.
     */
    public static void fadeBottom(float width, float height, float fadeHeight, float alpha) {
        glBegin(GL_QUADS);
        glColor4f(0, 0, 0, 0);
        glVertex2f(0, height - fadeHeight);
        glVertex2f(width, height - fadeHeight);
        glColor4f(0, 0, 0, alpha);
        glVertex2f(width, height);
        glVertex2f(0, height);
        glEnd();
    }
    
    // ============================================================
    // TEXTURE STATE HELPERS
    // ============================================================
    
    public static void enableTextures() {
        glEnable(GL_TEXTURE_2D);
    }
    
    public static void disableTextures() {
        glDisable(GL_TEXTURE_2D);
    }
    
    public static void bindTexture(int textureId) {
        glBindTexture(GL_TEXTURE_2D, textureId);
    }
}