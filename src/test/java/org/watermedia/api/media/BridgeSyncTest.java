package org.watermedia.api.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.Config.Capability;
import org.watermedia.api.media.MediaPlayer.Status;
import org.watermedia.test.support.PlayerWait;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bridge sync protocol end to end over a real byte transport: a
 * {@link ServerMediaPlayer} authority and its followers exchange encoded packets through
 * in-memory carriers, exactly as a dev's packet channel or socket would deliver them.
 * Covers follower registration, lockstep gating, late joiners, control requests and the
 * follower clock mirror.
 */
@DisplayName("Bridge sync")
public class BridgeSyncTest {

    private static final long AWAIT_MS = 4000L;    // THE AUTHORITY BROADCASTS ON A 50ms TICK
    private static final long TOLERANCE_MS = 200L; // TIGHTER THAN THE 1s DEFAULT SO ASSERTIONS STAY STRICT
    private static final long ALIGN_MS = 350L;     // TOLERANCE PLUS TRANSPORT SLACK

    private Hub hub;

    @AfterEach
    void tearDown() {
        if (this.hub != null) this.hub.close();
    }

    @Nested
    @DisplayName("Handshake")
    class Handshake {

        @Test
        @DisplayName("a follower registers with a hello and receives config plus a snapshot")
        void testWatchRegisters() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority(Capability.LOCKSTEP);
            server.syncDuration(60_000L);
            hub.send(new Watch(4242L));

