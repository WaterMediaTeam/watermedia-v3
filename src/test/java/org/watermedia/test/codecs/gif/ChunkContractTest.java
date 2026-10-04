package org.watermedia.test.codecs.gif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.gif.ColorTable;
import org.watermedia.api.codecs.common.gif.GraphicExtension;
import org.watermedia.api.codecs.common.gif.IChunk;
import org.watermedia.api.codecs.common.gif.ImageDescriptor;
import org.watermedia.api.codecs.common.gif.ScreenDescriptor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the GIF {@link IChunk} serialization contract: every block round-trips through its
 * reader, and descriptor validation rejects truncated input and unencodable palette sizes.
 */
@DisplayName("GIF chunk serialization contract")
public class ChunkContractTest {

    @Test
    @DisplayName("Serializes GIF blocks that read back unchanged")
    void serializesBlocks() throws XCodecException {
        final ScreenDescriptor screen = new ScreenDescriptor(320, 240, true, 8, false, 1, 0, 0);
        final GraphicExtension control = new GraphicExtension(2, false, true, 25, 1);
        final ImageDescriptor image = new ImageDescriptor(1, 2, 16, 9, true, false, false, 7);
        final ColorTable palette = new ColorTable(2, new int[] { 0x80123456, 0xFFABCDEF });
        final IChunk[] chunks = { screen, control, image, palette };
        final int[] lengths = { 7, 6, 9, 6 };
        for (int i = 0; i < chunks.length; i++) assertEquals(lengths[i], chunks[i].toBytes().length);

        assertEquals(screen, ScreenDescriptor.read(ByteBuffer.wrap(screen.toBytes()).order(ByteOrder.LITTLE_ENDIAN)));
        assertEquals(control, GraphicExtension.read(ByteBuffer.wrap(control.toBytes()).order(ByteOrder.LITTLE_ENDIAN)));
        final ByteBuffer imageBytes = ByteBuffer.wrap(image.toBytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(image, ImageDescriptor.read(imageBytes));
        assertFalse(imageBytes.hasRemaining());
        // SERIALIZATION DROPS ALPHA; READING RESTORES IT AS OPAQUE
        assertArrayEquals(new byte[] { 0x12, 0x34, 0x56, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF }, palette.toBytes());
        assertArrayEquals(new int[] { 0xFF123456, 0xFFABCDEF }, ColorTable.read(2, ByteBuffer.wrap(palette.toBytes())).colors());
    }

    @Test
    @DisplayName("Rejects truncated descriptors and unencodable palette sizes")
    void rejectsInvalidDescriptors() throws XCodecException {
        final ImageDescriptor valid = new ImageDescriptor(0, 0, 1, 1, true, false, false, 7);
        valid.validate();
        assertEquals(256, valid.getLocalColorTableSize());
        assertThrows(XCodecException.class, () -> ImageDescriptor.read(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)));
        assertThrows(XCodecException.class, () -> new ImageDescriptor(0, 0, 1, 1, true, false, false, 8).validate());
        assertThrows(XCodecException.class, () -> new ImageDescriptor(0, 0, 1, 1, true, false, false, -1).validate());
    }
}
