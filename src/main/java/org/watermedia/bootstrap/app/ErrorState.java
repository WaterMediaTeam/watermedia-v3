package org.watermedia.bootstrap.app;

/** State of the global error dialog. */
final class ErrorState {
    // show() MAY RUN ON A WORKER WHILE syncShell() POLLS has() EVERY FRAME ON THE RENDER THREAD
    public volatile String title;
    public volatile String message;
    public volatile Runnable onClose;

    public void show(final String title, final String message, final Runnable onClose) {
        this.title = title;
        this.onClose = onClose;
        this.message = message; // WRITTEN LAST: has() GATES ON message, SO title/onClose PUBLISH FIRST
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
