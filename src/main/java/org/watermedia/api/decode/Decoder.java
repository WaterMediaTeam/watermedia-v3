package org.watermedia.api.decode;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class Decoder {

    /**
     * Provides the decoder name
     */
    public String name() {
        return this.getClass().getSimpleName();
    }

    /**
     * Reads the "expected" header of the decoder format.
     * When it matches, it doesn't rewind to the initial position assuming it will proceed with the decoding
     * When it doesn't, rewinds to the initial position to let other decoders properly check the value.
     * @param buffer encoded picture data
     * @return true if the decoder its compatible with the buffer data, false otherwise
     */
    public abstract boolean supported(ByteBuffer buffer);

    /**
     * Decodes an image from the provided ByteBuffer.
     * The buffer should be positioned at the start of the encoded image data.
     * The header should be skipped previously by the call of {@link #decode(ByteBuffer)}
     *
     * @param buffer encoded picture data
     * @return decoded image
     * @throws IOException if the decoding fails
    */
    public abstract Image decode(ByteBuffer buffer) throws IOException;

    /**
     * Test current decoder
     * @return
     */
    public abstract boolean test();
}
