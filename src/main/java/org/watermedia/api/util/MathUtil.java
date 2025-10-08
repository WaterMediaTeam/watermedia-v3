package org.watermedia.api.util;

/**
 *
 * This API its safe to use even if watermedia isn't successfully loaded
 */
public enum MathUtil {
    EASE_IN {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeIn(start, end, value);
        }
    },
    EASE_OUT {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOut(start, end, value);
        }
    },
    EASE_IN_OUT {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOut(start, end, value);
        }
    },
    EASE_OUT_IN {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutIn(start, end, value);
        }
    },
    EASE_IN_SINE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInSine(start, end, value);
        }
    },
    EASE_OUT_SINE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutSine(start, end, value);
        }
    },
    EASE_IN_OUT_SINE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutSine(start, end, value);
        }
    },
    EASE_IN_CUBIC {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInCubic(start, end, value);
        }
    },
    EASE_OUT_CUBIC {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutCubic(start, end, value);
        }
    },
    EASE_IN_OUT_CUBIC {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutCubic(start, end, value);
        }
    },
    EASE_IN_QUAD {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInQuad(start, end, value);
        }
    },
    EASE_OUT_QUAD {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutQuad(start, end, value);
        }
    },
    EASE_IN_OUT_QUAD {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutQuad(start, end, value);
        }
    },
    EASE_IN_ELASTIC {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInElastic(start, end, value);
        }
    },
    EASE_OUT_ELASTIC {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutElastic(start, end, value);
        }
    },
    EASE_IN_OUT_ELASTIC {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutElastic(start, end, value);
        }
    },
    EASE_IN_QUINT {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInQuint(start, end, value);
        }
    },
    EASE_OUT_QUINT {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutQuint(start, end, value);
        }
    },
    EASE_IN_OUT_QUINT {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutQuint(start, end, value);
        }
    },
    EASE_IN_CIRCLE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInCircle(start, end, value);
        }
    },
    EASE_OUT_CIRCLE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutCircle(start, end, value);
        }
    },
    EASE_IN_OUT_CIRCLE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutCircle(start, end, value);
        }
    },
    EASE_IN_EXPO {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInExpo(start, end, value);
        }
    },
    EASE_OUT_EXPO {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutExpo(start, end, value);
        }
    },
    EASE_IN_OUT_EXPO {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutExpo(start, end, value);
        }
    },
    EASE_IN_BACK {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInBack(start, end, value);
        }
    },
    EASE_OUT_BACK {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutBack(start, end, value);
        }
    },
    EASE_IN_OUT_BACK {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutBack(start, end, value);
        }
    },
    EASE_IN_BOUNCE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInBounce(start, end, value);
        }
    },
    EASE_OUT_BOUNCE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeOutBounce(start, end, value);
        }
    },
    EASE_IN_OUT_BOUNCE {
        @Override
        public double apply(final double start, final double end, final double value) {
            return MathUtil.easeInOutBounce(start, end, value);
        }
    };

    public abstract double apply(double start, double end, double value);

    private static final int SIN_SIZE = 65536;
    private static final float[] SIN = new float[SIN_SIZE];

    static {
        for (int i = 0; i < SIN_SIZE; i++) {
            SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / SIN_SIZE);
        }
    }

    /**
     * 1 seconds in Minecraft equals 20 ticks
     * 20x50 equals 1000ms (1 sec)
     *
     * @param ticks Minecraft Tick count
     * @return ticks converted to MS
     */
    public static long tickToMs(final int ticks) { return ticks * 50L; }

    /**
     * 1 seconds in Minecraft equals 20 ticks
     * 20x50 equals 1000ms (1 sec)
     *
     * @param partialTicks Minecraft Partial tick count
     * @return ticks converted to MS
     */
    public static long tickToMs(final float partialTicks) { return (long) (partialTicks * 50L); }

    /**
     * 1000ms (1 sec) equals 20 ms in Minecraft
     * 1000/50 equals 20 Ticks (1 sec)
     *
     * @param ms Time in milliseconds
     * @return Milliseconds converted to Ticks
     */
    public static int msToTick(final long ms) { return (int) (ms / 50); }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be reset to zero
     * ej start: 0, end: 10, time: 15 - Result will be 0.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleTempo(final long start, final long end, final long time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");

        final long duration = end - start; // start acts like a margin
        final long realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        long result = realTime / duration;
        if (realTime > duration) result %= duration;
        return result;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be reset to zero
     * ej start: 0, end: 10, time: 15 - Result will be 0.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleTempo(final double start, final double end, final double time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");
        final double duration = end - start; // start acts like a margin
        final double realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        double result = realTime / duration;
        if (realTime > duration) result %= duration;
        return result;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be reset to zero
     * ej start: 0, end: 10, time: 15 - Result will be 0.5d
     * Method helper to calculate time in ticks (20t = 1s)
     * @param startTick initial point to calculate scale, cannot be negative
     * @param endTick final point to calculate scale. cannot be negative
     * @param timeTick current time between values
     * @return scaled time
     */
    public static double scaleTempoTick(final int startTick, final int endTick, final int timeTick) {
        return scaleTempo(MathUtil.tickToMs(startTick), MathUtil.tickToMs(endTick), MathUtil.tickToMs(timeTick));
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be over-scaled
     * ej start: 0, end: 10, time: 15 - Result will be 1.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleDesTempo(final long start, final long end, final long time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");
        final long duration = end - start; // start acts like a margin
        final long realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        return (double) realTime / duration;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be over-scaled
     * ej start: 0, end: 10, time: 15 - Result will be 1.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleDesTempo(final double start, final double end, final double time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");
        final double duration = end - start; // start acts like a margin
        final double realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        final double result = realTime / duration;
        return (Double.isNaN(result)) ? 0 : result;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be over-scaled
     * ej start: 0, end: 10, time: 15 - Result will be 1.5d
     * Method helper to calculate time in ticks (20t = 1s)
     * @param startTick initial point to calculate scale, cannot be negative
     * @param endTick final point to calculate scale. cannot be negative
     * @param timeTick current time between values
     * @return scaled time
     */
    public static double scaleDesTempoTick(final int startTick, final int endTick, final int timeTick) {
        return scaleTempo(MathUtil.tickToMs(startTick), MathUtil.tickToMs(endTick), MathUtil.tickToMs(timeTick));
    }

    /**
     * Returns the floor modulus of the {@code long} arguments.
     * <p>
     * The floor modulus is {@code r = x - (floorDiv(x, y) * y)},
     * has the same sign as the divisor {@code y} or is zero, and
     * is in the range of {@code -abs(y) < r < +abs(y)}.
     *
     * <p>
     * The relationship between {@code floorDiv} and {@code floorMod} is such that:
     * <ul>
     *   <li>{@code floorDiv(x, y) * y + floorMod(x, y) == x}</li>
     * </ul>
     * <p>
     *
     *     Method doesn't throw exceptions when X and Y is ZERO.
     *     Instead, returns ZERO by default
     *
     * @param x the dividend
     * @param y the divisor
     * @return the floor modulus {@code x - (floorDiv(x, y) * y)}
     * @since 2.0.7
     */
    public static long floorMod(final long x, final long y) {
        if (x == 0 || y == 0) return 0;

        final long r = x % y;
        // if the signs are different and modulo not zero, adjust result
        if ((x ^ y) < 0 && r != 0) {
            return r + y;
        }
        return r;
    }

    public static int floorMod(final int x, final int y) {
        if (x == 0 || y == 0) return 0;

        final int r = x % y;
        // if the signs are different and modulo not zero, adjust result
        if ((x ^ y) < 0 && r != 0) {
            return r + y;
        }
        return r;
    }

    public static int floorMod(final long x, final int y) {
        // Result cannot overflow the range of int.
        return (int) floorMod(x, (long)y);
    }

    public static int floorMod(final int x, final long y) {
        // Result cannot overflow the range of int.
        return (int) floorMod((long) x, y);
    }

    public static short min(final short a, final short b) {
        return (a <= b) ? a : b;
    }

    public static byte min(final byte a, final byte b) {
        return (a <= b) ? a : b;
    }

    /**
     * Creates a hexadecimal color based on gave params
     * All values need to be in a range of 0 ~ 255
     * @param a Alpha
     * @param r Red
     * @param g Green
     * @param b Blue
     * @return HEX color
     */
    public static int argb(final int a, final int r, final int g, final int b) { return (a << 24) | (r << 16) | (g << 8) | b; }

    /**
     * Converts arguments into an ease-in value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in interpolation between start and end at time t.
     */
    public static double easeIn(final double start, final double end, final double t) {
        return start + (end - start) * t * t;
    }

    /**
     * Converts arguments into an ease-out value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out interpolation between start and end at time t.
     */
    public static double easeOut(final double start, final double end, final double t) {
        return start + (end - start) * (1 - Math.pow(1 - t, 2));
    }

    /**
     * Converts arguments into an ease-in-out value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out interpolation between start and end at time t.
     */
    public static double easeInOut(final double start, final double end, final double t) {
        return t < 0.5 ? easeIn(start, end / 2, t * 2) : easeOut(start + (end / 2), end, (t - 0.5) * 2);
    }

    /**
     * Converts arguments into an ease-out-in value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-in interpolation between start and end at time t.
     */
    public static double easeOutIn(final double start, final double end, final double t) {
        return t < 0.5 ? easeOut(start, end / 2, t * 2) : easeIn(start + (end / 2), end, (t - 0.5) * 2);
    }

    /**
     * Converts arguments into an ease-in-circle value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-circle interpolation between start and end at time t.
     */
    public static double easeInCircle(final double start, final double end, final double t) {
        return start + (end - start) * (1 - Math.sqrt(1 - t * t));
    }

    /**
     * Converts arguments into an easy-ease value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of easy-ease interpolation between start and end at time t.
     */
    public static double easyEase(final double start, final double end, final double t) {
        return start + (end - start) * ((t < 0.5) ? 2 * t * t : -1 + 2 * t * (2 - t));
    }

    /**
     * Converts arguments into an ease-in-sine value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-sine interpolation between start and end at time t.
     */
    public static double easeInSine(final double start, final double end, final double t) {
        return (end - start) * (1 - cos((float) (t * Math.PI / 2))) + start;
    }

    /**
     * Converts arguments into an ease-in-cubic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-cubic interpolation between start and end at time t.
     */
    public static double easeInCubic(final double start, final double end, final double t) {
        return (end - start) * (t * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-quint value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-quint interpolation between start and end at time t.
     */
    public static double easeInQuint(final double start, final double end, final double t) {
        return (end - start) * (t * t * t * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-elastic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-elastic interpolation between start and end at time t.
     */
    public static double easeInElastic(final double start, final double end, final double t) {
        if (t == 0) {
            return start;
        } else if (t == 1) {
            return end;
        }
        final double c4 = (2 * Math.PI) / 3;
        return start - Math.pow(2, 10 * t - 10) * sin((float) ((t * 10 - 10.75) * c4)) * (end - start);
    }

    /**
     * Converts arguments into an ease-out-sine value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-sine interpolation between start and end at time t.
     */
    public static double easeOutSine(final double start, final double end, final double t) {
        return (end - start) * sin((float) (t * Math.PI / 2)) + start;
    }

    /**
     * Converts arguments into an ease-out-cubic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-cubic interpolation between start and end at time t.
     */
    public static double easeOutCubic(final double start, final double end, final double t) {
        return (end - start) * (1 - Math.pow(1 - t, 3)) + start;
    }

    /**
     * Converts arguments into an ease-out-quint value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-quint interpolation between start and end at time t.
     */
    public static double easeOutQuint(final double start, final double end, final double t) {
        return (end - start) * (1 - Math.pow(1 - t, 5)) + start;
    }

    /**
     * Converts arguments into an ease-out-circle value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-circle interpolation between start and end at time t.
     */
    public static double easeOutCircle(final double start, final double end, final double t) {
        return (end - start) * Math.sqrt(1 - Math.pow(t - 1, 2)) + start;
    }

    /**
     * Converts arguments into an ease-out-elastic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-elastic interpolation between start and end at time t.
     */
    public static double easeOutElastic(final double start, final double end, final double t) {
        if (t == 0) {
            return start;
        } else if (t == 1) {
            return end;
        }
        return (end - start) * (Math.pow(2, -10 * t) * sin((float) ((t * 10 - 0.75) * ((2 * Math.PI) / 3))) + 1) + start;
    }

    /**
     * Converts arguments into an ease-in-out-sine value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-sine interpolation between start and end at time t.
     */
    public static double easeInOutSine(final double start, final double end, final double t) {
        return (end - start) * (-(cos((float) (Math.PI * t)) - 1) / 2) + start;
    }

    /**
     * Converts arguments into an ease-in-out-cubic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-cubic interpolation between start and end at time t.
     */
    public static double easeInOutCubic(final double start, final double end, final double t) {
        if (t < 0.5) {
            return (end - start) * (4 * t * t * t) + start;
        } else {
            return (end - start) * (1 - Math.pow(-2 * t + 2, 3) / 2) + start;
        }
    }

    /**
     * Converts arguments into an ease-in-out-quint value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-int-out-quint interpolation between start and end at time t.
     */
    public static double easeInOutQuint(final double start, final double end, final double t) {
        if (t < 0.5) {
            return (end - start) * (16 * Math.pow(t, 5)) + start;
        } else {
            return (end - start) * (1 - Math.pow(-2 * t + 2, 5) / 2) + start;
        }
    }

    /**
     * Converts arguments into an ease-in-out-circle value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-circle interpolation between start and end at time t.
     */
    public static double easeInOutCircle(final double start, final double end, final double t) {
        if (t < 0.5) {
            return start + (end - start) * (1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2;
        } else {
            return start + (end - start) * (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2;
        }
    }

    /**
     * Converts arguments into an ease-in-out-elastic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-elastic interpolation between start and end at time t.
     */
    public static double easeInOutElastic(final double start, final double end, final double t) {
        if (t == 0) {
            return start;
        } else if (t == 1) {
            return end;
        }
        final double c5 = (2 * Math.PI) / 4.5;
        if (t < 0.5) {
            return start - (Math.pow(2, 20 * t - 10) * sin((float) ((20 * t - 11.125) * c5))) / 2;
        } else {
            return start + (Math.pow(2, -20 * t + 10) * sin((float) ((20 * t - 11.125) * c5))) / 2 + (end - start);
        }
    }

    /**
     * Converts arguments into an ease-in-quad value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-quad interpolation between start and end at time t.
     */
    public static double easeInQuad(final double start, final double end, final double t) {
        return (end - start) * (t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-quart value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-quart interpolation between start and end at time t.
     */
    public static double easeInQuart(final double start, final double end, final double t) {
        return (end - start) * (t * t * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-expo value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-expo interpolation between start and end at time t.
     */
    public static double easeInExpo(final double start, final double end, final double t) {
        if (t == 0) {
            return start;
        }
        return (end - start) * (Math.pow(2, 10 * t - 10)) + start;
    }

    /**
     * Converts arguments into an ease-in-back value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-back interpolation between start and end at time t.
     */
    public static double easeInBack(final double start, final double end, final double t) {
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        return (end - start) * (c3 * t * t * t - c1 * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-bounce value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-bounce interpolation between start and end at time t.
     */
    public static double easeInBounce(final double start, final double end, final double t) {
        return start + (end - start) * (1 - easeOutBounce(0, 1, 1 - t));
    }

    /**
     * Converts arguments into an ease-out-bounce value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-bounce interpolation between start and end at time t.
     */
    public static double easeOutBounce(final double start, final double end, double t) {
        final double n1 = 7.5625;
        final double d1 = 2.75;
        final double value;

        if (t < 1 / d1) {
            value = n1 * t * t;
        } else if (t < 2 / d1) {
            t -= 1.5 / d1;
            value = n1 * t * t + 0.75;
        } else if (t < 2.5 / d1) {
            t -= 2.25 / d1;
            value = n1 * t * t + 0.9375;
        } else {
            t -= 2.625 / d1;
            value = n1 * t * t + 0.984375;
        }

        return start + (end - start) * value;
    }

    /**
     * Converts arguments into an ease-out-quad value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-quad interpolation between start and end at time t.
     */
    public static double easeOutQuad(final double start, final double end, final double t) {
        return start + (end - start) * (1 - Math.pow(1 - t, 2));
    }

    /**
     * Converts arguments into an ease-out-quart value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-quart interpolation between start and end at time t.
     */
    public static double easeOutQuart(final double start, final double end, final double t) {
        return start + (end - start) * (1 - Math.pow(1 - t, 4));
    }

    /**
     * Converts arguments into an ease-out-expo value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-expo interpolation between start and end at time t.
     */
    public static double easeOutExpo(final double start, final double end, final double t) {
        if (t == 1) {
            return end;
        }
        return start + (end - start) * (1 - Math.pow(2, -10 * t));
    }

    /**
     * Converts arguments into an ease-out-back value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-back interpolation between start and end at time t.
     */
    public static double easeOutBack(final double start, final double end, final double t) {
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        final double adjustedT = t - 1;
        return start + (end - start) * (1 + c3 * Math.pow(adjustedT, 3) + c1 * Math.pow(adjustedT, 2));
    }

    /**
     * Converts arguments into an ease-in-out-quad value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-quad interpolation between start and end at time t.
     */
    public static double easeInOutQuad(final double start, final double end, final double t) {
        if (t < 0.5) {
            return start + (end - start) * (2 * t * t);
        } else {
            return start + (end - start) * (1 - Math.pow(-2 * t + 2, 2) / 2);
        }
    }

    /**
     * Converts arguments into an ease-in-out-quart value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-quart interpolation between start and end at time t.
     */
    public static double easeInOutQuart(final double start, final double end, final double t) {
        if (t < 0.5) {
            return start + (end - start) * (8 * Math.pow(t, 4));
        } else {
            return start + (end - start) * (1 - Math.pow(-2 * t + 2, 4) / 2);
        }
    }

    /**
     * Converts arguments into an ease-in-out-expo value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-expo interpolation between start and end at time t.
     */
    public static double easeInOutExpo(final double start, final double end, final double t) {
        if (t == 0) return start;
        if (t == 1) return end;

        if (t < 0.5) {
            return start + (end - start) * (Math.pow(2, 20 * t - 10) / 2);
        } else {
            return start + (end - start) * ((2 - Math.pow(2, -20 * t + 10)) / 2);
        }
    }

    /**
     * Converts arguments into an ease-in-out-back value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-back interpolation between start and end at time t.
     */
    public static double easeInOutBack(final double start, final double end, final double t) {
        final double c1 = 1.70158;
        final double c2 = c1 * 1.525;

        if (t < 0.5) {
            return start + (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2 * (end - start);
        } else {
            return start + (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (2 * t - 2) + c2) + 2) / 2 * (end - start);
        }
    }

    /**
     * Converts arguments into an ease-in-out-bounce value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-bounce interpolation between start and end at time t.
     */
    public static double easeInOutBounce(final double start, final double end, final double t) {
        if (t < 0.5) {
            return start + (end - start) * (1 - easeOutBounce(0, 1, 1 - 2 * t)) / 2;
        } else {
            return start + (end - start) * (1 + easeOutBounce(0, 1, 2 * t - 1)) / 2;
        }
    }

    public static int clamp(int min, int max, int value) {
        return value >= max ? max : value <= min ? min : value;
    }

    /**
     * Returns an approximate sine value for a given angle from a precomputed lookup table.
     *
     * @param pValue The angle in radians.
     * @return The approximate sine of the angle.
     */
    public static float sin(final float pValue) {
        return SIN[(int)(pValue * 10430.378F) & 0xFFFF];
    }

    /**
     * Returns an approximate cosine value for a given angle from a precomputed lookup table.
     *
     * @param pValue The angle in radians.
     * @return The approximate cosine of the angle.
     */
    public static float cos(final float pValue) {
        return SIN[(int)(pValue * 10430.378F + 16384.0F) & '\uffff'];
    }

    public static int sumArray(final int[] arr) {
        int r = 0;
        for (final int i: arr) {
            r += i;
        }
        return r;
    }

    public static long sumArray(final long[] arr) {
        long r = 0;
        for (final long i: arr) {
            r += i;
        }
        return r;
    }
}
