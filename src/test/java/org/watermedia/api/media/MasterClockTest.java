package org.watermedia.api.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.MediaPlayer.Status;

import java.util.ArrayList;
import java.util.List;

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

        @Test
        @DisplayName("A pause while loading only latches the intent, and start() applies it")
        void testPauseWhileLoadingLatches() {
            final MasterClock clock = new MasterClock();
            assertFalse(clock.paused(true), "nothing to latch into before a run exists");
            assertFalse(clock.pauseRequested());
            clock.transition(Status.LOADING);
            assertTrue(clock.paused(true), "the first latch is a change");
            assertEquals(Status.LOADING, clock.status(), "LOADING is left only by start() or a terminal state");
            assertFalse(clock.paused(true), "re-latching the same intent changes nothing");
            assertTrue(clock.pauseRequested());
            clock.start(clock.pauseRequested());
            assertEquals(Status.PAUSED, clock.status());
        }

        @Test
        @DisplayName("Entering STOPPED rewinds the clock and a halt forces it from any state")
        void testStoppedRewindsAndHaltForces() {
            final MasterClock clock = new MasterClock();
            clock.start(false);
            clock.requestSeek(5_000L, true);
            assertTrue(clock.transition(Status.STOPPED));
            assertEquals(0L, clock.timeMs());
            assertNull(clock.halt(), "already stopped");

            final MasterClock failed = new MasterClock();
            failed.start(false);
            assertTrue(failed.transition(Status.ERROR));
            assertEquals(Status.ERROR, failed.halt());
            assertEquals(Status.STOPPED, failed.status());
            failed.start(false);
            assertEquals(Status.STOPPED, failed.status(), "start() never revives a stopped run");
        }

        @Test
        @DisplayName("A listener may re-enter the clock from a nested transition without deadlocking")
        void testListenerReentrancy() throws InterruptedException {
            final MasterClock clock = new MasterClock();
            final List<Status> seen = new ArrayList<>();
            // paused() TRANSITIONS WHILE HOLDING THE LOCK; THE LISTENER THEN SEEKS, WHICH TAKES IT AGAIN
            clock.listener((prev, next) -> {
                seen.add(next);
                if (next == Status.PAUSED) clock.requestSeek(10L, true);
            });
            clock.start(false);
            final Thread other = new Thread(() -> clock.paused(true));
            other.start();
            other.join(2_000L);
            assertFalse(other.isAlive(), "the nested transition must not deadlock");
            assertEquals(List.of(Status.PLAYING, Status.PAUSED, Status.BUFFERING), seen);
        }

        @Test
        @DisplayName("The listener hears every transition in order, outside the lock")
        void testListenerOrder() {
            final MasterClock clock = new MasterClock();
            final List<Status> seen = new ArrayList<>();
            clock.listener((prev, next) -> {
                seen.add(next);
                // RE-ENTERING THE CLOCK FROM THE LISTENER MUST NOT DEADLOCK
                assertEquals(next, clock.status());
            });
            clock.transition(Status.LOADING);
            clock.start(false);
            clock.paused(true);
            clock.requestSeek(100L, true);
            clock.updateMs(100L, clock.serial());
            clock.transition(Status.ENDED);
            clock.reset();
            assertEquals(List.of(Status.LOADING, Status.PLAYING, Status.PAUSED, Status.BUFFERING, Status.PAUSED,
                    Status.ENDED, Status.WAITING), seen);
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
        @DisplayName("A seek while loading stays LOADING and start() lands BUFFERING at the target")
        void testSeekWhileLoadingDeferredToStart() {
            final MasterClock clock = new MasterClock();
            clock.transition(Status.LOADING);
            assertTrue(clock.requestSeek(4_000L, true));
            assertEquals(Status.LOADING, clock.status());
            clock.start(true);
            assertEquals(Status.BUFFERING, clock.status());
            assertEquals(4_000L, clock.timeMs());
            assertTrue(clock.pauseRequested());
            // THE FIRST FRAME AFTER THE SEEK SETTLES THE LATCHED PAUSE
            clock.updateMs(4_000L, clock.serial());
            assertEquals(Status.PAUSED, clock.status());
        }

        @Test
        @DisplayName("Seeks are rejected where no pipeline can serve them")
        void testSeekRejectedWithoutPipeline() {
            final MasterClock clock = new MasterClock();
            assertFalse(clock.requestSeek(1L, true), "WAITING");
            clock.start(false);
            assertTrue(clock.transition(Status.ENDED));
            assertFalse(clock.requestSeek(1L, true), "ENDED has no pipeline — the player restarts instead");
            assertEquals(Status.ENDED, clock.status());
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

        @Test
        @DisplayName("reset() keeps the playback speed across runs")
        void testResetKeepsSpeed() {
            final MasterClock clock = new MasterClock();
            clock.start(false);
            clock.speed(2.0);
            clock.reset();
            assertEquals(Status.WAITING, clock.status());
            assertEquals(2.0, clock.speed(), 0.0, "speed is a player setting, not pipeline state");
        }
    }
}
