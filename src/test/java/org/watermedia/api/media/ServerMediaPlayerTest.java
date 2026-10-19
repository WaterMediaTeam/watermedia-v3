package org.watermedia.api.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.test.support.PlayerWait;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ServerMediaPlayer}, the headless wall-clock sync authority. Its contract
 * (start/pause/seek/speed/repeat and the shared-ticker ENDED transition) is pure-Java and
 * deterministic; a regression here desyncs every client that trusts it.
 */
@DisplayName("ServerMediaPlayer")
public class ServerMediaPlayerTest {

    // TICK IS 50ms; GIVE THE SHARED TICKER A FEW CYCLES OF SLACK BEFORE ASSERTING A TRANSITION.
    private static final long ENDED_TIMEOUT_MS = 2000L;
    // ALLOW CLOCK RESOLUTION SLACK WHEN CHECKING MINIMUM LIVE PROGRESSION.
    private static final long TIMING_TOLERANCE_MS = 60L;

    // CAPTURES THE LAST publishStatus TRANSITION FROM THE SHARED TICKER THREAD; volatile FOR VISIBILITY.
    private static final class StatusCapture implements MediaPlayer.StatusListener {
        private volatile Status from;
        private volatile Status to;
        @Override public void onStatus(final MediaPlayer player, final Status prev, final Status next) { this.from = prev; this.to = next; }
    }

    @Test
    @DisplayName("follower timeout rejects nanosecond overflow without changing the prior value")
    void followerTimeoutRejectsOverflow() {
        final ServerMediaPlayer player = new ServerMediaPlayer(ignored -> {});
        try {
            final long maximum = Long.MAX_VALUE / 1_000_000L;
            player.followerTimeout(maximum);
            assertEquals(maximum, player.followerTimeout());
            assertThrows(IllegalArgumentException.class, () -> player.followerTimeout(maximum + 1));
            assertEquals(maximum, player.followerTimeout());
            assertThrows(IllegalArgumentException.class, () -> player.followerTimeout(Long.MAX_VALUE));
            assertEquals(maximum, player.followerTimeout());
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("start() plays and time advances with the wall clock")
    void testStartAdvancesTime() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        try {
            player.syncDuration(100_000L);
            // BRACKET BOTH CALLS SO SCHEDULER DELAYS COUNT AS ELAPSED TIME.
            final long before = System.nanoTime();
            assertTrue(player.start());
            final long started = System.nanoTime();
            assertEquals(Status.PLAYING, player.status());

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = player.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "time() must be within [" + minimum + ", " + maximum + "], was " + t);
        } finally {
            player.release();
        }
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
        try {
            player.syncDuration(100_000L);
            assertTrue(player.startPaused());
            assertTrue(player.speed(2.0f));
            final long before = System.nanoTime();
            assertTrue(player.resume());
            final long started = System.nanoTime();

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = player.time();
            final long after = System.nanoTime();
            final long minimum = 2 * TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = 2 * TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "time() at 2x must be within [" + minimum + ", " + maximum + "], was " + t);
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("Non-repeat playback transitions to ENDED and notifies the status listener")
    void testEndedTransitionAndListener() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        final StatusCapture cap = new StatusCapture();
        player.onStatus(cap);

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
        player.onStatus(cap);

        player.syncDuration(120L);
        player.repeat(true);
        player.start();

        // ACROSS SEVERAL LOOP PERIODS THE CLOCK MUST STAY PLAYING AND WRAP WITHIN [0, duration).
        Thread.sleep(500L);
        assertEquals(Status.PLAYING, player.status());
        assertFalse(player.ended());
        assertTrue(player.time() < 120L, "repeat time() must wrap under the duration, was " + player.time());
        assertNotNull(player.status());
        // NO TERMINAL TRANSITION WAS EVER PUBLISHED WHILE LOOPING — ONLY THE START ITSELF
        assertEquals(Status.PLAYING, cap.to);
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
    @DisplayName("live(true) flags the source as live and locks speed")
    void testLive() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(10_000L);
        assertFalse(player.live());
        assertTrue(player.canSpeed());

        player.live(true);
        assertTrue(player.live());
        assertFalse(player.canSpeed());

        player.live(false);
        assertFalse(player.live());
        player.release();
    }

