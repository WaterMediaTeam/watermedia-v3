package org.watermedia.api.codecs.readers.netpbm;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;

import java.nio.ByteBuffer;

/**
 * Parsed Netpbm header for the plain ({@code P1}..{@code P3}), binary ({@code P4}..{@code P6}) and
 * PAM ({@code P7}) variants. Produced by {@link #parse(ByteBuffer)}; {@code depth} and {@code maxVal}
 * are {@code null} when the variant does not declare them ({@code tuplType} follows the PAM
 * spec spelling).
 */
public record NetpbmHeader(int version, int width, int height, Integer depth, Integer maxVal, String tuplType) {

    // TUPLTYPE IS THE ONLY FREE-FORM HEADER VALUE AND IT REACHES EXCEPTION MESSAGES — AND THE LOG —
    // VERBATIM. A REAL PAM HEADER IS UNDER 200 BYTES IN TOTAL, SO THIS IS ALREADY GENEROUS
    private static final int MAX_TUPLTYPE = 256;

    /**
     * Parses an ASCII Netpbm header starting at the {@code Pn} version token and validates it.
     *
     * @throws UnsupportedFormatException when a PAM header names a tuple type outside the six the reader decodes
     * @throws CodecException             when the header is malformed or declares out-of-range values
     */
    public static NetpbmHeader parse(final ByteBuffer data) throws CodecException {
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
                version = parseInt(token.substring(1));
                continue;
            }

            switch (version) {
                case 1, 4 -> {
                    // EXPECT WIDTH AND HEIGHT IN ORDER
                    switch (tokenIndex) {
                        case 2 -> width = parseInt(token);
                        case 3 -> {
                            height = parseInt(token);
                            break label;
                        }
                    }
                }
                case 2, 3, 5, 6 -> {
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
                            // A REPEATED TUPLTYPE IS REJECTED: CONCATENATING THEM WOULD LET ONE FILE GROW AN UNBOUNDED STRING
                            if (tuplTypeSeen) throw new CodecException("Duplicate PAM TUPLTYPE");
                            tuplTypeSeen = true;
                            tuplType = readRestOfLine(data).trim();
                        }
                        case "ENDHDR" -> {
                            break label;
                        }
                    }
                }
                default -> throw new CodecException("Unknown Netpbm version: " + version);
            }
        }

        if (width <= 0) throw new CodecException("Invalid WIDTH: " + width + ". Must be greater than 0.");
        if (height <= 0) throw new CodecException("Invalid HEIGHT: " + height + ". Must be greater than 0.");

        // EVERYTHING BUT A BITMAP REQUIRES MAXVAL; A TRUNCATED HEADER LEAVES IT NULL AND NPEs THE DECODER UNDER A BLANKET CATCH
        if (version != 1 && version != 4 && maxVal == null)
            throw new CodecException("Missing MAXVAL for P" + version);
        if (maxVal != null && (maxVal <= 0 || maxVal > 65_535))
            throw new CodecException("Invalid MAXVAL: " + maxVal + ". Must be between 1 and 65535.");

        if (version == 7) {
            // THE TUPLE TYPE BELOW BOUNDS DEPTH TO 1..4; A MISSING OR NON-POSITIVE ONE IS MALFORMED WHATEVER THE TYPE
            if (depth == null || depth <= 0)
                throw new CodecException("Invalid DEPTH: " + depth + ". Must be greater than 0.");

            // DEPTH SIZES EVERY ROW AND IS THE ONE RASTER DIMENSION THE PIXEL CAP DOES NOT COVER, SO IT IS BOUND TO
            // THE TUPLE TYPE: "DEPTH 65535 / TUPLTYPE RGB" IS A 2 GiB ROW DESCRIBED BY 69 BYTES. ANY OTHER TUPLE TYPE
            // (OR NONE) IS VALID PAM WITH NO SAMPLE LAYOUT THE READER KNOWS, SO IT IS REFUSED BEFORE A RASTER IS SIZED
            final int expectedDepth = switch (tuplType) {
                case "BLACKANDWHITE", "GRAYSCALE" -> 1;
                case "BLACKANDWHITE_ALPHA", "GRAYSCALE_ALPHA" -> 2;
                case "RGB" -> 3;
                case "RGB_ALPHA" -> 4;
                default -> throw new UnsupportedFormatException("Unsupported PAM tuple type: " + (tuplType.isEmpty() ? "(none)" : tuplType));
            };
            if (depth != expectedDepth)
                throw new CodecException("PAM DEPTH " + depth + " does not match TUPLTYPE " + tuplType);
            if (tuplType.startsWith("BLACKANDWHITE") && maxVal != 1)
                throw new CodecException("PAM " + tuplType + " requires MAXVAL 1, not " + maxVal);
        }

        return new NetpbmHeader(version, width, height, depth, maxVal, tuplType);
    }

    /**
     * Netpbm header whitespace, matching C {@code isspace()} — the raster begins right after one.
     * Shared with the reader's header pre-scanner: both parsers must agree on where tokens end.
     */
    public static boolean whitespace(final int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0x0C;
    }

    private static int nextTokenInt(final ByteBuffer buf) throws CodecException {
        final String valueToken = nextToken(buf);
        if (valueToken.isBlank()) throw new CodecException("Expected integer token but found blank");
        return parseInt(valueToken);
    }

    private static int parseInt(final String string) throws CodecException {
        try {
            return Integer.parseInt(string);
        } catch (final NumberFormatException e) {
            throw new CodecException(e);
        }
    }

    private static String nextToken(final ByteBuffer buf) {
        final StringBuilder sb = new StringBuilder();
        while (buf.hasRemaining()) {
            // & 0xFF: A BARE CAST SIGN-EXTENDS 0x80.. INTO U+FF80.., CORRUPTING THE TOKEN AND DOUBLING ITS FOOTPRINT
            final char c = (char) (buf.get() & 0xFF);
            if (c == '#') {
                // A COMMENT ENDS THE CURRENT TOKEN AND RUNS TO END OF LINE, SO "1#x\n6384" STAYS THE TWO
                // TOKENS EVERY REFERENCE DECODER READS INSTEAD OF MERGING INTO "16384"
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
    private static String readRestOfLine(final ByteBuffer buf) throws CodecException {
        final StringBuilder sb = new StringBuilder();
        while (buf.hasRemaining()) {
            final char c = (char) (buf.get() & 0xFF);
            if (c == '\n') break;
            if (sb.length() >= MAX_TUPLTYPE) throw new CodecException("PAM TUPLTYPE too long");
            sb.append(c >= 0x20 && c < 0x7F ? c : ' ');
        }
        return sb.toString();
    }
}
