package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.XCodecException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * zTXt - Compressed Textual Data Chunk
 * Contains zlib-compressed Latin-1 textual data with a keyword-value pair
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11zTXt">PNG Specification - zTXt</a>
 */
public record ZTXT(String keyword, int compressionMethod, byte[] compressedText) {
    public static final int SIGNATURE = 0x7A_54_58_74; // "zTXt"
    // CAP DECOMPRESSED TEXT: A FEW COMPRESSED BYTES CAN INFLATE TO GIGABYTES (DECOMPRESSION BOMB)
    private static final int MAX_DECOMPRESSED = 2 * 1024 * 1024; // 2 MB

    /**
     * Reads zTXt chunk from buffer (reads length/type header first)
     */
    public static ZTXT read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 8) throw new XCodecException("Truncated zTXt chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new XCodecException("Invalid chunk type for zTXt: 0x" + Integer.toHexString(type));
        if (length < 0 || buffer.remaining() < length)
            throw new XCodecException("Truncated zTXt chunk");

        // READ KEYWORD (1-79 BYTES + NULL)
        final int endPosition = buffer.position() + length;
        final StringBuilder keywordBuilder = new StringBuilder();
        byte b;
        int bytesRead = 0;
        while (bytesRead < 80 && buffer.position() < endPosition && (b = buffer.get()) != 0) {
            keywordBuilder.append((char) (b & 0xFF));
            bytesRead++;
        }
        bytesRead++; // COUNT NULL TERMINATOR

        final String keyword = keywordBuilder.toString();
        if (buffer.position() >= endPosition) throw new XCodecException("Truncated zTXt: missing compression method");
        final int compressionMethod = buffer.get() & 0xFF;
        bytesRead++;

        // READ COMPRESSED TEXT (REMAINING BYTES): AN UNTERMINATED KEYWORD OVERSHOOTS AND GOES NEGATIVE
        final int textLength = length - bytesRead;
        if (textLength < 0) throw new XCodecException("Invalid zTXt: unterminated keyword");
        final byte[] compressedText = new byte[textLength];
        buffer.get(compressedText);

        return new ZTXT(keyword, compressionMethod, compressedText);
    }

    /**
     * Converts a generic CHUNK to ZTXT
     */
    public static ZTXT convert(final CHUNK chunk) throws XCodecException {
        if (chunk.type() != SIGNATURE) {
            throw new XCodecException("Invalid chunk type for zTXt: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();

        // FIND NULL SEPARATOR AFTER KEYWORD
        int nullIndex = -1;
        for (int i = 0; i < Math.min(80, data.length); i++) {
            if (data[i] == 0) {
                nullIndex = i;
                break;
            }
        }

        if (nullIndex < 1) {
            throw new XCodecException("Invalid zTXt: missing or empty keyword");
        }

        // NEED THE COMPRESSION-METHOD BYTE AFTER THE KEYWORD NUL; A TRUNCATED PAYLOAD OTHERWISE READS PAST THE ARRAY
        if (data.length < nullIndex + 2) {
            throw new XCodecException("Truncated zTXt: missing compression method");
        }

        final String keyword = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final int compressionMethod = data[nullIndex + 1] & 0xFF;

        if (compressionMethod != 0) {
            throw new XCodecException("Unknown zTXt compression method: " + compressionMethod);
        }

        // REMAINING DATA IS COMPRESSED TEXT
        final int textStart = nullIndex + 2;
        final byte[] compressedText = new byte[data.length - textStart];
        System.arraycopy(data, textStart, compressedText, 0, compressedText.length);

        return new ZTXT(keyword, compressionMethod, compressedText);
    }

    /**
     * Decompresses and returns the text
     */
    public String getText() throws XCodecException {
        final Inflater inflater = new Inflater();
        inflater.setInput(this.compressedText);

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1024];

        try {
            // EVERY ITERATION EITHER PRODUCES BYTES OR LEAVES THE LOOP: A ZLIB HEADER WITH FDICT SET
            // RETURNS Z_NEED_DICT (0 IN, 0 OUT, needsInput() FALSE) AND WOULD OTHERWISE SPIN FOREVER
            while (true) {
                final int length = inflater.inflate(buffer);
                if (length > 0) {
                    if (output.size() + length > MAX_DECOMPRESSED) {
                        throw new XCodecException("zTXt exceeds " + MAX_DECOMPRESSED + " bytes decompressed");
                    }
                    output.write(buffer, 0, length);
                    continue;
                }
                if (inflater.finished()) break;
                if (inflater.needsDictionary()) {
                    throw new XCodecException("zTXt uses an unsupported compression dictionary");
                }
                if (inflater.needsInput()) break; // TRUNCATED TEXT: KEEP WHAT DECOMPRESSED, THE IMAGE IS STILL USABLE
                throw new XCodecException("Invalid compressed text stream");
            }
        } catch (final DataFormatException e) {
            throw new XCodecException("Invalid compressed text: " + e.getMessage());
        } finally {
            inflater.end();
        }

        return output.toString(StandardCharsets.ISO_8859_1);
    }

    public byte[] toBytes() {
        final byte[] keywordBytes = this.keyword.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] data = new byte[keywordBytes.length + 1 + 1 + this.compressedText.length];
        System.arraycopy(keywordBytes, 0, data, 0, keywordBytes.length);
        data[keywordBytes.length] = 0;
        data[keywordBytes.length + 1] = (byte) this.compressionMethod;
        System.arraycopy(this.compressedText, 0, data, keywordBytes.length + 2, this.compressedText.length);
        return data;
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
