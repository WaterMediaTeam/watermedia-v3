package org.watermedia.api.codecs.common.png;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * sPLT - Suggested Palette Chunk
 * Contains a suggested palette for use when the display device has limited colors
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11sPLT">PNG Specification - sPLT</a>
 */
public record SPLT(String paletteName, int sampleDepth, SPLTEntry[] entries) {
    public static final int SIGNATURE = 0x73_50_4C_54; // "sPLT"

    /**
     * A single entry in the suggested palette
     */
    public record SPLTEntry(int red, int green, int blue, int alpha, int frequency) {
        /**
         * Returns the entry as packed ARGB (scaled to 8-bit if necessary)
         */
        public int toARGB(final int sampleDepth) {
            if (sampleDepth == 8) {
                return (this.alpha << 24) | (this.red << 16) | (this.green << 8) | this.blue;
            } else { // 16-BIT
                return ((this.alpha >> 8) << 24) | ((this.red >> 8) << 16) |
                        ((this.green >> 8) << 8) | (this.blue >> 8);
            }
        }
    }

    /**
     * Reads sPLT chunk from buffer (reads length/type header first)
     */
    public static SPLT read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for sPLT: 0x" + Integer.toHexString(type));

        final int startPosition = buffer.position();

        // READ PALETTE NAME (1-79 BYTES + NULL)
        final StringBuilder nameBuilder = new StringBuilder();
        byte b;
        while (buffer.hasRemaining() && (b = buffer.get()) != 0) {
            nameBuilder.append((char) (b & 0xFF));
        }
        final String paletteName = nameBuilder.toString();

        // SAMPLE DEPTH (1 BYTE)
        final int sampleDepth = buffer.get() & 0xFF;

        // CALCULATE ENTRY SIZE AND COUNT
        final int entrySize = (sampleDepth == 8) ? 6 : 10;
        final int bytesRead = buffer.position() - startPosition;
        final int entryDataLength = length - bytesRead;
        final int entryCount = entryDataLength / entrySize;

        final SPLTEntry[] entries = new SPLTEntry[entryCount];
        for (int i = 0; i < entryCount; i++) {
            final int red, green, blue, alpha, frequency;

            if (sampleDepth == 8) {
                red = buffer.get() & 0xFF;
                green = buffer.get() & 0xFF;
                blue = buffer.get() & 0xFF;
                alpha = buffer.get() & 0xFF;
                frequency = buffer.getShort() & 0xFFFF;
            } else { // 16-BIT
                red = buffer.getShort() & 0xFFFF;
                green = buffer.getShort() & 0xFFFF;
                blue = buffer.getShort() & 0xFFFF;
                alpha = buffer.getShort() & 0xFFFF;
                frequency = buffer.getShort() & 0xFFFF;
            }

            entries[i] = new SPLTEntry(red, green, blue, alpha, frequency);
        }

        return new SPLT(paletteName, sampleDepth, entries);
    }

    /**
     * Converts a generic CHUNK to SPLT
     */
    public static SPLT convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for sPLT: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();

        // FIND NULL SEPARATOR AFTER PALETTE NAME
        int nullIndex = -1;
        for (int i = 0; i < Math.min(80, data.length); i++) {
            if (data[i] == 0) {
                nullIndex = i;
                break;
            }
        }

        if (nullIndex < 1) {
            throw new IllegalArgumentException("Invalid sPLT: missing or empty palette name");
        }

        final String paletteName = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final int sampleDepth = data[nullIndex + 1] & 0xFF;

        if (sampleDepth != 8 && sampleDepth != 16) {
            throw new IllegalArgumentException("Invalid sPLT sample depth: " + sampleDepth);
        }

        // CALCULATE ENTRY SIZE AND COUNT
        final int entrySize = (sampleDepth == 8) ? 6 : 10; // RGBA + FREQUENCY
        final int entryDataStart = nullIndex + 2;
        final int entryDataLength = data.length - entryDataStart;

        if (entryDataLength % entrySize != 0) {
            throw new IllegalArgumentException("Invalid sPLT entry data length");
        }

        final int entryCount = entryDataLength / entrySize;
        final SPLTEntry[] entries = new SPLTEntry[entryCount];
        final ByteBuffer buffer = ByteBuffer.wrap(data, entryDataStart, entryDataLength).order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < entryCount; i++) {
            final int red, green, blue, alpha, frequency;

            if (sampleDepth == 8) {
                red = buffer.get() & 0xFF;
                green = buffer.get() & 0xFF;
                blue = buffer.get() & 0xFF;
                alpha = buffer.get() & 0xFF;
                frequency = buffer.getShort() & 0xFFFF;
            } else { // 16-BIT
                red = buffer.getShort() & 0xFFFF;
                green = buffer.getShort() & 0xFFFF;
                blue = buffer.getShort() & 0xFFFF;
                alpha = buffer.getShort() & 0xFFFF;
                frequency = buffer.getShort() & 0xFFFF;
            }

            entries[i] = new SPLTEntry(red, green, blue, alpha, frequency);
        }

        return new SPLT(paletteName, sampleDepth, entries);
    }

    /**
     * Returns the number of entries
     */
    public int size() {
        return this.entries.length;
    }

    /**
     * Returns a specific entry
     */
    public SPLTEntry getEntry(final int index) {
        return this.entries[index];
    }

    public byte[] toBytes() {
        final byte[] nameBytes = this.paletteName.getBytes(StandardCharsets.ISO_8859_1);
        final int entrySize = (this.sampleDepth == 8) ? 6 : 10;
        final ByteBuffer buf = ByteBuffer.allocate(nameBytes.length + 1 + 1 + this.entries.length * entrySize).order(ByteOrder.BIG_ENDIAN);
        buf.put(nameBytes);
        buf.put((byte) 0);
        buf.put((byte) this.sampleDepth);
        for (final SPLTEntry entry : this.entries) {
            if (this.sampleDepth == 8) {
                buf.put((byte) entry.red());
                buf.put((byte) entry.green());
                buf.put((byte) entry.blue());
                buf.put((byte) entry.alpha());
            } else {
                buf.putShort((short) entry.red());
                buf.putShort((short) entry.green());
                buf.putShort((short) entry.blue());
                buf.putShort((short) entry.alpha());
            }
            buf.putShort((short) entry.frequency());
        }
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
