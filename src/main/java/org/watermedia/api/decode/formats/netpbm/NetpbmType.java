package org.watermedia.api.decode.formats.netpbm;

import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.Image;
import org.watermedia.api.decode.formats.netpbm.decoders.PAMDecoder;
import org.watermedia.api.decode.formats.netpbm.decoders.PBMDecoder;
import org.watermedia.api.decode.formats.netpbm.decoders.PGMDecoder;
import org.watermedia.api.decode.formats.netpbm.decoders.PPMDecoder;

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
        for (final NetpbmType type : values()) {
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

    public Image decode(final ByteBuffer data, final NetpbmHeader header) throws DecoderException {
        return this.decoder.decode(data, header);
    }
}
