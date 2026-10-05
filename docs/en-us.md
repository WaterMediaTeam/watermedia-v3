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

The binaries module requires WaterMedia and shares this lifecycle. Minecraft clients require Binaries;
dedicated servers can omit it. After configuration loads, its startup blocks the remaining services
until it finishes; an installed module that fails stops startup.
Applications embedding the Java API can omit the module and retain image decoding and native-free
platform resolvers. Disabling FFmpeg skips extraction. `WaterMedia.stop()` clears native paths
after their consumers stop. There is no separate binaries initialization or shutdown API.

Compatible custom FFmpeg distributions may use GPL, LGPL or another declared license and build version.
The loader checks integrity and required native capabilities, not a fixed version or license allowlist.
The distribution must still provide JNI bindings compatible with the Java API used by WaterMedia.

A player owns the engines its factory consumes. Create engines lazily through suppliers so an unavailable media source cannot leak preallocated native resources. The application owns its OpenGL/Vulkan/OpenAL contexts and releases every player on the required host contexts before `WaterMedia.stop()`. Stop refuses while players remain open. A device/context reload requires new engines. Restarting WaterMedia does not unload native libraries or switch their version inside an existing JVM.

## Runtime requirements and configuration

The Java API targets Java 17. Supply WaterConfig, Log4j, Gson, JOML and the graphics/audio dependencies used by the selected host. WaterMedia bundles WaterConfig as a nested JAR for Forge, NeoForge and Fabric. The standalone launcher extracts that JAR into a temporary file and adds it to the relaunched JVM's classpath, downloading its other listed runtime libraries as needed; a cached or downloaded library joins the classpath only when it matches the SHA-256 pinned at build time. An embedding application must provide its own classpath. The distributed Binaries JAR embeds and relocates its XZ dependency. Development Gradle dependencies do not prove that a Minecraft installation contains other libraries. Forge, NeoForge and Fabric load WaterMedia on clients and dedicated servers, and reject Minecraft versions outside the published list. WaterMedia and Binaries accept each other from 3.0.0 up to, but excluding, 3.1.0; earlier 3.0.0 BETA builds are rejected. Fabric cannot limit a dependency to clients, so its bootstrap requires Binaries on clients and its manifest rejects incompatible Binaries versions.

Configuration loads before native provisioning. Check the generated configuration and the actual host classpath before diagnosing an unavailable backend. These field paths refer to `WaterMediaConfig`; sizes below use MiB (1,048,576 bytes), even where the configuration labels them MB.

| Setting | Default and related limits |
| --- | --- |
| `media.ffmpeg.disable` | `false`; enabling it skips FFmpeg provisioning and initialization. Restart the session after changing startup settings. Images remain independent of FFmpeg. |
| `media.ffmpeg.customPath` | Empty; an explicit directory must contain compatible native libraries, not an `ffmpeg.exe` command-line program. Patched TLS/origin capabilities remain required. |
| `media.ffmpeg.hardwareAccel` | `true`; actual use also depends on native/driver capabilities, codec and output engine. Software decoding remains available. |
| `media.ffmpeg.analyzeDuration` / `probeSize` | 7,000 ms / 10 MiB; both main and separate-audio inputs use them. Zero analysis duration leaves FFmpeg's default. Larger values can increase startup time. |
| `decoders.maxImageSourceSize` | 128 MiB of encoded image data. This is separate from the image reader's 512 MiB decoded-data limit. |
| `media.tx.texturesBudget` | 32 MiB; preloading also requires a capable engine, a valid frame count, and both decoded source frames and output textures fitting the budget. Zero forces animation streaming. |
| `media.ffmpeg.cache` / `cacheMaxSize` | Enabled / 10 MiB per eligible response; this does not cache every stream or HLS/DASH input. |
| `media.tx.cache` / `media.cacheMaxSize` | Image cache enabled / 8,192 MiB shared disk budget; older entries are evicted after writes. Disk, encoded-image, decoded-image and texture limits are independent. |
| `platforms.allowMatureContent` | `false`; supported platform adapters can mark a resource `BLOCKED` instead of handing it to playback. |
| `platforms.searchCacheCleanup` | 15 minutes; zero clears cached searches on every search. |

The optional file server starts when `network.forceEnableServer` is true, or when running server-side with `network.enableServer` true. Both default to false. It binds to `127.0.0.1:25572`; a remote listener needs an explicit address and a custom nonblank `network.token`. `network.remoteHost` is the client's full HTTP(S) base URL and does not change the server's bind address. The built-in listener uses HTTP; remote HTTPS requires host-managed TLS termination.

