package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * tIME - Image Last-Modification Time Chunk
 * Stores the time of the last image modification
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11tIME">PNG Specification - tIME</a>
 */
public record TIME(int year, int month, int day, int hour, int minute, int second) {
    public static final int SIGNATURE = 0x74_49_4D_45; // "tIME"
    public static final int LENGTH = 7;

    /**
     * Reads tIME chunk from buffer (reads length/type header first)
     */
    public static TIME read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 8) throw new XCodecException("Truncated tIME chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new XCodecException("Invalid chunk type for tIME: 0x" + Integer.toHexString(type));
        if (length != LENGTH)
            throw new XCodecException("tIME chunk length must be 7, got " + Integer.toUnsignedString(length));
        if (buffer.remaining() < LENGTH) throw new XCodecException("Truncated tIME chunk");

        return new TIME(
                buffer.getShort() & 0xFFFF, // YEAR (2 BYTES)
                buffer.get() & 0xFF,        // MONTH (1 BYTE, 1-12)
                buffer.get() & 0xFF,        // DAY (1 BYTE, 1-31)
                buffer.get() & 0xFF,        // HOUR (1 BYTE, 0-23)
                buffer.get() & 0xFF,        // MINUTE (1 BYTE, 0-59)
                buffer.get() & 0xFF         // SECOND (1 BYTE, 0-60, 60 FOR LEAP SECOND)
        );
    }

    /**
     * Converts a generic CHUNK to TIME
     */
    public static TIME convert(final CHUNK chunk) throws XCodecException {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for tIME: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        if (data.length != LENGTH) {
            throw new XCodecException("tIME data must be 7 bytes, got " + data.length);
        }

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new TIME(
                buffer.getShort() & 0xFFFF, // YEAR (2 BYTES)
                buffer.get() & 0xFF,        // MONTH (1 BYTE, 1-12)
                buffer.get() & 0xFF,        // DAY (1 BYTE, 1-31)
                buffer.get() & 0xFF,        // HOUR (1 BYTE, 0-23)
                buffer.get() & 0xFF,        // MINUTE (1 BYTE, 0-59)
                buffer.get() & 0xFF         // SECOND (1 BYTE, 0-60, 60 FOR LEAP SECOND)
        );
    }

    /**
     * Returns the modification time as a LocalDateTime
     */
    public LocalDateTime toLocalDateTime() {
        // CLAMP EVERY FIELD: THE CHUNK CARRIES RAW BYTES, AND LocalDateTime.of THROWS DateTimeException
        // ON ANYTHING OUT OF RANGE (LEAP SECOND 60, MONTH 0, DAY 31 IN FEBRUARY, ...)
        final int m = Math.min(Math.max(this.month, 1), 12);
        final int d = Math.min(Math.max(this.day, 1), YearMonth.of(this.year, m).lengthOfMonth());
        return LocalDateTime.of(this.year, m, d,
                Math.min(this.hour, 23), Math.min(this.minute, 59), Math.min(this.second, 59));
    }

    /**
     * Returns the modification time as UTC ZonedDateTime
     */
    public ZonedDateTime toZonedDateTime() {
        return this.toLocalDateTime().atZone(ZoneOffset.UTC);
    }

    /**
     * Returns ISO 8601 formatted string
     */
    public String toISO8601() {
        return String.format("%04d-%02d-%02dT%02d:%02d:%02dZ",
                this.year, this.month, this.day, this.hour, this.minute, this.second);
    }

    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) this.year);
        buf.put((byte) this.month);
        buf.put((byte) this.day);
        buf.put((byte) this.hour);
        buf.put((byte) this.minute);
        buf.put((byte) this.second);
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
