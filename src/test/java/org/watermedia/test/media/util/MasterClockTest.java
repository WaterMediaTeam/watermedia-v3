package org.watermedia.test.media.util;

import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.util.MasterClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MasterClock}: covers the state machine, seek lifecycle,
 * serial invalidation, and the speed/time linearity invariants.
 */
public class MasterClockTest {

    @Test
    public void initialStateIsWaitingZero() {
        final MasterClock clock = new MasterClock();
        assertEquals(Status.WAITING, clock.status());
        assertEquals(0.0, clock.time(), 0.0);
        assertEquals(0, clock.serial());
    }

    @Test
    public void startUnpausedTransitionsToPlaying() {
        final MasterClock clock = new MasterClock();
        clock.start(false);
        assertEquals(Status.PLAYING, clock.status());
    }

    @Test
    public void startPausedTransitionsToPaused() {
        final MasterClock clock = new MasterClock();
        clock.start(true);
        assertEquals(Status.PAUSED, clock.status());
    }

    @Test
    public void transitionFromPlayingToPausedFreezesPts() throws InterruptedException {
        final MasterClock clock = new MasterClock();
        clock.start(false);

        // ADVANCE WALLCLOCK SO PLAYING TIME DIVERGES FROM ZERO.
        Thread.sleep(20);
        assertTrue(clock.transition(Status.PAUSED));
        assertEquals(Status.PAUSED, clock.status());

        // CLOCK MUST NOT MOVE WHILE PAUSED — TWO READS SEPARATED BY A SLEEP RETURN THE SAME VALUE.
        final double frozen = clock.time();
        Thread.sleep(20);
        assertEquals(frozen, clock.time(), 1.0e-9);
    }

    @Test
    public void illegalTransitionIsRejected() {
        final MasterClock clock = new MasterClock();
        clock.start(false);

        // PLAYING -> LOADING IS NOT IN THE STATE MACHINE.
        assertFalse(clock.transition(Status.LOADING));
        assertEquals(Status.PLAYING, clock.status());
    }

    @Test
    public void requestSeekEnqueuesAndBufferingAndBumpsSerial() {
        final MasterClock clock = new MasterClock();
        clock.start(false);
        final int beforeSerial = clock.serial();

        assertTrue(clock.requestSeek(2000L, true));
        assertEquals(Status.BUFFERING, clock.status());
        assertTrue(clock.serial() > beforeSerial);

        final MasterClock.SeekRequest req = clock.consumeSeek();
        assertNotNull(req);
        assertEquals(2000L, req.targetMs());
        assertTrue(req.precise());

        // CONSUME IS DESTRUCTIVE — A SECOND CALL RETURNS NULL.
        assertNull(clock.consumeSeek());
    }

    @Test
    public void updateWithWrongSerialIsSilentlyRejected() {
        final MasterClock clock = new MasterClock();
        clock.start(false);
        // FORCE SERIAL FORWARD SO 0 IS STALE.
        clock.requestSeek(0L, false);
        final int currentSerial = clock.serial();
        final long before = clock.timeMs();

        // STALE SERIAL — UPDATE MUST BE IGNORED.
        clock.update(5.0, currentSerial - 1);

        // STATUS STILL BUFFERING (NO AUTO-PROMOTE TO PLAYING).
        assertEquals(Status.BUFFERING, clock.status());
        // TIME UNCHANGED — STILL FROZEN AT THE SEEK TARGET (0 MS).
        assertEquals(before, clock.timeMs());
    }

    @Test
    public void nextSerialIsMonotonic() {
        final MasterClock clock = new MasterClock();
        final int a = clock.nextSerial();
        final int b = clock.nextSerial();
        final int c = clock.nextSerial();
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    public void speedChangePreservesTimeLinearity() throws InterruptedException {
        final MasterClock clock = new MasterClock();
        clock.start(false);
        Thread.sleep(20);

        final double beforeChange = clock.time();
        clock.speed(2.0);
        final double afterChange = clock.time();

        // SPEED RECALIBRATES BUT MUST NOT INTRODUCE A DISCONTINUITY.
        assertEquals(beforeChange, afterChange, 0.010);
        assertEquals(2.0, clock.speed(), 0.0);

        // TIME KEEPS MOVING FORWARD AFTER THE SPEED CHANGE.
        Thread.sleep(20);
        assertTrue(clock.time() > afterChange);
    }
}
