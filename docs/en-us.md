# WaterMedia API guide

English · [Español (México)](es-mx.md)

WaterMedia resolves media addresses, decodes content and sends frames to graphics and audio engines. Use Java 17 or newer. Minecraft loaders bootstrap the library automatically; an embedding application starts it once after configuring its environment.

## Startup and ownership

```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded();
```

`start` loads the modules once for the life of the process and nothing is ever unloaded: there is no stop, and a second `start` throws. The last argument selects the client environment; the modules only clients need (Binaries, media) do nothing on a dedicated server. `WaterMedia.loaded()` is the only readiness check. While loading, `WaterMedia.loading()` names the module being started and `step()`/`steps()` count the modules, which is all a loading screen needs. A part a module can live without (the file server, FFmpeg) logs its failure and stays unavailable while the load completes, so check the capability you need, such as `MediaAPI.ffmpegLoaded()`; any other module failure (the configuration, an installed Binaries module) makes `start` throw `IllegalStateException` with that failure as its cause, and `loaded()` stays false. Pure Java image decoding through `CodecsAPI` needs no bootstrap.

`MediaAPI.mrl`, the media players and `NetworkAPI.upload` throw `IllegalStateException` until `loaded()` is true, and the media ones also on dedicated servers. `PlatformAPI` needs no bootstrap at all: its registry is static, `register`, `fetch` and `search` work at any time, and the handlers that need Binaries (YouTube, yt-dlp) fail with their own `PlatformException` until it loads, and always on dedicated servers, which never load Binaries. `NetworkAPI.upload` also rejects anything that is not an existing file up front; an unreachable or misconfigured remote server fails the returned status instead.

The binaries module requires WaterMedia and loads with it. Minecraft clients require Binaries;
dedicated servers can omit it. After configuration loads, it provisions the natives before the
network and media modules; an installed module that fails ends the load.
Applications embedding the Java API can omit the module and retain image decoding and native-free
platform resolvers. Disabling FFmpeg skips extraction. The native paths stay published for the life
of the process. There is no separate binaries initialization or shutdown API.

Compatible custom FFmpeg distributions may use GPL, LGPL or another declared license and build version.
The loader checks integrity and required native capabilities, not a fixed version or license allowlist.
The distribution must still provide JNI bindings compatible with the Java API used by WaterMedia.

A player owns the engines its factory consumes. Create engines lazily through suppliers so an unavailable media source cannot leak preallocated native resources. The application owns its OpenGL/Vulkan/OpenAL contexts and releases every player on the required host contexts before it tears those contexts down. A device/context reload requires new engines. Native libraries are never unloaded and cannot switch their version inside an existing JVM.

## Runtime requirements and configuration

The Java API targets Java 17. Supply WaterConfig, Log4j, Gson, JOML and the graphics/audio dependencies used by the selected host. WaterMedia bundles WaterConfig as a nested JAR for Forge, NeoForge and Fabric. The standalone launcher extracts that JAR into a temporary file and adds it to the relaunched JVM's classpath, downloading its other listed runtime libraries as needed; a cached or downloaded library joins the classpath only when it matches the SHA-256 pinned at build time. An embedding application must provide its own classpath. The distributed Binaries JAR embeds and relocates its XZ dependency. Development Gradle dependencies do not prove that a Minecraft installation contains other libraries. Forge, NeoForge and Fabric load WaterMedia on clients and dedicated servers, and reject Minecraft versions outside the published list. WaterMedia and Binaries accept each other from 3.0.0 up to, but excluding, 3.1.0; earlier 3.0.0 BETA builds are rejected. Fabric cannot limit a dependency to clients, so its bootstrap requires Binaries on clients and its manifest rejects incompatible Binaries versions.

The Minecraft bootstraps refuse to start when TLauncher launched the game. They detect it through its injected skin mod, its classes, its files on the class path, its JVM options, its threads and its parent processes, and fail with a message naming the evidence; leftovers of a past installation in the game directory only log a warning. Embedding applications skip this check.

Configuration loads before native provisioning. Check the generated configuration and the actual host classpath before diagnosing an unavailable backend. These paths are the keys of the generated `watermedia.toml`; sizes below use MiB (1,048,576 bytes), even where the configuration labels them MB.

