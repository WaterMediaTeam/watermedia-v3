package org.watermedia.api.codecs.readers;

import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming JPEG reader.
 *
 * <p>Receives a {@link ByteBuffer} positioned past the 2-byte SOI marker ({@code FF D8}). The
 * constructor synthesizes a fresh SOI prefix and decodes via {@link ImageIO} immediately to
 * resolve the canvas dimensions, then exposes the decoded frame on the first {@link #next()}.
 * JPEG is single-frame, so subsequent calls return {@code false}.
 */
public class JPEGReader extends ImageReader {

    private static final byte[] SOI = { (byte) 0xFF, (byte) 0xD8 };

    private final int width;
    private final int height;
    private final ByteBuffer directOut;

    private boolean delivered;

    public JPEGReader(final ByteBuffer data) throws IOException {
        super(data);
        // ImageIO needs the full stream including SOI to decode. Re-prepend it and decode now.
        final ByteBuffer body = this.data.duplicate();
        final byte[] bytes = new byte[SOI.length + body.remaining()];
        bytes[0] = SOI[0];
        bytes[1] = SOI[1];
        body.get(bytes, SOI.length, body.remaining());
        final BufferedImage img;
        try (final ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            img = ImageIO.read(in);
        }
        if (img == null) throw new XCodecException("Failed to decode JPEG image");

        this.width = img.getWidth();
        this.height = img.getHeight();
        this.directOut = ByteBuffer.allocateDirect(this.width * this.height * 4).order(ByteOrder.nativeOrder());
        writePixels(img, this.directOut);
    }

    @Override public int width() { return this.width; }
    @Override public int height() { return this.height; }
    @Override public PixelFormat pixelFormat() { return PixelFormat.BGRA; }
    @Override public ImageData.Scan scan() { return ImageData.Scan.EMPTY; }
    @Override public boolean variableFrameRate() { return false; }

    @Override
    public boolean hasNext() {
        return !this.delivered;
    }

    @Override
    public ByteBuffer next() throws IOException {
        if (this.delivered) throw new java.io.EOFException("No more JPEG frames");
        this.delivered = true;
        this.currentDelay = 0L;
        this.currentFrame = this.directOut;
        return this.directOut;
    }

    private static void writePixels(final BufferedImage image, final ByteBuffer buffer) {
        final int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB) {
            final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (final int pixel: pixels) {
                final int a = (type == BufferedImage.TYPE_INT_ARGB) ? ((pixel >> 24) & 0xFF) : 0xFF;
                DataTool.rgbaToBgra(buffer, pixel, (byte) a);
            }
        } else if (type == BufferedImage.TYPE_3BYTE_BGR) {
            final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < pixels.length; i += 3) {
                buffer.put(pixels[i]);
                buffer.put(pixels[i + 1]);
                buffer.put(pixels[i + 2]);
                buffer.put((byte) 0xFF);
            }
        } else if (type == BufferedImage.TYPE_4BYTE_ABGR) {
            final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < pixels.length; i += 4) {
                buffer.put(pixels[i + 1]);
                buffer.put(pixels[i + 2]);
                buffer.put(pixels[i + 3]);
                buffer.put(pixels[i]);
            }
        } else {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    final int pixel = image.getRGB(x, y);
                    buffer.put((byte) (pixel & 0xFF));
                    buffer.put((byte) ((pixel >> 8) & 0xFF));
                    buffer.put((byte) ((pixel >> 16) & 0xFF));
                    buffer.put((byte) ((pixel >> 24) & 0xFF));
                }
            }
        }
        buffer.flip();
    }
}
