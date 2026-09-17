# WaterMedia API guide

English · [Español (México)](es-mx.md)

WaterMedia resolves media addresses, decodes content and sends frames to graphics and audio engines. Use Java 17 or newer. Minecraft loaders bootstrap the library automatically; an embedding application starts it once after configuring its environment.

## Startup and ownership

```java
import org.watermedia.WaterMedia;
import org.watermedia.WaterMedia.BootStatus;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
BootStatus snapshot = WaterMedia.status();
```

Read `snapshot.state()` and `snapshot.failures()` together. `STARTING` is not ready. `READY` means services started; `DEGRADED` means optional services failed, so check the capability you need, such as `MediaAPI.ffmpegLoaded()`. `FAILED` retains failure diagnostics. Dedicated servers skip client modules normally. Pure Java image decoding through `CodecsAPI` needs no bootstrap.

`STOPPING` closes admission while services release their resources; `STOPPED` means there is no active session. An incomplete shutdown reports `FAILED` and requires a later stop attempt with the necessary host contexts still available. The last argument to `start` selects the client environment.

The optional binaries module requires WaterMedia and shares this lifecycle. After configuration loads,
its startup blocks the remaining services until it finishes; an installed module that fails stops startup.
An absent module is skipped: clients retain image decoding and native-free platform resolvers, and
servers need no native module. Disabling FFmpeg skips extraction. `WaterMedia.stop()` clears native paths
after their consumers stop. There is no separate binaries initialization or shutdown API.

Compatible custom FFmpeg distributions may use GPL, LGPL or another declared license and build version.
The loader checks integrity and required native capabilities, not a fixed version or license allowlist.
The distribution must still provide JNI bindings compatible with the Java API used by WaterMedia.

A player owns the engines its factory consumes. Create engines lazily through suppliers so an unavailable media source cannot leak preallocated native resources. The application owns its OpenGL/Vulkan/OpenAL contexts and releases every player on the required host contexts before `WaterMedia.stop()`. Stop refuses while players remain open. A device/context reload requires new engines. Restarting WaterMedia does not unload native libraries or switch their version inside an existing JVM.

## Shared tools

Shared file utilities are available through `IOTool`: bounded `httpsText` and `downloadVerified`,
non-destructive `verifySha256`, `makeExecutable`, `publishGeneration`/`currentGeneration` and `deleteTree`.
Downloads require HTTPS through redirects and an explicit byte budget. Generation directories may
have any safe direct-child name. `JSONTool.parse` handles downloaded JSON through the shared parser.

## Resolving addresses

```java
MRL mrl = MediaAPI.mrl("https://example.com/movie.mp4");
MRL[] playlist = MediaAPI.preload(firstUri, secondUri);
```

An MRL is a cached media-resource locator. One URI can resolve to multiple media sources, and each source can contain quality variants and separate audio tracks. Resolution is asynchronous. Poll `mrl.status()` from a game tick; the terminal states include `LOADED`, `ERROR` and `BLOCKED`. Inspect `mrl.exception()` on failure. `mrl.await(timeoutMillis)` is intended for background/application code: `true` means resolution finished, including a failure, rather than guaranteeing playable media.

`FETCHING` means resolution is pending, `EXPIRED` marks stale sources, and `FORGOTTEN` marks an evicted resource or a closed session. `await` returns `false` if its wait expires or the waiting thread is interrupted. Retain the returned handle when refreshing; a request made during a current load is deferred until that load finishes.

```java
if (mrl.status() == MRL.Status.LOADED) {
    List<MRL.Source> sources = mrl.sources();
    int count = mrl.sourceCount();
    MRL.Source first = mrl.source(0);
    MRL.Source firstVideo = mrl.sourceByType(MediaType.VIDEO);
    List<MRL.Source> videos = mrl.sourcesByType(MediaType.VIDEO);
}
```

`source(index)` returns null when the source is unavailable. `sources()` and `sourcesByType(...)` return lists. `MediaType` belongs to `org.watermedia.api.util`, not to `MRL`. Reload with `mrl = mrl.reload()`: a forgotten cache entry returns a replacement handle, while the old handle remains forgotten. MRLs belong to their bootstrap session and cannot revive work after that session closes.

Indices start at zero: a gallery can have an image at index 0, a video at index 1 and an animation at index 2. Pass index 1 to `MediaAPI.createPlayer` to play that gallery's video. Omitting the index selects the first source.

## Creating and controlling a player

```java
MediaPlayer player = MediaAPI.createPlayer(mrl, 0,
        () -> MediaAPI.glEngine(renderThread, renderExecutor),
        MediaAPI::jsEngine);
if (player == null) {
    throw new IllegalStateException("Source is unavailable or its backend failed");
}
try {
    if (!player.start()) throw new IllegalStateException("Player refused to start");
} catch (RuntimeException | Error failure) {
    player.release();
    throw failure;
}
```

`createPlayer` returns null if the source is still fetching, its index is invalid, the required backend is unavailable, or construction throws an `Exception`. An `Error` propagates. Check the MRL status before deciding whether to retry. Suppliers are invoked only when needed; images do not consume the audio supplier. Use a supplier returning null to omit an output, for example `() -> null` for audio-only playback's graphics engine. Do not share an engine between players.

