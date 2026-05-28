package org.watermedia.test.codecs.netpbm;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.readers.netpbm.NetpbmHeader;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Netpbm header parser smoke tests. Walks every {@code .pbm}/{@code .pgm}/{@code .ppm}/
 * {@code .pam} fixture and asserts the parsed header reports a known Netpbm version
 * (4..7) and positive dimensions.
 */
public class NetpbmTest {

    @TestFactory
    Iterable<DynamicTest> testNetpbmHeaders() {
        final List<DynamicTest> tests = new ArrayList<>();
        try (final Stream<Path> entries = Files.list(Fixtures.NETPBM_DIR)) {
            entries
                    .filter(p -> {
                        final String n = p.getFileName().toString();
                        return n.endsWith(".pbm") || n.endsWith(".pgm") || n.endsWith(".ppm") || n.endsWith(".pam");
                    })
                    .sorted()
                    .forEach(file -> tests.add(DynamicTest.dynamicTest("Parse " + file.getFileName(), () -> {
                        try {
                            final ByteBuffer buffer = ByteBuffer.wrap(Fixtures.readAll(file));
                            final NetpbmHeader header = new NetpbmHeader().parse(buffer);
                            assertTrue(header.version >= 4 && header.version <= 7,
                                    "Invalid version for file: " + file.getFileName());
                            assertTrue(header.width > 0,
                                    "Width should be greater than 0 for file: " + file.getFileName());
                            assertTrue(header.height > 0,
                                    "Height should be greater than 0 for file: " + file.getFileName());
                            System.out.println("Parsed " + file.getFileName() + ": " + header);
                        } catch (final Exception e) {
                            fail("Failed to parse file: " + file.getFileName() + " due to exception: " + e.getMessage());
                        }
                    })));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate netpbm fixtures", e);
        }
        return tests;
    }
}
