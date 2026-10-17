package org.watermedia.tools;

import org.apache.logging.log4j.Level;
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
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.test.support.LogCapture;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link LogTool} loggers redact URIs from messages, parameters and throwable traces
 * before appenders receive them, that safe events pass through untouched, and that the source
 * diagnostics logged through them omit header values on their own.
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
    @DisplayName("Throttled events report once per window and carry the suppressed count")
    void throttlesRepeatedEvents() throws InterruptedException {
        final LogTool.Throttle throttle = new LogTool.Throttle(50_000_000L);
        final Throwable cause = new IllegalStateException("boom");
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            LogTool.throttled(WaterMedia.LOGGER, Level.WARN, MarkerManager.getMarker("Test"), throttle, "Tick failed for {}", "a", cause);
            LogTool.throttled(WaterMedia.LOGGER, Level.WARN, MarkerManager.getMarker("Test"), throttle, "Tick failed for {}", "b", cause);
            LogTool.throttled(WaterMedia.LOGGER, Level.WARN, MarkerManager.getMarker("Test"), throttle, "Tick failed for {}", "c", cause);
            Thread.sleep(60);
            LogTool.throttled(WaterMedia.LOGGER, Level.WARN, MarkerManager.getMarker("Test"), throttle, "Tick failed for {}", "d", cause);
            assertEquals(4, capture.events().size());
            assertEquals(Level.WARN, capture.events().get(0).getLevel());
            assertEquals("Tick failed for a", capture.events().get(0).getMessage().getFormattedMessage());
            assertEquals(Level.DEBUG, capture.events().get(1).getLevel());
            assertEquals(Level.DEBUG, capture.events().get(2).getLevel());
            assertEquals(Level.WARN, capture.events().get(3).getLevel());
            assertEquals("Tick failed for d (2 more since the previous report)", capture.events().get(3).getMessage().getFormattedMessage());
            assertSame(cause, capture.events().get(3).getThrown());
        }
    }

    @Test
    @DisplayName("Source diagnostics omit header values without changing wire headers")
    void omitsHeaderValues() {
        final HttpHeaders headers = new HttpHeaders().set("Cookie", "session=private-cookie")
                .set("Authorization", "Bearer private-auth").set("X-Custom", "private-custom");
        final MRL.Source source = new MRL.Source(MediaType.VIDEO, null, null, headers,
                Map.of(MediaQuality.HIGHEST, URI.create("https://example.test/private-video")), null, null);
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            WaterMedia.LOGGER.debug("Creating player for {}", source);
            final String text = capture.events().get(0).getMessage().getFormattedMessage();
            assertFalse(text.contains("private-"), text);
            assertTrue(text.contains("Cookie"), text);
            assertTrue(text.contains("Authorization"), text);
            assertTrue(headers.toRawString().contains("Cookie: session=private-cookie\r\n"));
            assertEquals("Bearer private-auth", headers.value("Authorization"));
            assertFalse(headers.entries().toString().contains("private-"));
        }
    }
}
