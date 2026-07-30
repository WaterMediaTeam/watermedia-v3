package org.watermedia.api.codecs.proxy;

import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * Post-processor applied to each decoded frame during {@code ImageReader.readAll()}. A proxy takes a
 * frame with its geometry and returns a transformed one (rescaled, recolored, ...), so proxies chain:
 * each consumes the previous output's properties. The reader owns the buffer it hands in, so a proxy
 * may rewrite it in place and return {@code src}, or allocate a fresh one.
 */
public interface ReaderProxy {
    /**
     * Transforms one frame. The returned buffer must be positioned at {@code 0} and ready to read.
     * Return {@code src} unchanged when the transform is a no-op for this frame.
     */
    Frame compute(final Frame src);

    /** A decoded frame paired with the geometry a proxy needs to read and rewrite it. */
    record Frame(ByteBuffer buffer, int width, int height, PixelFormat format) {}
}