| Setting | Default and related limits |
| --- | --- |
| `media.ffmpeg.disable` | `false`; enabling it skips FFmpeg provisioning and initialization. Startup settings take effect on the next launch. Images remain independent of FFmpeg. |
| `media.ffmpeg.customPath` | Empty; an explicit directory must contain compatible native libraries, not an `ffmpeg.exe` command-line program. Patched TLS/origin capabilities remain required. |
| `media.ffmpeg.hardwareAccel` | `true`; actual use also depends on native/driver capabilities, codec and output engine. Software decoding remains available. On a `VKEngine` whose `VKContext.deviceInfo()` describes a device created for decoding, 4:2:0 H.264, H.265 and AV1 streams decode on that device and the engine samples them in place; every other accelerator downloads its frames to RAM. |
| `media.ffmpeg.analyzeDuration` / `probeSize` | 7,000 ms / 10 MiB; both main and separate-audio inputs use them. Zero analysis duration leaves FFmpeg's default. Larger values can increase startup time. |
| `decoders.maxImageSourceSize` | 128 MiB of encoded image data. This is separate from the image reader's 512 MiB decoded-data limit. |
| `media.watertx.texturesBudget` | 32 MiB; preloading also requires a capable engine, a valid frame count, and both decoded source frames and output textures fitting the budget. Zero forces animation streaming. |
| `media.ffmpeg.cache` / `cacheMaxResponse` | Enabled / 10 MiB per eligible response; this does not cache every stream or HLS/DASH input. |
| `media.watertx.cache` / `media.cacheBudget` | Image cache enabled / 8,192 MiB shared disk budget; older entries are evicted after writes. Disk, encoded-image, decoded-image and texture limits are independent. |
| `platforms.allowMatureContent` | `false`; supported platform adapters can mark a resource `BLOCKED` instead of handing it to playback. |
| `platforms.searchCleanupInterval` | 15 minutes; zero clears cached searches on every search. |
| `platforms.searchLimit` | 2 results per platform; a search asking for more is capped here. |

The optional file server starts according to `network.server`: `OFF` (the default) never, `DEDICATED` on any host started without a client (dedicated servers), `ALWAYS` on clients too. It binds to `127.0.0.1:25572`; a remote listener needs an explicit address and a custom nonblank `network.token`; a bad value fails the file server's start, which the network module logs while the load still completes. `network.remoteUrl` is the full HTTP(S) base URL every uploader uses, clients included, and does not change the server's bind address. The built-in listener uses HTTP; remote HTTPS requires host-managed TLS termination. Its root answers only the product name, file IDs use lowercase letters and digits so they name the same file on every file system, a single satisfiable `Range` is honoured by `GET` and `HEAD` alike, a malformed or multipart one is ignored, and a request that asks to close the connection gets `Connection: close` back, so FFmpeg opens a fresh connection for each seek.

The defaults allow eight simultaneous requests, a 30-second idle timeout per request, an 8 MiB upload and 1,024 MiB total storage. The timeout cuts a request only after that long without headers or transfer progress, so a long download on a slow link finishes while a stalled one is dropped. `maxUploadSize = 0` removes only the individual upload limit; storage quota, concurrency and the idle timeout still apply, and a full storage refuses uploads with `507`, warning on the first refusal of each server run, until files are removed by hand. `NetworkAPI.upload` returns one `UploadStatus` per file: `uri()` is the `water://remote/<id>` address once the server accepts it, and `cancel()` aborts a transfer in flight, ending the status as failed. Java network requests, playlist fetches and the yt-dlp socket timeout share `network.timeout` (15 seconds), ten redirects and a 16 MiB text-body limit. Native playback input options use their own timeout policy.

## Public and internal packages

The JAR is the explicit module `org.watermedia`. Its public API is the set of exported packages: `org.watermedia`, `api.codecs`, `api.media`, `api.network`, `api.platform` and `api.util`. Everything else is internal: codec readers, writers and chunk structures, player queues and caches, the built-in platforms, the bootstrap and the bundled FFmpeg bindings. `org.watermedia.tools` and `org.watermedia.bootstrap` are exported only to the Binaries module: `tools` shares its download, digest and JSON utilities, and `bootstrap` holds the module lifecycle Binaries implements.

