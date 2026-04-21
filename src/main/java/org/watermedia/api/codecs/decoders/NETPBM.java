package org.watermedia.api.codecs.decoders;

import org.watermedia.api.codecs.ImageCodec;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmHeader;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmType;

import java.io.IOException;
import java.nio.ByteBuffer;

public class NETPBM extends ImageCodec {

    public boolean supported(final ByteBuffer buffer) {
        try {
            buffer.mark();
            final NetpbmHeader header = new NetpbmHeader().parse(buffer);
            return NetpbmType.supportsVersion(header.versionString);
        } catch (final XCodecException ignored) {
            return false;
        } finally {
            buffer.reset();
        }
    }

    public ImageData decode(final ByteBuffer buffer) throws IOException {
        final NetpbmHeader header = new NetpbmHeader().parse(buffer);
        final NetpbmType type = NetpbmType.fromVersion(header.versionString);
        assert type != null;
        return type.decode(buffer, header);
    }
}
