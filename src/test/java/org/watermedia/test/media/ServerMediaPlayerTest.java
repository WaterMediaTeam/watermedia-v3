package org.watermedia.test.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.api.media.players.sync.Sync;
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

    @Test
    @DisplayName("revision bumps on successful mutations and stays put on failed ones")
    void testRevisionBumps() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        final int r0 = player.revision();
        player.syncDuration(10_000L);
        assertTrue(player.revision() > r0, "syncDuration must bump the revision");

        int r = player.revision();
        assertFalse(player.pause());
        assertFalse(player.stop());
        assertEquals(r, player.revision(), "failed operations must not bump the revision");

        player.start();
        assertTrue(player.revision() > r);
        r = player.revision();
        assertTrue(player.pause());
        assertTrue(player.revision() > r);
        r = player.revision();
        assertTrue(player.seek(5_000L));
        assertTrue(player.revision() > r);
        player.release();
    }

    @Test
    @DisplayName("syncDuration is first-wins; divergent or invalid reports are ignored")
    void testDurationFirstWins() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(10_000L);
        player.syncDuration(99_000L);
        assertEquals(10_000L, player.duration());
        // ZERO/NEGATIVE REPORTS NEVER TOUCH THE SYNCED VALUE
        player.syncDuration(0L);
        player.syncDuration(-5L);
        assertEquals(10_000L, player.duration());
        player.release();
    }

    @Test
    @DisplayName("syncLive flags the source as live and locks speed")
    void testSyncLive() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(10_000L);
        assertFalse(player.liveSource());
        assertTrue(player.canSpeed());

        player.syncLive(true);
        assertTrue(player.liveSource());
        assertFalse(player.canSpeed());

        player.syncLive(false);
        assertFalse(player.liveSource());
        player.release();
    }

    @Test
    @DisplayName("seek() on a finished or stopped clock lands PAUSED at the position")
    void testSeekAfterEndedPauses() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(150L);
        player.start();
        assertTrue(PlayerWait.awaitStatus(player, ENDED_TIMEOUT_MS, Status.ENDED));

        assertTrue(player.seek(50L));
        assertEquals(Status.PAUSED, player.status());
        assertEquals(50L, player.time());

        // STOPPED SCRUBS THE SAME WAY
        assertTrue(player.stop());
        assertTrue(player.seek(80L));
        assertEquals(Status.PAUSED, player.status());
        assertEquals(80L, player.time());
        player.release();
    }

    @Test
    @DisplayName("snapshot() captures the full authoritative state")
    void testSnapshot() {
        final ServerMediaPlayer source = new ServerMediaPlayer();
        source.syncDuration(60_000L);
        source.repeat(true);
        source.volume(40);
        source.start();
        assertTrue(source.speed(2.0f));
        assertTrue(source.seek(30_000L));

        final Sync snapshot = source.snapshot();
        assertEquals(source.revision(), snapshot.revision());
        assertEquals(Status.PLAYING, snapshot.status());
        assertEquals(60_000L, snapshot.duration());
        assertEquals(2.0f, snapshot.speed());
        assertEquals(40, snapshot.volume());
        assertTrue(snapshot.repeat());
        assertFalse(snapshot.live());
        assertTrue(Math.abs(snapshot.time() - 30_000L) <= TIMING_TOLERANCE_MS,
                "the snapshot must carry the current position, was " + snapshot.time());
        source.release();
    }

    @Test
    @DisplayName("repeat loop wrap bumps the revision for proxy re-broadcast")
    void testLoopWrapBumpsRevision() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(120L);
        player.repeat(true);
        player.start();
        final int r = player.revision();

        Thread.sleep(400L); // SEVERAL LOOP PERIODS
        assertTrue(player.revision() > r, "loop wraps must bump the revision");
        assertEquals(Status.PLAYING, player.status());
        player.release();
    }
}
