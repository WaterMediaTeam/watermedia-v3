package org.watermedia.test.media.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.api.media.players.sync.Sync;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a follower tracks its session between snapshots. Snapshots arrive seconds apart, so the
 * value is entirely in what happens <em>in between</em>: the last one is aged into a live
 * position, folded into the media timeline, and never replaced by an older one.
 */
@DisplayName("Follower clock")
public class FollowerClockTest {

    private ServerMediaPlayer follower;

    @AfterEach
    void tearDown() {
        if (this.follower != null) this.follower.release();
    }

    // A FOLLOWER WHOSE BRIDGE GOES NOWHERE: THE TEST FEEDS IT SNAPSHOTS BY HAND
    private ServerMediaPlayer follower() {
        this.follower = ServerMediaPlayer.follower(ignored -> {});
        return this.follower;
    }

    private static Sync sync(final int revision, final Status status, final long time, final long duration,
                             final boolean repeat, final float speed) {
        return new Sync(revision, status, time, duration, speed, 100, false, repeat, false);
    }

    @Test
    @DisplayName("ages the last snapshot into a live position at the session rate")
    void testAgesSnapshot() throws InterruptedException {
        final ServerMediaPlayer follower = this.follower();
        assertNull(follower.authority(), "nothing heard from the session yet");
        assertEquals(0L, follower.authorityTime());

        final long before = System.nanoTime();
        follower.sync(sync(7, Status.PLAYING, 30_000L, 60_000L, false, 2f));
        final long received = System.nanoTime();
        assertEquals(7, follower.authority().revision());

        Thread.sleep(100L);
        final long reading = System.nanoTime();
        final long t = follower.authorityTime();
        final long after = System.nanoTime();
        final long minimum = 30_000L + 2 * TimeUnit.NANOSECONDS.toMillis(reading - received);
        final long maximum = 30_000L + 2 * TimeUnit.NANOSECONDS.toMillis(after - before);
        assertTrue(t >= minimum && t <= maximum,
                "the session at 2x must be within [" + minimum + ", " + maximum + "], was " + t);
    }

    @Test
    @DisplayName("a stopped session reports its frozen position")
    void testFrozenWhenNotPlaying() throws InterruptedException {
        final ServerMediaPlayer follower = this.follower();
        follower.sync(sync(1, Status.PAUSED, 5_000L, 60_000L, false, 1f));
        assertEquals(5_000L, follower.authorityTime());
        Thread.sleep(100L);
        assertEquals(5_000L, follower.authorityTime(), "a paused session must not advance");
    }

    @Test
    @DisplayName("a live session drives the mirror's own clock")
    void testLiveSessionRunsMirrorClock() throws InterruptedException {
        final ServerMediaPlayer follower = this.follower();
        // A LIVE SESSION HAS NO DURATION — THE MIRROR MUST ADOPT THE LIVE FLAG OR THE
        // TIMELINE-HELD GUARD FREEZES ITS OWN CLOCK AT ZERO FOREVER
        follower.sync(new Sync(1, Status.PLAYING, 0L, 0L, 1f, 100, false, false, true));
        Thread.sleep(400L);
        assertTrue(follower.playing(), "the mirror must start with the session");
        assertTrue(follower.liveSource(), "the mirror must know the session is live");
        assertTrue(follower.time() >= 250L, "a live mirror clock must advance, was " + follower.time());
    }

    @Test
    @DisplayName("a live session's position is never folded into a latched duration")
    void testLiveSessionAgesUnfolded() throws InterruptedException {
        final ServerMediaPlayer follower = this.follower();
        follower.sync(new Sync(1, Status.PLAYING, 100L, 150L, 1f, 100, false, false, true));
        Thread.sleep(300L);
        final long t = follower.authorityTime();
        assertTrue(t > 150L, "a live session must age past the phantom duration, was " + t);
    }

    @Test
    @DisplayName("a looping timeline wraps instead of overrunning the media")
    void testWrapsOnRepeat() throws InterruptedException {
        final ServerMediaPlayer follower = this.follower();
        follower.sync(sync(1, Status.PLAYING, 400L, 500L, true, 1f));
        Thread.sleep(250L);
        final long t = follower.authorityTime();
        assertTrue(t < 500L, "a looping session must fold into the media length, was " + t);
    }

    @Test
    @DisplayName("a finite timeline clamps at the end")
    void testClampsWithoutRepeat() throws InterruptedException {
        final ServerMediaPlayer follower = this.follower();
        follower.sync(sync(1, Status.PLAYING, 400L, 500L, false, 1f));
        Thread.sleep(250L);
        assertEquals(500L, follower.authorityTime(), "playback cannot run past the end of the media");
    }

    @Test
    @DisplayName("an ended player is not replayed by a session already past its media")
    void testEndedHoldsAgainstDivergentSession() throws InterruptedException {
        final ServerMediaPlayer follower = this.follower();
        // THE FOLLOWER'S OWN MEDIA IS 500ms; THE SESSION LATCHED A DIVERGENT 60s TIMELINE AND
        // SITS AT 5s — RESTARTING WOULD REPLAY THE MEDIA FOREVER UNTIL THE SESSION CATCHES UP
        follower.syncDuration(500L);
        final long deadline = System.currentTimeMillis() + 3000L;
        boolean ended = false;
        long restarts = 0, last = 0;
        while (System.currentTimeMillis() < deadline) {
            follower.sync(sync(1, Status.PLAYING, 5_000L, 60_000L, false, 1f));
            Thread.sleep(50L);
            final long t = follower.time();
            if (t < last) restarts++;
            last = t;
            if (follower.ended()) { ended = true; break; }
        }
        assertTrue(ended, "the follower must reach its own end");
        assertEquals(0, restarts, "no restarts while ending");
        Thread.sleep(300L); // SEVERAL TICKS OF FRESH SNAPSHOTS MUST NOT REVIVE IT
        follower.sync(sync(1, Status.PLAYING, 8_000L, 60_000L, false, 1f));
        Thread.sleep(300L);
        assertEquals(Status.ENDED, follower.status(), "a session past our media must not replay it");

        // A REWIND BELOW OUR MEDIA'S END IS A REAL REPLAY AND MUST GO THROUGH
        follower.sync(sync(2, Status.PLAYING, 100L, 60_000L, false, 1f));
        final long replayDeadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < replayDeadline && !follower.playing()) Thread.sleep(25L);
        assertTrue(follower.playing(), "a rewound session must replay the ended media");
    }

    @Test
    @DisplayName("only the newest snapshot survives — older ones are dropped, heartbeats land")
    void testOnlyNewestSurvives() {
        final ServerMediaPlayer follower = this.follower();
        follower.sync(sync(5, Status.PAUSED, 1_000L, 60_000L, false, 1f));
        // OUT-OF-ORDER TRANSPORT: AN OLDER REVISION WOULD REWIND THE WHOLE AUDIENCE
        follower.sync(sync(4, Status.PLAYING, 500L, 60_000L, false, 1f));
        assertEquals(5, follower.authority().revision());
        assertEquals(Status.PAUSED, follower.authority().status());
        assertEquals(1_000L, follower.authorityTime());
        // HEARTBEAT: THE SAME REVISION CARRIES A FRESHER TIMESTAMP AND MUST LAND
        follower.sync(sync(5, Status.PAUSED, 2_000L, 60_000L, false, 1f));
        assertEquals(2_000L, follower.authorityTime());
    }
}