    @Test
    @DisplayName("seek() clamps to the known duration and saturates instead of overflowing")
    void testSeekClampsAndSaturates() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        try {
            player.syncDuration(Long.MAX_VALUE);
            player.start();
            assertTrue(player.seek(-5L));
            assertEquals(0L, player.time());
            // THE RUNNING SEGMENT MUST NOT WRAP NEGATIVE NEAR THE END OF THE LONG RANGE
            assertTrue(player.seek(Long.MAX_VALUE - 10L));
            assertTrue(player.time() >= Long.MAX_VALUE - 10L);
            assertTrue(player.skipTime(100L));
            assertEquals(Long.MAX_VALUE, player.time());
            assertNotNull(player.snapshot(), "a saturated clock still snapshots");
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("seek() is refused until a client reports the duration and on a live session")
    void testSeekNeedsATimeline() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        try {
            assertTrue(player.start());
            assertFalse(player.live(), "an unknown duration is not a live session");
            assertFalse(player.canSeek());
            assertFalse(player.seek(5_000L), "no timeline to move before the duration is known");
            assertFalse(player.skipTime(1_000L));
            assertEquals(0L, player.time());

            player.syncDuration(60_000L);
            assertTrue(player.canSeek());
            assertTrue(player.seek(5_000L));

            player.live(true);
            assertFalse(player.canSeek());
            final int revision = player.revision();
            assertFalse(player.seek(10_000L), "a live session has no position to seek");
            assertFalse(player.seekQuick(10_000L));
            assertFalse(player.forward());
            assertEquals(revision, player.revision(), "a refused seek is not broadcast");
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("A clock that turns live near the end of the long range saturates instead of wrapping negative")
    void testLiveClockSaturates() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        try {
            // A LIVE TIMELINE IGNORES THE DURATION, SO NOTHING CLAMPS THE RUNNING SEGMENT BUT THE SATURATION; PAUSED
            // UNTIL IT IS LIVE, SO THE TICKER CANNOT END THE CLOCK AT THE DURATION FIRST
            player.syncDuration(Long.MAX_VALUE);
            assertTrue(player.startPaused());
            assertTrue(player.seek(Long.MAX_VALUE - 10L));
            player.live(true);
            assertTrue(player.resume());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() == Long.MAX_VALUE, ENDED_TIMEOUT_MS),
                    "the live segment outgrows the 10ms left and saturates");
            assertEquals(Status.PLAYING, player.status(), "a live clock never ends");
            final Sync snapshot = player.snapshot();
            assertEquals(Long.MAX_VALUE, snapshot.time());
            assertEquals(snapshot, Packet.read(snapshot.toBytes()), "the saturated snapshot still crosses the wire");
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("A solo clock ignores follower traffic and never enrolls anyone")
    void testSoloIgnoresUpstream() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        try {
            player.sync(new Watch(7L));
            player.sync(new Report(7L, Status.PLAYING, 60_000L, true));
            assertEquals(0, player.followers());
            assertEquals(0L, player.duration(), "a solo clock must not learn facts from strangers");
            assertTrue(player.canSpeed(), "nor latch their live flag");
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("A late report behind an Unwatch does not re-enroll the departed follower")
    void testLateReportAfterUnwatch() {
        final ServerMediaPlayer player = new ServerMediaPlayer(ignored -> {});
        try {
            player.sync(new Watch(9L));
            assertEquals(1, player.followers());
            player.sync(new Unwatch(9L));
            player.sync(new Report(9L, Status.PLAYING, 60_000L, false));
            assertEquals(0, player.followers(), "the keepalive that overtook its own Unwatch must be ignored");
            // AN EXPLICIT HELLO IS A REJOIN
            player.sync(new Watch(9L));
            assertEquals(1, player.followers());
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("Every transition reaches the listener, including the ones controls and release cause")
    void testListenerHearsEveryTransition() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        final List<Status> seen = new CopyOnWriteArrayList<>();
        player.onStatus((p, prev, next) -> seen.add(next));
        try {
            player.syncDuration(60_000L);
            player.start();
            player.pause();
            player.resume();
            player.stop();
            assertEquals(List.of(Status.PLAYING, Status.PAUSED, Status.PLAYING, Status.STOPPED), seen);
        } finally {
            player.release();
        }
        assertEquals(Status.STOPPED, seen.get(seen.size() - 1));
        assertEquals(4, seen.size(), "release from STOPPED is not a transition");
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
    void testSnapshot() throws InterruptedException {
        final ServerMediaPlayer source = new ServerMediaPlayer();
        try {
            source.syncDuration(60_000L);
            source.repeat(true);
            source.volume(40);
            source.mute(true);
            assertTrue(source.startPaused());
            assertTrue(source.speed(2.0f));
            assertTrue(source.seek(30_000L));

            final Sync snapshot = source.snapshot();
            assertEquals(source.revision(), snapshot.revision());
            assertEquals(Status.PAUSED, snapshot.status());
            assertEquals(60_000L, snapshot.duration());
            assertEquals(2.0f, snapshot.speed());
            assertEquals(40, snapshot.volume());
            assertTrue(snapshot.mute());
            assertTrue(snapshot.repeat());
            assertFalse(snapshot.live());
            assertEquals(30_000L, snapshot.time());

            // WITHOUT LOOP WRAPS, THE RUNNING SNAPSHOT MUST FALL BETWEEN THE SURROUNDING READS.
            source.repeat(false);
            assertTrue(source.resume());
            Thread.sleep(50L);
            final long before = source.time();
            final Sync playing = source.snapshot();
            final long after = source.time();
            assertEquals(Status.PLAYING, playing.status());
            assertFalse(playing.repeat());
            assertTrue(playing.time() > snapshot.time(), "a running snapshot must advance after resume()");
            assertTrue(playing.time() >= before && playing.time() <= after,
                    "the snapshot must be within [" + before + ", " + after + "], was " + playing.time());
            assertEquals(30_000L, snapshot.time(), "an earlier snapshot must remain immutable");
        } finally {
            source.release();
        }
    }

    @Test
    @DisplayName("a clock without a timeline holds at zero and starts counting when the duration latches")
    void testHeldClockWithoutTimeline() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        try {
            assertTrue(player.start());

            // AN UNKNOWN NON-LIVE TIMELINE MUST STAY FROZEN UNTIL ITS DURATION ARRIVES.
            Thread.sleep(200L);
            assertEquals(Status.PLAYING, player.status());
            assertEquals(0L, player.time(), "a clock without a timeline must hold still");

            final long before = System.nanoTime();
            player.syncDuration(100_000L);
            final long started = System.nanoTime();
            Thread.sleep(100L);
            assertEquals(Status.PLAYING, player.status());
            final long reading = System.nanoTime();
            final long t = player.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "time since the duration latch must be within [" + minimum + ", " + maximum + "], was " + t);
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("a live clock runs even without a duration")
    void testLiveClockRuns() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.start();
        player.live(true); // A LIVE TIMELINE IS OPEN-ENDED BUT REAL — SESSION TIME COUNTS UP
        Thread.sleep(120L);
        assertTrue(player.time() >= 120L - TIMING_TOLERANCE_MS,
                "a live session clock must advance, was " + player.time());
        player.release();
    }

    @Test
    @DisplayName("a live session never ends nor wraps, even with a latched duration")
    void testLiveIgnoresLatchedDuration() throws InterruptedException {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        // MIXED-VARIANT SESSION: A VOD CLIENT LATCHED A DURATION, A STREAM CLIENT LATCHED LIVE
        player.syncDuration(150L);
        player.live(true);
        player.start();

        Thread.sleep(400L);
        assertEquals(Status.PLAYING, player.status(), "a live clock must never END at the phantom duration");
        assertFalse(player.canSeek(), "a live session is not seekable, matching FFMediaPlayer");
        assertTrue(player.time() > 150L, "a live clock must run past the phantom duration, was " + player.time());
        player.release();
    }

    @Test
    @DisplayName("resume after a post-ENDED seek keeps the clock ticking to its next end")
    void testResumeAfterEndedTicksAgain() {
        final ServerMediaPlayer player = new ServerMediaPlayer();
        player.syncDuration(150L);
        player.start();
        assertTrue(PlayerWait.awaitStatus(player, ENDED_TIMEOUT_MS, Status.ENDED));

        // SCRUB BACK AND RESUME: THE SOLO CLOCK MUST RE-REGISTER ON THE TICKER OR IT NEVER ENDS AGAIN
        assertTrue(player.seek(50L));
        assertTrue(player.resume());
        assertTrue(PlayerWait.awaitStatus(player, ENDED_TIMEOUT_MS, Status.ENDED),
                "a resumed clock must reach ENDED again");
        assertEquals(150L, player.time());
        player.release();
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