Forge 1.21.1 and newer enforce the descriptor, so code outside the module fails with `IllegalAccessError` or `IllegalAccessException` when it touches an internal package. NeoForge, Fabric, older Forge versions and classpath builds ignore it; treat the same boundary as the contract there too. Reach internal features through their entry points, such as `CodecsAPI.reader`, `CodecsAPI.writer` and `PlatformAPI.fetch`. `gradle javadocInventory` checks only the exported packages, read from `src/main/module/org.watermedia/module-info.java`.

## Resolving addresses

```java
MRL mrl = MediaAPI.mrl("https://example.com/movie.mp4");
MRL[] playlist = MediaAPI.preload(firstUri, secondUri);
```

An MRL is a cached media-resource locator. One URI can resolve to multiple media sources, and each source can contain quality variants and separate audio tracks. Resolution is asynchronous. Poll `mrl.status()` from a game tick; the terminal states include `LOADED`, `ERROR` and `BLOCKED`. Inspect `mrl.exception()` on failure. `mrl.await(timeoutMillis)` is intended for background/application code: `true` means resolution finished, including a failure, rather than guaranteeing playable media.

`FETCHING` means resolution is pending, `EXPIRED` marks stale sources, and `FORGOTTEN` marks an evicted resource. `await` returns `false` if its wait expires or the waiting thread is interrupted. Retain the returned handle when refreshing; a request made during a current load is deferred until that load finishes.

```java
if (mrl.status() == MRL.Status.LOADED) {
    List<MRL.Source> sources = mrl.sources();
    int count = mrl.sourceCount();
    MRL.Source first = mrl.source(0);
    MRL.Source firstVideo = mrl.sourceByType(MediaType.VIDEO);
    List<MRL.Source> videos = mrl.sourcesByType(MediaType.VIDEO);
}
```

`source(index)` returns null when the source is unavailable. `sources()` and `sourcesByType(...)` return lists. `MediaType` belongs to `org.watermedia.api.util`, not to `MRL`. Reload with `mrl = mrl.reload()`: a forgotten cache entry returns a replacement handle, while the old handle remains forgotten.

`MediaAPI.mrl(String)` treats a string naming an existing file as a path relative to the working directory WaterMedia started with; any other string is parsed as a URI. Malformed, relative or empty inputs return an uncached MRL born in `ERROR` whose `reload()` returns itself. A direct link is rejected only when the server answers with a document type (HTML, CSS, JavaScript, JSON, XHTML); any other content type passes whatever its casing or parameters, and ambiguous ones are classified by sniffing the first bytes. Each source keeps one URL per quality bucket — the widest rendition, then the tallest, then the highest frame rate, then the first the platform listed — and its headers are frozen.

Indices start at zero: a gallery can have an image at index 0, a video at index 1 and an animation at index 2. Pass index 1 to `MediaAPI.player` to play that gallery's video. Omitting the index selects the first source.

Platform handlers follow one contract. `Platform.resolve` returns `null` only for a foreign host or scheme; a URL of its own host whose shape it cannot play (a profile, a folder, a bare host) fails with `PlatformException` ("Unsupported URL") without touching the network, and every other failure, an interrupted wait included, is a `PlatformException` too, so `MRL` reports the handler's own message. Mature content blocks with `MatureContentException` wherever the platform exposes a flag or label (Imgur, Kick, Twitch, Bluesky, PornHub, yt-dlp, Twitter's age gate and YouTube's), which `MRL` turns into `BLOCKED`. An album or playlist skips a bad item with a warning and only fails when nothing remains. Handlers registered with `PlatformAPI.register` are probed before the built-in ones, newest first, and need no bootstrap: register once, before or after `WaterMedia.start`, and the override holds until `unregister`, which also drops a built-in handler for the rest of the process. `PlatformAPI.search(query)` returns at once the read-only list its results land in, up to `platforms.searchLimit` per platform in answer order or fewer with `search(query, limit, reSearch)`, and `PlatformAPI.searching(query)` turns false once every platform answered; poll both from the UI thread. One search runs at a time on four worker threads, and every built-in search request gives up after 5 seconds: a new query discards the one in flight, and a query one keystroke away from the previous one (a letter more, less or fixed, the user still typing) waits 300 ms for the next keystroke before touching the network, while any other query probes at once. A query already searched with the same limit is served the same list instance from the cache, finished or still filling, unless `search(query, true)` asks the platforms again. Registration drops cached searches, and a blank search returns an empty list and leaves a running search alone.

