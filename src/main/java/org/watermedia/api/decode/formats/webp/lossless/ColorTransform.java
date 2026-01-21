package org.watermedia.api.decode.formats.webp.lossless;

public final class ColorTransform {

    private ColorTransform() {
    }

    // APPLY INVERSE COLOR TRANSFORM
    public static void inverse(final int[] pixels, final int width, final int height, final int[] transforms, final int blockBits) {
        final int blockSize = 1 << blockBits;
        final int blocksPerRow = (width + blockSize - 1) / blockSize;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int pos = y * width + x;
                final int pixel = pixels[pos];

                // GET TRANSFORM ELEMENT FOR THIS BLOCK
                final int blockX = x >> blockBits;
                final int blockY = y >> blockBits;
                final int transform = transforms[blockY * blocksPerRow + blockX];

                // EXTRACT TRANSFORM COMPONENTS FROM ARGB
                // STORED AS: A=255, R=red_to_blue, G=green_to_blue, B=green_to_red
                final int greenToRed = (byte) (transform & 0xFF);
                final int greenToBlue = (byte) ((transform >> 8) & 0xFF);
                final int redToBlue = (byte) ((transform >> 16) & 0xFF);

                // EXTRACT PIXEL COMPONENTS
                final int a = (pixel >> 24) & 0xFF;
                final int r = (pixel >> 16) & 0xFF;
                final int g = (pixel >> 8) & 0xFF;
                final int b = pixel & 0xFF;

                // APPLY INVERSE TRANSFORM (ADD DELTAS)
                final int newR = (r + colorTransformDelta((byte) greenToRed, (byte) g)) & 0xFF;
                int newB = (b + colorTransformDelta((byte) greenToBlue, (byte) g)) & 0xFF;
                newB = (newB + colorTransformDelta((byte) redToBlue, (byte) newR)) & 0xFF;

                pixels[pos] = (a << 24) | (newR << 16) | (g << 8) | newB;
            }
        }
    }

    // APPLY INVERSE SUBTRACT GREEN TRANSFORM
    public static void addGreen(final int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            final int pixel = pixels[i];
            final int a = (pixel >> 24) & 0xFF;
            int r = (pixel >> 16) & 0xFF;
            final int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            r = (r + g) & 0xFF;
            b = (b + g) & 0xFF;

            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    // APPLY INVERSE COLOR INDEXING TRANSFORM
    public static void applyPalette(final int[] pixels, final int width, final int height, final int[] colorTable, final int widthBits) {
        if (widthBits == 0) {
            // NO BUNDLING, DIRECT INDEX LOOKUP
            for (int i = 0; i < pixels.length; i++) {
                final int index = (pixels[i] >> 8) & 0xFF; // GREEN CHANNEL IS INDEX
                pixels[i] = (index < colorTable.length) ? colorTable[index] : 0;
            }
        } else {
            // PIXELS ARE BUNDLED, NEED TO UNPACK
            final int pixelsPerByte = 1 << widthBits;
            final int mask = (1 << (8 / pixelsPerByte)) - 1;
            final int bundledWidth = (width + pixelsPerByte - 1) / pixelsPerByte;

            final int[] unpacked = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int bundledX = x / pixelsPerByte;
                    final int shift = (x % pixelsPerByte) * (8 / pixelsPerByte);

                    final int bundledPixel = pixels[y * bundledWidth + bundledX];
                    final int index = ((bundledPixel >> 8) >> shift) & mask; // FROM GREEN CHANNEL

                    unpacked[y * width + x] = (index < colorTable.length) ? colorTable[index] : 0;
                }
            }

            System.arraycopy(unpacked, 0, pixels, 0, width * height);
        }
    }

    // COLOR TRANSFORM DELTA: (t * c) >> 5 WITH SIGNED ARITHMETIC
    private static int colorTransformDelta(final byte t, final byte c) {
        return (t * c) >> 5;
    }

    // DECODE COLOR TABLE (SUBTRACT-CODED)
    public static int[] decodeColorTable(final int[] rawTable) {
        final int[] table = new int[rawTable.length];
        table[0] = rawTable[0];

        for (int i = 1; i < rawTable.length; i++) {
            final int prev = table[i - 1];
            final int curr = rawTable[i];

            // ADD EACH COMPONENT
            final int a = (((prev >> 24) & 0xFF) + ((curr >> 24) & 0xFF)) & 0xFF;
            final int r = (((prev >> 16) & 0xFF) + ((curr >> 16) & 0xFF)) & 0xFF;
            final int g = (((prev >> 8) & 0xFF) + ((curr >> 8) & 0xFF)) & 0xFF;
            final int b = ((prev & 0xFF) + (curr & 0xFF)) & 0xFF;

            table[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        return table;
    }

    // GET WIDTH BITS FOR PIXEL BUNDLING BASED ON COLOR TABLE SIZE
    public static int widthBits(final int colorTableSize) {
        if (colorTableSize <= 2) return 3;      // 8 PIXELS PER BYTE
        if (colorTableSize <= 4) return 2;      // 4 PIXELS PER BYTE
        if (colorTableSize <= 16) return 1;     // 2 PIXELS PER BYTE
        return 0;                               // NO BUNDLING
    }
}
