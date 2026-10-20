package org.watermedia.bootstrap.app;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** State of the log upload dialog. */
public final class UploadState {
    public volatile boolean visible;
    public volatile boolean working;
    public volatile boolean done;
    public volatile boolean error;
    public volatile boolean uploadsDone;
    public volatile boolean issueCopied;
    public volatile boolean issueOpened;
    public volatile int stage = 1;
    public volatile String status = "READY";
    public volatile String issueUrl;
    // REPOSITORY THE STAGE-3 SUBMIT ACTION WILL OPEN (WATERMEDIA BY DEFAULT, OR A SUSPECTED MOD)
    public volatile String repoUrl;
    public final List<AppContext.UploadFileEntry> files = new CopyOnWriteArrayList<>();
    // IDS OF SUSPECT_MODS WHOSE JAR WAS FOUND IN THE MODS FOLDER (POPULATED DURING THE UPLOAD SCAN)
    public final Set<String> suspectModIds = ConcurrentHashMap.newKeySet();

    /** Returns the dialog to its first stage, before a new scan. */
    public void reset() {
        this.working = false;
        this.done = false;
        this.error = false;
        this.uploadsDone = false;
        this.issueCopied = false;
        this.issueOpened = false;
        this.stage = 1;
        this.status = "SCAN";
        this.issueUrl = null;
        this.repoUrl = null;
        this.files.clear();
    }
}
