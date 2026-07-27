# MRL
MRL is a URI wrapper, acts like a gallery, MediaAPI catches and manages the instances, ensuring a single MRL instance for a single equal URL
```java
MRL mrl = MediaAPI.getMRL("https://imgur.com/gallery/abc123");
// IN CASE YOU WANT TO PRE-LOAD A LOT OF URLS AS A PLAYLIST, YOU CAN BY USING
MRL.preload(URI.create("https://example.com/video1.mp4"), URI.create("https://example.com/video2.mp4"));
```

## STATES
MRL has 3 different states
- `mrl.busy()` - The MRL is still loading sources from the platform. Not ready yet and no error has occurred.
- `mrl.ready()` - Sources have been successfully loaded. You can now access sources and create players.
- `mrl.error()` - Loading failed. The platform could not resolve the URI, or an exception occurred during loading.

In case you need a blocking way to get  the ready state, you can use `mrl.await(timeout)`, it will return if the MRL is busy or not, is up to you check if has `error()` or not.

## Sources and Media Indexes
Because MRLs can contain multiple sources. Each source represents a single piece of media (a video, an image, or an audio track). Sources are accessed by their index (zero-based position in the array).
```java
// ALL SOURCES
MRL.Source[] sources = mrl.sources();

// SOURCES COUNT
int count = mrl.sourceCount();

// GET A SPECIFIC SOURCE IF EXISTS (RETURNS NULL WHEN THERE IS NO SOURCE)
MRL.Source source = mrl.source(0);  // FIRST
MRL.Source second = mrl.source(1);  // SECOND

// GET FIRST SOURCE BY TYPE
MRL.Source video = mrl.videoSource(); // VIDEO
MRL.Source image = mrl.imageSource(); // IMAGE
MRL.Source audio = mrl.audioSource(); // AUDIO
MRL.Source[] videos = mrl.sourcesByType(MRL.MediaType.VIDEO);  // ALL VIDEO SOURCES
```

## MULTI SOURCES
Some platforms supported by watermedia (like imgur) resolves a single URL into mltiple sources.
Leading into this:
```
URL: https://imgur.com/gallery/abc123
  -> Source[0]: IMAGE (cat.png)       <- index 0
  -> Source[1]: VIDEO (dog.mp4)       <- index 1
  -> Source[2]: IMAGE (bird.gif)      <- index 2
```

To open a specific source, you need to specify the source index
```java
MRL mrl = MediaAPI.getMRL("https://imgur.com/gallery/abc123");
// CHECK FOR READY AND NO ERROR

// CREATE A PLAYER INSTANCE FOR THE SECOND SOURCE, IN THIS CASE, A DOG VIDEO
MediaPlayer player = mrl.createPlayer(1, renderThread, renderThreadEx, glEngine, alEngine, true, true);
```
-# NOTE: the error() method always returns true when there's no source avaiables despite begin valid and resolvable


# MEDIA PLAYERS
MediaPlayers can now be only AND JUST ONLY created by MRLs, so you need to always use MRLs.
The usage is simple
```
// INDEX CAN BE SKIPPED TO PICK ALWAYS FIRST INDEX
MediaPlayer player = mrl.createPlayer(renderThread, renderThreadEx, glEngine, alEngine, true, true);

// SPECIFIED INDEX
MediaPlayer player = mrl.createPlayer(2, renderThread, renderThreadEx, null, null, true, false);
```
The method can return NULL if the source index doesn't exists (MRL only has 2 sources) and if there's no available engine for that source type (you miss the WaterMedia Binaries Jar or FFMPEG failed to load)

It can return 3 variants of a mediaplayer
- TxMediaPlayer: A Texture based MediaPlayer (pictures, and animated pictures)
- FFMediaPlayer: Uses FFmpeg as a backend for full video/audio playback.
- ServerMediaPlayer: a headless wall-clock player that acts as the server-side time authority for synchronized playback (see SYNCHRONIZED PLAYBACK below)

