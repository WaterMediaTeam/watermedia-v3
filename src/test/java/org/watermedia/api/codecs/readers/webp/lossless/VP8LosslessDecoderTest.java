package org.watermedia.api.codecs.readers.webp.lossless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.common.webp.AlphaDecoder;
import org.watermedia.api.codecs.common.webp.BitReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies VP8L color indexing with pixel bundling: palettes of 16 colors or fewer pack several
 * indexes per green byte, so the main image and every transform read after the palette live at
 * the packed width while the inverse unpacks to the full one. Streams are assembled by hand with
 * simple one- or two-symbol prefix codes.
 */
@DisplayName("VP8L decoder")
public class VP8LosslessDecoderTest {

    // LSB-FIRST BIT WRITER MIRRORING BitReader
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int acc, count;

        Bits put(final int value, final int bits) {
            for (int i = 0; i < bits; i++) {
                this.acc |= ((value >>> i) & 1) << this.count;
                if (++this.count == 8) { this.out.write(this.acc); this.acc = 0; this.count = 0; }
            }
            return this;
        }

        // SIMPLE PREFIX CODE: ONE SYMBOL COSTS ZERO BITS PER PIXEL, TWO SYMBOLS ONE BIT (0 PICKS THE FIRST)
        Bits code(final int... symbols) {
            this.put(1, 1).put(symbols.length - 1, 1).put(1, 1).put(symbols[0], 8);
            if (symbols.length == 2) this.put(symbols[1], 8);
            return this;
        }

        // ONE HUFFMAN GROUP: GREEN, RED, BLUE AND ALPHA CODES PLUS A ZERO-BIT DISTANCE CODE
        void group(final int[] green, final int[] red, final int[] blue, final int[] alpha) {
            this.code(green).code(red).code(blue).code(alpha).code(0);
        }

