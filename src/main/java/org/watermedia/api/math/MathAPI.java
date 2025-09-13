package org.watermedia.api.math;

/**
 *
 * This API its safe to use even if watermedia isn't successfully loaded
 */
public class MathAPI {

    public static int clamp(int min, int max, int value) {
        return value >= max ? max : value <= min ? min : value;
    }
}
