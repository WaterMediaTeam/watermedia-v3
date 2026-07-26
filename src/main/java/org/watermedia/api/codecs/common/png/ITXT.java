package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.XCodecException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * iTXt - International Textual Data Chunk
 * Contains optionally compressed UTF-8 textual data with language tag
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11iTXt">PNG Specification - iTXt</a>
 */
public record ITXT(String keyword, boolean compressed, int compressionMethod,
                   String languageTag, String translatedKeyword, byte[] textData) {
    public static final int SIGNATURE = 0x69_54_58_74; // "iTXt"
    // CAP DECOMPRESSED TEXT: A FEW COMPRESSED BYTES CAN INFLATE TO GIGABYTES (DECOMPRESSION BOMB)
    private static final int MAX_DECOMPRESSED = 2 * 1024 * 1024; // 2 MB

    /**
     * Reads iTXt chunk from buffer (reads length/type header first)
     */
    public static ITXT read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 8) throw new XCodecException("Truncated iTXt chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new XCodecException("Invalid chunk type for iTXt: 0x" + Integer.toHexString(type));
        if (length < 0 || buffer.remaining() < length)
            throw new XCodecException("Truncated iTXt chunk");

        final int endPosition = buffer.position() + length;

        // READ KEYWORD (1-79 BYTES + NULL). EVERY SCAN IS BOUNDED BY endPosition SO A MISSING
        // TERMINATOR CANNOT EAT PAST THE CHUNK AND DRIVE textLength NEGATIVE
        final StringBuilder keywordBuilder = new StringBuilder();
        byte b;
        while (buffer.position() < endPosition && (b = buffer.get()) != 0) {
            keywordBuilder.append((char) (b & 0xFF));
        }
        final String keyword = keywordBuilder.toString();

        // COMPRESSION FLAG + METHOD (2 BYTES)
        if (endPosition - buffer.position() < 2) throw new XCodecException("Truncated iTXt: missing compression flag/method");
        final boolean compressed = buffer.get() != 0;
        final int compressionMethod = buffer.get() & 0xFF;

        // LANGUAGE TAG (0+ BYTES + NULL)
        final StringBuilder langBuilder = new StringBuilder();
        while (buffer.position() < endPosition && (b = buffer.get()) != 0) {
            langBuilder.append((char) (b & 0xFF));
        }
        final String languageTag = langBuilder.toString();

        // TRANSLATED KEYWORD (0+ BYTES + NULL)
        final ByteArrayOutputStream translatedBytes = new ByteArrayOutputStream();
        while (buffer.position() < endPosition && (b = buffer.get()) != 0) {
            translatedBytes.write(b);
        }
        final String translatedKeyword = translatedBytes.toString(StandardCharsets.UTF_8);

        // TEXT DATA (REMAINING BYTES)
        final int textLength = endPosition - buffer.position();
        final byte[] textData = new byte[textLength];
        buffer.get(textData);

        return new ITXT(keyword, compressed, compressionMethod, languageTag, translatedKeyword, textData);
    }

    /**
     * Converts a generic CHUNK to ITXT
     */
    public static ITXT convert(final CHUNK chunk) throws XCodecException {
        if (chunk.type() != SIGNATURE) {
            throw new XCodecException("Invalid chunk type for iTXt: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();
        int offset = 0;

        // KEYWORD (1-79 BYTES + NULL). findNull RETURNS data.length WHEN NO TERMINATOR EXISTS
        int nullIndex = findNull(data, offset, 80);
        if (nullIndex < 1 || nullIndex >= data.length) {
            throw new XCodecException("Invalid iTXt: missing or empty keyword");
        }
        final String keyword = new String(data, offset, nullIndex - offset, StandardCharsets.ISO_8859_1);
        offset = nullIndex + 1;

        // COMPRESSION FLAG + METHOD (2 BYTES): A TRUNCATED PAYLOAD OTHERWISE READS PAST THE ARRAY
        if (data.length < offset + 2) {
            throw new XCodecException("Truncated iTXt: missing compression flag/method");
        }
        final boolean compressed = data[offset++] != 0;
        final int compressionMethod = data[offset++] & 0xFF;

        // LANGUAGE TAG (0+ BYTES + NULL)
        nullIndex = findNull(data, offset, data.length);
        if (nullIndex >= data.length) {
            throw new XCodecException("Truncated iTXt: missing language tag terminator");
        }
        final String languageTag = new String(data, offset, nullIndex - offset, StandardCharsets.US_ASCII);
        offset = nullIndex + 1;

        // TRANSLATED KEYWORD (0+ BYTES + NULL)
        nullIndex = findNull(data, offset, data.length);
        if (nullIndex >= data.length) {
            throw new XCodecException("Truncated iTXt: missing translated keyword terminator");
        }
        final String translatedKeyword = new String(data, offset, nullIndex - offset, StandardCharsets.UTF_8);
        offset = nullIndex + 1;

        // TEXT DATA (REMAINING BYTES)
        final byte[] textData = new byte[data.length - offset];
        System.arraycopy(data, offset, textData, 0, textData.length);

        return new ITXT(keyword, compressed, compressionMethod, languageTag, translatedKeyword, textData);
    }

    private static int findNull(final byte[] data, final int start, final int limit) {
        for (int i = start; i < Math.min(data.length, limit); i++) {
            if (data[i] == 0) return i;
        }
        return data.length;
    }

    /**
     * Returns the text content, decompressing if necessary
     */
    public String getText() throws XCodecException {
        if (!this.compressed) {
            return new String(this.textData, StandardCharsets.UTF_8);
        }

        if (this.compressionMethod != 0) {
            throw new XCodecException("Unknown compression method: " + this.compressionMethod);
        }

        final Inflater inflater = new Inflater();
        inflater.setInput(this.textData);

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1024];

        try {
            // EVERY ITERATION EITHER PRODUCES BYTES OR LEAVES THE LOOP: A ZLIB HEADER WITH FDICT SET
            // RETURNS Z_NEED_DICT (0 IN, 0 OUT, needsInput() FALSE) AND WOULD OTHERWISE SPIN FOREVER
            while (true) {
                final int length = inflater.inflate(buffer);
                if (length > 0) {
                    if (output.size() + length > MAX_DECOMPRESSED) {
                        throw new XCodecException("iTXt exceeds " + MAX_DECOMPRESSED + " bytes decompressed");
                    }
                    output.write(buffer, 0, length);
                    continue;
                }
                if (inflater.finished()) break;
                if (inflater.needsDictionary()) {
                    throw new XCodecException("iTXt uses an unsupported compression dictionary");
                }
                if (inflater.needsInput()) break; // TRUNCATED TEXT: KEEP WHAT DECOMPRESSED, THE IMAGE IS STILL USABLE
                throw new XCodecException("Invalid compressed text stream");
            }
        } catch (final DataFormatException e) {
            throw new XCodecException("Invalid compressed text: " + e.getMessage());
        } finally {
            inflater.end();
        }

        return output.toString(StandardCharsets.UTF_8);
    }

    public byte[] toBytes() {
        final byte[] keywordBytes = this.keyword.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] langBytes = this.languageTag.getBytes(StandardCharsets.US_ASCII);
        final byte[] translatedBytes = this.translatedKeyword.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(
                keywordBytes.length + 1 + 1 + 1 + langBytes.length + 1 + translatedBytes.length + 1 + this.textData.length
        );
        buf.put(keywordBytes);
        buf.put((byte) 0);
        buf.put((byte) (this.compressed ? 1 : 0));
        buf.put((byte) this.compressionMethod);
        buf.put(langBytes);
        buf.put((byte) 0);
        buf.put(translatedBytes);
        buf.put((byte) 0);
        buf.put(this.textData);
        return buf.array();
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
