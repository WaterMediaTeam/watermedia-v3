package org.watermedia.api.media.engines;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Software {@link GFXEngine} base for CPU render targets that consume a packed BGRA frame
 * instead of a GPU texture — JavaFX ({@link JFXEngine}) and AWT/Swing ({@link AWTEngine}).
 * <p>
 * It only accepts single-plane {@code BGRA}/{@code RGBA}; every planar or packed-YUV format is
 * declined, so the frame producer pre-converts those to BGRA through the decoder's scaler. Each
 * frame is written into a reusable direct BGRA buffer that the concrete engine wraps into (or copies
 * to) its native surface. Because there is no GPU texture, {@link #texture()} returns a non-zero
 * sentinel once the first frame is uploaded (0 before), matching the base contract; the real output
 * is the surface the subclass exposes.
 * <p>
 * A frame arrives on the player's decode thread: {@link #upload(ByteBuffer, int)} writes the buffer
 * and calls {@link #present()} to publish it, then fires the optional {@code onFrame} callback so a
 * UI can repaint. The surface is reallocated on every {@link #setVideoFormat} (resolution change).
 */
public abstract sealed class SWEngine extends GFXEngine permits JFXEngine, AWTEngine {
    private static final Marker IT = MarkerManager.getMarker(SWEngine.class.getSimpleName());

    // REUSABLE DIRECT BGRA SURFACE — width*height*4 BYTES, REALLOCATED ON RESIZE
    protected ByteBuffer bgra;
    // OPTIONAL "FRAME READY" HOOK, RUN ON THE UPLOAD THREAD AFTER EACH present()
    private final Runnable onFrame;
    private volatile boolean framed;
    private volatile boolean released;

    protected SWEngine(final Runnable onFrame) {
        this.onFrame = onFrame;
    }

    @Override
    public boolean supportsFormat(final PixelFormat format) {
        // ANYTHING ELSE (PLANAR/PACKED YUV, RGB, GRAY) IS DECLINED SO THE PRODUCER CONVERTS IT TO BGRA
        return format == PixelFormat.BGRA || format == PixelFormat.RGBA;
    }

    @Override
    public void setVideoFormat(final PixelFormat pixelFormat, final int width, final int height, final int bitsPerComponent) {
        super.setVideoFormat(pixelFormat, width, height, bitsPerComponent);
        final int w = Math.max(1, width);
        final int h = Math.max(1, height);
        this.bgra = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        this.allocSurface(w, h, this.bgra);
    }

    @Override
    public long texture() { return this.released || !this.framed ? 0L : 1L; }

    @Override
    public void upload(final ByteBuffer buffer, final int stride) {
        final ByteBuffer dst = this.bgra;
        if (this.released || dst == null) return;
        final int w = this.width;
        final int h = this.height;
        final int rowBytes = w * 4;
        final int srcStride = stride == 0 ? rowBytes : stride;
        // WARN-DROP LIKE THE GPU ENGINES INSTEAD OF THROWING FROM THE DECODE THREAD ON A SHORT BUFFER
        final long needed = (long) (h - 1) * srcStride + rowBytes;
        if (buffer.remaining() < needed) {
            LOGGER.warn(IT, "Frame buffer too small: {} < {}", buffer.remaining(), needed);
            return;
        }
        final ByteBuffer src = buffer.duplicate();
        final int base = src.position();

        dst.clear();
        if (this.pixelFormat == PixelFormat.RGBA) {
            // SWIZZLE RGBA->BGRA WITH ONE 32-BIT OP PER PIXEL (SWAP R AND B). FORCING LE VIEWS MAKES
            // THE BYTE MATH HOST-AGNOSTIC — THE OUTPUT MEMORY IS ALWAYS B,G,R,A REGARDLESS OF ENDIANNESS.
            final ByteBuffer s = src.order(ByteOrder.LITTLE_ENDIAN);
            final ByteBuffer d = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < h; row++) {
                int o = base + row * srcStride;
                for (int x = 0; x < w; x++, o += 4) {
                    final int p = s.getInt(o); // 0xAABBGGRR
                    d.putInt((p & 0xFF00FF00) | ((p >>> 16) & 0xFF) | ((p & 0xFF) << 16));
                }
            }
        } else {
            // BGRA — COPY EACH ROW, DROPPING ANY STRIDE PADDING
            for (int row = 0; row < h; row++) {
                final int o = base + row * srcStride;
                src.limit(o + rowBytes).position(o);
                dst.put(src);
            }
        }

        this.framed = true;
        this.present();
        if (this.onFrame != null) this.onFrame.run();
    }

    // MULTI-PLANE FORMATS ARE DECLINED IN supportsFormat, SO THESE NEVER RUN
    @Override public void upload(final ByteBuffer y, final int ys, final ByteBuffer uv, final int us) {}
    @Override public void upload(final ByteBuffer y, final int ys, final ByteBuffer u, final int us, final ByteBuffer v, final int vs) {}
    @Override public void upload(final ByteBuffer y, final int ys, final ByteBuffer u, final int us, final ByteBuffer v, final int vs, final ByteBuffer a, final int as) {}

    @Override
    public void release() {
        this.released = true;
        this.bgra = null;
        this.disposeSurface();
    }

    protected boolean released() { return this.released; }

    // CREATES THE NATIVE SURFACE FOR A NEW SIZE; jfx WRAPS bgra ZERO-COPY, awt KEEPS ITS OWN IMAGE
    protected abstract void allocSurface(int width, int height, ByteBuffer bgra);

    // PUBLISHES THE FRAME JUST WRITTEN INTO bgra (FX-THREAD updateBuffer / AWT PIXEL CONVERSION)
    protected abstract void present();

    // DROPS THE NATIVE SURFACE ON release()
    protected abstract void disposeSurface();
}
