package org.watermedia.tools;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.ExtendedLoggerWrapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Shared diagnostic output that keeps resource credentials out of project logs. */
public final class LogTool {
    private static final Pattern URI_TEXT = Pattern.compile("(?i)(?<![\\w/])(?:[a-z][a-z0-9+.-]*://|file:|//)[^\\s<>]+");

    private LogTool() {}

    /** Creates a project logger that redacts URIs from messages, parameters and traces before appenders see them. */
    public static Logger logger(final String name) {
        final ExtendedLogger logger = LogManager.getContext(false).getLogger(name);
        return new ExtendedLoggerWrapper(logger, name, logger.getMessageFactory()) {
            @Override
            public void logMessage(final String caller, final Level level, final Marker marker, final Message message, final Throwable failure) {
                final String originalText = message == null ? "null" : message.getFormattedMessage();
                String text = redact(originalText);
                boolean changed = message == null || !Objects.equals(text, originalText);
                final Object[] parameters = message == null ? null : message.getParameters();
                if (parameters != null) {
                    for (final Object parameter: parameters) {
                        final String value = ParameterizedMessage.deepToString(parameter);
                        changed |= !Objects.equals(value, redact(value));
                    }
                }
                final Throwable embedded = message == null ? null : message.getThrowable();
                Throwable cause = failure != null ? failure : embedded;
                for (final Throwable thrown: new Throwable[] { cause, embedded == cause ? null : embedded }) {
                    if (thrown == null) continue;
                    final StringWriter trace = new StringWriter();
                    thrown.printStackTrace(new PrintWriter(trace));
                    final String safe = redact(trace.toString());
                    if (safe.contentEquals(trace.getBuffer())) continue;
                    // UNSAFE TRACES BECOME TEXT: TYPES, FRAMES, CAUSES AND SUPPRESSED FAILURES STAY READABLE
                    // WHILE THE THROWABLE ITSELF IS NEVER FORWARDED TO APPENDERS.
                    text += System.lineSeparator() + safe;
                    if (thrown == cause) cause = null;
                    changed = true;
                }
                super.logMessage(caller, level, marker, changed ? new SimpleMessage(text) : message, cause);
            }
        };
    }

    /**
     * Logs a repeated anomaly at most once per throttle window: the first event of a window keeps
     * {@code level} and carries the count suppressed since the previous report, the rest go to DEBUG.
     */
    public static void throttled(final Logger logger, final Level level, final Marker marker, final Throttle throttle,
                                 final String pattern, final Object... args) {
        final int suppressed = throttle.report();
        if (suppressed < 0) logger.debug(marker, pattern, args);
        else if (suppressed == 0) logger.log(level, marker, pattern, args);
        else {
            // THE AGGREGATED LINE PRE-FORMATS THE MESSAGE, SO A TRAILING THROWABLE MUST TRAVEL ON ITS OWN
            final Throwable cause = args.length > 0 && args[args.length - 1] instanceof final Throwable t ? t : null;
            final Object[] text = cause == null ? args : Arrays.copyOf(args, args.length - 1);
            logger.log(level, marker, "{} ({} more since the previous report)", ParameterizedMessage.format(pattern, text), suppressed, cause);
        }
    }

    /** Masks every URI in the text, keeping only the scheme, host and port of valid network URIs. */
    public static String redact(final String text) {
        if (text == null) return null;
        final Matcher matches = URI_TEXT.matcher(text);
        if (!matches.find()) return text;
        final StringBuilder result = new StringBuilder();
        do {
            String value = matches.group();
            int end = value.length();
            while (end > 0 && ").,;]}'\"`".indexOf(value.charAt(end - 1)) >= 0) end--;
            final String suffix = value.substring(end);
            value = value.substring(0, end);
            String replacement = "[redacted-uri]";
            try {
                final boolean relative = value.startsWith("//");
                final URI uri = URI.create(relative ? "https:" + value : value);
                // PATHS CAN CONTAIN PRIVATE IDS TOO; ONLY THE ORIGIN IS USEFUL WITHOUT DISCLOSING THE RESOURCE.
                if (uri.getHost() != null && !"file".equalsIgnoreCase(uri.getScheme())) {
                    replacement = (relative ? "//" : uri.getScheme() + "://") + uri.getHost()
                            + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + "/REDACTED";
                }
            } catch (final IllegalArgumentException ignored) {
                // MALFORMED URLS STILL NEED REDACTION WHEN A PARSER REPORTS THEM IN AN EXCEPTION.
            }
            matches.appendReplacement(result, Matcher.quoteReplacement(replacement + suffix));
        } while (matches.find());
        return matches.appendTail(result).toString();
    }

    /** Per-window report gate for repeated anomalies: the first event of each window reports, the rest are counted. */
    public static final class Throttle {
        private final long windowNs;
        private long windowStart;
        private int suppressed;

        public Throttle(final long windowNs) {
            this.windowNs = windowNs;
            this.windowStart = System.nanoTime() - windowNs;
        }

        /** Returns the events suppressed since the previous report, or -1 when this event is suppressed as well. */
        public synchronized int report() {
            final long now = System.nanoTime();
            if (now - this.windowStart < this.windowNs) {
                this.suppressed++;
                return -1;
            }
            final int count = this.suppressed;
            this.suppressed = 0;
            this.windowStart = now;
            return count;
        }
    }
}