`Metadata` means the same thing everywhere: `title` names the content (a video, stream, post or clip, never the account), `description` carries its caption, body text or category and `author` the channel, uploader or streamer. Signed CDN links expire when their `expire`, `expires`, `validto` or `x-expires` parameter says so; the rest carry a per-platform lifetime or none.

## Creating and controlling a player

```java
MediaPlayer player = MediaAPI.player(mrl, 0,
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

`player` returns null if the source is still fetching, its index is invalid, the required backend is unavailable, or construction throws an `Exception`. An `Error` propagates. Check the MRL status before deciding whether to retry. Suppliers are invoked only when needed; images do not consume the audio supplier. Use a supplier returning null to omit an output, for example `() -> null` for audio-only playback's graphics engine. Do not share an engine between players.

`TxMediaPlayer` handles supported images and animations. `FFMediaPlayer` handles audio/video through FFmpeg. `ServerMediaPlayer` provides a synchronization clock without decoding or native engines.

```java
player.pause(true);
player.pause(false);
player.seek(15_000);
player.volume(50);
player.mute(true);
player.speed(1.25f);
player.repeat(true);
player.scale(1280, 720);
```

`speed(value)` accepts finite values in `(0, 4]` and reports acceptance. Java Sound cannot change playback rate. `canSpeed()` is a pure query that any thread can call every frame; `speed(value)` must run on the owning audio context, and the player clock follows only a rate the audio engine accepted. `spatialAudio(...)` also reports whether the engine accepted the update; use `spatial()` before presenting positional controls.

Time arguments use milliseconds; player volume uses a percentage from 0 to 100. Check `canSeek()` for streams that do not support seeking. Poll `status()`, `time()`, `duration()`, `buffered()` and `exception()` for playback diagnostics. Scaling and level of detail affect supported pixel layouts. Built-in graphics engines do not currently accept compressed BC textures. Release with `player.release()` when the host removes the playback surface, including when playback has failed.

Every boolean control answers one question: did this call change the player's own state? `false` covers a call refused by the media, already satisfied or made after `release()`; `repeat(boolean)` follows the same rule. The three players share one contract: a pause while loading is latched and applied when the pipeline opens; `pause()` and `resume()` return `false` on an ended, stopped or failed player; `stop()` tears the pipeline down, lands in `STOPPED` with `time()` at 0 and returns `true` only when a transition happened; `seek(...)` on an ended or stopped player restarts it paused at the target, and `togglePlay()` there starts it again; `nextFrame()` and `previousFrame()` only work while paused. `canPlay()` is true from the first successful load until the player fails or is released, `canSeek()` and `live()` are false until the source is open, `fps()` is 0 until the rate is known, and `status()` is `STOPPED` after `release()`. A still image shown through FFmpeg honours `displayTime(ms)` like the image player and never loops; without a display time it stays up until stopped.

`onStatus(listener)` adds a `MediaPlayer.StatusListener` that receives the player, the previous and the current status on every real transition, including the ones a restart, `stop()` and `release()` cause. Listeners stack (adding one twice keeps it once), and the transition into `STOPPED`, `ENDED` or `ERROR` consumes them, so a playlist consumer registers once per playback and, when it restarts the player from that terminal callback, registers again from the callback itself. Transitions are delivered one at a time and in the order the statuses changed, even when several threads drive the player, on whichever thread drove the change (a playback thread, the shared sync ticker, or the caller of `status()`, `time()` or `texture()` on image players whose clock resolves the end lazily), so keep it short and never call `release()` from it. A listener never runs while the player holds one of its locks, nor on a playback thread `release()` is waiting for: `release()` delivers its `STOPPED`, and anything published while it tore the player down, once it finishes and the engines are freed, so that callback may wait for another thread that uses the player; every other callback must stay short and never block. `release()` is idempotent.

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
it does not replace FFmpeg's OpenSSL transport. `FFMediaPlayer` creates and owns the temporary PEM
when FFmpeg starts, checks that the required native options exist and applies them to the main and
separate-audio inputs of every player it opens; nothing in the public API touches that configuration.
Custom JSSE socket factories, hostname-verifier callbacks and revocation policies are not transferred
to OpenSSL. Protocol whitelists remain unchanged. The temporary CA bundle lives for the whole process
and is deleted when the JVM exits.

Playback credentials belong to the initial request's origin: scheme, hostname and effective port.
Redirects and HLS/DASH child requests send `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2`
and `X-WaterMedia-Token` only to that origin. A cross-origin request generates its own `Host` header;
ordinary headers such as User-Agent, Accept, Referer and Origin remain available to CDNs.

Generated cookies follow the same origin boundary. Cookies from another origin's `Set-Cookie`
response are ignored, including when a redirect later returns to the original server. This is a
single-origin credential policy; a redirected server cannot establish a separate cookie session
inside that playback input. Native HTTP logs omit full requests, header dumps and cookie values.

`NetRequest` applies the same boundary to request bodies. A redirect to another origin fails instead
of resending the body unless the body is set with `body(body, true)`. A 303 response is re-requested
as GET without a body. A request is sent once: configure it, `send()` it and read its response on one thread.

Redirects only move between http and https, in either direction: a `Location` pointing at a `file:`,
`jar:` or `ftp:` resource fails the request, and only the first request may name a local file. URLs
taken from platform JSON must be web links too. Playlist entries may use any streaming scheme FFmpeg
speaks (`rtsp`, `rtmp`, `udp`, `srt`) but never a local file or archive, unless the playlist itself is
a local file. The headers a caller sets win over the defaults, User-Agent included,
and the derived Referer keeps an explicit port. `inputStream()` and `text()` return the body of an
error status instead of throwing, the body can be read once, `json(type)` accepts every JSON content
type (`application/json`, `text/json`, `+json` subtypes) and a text body that trickles in stops after
four read timeouts in total. Neither the builder nor the response is thread-safe.

## Choosing engines

Vulkan development takes priority; OpenGL remains supported as the secondary backend. Backend decisions belong in the rendering layer. The standalone app renders with Vulkan unless the user picks OpenGL; when Vulkan is unavailable it runs that launch on OpenGL and keeps the saved choice. `-Dwatermedia.engine=opengl` or `-Dwatermedia.engine=vulkan` overrides the engine for one launch without saving it. The app keeps its engine, interface scale (`0` follows the monitor, otherwise a factor from 1 to 3 in 0.25 steps, shown in Settings as `Auto`, `x1.0`, `x1.25` and so on), CRT effect and click sound in `config/app.properties` under its working directory.

| Factory | Host obligation |
| --- | --- |
| `MediaAPI.glEngine(renderThread, executor)` | Executor dispatches to the thread owning the OpenGL context and is pumped while playback/release can wait for it. |
| `MediaAPI.vkEngine(context)` | Supply a `VKContext`; its Vulkan objects and retirement mechanism outlive the engine. Override `deviceInfo()` with the feature chain, extensions and queue counts of the device to decode video on it (see the README); `MediaAPI.vulkanDecode()` tells whether the natives support that. |
| `MediaAPI.awtEngine(onFrame)` | Paint the published image from the AWT/Swing UI; marshal the optional callback to the UI thread as needed. |
| `MediaAPI.jfxEngine(onFrame)` | Supply a JavaFX 13 or newer runtime and bind the engine's image to the host UI; without it the factory throws `IllegalStateException` instead of a linkage error. |
| `MediaAPI.headlessEngine()` / `headlessEngine(preload)` | Captures uploaded frames without a graphics context; useful for tests and image processing. Like every engine and media player it only exists on a client; a dedicated server can only create `ServerMediaPlayer` clocks. |
| `MediaAPI.alEngine()` | OpenAL context is already current and remains valid on the threads performing audio operations. |
| `MediaAPI.alEngine(true)` | Same OpenAL contract; advertises mono output for positional sound. |
| `MediaAPI.jsEngine()` | Java Sound device/line is available; positional audio is unsupported. |

Spatial audio uses `SpatialAudio` and an optional environment processor. See [spatial audio and Sound Physics Remastered](#spatial-audio-and-sound-physics-remastered) for the adapter, source/context ownership, update cadence and feature gates. Spatial state is local to a listener and never travels in a clock snapshot.

Engine base classes are sealed. The integration supplies host contexts and executors; it cannot pass arbitrary implementations of these engines. Engines manage their own playback resources, including textures, audio sources and buffers.

Every engine follows one lifecycle. `release()` is idempotent, must never run concurrently with the player that feeds the engine, and leaves it inert: `released()` is true and `texture()` returns 0. The player checks `supports(format, bits)` before uploading and falls back to 8-bit BGRA when the engine declines a layout; uploads never throw, so a driver failure only parks the engine with a logged error while the last texture stays exposed, and a Vulkan resolution change keeps the previous view until the first frame of the new layout completes. Software and headless engines expose a non-zero sentinel instead of a GPU handle: test the handle against 0 only. `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` and `frameBytes` give the size of each plane of an engine's `format()`, `width()`, `height()` and `bits()`, for example to budget its memory; subsampled planes round up like FFmpeg, so odd sizes keep their last chroma column and row. The OpenGL and Vulkan engines convert YUV with the matrix (BT.601, BT.709 or BT.2020), range and chroma position each video declares, guessing like mpv when it declares nothing (BT.601 for SD frames, narrower than 1280 pixels and up to 576 lines tall; BT.709 otherwise), and show PQ and HLG HDR as SDR with BT.2020 colors mapped to BT.709 and highlights compressed so a 1000-nit peak reaches white. Engines on one OpenGL context or Vulkan device build that conversion once and share it, and it goes away with the last engine's release work: the task `release()` queues on the OpenGL render executor, or the `VKContext.retire` callbacks. Release every engine and let that work run before destroying the context or device. Software engines receive BGRA that FFmpeg converts with the declared matrix and range, without HDR tone mapping. OpenAL needs its original context current on every thread that drives the player, including the synchronization ticker; Java Sound is thread-safe and reopens a wider line when an audio chunk exceeds the line buffer, dropping the audio the old line still held.

## Spatial audio and Sound Physics Remastered

Create an OpenAL engine with `MediaAPI.alEngine(true)` before constructing the player. Spatial mode is fixed for the engine's lifetime: its channel table advertises mono, so FFmpeg's existing resampler downmixes stereo and surround sources before upload. The ordinary `alEngine()` factory uses normal channel negotiation without forcing mono; layouts unsupported by the device may still be converted to the closest available channel count. Java Sound returns `false` from spatial updates and does not expose a native source.

The engine starts with dry, listener-relative mono audio. Supply a `SpatialAudio` state to place it in the world. Passing `null` restores dry, listener-relative playback while retaining mono decoding. Volume, mute, speed, pause and synchronization remain independent of spatial settings.

```java
import org.watermedia.api.media.SFXEngine.SpatialAudio;

