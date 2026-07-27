package org.watermedia.test.media.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.api.media.players.sync.Sync;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

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

    // WALLCLOCK ADVANCE BETWEEN CALLS IS NOT EXACT — TIMING IS TOLERANCE-ASSERTED
    private static final long SLACK_MS = 60L;

    private final List<ByteBuffer> sent = new CopyOnWriteArrayList<>();
    private ServerMediaPlayer follower;

    @AfterEach
    void tearDown() {
        if (this.follower != null) this.follower.release();
    }

    // A FOLLOWER WHOSE BRIDGE GOES NOWHERE: THE TEST FEEDS IT SNAPSHOTS BY HAND
    private ServerMediaPlayer follower() {
        this.follower = ServerMediaPlayer.follower(this.sent::add);
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

        follower.sync(sync(7, Status.PLAYING, 30_000L, 60_000L, false, 2f));
        assertEquals(7, follower.authority().revision());

        Thread.sleep(100L);
        final long t = follower.authorityTime();
        assertTrue(t >= 30_000L + 100L && t <= 30_000L + 200L + 2 * SLACK_MS,
                "a running session must be extrapolated at 2x, was " + t);
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
