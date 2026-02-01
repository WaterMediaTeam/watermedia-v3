package org.watermedia.api.decode.formats.netpbm;

import org.watermedia.api.decode.DecoderException;

import java.nio.ByteBuffer;

public final class NetpbmHeader {

    public String versionString;
    public int version;
    public int width;
    public int height;
    public Integer depth;
    public Integer maxVal;
    public String tuplType; //NOT a typo, matches PAM spec

    private int tokenIndex;

    public NetpbmHeader parse(final ByteBuffer data) throws DecoderException {
        this.tokenIndex = 0;
        StringBuilder tuplTypeBuilder = new StringBuilder();

        label:
        while (data.hasRemaining()) {
            final String token = this.nextToken(data);
            if (token.isEmpty()) break;

            if (this.tokenIndex == 1 && token.startsWith("P")) {
                this.versionString = token;
                this.version = this.parseInt(token.substring(1));
                continue;
            }

            switch (this.version) {
                case 4 -> {
                    //expect width and height in order
                    switch (this.tokenIndex) {
                        case 2 -> this.width = this.parseInt(token);
                        case 3 -> {
                            this.height = this.parseInt(token);
                            break label;
                        }
                    }
                }
                case 5, 6 -> {
                    //expect width, height, maxval in order
                    switch (this.tokenIndex) {
                        case 2 -> this.width = this.parseInt(token);
                        case 3 -> this.height = this.parseInt(token);
                        case 4 -> {
                            this.maxVal = this.parseInt(token);
                            break label;
                        }
                    }
                }
                case 7 -> {
                    //expect key-value pairs until ENDHDR
                    switch (token) {
                        case "WIDTH":
                            this.width = this.nextTokenInt(data);
                            break;
                        case "HEIGHT":
                            this.height = this.nextTokenInt(data);
                            break;
                        case "DEPTH":
                            this.depth = this.nextTokenInt(data);
                            break;
                        case "MAXVAL":
                            this.maxVal = this.nextTokenInt(data);
                            break;
                        case "TUPLTYPE":
                            if (!tuplTypeBuilder.isEmpty()) tuplTypeBuilder.append(' ');
                            tuplTypeBuilder.append(this.readRestOfLine(data).trim());
                            break;
                        case "ENDHDR":
                            break label;
                    }
                }
                default -> throw new DecoderException("Unsupported Netpbm version: " + this.version);
            }
        }

        if (this.width <= 0) throw new DecoderException("Invalid WIDTH: " + this.width + ". Must be greater than 0.");
        if (this.height <= 0) throw new DecoderException("Invalid HEIGHT: " + this.height + ". Must be greater than 0.");
        if (this.version == 7) {
            if (this.depth == null || this.depth <= 0)
                throw new DecoderException("Invalid DEPTH: " + this.depth + ". Must be between 1 and 65535.");
        }

        if (this.maxVal != null && (this.maxVal <= 0 || this.maxVal > 65535))
            throw new DecoderException("Invalid MAXVAL: " + this.maxVal + ". Must be between 1 and 65535.");

        this.tuplType = tuplTypeBuilder.toString();

        return this;
    }

    private int nextTokenInt(final ByteBuffer buf) throws DecoderException {
        final String valueToken = this.nextToken(buf);
        if (valueToken.isBlank()) throw new DecoderException("Expected integer token but found blank");
        return this.parseInt(valueToken);
    }

    private int parseInt(final String string) throws DecoderException {
        try {
            return Integer.parseInt(string);
        } catch (final NumberFormatException e) {
            throw new DecoderException(e);
        }
    }

    private String nextToken(final ByteBuffer buf) {
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

        final String result = sb.toString();
        if (!result.isEmpty()) this.tokenIndex++;
        return result;
    }

    private String readRestOfLine(final ByteBuffer buf) {
        final StringBuilder sb = new StringBuilder();
        while (buf.hasRemaining()) {
            final char c = (char) buf.get();
            if (c == '\n') break;
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "NetpbmHeader{" +
                "version='" + this.version + '\'' +
                ", width=" + this.width +
                ", height=" + this.height +
                ", depth=" + this.depth +
                ", maxVal=" + this.maxVal +
                ", tuplType='" + this.tuplType + '\'' +
                '}';
    }
}
