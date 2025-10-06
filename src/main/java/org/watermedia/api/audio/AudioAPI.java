package org.watermedia.api.audio;

import org.lwjgl.openal.AL10;

public class AudioAPI {
    public static int genSource(final int[] buffers) {
        AL10.alGenBuffers(buffers);
        return AL10.alGenSources();
    }
}
