package org.watermedia.test.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ServerMediaPlayer}, the headless wall-clock sync authority. Its contract
 * (start/pause/seek/speed/repeat and the shared-ticker ENDED transition) is pure-Java and
 * deterministic; a regression here desyncs every client that trusts it.
 */
@DisplayName("ServerMediaPlayer")
public class ServerMediaPlayerTest {

    // TICK IS 50ms; GIVE THE SHARED TICKER A FEW CYCLES OF SLACK BEFORE ASSERTING A TRANSITION.
    private static final long ENDED_TIMEOUT_MS = 2000L;
    // NANO-CLOCK TIMING IS TOLERANCE-ASSERTED — WALLCLOCK SLEEPS ARE NOT EXACT.
    private static final long TIMING_TOLERANCE_MS = 60L;

    // CAPTURES THE LAST publishStatus TRANSITION FROM THE SHARED TICKER THREAD; volatile FOR VISIBILITY.
    private static final class StatusCapture {
        private volatile Status from;
        private volatile Status to;
        void accept(final Status prev, final Status next) { this.from = prev; this.to = next; }
    }

    @Test
    @DisplayName("start() plays and time advances with the wall clock")
    void testStartAdvancesTime() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(100_000L);
        player.start();
        assertEquals(Status.PLAYING, player.status());

        Thread.sleep(100L);
        final long t = player.time();
        assertTrue(t >= 100L - TIMING_TOLERANCE_MS && t <= 100L + TIMING_TOLERANCE_MS,
                "time() should track the wall clock, was " + t);
        player.release();
    }

    @Test
    @DisplayName("pause() freezes time, resume() continues it")
    void testPauseFreezesTime() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(100_000L);
        player.start();

        Thread.sleep(80L);
        assertTrue(player.pause());
        final long frozen = player.time();
        Thread.sleep(80L);
        // TIME MUST NOT MOVE WHILE PAUSED.
        assertEquals(frozen, player.time());

        assertTrue(player.resume());
        Thread.sleep(80L);
        assertTrue(player.time() > frozen, "time must resume advancing after resume()");
        player.release();
    }

    @Test
    @DisplayName("seek() sets the reported position")
    void testSeekSetsPosition() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(100_000L);
        player.startPaused();
        assertTrue(player.seek(30_000L));
        // PAUSED: time() REPORTS THE ACCUMULATED POSITION VERBATIM.
        assertEquals(30_000L, player.time());
        player.release();
    }

    @Test
    @DisplayName("speed(2x) roughly doubles the clock rate")
    void testSpeedScalesClock() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(100_000L); // NON-LIVE SO canSpeed() ALLOWS THE CHANGE
        player.start();
        assertTrue(player.speed(2.0f));

        Thread.sleep(100L);
        final long t = player.time();
        // ~200ms OF MEDIA TIME FOR ~100ms OF WALL TIME AT 2x.
        assertTrue(t >= 200L - 2 * TIMING_TOLERANCE_MS && t <= 200L + 2 * TIMING_TOLERANCE_MS,
                "time() at 2x should be ~2x wall, was " + t);
        player.release();
    }

    @Test
    @DisplayName("Non-repeat playback transitions to ENDED and notifies the status listener")
    void testEndedTransitionAndListener() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        final StatusCapture cap = new StatusCapture();
        player.onStatus(cap::accept);

        player.syncDuration(150L);
        player.repeat(false);
        player.start();

        assertTrue(PlayerWait.awaitStatus(player, ENDED_TIMEOUT_MS, Status.ENDED));
        assertEquals(Status.ENDED, player.status());
        // TERMINAL TIME IS CLAMPED TO THE DURATION.
        assertEquals(150L, player.time());
        // THE LISTENER SAW THE PLAYING -> ENDED TRANSITION.
        assertEquals(Status.PLAYING, cap.from);
        assertEquals(Status.ENDED, cap.to);
        player.release();
    }

    @Test
    @DisplayName("Repeat wraps without ENDING and time() never overruns the duration")
    void testRepeatWrapsWithoutEnding() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        final StatusCapture cap = new StatusCapture();
        player.onStatus(cap::accept);

        player.syncDuration(120L);
        player.repeat(true);
        player.start();

        // ACROSS SEVERAL LOOP PERIODS THE CLOCK MUST STAY PLAYING AND WRAP WITHIN [0, duration).
        Thread.sleep(500L);
        assertEquals(Status.PLAYING, player.status());
        assertFalse(player.ended());
        assertTrue(player.time() < 120L, "repeat time() must wrap under the duration, was " + player.time());
        assertNotNull(player.status());
        // NO TERMINAL TRANSITION WAS EVER PUBLISHED WHILE LOOPING.
        assertNull(cap.to);
        player.release();
    }
}
