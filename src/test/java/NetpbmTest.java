import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.decoders.netpbm.NetpbmHeader;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;

public class NetpbmTest {

    @TestFactory
    Iterable<DynamicTest> testNetpbmHeaders() {
        final File baseDir = new File("src/test/resources/netpbm/");
        final File[] files = baseDir.listFiles((dir, name) -> name.endsWith(".pbm") || name.endsWith(".pgm") || name.endsWith(".ppm") || name.endsWith(".pam"));
        final java.util.List<DynamicTest> tests = new java.util.ArrayList<>();

        if (files != null) {
            for (final File file : files) {
                tests.add(DynamicTest.dynamicTest("Parse " + file.getName(), () -> {
                    try {
                        final byte[] fileData = Files.readAllBytes(file.toPath());
                        final ByteBuffer buffer = ByteBuffer.wrap(fileData);
                        final NetpbmHeader header = new NetpbmHeader().parse(buffer);
                        Assertions.assertTrue(header.version >= 4 && header.version <= 7, "Invalid version for file: " + file.getName());
                        Assertions.assertTrue(header.width > 0, "Width should be greater than 0 for file: " + file.getName());
                        Assertions.assertTrue(header.height > 0, "Height should be greater than 0 for file: " + file.getName());
                        System.out.println("Parsed " + file.getName() + ": " + header);
                    } catch (final Exception e) {
                        Assertions.fail("Failed to parse file: " + file.getName() + " due to exception: " + e.getMessage());
                    }
                }));
            }
        }

        return tests;
    }
}
