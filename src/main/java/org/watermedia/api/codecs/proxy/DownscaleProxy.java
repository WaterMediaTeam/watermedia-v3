package org.watermedia.api.codecs.proxy;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;

/**
 * Downscales every decoded frame with an area-average filter. Configurable by an absolute target
 * size, a scale factor resolved against each frame, or a source size pre-multiplied by a factor.
 */
public class DownscaleProxy implements ReaderProxy {

    private final float scale;
    private final int outWidth;
    private final int outHeight;
    public DownscaleProxy(final float scale) {
        this.scale = scale;
        this.outWidth = -1;
        this.outHeight = -1;
    }

    public DownscaleProxy(final int width, final int height) {
        this.outWidth = width;
        this.outHeight = height;
        this.scale = -1;
    }

    public DownscaleProxy(final int width, final int height, final float scale) {
        this.outWidth = (int) (width * scale); // SCALE WITH SOURCE, NO NEED TO COMPUTE 3 PATHS
        this.outHeight = (int) (height * scale); // SCALE WITH SOURCE, NO NEED TO COMPUTE 3 PATHS
        this.scale = -1;
    }

    @Override
    public Frame compute(final Frame src) {
        // SCALE-RELATIVE MODE RESOLVES THE TARGET AGAINST THE INCOMING FRAME; ABSOLUTE MODE USES THE FIXED SIZE
        final int dw = this.scale > 0 ? Math.max(1, Math.round(src.width() * this.scale)) : this.outWidth;
        final int dh = this.scale > 0 ? Math.max(1, Math.round(src.height() * this.scale)) : this.outHeight;
        // NOTHING TO DO WHEN THE FORMAT CAN'T BE AREA-AVERAGED OR THE TARGET ALREADY MATCHES THE SOURCE
        if (!DataTool.scalable(src.format()) || (dw == src.width() && dh == src.height())) return src;
        final ByteBuffer dst = ByteBuffer.allocateDirect((int) DataTool.frameBytes(src.format(), dw, dh)).order(src.buffer().order());
        DataTool.scaleArea(src.buffer(), src.width(), src.height(), dst, dw, dh, src.format());
        dst.flip();
        return new Frame(dst, dw, dh, src.format());
    }
}
