# 📦 UPDATE 3.0.0.23 (RC)
## ⚡ MediaAPI
- 💥 BREAKING CHANGE (requires re-compile):`start()` and `startPaused()` return `boolean` instead of `void`. Every other control already answered whether it took effect, and the sync layer needs that answer from these two as well: an implementation now writes `if (!super.start()) return false;` before its pipeline work, and a bridged player reports `false` when the call went to the session instead of to the local pipeline. **Calling code does not change** — `player.start();` and `player::start` as a `Runnable` both still compile — but this is a binary break: anything compiled against 3.0.0.22 must be recompiled, or it fails at runtime with `NoSuchMethodError`
- ✨ Added: synchronized playback is now built into `MediaPlayer` — pass a **`Bridge`** (one method, `send(ByteBuffer)`, backed by a game packet, a socket, any byte carrier) to the constructor or `MediaAPI.createPlayer(...)`, feed what you receive into `player.sync(ByteBuffer)`, and that is the whole integration. Nothing about peer identity reaches WaterMedia: the bridge knows which session it serves and routes accordingly, so the natural shape is a small class holding that key: no polling loop, no state machine and no correction math on the developer's side. A bridged `ServerMediaPlayer` becomes the authority (registers spectators, broadcasts on change plus a ~5s heartbeat, applies control requests, sweeps clients that vanish); any bridged client player becomes a follower that replicates it and keeps itself aligned
- ✨ Added: `org.watermedia.api.media.players.sync` — a sealed `Packet` family with fixed-size versioned big-endian codecs, validated at decode (the trust boundary): `Sync` (29 B authoritative snapshot), `Config` (11 B), `Watch`/`Unwatch` (10 B), `Report` (20 B), `Control` (19 B). Decoding consumes only the packet's own bytes and leaves the rest, so a payload can travel inside a larger frame carrying the developer's routing fields
- ✨ Added: `Config.Capability` — the authority declares what the session grants and every follower learns it in the handshake: `LOCKSTEP` (while any ready spectator loads or buffers, the audience holds on a frozen clock and resumes exactly where it froze — failed clients ignored, and a mid-playback joiner never interrupts the others until it reports ready), `CONTROLS` (follower control calls travel upstream as requests and come back as authoritative state, making the API symmetric on both sides), `VOLUME` (volume/mute follow the authority instead of staying client-local). Followers read what was granted with `MediaPlayer.granted(capability)`
- ✨ Added: `MediaPlayer.role()` (`SOLO`/`AUTHORITY`/`FOLLOWER`) — the axis is not client versus server but who owns the truth, so a `ServerMediaPlayer` can be either side while players backed by real media can only follow
- ✨ Added: `MediaPlayer.authority()`/`authorityTime()`/`drift()` plus the `tolerance(ms)` tunable and `ServerMediaPlayer.watcherTimeout(ms)` — a follower keeps the last snapshot it heard and ages it into a live position (snapshots arrive seconds apart; a stale target would drag every correction backwards), then jumps to it with a `seekQuick` once drift passes the tolerance (1s default). Drift is circular on repeating media, corrections are rate-limited while a pipeline resettles, and none run while the local player loads or buffers
- ✨ Added: `ServerMediaPlayer.revision()`/`snapshot()`/`syncLive(boolean)` — every successful mutation (including the internal repeat-loop wrap and lockstep gate flips) bumps a monotonic counter that drives the automatic re-broadcast; on the receiving end an out-of-order snapshot is dropped while a heartbeat (same revision, fresher time) lands
- ⚙️ Changed: A player built with a `Bridge` no longer acts on its own control calls. `start`/`pause`/`stop`/`togglePlay`/`seek`/`seekQuick`/`speed`/`repeat` become requests to the session and return `false` when local state was left untouched; `previousFrame`/`nextFrame` are refused outright, since stepping frames against a synced timeline is meaningless. This only affects players you deliberately bridge — built without one, they behave exactly as before
- ⚙️ Changed: `speed(float)` now rejects `Float.NaN`. It used to pass validation (`NaN <= 0` and `NaN > 4` are both false) and poison the playback clock and the audio engine; only `TxMediaPlayer` guarded against it, so every FFmpeg player was exposed
- ⚙️ Changed: `TxMediaPlayer` no longer shadows the base `speed` field nor reimplements its validation — it calls `super.speed(...)` and only rebases its own passive clock, closing the segment at the outgoing rate so a rate change never retroactively rescales time that already passed
- ⚙️ Changed: the sync flow never touches the game thread — inbound payloads are decoded and validated on whatever thread hands them to `sync()`, and everything they cause runs on the shared 50ms daemon ticker
- ⚙️ Changed: `ServerMediaPlayer.syncDuration` is now first-wins per session — mixed-quality clients report durations differing by a few ms, and last-wins moved the loop modulo on every report, desyncing everyone; divergent reports (>500ms) are logged and ignored
- ⚙️ Changed: `ServerMediaPlayer.seek()` on an ENDED/STOPPED clock lands PAUSED at the (duration-clamped) position instead of staying dead — scrubbing semantics, matching VLC/mpv
- ⚙️ Changed: `ServerMediaPlayer` mutators are `synchronized` (network/game threads vs the shared ticker); `time()`/`status()` reads stay volatile lock-free
## ⚡ CodecsAPI
- 🐛 Fixed: every decoder capped each axis but never the pixel product, so a header of a few dozen bytes forced gigabyte allocations before any pixel data was read (`OutOfMemoryError`); PNG/JPEG/GIF/NETPBM/DDS now share WEBP's total-pixel cap, and `ImageReader.readAll` bounds what one animation may retain
- 🐛 Fixed: PNG compressed text (`zTXt`/`iTXt`/`iCCP`) spun forever at 100% CPU on a zlib stream requesting a preset dictionary — the inflate loop never checked `needsDictionary()`, so it never made progress and never hit its own size cap
- 🐛 Fixed: progressive JPEG accepted refinement scans with no preceding first-pass scan and an unlimited scan count, letting a small file burn minutes of CPU; scan ordering is now tracked per coefficient and the scan count is capped
- 🐛 Fixed: JPEG accepted duplicate frame headers and read table data past its own declared segment length
- 🐛 Fixed: VP8L declared pixel and huffman-group counts its bitstream cannot encode, and the VP8 bool decoder kept decoding from implicit zero bytes past the end of its partition
- 🐛 Fixed: WEBP XMP metadata was scanned quadratically, retaining one copy of the same element per iteration
- 🐛 Fixed: SVG rasterization was quadratic in edge count, and non-finite values (`1e999`) drove curve flattening to full depth from a 200-byte file; geometry must now be finite and the fill sorts in `O(n log n)` under explicit budgets
- 🐛 Fixed: SVG entity expansion inherited JDK limits that differ by orders of magnitude across Java 17/21/25 — they are now set explicitly and the decoder owns its own element, path and gradient budgets
- 🐛 Fixed: NETPBM merged two numbers into a single dimension across a comment, disagreeing with every reference decoder, and an unbounded `TUPLTYPE` reached the log verbatim
- 🐛 Fixed: DDS block-count arithmetic overflowed to negative or zero sizes; it is now `long`-typed with dimension and array-size caps
- 🐛 Fixed: GIF metadata records accumulated without bound across `reset()`, and an LZW stream could reference a dictionary entry the current frame never defined
- 🐛 Fixed: malformed input escaped as `IllegalArgumentException`, raw `EOFException` or bare `IOException` across the codec tree, making a corrupt image indistinguishable from a transport failure; the contract is `XCodecException` throughout

