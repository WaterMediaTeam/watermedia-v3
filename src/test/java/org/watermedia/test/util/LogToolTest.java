package org.watermedia.test.util;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.StringMapMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.RequestHeaders;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.test.support.LogCapture;
import org.watermedia.tools.LogTool;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link LogTool} loggers redact URIs and header values from messages, parameters
 * and throwable traces before appenders receive them, while safe events pass through untouched.
 */
@DisplayName("LogTool")
public class LogToolTest {

    @Test
    @DisplayName("Redacts both project loggers before events reach appenders")
    void redactsProjectLoggers() {
        for (final Logger logger: new Logger[] { WaterMedia.LOGGER, WaterMediaBinaries.LOGGER }) {
            try (final LogCapture capture = new LogCapture(logger.getName())) {
                logger.warn(MarkerManager.getMarker("PrivacyProbe"), "Source {}",
                        URI.create("https://user:password@example.test:8443/private-id?token=secret#fragment"));
                final var event = capture.events().get(0);
                assertEquals("Source https://example.test:8443/REDACTED", event.getMessage().getFormattedMessage());
                assertEquals("PrivacyProbe", event.getMarker().getName());
                assertNull(event.getMessage().getParameters());
            }
        }
    }

    @Test
    @DisplayName("Redacts nested and suppressed traces without mutating the original failures")
    void redactsNestedTraces() {
        final IOException cause = new IOException("https://api.test/private-cause?token=nested");
        final IllegalStateException failure = new IllegalStateException("Primary failure", cause);
        failure.addSuppressed(new IOException("file:///private/suppressed-secret"));
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            WaterMedia.LOGGER.error("Could not resolve media", failure);
            final var event = capture.events().get(0);
            final String text = PatternLayout.newBuilder().setPattern("%m%n%throwable").build().toSerializable(event);
            assertTrue(text.contains("IllegalStateException: Primary failure"), text);
            assertTrue(text.contains("Caused by: java.io.IOException"), text);
            assertTrue(text.contains("Suppressed: java.io.IOException"), text);
            assertTrue(text.contains("LogToolTest"), text);
            assertFalse(text.contains("private-cause"), text);
            assertFalse(text.contains("nested"), text);
            assertFalse(text.contains("suppressed-secret"), text);
            assertNull(event.getThrown());
            assertSame(cause, failure.getCause());
            assertTrue(cause.getMessage().contains("token=nested"));
        }
    }

    @Test
    @DisplayName("Keeps safe structured messages and throwable objects")
    void keepsSafeEvents() {
        final StringMapMessage message = new StringMapMessage(Map.of("codec", "h264", "stream", "2"));
        final IOException failure = new IOException("Decoder unavailable");
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            WaterMedia.LOGGER.error(message, failure);
            final var event = capture.events().get(0);
            assertSame(message, event.getMessage());
            assertSame(failure, event.getThrown());
        }
    }

    @Test
    @DisplayName("Redacts throwables embedded in messages")
    void redactsEmbeddedThrowables() {
        final IOException failure = new IOException("https://example.test/embedded-secret");
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            WaterMedia.LOGGER.error(new ParameterizedMessage("Failure in {}", new Object[] { "decoder" }, failure));
            final var event = capture.events().get(0);
            assertFalse(event.getMessage().getFormattedMessage().contains("embedded-secret"));
            assertNull(event.getThrown());
            assertNull(event.getMessage().getThrowable());
        }
    }

    @Test
    @DisplayName("Handles redirect chains, malformed URLs and protocol-relative links")
    void redactsUriVariants() {
        final String text = "[https://first.test/secret?key=one] -> //second.test/secret?key=two "
                + "ftp://name:password@files.test/private file:/private/file "
                + "https://bad.test/%not-hex";
        final String safe = LogTool.redact(text);
        assertFalse(safe.contains("secret"), safe);
        assertFalse(safe.contains("private"), safe);
        assertFalse(safe.contains("password"), safe);
        assertFalse(safe.contains("key="), safe);
        assertFalse(safe.contains("not-hex"), safe);
        assertTrue(safe.contains("https://first.test/REDACTED]"), safe);
        assertTrue(safe.contains("//second.test/REDACTED"), safe);
        assertEquals(safe, LogTool.redact(safe));
    }

    @Test
    @DisplayName("Redacts apostrophes inside URLs without losing quoted message boundaries")
    void redactsQuotedUris() {
        final String safe = LogTool.redact("Source 'https://name'part:password@host.test/path?token=first'second' failed");
        assertEquals("Source 'https://host.test/REDACTED' failed", safe);
        assertFalse(safe.contains("second"));
    }

    @Test
    @DisplayName("Source diagnostics omit header values without changing wire headers")
    void omitsHeaderValues() {
        final RequestHeaders headers = new RequestHeaders().set("Cookie", "session=private-cookie")
                .set("Authorization", "Bearer private-auth").set("X-Custom", "private-custom");
        final MRL.Source source = new MRL.Source(MediaType.VIDEO, null, null, headers,
                Map.of(MediaQuality.HIGHER, URI.create("https://example.test/private-video")), null, null);
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            WaterMedia.LOGGER.debug("Creating player for {}", source);
            final String text = capture.events().get(0).getMessage().getFormattedMessage();
            assertFalse(text.contains("private-"), text);
            assertTrue(text.contains("Cookie"), text);
            assertTrue(text.contains("Authorization"), text);
            assertTrue(headers.toRawString().contains("Cookie: session=private-cookie\r\n"));
            assertEquals("Bearer private-auth", headers.get("Authorization"));
            assertFalse(headers.entries().toString().contains("private-"));
        }
    }
}
