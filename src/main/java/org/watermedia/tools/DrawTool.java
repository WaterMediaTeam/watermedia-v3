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

    /**
     * Fills a triangle with three vertices.
     */
    public static void fillTriangle(float x1, float y1, float x2, float y2, float x3, float y3,
                                    float r, float g, float b, float a) {
        color(r, g, b, a);
        glBegin(GL_TRIANGLES);
        glVertex2f(x1, y1);
        glVertex2f(x2, y2);
        glVertex2f(x3, y3);
        glEnd();
    }

    /**
     * Fills a triangle with rounded corners.
     * @param x1, y1 First vertex (top)
     * @param x2, y2 Second vertex (bottom-left)
     * @param x3, y3 Third vertex (bottom-right)
     * @param radius Corner rounding radius
     */
    public static void fillRoundedTriangle(float x1, float y1, float x2, float y2, float x3, float y3,
                                           float radius, float r, float g, float b, float a) {
        color(r, g, b, a);

        // Calculate edge directions (normalized)
        float d12x = x2 - x1, d12y = y2 - y1;
        float d23x = x3 - x2, d23y = y3 - y2;
        float d31x = x1 - x3, d31y = y1 - y3;

        float len12 = (float) Math.sqrt(d12x * d12x + d12y * d12y);
        float len23 = (float) Math.sqrt(d23x * d23x + d23y * d23y);
        float len31 = (float) Math.sqrt(d31x * d31x + d31y * d31y);

        d12x /= len12; d12y /= len12;
        d23x /= len23; d23y /= len23;
        d31x /= len31; d31y /= len31;

        // Calculate inset amount based on angles
        float inset = radius * 2f;

        // Inset corner centers
        float c1x = x1 + (d12x - d31x) * inset / 2f;
        float c1y = y1 + (d12y - d31y) * inset / 2f;
        float c2x = x2 + (d23x - d12x) * inset / 2f;
        float c2y = y2 + (d23y - d12y) * inset / 2f;
        float c3x = x3 + (d31x - d23x) * inset / 2f;
        float c3y = y3 + (d31y - d23y) * inset / 2f;

        // Calculate angles for arcs at each corner
        float angle1Start = (float) Math.atan2(-d31y, -d31x);
        float angle1End = (float) Math.atan2(d12y, d12x);
        float angle2Start = (float) Math.atan2(-d12y, -d12x);
        float angle2End = (float) Math.atan2(d23y, d23x);
        float angle3Start = (float) Math.atan2(-d23y, -d23x);
        float angle3End = (float) Math.atan2(d31y, d31x);

        // Normalize angle ranges
        if (angle1End < angle1Start) angle1End += 2 * Math.PI;
        if (angle2End < angle2Start) angle2End += 2 * Math.PI;
        if (angle3End < angle3Start) angle3End += 2 * Math.PI;

        final int arcSegments = 5;

        // Draw as triangle fan from centroid
        float cx = (x1 + x2 + x3) / 3f;
        float cy = (y1 + y2 + y3) / 3f;

        glBegin(GL_TRIANGLE_FAN);
        glVertex2f(cx, cy);

        // Corner 1 arc
        for (int i = 0; i <= arcSegments; i++) {
            float angle = angle1Start + (angle1End - angle1Start) * i / arcSegments;
            glVertex2f(c1x + (float) Math.cos(angle) * radius, c1y + (float) Math.sin(angle) * radius);
        }

        // Corner 2 arc
        for (int i = 0; i <= arcSegments; i++) {
            float angle = angle2Start + (angle2End - angle2Start) * i / arcSegments;
            glVertex2f(c2x + (float) Math.cos(angle) * radius, c2y + (float) Math.sin(angle) * radius);
        }

        // Corner 3 arc
        for (int i = 0; i <= arcSegments; i++) {
            float angle = angle3Start + (angle3End - angle3Start) * i / arcSegments;
            glVertex2f(c3x + (float) Math.cos(angle) * radius, c3y + (float) Math.sin(angle) * radius);
        }

        // Close - back to first point of corner 1
        glVertex2f(c1x + (float) Math.cos(angle1Start) * radius, c1y + (float) Math.sin(angle1Start) * radius);

        glEnd();
    }

    /**
     * Fills a circle at the specified center with given radius.
     */
    public static void fillCircle(float cx, float cy, float radius, float r, float g, float b, float a) {
        color(r, g, b, a);
        glBegin(GL_TRIANGLE_FAN);
        glVertex2f(cx, cy);
        final int segments = 16;
        for (int i = 0; i <= segments; i++) {
            final float angle = (float) (i * 2 * Math.PI / segments);
            glVertex2f(cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius);
        }
        glEnd();
    }

    // ============================================================
    // ROUNDED SHAPES
    // ============================================================

    /**
     * Fills a rounded rectangle using the current color.
     */
    public static void fillRounded(float x, float y, float w, float h, float radius) {
        radius = Math.min(radius, Math.min(w, h) / 2f);
        fill(x + radius, y, w - 2 * radius, h);
        fill(x, y + radius, radius, h - 2 * radius);
        fill(x + w - radius, y + radius, radius, h - 2 * radius);
        fillArc(x + radius, y + radius, radius, (float) Math.PI, (float) (Math.PI * 1.5), 8);
        fillArc(x + w - radius, y + radius, radius, (float) (Math.PI * 1.5), (float) (Math.PI * 2), 8);
        fillArc(x + w - radius, y + h - radius, radius, 0, (float) (Math.PI * 0.5), 8);
        fillArc(x + radius, y + h - radius, radius, (float) (Math.PI * 0.5), (float) Math.PI, 8);
    }

    /**
     * Fills a rounded rectangle with specified RGBA color.
     */
    public static void fillRounded(float x, float y, float w, float h, float radius,
                                    float r, float g, float b, float a) {
        color(r, g, b, a);
        fillRounded(x, y, w, h, radius);
    }

    /**
     * Fills a rounded rectangle with specified Color.
     */
    public static void fillRounded(float x, float y, float w, float h, float radius, Color c) {
        color(c);
        fillRounded(x, y, w, h, radius);
    }

    /**
     * Draws a rounded rectangle outline using the current color.
     */
    public static void rectRounded(float x, float y, float w, float h, float radius, float lineWidth) {
        radius = Math.min(radius, Math.min(w, h) / 2f);
        glLineWidth(lineWidth);
        final int segments = 8;
        glBegin(GL_LINE_LOOP);
        // Top-right corner
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (-Math.PI / 2) + (float) (Math.PI / 2) * i / segments;
            glVertex2f(x + w - radius + (float) Math.cos(angle) * radius,
                       y + radius + (float) Math.sin(angle) * radius);
        }
        // Bottom-right corner
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI / 2) * i / segments;
            glVertex2f(x + w - radius + (float) Math.cos(angle) * radius,
                       y + h - radius + (float) Math.sin(angle) * radius);
        }
        // Bottom-left corner
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI / 2) + (float) (Math.PI / 2) * i / segments;
            glVertex2f(x + radius + (float) Math.cos(angle) * radius,
                       y + h - radius + (float) Math.sin(angle) * radius);
        }
        // Top-left corner
        for (int i = 0; i <= segments; i++) {
            float angle = (float) Math.PI + (float) (Math.PI / 2) * i / segments;
            glVertex2f(x + radius + (float) Math.cos(angle) * radius,
                       y + radius + (float) Math.sin(angle) * radius);
        }
        glEnd();
    }

    /**
     * Draws a rounded rectangle outline with specified RGBA color and line width.
     */
    public static void rectRounded(float x, float y, float w, float h, float radius,
                                    float r, float g, float b, float a, float lineWidth) {
        color(r, g, b, a);
        rectRounded(x, y, w, h, radius, lineWidth);
    }

    /**
     * Draws a rounded rectangle outline with specified Color and line width.
     */
    public static void rectRounded(float x, float y, float w, float h, float radius,
                                    Color c, float lineWidth) {
        color(c);
        rectRounded(x, y, w, h, radius, lineWidth);
    }

    /**
     * Fills an arc (pie slice) as part of a rounded shape.
     */
    private static void fillArc(float cx, float cy, float radius,
                                 float startAngle, float endAngle, int segments) {
        glBegin(GL_TRIANGLE_FAN);
        glVertex2f(cx, cy);
        for (int i = 0; i <= segments; i++) {
            float angle = startAngle + (endAngle - startAngle) * i / segments;
            glVertex2f(cx + (float) Math.cos(angle) * radius,
                       cy + (float) Math.sin(angle) * radius);
        }
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