The defaults allow eight simultaneous requests, a 30-second server request deadline, an 8 MiB upload and 1,024 MiB total storage. `maxUploadSize = 0` removes only the individual upload limit; storage quota, concurrency and deadline still apply. Java network requests separately default to a 15-second timeout, ten redirects and a 16 MiB text-body limit. Native playback input options use their own timeout policy.

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

`speed(value)` accepts finite values in `(0, 4]` and reports acceptance. Java Sound cannot change playback rate. `canSpeed()` is a pure query that any thread can call every frame; `speed(value)` must run on the owning audio context, and the player clock follows only a rate the audio engine accepted. A synchronized follower returns `false` even when it sends a control request; that return does not confirm remote application. `spatialAudio(...)` also reports whether the engine accepted the update; use `spatialAudioSupported()` before presenting positional controls.

Time arguments use milliseconds; player volume uses a percentage from 0 to 100. Check `canSeek()` for streams that do not support seeking. Poll `status()`, `time()`, `duration()`, `buffered()` and `exception()` for playback diagnostics. Scaling and level of detail affect supported pixel layouts. Built-in graphics engines do not currently accept compressed BC textures. Release with `player.release()` when the host removes the playback surface, including when playback has failed.

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

`NetRequest` applies the same boundary to request bodies. A redirect to another origin fails instead
of resending the body unless the body is set with `body(body, true)`. A 303 response switches only
that send to GET without a body; the builder keeps its method and body for later sends.

## Choosing engines

Vulkan development takes priority; OpenGL remains supported as the secondary backend. Backend decisions belong in the rendering layer. This development preference does not change the standalone launcher's current initial OpenGL default or the user's saved engine selection.

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