### ARGUMENTS
- sourceIndex (int) - the source index to play
- renderThread (Thread) - the thread instance of the render thread (available on ``Minecraft.thread``)
- renderThreadEx (Executor) - the executor that runs task on render thread (``Minecraft.getInstance()``)
- glEngine (GLEngine) - the instance you create via MediaAPI.glEngine(renderThread, renderExecutor). The engine is self-contained: it captures and restores the host GL state around every upload, so GlStateManager (or Sodium's tracker) stays truthful without any proxying.
- alEngine (alEngine) - for now on, this was not needed to be created and you can give it a nll
- video (boolean) - enables video output, useful to not waste GPU resources in a audio-only player, this just turn off the support, doesn't force media to have or find the media
- audio (boolean) - same as video

## GLEngine and ALEngine
These engines abstract the OpenGL/OpenAL calls so that different platforms (Minecraft versions, custom renderers) can provide their own implementations:

## GLEngine handles:
Texture creation with proper filtering (LINEAR) and wrapping (CLAMP_TO_EDGE)
PBO (Pixel Buffer Object) double-buffered texture uploads for performance
Texture deletion

## ALEngine handles:
Audio source and buffer creation
Streaming audio upload with buffer queuing
Volume, speed, pause/play control
Resource cleanup

# MediaPlayer Status
A MediaPlayer goes through these statuses during its lifecycle:

### Status
- WAITING - Player created, waiting for resources or conditions to begin loading.
- LOADING - Actively loading media data from the network or disk.
- BUFFERING - Buffering data to ensure smooth playback.
- PLAYING - Media is actively playing.
- PAUSED - Playback is paused, can be resumed.
- STOPPED - Playback stopped, can be restarted from the beginning.
- ENDED - Media reached the end. Can be restarted or will loop if repeat is enabled.
- ERROR - An error occurred. Playback cannot continue.

You can check the status using `player#status()` or the convenience methods

# SYNCHRONIZED PLAYBACK (BRIDGE)
WaterMedia ships the whole sync system; you ship the byte carrier. A **`Bridge`** is one method, `send(ByteBuffer)`, and everything you receive from the other side goes into `player.sync(payload)`. There is no loop to write, no state to poll and no correction math to implement.

Nothing about who the peers are reaches WaterMedia: your bridge knows which session it serves and routes accordingly, which is why the natural shape is a small class holding that key rather than a lambda.

```java
public final class MediaBridge implements Bridge {
    private final ResourceLocation session;
    public MediaBridge(ResourceLocation session) { this.session = session; }
    public void send(ByteBuffer payload) { Network.send(this.session, payload); }
}
```

A player built with a bridge stops being independent:
- On the server, `ServerMediaPlayer` becomes the **authority**: it registers spectators, broadcasts its state and applies control requests.
- On the client, any player becomes a **follower**: it replicates the authority and keeps itself aligned with it. Its own control calls (`start`, `pause`, `seek`, `speed`, `repeat`…) are no longer applied locally — they travel upstream as requests and come back as authoritative state.

```java
// SERVER — YOUR BRIDGE BROADCASTS THE BYTES TO EVERY CLIENT WATCHING THIS MEDIA
ServerMediaPlayer server = MediaAPI.createPlayer(new MediaBridge(session), Capability.LOCKSTEP);
server.start();                 // THAT IS ALL — SNAPSHOTS AND HEARTBEATS GO OUT BY THEMSELVES

// SERVER PACKET HANDLER
server.sync(payload);

// CLIENT — YOUR BRIDGE SENDS THE BYTES TO THE SERVER
MediaPlayer player = MediaAPI.createPlayer(mrl, gfx, sfx, new MediaBridge(session));

// CLIENT PACKET HANDLER
player.sync(payload);
```
`sync(ByteBuffer)` is safe to call from your network thread: it decodes and validates there (the trust boundary), and everything else happens on WaterMedia's own 50ms tick, never on the game thread.

### THE SEQUENCE
1. A client player is created and announces itself with a hello; it enters the session as a loading spectator. A newcomer never interrupts the media already running for the others.
2. The authority answers with the session `Config` (the granted capabilities) plus a fresh snapshot, so a late joiner lands at the right timestamp immediately.
3. Each follower reports its own status upstream on every transition, plus a keepalive. The first client to know the media reports its duration and live flag, and the authority adopts them (first non-zero report wins for the session).
4. The authority broadcasts a snapshot whenever its state changes, plus a ~5s heartbeat. Out-of-order packets are rejected by revision; heartbeats re-apply.
5. On release the follower says goodbye. One that vanishes without saying it is swept by a silence timeout (`watcherTimeout(ms)`, 15s by default), so a client that disappears never freezes the audience.

### CAPABILITIES
Capabilities are declared on the authority and announced to every follower; the clock and state mirror is always on. Followers can read what was granted with `player.granted(capability)` — handy to grey out a control the audience is not allowed to drive.

- `LOCKSTEP` — one experience for everyone: while any ready spectator is loading or buffering, the authority presents `BUFFERING` with a frozen clock and the whole audience holds; it resumes exactly where it froze. Failed clients are ignored, and a spectator joining mid-playback only starts counting once it reports ready.
- `CONTROLS` — followers may drive the session: `pause()` on any client travels to the authority, which decides and broadcasts the result to everyone. Permissions are yours: filter before calling `sync()`, or gate your own UI. Without this capability, control calls on a follower are simply dropped.
- `VOLUME` — the authority also dictates volume and mute. Without it both stay client-local, as scaling and LOD always do.

### TUNING
One threshold, one correction: drift past the tolerance (`tolerance(ms)`, 1s by default) and playback jumps to where the session is with a `seekQuick`. Nothing else — no rate trimming to converge smoothly. That was tried and dropped: it is perceived as the video randomly running slow, and the client only ever falls *behind* anyway, because the authority is a bare clock with no decoding and no buffers. Drift is circular on repeating media so a loop boundary never fakes a huge gap, corrections are rate-limited while a pipeline resettles, and none run while the local player is loading or buffering.

Correcting needs two positions: where playback **should** be and where it actually is. `authorityTime()` is the first one — the last snapshot aged into a live position, because snapshots arrive seconds apart and a stale target would drag every correction backwards; `authority()` hands you that raw snapshot. The second is the player itself, with its own status (it may still be LOADING) and its own decode position. Inspect the gap with `drift()`, and what this player is with `role()`.

### THE WIRE
Packets are small fixed-size big-endian records in `org.watermedia.api.media.players.sync`, decoded by `Packet.of(ByteBuffer)`: `Sync` (29 B, the authoritative snapshot), `Config` (11 B), `Watch`/`Unwatch` (10 B), `Report` (20 B) and `Control` (19 B). Decoding takes only the packet's own bytes and leaves the rest in the buffer, so you can embed a payload in a larger frame carrying your own routing fields.