package org.watermedia.api.decode.formats.webp.lossless;

public final class Predictor {

    private Predictor() {
    }

    // APPLY INVERSE PREDICTOR TRANSFORM
    public static void inverse(final int[] pixels, final int width, final int height, final int[] modes, final int blockBits) {
        final int blockSize = 1 << blockBits;
        final int blocksPerRow = (width + blockSize - 1) / blockSize;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int pos = y * width + x;

                // GET PREDICTION MODE FOR THIS BLOCK
                final int blockX = x >> blockBits;
                final int blockY = y >> blockBits;
                final int mode = (modes[blockY * blocksPerRow + blockX] >> 8) & 0xF; // GREEN CHANNEL

                // GET NEIGHBOR PIXELS
                final int left = (x > 0) ? pixels[pos - 1] : 0;
                final int top = (y > 0) ? pixels[pos - width] : 0;
                final int topLeft = (x > 0 && y > 0) ? pixels[pos - width - 1] : 0;
                final int topRight = (x < width - 1 && y > 0) ? pixels[pos - width + 1] : (y > 0) ? pixels[y * width] : 0;

                // SPECIAL CASES FOR BORDERS
                if (x == 0 && y == 0) {
                    // TOP-LEFT PIXEL: PREDICTOR IS BLACK
                    pixels[pos] = addPixels(pixels[pos], 0xFF000000);
                    continue;
                } else if (y == 0) {
                    // TOP ROW: USE LEFT
                    pixels[pos] = addPixels(pixels[pos], left);
                    continue;
                } else if (x == 0) {
                    // LEFT COLUMN: USE TOP
                    pixels[pos] = addPixels(pixels[pos], top);
                    continue;
                }

                // APPLY PREDICTOR MODE
                final int predicted = predict(mode, left, top, topLeft, topRight);
                pixels[pos] = addPixels(pixels[pos], predicted);
            }
        }
    }

    private static int predict(final int mode, final int L, final int T, final int TL, final int TR) {
        return switch (mode) {
            case 0 -> 0xFF000000;                    // BLACK
            case 1 -> L;                             // LEFT
            case 2 -> T;                             // TOP
            case 3 -> TR;                            // TOP-RIGHT
            case 4 -> TL;                            // TOP-LEFT
            case 5 -> avg2(avg2(L, TR), T);          // AVG(AVG(L,TR), T)
            case 6 -> avg2(L, TL);                   // AVG(L, TL)
            case 7 -> avg2(L, T);                    // AVG(L, T)
            case 8 -> avg2(TL, T);                   // AVG(TL, T)
            case 9 -> avg2(T, TR);                   // AVG(T, TR)
            case 10 -> avg2(avg2(L, TL), avg2(T, TR)); // AVG(AVG(L,TL), AVG(T,TR))
            case 11 -> select(L, T, TL);             // SELECT
            case 12 -> clampAddSubFull(L, T, TL);    // CLAMP_ADD_SUB_FULL
            case 13 -> clampAddSubHalf(avg2(L, T), TL); // CLAMP_ADD_SUB_HALF
            default -> 0xFF000000;
        };
    }

    // ADD RESIDUAL TO PREDICTED (PER CHANNEL, WRAP AT 256)
    private static int addPixels(final int residual, final int predicted) {
        final int a = ((residual >> 24) + (predicted >> 24)) & 0xFF;
        final int r = (((residual >> 16) & 0xFF) + ((predicted >> 16) & 0xFF)) & 0xFF;
        final int g = (((residual >> 8) & 0xFF) + ((predicted >> 8) & 0xFF)) & 0xFF;
        final int b = ((residual & 0xFF) + (predicted & 0xFF)) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // AVERAGE OF TWO PIXELS (PER CHANNEL)
    private static int avg2(final int a, final int b) {
        final int aa = (((a >> 24) & 0xFF) + ((b >> 24) & 0xFF)) / 2;
        final int ra = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2;
        final int ga = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2;
        final int ba = ((a & 0xFF) + (b & 0xFF)) / 2;
        return (aa << 24) | (ra << 16) | (ga << 8) | ba;
    }

    // SELECT PREDICTOR
    private static int select(final int L, final int T, final int TL) {
        final int pA = ((L >> 24) & 0xFF) + ((T >> 24) & 0xFF) - ((TL >> 24) & 0xFF);
        final int pR = ((L >> 16) & 0xFF) + ((T >> 16) & 0xFF) - ((TL >> 16) & 0xFF);
        final int pG = ((L >> 8) & 0xFF) + ((T >> 8) & 0xFF) - ((TL >> 8) & 0xFF);
        final int pB = (L & 0xFF) + (T & 0xFF) - (TL & 0xFF);

        final int pL = Math.abs(pA - ((L >> 24) & 0xFF)) + Math.abs(pR - ((L >> 16) & 0xFF)) +
                Math.abs(pG - ((L >> 8) & 0xFF)) + Math.abs(pB - (L & 0xFF));
        final int pT = Math.abs(pA - ((T >> 24) & 0xFF)) + Math.abs(pR - ((T >> 16) & 0xFF)) +
                Math.abs(pG - ((T >> 8) & 0xFF)) + Math.abs(pB - (T & 0xFF));

        return (pL < pT) ? L : T;
    }

    // CLAMP VALUE TO 0-255
    private static int clamp(final int v) {
        return (v < 0) ? 0 : (v > 255) ? 255 : v;
    }

    // CLAMP_ADD_SUBTRACT_FULL
    private static int clampAddSubFull(final int a, final int b, final int c) {
        final int aa = clamp(((a >> 24) & 0xFF) + ((b >> 24) & 0xFF) - ((c >> 24) & 0xFF));
        final int ra = clamp(((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) - ((c >> 16) & 0xFF));
        final int ga = clamp(((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) - ((c >> 8) & 0xFF));
        final int ba = clamp((a & 0xFF) + (b & 0xFF) - (c & 0xFF));
        return (aa << 24) | (ra << 16) | (ga << 8) | ba;
    }

    // CLAMP_ADD_SUBTRACT_HALF
    private static int clampAddSubHalf(final int a, final int b) {
        final int aa = clamp(((a >> 24) & 0xFF) + (((a >> 24) & 0xFF) - ((b >> 24) & 0xFF)) / 2);
        final int ra = clamp(((a >> 16) & 0xFF) + (((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) / 2);
        final int ga = clamp(((a >> 8) & 0xFF) + (((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) / 2);
        final int ba = clamp((a & 0xFF) + ((a & 0xFF) - (b & 0xFF)) / 2);
        return (aa << 24) | (ra << 16) | (ga << 8) | ba;
    }
}