`TxMediaPlayer` handles supported images and animations. `FFMediaPlayer` handles audio/video through FFmpeg. `ServerMediaPlayer` provides a synchronization clock without decoding or native engines.

```java
player.pause(true);
player.pause(false);
player.seek(15_000);
player.volume(50);
player.mute(true);
player.speed(1.25f);
player.repeat(true);
player.maxSize(1280, 720);
```

Time arguments use milliseconds; player volume uses a percentage from 0 to 100. Check `canSeek()` for streams that do not support seeking. Poll `status()`, `time()`, `duration()`, `buffered()` and `exception()` for playback diagnostics. Scaling and level of detail affect supported pixel layouts; BC block data is uploaded at its encoded size. Release with `player.release()` when the host removes the playback surface, including when playback has failed.

| Player state | Meaning |
| --- | --- |
| `WAITING` | Created and waiting for startup conditions. |
| `LOADING` | Loading or preparing media. |
| `BUFFERING` | Waiting for enough data to continue. |
| `PLAYING` | Playing media. |
| `PAUSED` | Paused and available to resume. |
| `STOPPED` | Stopped and available to start from the beginning. |
| `ENDED` | Reached the end of the media. |
| `ERROR` | Playback encountered a failure. |

## HTTPS trust

HTTPS playback requires the native verification capabilities supplied by the bundled build or a
compatible custom build. FFmpeg verifies certificate chains and
DNS/IP identities using a snapshot of the JVM truststore taken during startup. Configure the standard
`javax.net.ssl.trustStore` properties before starting WaterMedia when private certificate authorities
are needed. An empty or invalid truststore prevents FFmpeg initialization; verification is never
silently disabled. Empty `customPath` settings do not add the working directory to native discovery.

The exported authorities also reach nested HLS/DASH requests. This shares trust anchors with Java;
it does not replace FFmpeg's OpenSSL transport. MediaAPI creates and owns
the temporary PEM, checks that the required native options exist and applies them to the main and
separate-audio inputs. Its module owns initialization and cleanup; player input setup calls
`MediaAPI.configureTLS(...)`. Custom JSSE socket factories, hostname-verifier callbacks and revocation policies are not transferred
to OpenSSL. Protocol whitelists remain unchanged. The temporary CA bundle is removed when the media
module stops, after all players have released their native connections.

Playback credentials belong to the initial request's origin: scheme, hostname and effective port.
Redirects and HLS/DASH child requests send `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2`
and `X-WaterMedia-Token` only to that origin. A cross-origin request generates its own `Host` header;
ordinary headers such as User-Agent, Accept, Referer and Origin remain available to CDNs.

Generated cookies follow the same origin boundary. Cookies from another origin's `Set-Cookie`
response are ignored, including when a redirect later returns to the original server. This is a
single-origin credential policy; a redirected server cannot establish a separate cookie session
inside that playback input. Native HTTP logs omit full requests, header dumps and cookie values.

## Choosing engines

| Factory | Host obligation |
| --- | --- |
| `MediaAPI.glEngine(renderThread, executor)` | Executor dispatches to the thread owning the OpenGL context and is pumped while playback/release can wait for it. |
| `MediaAPI.vkEngine(context)` | Supply a `VKContext`; its Vulkan objects and retirement mechanism outlive the engine. |
| `MediaAPI.awtEngine(onFrame)` | Paint the published image from the AWT/Swing UI; marshal the optional callback to the UI thread as needed. |
| `MediaAPI.jfxEngine(onFrame)` | Supply a JavaFX runtime and bind the engine's image to the host UI. |
| `MediaAPI.headlessEngine(preload)` | Captures uploaded frames without a graphics context; useful for tests and server-side image processing. |
| `MediaAPI.alEngine()` | OpenAL context is already current and remains valid on the threads performing audio operations. |
| `MediaAPI.alEngine(true)` | Same OpenAL contract; advertises mono output for positional sound. |
| `MediaAPI.jsEngine()` | Java Sound device/line is available; positional audio is unsupported. |

Spatial audio uses `SpatialAudio` and an optional environment processor. See [spatial audio and Sound Physics Remastered](SPATIAL_AUDIO.md) for the adapter, source/context ownership, update cadence and feature gates. Spatial state is local to a listener and is not transported by the synchronization protocol.

Engine base classes are sealed. The integration supplies host contexts and executors; it cannot pass arbitrary implementations of these engines. Engines manage their own playback resources, including textures, audio sources and buffers.

## Image and DDS access

```java
try (ImageReader reader = CodecsAPI.decodeImage(encodedBuffer)) {
    while (reader.hasNext()) {
        reader.next();
        PixelFormat format = reader.pixelFormat();
        for (int plane = 0; plane < reader.planeCount(); plane++) {
            ByteBuffer pixels = reader.plane(plane);
            // CONSUME OR COPY BEFORE THE NEXT FRAME REUSES THE READER'S BUFFER.
        }
    }
}
```

