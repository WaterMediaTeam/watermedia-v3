package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.WaterMediaModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Verifies that {@link WaterMediaModule#mergeFailure} keeps the first ordinary cause and lets fatal errors win. */
@DisplayName("Cleanup failure merging")
public class CleanupFailureTest {
    @Test
    @DisplayName("Preserves the first ordinary cause and avoids self-suppression")
    void preservesFirstOrdinaryCauseAndAvoidsSelfSuppression() {
        final Throwable first = new IllegalStateException("first");
        final Throwable next = new IllegalArgumentException("next");
        assertSame(first, WaterMediaModule.mergeFailure(null, first));
        assertSame(first, WaterMediaModule.mergeFailure(first, first));
        assertSame(first, WaterMediaModule.mergeFailure(first, next));
        assertSame(next, first.getSuppressed()[0]);
        assertEquals(1, first.getSuppressed().length);
    }

    @Test
    @DisplayName("Errors win over exceptions and fatal VM errors win over everything")
    void fatalErrorsWin() {
        final Throwable ordinary = new IllegalStateException("ordinary");
        final Throwable assertion = new AssertionError("assertion");
        final Throwable fatal = new OutOfMemoryError("fatal");
        assertSame(assertion, WaterMediaModule.mergeFailure(ordinary, assertion));
        assertSame(fatal, WaterMediaModule.mergeFailure(assertion, fatal));
        assertSame(fatal, WaterMediaModule.mergeFailure(fatal, new StackOverflowError()));
    }
}