        byte[] bytes() {
            final byte[] whole = this.out.toByteArray();
            if (this.count == 0) return whole;
            final byte[] padded = Arrays.copyOf(whole, whole.length + 1);
            padded[whole.length] = (byte) this.acc;
            return padded;
        }
    }

    private static final int[] ZERO = {0};

    // WRAPS A HEADERLESS VP8L STREAM INTO A SIMPLE-FORMAT WEBP FILE
    private static byte[] webp(final int width, final int height, final byte[] stream) {
        final int size = 5 + stream.length;
        final ByteBuffer file = ByteBuffer.allocate(20 + ((size + 1) & ~1)).order(ByteOrder.LITTLE_ENDIAN);
        file.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(file.capacity() - 8).put("WEBP".getBytes(StandardCharsets.US_ASCII));
        file.put("VP8L".getBytes(StandardCharsets.US_ASCII)).putInt(size);
        file.put((byte) 0x2F).putInt((width - 1) | ((height - 1) << 14)).put(stream);
        return file.array();
    }

    @Nested
    @DisplayName("Bundled palette")
    class BundledPalette {

        @Test
        @DisplayName("two colors unpack eight indexes per byte and the predictor read after them runs at the packed width")
        void twoColorsWithPackedPredictor() throws IOException {
            final Bits bits = new Bits();
            // COLOR INDEXING, 2 COLORS: OPAQUE BLACK THEN WHITE (DELTA-CODED SECOND ENTRY 0x00FFFFFF)
            bits.put(1, 1).put(3, 2).put(1, 8);
            bits.put(0, 1).group(new int[]{0x00, 0xFF}, new int[]{0x00, 0xFF}, new int[]{0x00, 0xFF}, new int[]{0xFF, 0x00});
            bits.put(0b0000, 4).put(0b1111, 4);
            // PREDICTOR READ AT THE PACKED WIDTH (1): ITS BLOCK IMAGE IS ONE PIXEL AND SPENDS ONE BIT; AT THE
            // FULL WIDTH (8) IT WOULD BE TWO PIXELS AND DESYNCHRONIZE EVERYTHING AFTER IT
            bits.put(1, 1).put(0, 2).put(0, 3);
            bits.put(0, 1).group(new int[]{1, 2}, ZERO, ZERO, ZERO);
            bits.put(0, 1);
            bits.put(0, 1);
            // MAIN IMAGE 1x2: BOTH RESIDUALS ARE 0x55 IN GREEN (0xFF005500 OVER BLACK, THEN 0xFF00AA00 OVER THE TOP)
            bits.put(0, 1).put(0, 1).group(new int[]{0x55}, ZERO, ZERO, ZERO);

            final ImageData image = CodecsAPI.decode(webp(8, 2, bits.bytes()));
            assertEquals(8, image.width());
            assertEquals(2, image.height());
            final int[] pixels = new int[16];
            image.frames()[0].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
            // ROW 0 PACKS 0x55 (WHITE FIRST), ROW 1 PACKS 0xAA (BLACK FIRST), LOW BIT LEFTMOST
            final int[] expected = new int[16];
            for (int x = 0; x < 8; x++) {
                expected[x] = (x & 1) == 0 ? 0xFFFFFFFF : 0xFF000000;
                expected[8 + x] = (x & 1) == 0 ? 0xFF000000 : 0xFFFFFFFF;
            }
            assertArrayEquals(expected, pixels);
        }

        @Test
        @DisplayName("sixteen colors unpack two indexes per byte, the last byte of an odd row half used")
        void sixteenColorsOddWidth() throws IOException {
            final Bits bits = new Bits();
            bits.put(1, 1).put(3, 2).put(15, 8);
            // PALETTE ENTRY i IS 0xFF000000 + i * 0x101010: ONE OPAQUE BLACK, THEN FIFTEEN 0x00101010 DELTAS
            bits.put(0, 1).group(new int[]{0x00, 0x10}, new int[]{0x00, 0x10}, new int[]{0x00, 0x10}, new int[]{0xFF, 0x00});
            bits.put(0b0000, 4);
            for (int i = 1; i < 16; i++) bits.put(0b1111, 4);
            bits.put(0, 1);
            // MAIN IMAGE 2x1: 0xA5 PACKS INDEXES 5 AND 10, 0x0F HOLDS INDEX 15 IN ITS LOW NIBBLE
            bits.put(0, 1).put(0, 1).group(new int[]{0xA5, 0x0F}, ZERO, ZERO, ZERO);
            bits.put(0, 1).put(1, 1);

            final int[] pixels = VP8LosslessDecoder.decode(new BitReader(ByteBuffer.wrap(bits.bytes())), 3, 1);
            assertArrayEquals(new int[]{0xFF505050, 0xFFA0A0A0, 0xFFF0F0F0}, pixels);
        }

        @Test
        @DisplayName("four levels in a lossless ALPH plane unpack four indexes per byte")
        void fourLevelAlphaPlane() throws IOException {
            final Bits bits = new Bits();
            bits.put(1, 1).put(3, 2).put(3, 8);
            // THE PALETTE CARRIES THE ALPHA LEVEL IN GREEN: 0, 85, 170, 255 (DELTA 0x55 EACH)
            bits.put(0, 1).group(new int[]{0x00, 0x55}, ZERO, ZERO, ZERO);
            bits.put(0b1110, 4);
            bits.put(0, 1);
            // MAIN IMAGE 2x2: 0xE4 PACKS 0, 1, 2, 3 (TWO BITS EACH), THE NEXT BYTE HOLDS THE FIFTH INDEX 0
            bits.put(0, 1).put(0, 1).group(new int[]{0xE4, 0x00}, ZERO, ZERO, ZERO);
            bits.put(0b1010, 4);

            final byte[] stream = bits.bytes();
            final ByteBuffer alph = ByteBuffer.allocate(1 + stream.length).put((byte) 0x01).put(stream).flip();
            final byte[] alpha = AlphaDecoder.decode(alph, 5, 2);
            assertArrayEquals(new byte[]{0, 85, (byte) 170, (byte) 255, 0, 0, 85, (byte) 170, (byte) 255, 0}, alpha);
        }
    }
}
