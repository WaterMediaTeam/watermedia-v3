package org.watermedia.api.util;

/**
 * Named easing curves over {@link MathUtil}'s easing functions.
 * <p>
 * This API is safe to use even if watermedia isn't successfully loaded.
 */
public enum MathEases {
    EASE_IN(MathUtil::easeIn),
    EASE_OUT(MathUtil::easeOut),
    EASE_IN_OUT(MathUtil::easeInOut),
    EASE_OUT_IN(MathUtil::easeOutIn),
    EASE_IN_SINE(MathUtil::easeInSine),
    EASE_OUT_SINE(MathUtil::easeOutSine),
    EASE_IN_OUT_SINE(MathUtil::easeInOutSine),
    EASE_IN_CUBIC(MathUtil::easeInCubic),
    EASE_OUT_CUBIC(MathUtil::easeOutCubic),
    EASE_IN_OUT_CUBIC(MathUtil::easeInOutCubic),
    // EASE_IN/EASE_OUT ALREADY COVER QUADRATIC IN/OUT; ONLY THE START↔END IN-OUT SHAPE IS DISTINCT
    EASE_IN_OUT_QUAD(MathUtil::easeInOutQuad),
    EASE_IN_ELASTIC(MathUtil::easeInElastic),
    EASE_OUT_ELASTIC(MathUtil::easeOutElastic),
    EASE_IN_OUT_ELASTIC(MathUtil::easeInOutElastic),
    EASE_IN_QUINT(MathUtil::easeInQuint),
    EASE_OUT_QUINT(MathUtil::easeOutQuint),
    EASE_IN_OUT_QUINT(MathUtil::easeInOutQuint),
    EASE_IN_CIRCLE(MathUtil::easeInCircle),
    EASE_OUT_CIRCLE(MathUtil::easeOutCircle),
    EASE_IN_OUT_CIRCLE(MathUtil::easeInOutCircle),
    EASE_IN_EXPO(MathUtil::easeInExpo),
    EASE_OUT_EXPO(MathUtil::easeOutExpo),
    EASE_IN_OUT_EXPO(MathUtil::easeInOutExpo),
    EASE_IN_BACK(MathUtil::easeInBack),
    EASE_OUT_BACK(MathUtil::easeOutBack),
    EASE_IN_OUT_BACK(MathUtil::easeInOutBack),
    EASE_IN_BOUNCE(MathUtil::easeInBounce),
    EASE_OUT_BOUNCE(MathUtil::easeOutBounce),
    EASE_IN_OUT_BOUNCE(MathUtil::easeInOutBounce);

    private final Easing easing;

    MathEases(final Easing easing) {
        this.easing = easing;
    }

    // APPLIES THE EASING CURVE: INTERPOLATES BETWEEN start AND end AT NORMALISED TIME value (0.0-1.0)
    public double apply(final double start, final double end, final double value) {
        return this.easing.apply(start, end, value);
    }

    /**
     * A single easing curve mapping {@code (start, end, t)} to an interpolated value.
     */
    @FunctionalInterface
    public interface Easing {
        double apply(double start, double end, double t);
    }
}
