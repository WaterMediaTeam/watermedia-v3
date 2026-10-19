package org.watermedia.api.platform;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live handle to an asynchronous {@link PlatformAPI#search(String) search}.
 * <p>
 * The {@link #results() results} list fills in off-thread as each registered {@link Platform}
 * answers — a caller (typically a client UI) can poll it every frame and draw hits as they land,
 * without ever blocking. Platforms are probed concurrently on a shared daemon pool, and a newer
 * search supersedes this one: when that happens this handle ends at once, {@link #done() done} and
 * {@link #superseded() superseded}, with the hits gathered so far.
 */
public final class PlatformSearch {
    private final String query;
    // WRITTEN BY THE SEARCH THREADS, READ BY THE CALLER (UI) THREAD — COPY-ON-WRITE KEEPS BOTH SAFE WITHOUT LOCKS
    private final List<PlatformResult> results = new CopyOnWriteArrayList<>();
    // GROW-ONLY UNMODIFIABLE VIEW: LETS results() BE POLLED PER FRAME WITHOUT COPYING THE WHOLE LIST EACH CALL
    private final List<PlatformResult> resultsView = Collections.unmodifiableList(this.results);
    private volatile boolean done;
    private volatile boolean superseded;

    PlatformSearch(final String query) {
        this.query = query;
    }

    /**
     * Returns the search text this handle was created for.
     */
    public String query() {
        return this.query;
    }

    /**
     * Returns an unmodifiable, live view of the results gathered so far. Safe to read from the render
     * thread while the search threads keep appending: the backing list is copy-on-write and grow-only,
     * so iteration is stable and {@code size()}/{@code get()} stay consistent, and the view simply
     * reflects more hits as they land — until the search is {@link #done() done}.
     */
    public List<PlatformResult> results() {
        return this.resultsView;
    }

    /**
     * Returns whether no more results will arrive: every registered platform answered, or a newer
     * search {@link #superseded() superseded} this one.
     */
    public boolean done() {
        return this.done;
    }

    /** Returns whether a newer search ended this one before every platform answered. */
    public boolean superseded() {
        return this.superseded;
    }

    // APPENDS A PLATFORM'S HITS TO THE LIVE LIST — CALLED ONLY FROM THE SEARCH THREAD
    void add(final List<PlatformResult> hits) {
        this.results.addAll(hits);
    }

    // MARKS THE SEARCH AS FULLY PROCESSED
    void complete() {
        this.done = true;
    }

    // ENDS A SEARCH A NEWER ONE REPLACED; A SEARCH THAT ALREADY COMPLETED STAYS COMPLETE
    void supersede() {
        if (this.done) return;
        this.superseded = true;
        this.done = true;
    }
}
