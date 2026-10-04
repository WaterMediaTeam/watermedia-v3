package org.watermedia.test.codecs.png;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.common.png.ACTL;
import org.watermedia.api.codecs.common.png.CHUNK;
import org.watermedia.api.codecs.common.png.FDAT;
import org.watermedia.api.codecs.common.png.IChunk;
import org.watermedia.api.codecs.common.png.IDAT;
import org.watermedia.api.codecs.common.png.IEND;
import org.watermedia.api.codecs.common.png.IHDR;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the PNG {@link IChunk} serialization contract: {@code toChunk()} wraps exactly the
 * {@code toBytes()} data with a valid CRC, and the written envelope reads back unchanged.
 */
@DisplayName("PNG chunk serialization contract")
public class ChunkContractTest {

    @Test
    @DisplayName("Serializes an empty IEND chunk")
    void serializesEmptyChunk() throws IOException {
        final CHUNK chunk = assertContract(new IEND(), IEND.SIGNATURE);
        assertEquals(0, chunk.length());
        assertEquals(0xAE426082, chunk.crc());
    }

    @Test
    @DisplayName("Serializes structured metadata and animation control")
    void serializesStructuredChunks() throws IOException {
        assertContract(new IHDR(16, 9, 8, 6, 0, 0, 0), IHDR.SIGNATURE);
        assertContract(new ACTL(3, 0), ACTL.SIGNATURE);
        final CHUNK frameData = assertContract(new FDAT(7, new byte[] { 9, 10 }), FDAT.SIGNATURE);
        assertArrayEquals(new byte[] { 0, 0, 0, 7, 9, 10 }, frameData.data());
    }

    @Test
    @DisplayName("Serializes IDAT data without changing its payload")
    void serializesDataChunk() throws IOException {
        final byte[] data = { 0, 1, (byte) 0x80, (byte) 0xFF };
        final IDAT idat = new IDAT(data);
        final byte[] payload = idat.toBytes();
        assertArrayEquals(data, payload);
        payload[0] = 7;
        assertArrayEquals(data, idat.toBytes());
        assertArrayEquals(data, assertContract(idat, IDAT.SIGNATURE).data());
    }

    private static CHUNK assertContract(final IChunk value, final int type) throws IOException {
        final byte[] data = value.toBytes();
        final CHUNK chunk = value.toChunk();
        assertEquals(type, chunk.type());
        assertEquals(data.length, chunk.length());
        assertArrayEquals(data, chunk.data());
        assertFalse(chunk.corrupted());

        final ByteBuffer envelope = ByteBuffer.allocate(data.length + 12);
        chunk.write(envelope);
        envelope.flip();
        final CHUNK decoded = CHUNK.read(envelope);
        assertEquals(chunk.length(), decoded.length());
        assertEquals(chunk.type(), decoded.type());
        assertArrayEquals(chunk.data(), decoded.data());
        assertEquals(chunk.crc(), decoded.crc());
        return chunk;
    }
}