            await("the follower to register", () -> server.followers() == 1);
            await("a config answering the hello", () -> hub.received.stream().anyMatch(p ->
                    p instanceof final Config c && c.watcherId() == 4242L && c.has(Capability.LOCKSTEP)
                            && !c.has(Capability.CONTROLS)));
            await("a snapshot carrying the session", () -> hub.received.stream().anyMatch(p ->
                    p instanceof final Sync s && s.duration() == 60_000L));
        }
    }

    @Nested
    @DisplayName("Lockstep")
    class Lockstep {

        @Test
        @DisplayName("lockstep holds the pool until every follower is ready")
        void testLockstepGatesStart() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority(Capability.LOCKSTEP);
            server.syncDuration(60_000L);

            // 3 FOLLOWERS ANNOUNCE THEMSELVES BEFORE PLAYBACK, ALL STILL LOADING
            final long[] ids = { 11L, 22L, 33L };
            for (final long id: ids) {
                hub.send(new Watch(id));
                hub.send(new Report(id, Status.LOADING, 60_000L, false));
            }
            await("all three followers", () -> server.followers() == 3);

            final long opened = System.nanoTime();
            server.start();
            // THE SESSION IS PLAYING UNDERNEATH, BUT PRESENTED AS BUFFERING WITH A FROZEN CLOCK
            await("the gate to engage", () -> server.status() == Status.BUFFERING);
            final long frozen = server.time();
            assertTrue(frozen <= elapsedMs(opened), "the gate must engage at the very start, froze at " + frozen);
            settle();
            assertEquals(frozen, server.time(), "a gated clock must not advance");

            // TWO READY, ONE STILL LOADING: THE SHOW WAITS
            hub.send(new Report(ids[0], Status.PLAYING, 60_000L, false));
            hub.send(new Report(ids[1], Status.PLAYING, 60_000L, false));
            settle();
            assertEquals(Status.BUFFERING, server.status(), "one loading follower still holds the gate");

            final long lifting = System.nanoTime();
            hub.send(new Report(ids[2], Status.PLAYING, 60_000L, false));
            await("the gate to lift", () -> server.status() == Status.PLAYING);
            await("the clock to resume", () -> server.time() > frozen);
            assertTrue(server.time() - frozen <= elapsedMs(lifting), "playback must resume where it froze, was " + server.time());
        }

        @Test
        @DisplayName("a rebuffering follower freezes everyone and resumes in place")
        void testBufferingGatesThePool() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority(Capability.LOCKSTEP);
            server.syncDuration(60_000L);
            hub.send(new Watch(1L));
            hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
            await("the follower to be pooled", () -> server.followers() == 1);

            server.start();
            await("playback to run", () -> server.status() == Status.PLAYING && server.time() > 100L);

            hub.send(new Report(1L, Status.BUFFERING, 60_000L, false));
            await("the gate to engage", () -> server.status() == Status.BUFFERING);
            settle();
            final long frozen = server.time();
            settle();
            assertEquals(frozen, server.time(), "the pool clock must hold while one client rebuffers");

            final long lifting = System.nanoTime();
            hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
            await("the gate to lift", () -> server.status() == Status.PLAYING);
            // NO JUMP: PLAYBACK CONTINUES FROM THE FROZEN POSITION, NOT FROM WALL TIME
            await("the clock to move again", () -> server.time() > frozen);
            assertTrue(server.time() - frozen <= elapsedMs(lifting), "resume must not skip ahead, was " + server.time());
        }

        @Test
        @DisplayName("a late joiner never interrupts the session and joins the pool once ready")
        void testLateJoinerDoesNotInterrupt() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority(Capability.LOCKSTEP);
            server.syncDuration(60_000L);
            hub.send(new Watch(1L));
            hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
            server.start();
            await("playback to run", () -> server.status() == Status.PLAYING && server.time() > 100L);

            // SOMEONE LOADS THE CHUNK MID-PLAYBACK: STILL LOADING, BUT THE SHOW GOES ON
            hub.send(new Watch(2L));
            hub.send(new Report(2L, Status.LOADING, 60_000L, false));
            await("the joiner to register", () -> server.followers() == 2);
            settle();
            assertEquals(Status.PLAYING, server.status(), "a late joiner must not gate the pool");

            // ONCE IT REPORTS READY IT BECOMES A FULL MEMBER AND CAN GATE FROM THEN ON; THE CARRIER IS FIFO
            hub.send(new Report(2L, Status.PLAYING, 60_000L, false));
            hub.send(new Report(2L, Status.BUFFERING, 60_000L, false));
            await("the now-pooled joiner to gate", () -> server.status() == Status.BUFFERING);
        }

        @Test
        @DisplayName("failed and vanished followers never hold the gate")
        void testFailedAndStaleFollowersIgnored() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority(Capability.LOCKSTEP);
            server.followerTimeout(1_000L); // A SHORT SILENCE TOLERANCE KEEPS THE TTL TEST FAST
            server.syncDuration(60_000L);

            hub.send(new Watch(1L));
            hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
            hub.send(new Watch(2L));
            hub.send(new Report(2L, Status.ERROR, 0L, false));
            await("both followers", () -> server.followers() == 2);

            // A BROKEN CLIENT IS IGNORED INSTEAD OF FREEZING THE SHOW
            server.start();
            settle();
            assertEquals(Status.PLAYING, server.status(), "an errored follower must not gate");

            // A POOLED CLIENT THAT REBUFFERS AND THEN VANISHES WITHOUT SAYING GOODBYE MUST NOT
            // FREEZE THE POOL FOREVER: THE TTL SWEEP DROPS IT AND THE GATE LIFTS
            hub.send(new Report(1L, Status.BUFFERING, 60_000L, false));
            await("the vanishing follower to gate", () -> server.status() == Status.BUFFERING);
            await("the TTL sweep to release the gate", () -> server.status() == Status.PLAYING);
            assertEquals(0, server.followers(), "silent followers must be swept");
        }
    }

    @Nested
    @DisplayName("Mirror")
    class Mirror {

        @Test
        @DisplayName("followers mirror start, pause and stop through the bridge")
        void testFollowersMirrorState() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority();
            server.syncDuration(60_000L);
            final ServerMediaPlayer a = hub.follower();
            final ServerMediaPlayer b = hub.follower();
            await("both followers to register", () -> server.followers() == 2);

            server.start();
            await("both to play", () -> a.playing() && b.playing());
            await("their clocks to run", () -> a.time() > 100L && b.time() > 100L);

            server.pause();
            await("both to pause", () -> a.paused() && b.paused());
            await("the paused followers to align", () -> Math.abs(a.time() - server.time()) <= ALIGN_MS);
            final long frozen = a.time();
            settle();
            assertEquals(frozen, a.time(), "a paused follower clock must not advance");

            server.stop();
            await("both to stop", () -> a.stopped() && b.stopped());
        }

        @Test
        @DisplayName("a follower joining mid-playback catches up to the authority clock")
        void testFollowerCatchesUp() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority();
            server.syncDuration(300_000L);
            server.start();
            server.seek(120_000L);

            final ServerMediaPlayer late = hub.follower();
            await("the late follower to play", late::playing);
            await("it to catch up with a hard seek", () -> Math.abs(late.time() - server.time()) <= ALIGN_MS);
        }
    }

    @Nested
    @DisplayName("Controls")
    class Controls {

        @Test
        @DisplayName("control requests drive the whole pool when granted")
        void testControlRequestsAreSymmetric() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority(Capability.CONTROLS);
            server.syncDuration(60_000L);
            final ServerMediaPlayer a = hub.follower();
            final ServerMediaPlayer b = hub.follower();
            await("the handshake to grant controls", () -> a.granted(Capability.CONTROLS)
                    && b.granted(Capability.CONTROLS) && server.followers() == 2);

            // A CLIENT ASKS FOR PLAYBACK: THE AUTHORITY DECIDES AND EVERYONE FOLLOWS
            a.start();
            await("the authority to start", server::playing);
            await("both followers to play", () -> a.playing() && b.playing());

            a.pause();
            await("the authority to pause", server::paused);
            await("both followers to pause", () -> a.paused() && b.paused());

            b.seek(30_000L);
            await("the authority to seek", () -> Math.abs(server.time() - 30_000L) <= ALIGN_MS);
            await("both followers to land there", () -> Math.abs(a.time() - 30_000L) <= ALIGN_MS
                    && Math.abs(b.time() - 30_000L) <= ALIGN_MS);
        }

        @Test
        @DisplayName("without the controls capability a follower cannot drive anything")
        void testControlLockout() {
            final Hub hub = BridgeSyncTest.this.hub();
            final ServerMediaPlayer server = hub.authority();
            server.syncDuration(60_000L);
            final ServerMediaPlayer follower = hub.follower();
            await("the follower to register", () -> server.followers() == 1);

            server.start();
            await("the follower to play", follower::playing);
            assertFalse(follower.granted(Capability.CONTROLS), "controls were never granted");

            // LOCAL CONTROL IS A DEAD DROP: NEITHER THE AUTHORITY NOR THE FOLLOWER MOVES
            assertFalse(follower.pause(), "a locked follower must reject the control");
            settle();
            assertTrue(server.playing(), "the authority must be untouched");
            assertTrue(follower.playing(), "the follower must keep following");
        }

        @Test
        @DisplayName("volume follows the authority only under the volume capability")
        void testVolumeCapability() {
            final Hub shared = BridgeSyncTest.this.hub();
            final ServerMediaPlayer authority = shared.authority(Capability.VOLUME);
            final ServerMediaPlayer follower = shared.follower();
            await("the handshake to grant volume", () -> follower.granted(Capability.VOLUME));
            follower.volume(10);

            authority.volume(70);
            authority.mute(true);
            await("volume to mirror", () -> follower.volume() == 70 && follower.mute());
            shared.close();

            // WITHOUT THE CAPABILITY IT STAYS CLIENT-LOCAL
            final Hub local = BridgeSyncTest.this.hub();
            final ServerMediaPlayer plain = local.authority();
            final ServerMediaPlayer localFollower = local.follower();
            await("the follower to register", () -> plain.followers() == 1);
            localFollower.volume(10);
            plain.volume(70);
            settle();
            assertEquals(10, localFollower.volume(), "volume must stay client-local without the capability");
        }
    }

    @Nested
    @DisplayName("Release")
    class Release {

        @Test
        @DisplayName("release emits its goodbye after an in-flight report without blocking the carrier")
        void testReleaseDuringReport() throws InterruptedException {
            final ServerMediaPlayer authority = new ServerMediaPlayer(ignored -> {});
            final CountDownLatch reporting = new CountDownLatch(1);
            final CountDownLatch resume = new CountDownLatch(1);
            final CountDownLatch unwatched = new CountDownLatch(1);
            final List<Packet> delivered = new CopyOnWriteArrayList<>();
            final ServerMediaPlayer follower = MediaAPI.follower(payload -> {
                final Packet packet = Packet.read(payload);
                if (packet instanceof Report) {
                    reporting.countDown();
                    try {
                        if (!resume.await(AWAIT_MS, TimeUnit.MILLISECONDS)) return;
                    } catch (final InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                delivered.add(packet);
                authority.sync(packet);
                if (packet instanceof Unwatch) unwatched.countDown();
            });
            try {
                assertEquals(1, authority.followers());
                follower.sync(authority.snapshot());
                assertTrue(reporting.await(AWAIT_MS, TimeUnit.MILLISECONDS), "the real sync ticker must enter the carrier");

                follower.release();
                follower.release();
                assertEquals(1L, unwatched.getCount(), "goodbye must wait for the admitted report");
                resume.countDown();

                assertTrue(unwatched.await(AWAIT_MS, TimeUnit.MILLISECONDS), "the last send must finish deregistration");
                assertEquals(0, authority.followers());
                assertEquals(List.of(Watch.class, Report.class, Unwatch.class),
                        delivered.stream().map(Object::getClass).toList());
                authority.start();
                follower.sync(authority.snapshot());
                assertFalse(follower.start(), "released players cannot resume sending or ticking");
                settle();
                assertEquals(0, authority.followers(), "late snapshots must not register a released follower again");
                assertEquals(3, delivered.size());
            } finally {
                resume.countDown();
                follower.release();
                authority.release();
            }
        }
    }

    // UTILITY

    private Hub hub() {
        this.hub = new Hub();
        return this.hub;
    }

    // BLOCKS UNTIL THE CONDITION HOLDS OR THE DEADLINE PASSES, THEN ASSERTS IT
    private static void await(final String what, final BooleanSupplier condition) {
        assertTrue(PlayerWait.awaitCondition(condition, AWAIT_MS), "timed out waiting for: " + what);
    }

    // SLEEPS THROUGH SEVERAL AUTHORITY TICKS SO A "NOTHING HAPPENED" ASSERTION IS MEANINGFUL
    private static void settle() {
        try { Thread.sleep(300L); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static long elapsedMs(final long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    // IN-MEMORY TRANSPORT. DELIVERY IS ASYNCHRONOUS ON A SINGLE "NETWORK" THREAD, LIKE A REAL
    // CHANNEL: NOTHING IS APPLIED ON THE CALLER'S STACK, AND EVERY PACKET TRAVELS AS BYTES.
    private static final class Hub {
        private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "BridgeSyncTest-net");
            t.setDaemon(true);
            return t;
        });
        private final List<ServerMediaPlayer> followers = new CopyOnWriteArrayList<>();
        private final List<Packet> received = new CopyOnWriteArrayList<>();
        private ServerMediaPlayer server;

        ServerMediaPlayer authority(final Capability... capabilities) {
            this.server = new ServerMediaPlayer(buf -> {
                final ByteBuffer frame = copy(buf);
                this.received.add(Packet.read(frame.duplicate()));
                this.net.execute(() -> {
                    synchronized (this) {
                        for (final ServerMediaPlayer follower: this.followers) follower.sync(frame.duplicate());
                    }
                });
            }, capabilities);
            return this.server;
        }

        // A HEADLESS FOLLOWER JOINING THE SESSION. THE LOCK CLOSES THE WINDOW WHERE THE AUTHORITY
        // COULD ANSWER THE HELLO BEFORE THE NEW FOLLOWER IS REACHABLE.
        ServerMediaPlayer follower() {
            synchronized (this) {
                final ServerMediaPlayer follower = MediaAPI.follower(this.upstream());
                follower.tolerance(TOLERANCE_MS);
                this.followers.add(follower);
                return follower;
            }
        }

        // RAW UPSTREAM CARRIER, ALSO USED TO INJECT SYNTHETIC FOLLOWERS WITHOUT A REAL PLAYER
        Bridge upstream() {
            return buf -> {
                final ByteBuffer frame = copy(buf);
                this.net.execute(() -> this.server.sync(frame));
            };
        }

        void send(final Packet packet) {
            this.upstream().send(packet.toBytes());
        }

        private static ByteBuffer copy(final ByteBuffer buf) {
            final byte[] bytes = new byte[buf.remaining()];
            buf.duplicate().get(bytes);
            return ByteBuffer.wrap(bytes);
        }

        void close() {
            for (final ServerMediaPlayer follower: this.followers) follower.release();
            this.followers.clear();
            if (this.server != null) this.server.release();
            this.net.shutdownNow();
        }
    }
}
