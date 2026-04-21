package org.watermedia.api.codecs.common.png;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * tEXt - Textual Data Chunk
 * Contains uncompressed Latin-1 textual data with a keyword-value pair
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11tEXt">PNG Specification - tEXt</a>
 */
public record TEXT(String keyword, String text) {
    public static final int SIGNATURE = 0x74_45_58_74; // "tEXt"

    // PREDEFINED KEYWORDS
    public static final String KEYWORD_TITLE = "Title";
    public static final String KEYWORD_AUTHOR = "Author";
    public static final String KEYWORD_DESCRIPTION = "Description";
    public static final String KEYWORD_COPYRIGHT = "Copyright";
    public static final String KEYWORD_CREATION_TIME = "Creation Time";
    public static final String KEYWORD_SOFTWARE = "Software";
    public static final String KEYWORD_DISCLAIMER = "Disclaimer";
    public static final String KEYWORD_WARNING = "Warning";
    public static final String KEYWORD_SOURCE = "Source";
    public static final String KEYWORD_COMMENT = "Comment";

    /**
     * Reads tEXt chunk from buffer (reads length/type header first)
     */
    public static TEXT read(final ByteBuffer buffer) {
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        if (type != SIGNATURE)
            throw new IllegalArgumentException("Invalid chunk type for tEXt: 0x" + Integer.toHexString(type));

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

        // READ TEXT (REMAINING BYTES)
        final int textLength = length - bytesRead;
        final byte[] textBytes = new byte[textLength];
        buffer.get(textBytes);
        final String text = new String(textBytes, StandardCharsets.ISO_8859_1);

        return new TEXT(keyword, text);
    }

    /**
     * Converts a generic CHUNK to TEXT
     */
    public static TEXT convert(final CHUNK chunk) {
        if (chunk.type() != SIGNATURE) {
            throw new IllegalArgumentException("Invalid chunk type for tEXt: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();

        // FIND NULL SEPARATOR BETWEEN KEYWORD AND TEXT
        int nullIndex = -1;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                nullIndex = i;
                break;
            }
        }

        if (nullIndex < 1 || nullIndex > 79) {
            throw new IllegalArgumentException("Invalid tEXt: keyword must be 1-79 characters");
        }

        final String keyword = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final String text = (nullIndex + 1 < data.length)
                ? new String(data, nullIndex + 1, data.length - nullIndex - 1, StandardCharsets.ISO_8859_1)
                : "";

        return new TEXT(keyword, text);
    }

    public byte[] toBytes() {
        final byte[] keywordBytes = this.keyword.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] textBytes = this.text.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] data = new byte[keywordBytes.length + 1 + textBytes.length];
        System.arraycopy(keywordBytes, 0, data, 0, keywordBytes.length);
        data[keywordBytes.length] = 0;
        System.arraycopy(textBytes, 0, data, keywordBytes.length + 1, textBytes.length);
        return data;
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
