package org.watermedia.bootstrap.app;

/** Interface magnification choices; {@link #AUTO} derives the factor from the window's monitor at runtime. */
public enum UiScale {
    AUTO(0f),
    NORMAL(1f),
    LARGE(1.25f),
    LARGER(1.5f),
    LARGEST(2f);

    /** Physical pixels per logical pixel; 0 for {@link #AUTO}. */
    public final float factor;

    UiScale(final float factor) {
        this.factor = factor;
    }
}
