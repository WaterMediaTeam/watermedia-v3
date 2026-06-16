package org.watermedia.test.media.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("MasterClock")
public class MasterClockTest {

    // WALLCLOCK ADVANCE USED TO LET PLAYING TIME DIVERGE FROM ZERO BEFORE A READ
    private static final long ADVANCE_MS = 20L;

    // ==========================================================================
    // STATE MACHINE TESTS
    // ==========================================================================

    @Nested
    @DisplayName("State Machine")
    class StateMachineTests {

        @Test
        @DisplayName("Initial state is WAITING at time zero")
        void testInitialStateIsWaitingZero() {
            final MasterClock clock = new MasterClock();
            assertEquals(Status.WAITING, clock.status());
            assertEquals(0.0, clock.time(), 0.0);
            assertEquals(0, clock.serial());
        }

        @Test
        @DisplayName("start(false) transitions to PLAYING")
        void testStartUnpausedTransitionsToPlaying() {
            final MasterClock clock = new MasterClock();
            clock.start(false);
            assertEquals(Status.PLAYING, clock.status());
        }

        @Test
        @DisplayName("start(true) transitions to PAUSED")
        void testStartPausedTransitionsToPaused() {
            final MasterClock clock = new MasterClock();
            clock.start(true);
            assertEquals(Status.PAUSED, clock.status());
        }

        @Test
        @DisplayName("PLAYING to PAUSED freezes the PTS")
        void testTransitionFromPlayingToPausedFreezesPts() throws InterruptedException {
            final MasterClock clock = new MasterClock();
            clock.start(false);

            // ADVANCE WALLCLOCK SO PLAYING TIME DIVERGES FROM ZERO.
            Thread.sleep(ADVANCE_MS);
            assertTrue(clock.transition(Status.PAUSED));
            assertEquals(Status.PAUSED, clock.status());

            // CLOCK MUST NOT MOVE WHILE PAUSED — TWO READS SEPARATED BY A SLEEP RETURN THE SAME VALUE.
            final double frozen = clock.time();
            Thread.sleep(ADVANCE_MS);
            assertEquals(frozen, clock.time(), 1.0e-9);
        }

        @Test
        @DisplayName("Illegal transition is rejected")
        void testIllegalTransitionIsRejected() {
            final MasterClock clock = new MasterClock();
            clock.start(false);

            // PLAYING -> LOADING IS NOT IN THE STATE MACHINE.
            assertFalse(clock.transition(Status.LOADING));
            assertEquals(Status.PLAYING, clock.status());
        }
    }

    // ==========================================================================
    // SEEK LIFECYCLE TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Seek Lifecycle")
    class SeekTests {

        @Test
        @DisplayName("requestSeek enqueues, enters BUFFERING and bumps the serial")
        void testRequestSeekEnqueuesAndBufferingAndBumpsSerial() {
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
        @DisplayName("Update with a stale serial is silently rejected")
        void testUpdateWithWrongSerialIsSilentlyRejected() {
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
        @DisplayName("nextSerial is strictly monotonic")
        void testNextSerialIsMonotonic() {
            final MasterClock clock = new MasterClock();
            final int a = clock.nextSerial();
            final int b = clock.nextSerial();
            final int c = clock.nextSerial();
            assertTrue(b > a);
            assertTrue(c > b);
        }
    }

    // ==========================================================================
    // SPEED / TIME LINEARITY TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Speed and Time Linearity")
    class SpeedTests {

        @Test
        @DisplayName("Speed change preserves time linearity")
        void testSpeedChangePreservesTimeLinearity() throws InterruptedException {
            final MasterClock clock = new MasterClock();
            clock.start(false);
            Thread.sleep(ADVANCE_MS);

            final double beforeChange = clock.time();
            clock.speed(2.0);
            final double afterChange = clock.time();

            // SPEED RECALIBRATES BUT MUST NOT INTRODUCE A DISCONTINUITY.
            assertEquals(beforeChange, afterChange, 0.010);
            assertEquals(2.0, clock.speed(), 0.0);

            // TIME KEEPS MOVING FORWARD AFTER THE SPEED CHANGE.
            Thread.sleep(ADVANCE_MS);
            assertTrue(clock.time() > afterChange);
        }
    }
}
