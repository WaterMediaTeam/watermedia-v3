package org.watermedia.bootstrap.app.element;

import org.watermedia.api.media.players.MediaPlayer;

import java.awt.Color;

/**
 * A leaf view that presents the shared media player's current frame. Every draw it reads the player
 * from the attached context (handles are never captured at build time), paints its box black and, when
 * a frame is available, blits it aspect-fitted and centered through the backend's media path. When both
 * resolution caps are set the sampled UV region is center-cropped to the cap aspect, so the frame shows
 * at the cap aspect with trimmed edges instead of distortion. Without a usable frame (no player, ended,
 * stopped, error or no texture yet) the surface stays black.
 */
public final class VideoSurface extends Element<VideoSurface> {

    public VideoSurface() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int boxW = this.measuredWidth;
        final int boxH = this.measuredHeight;
        if (boxW <= 0 || boxH <= 0) return;

        // BLACK BACKDROP — ALSO THE PLACEHOLDER WHEN NO FRAME IS READY
        canvas.fill(this.left, this.top, boxW, boxH, Color.BLACK);

        final MediaPlayer player = this.ctx != null ? this.ctx.player : null;
        // 0 = NO FRAME READY. THE HANDLE IS A GL TEXTURE NAME OR A 64-BIT VULKAN VkImageView, SO TEST == 0 (NOT <= 0)
        if (player == null || player.ended() || player.stopped() || player.error() || player.texture() == 0) return;

        final int videoW = player.width();
        final int videoH = player.height();

        float renderW = boxW, renderH = boxH, offsetX = 0, offsetY = 0;
        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;

        if (videoW > 0 && videoH > 0) {
            final float srcAspect = (float) videoW / videoH;

            // CENTER-CROP THE UPLOADED FRAME TO THE CUSTOM RESOLUTION ASPECT WHEN BOTH CAPS ARE
            // SET. maxSize KEEPS THE TEXTURE AT THE SOURCE ASPECT, SO WE ONLY NARROW THE SAMPLED
            // UV REGION — NO DISTORTION, JUST TRIMMED EDGES — AND DISPLAY IT AT THE CAP ASPECT.
            float dispAspect = srcAspect;
            final int capW = player.maxWidth();
            final int capH = player.maxHeight();
            if (capW > 0 && capH > 0) {
                dispAspect = (float) capW / capH;
                if (dispAspect > srcAspect) {
                    final float frac = srcAspect / dispAspect;
                    v0 = (1f - frac) / 2f;
                    v1 = 1f - v0;
                } else if (dispAspect < srcAspect) {
                    final float frac = dispAspect / srcAspect;
                    u0 = (1f - frac) / 2f;
                    u1 = 1f - u0;
                }
            }

            final float boxAspect = (float) boxW / boxH;
            if (dispAspect > boxAspect) {
                renderW = boxW;
                renderH = boxW / dispAspect;
                offsetY = (boxH - renderH) / 2f;
            } else {
                renderH = boxH;
                renderW = boxH * dispAspect;
                offsetX = (boxW - renderW) / 2f;
            }
        }

        canvas.mediaImage(player.texture(), this.left + offsetX, this.top + offsetY, renderW, renderH, u0, v0, u1, v1);
    }
}
