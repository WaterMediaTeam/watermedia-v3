package org.watermedia.api.decode.formats.webp.common;

import org.watermedia.api.decode.DecoderException;

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
    private final ByteBuffer buf;
    private int bitBuf;      // Accumulated bits buffer
    private int bitsAvail;   // Number of available bits in buffer

    public BitReader(final ByteBuffer buffer) {
        this.buf = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        this.bitBuf = 0;
        this.bitsAvail = 0;
    }

    public BitReader(final ByteBuffer buffer, final int off, final int len) {
        final ByteBuffer slice = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        slice.position(off);
        slice.limit(off + len);
        this.buf = slice.slice().order(ByteOrder.LITTLE_ENDIAN);
        this.bitBuf = 0;
        this.bitsAvail = 0;
    }

    /**
     * Ensure at least 'bits' bits are available in the buffer.
     * Loads more bytes from the stream if needed.
     * Returns actual number of bits available (may be less than requested if EOF).
     */
    private int ensureBits(final int bits) {
        while (this.bitsAvail < bits && this.buf.hasRemaining()) {
            this.bitBuf |= (this.buf.get() & 0xFF) << this.bitsAvail;
            this.bitsAvail += 8;
        }
        return this.bitsAvail;
    }

    /**
     * Peek at N bits without consuming them (LSB first).
     * Max 24 bits at once.
     * If there aren't enough bits available, returns what's available padded with zeros.
     */
    public int peek(final int bits) throws DecoderException {
        if (bits == 0) return 0;
        if (bits > 24) throw new DecoderException("Cannot peek more than 24 bits at once");

        final int available = this.ensureBits(bits);
        final int actualBits = Math.min(bits, available);
        if (actualBits == 0) {
            return 0;  // No bits available
        }
        return this.bitBuf & ((1 << actualBits) - 1);
    }

    /**
     * Read N bits (LSB first) - max 24 bits at once.
     * Consumes the bits from the buffer.
     */
    public int read(final int bits) throws DecoderException {
        if (bits == 0) return 0;
        if (bits > 24) throw new DecoderException("Cannot read more than 24 bits at once");

        final int available = this.ensureBits(bits);
        if (available < bits) {
            throw new DecoderException("Unexpected end of data: needed " + bits + " bits, only " + available + " available");
        }

        final int result = this.bitBuf & ((1 << bits) - 1);
        this.bitBuf >>>= bits;
        this.bitsAvail -= bits;
        return result;
    }

    /**
     * Read single bit as boolean.
     */
    public boolean readBool() throws DecoderException {
        return this.read(1) == 1;
    }

    /**
     * Read signed value with sign extension.
     */
    public int readSigned(final int bits) throws DecoderException {
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
    public void skip(final int bits) throws DecoderException {
        if (bits <= this.bitsAvail) {
            this.bitBuf >>>= bits;
            this.bitsAvail -= bits;
        } else {
            // Need to read more
            this.read(bits);
        }
    }

    public void readBytes(final byte[] dest, final int off, final int len) {
        this.flushBits();
        this.buf.get(dest, off, len);
    }

    public void flushBits() {
        this.bitBuf = 0;
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
        return "bytes=" + this.buf.remaining() + ",bitsAvail=" + this.bitsAvail + ",bitBuf=0x" + Integer.toHexString(this.bitBuf);
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