package org.watermedia.api.decode.formats.netpbm;

import org.watermedia.api.decode.Decoder;
import org.watermedia.api.decode.DecoderException;
import org.watermedia.api.decode.Image;

import java.io.IOException;
import java.nio.ByteBuffer;

public class NETPBM extends Decoder {

    public boolean supported(final ByteBuffer buffer) {
        try {
            buffer.mark();
            final NetpbmHeader header = new NetpbmHeader().parse(buffer);
            return NetpbmType.supportsVersion(header.versionString);
        } catch (final DecoderException ignored) {
            return false;
        } finally {
            buffer.reset();
        }
    }

    public Image decode(final ByteBuffer buffer) throws IOException {
        final NetpbmHeader header = new NetpbmHeader().parse(buffer);
        final NetpbmType type = NetpbmType.fromVersion(header.versionString);
        assert type != null;
        return type.decode(buffer, header);
    }

    public boolean test() {
        return true;
    }
}