Spatial audio uses `SpatialAudio` and an optional environment processor. See [spatial audio and Sound Physics Remastered](#spatial-audio-and-sound-physics-remastered) for the adapter, source/context ownership, update cadence and feature gates. Spatial state is local to a listener and is not transported by the synchronization protocol.

Engine base classes are sealed. The integration supplies host contexts and executors; it cannot pass arbitrary implementations of these engines. Engines manage their own playback resources, including textures, audio sources and buffers.

## Spatial audio and Sound Physics Remastered

Create an OpenAL engine with `MediaAPI.alEngine(true)` before constructing the player. Spatial mode is fixed for the engine's lifetime: its channel table advertises mono, so FFmpeg's existing resampler downmixes stereo and surround sources before upload. The ordinary `alEngine()` factory uses normal channel negotiation without forcing mono; layouts unsupported by the device may still be converted to the closest available channel count. Java Sound returns `false` from spatial updates and does not expose a native source.

The engine starts with dry, listener-relative mono audio. Supply a `SpatialAudio` state to place it in the world. Passing `null` restores dry, listener-relative playback while retaining mono decoding. Volume, mute, speed, pause and synchronization remain independent of spatial settings.

```java
import org.watermedia.api.media.engines.SFXEngine.SpatialAudio;

final ALEngine audio = MediaAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Run engine creation, spatial updates and release on the host's sound executor, with the original OpenAL context current. The host owns the device, context and listener; they must outlive every player using them. `FFMediaPlayer` uses private playback threads and currently requires the source's context to be the process-current context on those threads, as with ordinary `ALEngine` playback. A host that exposes its audio context only through thread-local binding is not supported by the player pipeline; binding that context on the caller's sound thread alone is insufficient. Native engine operations reject a different or absent context; they never switch or recreate the host's context. Release players before a device reload, then create new engines in the replacement context. `FFMediaPlayer.release()` interrupts and joins its playback pipeline before deleting the audio source; it must run before context destruction, not as a callback from that pipeline.

`SpatialAudio` validates finite positions representable by OpenAL floats, `0 < referenceDistance < maxDistance`, and finite nonnegative rolloff. The host's distance model determines how these parameters attenuate audio. The API does not change the global distance model, listener or source velocity. In particular, `maxDistance` is not a universal silence radius under every OpenAL model. Passing zero rolloff disables distance attenuation. Spatial state belongs to the local listener and is not included in synchronization packets.

### Sound Physics Remastered adapter

The reference artifact reviewed for this integration was `sound-physics-remastered-1.21.1.zip`, containing source code rather than a compiled JAR. Its modern entry point is:

```java
SoundPhysics.processSound(source, x, y, z, category, soundId, auxOnly);
```

Provide a small adapter from the consuming Minecraft mod. WATERMeDIA intentionally has no Minecraft or Sound Physics classes in its API. The host must load this adapter only when Sound Physics Remastered is present and initialized.

```java
final ResourceLocation soundId = ResourceLocation.fromNamespaceAndPath("waterframes", "media");
final SoundSource category = SoundSource.MASTER;
final SpatialAudio.Environment environment = new SpatialAudio.Environment() {
    @Override
    public void process(final int source, final SpatialAudio audio) {
        SoundPhysics.processSound(source, audio.x(), audio.y(), audio.z(),
                category, soundId, audio.auxOnly());
    }

    @Override
    public void reset(final int source) {
        SoundPhysics.setDefaultEnvironment(source, false);
    }
};

// INSIDE THE HOST SOUND EXECUTOR, AFTER ITS CONTEXT, SOUND PHYSICS AND MRL ARE READY.
final MRL.Source media = mrl.source(0);
if (media == null || (media.type() != MediaType.AUDIO && media.type() != MediaType.VIDEO)) {
    throw new IllegalStateException("A resolved audio or video source is required");
}
final MediaPlayer player = MediaAPI.createPlayer(mrl, () -> null, () -> MediaAPI.alEngine(true));
if (player == null) throw new IllegalStateException("The media backend could not create a player");
try {
    player.spatialAudio(new SpatialAudio(sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment));
    if (!player.start()) throw new IllegalStateException("The player refused to start");
} catch (final RuntimeException | Error failure) {
    player.release();
    throw failure;
}

// SUBMIT FROM THE HOST'S TICK INTEGRATION; THE EXECUTOR MUST SERIALIZE SOUND PHYSICS CALLS.
soundExecutor.execute(() -> player.spatialAudio(new SpatialAudio(
        sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment)));

// BEFORE THE CONTEXT IS DESTROYED, ON THE SAME SOUND EXECUTOR.
soundExecutor.execute(player::release);
```

The example's `soundExecutor`, resolved `mrl` and world coordinates come from the consuming mod. It plays the audio track without video output; the null-returning video supplier is intentional. The audio supplier creates one engine only if the factory actually needs it, and the factory releases it if player construction throws an `Exception`. To render video too, provide a video supplier that respects the rendering context's ownership. Keep ownership of any preallocated engine until the factory consumes it. The modern `processSound` call passes the category and identifier directly; the legacy `setLastSoundCategoryAndName` plus `onPlaySound` pair uses shared mutable metadata and is unnecessary here.

Keep a stable adapter instance between updates. Each update restores the original world position before invoking it, because Sound Physics can replace `AL_POSITION` with a reflected sound origin. It also clears prior source filters and auxiliary sends, so a disabled or replaced processor cannot leave old occlusion applied. The processor must apply its complete environment on every invocation. Throttle calls to `spatialAudio(...)` at the host level rather than returning early inside the processor. `reset` runs when replacing or removing an adapter and during release; the engine deletes only its own source and buffers, never Sound Physics' shared filters or reverb slots. If processing or reset throws, source effects are cleared and the exception propagates; a failing reset cannot prevent native source deletion during release.

Refresh long-running media as the listener, source or world changes, including stationary sources when the listener moves. A reasonable starting point is the mod's configured `soundUpdateInterval` of five ticks, with immediate updates for newly created sources and position changes. Stagger multiple players across ticks and obey the sound rate limits; performing ray casts per decoded audio packet is unnecessary. The API invokes the adapter only during explicit host updates, never from the FFmpeg decode or playback loop.

Sound Physics' verified prerequisites and neighboring settings in the supplied sources are:

| Requirement or gate | Effect on integration | Source inside the ZIP |
| --- | --- | --- |
| The host initializes Sound Physics in the source's context | Its shared filter and reverb IDs must belong to the same context; do not call `SoundPhysics.init()` for each player | `SoundPhysics.java:66`, `:85`; `mixin/SoundSystemMixin.java:27` |
| EFX and four auxiliary sends | Request `ALC_MAX_AUXILIARY_SENDS = 4` when the host creates the context; fewer sends yield fewer reverb paths | `mixin/LibraryMixin.java:18`; `SoundPhysics.java:97`, `:631` |
| `enabled` | Both processing and `setDefaultEnvironment` do nothing when disabled; WATERMeDIA therefore clears its source's EFX attachments itself | `SoundPhysics.java:189`, `:625` |
| `updateMovingSounds = false` by default | Category `RECORDS` is excluded; select the intended category and enable moving-sound processing if records need effects | `SoundPhysics.java:223`; `config/SoundPhysicsConfig.java:150` |
| World initialized, player and level available | Until these are ready the mod restores the default environment | `SoundPhysics.java:208`, `:230` |
| Exact position `(0, 0, 0)` | The mod treats this as a default environment; WATERMeDIA accepts it but cannot force the mod to process it | `SoundPhysics.java:211` |
| `maxSoundProcessingDistance = 512` by default | Distant sources receive the default environment | `SoundPhysics.java:216`; `config/SoundPhysicsConfig.java:158` |
| Per-identifier sound rate limits and ambient-sound settings | Calls may receive a default environment even when the source and context are valid | `SoundPhysics.java:236`, `:242` |
| Attenuation factor | To match its Minecraft linear-attenuation mixin, divide the desired range by `attenuationFactor` while enabled and set reference distance to half that range; the host must select the corresponding linear source distance model | `mixin/SourceMixin.java:42` |

`auxOnly = true` requests reflected-only output from the adapter and requires an environment processor. It does not implement reverb or enforce direct-path silence by itself. A disabled or bypassed processor that returns without applying effects leaves dry audio; the consumer must mute the player when strict silence is required in that situation. Basic mono positioning works without EFX; an EFX-based adapter requires a device that exposes EFX. Optional adapter code should remain in the consumer's integration module so loading WATERMeDIA without Minecraft or Sound Physics remains valid.

### Verification

`SpatialAudioTest` exercises invalid state rejection, Java Sound refusal, real OpenAL source coordinates and attenuation, callback origin restoration, effect clearing, exception handling, mismatched contexts and late calls after release. It also generates a stereo WAV and drives it through `FFMediaPlayer`, verifying mono PCM in actual OpenAL buffers. The device tests require an available OpenAL output device; the decode case additionally requires FFmpeg natives.

An in-game check is still needed for audibility of occlusion and reflections, host sound-executor serialization, listener movement, category settings and device reloads. The reference sources were reviewed during integration, but this checkout has no runnable Minecraft plus Sound Physics integration environment.

## Image and DDS access

PNG chunk representations implement `IChunk`: `toBytes()` serializes the payload without length, type or CRC; `toChunk()` returns a `CHUNK` envelope with its type and calculated CRC. The shared interface documents these inherited operations. The four GIF structures implement their own `common.gif.IChunk` with `toBytes()`: palettes emit RGB triples, descriptors emit their body, and graphic extensions include their size and terminator. GIF has no PNG-style CRC envelope. Its descriptor readers require little-endian buffers; PNG chunk envelope writes require big-endian buffers. Both families retain their serializers for future animation writers. Packages under `org.watermedia.bootstrap` and `org.watermedia.tools` are internal and excluded from the public API documentation inventory. The remaining methods from the reviewed list now document their contracts in source Javadoc. Run `gradle javadocInventory` from the project root to list missing public-method documentation with file names and line numbers; the task only reports findings.

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

`BCReader` reads already compressed BC1/BC3/BC7 blocks in a DX10 DDS texture array. Instantiate `BCReader` directly for block access; `CodecsAPI.decodeImage` does not dispatch DDS files. No native encoder is needed, but built-in OpenGL, Vulkan and software engines do not accept BC textures, so DDS block reading does not establish end-to-end player support. A WaterMedia animation footer supplies delays when present, while ordinary DDS slices have zero delay. Mip chains, volumes and cube maps are rejected. There is no BC encoder or codec-cache option. `CodecsAPI.available(...)` reports software pixel-decoder support, not GPU texture-format support.

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

The project logger names are `watermedia` and `watermedia_binaries`; configure those names for DEBUG diagnostics. Project log messages reduce media URLs to their origin and hide header values. Exception traces with private URLs retain their type, stack, causes and suppressed failures as sanitized text. Repeated file-server rejections and slow playback iterations are summarized in ten-second windows, with pending counts reported on shutdown.

The source in `src/test/java/org/watermedia/test/docs/ApiGuideExample.java` compiles factory usage for every graphics/audio engine and both synchronization roles. Its headless image example runs in `ApiGuideExampleTest`; GUI, audio-device and Minecraft behavior requires the corresponding host integration. The normal playback and spatial test suites exercise actual FFmpeg/OpenAL behavior separately.

Examples use host-provided variables and are integration fragments, not complete standalone applications. Jobs configured with `require_natives=true` require FFmpeg binaries instead of skipping their absence.