final ALEngine audio = MediaAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Run engine creation, spatial updates and release on the host's sound executor, with the original OpenAL context current. The host owns the device, context and listener; they must outlive every player using them. `FFMediaPlayer` uses private playback threads and currently requires the source's context to be the process-current context on those threads, as with ordinary `ALEngine` playback. A host that exposes its audio context only through thread-local binding is not supported by the player pipeline; binding that context on the caller's sound thread alone is insufficient. Native engine operations reject a different or absent context; they never switch or recreate the host's context. Release players before a device reload, then create new engines in the replacement context. `FFMediaPlayer.release()` interrupts and joins its playback pipeline before deleting the audio source; it must run before context destruction, not as a callback from that pipeline.

`SpatialAudio` validates finite positions representable by OpenAL floats, `0 < referenceDistance < maxDistance`, and finite nonnegative rolloff. The host's distance model determines how these parameters attenuate audio. The API does not change the global distance model, listener or source velocity. In particular, `maxDistance` is not a universal silence radius under every OpenAL model. Passing zero rolloff disables distance attenuation. Spatial state belongs to the local listener and is not included in the clock snapshot.

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
final MediaPlayer player = MediaAPI.player(mrl, () -> null, () -> MediaAPI.alEngine(true));
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

`CodecsAPI.reader` picks an internal reader from the leading bytes and returns it as an `ImageReader`. The PNG and GIF chunk structures in `api.codecs.common` are internal and keep their serializers for future animation writers. Run `gradle javadocInventory` from the project root to list missing public-method documentation with file names and line numbers; the task only reports findings.

