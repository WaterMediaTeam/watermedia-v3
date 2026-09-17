package org.watermedia.test.tools;

import org.junit.jupiter.api.Test;
import org.watermedia.tools.IOTool;

import static org.junit.jupiter.api.Assertions.*;

class CleanupFailureTest {
    @Test
    void preservesFirstOrdinaryCauseAndAvoidsSelfSuppression() {
        final Throwable first = new IllegalStateException("first");
        final Throwable next = new IllegalArgumentException("next");
        assertSame(first, IOTool.mergeFailure(null, first));
        assertSame(first, IOTool.mergeFailure(first, first));
        assertSame(first, IOTool.mergeFailure(first, next));
        assertArrayEquals(new Throwable[] {next}, first.getSuppressed());
    }

    @Test
    void fatalErrorsRemainPrimaryThroughLaterCleanupFailures() {
        final Throwable ordinary = new IllegalStateException("ordinary");
        final Throwable assertion = new AssertionError("assertion");
        final Throwable fatal = new InternalError("fatal");
        assertSame(assertion, IOTool.mergeFailure(ordinary, assertion));
        assertSame(fatal, IOTool.mergeFailure(assertion, fatal));
        assertSame(fatal, IOTool.mergeFailure(fatal, new ThreadDeath()));
    }
}
