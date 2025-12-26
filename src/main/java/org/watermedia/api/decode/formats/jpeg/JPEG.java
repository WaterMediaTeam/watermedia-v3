package org.watermedia.api.decode.formats.jpeg;

import org.watermedia.api.decode.Decoder;
import org.watermedia.api.decode.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class JPEG extends Decoder {

    // JPEG MAGIC BYTES
    private static final byte MARKER_START = (byte) 0xFF;
    private static final byte SOI_MARKER = (byte) 0xD8; // START OF IMAGE

    @Override
    public boolean supported(final ByteBuffer buffer) {
        if (buffer.remaining() < 2) {
            return false;
        }

        buffer.mark();
        final byte first = buffer.get();
        final byte second = buffer.get();
        buffer.reset();

        // JPEG STARTS WITH 0xFF 0xD8
        return first == MARKER_START && second == SOI_MARKER;
    }

    @Override
    public Image decode(final ByteBuffer buffer) throws IOException {
        // CONVERT BYTEBUFFER TO BYTE ARRAY FOR IMAGEIO
        final byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        // DECODE USING IMAGEIO
        final BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(data));
        if (bufferedImage == null) {
            throw new IOException("Failed to decode JPEG image");
        }

        final int width = bufferedImage.getWidth();
        final int height = bufferedImage.getHeight();

        // CONVERT TO RGBA BYTEBUFFER
        final ByteBuffer pixels = this.extractPixels(bufferedImage, width, height);

        // CREATE IMAGE WITH SINGLE FRAME (JPEG IS NOT ANIMATED)
        // DURATION OF 1ms FOR STATIC IMAGE
        return new Image(new ByteBuffer[] { pixels }, width, height, new long[] { 1L }, Image.NO_REPEAT);
    }

    private ByteBuffer extractPixels(final BufferedImage image, final int width, final int height) {
        final int pixelCount = width * height;
        final ByteBuffer buffer = ByteBuffer.allocateDirect(pixelCount * 4);
        buffer.order(ByteOrder.nativeOrder());

        final int type = image.getType();

        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB) {
            // FAST PATH FOR INT-BASED IMAGES
            final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (final int pixel : pixels) {
                final int a = (type == BufferedImage.TYPE_INT_ARGB) ? ((pixel >> 24) & 0xFF) : 0xFF;
                final int r = (pixel >> 16) & 0xFF;
                final int g = (pixel >> 8) & 0xFF;
                final int b = pixel & 0xFF;
                buffer.put((byte) b);
                buffer.put((byte) g);
                buffer.put((byte) r);
                buffer.put((byte) a);
            }
        } else if (type == BufferedImage.TYPE_3BYTE_BGR) {
            // FAST PATH FOR BGR IMAGES - ALREADY BGR, JUST ADD ALPHA
            final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < pixels.length; i += 3) {
                buffer.put(pixels[i]);     // B
                buffer.put(pixels[i + 1]); // G
                buffer.put(pixels[i + 2]); // R
                buffer.put((byte) 0xFF);   // A
            }
        } else if (type == BufferedImage.TYPE_4BYTE_ABGR) {
            // FAST PATH FOR ABGR IMAGES
            final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < pixels.length; i += 4) {
                buffer.put(pixels[i + 1]); // B
                buffer.put(pixels[i + 2]); // G
                buffer.put(pixels[i + 3]); // R
                buffer.put(pixels[i]);     // A
            }
        } else {
            // FALLBACK: USE getRGB FOR ANY IMAGE TYPE
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int pixel = image.getRGB(x, y);
                    buffer.put((byte) (pixel & 0xFF));         // B
                    buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
                    buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
                    buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
                }
            }
        }

        buffer.flip();
        return buffer;
    }

    @Override
    public boolean test() {
        // CHECK IF IMAGEIO CAN READ JPEG FORMAT
        final String[] readers = ImageIO.getReaderFormatNames();
        for (final String format : readers) {
            if (format.equalsIgnoreCase("JPEG") || format.equalsIgnoreCase("JPG")) {
                return true;
            }
        }
        return false;
    }
}