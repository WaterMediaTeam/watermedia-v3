package org.watermedia.bootstrap.app;

/**
 * State for the global error dialog.
 */
public final class ErrorState {
    // VOLATILE LIKE THE SIBLING UploadState/CleanupState: syncShell() POLLS has() EACH FRAME ON THE RENDER
    // THREAD, AND show() MAY BE CALLED FROM A BACKGROUND WORKER (E.G. AN UPLOAD/CLEANUP FAILURE).
    public volatile String title;
    public volatile String message;
    public volatile Runnable onClose;

    public void show(final String title, final String message, final Runnable onClose) {
        this.title = title;
        this.onClose = onClose;
        this.message = message; // WRITTEN LAST: has() GATES ON message, SO title/onClose PUBLISH FIRST
    }

    public void show(final String message, final Runnable onClose) {
        this.show("Error", message, onClose);
    }

    public boolean has() {
        return this.message != null;
    }

    public void clear() {
        final Runnable callback = this.onClose;
        this.title = null;
        this.message = null;
        this.onClose = null;
        if (callback != null) callback.run();
    }
}
