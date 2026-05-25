package org.watermedia.api.codecs.common.webp;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WebP Lossless/Common Bit Reader - LSB-first bit extraction
 * Used by VP8L decoder and alpha channel processing
 * <p>
 * Bits are read LSB-first (least significant bit first) within each byte.
 * Multi-bit reads accumulate bits in LSB-first order:
 * read(2) with bits [b0, b1] returns b0 | (b1 << 1)
 */
public final class BitReader {
    public static final int MAX_BURST_BITS = 24;

    private final ByteBuffer buf;
    private long bitBuf;     // LSB-aligned accumulator, low `bitsAvail` bits valid
    private int bitsAvail;

    public BitReader(final ByteBuffer buffer) {
        this.buf = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        this.bitBuf = 0L;
        this.bitsAvail = 0;
    }

    public BitReader(final ByteBuffer buffer, final int off, final int len) {
        final ByteBuffer slice = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        slice.position(off);
        slice.limit(off + len);
        this.buf = slice.slice().order(ByteOrder.LITTLE_ENDIAN);
        this.bitBuf = 0L;
        this.bitsAvail = 0;
    }

    // ENSURE AT LEAST 'BITS' BITS ARE AVAILABLE. PREFERS BATCHED 32-BIT REFILL WHEN POSSIBLE,
    // FALLS BACK TO BYTE-BY-BYTE NEAR EOF. RETURNS ACTUAL BITS AVAILABLE (MAY BE LESS THAN
    // REQUESTED IF EOF).
    private int ensureBits(final int bits) {
        if (this.bitsAvail >= bits) return this.bitsAvail;
        while (this.bitsAvail <= 32 && this.buf.remaining() >= 4) {
            final long word = this.buf.getInt() & 0xFFFFFFFFL;
            this.bitBuf |= word << this.bitsAvail;
            this.bitsAvail += 32;
        }
        while (this.bitsAvail < bits && this.buf.hasRemaining()) {
            this.bitBuf |= ((long) (this.buf.get() & 0xFF)) << this.bitsAvail;
            this.bitsAvail += 8;
        }
        return this.bitsAvail;
    }

    /**
     * Peek at N bits without consuming them (LSB first).
     * Max 24 bits at once.
     * If there aren't enough bits available, returns what's available padded with zeros.
     */
    public int peek(final int bits) throws XCodecException {
        if (bits == 0) return 0;
        if (bits > MAX_BURST_BITS) throw new XCodecException("Cannot peek more than 24 bits at once");

        final int available = this.ensureBits(bits);
        if (available == 0) return 0;
        final int actualBits = Math.min(bits, available);
        return (int) (this.bitBuf & ((1L << actualBits) - 1L));
    }

    /**
     * Read N bits (LSB first) - max 24 bits at once.
     * Consumes the bits from the buffer.
     */
    public int read(final int bits) throws XCodecException {
        if (bits == 0) return 0;
        if (bits > MAX_BURST_BITS) throw new XCodecException("Cannot read more than 24 bits at once");

        final int available = this.ensureBits(bits);
        if (available < bits) {
            throw new XCodecException("Unexpected end of data: needed " + bits + " bits, only " + available + " available");
        }

        final int result = (int) (this.bitBuf & ((1L << bits) - 1L));
        this.bitBuf >>>= bits;
        this.bitsAvail -= bits;
        return result;
    }

    /**
     * Read single bit as boolean.
     */
    public boolean readBool() throws XCodecException {
        if (this.bitsAvail == 0) this.ensureBits(1);
        if (this.bitsAvail == 0) throw new XCodecException("Unexpected end of data: needed 1 bit, only 0 available");
        final boolean result = (this.bitBuf & 1L) != 0L;
        this.bitBuf >>>= 1;
        this.bitsAvail -= 1;
        return result;
    }

    /**
     * Read signed value with sign extension.
     */
    public int readSigned(final int bits) throws XCodecException {
        int v = this.read(bits);
        final int signBit = 1 << (bits - 1);
        if ((v & signBit) != 0) {
            v |= ~((1 << bits) - 1);
        }
        return v;
    }

    /**
     * Skip N bits (consume without returning).
     */
    public void skip(final int bits) throws XCodecException {
        if (bits <= this.bitsAvail) {
            this.bitBuf >>>= bits;
            this.bitsAvail -= bits;
        } else {
            this.read(bits);
        }
    }

    /**
     * Drop N already-available bits without bounds checking.
     * MUST be paired with a successful {@link #peek(int)} that returned at least {@code bits} bits.
     */
    public void consume(final int bits) {
        this.bitBuf >>>= bits;
        this.bitsAvail -= bits;
    }

    /**
     * Number of bits currently buffered without touching the underlying ByteBuffer.
     */
    public int bitsAvail() {
        return this.bitsAvail;
    }

    /**
     * Make sure the accumulator has at least the requested number of bits when possible.
     * Returns the actual bits available (may be less than requested at EOF).
     */
    public int prefetch(final int bits) {
        return this.ensureBits(bits);
    }

    public void readBytes(final byte[] dest, final int off, final int len) {
        this.flushBits();
        this.buf.get(dest, off, len);
    }

    public void flushBits() {
        this.bitBuf = 0L;
        this.bitsAvail = 0;
    }

    public void skipBytes(final int bytes) {
        this.flushBits();
        this.buf.position(this.buf.position() + bytes);
    }

    public int remaining() {
        return this.buf.remaining();
    }

    public String bitPosition() {
        return "bytes=" + this.buf.remaining() + ",bitsAvail=" + this.bitsAvail + ",bitBuf=0x" + Long.toHexString(this.bitBuf);
    }

    public int position() {
        return this.buf.position();
    }

    public void position(final int pos) {
        this.flushBits();
        this.buf.position(pos);
    }

    public boolean hasRemaining() {
        return this.buf.hasRemaining() || this.bitsAvail > 0;
    }

    public ByteBuffer buffer() {
        return this.buf;
    }
}
