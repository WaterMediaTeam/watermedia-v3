package org.watermedia.test.media.players;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.MediaPlayer.LodLevel;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.ServerMediaPlayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the pure-Java control surface {@link MediaPlayer} implements for every subclass:
 * volume clamp/round-trip, mute orthogonality, speed bounds and the {@link MediaPlayer#canSpeed()}
 * gate, repeat, maxSize/lod validation and {@link Status#of(int)}. {@link ServerMediaPlayer} is the
 * headless concrete instance (the class is sealed, so no test-only stub is possible).
 */
@DisplayName("MediaPlayer base surface")
public class MediaPlayerBaseTest {

    @Nested
    @DisplayName("volume")
    class VolumeTests {

        @Test
        @DisplayName("volume round-trips for every 0..100 value")
        void volumeRoundTrip() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            // Math.round RECOVERS THE INPUT — THE OLD int CAST TRUNCATED 53 -> 52 (0.53f*100 = 52.9999f)
            for (int v = 0; v <= 100; v++) {
                player.volume(v);
                assertEquals(v, player.volume(), "round-trip failed at " + v);
            }
        }

        @Test
        @DisplayName("volume clamps out-of-range values")
        void volumeClamps() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.volume(150);
            assertEquals(100, player.volume());
            player.volume(-10);
            assertEquals(0, player.volume());
        }
    }

    @Nested
    @DisplayName("mute")
    class MuteTests {

        @Test
        @DisplayName("mute is independent from volume")
        void muteIsOrthogonal() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.volume(50);
            player.mute(true);
            assertTrue(player.mute());

            // CHANGING VOLUME MUST NOT CLEAR THE MUTE FLAG
            player.volume(80);
            assertTrue(player.mute());
            assertEquals(80, player.volume());

            player.mute(false);
            assertFalse(player.mute());
        }

        @Test
        @DisplayName("volume 0 does not implicitly mute")
        void volumeZeroDoesNotMute() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.volume(0);
            assertFalse(player.mute());
        }
    }

    @Nested
    @DisplayName("speed")
    class SpeedTests {

        @Test
        @DisplayName("live source cannot change speed")
        void liveSourceLocksSpeed() {
            // NO SYNCED DURATION -> liveSource() -> canSpeed() FALSE
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertFalse(player.canSpeed());
            assertFalse(player.speed(2.0f));
            // RE-REQUESTING THE CURRENT SPEED IS A SUCCESSFUL NO-OP EVEN WHEN LOCKED
            assertTrue(player.speed(1.0f));
            assertEquals(1.0f, player.speed());
        }

        @Test
        @DisplayName("bounded speed changes apply on a seekable source")
        void speedBounds() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.syncDuration(10_000); // NON-LIVE -> canSpeed() TRUE
            assertTrue(player.canSpeed());

            assertTrue(player.speed(2.0f));
            assertEquals(2.0f, player.speed());
            assertTrue(player.speed(4.0f));

            // OUT OF RANGE — REJECTED, SPEED UNCHANGED
            assertFalse(player.speed(0f));
            assertFalse(player.speed(-1f));
            assertFalse(player.speed(4.1f));
            assertEquals(4.0f, player.speed());
        }
    }

    @Nested
    @DisplayName("repeat / maxSize / lod")
    class StateTests {

        @Test
        @DisplayName("repeat toggles and reports the new state")
        void repeatToggles() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertFalse(player.repeat());
            assertTrue(player.repeat(true));
            assertTrue(player.repeat());
        }

        @Test
        @DisplayName("maxSize stores the cap and rejects negatives")
        void maxSizeValidation() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.maxSize(160, 90);
            assertEquals(160, player.maxWidth());
            assertEquals(90, player.maxHeight());

            // 0 MEANS UNLIMITED (NO_SIZE)
            player.maxSize(0, 0);
            assertEquals(MediaPlayer.NO_SIZE, player.maxWidth());
            assertThrows(IllegalArgumentException.class, () -> player.maxSize(-1, 10));
        }

        @Test
        @DisplayName("lod defaults to MAX and rejects null")
        void lodValidation() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertEquals(LodLevel.MAX, player.lod());
            player.lod(LodLevel.NEAR);
            assertEquals(LodLevel.NEAR, player.lod());
            assertThrows(IllegalArgumentException.class, () -> player.lod(null));
        }

        @Test
        @DisplayName("LodLevel keeps its documented percentages")
        void lodPercent() {
            assertEquals(100, LodLevel.MAX.percent());
            assertEquals(50, LodLevel.NEAR.percent());
            assertEquals(10, LodLevel.FAR_AWAY.percent());
        }
    }

    @Nested
    @DisplayName("Status.of")
    class StatusTests {

        @Test
        @DisplayName("of maps every ordinal, including ERROR (7)")
        void ofCoversFullRange() {
            for (final Status s: Status.values()) {
                assertEquals(s, Status.of(s.ordinal()));
            }
            assertEquals(Status.WAITING, Status.of(0));
            assertEquals(Status.ERROR, Status.of(7));
        }
    }
}
