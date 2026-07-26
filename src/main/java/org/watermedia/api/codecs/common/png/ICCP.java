package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.XCodecException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * iCCP - Embedded ICC Profile Chunk
 * Contains an embedded ICC color profile for precise color space definition
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11iCCP">PNG Specification - iCCP</a>
 */
public record ICCP(String profileName, int compressionMethod, byte[] compressedProfile) {
    public static final int SIGNATURE = 0x69_43_43_50; // "iCCP"
    // CAP DECOMPRESSED PROFILE: A TINY iCCP CHUNK CAN INFLATE TO GIGABYTES (ZLIB BOMB); LEGIT ICC PROFILES ARE WELL UNDER THIS
    private static final int MAX_DECOMPRESSED = 16 * 1024 * 1024; // 16 MB

    /**
     * Reads iCCP chunk from buffer (reads length/type header first)
     */
    public static ICCP read(final ByteBuffer buffer) throws XCodecException {
        if (buffer.remaining() < 8) throw new XCodecException("Truncated iCCP chunk header");
        final int length = buffer.getInt();
        final int type = buffer.getInt();

        // TYPE AND LENGTH COME STRAIGHT OFF THE WIRE HERE, SO BOTH ARE ATTACKER DATA (UNLIKE convert)
        if (type != SIGNATURE)
            throw new XCodecException("Invalid chunk type for iCCP: 0x" + Integer.toHexString(type));
        if (length < 0 || buffer.remaining() < length)
            throw new XCodecException("Truncated iCCP chunk");

        // READ PROFILE NAME (1-79 BYTES + NULL)
        final int endPosition = buffer.position() + length;
        final StringBuilder nameBuilder = new StringBuilder();
        byte b;
        int bytesRead = 0;
        while (bytesRead < 80 && buffer.position() < endPosition && (b = buffer.get()) != 0) {
            nameBuilder.append((char) (b & 0xFF));
            bytesRead++;
        }
        bytesRead++; // COUNT NULL TERMINATOR

        final String profileName = nameBuilder.toString();
        if (buffer.position() >= endPosition) throw new XCodecException("Truncated iCCP: missing compression method");
        final int compressionMethod = buffer.get() & 0xFF;
        bytesRead++;

        // REMAINING DATA IS COMPRESSED PROFILE: AN UNTERMINATED NAME OVERSHOOTS AND GOES NEGATIVE
        final int profileLength = length - bytesRead;
        if (profileLength < 0) throw new XCodecException("Invalid iCCP: unterminated profile name");
        final byte[] compressedProfile = new byte[profileLength];
        buffer.get(compressedProfile);

        return new ICCP(profileName, compressionMethod, compressedProfile);
    }

    /**
     * Converts a generic CHUNK to ICCP
     */
    public static ICCP convert(final CHUNK chunk) throws XCodecException {
        if (chunk.type() != SIGNATURE) {
            throw new XCodecException("Invalid chunk type for iCCP: 0x" + Integer.toHexString(chunk.type()));
        }

        final byte[] data = chunk.data();

        // FIND NULL SEPARATOR AFTER PROFILE NAME (1-79 BYTES + NULL)
        int nullIndex = -1;
        for (int i = 0; i < Math.min(80, data.length); i++) {
            if (data[i] == 0) {
                nullIndex = i;
                break;
            }
        }

        if (nullIndex < 1) {
            throw new XCodecException("Invalid iCCP: missing or empty profile name");
        }

        // NEED THE COMPRESSION-METHOD BYTE AFTER THE NAME NUL; A TRUNCATED PAYLOAD OTHERWISE READS PAST THE ARRAY
        if (data.length < nullIndex + 2) {
            throw new XCodecException("Truncated iCCP: missing compression method");
        }

        final String profileName = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final int compressionMethod = data[nullIndex + 1] & 0xFF;

        if (compressionMethod != 0) {
            throw new XCodecException("Unknown iCCP compression method: " + compressionMethod);
        }

        // REMAINING DATA IS COMPRESSED PROFILE
        final int profileStart = nullIndex + 2;
        final byte[] compressedProfile = new byte[data.length - profileStart];
        System.arraycopy(data, profileStart, compressedProfile, 0, compressedProfile.length);

        return new ICCP(profileName, compressionMethod, compressedProfile);
    }

    /**
     * Decompresses and returns the ICC profile data
     */
    public byte[] getProfile() throws XCodecException {
        final Inflater inflater = new Inflater();
        inflater.setInput(this.compressedProfile);

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];

        try {
            // EVERY ITERATION EITHER PRODUCES BYTES OR LEAVES THE LOOP: A ZLIB HEADER WITH FDICT SET
            // RETURNS Z_NEED_DICT (0 IN, 0 OUT, needsInput() FALSE) AND WOULD OTHERWISE SPIN FOREVER
            while (true) {
                final int length = inflater.inflate(buffer);
                if (length > 0) {
                    if (output.size() + length > MAX_DECOMPRESSED) {
                        throw new XCodecException("iCCP exceeds " + MAX_DECOMPRESSED + " bytes decompressed");
                    }
                    output.write(buffer, 0, length);
                    continue;
                }
                if (inflater.finished()) break;
                if (inflater.needsDictionary()) {
                    throw new XCodecException("iCCP uses an unsupported compression dictionary");
                }
                if (inflater.needsInput()) throw new XCodecException("Incomplete compressed ICC profile");
                throw new XCodecException("Invalid compressed ICC profile stream");
            }
        } catch (final DataFormatException e) {
            throw new XCodecException("Invalid compressed ICC profile: " + e.getMessage());
        } finally {
            inflater.end();
        }

        return output.toByteArray();
    }

    public byte[] toBytes() {
        final byte[] nameBytes = this.profileName.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] data = new byte[nameBytes.length + 1 + 1 + this.compressedProfile.length];
        System.arraycopy(nameBytes, 0, data, 0, nameBytes.length);
        data[nameBytes.length] = 0;
        data[nameBytes.length + 1] = (byte) this.compressionMethod;
        System.arraycopy(this.compressedProfile, 0, data, nameBytes.length + 2, this.compressedProfile.length);
        return data;
    }

    public CHUNK toChunk() {
        return CHUNK.create(SIGNATURE, this.toBytes());
    }
}
