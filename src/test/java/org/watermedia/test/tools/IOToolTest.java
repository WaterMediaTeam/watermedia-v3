package org.watermedia.test.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.tools.IOTool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IOTool}: the bounded reader and the Zip Slip guard in the
 * archive extractor.
 */
@DisplayName("IOTool")
public class IOToolTest {

    private static byte[] zip(final String entryName) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (final ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write("payload".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("readLimited returns the body when under the cap")
    void readLimitedUnderCap() throws IOException {
        final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), 100, -1L));
    }

    @Test
    @DisplayName("readLimited throws once the body exceeds the cap")
    void readLimitedOverCap() {
        final byte[] data = new byte[100];
        assertThrows(IOException.class, () -> IOTool.readLimited(new ByteArrayInputStream(data), 10, -1L));
    }

    @Test
    @DisplayName("jarExtractZip writes a normal entry into the output directory")
    void extractsNormalEntry(@TempDir final Path out) throws Exception {
        assertTrue(IOTool.jarExtractZip(new ByteArrayInputStream(zip("good.txt")), out.toFile()));
        assertTrue(new File(out.toFile(), "good.txt").isFile());
    }

    @Test
    @DisplayName("jarExtractZip rejects a Zip Slip entry that escapes the target")
    void rejectsZipSlip(@TempDir final Path out) throws Exception {
        final byte[] evil = zip("../escapes.txt");
        assertThrows(IOException.class, () -> IOTool.jarExtractZip(new ByteArrayInputStream(evil), out.toFile()));
        // THE ESCAPING FILE MUST NOT HAVE BEEN WRITTEN OUTSIDE THE TARGET
        assertFalse(new File(out.toFile().getParentFile(), "escapes.txt").exists());
    }

    @Test
    @DisplayName("platformClassifier is never blank")
    void platformClassifier() {
        assertFalse(IOTool.platformClassifier().isBlank());
    }
}