Consult the returned pixel format and plane count rather than assuming BGRA. `readAll()` retains copies and enforces an aggregate decoded-byte limit for one image. Concurrent readers still consume independent memory.

`BCReader` reads already compressed BC1/BC3/BC7 blocks in a DX10 DDS texture array. It needs no native encoder; the receiving graphics engine must support the block format. A WaterMedia animation footer supplies delays when present, while ordinary DDS slices have zero delay. Mip chains, volumes and cube maps are rejected. There is no BC encoder or codec-cache option. `CodecsAPI.available(...)` reports software pixel-decoder support, not GPU texture-format support.

## Synchronized playback

Implement `Bridge.send(ByteBuffer)` with the host's transport. The authority's bridge broadcasts downstream; a follower's bridge sends upstream. Route incoming payloads to the matching `player.sync(payload)` session. Copy the bytes when the transport queues them beyond the call. Bridge implementations must be thread-safe and nonblocking; authenticate and authorize the peer in the host transport.

```java
ServerMediaPlayer authority = MediaAPI.createPlayer(downstreamBridge,
        Config.Capability.LOCKSTEP, Config.Capability.CONTROLS);
authority.start();
MediaPlayer follower = MediaAPI.createPlayer(mrl, gfxSupplier, sfxSupplier, upstreamBridge);
if (follower == null) throw new IllegalStateException("Follower media is unavailable");
```

Use `org.watermedia.api.media.players.sync.Config.Capability`. `LOCKSTEP` holds the authority while an established follower buffers; late joiners do not immediately pause the audience. `CONTROLS` allows followers to request playback changes. `VOLUME` also synchronizes volume and mute. Other local controls, including scaling and spatial effects, remain local.

`sync(...)` validates incoming packet structure. The bridge does not identify or authenticate peers for you. Followers announce themselves and receive session state; the authority learns duration/live status from a ready follower. Release each authority and follower when its host session ends. Tune drift with `tolerance(ms)` and inspect `authorityTime()`, `drift()` and `role()` when diagnosing synchronization.

### Session sequence and calling threads

1. A follower announces itself as a loading spectator without immediately holding the existing audience.
2. The authority replies with capabilities and a current state snapshot.
3. Followers report state changes and send periodic keepalives. Until the authority learns a duration or a live stream, its clock stays at zero.
4. The authority broadcasts state changes and refreshes its snapshot about every five seconds. Followers ignore older revisions; equal revisions can refresh the time reference.
5. Releasing a follower sends its departure. `watcherTimeout(ms)` removes silent viewers after 15 seconds by default.

Watcher timeouts must be between 1 and `Long.MAX_VALUE / 1_000_000` milliseconds. Values outside that range are rejected without changing the current timeout.

The authority retains the first positive duration for its session. When reports identify a live stream, later reports with `live=false` do not clear that classification. With `LOCKSTEP`, waiting for an established viewer presents `BUFFERING`, freezes the clock and resumes from the same position. Follower playback controls such as start, pause, seek, speed and repeat travel as requests to the authority instead of applying locally; after capabilities arrive, requests without `CONTROLS` are discarded.

`sync(...)` decodes and consumes the packet on the calling thread. Followers reconcile playback on their 50 ms synchronization tick. An authority can process controls and send replies synchronously during `sync`; not every effect is deferred to the tick. Bridge callbacks can also run on host control threads. Use `authority.sync(payload)` for upstream messages and `follower.sync(payload)` for downstream messages, after routing and authorizing them in the host transport.

Read `player.granted(capability)` when presenting controls. Frame stepping is unavailable to followers. `tolerance(ms)` defaults to one second; correction uses rate-limited `seekQuick`, pauses while loading or buffering, and accounts for loop boundaries in finite repeating media. Drift can be corrected in either direction; synchronization does not gradually alter playback speed. `authority()` returns the last received snapshot, while `authorityTime()` estimates its current position using elapsed time.

### Packet format

Packets are fixed-size big-endian records in the synchronization package. `Packet.of(ByteBuffer)` consumes only the packet's bytes, leaving trailing data for the host transport.

| Packet | Size | Direction |
| --- | ---: | --- |
| `Sync` | 29 bytes | Authority → followers |
| `Config` | 11 bytes | Authority → followers |
| `Watch` / `Unwatch` | 10 bytes | Follower → authority |
| `Report` | 20 bytes | Follower → authority |
| `Control` | 19 bytes | Follower → authority |

## Executable examples and diagnostics

The source in `src/test/java/org/watermedia/test/docs/ApiGuideExample.java` compiles factory usage for every graphics/audio engine and both synchronization roles. Its headless image example runs in `ApiGuideExampleTest`; GUI, audio-device and Minecraft behavior requires the corresponding host integration. The normal playback and spatial test suites exercise actual FFmpeg/OpenAL behavior separately.

Examples use host-provided variables and are integration fragments, not complete standalone applications. Jobs configured with `require_natives=true` require FFmpeg binaries instead of skipping their absence.

See the [technical review](technical-review-2026-09-06.md) for bootstrap/module outcomes, the native verification record and remaining platform-specific checks.
