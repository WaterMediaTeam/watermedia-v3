package org.watermedia.api.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live handle to an asynchronous {@link PlatformAPI#search(String) search}.
 * <p>
 * The {@link #results() results} list fills in off-thread as each registered {@link IPlatform}
 * answers — a caller (typically a client UI) can poll it every frame and draw hits as they land,
 * without ever blocking. Searches are single-threaded and a newer search supersedes this one:
 * when that happens this handle is simply abandoned and its list stops growing (it is never
 * marked {@link #done() done}). The handle also carries the {@link #history() recent-query
 * history} captured at the moment it was issued.
 */
public final class PlatformSearch {
    private final String query;
    private final List<String> history;
    // WRITTEN BY THE SEARCH THREAD, READ BY THE CALLER (UI) THREAD — COPY-ON-WRITE KEEPS BOTH SAFE WITHOUT LOCKS
    private final List<PlatformResult> results = new CopyOnWriteArrayList<>();
    private volatile boolean done;

    PlatformSearch(final String query, final List<String> history) {
        this.query = query;
        this.history = history;
    }

    /**
     * Returns the search text this handle was created for.
     */
    public String query() {
        return this.query;
    }

    /**
     * Returns an immutable snapshot of the results gathered so far. Safe to read from the render
     * thread while the search thread keeps appending: the snapshot is stable for the caller's frame
     * (its size and contents always agree), and a later call returns a larger snapshot as more hits
     * land — until the search is {@link #done() done} or superseded.
     */
    public List<PlatformResult> results() {
        return List.copyOf(this.results);
    }

    /**
     * Returns the recent-query history (at most {@code 10} entries), most recent first and
     * including this search's own query. The list is an immutable snapshot taken when the search
     * was issued.
     */
    public List<String> history() {
        return this.history;
    }

    /**
     * Returns whether every registered platform has been queried, so no more results will arrive.
     * Stays {@code false} forever on a handle that a newer search superseded.
     */
    public boolean done() {
        return this.done;
    }

    // APPENDS A PLATFORM'S HITS TO THE LIVE LIST — CALLED ONLY FROM THE SEARCH THREAD
    void add(final List<PlatformResult> hits) {
        this.results.addAll(hits);
    }

    // MARKS THE SEARCH AS FULLY PROCESSED
    void complete() {
        this.done = true;
    }
}