```java
try (ImageReader reader = CodecsAPI.reader(encodedBuffer)) {
    PixelFormat format = reader.pixelFormat();
    while (reader.hasNext()) {
        ByteBuffer pixels = reader.next();
        // CONSUME OR COPY BEFORE THE NEXT FRAME REUSES THE READER'S BUFFER.
    }
}
```

Every decoder delivers one tightly packed BGRA buffer per frame, except grayscale JPEGs, which deliver one luma byte per pixel (`GRAY`); `CodecsAPI.reader(buffer, format)` asks for `BGRA`, `RGBA` or `GRAY` and the reader converts when that is not its own layout, so `pixelFormat()` and `ImageData.format()` always name what the bytes hold, and any other layout is rejected with `IllegalArgumentException`. `hasNext()` and `next()` follow iterator rules: `next()` past the last frame throws `NoSuchElementException`, `hasNext()`, `next()`, `reset()` and `readAll()` on a closed reader throw `IllegalStateException` while the descriptors stay readable, and `close()` is idempotent and the only method safe to call from another thread. `delay()` is the delay of the frame `next()` just returned, `repeat()` counts total plays (`ImageData.REPEAT_FOREVER`, 0, loops forever), `variableFrameRate()` is false for a single frame, and `metadata()` returns a frozen snapshot that never changes under the consumer. JPEG and WebP frames come out already rotated into their EXIF orientation, with `width()` and `height()` swapped when it calls for it. Metadata uses plain Java values under shared keys (`CodecsAPI.METAKEY_ICC_PROFILE`, `METAKEY_EXIF`, `METAKEY_XMP`, `METAKEY_ORIENTATION`, `METAKEY_LOOP_COUNT`) plus per-format keys such as `PNG_METAKEY_*`; a malformed or oversized ancillary chunk is skipped and logged once, never fatal. A valid feature this decoder lacks (an unknown critical PNG chunk, a lossless or CMYK JPEG, an unknown PAM tuple type) throws `UnsupportedFormatException`, a `CodecException` subtype, so a host can tell "fall back" from "broken file". Netpbm covers the plain `P1`..`P3` and binary `P4`..`P7` variants. `readAll()` retains copies and enforces an aggregate decoded-byte limit for one image; `CodecsAPI.decode` also accepts a `ByteBuffer` or an `InputStream`, and `sniff` a `ByteBuffer`. The source buffer is shared with the reader, not copied, so it must not change until the reader closes, and when a reader refuses its body the buffer position is left where it was. Concurrent readers still consume independent memory: media players coordinate their decoding and reuse decoded images through their caches, while code that drives the decoders directly owns its concurrency.

