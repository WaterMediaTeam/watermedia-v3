package org.watermedia.test.media.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.media.players.MediaPlayer.Status;
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.api.media.players.sync.Bridge;
import org.watermedia.api.media.players.sync.Config;
import org.watermedia.api.media.players.sync.Config.Capability;
import org.watermedia.api.media.players.sync.Packet;
import org.watermedia.api.media.players.sync.Report;
import org.watermedia.api.media.players.sync.Sync;
import org.watermedia.api.media.players.sync.Watch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the bridge sync protocol over a real byte transport: a
 * {@link ServerMediaPlayer} authority and its followers exchange encoded packets through
 * in-memory carriers, exactly as a dev's packet channel or socket would deliver them.
 * Covers spectator registration, lockstep gating, late joiners, control requests and the
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

    // BLOCKS UNTIL THE CONDITION HOLDS OR THE DEADLINE PASSES, THEN ASSERTS IT
    private static void await(final String what, final BooleanSupplier condition) {
        final long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(10L); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    // SLEEPS THROUGH SEVERAL AUTHORITY TICKS SO A "NOTHING HAPPENED" ASSERTION IS MEANINGFUL
    private static void settle() {
        try { Thread.sleep(300L); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
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
                this.received.add(Packet.of(frame.duplicate()));
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
                final ServerMediaPlayer follower = ServerMediaPlayer.follower(this.upstream());
                follower.tolerance(TOLERANCE_MS);
                this.followers.add(follower);
                return follower;
            }
        }

        // RAW UPSTREAM CARRIER — ALSO USED TO INJECT SYNTHETIC SPECTATORS WITHOUT A REAL PLAYER
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

    @Test
    @DisplayName("a spectator registers with a hello and receives config plus a snapshot")
    void testWatchRegisters() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority(Capability.LOCKSTEP);
        server.syncDuration(60_000L);
        this.hub.send(new Watch(4242L));

        await("the watcher to register", () -> server.watchers() == 1);
        await("a config answering the hello", () -> this.hub.received.stream().anyMatch(p ->
                p instanceof final Config c && c.watcherId() == 4242L && c.has(Capability.LOCKSTEP)
                        && !c.has(Capability.CONTROLS)));
        await("a snapshot carrying the session", () -> this.hub.received.stream().anyMatch(p ->
                p instanceof final Sync s && s.duration() == 60_000L));
    }

    @Test
    @DisplayName("lockstep holds the audience until every spectator is ready")
    void testLockstepGatesStart() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority(Capability.LOCKSTEP);
        server.syncDuration(60_000L);

        // 3 SPECTATORS ANNOUNCE THEMSELVES BEFORE PLAYBACK — ALL STILL LOADING
        final long[] ids = {11L, 22L, 33L};
        for (final long id: ids) {
            this.hub.send(new Watch(id));
            this.hub.send(new Report(id, Status.LOADING, 60_000L, false));
        }
        await("all three watchers", () -> server.watchers() == 3);

        server.start();
        // THE SESSION IS PLAYING UNDERNEATH, BUT PRESENTED AS BUFFERING WITH A FROZEN CLOCK
        await("the gate to engage", () -> server.status() == Status.BUFFERING);
        final long frozen = server.time();
        assertTrue(frozen < 200L, "the gate must engage at the very start, froze at " + frozen);
        settle();
        assertEquals(frozen, server.time(), "a gated clock must not advance");

        // TWO READY, ONE STILL LOADING — THE SHOW WAITS
        this.hub.send(new Report(ids[0], Status.PLAYING, 60_000L, false));
        this.hub.send(new Report(ids[1], Status.PLAYING, 60_000L, false));
        settle();
        assertEquals(Status.BUFFERING, server.status(), "one loading spectator still holds the gate");

        this.hub.send(new Report(ids[2], Status.PLAYING, 60_000L, false));
        await("the gate to lift", () -> server.status() == Status.PLAYING);
        await("the clock to resume", () -> server.time() > frozen);
        assertTrue(server.time() < frozen + 2000L, "playback must resume where it froze, was " + server.time());
    }

    @Test
    @DisplayName("a rebuffering spectator freezes everyone and resumes in place")
    void testBufferingGatesTheAudience() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority(Capability.LOCKSTEP);
        server.syncDuration(60_000L);
        this.hub.send(new Watch(1L));
        this.hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
        await("the watcher to be pooled", () -> server.watchers() == 1);

        server.start();
        await("playback to run", () -> server.status() == Status.PLAYING && server.time() > 100L);

        this.hub.send(new Report(1L, Status.BUFFERING, 60_000L, false));
        await("the gate to engage", () -> server.status() == Status.BUFFERING);
        settle();
        final long frozen = server.time();
        settle();
        assertEquals(frozen, server.time(), "the audience clock must hold while one client rebuffers");

        this.hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
        await("the gate to lift", () -> server.status() == Status.PLAYING);
        // NO JUMP: PLAYBACK CONTINUES FROM THE FROZEN POSITION, NOT FROM WALL TIME
        await("the clock to move again", () -> server.time() > frozen);
        assertTrue(server.time() < frozen + 2000L, "resume must not skip ahead, was " + server.time());
    }

    @Test
    @DisplayName("a late joiner never interrupts the session and joins the pool once ready")
    void testLateJoinerDoesNotInterrupt() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority(Capability.LOCKSTEP);
        server.syncDuration(60_000L);
        this.hub.send(new Watch(1L));
        this.hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
        server.start();
        await("playback to run", () -> server.status() == Status.PLAYING && server.time() > 100L);

        // SOMEONE LOADS THE CHUNK MID-PLAYBACK: STILL LOADING, BUT THE SHOW GOES ON
        this.hub.send(new Watch(2L));
        this.hub.send(new Report(2L, Status.LOADING, 60_000L, false));
        await("the joiner to register", () -> server.watchers() == 2);
        settle();
        assertEquals(Status.PLAYING, server.status(), "a late joiner must not gate the audience");

        // ONCE IT REPORTS READY IT BECOMES A FULL MEMBER — AND CAN GATE FROM THEN ON
        this.hub.send(new Report(2L, Status.PLAYING, 60_000L, false));
        settle();
        this.hub.send(new Report(2L, Status.BUFFERING, 60_000L, false));
        await("the now-pooled joiner to gate", () -> server.status() == Status.BUFFERING);
    }

    @Test
    @DisplayName("failed and vanished spectators never hold the gate")
    void testFailedAndStaleWatchersIgnored() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority(Capability.LOCKSTEP);
        server.watcherTimeout(1000L); // A SHORT SILENCE TOLERANCE KEEPS THE TTL TEST FAST
        server.syncDuration(60_000L);

        this.hub.send(new Watch(1L));
        this.hub.send(new Report(1L, Status.PLAYING, 60_000L, false));
        this.hub.send(new Watch(2L));
        this.hub.send(new Report(2L, Status.ERROR, 0L, false));
        await("both watchers", () -> server.watchers() == 2);

        // A BROKEN CLIENT IS IGNORED INSTEAD OF FREEZING THE SHOW
        server.start();
        settle();
        assertEquals(Status.PLAYING, server.status(), "an errored spectator must not gate");

        // A POOLED CLIENT THAT REBUFFERS AND THEN VANISHES WITHOUT SAYING GOODBYE MUST NOT
        // FREEZE THE AUDIENCE FOREVER — THE TTL SWEEP DROPS IT AND THE GATE LIFTS
        this.hub.send(new Report(1L, Status.BUFFERING, 60_000L, false));
        await("the vanishing spectator to gate", () -> server.status() == Status.BUFFERING);
        await("the TTL sweep to release the gate", () -> server.status() == Status.PLAYING);
        assertEquals(0, server.watchers(), "silent spectators must be swept");
    }

    @Test
    @DisplayName("followers mirror start, pause and stop through the bridge")
    void testFollowersMirrorState() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority();
        server.syncDuration(60_000L);
        final ServerMediaPlayer a = this.hub.follower();
        final ServerMediaPlayer b = this.hub.follower();
        await("both followers to register", () -> server.watchers() == 2);

        server.start();
        await("both to play", () -> a.playing() && b.playing());
        await("their clocks to run", () -> a.time() > 100L && b.time() > 100L);

        server.pause();
        await("both to pause", () -> a.paused() && b.paused());
        settle(); // LET ANY ONE-SHOT ALIGNMENT LAND BEFORE SAMPLING
        final long frozen = a.time();
        settle();
        assertEquals(frozen, a.time(), "a paused follower clock must not advance");
        assertTrue(Math.abs(a.time() - server.time()) <= ALIGN_MS,
                "paused followers align to the authority, off by " + (a.time() - server.time()));

        server.stop();
        await("both to stop", () -> a.stopped() && b.stopped());
    }

    @Test
    @DisplayName("a follower joining mid-playback catches up to the authority clock")
    void testFollowerCatchesUp() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority();
        server.syncDuration(300_000L);
        server.start();
        server.seek(120_000L);

        final ServerMediaPlayer late = this.hub.follower();
        await("the late follower to play", late::playing);
        await("it to catch up with a hard seek", () -> Math.abs(late.time() - server.time()) <= ALIGN_MS);
    }

    @Test
    @DisplayName("control requests drive the whole audience when granted")
    void testControlRequestsAreSymmetric() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority(Capability.CONTROLS);
        server.syncDuration(60_000L);
        final ServerMediaPlayer a = this.hub.follower();
        final ServerMediaPlayer b = this.hub.follower();
        await("the handshake to grant controls", () -> a.granted(Capability.CONTROLS)
                && b.granted(Capability.CONTROLS) && server.watchers() == 2);

        // A CLIENT ASKS FOR PLAYBACK — THE AUTHORITY DECIDES AND EVERYONE FOLLOWS
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
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority();
        server.syncDuration(60_000L);
        final ServerMediaPlayer follower = this.hub.follower();
        await("the follower to register", () -> server.watchers() == 1);

        server.start();
        await("the follower to play", follower::playing);
        assertFalse(follower.granted(Capability.CONTROLS), "controls were never granted");

        // LOCAL CONTROL IS A DEAD DROP — NEITHER THE AUTHORITY NOR THE FOLLOWER MOVES
        assertFalse(follower.pause(), "a locked follower must reject the control");
        settle();
        assertTrue(server.playing(), "the authority must be untouched");
        assertTrue(follower.playing(), "the follower must keep following");
    }

    @Test
    @DisplayName("volume follows the authority only under the volume capability")
    void testVolumeCapability() {
        this.hub = new Hub();
        final ServerMediaPlayer shared = this.hub.authority(Capability.VOLUME);
        final ServerMediaPlayer follower = this.hub.follower();
        await("the handshake to grant volume", () -> follower.granted(Capability.VOLUME));
        follower.volume(10);

        shared.volume(70);
        shared.mute(true);
        await("volume to mirror", () -> follower.volume() == 70 && follower.mute());
        this.hub.close();

        // WITHOUT THE CAPABILITY IT STAYS CLIENT-LOCAL
        this.hub = new Hub();
        final ServerMediaPlayer local = this.hub.authority();
        final ServerMediaPlayer localFollower = this.hub.follower();
        await("the follower to register", () -> local.watchers() == 1);
        localFollower.volume(10);
        local.volume(70);
        settle();
        assertEquals(10, localFollower.volume(), "volume must stay client-local without the capability");
    }

    @Test
    @DisplayName("a follower that has not opened its media cannot latch the session as live")
    void testUnknownMediaDoesNotLatchLive() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority();
        final ServerMediaPlayer follower = this.hub.follower();
        await("the follower to register", () -> server.watchers() == 1);
        // REPORTS FLOW WHILE NEITHER SIDE KNOWS THE MEDIA: AN UNKNOWN DURATION READS AS "LIVE",
        // AND LATCHING IT WOULD KILL SPEED CONTROL AND EVERY TIME CORRECTION FOR THE SESSION
        settle();

        server.syncDuration(60_000L);
        server.start();
        await("the follower to play", follower::playing);
        settle();
        assertFalse(server.liveSource(), "an unopened client must not mark the session live");
        assertTrue(server.canSpeed(), "speed control must survive the handshake");
    }

    @Test
    @DisplayName("releasing a follower deregisters it from the authority")
    void testReleaseUnwatches() {
        this.hub = new Hub();
        final ServerMediaPlayer server = this.hub.authority();
        final List<ServerMediaPlayer> followers = new ArrayList<>();
        for (int i = 0; i < 3; i++) followers.add(this.hub.follower());
        await("all followers to register", () -> server.watchers() == 3);

        followers.get(0).release();
        await("the released follower to be dropped", () -> server.watchers() == 2);
    }
}
