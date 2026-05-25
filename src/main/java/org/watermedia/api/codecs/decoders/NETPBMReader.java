package org.watermedia.api.codecs.decoders;

import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmHeader;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmType;
import org.watermedia.api.util.PixelFormat;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming Netpbm reader (PBM/PGM/PPM/PAM binary variants — P4..P7).
 *
 * <p>Receives a {@link ByteBuffer} positioned after the 2-byte {@code P<digit>} magic, plus the
 * digit value passed as a constructor argument by {@code CodecsAPI}. Parses the rest of the
 * text header (whitespace, width, height, maxval, plus TUPLTYPE/ENDHDR for P7), then in
 * {@link #next()} reads the raster bytes lazily and decodes into BGRA.
 *
 * <p>Only the four binary variants are supported (matching the legacy decoder).
 */
public class NETPBMReader extends ImageReader {

    private final NetpbmType type;
    private final NetpbmHeader header;
    private final ByteBuffer directOut;
    private byte[] rowScratch = new byte[0];

    private boolean delivered;

    public NETPBMReader(final ByteBuffer data, final int version) throws IOException {
        super(data);
        // Re-synthesize "P<digit>" so we can reuse NetpbmHeader.parse(ByteBuffer)
        // which expects to consume the "Pn" version token first. The header is small
        // (a few tokens of ASCII whitespace-separated text) so buffering it is fine.
        final byte[] headerBytes = readHeaderBytes(this.data, version);
        this.header = new NetpbmHeader().parse(ByteBuffer.wrap(headerBytes));

        this.type = NetpbmType.fromVersion(this.header.versionString);
        if (this.type == null) {
            throw new UnsupportedFormatException("Unsupported Netpbm version: " + this.header.versionString);
        }

        this.directOut = ByteBuffer.allocateDirect(this.header.width * this.header.height * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override public int width() { return this.header.width; }
    @Override public int height() { return this.header.height; }
    @Override public PixelFormat pixelFormat() { return PixelFormat.BGRA; }
    @Override public ImageData.Scan scan() { return ImageData.Scan.STATIC; }
    @Override public boolean variableFrameRate() { return false; }

    @Override
    public boolean hasNext() {
        return !this.delivered;
    }

    @Override
    public ByteBuffer next() throws IOException {
        if (this.delivered) throw new EOFException("No more Netpbm frames");
        this.delivered = true;

        this.directOut.clear();
        try {
            switch (this.type) {
                case PBM -> this.decodePbmBits();
                case PGM -> this.decodeGrayscale(1, this.header.maxVal);
                case PPM -> this.decodeColor(3, this.header.maxVal, false);
                case PAM -> this.decodePam();
            }
        } catch (final XCodecException e) {
            throw e;
        } catch (final Exception e) {
            throw new XCodecException("Failed to decode Netpbm raster", e);
        }
        this.directOut.flip();
        this.currentDelay = 0L;
        this.currentFrame = this.directOut;
        return this.directOut;
    }

    private void decodePam() throws IOException {
        final int depth = this.header.depth;
        final int maxVal = this.header.maxVal;
        final String tupleType = this.header.tuplType != null ? this.header.tuplType : "";
        switch (tupleType) {
            case "BLACKANDWHITE" -> this.decodeBW(depth, maxVal, false);
            case "GRAYSCALE" -> this.decodeGrayscale(depth, maxVal);
            case "RGB" -> this.decodeColor(depth, maxVal, false);
            case "RGB_ALPHA" -> this.decodeColor(depth, maxVal, true);
            default -> throw new XCodecException("Unsupported PAM tuple type: " + tupleType);
        }
    }

    private void decodePbmBits() throws IOException {
        final int rowBytes = (this.header.width + 7) >> 3;
        final byte[] row = this.ensureRowScratch(rowBytes);
        for (int y = 0; y < this.header.height; y++) {
            readFully(this.data, row, 0, rowBytes);
            for (int x = 0; x < this.header.width; x++) {
                final int packed = row[x >> 3] & 0xFF;
                final int bit = (packed >> (7 - (x & 7))) & 1;
                final int v = bit == 1 ? 0 : 255;
                this.putArgb(0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
    }

    private void decodeBW(final int depth, final int maxVal, final boolean flip) throws IOException {
        if (maxVal != 1) throw new XCodecException("black and white images must have maxVal of 1");
        final int bytesPerSample = bytesPerSample(maxVal);
        final int rowBytes = this.header.width * depth * bytesPerSample;
        final byte[] row = this.ensureRowScratch(rowBytes);
        for (int y = 0; y < this.header.height; y++) {
            readFully(this.data, row, 0, rowBytes);
            int offset = 0;
            for (int x = 0; x < this.header.width; x++) {
                final int value = readSample(row, offset, bytesPerSample);
                offset += depth * bytesPerSample;
                final int v = (value == (flip ? 1 : 0)) ? 0 : 255;
                this.putArgb(0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
    }

    private void decodeGrayscale(final int depth, final int maxVal) throws IOException {
        final int bytesPerSample = bytesPerSample(maxVal);
        final int rowBytes = this.header.width * depth * bytesPerSample;
        final byte[] row = this.ensureRowScratch(rowBytes);
        for (int y = 0; y < this.header.height; y++) {
            readFully(this.data, row, 0, rowBytes);
            int offset = 0;
            for (int x = 0; x < this.header.width; x++) {
                int gray = readSample(row, offset, bytesPerSample);
                offset += depth * bytesPerSample;
                gray = (gray * 255) / maxVal;
                this.putArgb(0xFF000000 | (gray << 16) | (gray << 8) | gray);
            }
        }
    }

    private void decodeColor(final int depth, final int maxVal, final boolean hasAlpha) throws IOException {
        final int bytesPerSample = bytesPerSample(maxVal);
        final int rowBytes = this.header.width * depth * bytesPerSample;
        final byte[] row = this.ensureRowScratch(rowBytes);
        for (int y = 0; y < this.header.height; y++) {
            readFully(this.data, row, 0, rowBytes);
            int offset = 0;
            for (int x = 0; x < this.header.width; x++) {
                int r = readSample(row, offset, bytesPerSample);
                int g = depth >= 2 ? readSample(row, offset + bytesPerSample, bytesPerSample) : 0;
                int b = depth >= 3 ? readSample(row, offset + bytesPerSample * 2, bytesPerSample) : 0;
                int a = hasAlpha && depth >= 4 ? readSample(row, offset + bytesPerSample * 3, bytesPerSample) : maxVal;
                offset += depth * bytesPerSample;

                r = (r * 255) / maxVal;
                g = (g * 255) / maxVal;
                b = (b * 255) / maxVal;
                a = (a * 255) / maxVal;
                this.putArgb((a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    private void putArgb(final int argb) {
        this.directOut.putInt(argb);
    }

    private byte[] ensureRowScratch(final int size) {
        if (this.rowScratch.length < size) this.rowScratch = new byte[size];
        return this.rowScratch;
    }

    private static int bytesPerSample(final int maxVal) {
        return maxVal < 256 ? 1 : 2;
    }

    private static int readSample(final byte[] row, final int offset, final int bytesPerSample) {
        if (bytesPerSample == 1) return row[offset] & 0xFF;
        return ((row[offset] & 0xFF) << 8) | (row[offset + 1] & 0xFF);
    }

    private static int readUnsignedOrEnd(final ByteBuffer in) {
        return in.hasRemaining() ? in.get() & 0xFF : -1;
    }

    private static void readFully(final ByteBuffer in, final byte[] dst, final int offset, final int length) throws IOException {
        if (in.remaining() < length) throw new EOFException("Truncated Netpbm raster");
        in.get(dst, offset, length);
    }

    /**
     * Re-emits "P<digit>\n" then drains the ASCII header from the stream until the raster begins.
     * The raster boundary depends on version: P4/P5/P6 end after the last numeric token's trailing
     * whitespace byte (consumed); P7 ends after the "ENDHDR\n" line.
     */
    private static byte[] readHeaderBytes(final ByteBuffer in, final int version) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.write('P');
        out.write('0' + version);

        if (version == 7) {
            // Read until "\nENDHDR\n"
            int state = 0; // 0: scanning; 1: just saw '\n' at start of new line
            final byte[] sentinel = "ENDHDR".getBytes();
            int sentIdx = 0;
            boolean endhdrSeen = false;
            int last = -1;
            while (true) {
                final int b = readUnsignedOrEnd(in);
                if (b < 0) throw new EOFException("Truncated PAM header");
                out.write(b);
                if (endhdrSeen) {
                    if (b == '\n') return out.toByteArray();
                    // ignore until newline
                    continue;
                }
                if (last == '\n' || last == -1) {
                    if (b == sentinel[0]) { sentIdx = 1; }
                    else { sentIdx = 0; }
                } else if (sentIdx > 0 && sentIdx < sentinel.length && b == sentinel[sentIdx]) {
                    sentIdx++;
                    if (sentIdx == sentinel.length) endhdrSeen = true;
                } else {
                    sentIdx = 0;
                }
                last = b;
            }
        }

        // P4/P5/P6: header is "<digit> WSP <width> WSP <height> [WSP <maxval>] WSP".
        // Tokens are whitespace-separated; comments start with '#' and run to '\n'.
        // P4 → 3 tokens (Pn, width, height). P5/P6 → 4 tokens (Pn, width, height, maxval).
        final int expectedTokens = (version == 4) ? 3 : 4;
        int tokens = 1; // already wrote "Pn"
        boolean inComment = false;
        boolean inToken = false;
        while (tokens < expectedTokens || inToken) {
            final int b = readUnsignedOrEnd(in);
            if (b < 0) throw new EOFException("Truncated Netpbm header");
            out.write(b);
            if (inComment) {
                if (b == '\n') inComment = false;
                continue;
            }
            if (b == '#') { inComment = true; continue; }
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                if (inToken) { tokens++; inToken = false; }
            } else {
                inToken = true;
            }
        }
        return out.toByteArray();
    }
}
