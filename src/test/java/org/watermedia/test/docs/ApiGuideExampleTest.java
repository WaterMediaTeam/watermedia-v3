package org.watermedia.test.docs;

import org.junit.jupiter.api.Test;
import org.watermedia.test.support.Fixtures;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiGuideExampleTest {
    @Test
    void documentedHeadlessImageFlowUploadsAFrame() throws Exception {
        final ByteBuffer image = ByteBuffer.allocate(17);
        image.put("P6\n2 1\n255\n".getBytes(StandardCharsets.US_ASCII))
                .put(new byte[] { -1, 0, 0, 0, -1, 0 }).flip();
        assertEquals(1, ApiGuideExample.capture(image));
    }

    @Test
    void documentedHeadlessFlowUploadsEveryAnimationFrame() throws Exception {
        assertEquals(40, ApiGuideExample.capture(ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_DIR.resolve("2.png")))));
    }
}
