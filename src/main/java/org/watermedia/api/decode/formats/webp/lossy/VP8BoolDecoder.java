package org.watermedia.api.decode.formats.webp.lossy;

import org.watermedia.api.decode.DecoderException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.decode.formats.webp.lossy.VP8LossyDecoder.IT;

// RFC6386 SECTION 7 - BOOLEAN ENTROPY DECODER
// MAINTAINS 'VALUE' AND 'RANGE' TO TRACK CURRENT POSITION IN ARITHMETIC CODING INTERVAL
// KEY CONSTRAINTS: 128 <= RANGE <= 255, VALUE CONTAINS AT LEAST 8 SIGNIFICANT BITS
final class VP8BoolDecoder {
    private final ByteBuffer buf;
    private int range;    // ALWAYS 128-255 PER RFC6386 7.2
    private int val;      // CURRENT CODED VALUE
    private int bitCnt;   // NUMBER OF BITS SHIFTED OUT, MAX 7

    // RFC6386 SECTION 7.3 - DECODER INITIALIZATION
    // READS FIRST TWO BYTES TO INITIALIZE VALUE, SETS RANGE = 255
    VP8BoolDecoder(final ByteBuffer buffer) throws DecoderException {
        this.buf = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);

        if (this.buf.remaining() < 2) {
            throw new DecoderException("VP8 bool decoder buffer too small");
        }

        // INIT: VALUE = FIRST 2 INPUT BYTES (BIG-ENDIAN ORDER)
        this.val = (this.buf.get() & 0xFF) << 8;
        this.val |= (this.buf.get() & 0xFF);
        this.range = 255;  // INITIAL RANGE IS FULL
        this.bitCnt = 0;   // NO BITS SHIFTED OUT YET

        LOGGER.debug(IT, "Init: val=0x{}, range={}", Integer.toHexString(this.val), this.range);
    }

    // RFC6386 SECTION 7.3 - READ_BOOL IMPLEMENTATION
    // PROB: 0-255 WHERE PROB IS P(FALSE)*256
    // SPLIT = 1 + (((RANGE - 1) * PROB) >> 8)
    // COMPARE VALUE TO SPLIT << 8 TO DETERMINE BIT
    boolean readBool(final int prob) throws DecoderException {
        // CALCULATE SPLIT POINT PER RFC6386 EQ 7.1
        final int split = 1 + (((this.range - 1) * prob) >> 8);
        final int bigSplit = split << 8;

        final boolean bit;
        if (this.val >= bigSplit) {
            // ENCODED A 1 - ADJUST RANGE AND VALUE
            bit = true;
            this.range -= split;
            this.val -= bigSplit;
        } else {
            // ENCODED A 0 - REDUCE RANGE ONLY
            bit = false;
            this.range = split;
        }

        // RENORMALIZE: WHILE RANGE < 128, DOUBLE IT AND SHIFT IN NEW BITS
        while (this.range < 128) {
            this.val <<= 1;
            this.range <<= 1;

            if (++this.bitCnt == 8) {
                this.bitCnt = 0;
                if (this.buf.hasRemaining()) {
                    this.val |= (this.buf.get() & 0xFF);
                }
                // IF NO MORE BYTES, ZEROS ARE SHIFTED IN
            }
        }
        return bit;
    }

    // RFC6386 SECTION 7.3 - 50% PROBABILITY BOOL (FLAG)
    boolean readBool() throws DecoderException {
        return this.readBool(128);
    }

    // RFC6386 SECTION 8 - READ N-BIT UNSIGNED LITERAL (MSB FIRST)
    // L(N) IN RFC NOTATION
    int readLit(final int n) throws DecoderException {
        int r = 0;
        for (int i = 0; i < n; i++) {
            r = (r << 1) | (this.readBool() ? 1 : 0);
        }
        LOGGER.debug(IT, "readLit({}): {}", n, r);
        return r;
    }

    // RFC6386 SECTION 8 - READ SIGNED LITERAL
    // MAGNITUDE FIRST, THEN SIGN BIT (1 = NEGATIVE)
    int readSigned(final int n) throws DecoderException {
        final int mag = this.readLit(n);
        return this.readBool() ? -mag : mag;
    }

    // READ OPTIONAL SIGNED: FLAG BIT, THEN VALUE IF FLAG SET
    int readOptSigned(final int n, final int flagProb) throws DecoderException {
        if (this.readBool(flagProb)) {
            return this.readSigned(n);
        }
        return 0;
    }

    boolean hasRemaining() {
        return this.buf.hasRemaining() || this.bitCnt > 0;
    }

    int remaining() {
        return this.buf.remaining();
    }

    int position() {
        return this.buf.position();
    }

    int getVal() {
        return this.val;
    }

    int getRange() {
        return this.range;
    }

}