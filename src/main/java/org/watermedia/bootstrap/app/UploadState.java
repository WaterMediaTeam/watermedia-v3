package org.watermedia.bootstrap.app;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** State of the log upload dialog, with the repositories it can file an issue against. */
public final class UploadState {
    public static final String REPO = "SrRapero720/watermedia"; // MIRRORS github IN gradle.properties
    public static final boolean IN_MODS = new File("").getAbsoluteFile().getName().equalsIgnoreCase("mods");
    // MODS BUILT ON WATERMEDIA, OFFERED AS ISSUE TARGETS IN THE THIRD UPLOAD STAGE WHEN A JAR NAME IN THE
    // MODS FOLDER CONTAINS THEIR id
    public static final List<SuspectMod> SUSPECT_MODS = List.of(
            new SuspectMod("fancymenu", "FancyMenu", "Keksuccino/FancyMenu", "https://github.com/Keksuccino/FancyMenu/issues/new"),
            new SuspectMod("waterframes", "WaterFrames", "SrRapero720/waterframes", "https://github.com/SrRapero720/waterframes/issues/new"),
            new SuspectMod("watervision", "WaterVision", "SrRapero720/watervision", "https://github.com/SrRapero720/watervision/issues/new"),
            new SuspectMod("littleframes", "LittleFrames", "CreativeMD/littleframes", "https://github.com/CreativeMD/littleframes/issues/new")
    );

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
    public final List<FileEntry> files = new CopyOnWriteArrayList<>();
    // IDS OF SUSPECT_MODS WHOSE JAR WAS FOUND IN THE MODS FOLDER (POPULATED DURING THE UPLOAD SCAN)
    public final Set<String> suspectModIds = ConcurrentHashMap.newKeySet();

    public record SuspectMod(String id, String name, String slug, String url) {
    }

    /** One log file of the dialog: where it lives, whether it can go up and how its upload went. */
    public static final class FileEntry {
        public final String name;
        public final Path path;
        public volatile String sizeLabel = "-";
        public volatile String state = "PENDING";
        public volatile String url = "";
        public volatile int progress;
        public volatile boolean present;
        public volatile boolean valid;
        public volatile boolean uploaded;

        public FileEntry(final String name, final Path path) {
            this.name = name;
            this.path = path;
        }
    }

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
