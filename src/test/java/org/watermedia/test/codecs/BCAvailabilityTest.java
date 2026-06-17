package org.watermedia.test.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.XCodecException;
import org.watermedia.api.codecs.common.dds.DDSHeader;
import org.watermedia.api.codecs.readers.BCReader;
import org.watermedia.api.codecs.writers.BCWriter;
import org.watermedia.api.util.PixelFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec-availability and rigid-validation tests. Pure-Java image codecs are always available; the
 * native BC family is absent until its JNI bindings ship, so the BC reader/writer must refuse to
 * initialize (the codecs are not pluggable, so an instance that exists is always usable).
 */
@DisplayName("Codec availability")
public class BCAvailabilityTest {

    @Test
    @DisplayName("Pure-Java image codecs are always available")
    void testPureJavaCodecsAvailable() {
        assertTrue(CodecsAPI.available(CodecsAPI.CODEC_PNG));
        assertTrue(CodecsAPI.available(CodecsAPI.CODEC_JPEG));
        assertTrue(CodecsAPI.available("jpg"));
        assertTrue(CodecsAPI.available(CodecsAPI.CODEC_GIF));
        assertTrue(CodecsAPI.available(CodecsAPI.CODEC_WEBP));
        assertTrue(CodecsAPI.available(CodecsAPI.CODEC_NETPBM));
    }

    @Test
    @DisplayName("BC codecs are unavailable without native bindings")
    void testBcUnavailable() {
        assertFalse(CodecsAPI.available(CodecsAPI.CODEC_BC));
        assertFalse(CodecsAPI.available(CodecsAPI.CODEC_BC7));
        assertFalse(CodecsAPI.available(CodecsAPI.CODEC_BC3));
        assertFalse(CodecsAPI.available(CodecsAPI.CODEC_BC1));
    }

    @Test
    @DisplayName("Unknown and null codecs are unavailable")
    void testUnknownUnavailable() {
        assertFalse(CodecsAPI.available(null));
        assertFalse(CodecsAPI.available("definitely-not-a-codec"));
    }

    @Test
    @DisplayName("BCWriter refuses to open when no BC codec is available")
    void testWriterRefusesWhenUnavailable() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // BEST-AVAILABLE (NONE) AND AN EXPLICIT VERSION BOTH FAIL FAST IN THE CONSTRUCTOR.
        assertThrows(IOException.class, () -> new BCWriter(out, 16, 16, PixelFormat.BGRA));
        assertThrows(IOException.class, () -> new BCWriter(out, 16, 16, PixelFormat.BGRA, CodecsAPI.CODEC_BC7));
    }

    @Test
    @DisplayName("BCReader refuses a texture whose BC version is unavailable")
    void testReaderRefusesWhenUnavailable() {
        // A WELL-FORMED HEADER WHOSE CODEC (BC7) IS NOT NATIVELY AVAILABLE MUST BE REJECTED.
        final ByteBuffer header = ByteBuffer.wrap(DDSHeader.write(16, 16, CodecsAPI.CODEC_BC7, 1));
        assertThrows(XCodecException.class, () -> new BCReader(header));
    }
}