`CodecsAPI.writer(...)` returns an experimental `ImageWriter` that encodes VP9 inside WebM; it requires FFmpeg, flattens alpha and tags the video BT.709 limited range (RGB input is converted with that matrix, YUV input is taken as already using it).

`BcReader` reads already compressed BC1/BC3/BC7 blocks in a DX10 DDS texture array, and `pixelFormat()` names their layout as `PixelFormat.BC1`, `BC3` or `BC7` (`PixelFormat.blockBytes()` gives the block size). Instantiate `org.watermedia.api.codecs.BcReader` directly for block access; `CodecsAPI.reader` does not dispatch DDS files. No native encoder is needed, but built-in OpenGL, Vulkan and software engines do not accept BC textures, so DDS block reading does not establish end-to-end player support. A WaterMedia animation footer supplies delays when present; bytes after the texture that do not start with the footer magic (the ASCII bytes `WMTC`) are ignored, and ordinary DDS slices have zero delay. A footer that starts with the magic but is truncated or inconsistent is malformed. Mip chains, volumes and cube maps are rejected. There is no BC encoder or codec-cache option. `CodecsAPI.available(...)` reports software pixel-decoder support, not GPU texture-format support.

## Synchronized playback

`MediaAPI.serverPlayer()` builds a `ServerMediaPlayer`, a clock without media or engines that runs on either side. It owns the time math: start, pause, seek, speed, repeat and the loop wrap or `ENDED` transition happen on the system clock, and every successful mutation bumps `revision()`. The host owns everything else: who may drive the clock, how its snapshots reach the clients and where its position is persisted. Engines and players have no public constructors: every instance comes from `MediaAPI`.

