package org.watermedia.api.codecs.readers.netpbm;

import org.watermedia.api.codecs.XCodecException;

import java.nio.ByteBuffer;

/**
 * Parsed Netpbm header. Produced by {@link #parse(ByteBuffer)}; {@code depth} and {@code maxVal}
 * are {@code null} when the variant does not declare them ({@code tuplType} follows the PAM
 * spec spelling).
 */
public record NetpbmHeader(String versionString, int version, int width, int height,
                           Integer depth, Integer maxVal, String tuplType) {

    /**
     * Parses an ASCII Netpbm header starting at the {@code Pn} version token and validates it.
     *
     * @throws XCodecException when the header is malformed or declares out-of-range values
     */
    public static NetpbmHeader parse(final ByteBuffer data) throws XCodecException {
        String versionString = null;
        int version = 0;
        int width = 0;
        int height = 0;
        Integer depth = null;
        Integer maxVal = null;
        final StringBuilder tuplType = new StringBuilder();
        int tokenIndex = 0;

        label:
        while (data.hasRemaining()) {
            final String token = nextToken(data);
            if (token.isEmpty()) break;
            tokenIndex++;

            if (tokenIndex == 1 && token.startsWith("P")) {
                versionString = token;
                version = parseInt(token.substring(1));
                continue;
            }

            switch (version) {
                case 4 -> {
                    // EXPECT WIDTH AND HEIGHT IN ORDER
                    switch (tokenIndex) {
                        case 2 -> width = parseInt(token);
                        case 3 -> {
                            height = parseInt(token);
                            break label;
                        }
                    }
                }
                case 5, 6 -> {
                    // EXPECT WIDTH, HEIGHT, MAXVAL IN ORDER
                    switch (tokenIndex) {
                        case 2 -> width = parseInt(token);
                        case 3 -> height = parseInt(token);
                        case 4 -> {
                            maxVal = parseInt(token);
                            break label;
                        }
                    }
                }
                case 7 -> {
                    // EXPECT KEY-VALUE PAIRS UNTIL ENDHDR
                    switch (token) {
                        case "WIDTH" -> width = nextTokenInt(data);
                        case "HEIGHT" -> height = nextTokenInt(data);
                        case "DEPTH" -> depth = nextTokenInt(data);
                        case "MAXVAL" -> maxVal = nextTokenInt(data);
                        case "TUPLTYPE" -> {
                            if (!tuplType.isEmpty()) tuplType.append(' ');
                            tuplType.append(readRestOfLine(data).trim());
                        }
                        case "ENDHDR" -> {
                            break label;
                        }
                    }
                }
                default -> throw new XCodecException("Unsupported Netpbm version: " + version);
            }
        }

        if (width <= 0) throw new XCodecException("Invalid WIDTH: " + width + ". Must be greater than 0.");
        if (height <= 0) throw new XCodecException("Invalid HEIGHT: " + height + ". Must be greater than 0.");
        if (version == 7) {
            // ENFORCE THE UPPER BOUND THE MESSAGE ALREADY CLAIMS; AN UNBOUNDED DEPTH OVERFLOWS THE RASTER MATH
            if (depth == null || depth <= 0 || depth > 65535)
                throw new XCodecException("Invalid DEPTH: " + depth + ". Must be between 1 and 65535.");
        }

        // PGM/PPM/PAM ALL REQUIRE MAXVAL; A TRUNCATED HEADER LEAVES IT NULL AND NPEs THE DECODER UNDER A BLANKET CATCH
        if ((version == 5 || version == 6 || version == 7) && maxVal == null)
            throw new XCodecException("Missing MAXVAL for P" + version);

        if (maxVal != null && (maxVal <= 0 || maxVal > 65535))
            throw new XCodecException("Invalid MAXVAL: " + maxVal + ". Must be between 1 and 65535.");

        return new NetpbmHeader(versionString, version, width, height, depth, maxVal, tuplType.toString());
    }

    private static int nextTokenInt(final ByteBuffer buf) throws XCodecException {
        final String valueToken = nextToken(buf);
        if (valueToken.isBlank()) throw new XCodecException("Expected integer token but found blank");
        return parseInt(valueToken);
    }

    private static int parseInt(final String string) throws XCodecException {
        try {
            return Integer.parseInt(string);
        } catch (final NumberFormatException e) {
            throw new XCodecException(e);
        }
    }

    private static String nextToken(final ByteBuffer buf) {
        final StringBuilder sb = new StringBuilder();
        boolean inComment = false;

        while (buf.hasRemaining()) {
            final char c = (char) buf.get();
            if (c == '#') inComment = true;
            if (inComment) {
                if (c == '\n') inComment = false;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!sb.isEmpty()) break;
                else continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String readRestOfLine(final ByteBuffer buf) {
        final StringBuilder sb = new StringBuilder();
        while (buf.hasRemaining()) {
            final char c = (char) buf.get();
            if (c == '\n') break;
            sb.append(c);
        }
        return sb.toString();
    }
}
