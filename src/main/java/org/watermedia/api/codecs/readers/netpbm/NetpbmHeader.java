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

    // TUPLTYPE IS THE ONLY FREE-FORM HEADER VALUE AND IT REACHES EXCEPTION MESSAGES — AND THE LOG —
    // VERBATIM. A REAL PAM HEADER IS UNDER 200 BYTES IN TOTAL, SO THIS IS ALREADY GENEROUS
    private static final int MAX_TUPLTYPE = 256;

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
        String tuplType = "";
        boolean tuplTypeSeen = false;
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
                            // REPEATED TAGS USED TO CONCATENATE INSTEAD OF OVERWRITING, LETTING ONE
                            // FILE GROW A SINGLE ATTACKER-CHOSEN STRING WITHOUT ANY BOUND
                            if (tuplTypeSeen) throw new XCodecException("Duplicate PAM TUPLTYPE");
                            tuplTypeSeen = true;
                            tuplType = readRestOfLine(data).trim();
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

            // DEPTH SIZES EVERY ROW BUFFER AND IS THE ONE RASTER DIMENSION THE READER'S MAX_DIM DOES
            // NOT COVER, SO BIND IT TO THE TUPLE TYPE: "DEPTH 65535 / TUPLTYPE RGB" IS A 2 GiB ROW
            // BUFFER DESCRIBED BY 69 BYTES. UNKNOWN TUPLE TYPES CARRY NO SAMPLE COUNT AND ARE REFUSED
            // BY THE READER BEFORE ANY RASTER IS SIZED
            final int expectedDepth = switch (tuplType) {
                case "BLACKANDWHITE", "GRAYSCALE" -> 1;
                case "RGB" -> 3;
                case "RGB_ALPHA" -> 4;
                default -> 0;
            };
            if (expectedDepth != 0 && depth != expectedDepth)
                throw new XCodecException("PAM DEPTH " + depth + " does not match TUPLTYPE " + tuplType);
        }

        // PGM/PPM/PAM ALL REQUIRE MAXVAL; A TRUNCATED HEADER LEAVES IT NULL AND NPEs THE DECODER UNDER A BLANKET CATCH
        if ((version == 5 || version == 6 || version == 7) && maxVal == null)
            throw new XCodecException("Missing MAXVAL for P" + version);

        if (maxVal != null && (maxVal <= 0 || maxVal > 65535))
            throw new XCodecException("Invalid MAXVAL: " + maxVal + ". Must be between 1 and 65535.");

        return new NetpbmHeader(versionString, version, width, height, depth, maxVal, tuplType);
    }

    /**
     * Netpbm header whitespace, matching C {@code isspace()} — the raster begins right after one.
     * Shared with the reader's header pre-scanner: both parsers must agree on where tokens end.
     */
    public static boolean whitespace(final int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0x0C;
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
        while (buf.hasRemaining()) {
            // & 0xFF: A BARE CAST SIGN-EXTENDS 0x80.. INTO U+FF80.., CORRUPTING THE TOKEN AND DOUBLING ITS FOOTPRINT
            final char c = (char) (buf.get() & 0xFF);
            if (c == '#') {
                // A COMMENT ENDS THE CURRENT TOKEN AND RUNS TO END OF LINE. SWALLOWING ITS NEWLINE IN A
                // COMMENT BRANCH IS WHAT MERGED "1#x\n6384" INTO THE SINGLE TOKEN "16384" — EVERY
                // REFERENCE DECODER READS THAT AS TWO TOKENS, SO THE FORGED DIMENSION WAS A DIFFERENTIAL
                while (buf.hasRemaining() && buf.get() != '\n') { /* SKIP THE COMMENT BODY */ }
                if (!sb.isEmpty()) break;
                continue;
            }
            if (whitespace(c)) {
                if (!sb.isEmpty()) break;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // READS A TAG VALUE UP TO ITS END OF LINE. THE VALUE IS ATTACKER-CHOSEN AND IS INTERPOLATED INTO
    // MESSAGES THE PLAYER LOGS VERBATIM, SO IT IS BOUNDED AND EVERYTHING OUTSIDE PRINTABLE ASCII
    // BECOMES A SPACE — A RAW CR/LF WOULD LET AN IMAGE FORGE WHOLE LINES INTO latest.log
    private static String readRestOfLine(final ByteBuffer buf) throws XCodecException {
        final StringBuilder sb = new StringBuilder();
        while (buf.hasRemaining()) {
            final char c = (char) (buf.get() & 0xFF);
            if (c == '\n') break;
            if (sb.length() >= MAX_TUPLTYPE) throw new XCodecException("PAM TUPLTYPE too long");
            sb.append(c >= 0x20 && c < 0x7F ? c : ' ');
        }
        return sb.toString();
    }
}