```java
ServerMediaPlayer clock = MediaAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = MediaAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

A player fed a snapshot through `sync(Sync)` (or `sync(ByteBuffer)` for the encoded form) follows the clock from then on: every 50 ms it mirrors the snapshot's status (start, pause, stop; a `BUFFERING` snapshot, which only a hand-built one carries, holds like a pause), adopts its repeat and speed and pulls playback back with a rate-limited `seekQuick` once `drift()` exceeds `tolerance(ms)` (one second by default). The latest snapshot fed always wins, so send them in the order the clock produced them; `authority()` returns that snapshot, `authorityTime()` extrapolates where the clock is now, and `following()` tells whether a player follows one. Correction never trims the playback speed, pauses while the player loads or buffers, and takes the short way around a loop boundary on repeating media. A control called on a following player applies at once and is undone by the next tick that disagrees with it, so route user controls to the clock through your own packets and let the snapshot carry the result; frame stepping is refused while following. Volume, mute, scaling and spatial audio always stay local.

The clock holds at zero until `syncDuration(ms)` or `live(true)` tells it what timeline it runs on: report the duration once from a client that opened the source (the first positive value wins for the clock's lifetime and divergent later values are ignored), and restore a persisted position with `syncTime(ms)` after starting the clock, since `start()` restarts from zero. `canSeek()` is false and `seek(...)` returns `false` until a duration is known and on a live session. `release()` freezes the position and leaves the clock `STOPPED` with a new revision, so persist and send that last snapshot before dropping it or the players keep extrapolating the previous one; release each player when its host session ends.

### Snapshot format

`Sync` is a fixed 27-byte big-endian record (version, revision, status, time, duration, speed, flags). `Sync.read(ByteBuffer)` consumes only those bytes, leaving trailing data for the host transport; the buffer's own byte order is irrelevant, and a rejected payload leaves the position untouched. Validate who sent a snapshot in the host transport: the library never identifies peers.

## Executable examples and diagnostics

The project logger names are `watermedia` and `watermedia_binaries`; configure those names for DEBUG diagnostics. Project log messages reduce media URLs to their origin, hide header values and mask bare access tokens, `Authorization` values, Minecraft session arguments and secret-looking `key=value` pairs (`DataTool.redact` applies the same filter to the app's log uploads). Exception traces with private URLs retain their type, stack, causes and suppressed failures as sanitized text. Nothing is aggregated per time window: an anomaly that can repeat, such as a short frame buffer, a refused layout, a failing callback or a rejected upload, is logged on its first occurrence in each engine, player, server or playlist and then stays silent, and slow playback iterations are counted and reported once when the run ends. FFmpeg still writes its own native log lines to the process error output, outside the project loggers and their redaction; routing them through the project logger is prepared but disabled until macOS and Linux are validated.

The source in `src/test/java/org/watermedia/ApiGuideExample.java` compiles factory usage for every graphics/audio engine and both sides of synchronized playback, the clock and a follower. Its image decoding example, which filters every frame through a `DownscaleFilter`, runs in `ApiGuideExampleTest`; GUI, audio-device and Minecraft behavior requires the corresponding host integration. The normal playback and spatial test suites exercise actual FFmpeg/OpenAL behavior separately.

Examples use host-provided variables and are integration fragments, not complete standalone applications. Jobs configured with `require_natives=true` require FFmpeg binaries instead of skipping their absence.
