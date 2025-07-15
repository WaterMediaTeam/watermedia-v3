package org.watermedia.tools;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import static org.watermedia.WaterMedia.LOGGER;

public class TimeTool {
    private static final Marker IT = MarkerManager.getMarker("TimeTool");
    private static long START_TIME = -1;
    public static void startTimer() {
        if (START_TIME != -1) {
            throw new IllegalStateException("Timer was already started.");
        }

        START_TIME = System.currentTimeMillis();
    }

    public static long getElapsedTime() {
        if (START_TIME == -1) {
            throw new IllegalStateException("Timer was not started.");
        }

        return System.currentTimeMillis() - START_TIME;
    }

    public static void endTimer() {
        if (START_TIME == -1) {
            throw new IllegalStateException("Timer was not started.");
        }

        final long elapsed = getElapsedTime();
        LOGGER.info(IT, "Elapsed time: " + elapsed + " ms");
        START_TIME = -1; // Reset the timer
    }
}
