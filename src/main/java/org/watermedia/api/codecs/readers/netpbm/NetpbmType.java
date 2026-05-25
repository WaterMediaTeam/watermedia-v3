package org.watermedia.api.codecs.readers.netpbm;

import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.readers.netpbm.decoders.PAMDecoder;
import org.watermedia.api.codecs.readers.netpbm.decoders.PBMDecoder;
import org.watermedia.api.codecs.readers.netpbm.decoders.PGMDecoder;
import org.watermedia.api.codecs.readers.netpbm.decoders.PPMDecoder;

import java.nio.ByteBuffer;

public enum NetpbmType {
    PBM("P4", new PBMDecoder()),
    PGM("P5", new PGMDecoder()),
    PPM("P6", new PPMDecoder()),
    PAM("P7", new PAMDecoder());

    public static boolean supportsVersion(final String version) {
        return fromVersion(version) != null;
    }

    public static NetpbmType fromVersion(final String version) {
        for (final NetpbmType type: values()) {
            if (type.version.equals(version))
                return type;
        }
        return null;
    }

    public final String version;
    private final NetpbmDecoder decoder;

    NetpbmType(final String version, final NetpbmDecoder decoder) {
        this.version = version;
        this.decoder = decoder;
    }

    public ImageData decode(final ByteBuffer data, final NetpbmHeader header) throws XCodecException {
        return this.decoder.decode(data, header);
    }

    public void decodeInto(final ByteBuffer data, final NetpbmHeader header, final ByteBuffer frame) throws XCodecException {
        this.decoder.decodeInto(data, header, frame);
    }
}
