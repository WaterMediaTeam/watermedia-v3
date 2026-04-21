package org.watermedia.api.codecs.common.png;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    /**
     * Reads zTXt chunk from buffer (reads length/type header first)
     */
    public static ZTXT read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for zTXt: 0x" + Integer.toHexString(type));

        // READ KEYWORD (1-79 BYTES + NULL)
        final StringBuilder keywordBuilder = new StringBuilder();
        byte b;
        int bytesRead = 0;
        while (bytesRead < 80 && buffer.hasRemaining() && (b = buffer.get()) != 0) {
            keywordBuilder.append((char) (b & 0xFF));
            bytesRead++;
        }
        bytesRead++; // COUNT NULL TERMINATOR

        final String keyword = keywordBuilder.toString();
        final int compressionMethod = buffer.get() & 0xFF;
        bytesRead++;

        // READ COMPRESSED TEXT (REMAINING BYTES)
        final int textLength = length - bytesRead;
        final byte[] compressedText = new byte[textLength];
        buffer.get(compressedText);

        return new ZTXT(keyword, compressionMethod, compressedText);
    }

    /**
     * Converts a generic CHUNK to ZTXT
     */
    public static ZTXT convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for zTXt: 0x" + Integer.toHexString(chunk.type()));
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
            throw new IllegalArgumentException("Invalid zTXt: missing or empty keyword");
        }

        final String keyword = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final int compressionMethod = data[nullIndex + 1] & 0xFF;

        if (compressionMethod != 0) {
            throw new IllegalArgumentException("Unknown zTXt compression method: " + compressionMethod);
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
    public String getText() throws IOException {
        final Inflater inflater = new Inflater();
        inflater.setInput(this.compressedText);

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1024];

        try {
            while (!inflater.finished()) {
                final int length = inflater.inflate(buffer);
                if (length == 0 && inflater.needsInput()) {
                    break;
                }
                output.write(buffer, 0, length);
            }
        } catch (final DataFormatException e) {
            throw new IOException("Invalid compressed text: " + e.getMessage());
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
