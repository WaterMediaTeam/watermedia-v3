package org.watermedia.bootstrap.app;

import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.ALEngine;
import org.watermedia.api.media.JSEngine;
import org.watermedia.api.media.SFXEngine;

import java.util.function.Supplier;

/**
 * Audio backend the standalone app hands to {@code MediaAPI.player} when opening a player.
 * A first-class, user-selectable choice (see the settings screen) — not an automatic fallback:
 * {@link #JAVASOUND} can be picked even when OpenAL is available, and {@link #OPENAL} stays the default.
 */
public enum AudioEngine {
    /** LWJGL OpenAL output ({@link ALEngine}) — the default. */
    OPENAL(MediaAPI::alEngine),
    /** Native, dependency-free Java Sound output ({@link JSEngine}). */
    JAVASOUND(MediaAPI::jsEngine);

    private final Supplier<SFXEngine> supplier;

    AudioEngine(final Supplier<SFXEngine> supplier) {
        this.supplier = supplier;
    }

    /**
     * Returns the engine factory passed to {@code MediaAPI.player}. Each call to the supplier
     * builds a fresh engine — engines are player-exclusive and must not be shared.
     * @return the {@link SFXEngine} supplier for this backend
     */
    public Supplier<SFXEngine> supplier() {
        return this.supplier;
    }
}
