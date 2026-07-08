package org.watermedia.bootstrap.app;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * State for the upload logs dialog.
 */
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
    public volatile String issueUrl = "github.com/watermedia/issues/new";
    // REPOSITORY THE STAGE-3 SUBMIT ACTION WILL OPEN (WATERMEDIA BY DEFAULT, OR A SUSPECTED MOD)
    public volatile String repoUrl;
    public final List<AppContext.UploadFileEntry> files = new CopyOnWriteArrayList<>();
    // IDS OF SUSPECT_MODS WHOSE JAR WAS FOUND IN THE MODS FOLDER (POPULATED DURING THE UPLOAD SCAN)
    public final Set<String> suspectModIds = ConcurrentHashMap.newKeySet();
}
