package org.watermedia.bootstrap.app;

/**
 * State for the global error dialog.
 */
public final class ErrorState {
    public String title;
    public String message;
    public Runnable onClose;

    public void show(final String title, final String message, final Runnable onClose) {
        this.title = title;
        this.message = message;
        this.onClose = onClose;
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
