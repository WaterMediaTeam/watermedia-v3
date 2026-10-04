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
}
