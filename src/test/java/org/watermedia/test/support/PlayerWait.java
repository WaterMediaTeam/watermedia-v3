package org.watermedia.test.support;

import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.media.players.MediaPlayer.Status;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Spin-waits that turn the player's volatile state into a synchronization
 * point for tests. Polls every 5 ms and bails after {@code timeoutMs}.
 *
 * <p>Used heavily by {@code TxMediaPlayer} tests: the lifecycle thread drives
 * status transitions on a separate executor, so tests need a tight loop to
 * observe each transition without sleeping for an arbitrary chunk of time.
 */
public final class PlayerWait {
    private static final long POLL_INTERVAL_MS = 5L;

    private PlayerWait() {}

    /** Waits until {@code player.status()} matches one of {@code anyOf}, or {@code timeoutMs} elapses. */
    public static boolean awaitStatus(final MediaPlayer player, final long timeoutMs, final Status... anyOf) {
        final Set<Status> targets = EnumSet.copyOf(java.util.Arrays.asList(anyOf));
        return awaitCondition(() -> targets.contains(player.status()), timeoutMs);
    }

    /** Waits until {@code player.canPlay()} returns true, or {@code timeoutMs} elapses. */
    public static boolean awaitLoaded(final MediaPlayer player, final long timeoutMs) {
        return awaitCondition(player::canPlay, timeoutMs);
    }

    /** Generic spin-wait helper — returns true if the condition was satisfied within the timeout. */
    public static boolean awaitCondition(final BooleanSupplier condition, final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(POLL_INTERVAL_MS); }
            catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
