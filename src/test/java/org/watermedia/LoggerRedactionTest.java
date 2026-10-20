package org.watermedia;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.StringMapMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MRL;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
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
 * Verifies that {@link WaterMedia#logger(String)} loggers redact secrets from messages, parameters and traces
 * before appenders see them, keep safe events untouched, and that source diagnostics omit header values.
 */
@DisplayName("Logger redaction")
public class LoggerRedactionTest {
    private static final Marker IT = MarkerManager.getMarker("LoggerRedactionTest");

    @Nested
    @DisplayName("Events")
    class EventTests {
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
                WaterMedia.LOGGER.error(IT, "Could not resolve media", failure);
                final var event = capture.events().get(0);
                final String text = PatternLayout.newBuilder().setPattern("%m%n%throwable").build().toSerializable(event);
                assertTrue(text.contains("IllegalStateException: Primary failure"), text);
                assertTrue(text.contains("Caused by: java.io.IOException"), text);
                assertTrue(text.contains("Suppressed: java.io.IOException"), text);
                assertTrue(text.contains("LoggerRedactionTest"), text);
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
                WaterMedia.LOGGER.error(IT, message, failure);
                final var event = capture.events().get(0);
                assertSame(message, event.getMessage());
                assertSame(failure, event.getThrown());
            }
        }

        @Test
        @DisplayName("Redacts throwables embedded in messages and bare tokens in parameters")
        void redactsEmbeddedThrowables() {
            final IOException failure = new IOException("https://example.test/embedded-secret");
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                WaterMedia.LOGGER.error(IT, new ParameterizedMessage("Failure in {}", new Object[] { "decoder" }, failure));
                WaterMedia.LOGGER.warn(IT, "Header {}", "Authorization: Bearer private-bearer");
                final var event = capture.events().get(0);
                assertFalse(event.getMessage().getFormattedMessage().contains("embedded-secret"));
                assertNull(event.getThrown());
                assertNull(event.getMessage().getThrowable());
                assertEquals("Header Authorization: Bearer ********", capture.events().get(1).getMessage().getFormattedMessage());
            }
        }
    }

    @Nested
    @DisplayName("Sources")
    class SourceTests {
        @Test
        @DisplayName("Source diagnostics omit header values without changing wire headers")
        void omitsHeaderValues() {
            final HttpHeaders headers = new HttpHeaders().set("Cookie", "session=private-cookie")
                    .set("Authorization", "Bearer private-auth").set("X-Custom", "private-custom");
            final MRL.Source source = new MRL.Source(MediaType.VIDEO, null, null, headers,
                    Map.of(MediaQuality.HIGHER, URI.create("https://example.test/private-video")), null, null);
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                WaterMedia.LOGGER.debug(IT, "Creating player for {}", source);
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
}