# 📦 UPDATE 3.0.0.22 (BETA)
## ⚡ Core — Boot pipeline, modules & progress metrics
- ⚙️ Renamed: `WaterMediaAPI` → `WaterMediaModule` (moved to `org.watermedia`) — non-API boot processes extend it too now; the lifecycle (`load`/`start`/`release`) is `protected`, so only the `WaterMedia` orchestrator (same package) can drive it
- ⚙️ Changed: Binaries and Config are homologated as boot modules — `WaterMediaBinaries` and `WaterMediaConfig` extend `WaterMediaModule` and boot through the same registry, ordered `Binaries → Config → Codecs → Platform → Media → Network`; the hand-rolled pre-registry startup blocks in `WaterMedia.start` are gone
- ⚙️ Changed: the global `load()` pre-pass is interleaved per module (`load` → `start`), so config-dependent step counts (e.g. `NetworkAPI`'s file-server step) read registered config values instead of compile-time defaults
- ⚙️ Changed: the FFmpeg bootstrap is centralized in `MediaAPI` (code-sectioning into the player served no purpose) — `FFMediaPlayer.load()/loaded()/loadError()/vulkanDecodeAvailable()` became `MediaAPI.ffmpegLoaded()/ffmpegError()/vulkanDecode()`; binaries extraction stays separate in the binaries module
- ✨ Added: three-level boot metrics on `WaterMedia`, names only — `step()/steps()/stepName()` (which module is loading), `taskStep()/taskSteps()/taskName()` (which element the module is loading), `work()/workTotal()/workName()` (units of the demanding task in flight: download/extraction bytes) — enough to emulate three loading bars; `currentAPI()`, `totalWorkSteps()` and `completedWorkSteps()` are removed, module instances are never exposed
- ✨ Added: `WaterMedia.failures()` — safe (non-fatal) boot failures as `Failure(api, step)` records (a failed FFmpeg load, binaries extraction, media cache init or file-server bind), surfaced as `[NO]` lines in the app's boot splash
- ✨ Added: FFmpeg extraction publishes real byte progress (compressed bytes read vs. the shipped zip size) through the binaries module's work bar; the boot splash shows the percentage on the active line
## ⚡ API — Sealing & extension hardening (security)
- ⚙️ Changed: `GFXEngine` is now `sealed` (permits `GLEngine`, `VKEngine`, `HeadlessGFXEngine`) and `SFXEngine` is now `sealed` (permits `ALEngine`, `JSEngine`) — the public `MediaPlayer`/`TxMediaPlayer`/`FFMediaPlayer` constructors take an engine instance, so sealing closes the injection gate: a caller can only pass WaterMedia's own engines, never a hand-rolled subclass, and can no longer reimplement an engine to work around a rendering bug that isn't WaterMedia's
- ⚙️ Added: `HeadlessGFXEngine` — a permitted no-GPU `GFXEngine` that records uploads into memory instead of a context (server-side probing, CI, headless validation); exposes `uploadCount()`/`lastUpload()`/`format()`/`activeFrame()` for pipeline introspection and replaces the former test-only stand-in
- ⚙️ Changed: every concrete API class a developer receives an instance of is now `final` so it can't be extended — the six `ImageReader`s (`GIF`/`JPEG`/`PNG`/`WEBP`/`NETPBM`/`SVGReader`), the four `NetpbmDecoder`s, `ImageMetadata`, `NetRequest`, `NetworkServer`, the four `WaterMediaAPI` facades (`Codecs`/`Media`/`Network`/`PlatformAPI`), and the built-in `IPlatform` implementations (`WaterPlatform` + every web platform)
- ⚙️ Changed: `XCodecException` is now `sealed` (permits `UnsupportedFormatException`) and `YtDlpPlatform` is now `sealed` (permits `YouTubePlatform`)
- 🔸 Note: `IPlatform` (registered by apps through `PlatformAPI.register`), `VKContext` (the modder Vulkan-device bridge), and the platform exception hierarchy (`PlatformException`/`MatureContentException`) stay open on purpose — they are documented extension points, not instances handed back to callers
- 🔸 Note: the unnamed-module boundary means `sealed` only compiles for same-package subclasses, so the cross-package abstract bases (`WaterMediaAPI`, `ImageReader`, `ImageWriter`, `NetpbmDecoder`) are hardened by finalizing their leaves instead — a developer subclass of the base is inert because WaterMedia never accepts one
## ⚡ CodecsAPI — JPEG decoder (security hardening)
- 🐛 Fixed: uncapped 16-bit SOF dimensions overflowed the coefficient/sample/BGRA int math — a few-byte header could wrap `new int[...]` to `0`/negative (`ArrayIndexOutOfBoundsException`/`NegativeArraySizeException`) or force multi-GB allocations (`OutOfMemoryError`); both axes are now hard-capped at 16384 (16K) and rejected with a clean codec error
- 🐛 Fixed: the scan header's 4-bit DC/AC Huffman table selectors (0..15) were used unchecked to index the `[2][4]` table array, so a selector of 4..15 threw an uncontrolled `ArrayIndexOutOfBoundsException`; selectors are now validated to 0..3
- 🐛 Fixed: a malformed DHT declaring an oversubscribed (non-canonical) Huffman code overflowed the fast-lookup table fill and value indexing with an uncontrolled `ArrayIndexOutOfBoundsException`; the table builder now rejects oversubscribed codes with a clean codec error
- 🐛 Fixed: a corrupt DC Huffman table could yield an out-of-range magnitude category (SSSS > 15) feeding a bogus bit count into the coefficient extend; the category is now validated to 0..15
## ⚡ CodecsAPI — GIF decoder (security hardening)
- 🐛 Fixed: uncapped 16-bit logical-screen dimensions overflowed `width*height*4` int math (`NegativeArraySizeException`) or forced multi-GB allocations (`OutOfMemoryError`) for the canvas and LZW index buffers; the canvas is now hard-capped at 16384 (16K) per axis and rejected with a clean codec error
- 🐛 Fixed: an animation frame whose image descriptor extends beyond the logical screen overflowed the `width*height` LZW allocation; frames are now bounds-checked against the canvas and rejected with a clean codec error
- ⚙️ Added: config `decoders.gif.clampImageDesc` (default off) — when enabled, an out-of-bounds frame is clamped to the canvas (best-effort, logged as WARN, may show artifacts) instead of failing the decode
- 🐛 Fixed: `ScreenDescriptor`, `ImageDescriptor` and `ColorTable` threw unchecked `IllegalArgumentException` on malformed data, escaping the `IOException` contract and crashing the caller; validation now surfaces as `XCodecException` at the parse boundary
- 🐛 Fixed: the sub-block buffer growth (`next <<= 1`) could overflow to a negative size (`NegativeArraySizeException`) on pathologically large inputs; growth is now overflow-guarded and bounded
## ⚡ CodecsAPI — PNG/APNG decoder (security hardening)
- 🐛 Fixed: APNG `fcTL` frame geometry was never validated against the canvas — a frame declaring a size larger than the canvas, or negative x/y offsets, drove out-of-bounds writes (uncontrolled `ArrayIndexOutOfBoundsException`) in the decode, blend and dispose paths; every frame is now bounds-checked (the existing `FCTL.validate` is now actually called, and hardened to `long` math so offset+size can't wrap past the check) and rejected with a clean codec error
- 🐛 Fixed: uncapped `IHDR` dimensions — the raw 32-bit width/height overflowed `width*height*4` int math (`NegativeArraySizeException`) or forced multi-GB allocations (`OutOfMemoryError`); both axes are now hard-capped at 16384 (16K) and rejected with a clean codec error
- 🐛 Fixed: IDAT/fdAT decompression bomb — inflation grew the output buffer unbounded, so a tiny image could inflate to gigabytes (`OutOfMemoryError`); output is now bounded to the exact size a well-formed frame decompresses to (computed as `long` to avoid overflow on 16K depth-16 frames) and aborts with a clean codec error past it
- 🐛 Fixed: zTXt/iTXt decompression bomb — compressed text metadata inflated with no output limit; both are now capped at 2 MB decompressed and throw past it
- 🐛 Fixed: indexed `bKGD` with a palette index beyond the `PLTE` (or with no `PLTE` at all) hit the unchecked `PLTE.getColor` with an uncontrolled `ArrayIndexOutOfBoundsException` — validated at construction (only reachable with `decoders.png.useBKGDChunk` enabled)
## ⚡ CodecsAPI — WEBP decoder (security hardening)
- 🐛 Fixed: uncapped 24-bit VP8X canvas and ANMF frame dimensions — a crafted file of a few bytes could declare up to 16777216×16777216, overflowing `width*height*4` int math and forcing multi-GB allocations (`OutOfMemoryError`) or uncontrolled `ArrayIndexOutOfBoundsException`; both are now hard-capped at 16384 (16K) per axis and rejected with a clean codec error
- 🐛 Fixed: lossless images using bundled palettes (16 colors or fewer) crashed with an uncontrolled `IndexOutOfBoundsException` (the image decodes at a reduced width but the palette inverse copied back at full width) — they now fail with a clean codec error; proper bundling decode is pending
- 🐛 Fixed: VP8 lossy frames declaring broken partition sizes (partition 0 or token partitions overrunning the chunk, truncated size table, empty partitions) escaped the `IOException` contract with uncontrolled `IllegalArgumentException`/`BufferUnderflowException` — sizes are now validated up front and fail with a clean codec error
- 🐛 Fixed: VP8L bodies shorter than the 5-byte header inside extended (`VP8X`) or animation (`ANMF`) containers crashed with an uncontrolled `IllegalArgumentException` when skipping the header (only the simple `VP8L` path validated it) — now rejected with a clean codec error
- ⚙️ Added: config `decoders.vp8.brokenTokens` (default off) — instead of failing, attempts to repair frames with broken token partition sizes: declared sizes are clamped to the bytes actually available and unusable partitions are replaced with a duplicate of the previous one or with fake zeroed data; decode continues best-effort and may show artifacts, every repair is logged as WARN
- 🐛 Fixed: the container-declared dimensions (VP8X canvas or ANMF frame) were never checked against the dimensions the embedded VP8/VP8L bitstream actually carries — output buffers are sized from the container, so a crafted mismatch overran the YUV plane copy (`IndexOutOfBoundsException`), the BGRA buffer limit (`IllegalArgumentException`) or the animation compositor (`ArrayIndexOutOfBoundsException`), and a *smaller* canvas silently delivered truncated pixels; both sides must now agree (as the spec requires) and mismatches fail with a clean codec error
- 🐛 Fixed: ANMF sub-chunk sizes were read as signed 32-bit and used unvalidated — a negative size made the sub-chunk cursor stop advancing (**infinite loop / thread hang**) or walk into negative indices (`ArrayIndexOutOfBoundsException`), and a huge size wrapped the bounds check; sizes are now validated with overflow-free math before use
- 🐛 Fixed: animation frames extending past the canvas were silently clipped while still decoding (and allocating) the full off-canvas frame; the spec requires frames to lie fully inside the canvas, so they are now rejected with a clean codec error — which also bounds every per-frame allocation by the validated canvas size
- 🐛 Fixed: the animation pre-scan crashed the constructor with an uncontrolled `IllegalArgumentException` on an odd-sized VP8X chunk truncated exactly at its end (the RIFF padding byte seek landed one past the buffer limit); the scan now clamps and degrades to a static-image summary
- 🐛 Fixed: the 16K-per-axis cap alone still allowed 16384×16384 declarations — ~1 GiB per pixel buffer and ~2 GiB on the animated path (canvas + output) from a few-byte header (`OutOfMemoryError`); a total-pixel cap (64 Mpx, e.g. 8192×8192) now rejects them at construction, and zero-dimension VP8 frames are rejected instead of producing empty buffers
- 🐛 Fixed: VP8L transform declarations were unbounded — repeating transform types let a tiny file queue thousands of full-image inverse passes (CPU exhaustion); each transform type may appear at most once per the spec and duplicates now fail with a clean codec error
- ⚙️ Changed: VP8L huffman group counts are now enforced through a feasibility gate — each group costs at least 20 bits, so a count the remaining bitstream cannot physically encode (e.g. a 1×1 entropy image declaring 65536 groups ≈ 200 MB of tables from a few-KB file) fails before allocating instead of after; counts past the recommended 256 still log a WARN
## ⚡ MediaAPI — Engines (rewrite: construction, naming & BCn pixel formats)
- ⚙️ Changed: engine creation is centralized in `MediaAPI` factories — `glEngine(thread, ex)`, `vkEngine(ctx)`, `jfxEngine(onFrame)`, `awtEngine(onFrame)`, `headlessEngine(preload)`, `alEngine([buffers])`, `jsEngine([bufferMs])`; every `Builder` class and `buildDefault()` is removed (they were one-argument wrappers around one-argument constructors)
- ⚙️ Changed: the client-side check moved into the sealed `GFXEngine`/`SFXEngine` base constructors — one enforcement point covering **every** engine (previously only `GLEngine`/`VKEngine` checked, each on its own), impossible to bypass even with a direct `new`; `HeadlessGFXEngine` is the sanctioned server-side exception via a package-private unchecked path
- ⚙️ Renamed (`GFXEngine`): `setVideoFormat` → `format(...)`, `supportsFormat` → `supports`, `supportsFrameTextures()` → `preload()`, `uploadFrameTextures` → `preload(frames, stride)`, `useFrameTexture` → `frame(index)`, `requiredBufferAlignment()` → `alignment()`, `releaseBuffer(buf)` → `release(buf)`, `bitsPerComponent()` → `bits()` — record-like naming, overload-paired with the getters
- ⚙️ Renamed (`SFXEngine`): `setAudioFormat` → `format(type, channels, rate)`, mirroring the GFX contract
- ✨ Added: `PixelFormat` now carries intrinsic layout data — `planes()`, `blockBytes()`, `compressed()` — and gains the block-compressed constants `BC1`/`BC2`/`BC3`/`BC5`/`BC7` ahead of the ISPC encoders, so BCn is a first-class pixel format instead of an external codec-only concept
- ⚙️ Removed: `GFXEngine#supportsCompressedTextures(String)` / `uploadCompressedFrames(ByteBuffer[], String, int)` — compressed textures ride the standard path now: `supports(PixelFormat.BC*)` + `format(BC*, w, h)` + `preload(blocks, 0)`, with `blockBytes` intrinsic to the enum; the `TxMediaPlayer` codec-cache replay maps the DDS codec id straight to its `PixelFormat` constant
- ⚙️ Changed: `GLEngine`'s five conversion shaders share one uniform scheme (`plane0..plane3`/`bitScale`/`uvSwap`/`outputWidth`) — undeclared locations resolve to -1 and are silently ignored per the GL spec, so a single compile/bind path replaces the three per-format switches (~25 uniform fields and ~150 duplicated lines dropped)
- ⚙️ Changed: single-use helpers aggressively inlined across both GPU engines (`GLEngine`: quad/PBO init, shader release, hub acquire/release, async frame-texture scheduling; `VKEngine`: `init`, `buildFormat`, `updateDescriptorSet`, `ensureComputePipeline`, the SPIR-V cache wrappers) — locals stay in one frame for the JIT and the read flow is linear; big cold well-named steps (`ensureYcbcr`, ring lifecycle) stay extracted
## ⚡ MediaAPI — Engines (VKEngine)
- ⚙️ Renamed: `VKContext#instance()` → `vkInstance()` and `VKContext#device()` → `vkDevice()` — avoids overloading Minecraft 26.x's `VulkanDevice#instance()` by return type alone (the JVM resolves by full descriptor, so the mixin-merged class is legal bytecode, but same-name methods muddy stack traces and crash reports); a mod can now implement `VKContext` directly on it through a mixin (MC's own `vkDevice()` accessor satisfies the interface verbatim) and pass the cast device straight to `MediaAPI.vkEngine`; the README documents the full pattern
## ⚡ MediaAPI — Engines (GLEngine)
- ⚙️ Removed: the 9 GL proxy callbacks (`setGenTexture`/`setBindTexture`/`setTexParameter`/`setPixelStore`/`setDelTexture`/`setActiveTexture`/`setBindVertexArray`/`setBindFrameBuffer`/`setBindBuffer`) and the `BindConsumer`/`TexParamConsumer` interfaces — the engine is now self-contained; `MediaAPI.glEngine(renderThread, renderThreadEx)` is the whole contract
- ⚙️ Added: exact GL state capture/restore around every upload wave — texture bindings, active unit, sampler bindings, pixel-store, PBO/ARRAY_BUFFER bindings, VAO, READ/DRAW framebuffers, program, viewport and draw toggles are read once and restored to their exact prior values, so any host-side skip-if-equal cache (Minecraft `GlStateManager`, Sodium/Iris trackers) stays truthful without integration
- ⚙️ Added: per-render-thread drain hub — all engines sharing a render thread drain through one batched task per frame inside a single capture/restore envelope, keeping the `glGet*` cost constant per frame (not per player) at any scale; a broken engine no longer aborts the rest of the wave
- ⚙️ Added: provably-safe deletion of exposed texture names — storage is freed immediately on release (zero-size respec) and the name is deleted the moment no texture unit still binds it (which, given exact state restore, proves no host binding cache references it), so a cached binding can never point at a driver-reused name; pending names are tracked per `GLCapabilities` identity so they never leak across recreated contexts
- 🐛 Fixed: the YUV→RGBA convert pass ran with whatever blend/scissor/colorMask/cull/polygon-mode/sampler state the host left behind — frames could come out clipped, blended or mis-sampled; the pass now forces a clean draw state and restores the host's exactly
- 🐛 Fixed: a queued upload draining after `release()` resurrected GL resources (leaked textures on a dead engine) — engines are now terminally flagged and stale render tasks become no-ops
- ⚙️ Changed: `GLEngine` construction now rejects a non-null `renderThread` without an executor instead of failing later on submit
## ⚡ MediaAPI — Engines (SFXEngine)
- ⚙️ Added: `JSEngine` — a native, dependency-free `SFXEngine` backed by the Java Sound API (`javax.sound.sampled`), playing decoded PCM straight through the OS mixer (WASAPI/DirectSound, ALSA/PulseAudio, CoreAudio) with no OpenAL context and no external library; a first-class alternative to `ALEngine`, usable even when OpenAL is available
- ⚙️ Added: stream-based `SFXEngine` backend — `format(...)` opens a fresh `SourceDataLine` (reopens on format change); uploads are non-blocking via `available()` backpressure, mirroring `ALEngine`'s buffer-pool contract so the clock keeps tracking the audible position through `pendingMs()` (`framesWritten − getLongFramePosition()`, rebased on `flush()`); volume via `MASTER_GAIN`; conservative U8/S16 mono/stereo capability tables so format negotiation always lands on an openable line
- ⚙️ Added: standalone app — an "Audio engine" selector (Settings → App → General) switches between `OPENAL` and `JAVASOUND`; the choice (`AppContext.audioEngine`) feeds the `Supplier<SFXEngine>` at player creation, so it applies to the next media opened
- ⚙️ Added: `SFXEngine#speed()` — reports whether the backend can change the playback speed (`ALEngine` → `true`, `JSEngine` → `false`), so players and UIs can honor engines stuck at 1.0× instead of desyncing against them
- ⚙️ Added: `MediaPlayer#canSpeed()` — `true` only when the source and the audio engine both allow a speed change (live streams and speed-less engines report `false`); `speed(float)` now refuses instead of scaling the playback clock against audio stuck at 1.0×, which caused unfixable stuttering on `FFMediaPlayer` under `JSEngine`
- ⚙️ Added: standalone app — the player screen's speed dropdown locks (not clickable, drawn dimmed) while the current media reports `canSpeed() == false`
- 🔸 Note: the Java Sound backend has no portable pitch/rate control, so `speed(float)` is a documented no-op (playback stays at 1.0×) and there is no per-source spatialization; use `ALEngine` when those are required
## ⚡ MediaAPI — Engines (Software: JavaFX / AWT)
- ✨ Added: `JFXEngine` — a `GFXEngine` that renders decoded frames into a JavaFX `Image`; the engine's direct BGRA buffer backs a `PixelBuffer` with no copy, so binding `image()` to an `ImageView` shows video after a single `updateBuffer` on the FX thread — a pure JavaFX app can embed WaterMedia with no OpenGL/Vulkan context
- ✨ Added: `AWTEngine` — a `GFXEngine` that renders into an AWT `BufferedImage` (`TYPE_INT_ARGB`) for Swing/AWT apps; uses only `java.desktop`, so it needs no extra dependency
- ⚙️ Added: shared `SWEngine` base (`sealed`, permits `JFXEngine`/`AWTEngine`) — accepts only single-plane BGRA/RGBA and declines every YUV/planar format, so the decoder's scaler pre-converts frames to BGRA; each frame is copied into a reusable direct buffer and published, firing an optional `onFrame` hook for repaint. `texture()` returns a non-zero sentinel (there is no GPU texture)
- ⚙️ Added: JavaFX is a **compile-only** dependency (openjfx 21 LTS, host-classified — matches the Java 21 runtime; a newer JavaFX would fail with `UnsupportedClassVersionError`) — WaterMedia compiles `JFXEngine` against it but never bundles it; a consuming JavaFX app supplies its own runtime, and `JFXEngine` loads FX classes only when instantiated
## ⚡ PlatformsAPI — Web platforms (Imgur)
- 🐛 Fixed: most Imgur links failed to resolve even though the API answered HTTP 200 — `/gallery/{id}` posts holding a single image (the most common kind on Imgur) carry the image fields directly on `data` instead of an `images[]` array and were rejected as "empty or unsuccessful"; both response shapes are now detected and resolved
- 🐛 Fixed: direct image links (`imgur.com/{id}`) crashed during JSON deserialization — the image endpoint answers `"ad_type": null` and Gson refuses `null` for a primitive record component; the data records now bind only the fields actually consumed, making them immune to Imgur's endpoint-dependent `null`s
- 🐛 Fixed: hidden albums (`/a/{id}` links never posted to the gallery) returned 404 — `/a/` links now resolve through the album endpoint instead of the gallery one
- ⚙️ Added: tag links (`imgur.com/t/{tag}/{id}`) resolve through the gallery flow; the legacy `#/t/` fragment detection (broken — `URI#getFragment()` never contains `#`) was removed
- ⚙️ Added: `www.imgur.com` / `m.imgur.com` hosts are claimed, title slugs and file extensions are stripped from ids, and URLs without a media id are ignored with a warning instead of hitting the API
- ⚙️ Added: NSFW posts and images now honor `platforms.allowMatureContent` and throw `MatureContentException` when disabled — same gate as Kick/Twitch
## ⚡ PlatformsAPI — Web platforms (Medal)
- ✨ Added: `MedalPlatform` — resolves **Medal.tv** game clips through the public `medal.tv/api/content/<id>` endpoint (no auth); claims every URL shape (`/games/<game>/clips/<id>`, any locale prefix like `/es/`, the short `/clip|/clips/<id>` links, and `?contentId=<id>`), exposes the HLS ladder (`source/540p/360p/240p`) as quality variants like Kick, with the progressive source MP4 and the social-share MP4 as ordered fallbacks, full metadata (title, description, author, duration, thumbnail, posted date) and URL-expiry honored; DMCA-takedown and login-walled clips throw a clear `PlatformException`
- 🔸 Note: Medal's `contentUrl<height>p` MP4 rungs are ignored — they serve the source file for every rung when no real ladder was transcoded, so the HLS master is used as the single source of truth for qualities
## ⚡ PlatformsAPI / Tools — Shared JSON tooling
- ⚙️ Changed: Imgur, Kick (channel/VOD/clip/search), Medal, Sendvid, Streamable and Twitter dropped their identical private fetch helpers and resolve through `fetchJson`; `NetRequest`, `PornHubPlatform`, `TwitchPlatform` and the standalone app (`AppContext`) dropped their private `new Gson()` copies and parse/write through `JsonTool`
## ⚡ App — AWT popup player (showcase)
- ✨ Added: opening an MRL can pop out a native AWT/Swing player window (`AWTPlayerWindow`) instead of the in-app GPU screen — a `MediaPlayer` driven by an `AWTEngine`, with an aspect-fit video surface and a minimal transport bar (play/pause, seek, volume, time). Showcases embedding WaterMedia with no GPU backend and exercises `AWTEngine` end-to-end inside the product
- ⚙️ Added: `AppContext.playerTarget` (`IN_APP`/`AWT`) with a segmented **Player output** control in the settings `engines` section (persisted in `watermedia-app.toml`); the `PLAYER` navigation branches to the popup when set to `AWT`. The window disposes on close without touching the host app, and its blocking player teardown runs off the EDT
## ⚡ GENERAL
- 🛠️ Minecraft/Sodium GL state can no longer be desynced by video playback — no `GlStateManager` wiring needed anymore, and the overhead stays flat no matter how many screens play at once
- 🛠️ Hardened the WebP, PNG/APNG, GIF and JPEG decoders against malicious files — crafted images can no longer force gigabyte memory allocations, decompression bombs or crash threads with unexpected exceptions; every malformed file now surfaces as a clean codec error
- 🐛 Fixed: Imgur links work again — gallery posts, albums (including hidden ones), single images and tag links all resolve now

# 📦 UPDATE 3.0.0.21 (BETA)
## ⚡ Packaging
- 🐛 Fixed: `gson` was being bundled inside the final WaterMedia jar — it leaked in as a transitive dependency of the `tools` module (declared there as `implementation`) and got shaded by the `include` configuration; `tools` now declares `gson` as a provided `library`, so the loader-provided copy is used and `gson` is no longer duplicated in the jar
- ⚙️ Changed: homologated the `tools` buildscript and `gradle.properties` with the `watermedia`/`binaries` conventions (`include`/`library` configurations, build-info logging, aligned JUnit BOM and `gson` versions)

# 📦 UPDATE 3.0.0.20 (BETA)
## ⚡ MediaAPI — Playback engine (FFMediaPlayer)
- ⚙️ Added: `AV_HWDEVICE_TYPE_AMF` (AMD) and `AV_HWDEVICE_TYPE_OHCODEC` (OpenHarmony) to the hardware decoder candidates
- ⚙️ Changed: `VULKAN` restored as a last-resort generic GPU decoder (kept last in the candidate list, after D3D11/D3D12 and the platform decoders); `OPENCL` stays excluded
- ⚙️ Fixed: AV1 software decode — FFmpeg's native `av1` decoder is hwaccel-only and emits zero frames without a GPU accelerator (video "ended" instantly); the software path now picks a real software decoder (`libdav1d`, else `libaom-av1`), on both the initial open and the HW→SW fallback
- ⚙️ Fixed: decoders with a reorder window (libdav1d, native av1) lost the tail of the stream / produced nothing on repeat — the video/audio decode loops now drain the decoder with a flush packet at a clean EOF, while an `abort()` teardown still drops the stale backlog
- ⚙️ Changed: a video stream that drains without ever emitting a frame now reports `ERROR` instead of `ENDED` — dead-decoder failures are visible and no longer retried forever by `repeat()`
- ⚙️ Added: `PacketQueue#endOfFile()` — distinguishes a clean end-of-stream from an `abort()` teardown so the decode threads know whether to drain or drop
- ⚙️ Removed: `FFMediaPlayer#pollVideoFrame()` / `pollAudioFrame()` — were public but internal-only (a second consumer was always a data race); inlined into the lifecycle consumption loop
- ⚙️ Changed: the resolved decoder is logged (`libdav1d` / `libaom-av1` / native) and `DEBUG` logging was added across the lifecycle (start/stop/pause/seek/quality/speed/release); `isHwAccel()` documented
## ⚡ [NEW] PlatformsAPI — Search
- ⚙️ Added: `PlatformAPI.search(String)` / `search(String, int limit)` — asynchronous, client-side search across every registered platform; returns a live `PlatformSearch` immediately and fills it off-thread (platforms probed concurrently, hits land in completion order, ≤ `limit` per platform — default 2), a newer search supersedes the previous one
- ⚙️ Added: `PlatformAPI.searchHistory()` — snapshot of recent queries (newest first, ≤ 10)
- ⚙️ Added: `PlatformSearch` — live handle (`query()`, `results()` immutable growing snapshot, `history()`, `done()`); a superseded handle simply stops growing
- ⚙️ Added: `PlatformResult(String platform, String title, URI thumbnail, URI url)` record — one raw, unresolved hit; the URL is resolved through `MRL`/`PlatformAPI.fetch(URI)` only when the user picks it
- ⚙️ Added: `IPlatform#search(String query, int limit)` default method (returns an empty list) — source/binary compatible; overridden by YouTube, Twitch, Kick, Imgur
- ⚙️ Added: in-memory result cache — identical `(limit, query)` searches served from memory; bounded LRU (32 entries), whole-cache sweep every `platforms.searchCacheCleanup` minutes; only completed, non-empty searches are cached
- ⚙️ Added: config `platforms.searchCacheCleanup` (minutes, default 15; `0` disables caching)
- ⚙️ Changed: `PlatformAPI#release()` now cancels the active search and clears history + cache
## ⚡ PlatformsAPI — Web platforms
- ⚙️ Added: `YtDlpPlatform` — drives the bundled **yt-dlp** binary out-of-process and maps its JSON into `DataSource`s; enables **SoundCloud**, **Facebook**, **Instagram** and **Newgrounds** (single videos + playlists, video/muxed/audio-only variants, audio-only sources, subtitles incl. auto-captions, thumbnails, metadata, mature-content gate, per-format headers/UA, URL expiry); hardened subprocess handling (hermetic, 120s timeout, both pipes drained)
- ⚙️ Added: `YouTubePlatform` — re-adds **YouTube** (removed with no replacement in a prior beta), backed by yt-dlp; separates video vs playlist links, and on the bot-check/age gate retries with a freshly minted **po_token** via the `rustypipe-botguard` binary; implements `search()` through yt-dlp `ytsearchN:` (`--flat-playlist`, playable videos only)
- ⚙️ Added: search support on **Imgur** (gallery search), **Kick** (channel search) and **Twitch** (inline GraphQL `searchFor`, no persisted hash)
- ⚙️ Changed: merged the old `platform-extension` module into WaterMedia — yt-dlp/botguard provisioning lives in `libs/binaries`, the platform integration here
- ⚙️ Changed: platform JSON parsing migrated to the shared `JsonTool` helpers (BiliBili, Odysee, TikTok, Twitch) — no behavior change
## ⚡ Packaging / licensing
- ⚙️ Changed: native libraries rebuilt — FFmpeg with AMD **AMF**, OpenHarmony codec support, **x264/x265** encoders and Vulkan, and yt-dlp + rustypipe-botguard provisioning (`libs/binaries`, `libs/tools`)
- ⚙️ Added: `META-INF/licenses/javacpp-LICENSE.txt` — bundles the JavaCPP / JavaCPP-Presets-FFmpeg JNI-bindings license (Apache 2.0); README points at the bundled license texts under `META-INF/licenses/`
## ⚡ GENERAL
- ✨ Search videos by keyword across YouTube, Twitch, Kick and Imgur — results stream in as you type, no need to paste a link
- ✨ YouTube is back, and SoundCloud, Facebook, Instagram and Newgrounds now play too — all through yt-dlp, with automatic bot-check bypass on YouTube
- 🛠️ Hardware video decoding now covers AMD GPUs (AMF) and OpenHarmony devices, with Vulkan used as a last-resort GPU decoder
- 🐛 Fixed: AV1 videos that played for an instant and then "ended" (or showed nothing) when the GPU has no AV1 decoder — they now decode in software
- 🐛 Fixed: the last moment of some videos getting cut off, and looping/repeat freezing, on certain AV1 / threaded decoders

# 📦 UPDATE 3.0.0.19 (BETA)
- 🐛 Fixed: Stall opening youtube videos 

# 📦 UPDATE 3.0.0.18 (BETA)
## ⚡ CodecsAPI
- ⚙️ Added: `ImageReader#reset()` — frame-0 rewind (GIF/PNG/APNG/WebP; other formats full re-open)
- ⚙️ Added: `CodecsAPI#available(String)` — codec-availability query (pure-Java codecs `PNG`/`JPEG`/`GIF`/`WEBP`/`NETPBM` always present)
- ⚙️ Added: `common.dds.DDSHeader` (DX10 DDS + per-frame-delay footer), `common.bc.BCCodec` (native seam, bindings pending)
## ⚡ MediaAPI — Animated images (TxMediaPlayer / GLEngine)
- ⚙️ Added: codec cache (BC over DDS) — `NetworkCache.Mode` (`DISK`/`CODEC`, from `media.txCodecCache`) + streaming `CodecWriter`; dormant until native BC ships (playback unchanged)
- ⚙️ Added: `GFXEngine#supportsCompressedTextures(String)` / `uploadCompressedFrames(ByteBuffer[], String, int)` — default-off BCn upload hooks
- ⚙️ Added: config `media.txCodecCache` (default off)
- ⚙️ Changed: Mode 2 gated by VRAM budget — `media.txFrameTexturesBudgetMB` (32 MB, 0 disables) replaces `media.txMultiTextureFrameThreshold`; 256-frame cap
- ⚙️ Changed: Mode 2 passive clock — no thread per animated image; frame resolved from wall time on `texture()`
- ⚙️ Changed: preloaded frame-texture sets upload progressively (no load hitch on long animations)
- ⚙️ Added: `GLEngine` latest-wins coalescing — ≤1 upload task queued, newer frames replace undrained
- ⚙️ Added: `GLEngine` persistent-mapped PBO ring (`ARB_buffer_storage` / GL 4.4); legacy PBO path as fallback
- ⚙️ Changed: hot-path `glGetError` off unless `-Dwatermedia.glchecks=true`
- ⚙️ Changed: loop/seek/step-back rewind via `ImageReader#reset()` (no disk re-read/decoder rebuild)
- ⚙️ Added: shared decode permit pool — bounds aggregate Tx decode CPU (simultaneous GIFs no longer starve game threads)
- ⚙️ Changed: paused streaming waits on signals (no 100 Hz poll); pause/seek/stop interrupt frame delays
- ⚙️ Changed: streaming memory — retention 6→2 buffers, pool trimmed while paused, prefetch cap uses real frame size
## ⚡ MediaAPI
- ⚙️ Added: `MediaPlayer#maxSize(int, int)` (+ `maxWidth()` / `maxHeight()`) — caps uploaded frame dims per axis (`min(native, cap)`, never upscales)
- ⚙️ Added: `MediaPlayer.LodLevel` (`MAX`/`CLOSE`/`NEAR`/`FAR`/`FAR_AWAY` = 100/75/50/25/10%) + `lod(LodLevel)` / `lod()` — % of capped dims; applies hot
- ⚙️ Added: `MediaPlayer#sourceWidth()` / `sourceHeight()` (native res pre-scale) via `scaledWidth(int)` / `scaledHeight(int)`
- ⚙️ Added: `FFMediaPlayer` per-frame downscale via `sws_scale` (`SWS_AREA`); keeps native pixel format when sws-supported, else BGRA
- ⚙️ Added: `TxMediaPlayer` Java area-average downscale before upload (Mode 3 on the fly; Modes 1-2 at prep)
- ⚙️ Changed: `MRL.preload(URI...)` moved to `MediaAPI`
- ⚙️ Removed (`MRL`): `reloadAll()`, `forgotten()`, `error()`, `expired()`
- ⚙️ Added: `MRL.Status` — `FETCHING` / `LOADED` / `EXPIRED` (manual reload) / `ERROR` / `BLOCKED` (mature-gated) / `FORGOTTEN` (renew via `MediaAPI.getMRL(URI)`)
## ⚡ MediaAPI — Playback engine (FFMediaPlayer / engines)
- ⚙️ Added: `SFXEngine#flush()` / `pendingMs()` (`ALEngine` via `AL_SOFT_source_latency`) — clock now tracks audible position
- ⚙️ Added: config `media.ffmpegHardwareAcceleration` + auto SW fallback when GPU transfer fails/exceeds budget (AMD D3D11VA stutter)
- ⚙️ Changed: audio fed eagerly (buffer pool = backpressure, no 2ms gate); `ALEngine` buffers 4→8 (~340ms)
- ⚙️ Fixed: stale OpenAL buffers after underrun/pause-resume (audio "slowed down"/repeated)
- ⚙️ Fixed: `pause()` never paused the source (drained/underran); `startPaused()` pause/clock-reset race
- ⚙️ Fixed: resampler-flush PTS yanked clock backwards/stalled — delay now compensated in output PTS
- ⚙️ Fixed: mid-stream audio param changes (chained OGG / Icecast) rebuild resampler; state dropped on seek
- ⚙️ Fixed: `AV_NOPTS_VALUE` timestamps (Ogg demuxer) corrupted clock — synthesized continuity
- ⚙️ Fixed: native use-after-free — `duration()`/`liveSource()`/`canPlay()` read cached snapshots; `release()` waits for pipeline
- ⚙️ Fixed: torn video frames (rotating buffer pool); stale `SwsContext` after resolution change (BGRA fallback)
- ⚙️ Fixed: `file://` broken on Linux/macOS; seek clamped to 0 without known duration; odd-height chroma copied one row short; stream indices re-resolved after reopen
- ⚙️ Removed: `VULKAN` and `OPENCL` from HW decoder candidates
## ⚡ PlatformsAPI
- ⚙️ Removed: `IPlatform#validate(URI)` (folded into `getData(URI)`)
- ⚙️ Changed: `IPlatform#getData(URI)` is now 3-state — `null` (not this platform) / throws (belongs but failed, or mature-gated) / `PlatformData` (success)
- ⚙️ Fixed: `PornHubPlatform` NPE on hostless URIs (e.g. `file://`) broke local-file playback — now returns `null` for a null host
- ⚙️ Changed: Enhanced logging and exception messages
## ⚡ GENERAL
- ✨ Animated images (GIF / WebP) are far lighter and smoother — most animations no longer stream frame-by-frame, there is no longer a background thread per animation, long animations no longer hitch while loading, and many animations playing at once no longer stutter the game or each other (lower CPU and VRAM all around)
- ✨ Videos can use less VRAM and bandwidth — mods can now cap the upload resolution or pick a distance-based level of detail, so distant or many simultaneous screens cost far less
- 🛠️ Smoother video on AMD GPUs — playback now falls back to software decoding automatically when hardware decoding stutters, and hardware decoding can be disabled from the config (`media.ffmpegHardwareAcceleration`)
- 🛠️ Changed default 
- 🐛 Fixed: local files (`file://`) would not play on Linux and macOS — local media opens again on those systems
- 🐛 Fixed: audio playing "slowed down" or repeating itself after a pause or a game hitch
- 🐛 Fixed: pausing did not actually pause audio (it kept draining and then cut out)
- 🐛 Fixed: live / streaming audio (chained OGG, Icecast radio) playing at the wrong speed
- 🐛 Fixed: torn or garbled video frames under heavy load
- 🐛 Fixed: occasional crashes and freezes while playing some streams

# 📦 UPDATE 3.0.0.17 (BETA)
## ⚡ Core / lifecycle
- ⚙️ Added: `org.watermedia.api.WaterMediaAPI` abstract base class — every top-level API now inherits `name()`, `load(WaterMedia)`, `start(WaterMedia)`, `release(WaterMedia)` plus boot-progress fields `step`/`steps`/`stepName` for loading screens
- ⚙️ Added: `WaterMedia#steps()`, `step()`, `currentAPI()`, `totalWorkSteps()`, `completedWorkSteps()` to surface boot progress
- ⚙️ Changed: APIs are now registered as `WaterMediaAPI` instances (`CodecsAPI` → `PlatformAPI` → `MediaAPI` → `NetworkAPI`), each driven through `load()` + `start()` with per-API try/catch
- ⚙️ Changed: `WaterMedia#start(name, ...)` now rejects blank names (`IllegalArgumentException`)
- ⚙️ Changed: `WaterMediaConfig.Decoders#defaultQuality` retyped from `MRL.Quality` to `org.watermedia.api.util.MediaQuality`
## ⚡ CodecsAPI
- ⚙️ Added: `ImageReader` — new abstract pull-based per-frame decoder (`Closeable`) with `width/height/pixelFormat/planeCount/plane/planeStride/scan/loopCount/frameCount/duration/delays/averageFps/variableFrameRate/metadata/readAll/hasNext/next`
- ⚙️ Added: `ImageWriter` — streaming frame encoder skeleton (`writeFrame(ByteBuffer)`, `writeFrame(ByteBuffer, long)`)
- ⚙️ Added: `ImageMetadata` — normalized metadata bag with typed accessors (`title`, `description`, `authors`, `copyright`, `comments`, `creationTime`, `software`, `source`) and free-form map
- ⚙️ Added: `PNG_METAKEY_*`, `GIF_METAKEY_*`, `WEBP_METAKEY_*` metadata key constants on `CodecsAPI`
- ⚙️ Added: `ImageData.Scan` record (`frameCount`, `delays`, `duration`, `loopCount`) with `Scan.EMPTY` constant
- ⚙️ Added: `UnsupportedFormatException extends XCodecException`
- ⚙️ Added: `CodecsAPI#getMediaType(InputStream)` — byte-signature sniffer returning a `MediaType` (resolves ambiguous `application/octet-stream` responses)
- ⚙️ Changed: `CodecsAPI#decodeImage(byte[]|ByteBuffer)` now declares `throws IOException` and returns an `ImageReader`. New overloads `decodeImage(ByteBuffer|byte[], PixelFormat requestedFormat)`. Unknown magic throws `UnsupportedFormatException` (was returning `null`)
- ⚙️ Removed: pluggable decoder registry — `CodecsAPI#register(ImageCodec)`, the `IMAGE_CODECS` list and the `org.watermedia.api.codecs.ImageCodec` abstract base (superseded by `ImageReader`)
## ⚡ MediaAPI
- ⚙️ Added: `MediaAPI#getMRL(URI)`, `MediaAPI#createPlayer(MRL, [int sourceIndex,] Supplier<GFXEngine>, Supplier<SFXEngine>)` — player factory now lives on `MediaAPI` (was on `MRL`)
- ⚙️ Added: `MRL#reloadAll()`, `MRL#subscribe(Consumer<MRL>)` (fires once and drops), `MRL#hasError()`, `MRL#exception()`, `MRL#forgotten()`, `MRL#blocked()` (true when gated by mature-content)
- ⚙️ Added: `MRL.Source#qualityOf(URI)` and a new `MRL.SlaveEntry(name, lang, uri)` record
- ⚙️ Added: `MediaPlayer.NO_SOURCE` constant
- ⚙️ Changed: `MRL#get(URI)` renamed to `MRL#getMRL(URI)`; `MRL#preload(URI...)` now returns `MRL[]`
- ⚙️ Changed: `MRL#sources()` / `MRL#sourcesByType(MediaType)` return `List<Source>` (was `Source[]`)
- ⚙️ Changed: `MRL.Source` rewritten as record `(MediaType type, URI thumbnail, Metadata metadata, RequestHeaders headers, EnumMap<MediaQuality,URI> qualities, List<SlaveEntry> audioSlaves, List<SlaveEntry> subSlaves)`; non-empty `qualities` invariant enforced
- ⚙️ Changed: `MediaPlayer` constructor now takes `(MRL, int sourceIndex, GFXEngine, SFXEngine)` instead of a resolved `Source`
- ⚙️ Changed: `MediaPlayer#quality(MediaQuality)` / `quality()` retyped from `MRL.Quality` to `MediaQuality`
- ⚙️ Changed: `MediaPlayer#audioSource()` returns `NO_SOURCE` (was `NO_TEXTURE`)
- ⚙️ Removed (from `MRL`): `invalidate(URI)`, `clearCache()`, `cacheSize()`, `error()`, `busy()`, `createPlayer(...)`, `createThumbnailPlayer(...)`, `sourceBuilder(...)`, `SourceBuilder`, `Source.withQuality/reassignQuality/withSlave/withMetadata/slaveByLanguage`
- ⚙️ Removed (from `MediaAPI`): `registerPlatform(...)` overloads and the internal `PLATFORMS` list (moved to new `PlatformAPI`)
- ⚙️ Relocated: `MRL.MediaType` → `api.util.MediaType`; `MRL.Quality` → `api.util.MediaQuality`; `MRL.Metadata` → `api.util.Metadata`
- ⚙️ Added: optional fast-path on `GFXEngine` — `supportsFrameTextures()`, `uploadFrameTextures(ByteBuffer[] frames, int stride)`, `useFrameTexture(int)`
- ⚙️ Added: `GLEngine.Builder` accepts four extra GL function consumers — `activeTexture`, `bindVertexArray`, `bindFrameBuffer`, `bindBuffer` — so a host can intercept GL state
- ⚙️ Removed: `GFXEngine.ColorSpace` inner enum — replaced by top-level `org.watermedia.api.util.PixelFormat`
- ⚙️ Changed: `colorSpace` parameters/fields renamed `pixelFormat` across `GFXEngine`/`GLEngine`/`FFMediaPlayer`
- ⚙️ Fixed typo: `MediaPlayer#foward()` → `forward()` (also in `ServerMediaPlayer`, `FFMediaPlayer`, `TxMediaPlayer`)
## ⚡ [NEW] PlatformsAPI (`api/platform/` package)
- ⚙️ Added: `org.watermedia.api.platform.PlatformAPI extends WaterMediaAPI` — registry with `static PlatformData fetch(URI)`, `static void register(IPlatform)`; iteration is reverse-registration so apps can override built-ins
- ⚙️ Added: `IPlatform` (new contract — `name()`, `validate(URI)`, `PlatformData getData(URI)`), `PlatformData(Instant expires, DataSource... entries)`, `DataSource(MediaType, URI thumbnail, Metadata, RequestHeaders, DataQuality[], List<DataSlave> audioSlaves, List<DataSlave> subSlaves)`, `DataQuality(URI, int width, int height)`, `DataSlave(name, lang, uri)`
- ⚙️ Added: `internal.WaterPlatform` — handles `water://local/remote/global` URIs (constants `HOST_LOCAL/REMOTE/GLOBAL`, `GLOBAL_SERVER`, `toHttpURL(URI)`)
- ⚙️ Relocated: `BiliBiliPlatform`, `ImgurPlatform`, `KickPlatform`, `PornHubPlatform`, `TwitterPlatform` moved from `api/media/platform/` to `api/platform/web/` and migrated to the new `PlatformData/DataSource/DataQuality` shape
- ⚙️ Removed: `api/media/platform/DefaultPlatform`, the old `api/media/platform/IPlatform`, and the old `api/media/platform/{Lightshot,Streamable,Twitch,Youtube}Platform`
- ⚙️ Removed: `YoutubePlatform` (no replacement in this beta)
## ⚡ NetworkAPI
- ⚙️ Added: `org.watermedia.api.util.NetRequest` — builder-style HTTP/FTP/file:// client (`create(URI|String)`, `Builder.method/accept/contentType/referer/userAgent/header/addHeader/body/connectTimeout/readTimeout/maxRedirects/headers/send`; `uri()`, `statusCode()`, `contentType()`, `contentLength()`, `header()`, `requestHeaders()`, `responseHeaders()`, `inputStream()`, `inputStream(Function)`, `readAllAsString()`, `download(Path)`, `json()`, `json(Class)`, `UserAgent` enum, `installExtraMimeTypes()`)
- ⚙️ Added: `org.watermedia.api.util.RequestHeaders` — insertion-ordered case-insensitive multi-value header bag (`set/add/get/getAll/has/removeAll/entries/iterator/toRawString`, `defaults(URI)` factory; FFmpeg-ready blob via `toRawString()`)
- ⚙️ Added: `org.watermedia.api.util.MediaQuality` enum (`UNKNOWN`, `Q144P`/`LOWEST`…`Q8K`) with `of(int)`, `of(int,int)`, `higher()`, `lower()`, `closest(Set, MediaQuality)`
- ⚙️ Added: `org.watermedia.api.util.MediaType` enum (`IMAGE/VIDEO/AUDIO/SUBTITLES/UNKNOWN`) with `of(String mimeType)` and `ofExtension(String)`
- ⚙️ Added: `org.watermedia.api.util.Metadata` record `(title, desc, Instant postedAt, long duration, author)`
- ⚙️ Added: `HlsTool#fetch(URI, String userAgent)` overload (internals migrated from `HttpClient` to `NetRequest`)
- ⚙️ Changed: `NetworkAPI` now `extends WaterMediaAPI`; `start(WaterMedia)` becomes an instance override; reports two boot steps (`MIME registry`, `FileServer`); also installs MIME mappings (webp, apng, mkv, opus, m3u8, mpd, vtt, NETPBM, …) into `URLConnection.getFileNameMap()`
- ⚙️ Changed: `NetworkAPI#upload(...)` return type is now `NetworkServer.UploadStatus` (was top-level `UploadStatus`)
- ⚙️ Removed (from `NetworkAPI`): `parseQuery(String)`, `waterURL(String)`, `parseWaterURL(URI)`, the static `WATER_HANDLER` field — `water://` URL handling is gone from the public API
- ⚙️ Renamed: `NetServer` → `NetworkServer` (`UploadStatus` moved inside as nested class)
- ⚙️ Removed: `WaterStreamHandler`, top-level `UploadStatus`, `NetTool` (replaced by `NetRequest`)
## ⚡ GENERAL
- ✨ Added TikTok platform support (with full Metadata and Multi-variant qualities)
- ✨ Added D.Tube platform support (with full Metadata and Multi-variant qualities)
- ✨ Added Bluesky platform support (with full Metadata, Gallery support and Multi-variant qualities)
- ✨ Added Odysee platform support (with full Metadata and Multi-variant qualities)
- ✨ Added VidLii platform support
- ✨ Added Sendvid platform support (with half metadata and status-poll wait)
- ✨ Added back Google Drive, Dropbox and MediaFire platform support (limited support due to platform restrictions)
- ✨ Rewrote and Enhanced Twitch platform: now covers VOD, live, **clips** and better codecs
- ✨ Rewrote and Enhanced Kick platform: now covers **clips** 
- ✨ Rewrote and Enhanced Streamable platform: both `mp4` and `mp4-mobile` qualities exposed as quality variants
- ✨ Rewrote Lightshot platform on the new platform API (produces `DataSource(MediaType.IMAGE, ...)`)
- ✨ Added on-disk media `NetworkCache` — two-tier cache (NETWORK live; CODEC reserved for upcoming BC7/DDS) with atomic writes, lock striping, expiry index (`WMIC` v3 format). Wired into `MediaAPI.start()` under `instance.tmp/cache`. Honored by `FFMediaPlayer` HTTP body fetches (config-gated, skips HLS/DASH manifests) and by `TxMediaPlayer`
- ✨ Rewrote `TxMediaPlayer` with three playback modes: (1) static one-shot upload, (2) pre-uploaded per-frame textures via the new `GFXEngine#uploadFrameTextures` fast-path when frame count ≤ `txMultiTextureFrameThreshold`, (3) streaming `ImageReader` decode driven by the playback clock with a bounded prefetch queue (64 MB budget) and direct-`ByteBuffer` pool. Supports seek/loop/step-backwards, falls into `BUFFERING` on under-run
- ✨ Added IPTV channel support — `m3u`/`m3u8` channel playlists are expanded into individual channel sources (title, group and logo per channel), with a bundled channel catalog
- ✨ Added a house-made JPEG decoder (pure Java, no `ImageIO` dependency; baseline + progressive, all common chroma subsamplings)
- ✨ Optimized PNG/APNG decoder (~40% faster on animated PNG) and added rich image metadata reporting (text, gamma, chromaticities, sRGB/ICC color profile, physical dimensions, timestamps, and ancillary chunks)
- ✨ Optimized GIF decoder (~12–24% faster); Netscape loop count now surfaced in metadata
- ✨ Optimized WEBP lossless decoder (~40% faster)
- ✨ Optimized WEBP lossy decoder (~2–14% faster); static lossy VP8 without alpha now decodes to native `YUV420P` planes (no RGB conversion)
- ✨ Added new config options: `decoders.maxImageSourceBytesMB` (128), `media.mrlManagerCleanupInterval` (60 min), `media.txMultiTextureFrameThreshold` (5), `media.txNetworkCache`, `media.ffmpegNetworkCache`, `media.ffmpegNetworkCacheMaxBytesMB` (10), `media.ffmpegAnalyzeDurationMs` (7000), `media.ffmpegProbeSizeMB` (10), `media.platforms.allowMatureContent`, `network.requestTimeoutMs` (15000), `network.maxRedirects` (10), `network.maxTextBytes` (16 MiB)
- ✨ Added JOML 1.10.8 as a library dependency
- ✨ [WaterMediaApp] Redesigned the entire standalone app on a new backend-agnostic 2D `RenderEngine`/`RenderSystem` (OpenGL 3.2 core backend; architected so Vulkan can drop in without touching widgets), vertex batching, rounded rects / circles / arcs / gradients / glow / shadow primitives
- ✨ [WaterMediaApp] Added more test cases, thumbnail previews, status badges.
- ✨ [WaterMediaApp] Enhanced Mouse experience on controls and buttons
- ✨ [WaterMediaApp] Added a `FrameLimiter` with monitor-aware pacing for drivers ignoring swap interval
- ✨ [WaterMediaApp] New `LoadingScreen` with animated boot splash and 8-frame duck animation, eased progress driven by `WaterMedia.completedWorkSteps()/totalWorkSteps()`
- ✨ [WaterMediaApp] Exit-confirmation dialog with `ENTER`/`ESC` bindings
- 🛠️ Changed: `MRL` `ready` state flips true on success **or** failure; callers must now check `hasError()`/`exception()` separately
- 🛠️ Changed: `FFMediaPlayer` HW-decoder priority — `D3D11VA` now precedes `D3D12VA`
- 🛠️ Changed: `FFMediaPlayer` quality auto-aligns — when initial quality is `UNKNOWN`, it is corrected once real video stream dimensions are known
- 🛠️ Changed: `FFMediaPlayer` open-failure logs now include decoded `av_strerror` text instead of swallowing the return code
- 🛠️ Changed: HTTP requests across `FFMediaPlayer`, `TxMediaPlayer`, and audio slaves now build headers from a unified `RequestHeaders.defaults(uri)` (no more hand-baked `User-Agent`/`Accept`/`Referer`)
- 🛠️ Changed: `MRL` source resolution delegated to `PlatformAPI.fetch(URI)`; loader threadpool switched to `ThreadTool.createRecomendedThreadPool(...)`
- 🛠️ Changed: ambiguous/wrong `Content-Type` (e.g. `application/octet-stream`) is now resolved by sniffing the leading bytes with a URL-extension fallback, so mislabeled media from CDNs no longer fails to open; non-HTTP requests force a real connection instead of letting the URL handler guess the MIME type and existence
- 🛠️ Changed: the MRL manager periodically forgets expired or errored MRLs to free memory (interval configurable via `media.mrlManagerCleanupInterval`)
- 🛠️ Changed: BiliBili CDN cookie/UA/Referer now flow through `RequestHeaders` on every `DataSource` (including live); `WaterMediaConfig.media.platforms.biliBiliCookie` honored at request time
- 🛠️ Changed: Mature content is now gated behind `media.platforms.allowMatureContent` (disabled by default) — Twitch (streams, VODs, clips) and PornHub throw `MatureContentException` before any data fetch; `MRL#blocked()` reports the gated state
- 🛠️ Changed: `NetworkServer.maxUploadSizeMB <= 0` now disables the size cap (was always enforced); `NetworkAPI.upload` honors `WaterMediaConfig.network.requestTimeoutMs`
- 🛠️ Changed: [WaterMediaApp] `AppBootstrap` quick-scan fast-path skips the GUI when all jars are cached; otherwise a console window with progress and 5-second auto-launch is shown; renamed `Sideloadable` → `Extension`, `BOOTSTRAPPED_FLAG` → `APP_FLAG`
- 🛠️ Changed: [WaterMediaApp] `HomeScreen`, `PlayerScreen`, `MRLSelectorScreen`, `OpenMultimediaScreen` rebuilt (`ConsoleScreen` and `SourceSelectorScreen` removed; `Grid` and `Selector` removed in favor of `ListView`/`StackContainer`)
- 🐛 Fixed: GIF decoder writing `0x00000000` at the transparent index even on frames whose GCE had no transparent-color flag (non-transparent GIFs were getting holes)
- 🐛 Fixed: NETPBM `PAM` `RGB_ALPHA` decode was emitting an extra `0xFF` byte between RGB and A, shifting every subsequent pixel
- 🐛 Fixed: `NetworkServer#handleUpload` no longer NPEs / throws `NumberFormatException` on missing or malformed `Content-Length` (returns 400)
- 🐛 Fixed: `NetworkServer` partial/aborted uploads are cleaned up — half-written file and its ID directory are deleted on mid-transfer `IOException`
- 🐛 Fixed: `TxMediaPlayer` reset/release no longer leaks lifecycle threads or the `ImageReader`
- 🐛 Fixed: implausible frame rates reported by some HLS streams (e.g. the 90 kHz clock) no longer break frame pacing — values outside a sane range are now rejected
- 🐛 Fixed: JVM crash on HLS streams whose audio/video parameters fail to probe (sample rate 0) — the stream is now torn down cleanly instead of dividing by zero

# 📦 UPDATE 3.0.0.16 (BETA)
- ✨ Added back [orange page] platform support with quality and metadata support
- ✨ Added back Lightshot (prnt.sc) platform support
- ✨ Added back Twitter(x) platform support with multi-source, quality and metadata support
- ✨ Added BiliBili platform support with quality and metadata
  - To unlock high quality, you must configure your cookie on "watermedia.toml" config file
- ✨ Added GPU procesing for more color formats and support for videos with 10, 12, 16 and 32 bytes per pixel
- 🐛 Fixed: GIF decoder doesn't decode alpha properly
- 🐛 Fixed: GIF decoder sometimes shows garbage pixels
- 🐛 Fixed: Non HTTP connections (file or ftp) fails on opening any MRL
- 🐛 Fixed: FFMPEG Players hanging on release
- 🐛 Fixed: JVM crash when release is invoked opening a MRL
- ⚙️ Changed: FFMediaPlayer#liveSource no longer flickers to true when media is still loading
- ⚙️ Breaking Change: added MRL as an argument on MediaPlayer constructor (also changes MRL.Source#createThumbnailPlayer signature)

# 📦 UPDATE 3.0.0.15 (BETA)
- 🐛 Fixed: Seek spams internally triggered when loop is enabled
- 🐛 Fixed: Logs doesn't show right jar version
- ⚙️ Changed: Removed -beta suffix to avoid issues with the bad (neo)forge version parsing
- ⚙️ Changed: ALEngine was refactored to match behavior with GLEngine
- ⚙️ Changed: Added ALEngine.Builder() to build a engine instance (engines are player exclusive, do not resuse instances)
- ⚙️ Changed: Added "defaultBuilder" to provide a default ALEngine builder with default settings
- ✨ Added support to IEEE (Float and Double buffers)

# 📦 UPDATE 3.0.0-beta.13
- 🐛 Fixed: Crashes on GL context with CORE_PROFILE (specifically on Minecraft 1.17+)

# 📦 BREAKING UPDATE 3.0.0-beta.12
- ✨ Improved 4K video performance on FFMediaPlayer
- ✨ Redesigned WaterMedia App with a tile-based layout
- ✨ Added audio slave support for FFMediaPlayer
- 🛠️ Improved general FFMediaPlayer stability
- ⚙️ Added: `GFXEngine`, a new abstract class for managing MediaPlayer texture buffers (preparation for Vulkan support)
- ⚙️ Changed: `GLEngine` now extends `GFXEngine`
- ⚙️ Changed: `MediaPlayer#texture` now returns a long (for OpenGL it can be casted to int for texture id, for Vulkan it will be a handle)
- ⚙️ Changed: `GLEngine.Builder` constructor now requires `Thread` (render thread) and `Executor` (render thread executor)
- ⚙️ Changed: `MediaPlayer` constructor now requires `GFXEngine` instead of `GLEngine`
- ⚙️ Changed: `MediaPlayer` constructor no longer accepts `renderThread` or `renderThreadExecutor` (also changes `MRL#createPlayer()` signature)
- ⚙️ Changed: Passing `null` as `GFXEngine` disables video rendering entirely instead of falling back to a default `GLEngine`
- ⚙️ Removed: `video` flag on `MediaPlayer` — pass `null` as `GFXEngine` to disable video
- 🐛 Fixed: Low framerate on FFMediaPlayer caused by certain muxing/encoding configurations

# 📦 UPDATE 3.0.0-beta.11
- ✨ Added: AppBootstrap.Sideloadable as a service interface to load watermedia's extensions/plugins on AppBootstrap initialization (standalone app)
- 🐛 Fixed: wrong video playback speed on media with slaves

# 📦 UPDATE 3.0.0-beta.10
- 🐛 Fixed: registering platforms after MediaAPI init causes DefaultPlatform always stay on top  

# 📦 UPDATE 3.0.0-beta.9
- ✨ Added ``water://`` protocol support on ``FFMediaPlayer``
- ✨ Added slaves support on ``FFMediaPlayer``
- ✨ Added method to override registered platforms on ``MediaAPI``
- 🐛 Fixed: HTTP file server issues
- ⚙️ Bumped waterconfig dependency

# 📦 UPDATE 3.0.0-beta.8
- ✨ Added ``water://`` protocol
  - ``water://local/<path>`` - opens files inside Current Working Directory (instance folder)
  - ``water://remote/<id>`` - opens files from the remote server using the media ID (requires watermedia on server-side)
  - ``water://global/<id>`` - opens files from the global remote server (allocated by SrRapero720). Not working yet
- ✨ PNG: Added ancillary chunks support
- 🛠️ Normalize by default the given URI string
- 🐛 Fixed: crashes on Java 17 (including MC versions using it)
- 🐛 Fixed: slow framerate on FFMediaPlayer when media has a poorly worked muxing
- 🐛 Fixed: AppBootstrap was not working... AGAIN

# 📦 UPDATE 3.0.0-beta.7
- ✨ New: Added support for webp decoding (with animated webp support)
  - This imageCodec is made in Java pure and does not require native libraries.
- ✨ New: Change ``FFMediaPlayer#seek(long)`` with accurate frame seeking, this may be slower on some formats.
  - Keyframe seeking (quick seek) its moved into ``FFMediaPlayer#seekQuick(long)``
- 🐛 Fixed: odd behaviors on FFMediaPlayer on pausing

# 📦 UPDATE 3.0.0-beta.6
- ⚙️ Workaround: APNG and GIF decoders test fail
- 🛠️ Change: DecodersAPI service, use instead static method #register()
- 🐛 Fixed: AppBootstrap wasn't working when opening the JAR
- See [PORTING-PRIME.md](https://github.com/WaterMediaTeam/watermedia-v3/blob/main/PORTING-PRIME.md) for details.
