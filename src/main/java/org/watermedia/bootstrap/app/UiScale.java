package org.watermedia.bootstrap.app;

/**
 * User-selectable global UI magnification (see the settings screen). Each constant maps to a display
 * label and either a fixed logical-to-physical factor or, for {@link #AUTO}, a monitor-derived factor
 * resolved at runtime. The choice is persisted through {@code RenderSystem.saveUiScalePreference} as the
 * token {@link #preference()} produces ({@code "auto"} or the factor), and read back with
 * {@link #fromPreference(String)}.
 */
public enum UiScale {
    /** Derive the factor from the monitor's content scale and resolution. */
    AUTO("AUTO", 0f),
    X100("1.0x", 1.0f),
    X125("1.25x", 1.25f),
    X150("1.5x", 1.5f),
    X200("2.0x", 2.0f);

    /** Human-readable label shown in the settings row. */
    public final String label;
    // FIXED PHYSICAL-PX-PER-LOGICAL-PX FACTOR; 0 IS THE AUTO SENTINEL (RESOLVED AT RUNTIME)
    public final float factor;

    UiScale(final String label, final float factor) {
        this.label = label;
        this.factor = factor;
    }

    /**
     * Returns the persistence token for this choice: {@code "auto"} for {@link #AUTO}, otherwise the
     * fixed factor. The value round-trips through {@link #fromPreference(String)}.
     * @return the token to persist
     */
    public String preference() {
        return this == AUTO ? "auto" : Float.toString(this.factor);
    }

    /**
     * Resolves a persisted preference token back to a constant. An absent, {@code "auto"} or
     * unrecognized value maps to {@link #AUTO}.
     * @param pref the persisted token ({@code "auto"} or a factor), possibly {@code null}
     * @return the matching constant, or {@link #AUTO}
     */
    public static UiScale fromPreference(final String pref) {
        if (pref == null || pref.isBlank() || pref.equalsIgnoreCase("auto")) return AUTO;
        try {
            final float f = Float.parseFloat(pref.trim());
            for (final UiScale s: values()) {
                if (s != AUTO && Math.abs(s.factor - f) < 0.001f) return s;
            }
        } catch (final NumberFormatException ignored) {
        }
        return AUTO;
    }
}
