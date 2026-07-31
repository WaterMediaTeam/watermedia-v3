# WATERMeDIA — Condensed Memory

State of the assistant's persistent memory as of **2026-07-27** (v3 beta, ~3.0.0.22–3.0.0.23).
Point-in-time observations, not live state: verify against current code before asserting as fact.

Layout: **§1** rules that govern how work is done · **§2** a flat map of changes already consumed
(`was → now`, no rationale — look here first before "restoring" an old name) · **§3+** live architecture,
where only load-bearing invariants and the reasoning that prevents a regression are kept.

---

## 1. Working agreements

- **Beta monolithization.** During v3 beta prefer aggressive inlining over surgical refactors requested by the user.
  Inline single-use private methods into their sole caller even when the caller grows a lot. Keep a helper only if
  genuinely reused or a clear shared abstraction over gnarly native code (`init*`, `*Loop`, `switchQuality`).
  Translate helper `return true/false` into `didWork = true; break;` / `break`. Always recompile.
- **Prefer records** for immutable data carriers (~10% JIT win: escape analysis / scalar replacement). Small
  abstract data hierarchies → sealed interface + record impls. Keep plain classes for mutable/stateful types or
  types that must extend a class.
- **Never `Atomic*`, always `volatile`** — two approved exceptions (do NOT "fix" them): `ThreadTool`'s
  `AtomicInteger` thread counters (`createStarted*` auto-appends `-N`, callers pass only `Origin-Task`) and
  `ServerMediaPlayer.revision` (user-approved 2026-07-30: `++` on a volatile is not atomic, `repeat()` bumps
  outside the player lock, and the bridge invariant forbids wrapping `super` calls in the lock).
- **Shell writes corrupt files in this environment.** `WriteAllText`/`Set-Content`/`Out-File`/`>`/`sed` produce a
  systematic single-char substitution (sometimes a stray NUL `0x20`→`0x00`). Make ALL content changes with
  `Edit`/`Write`; shell only for reads, `git mv`/deletes and builds. Detect via `Bin n -> m bytes` in
  `git diff --stat`; repair with a byte round-trip (`ReadAllBytes`/`WriteAllBytes`), then recompile. Pass this
  rule to every sub-agent that edits files.
- **Read the full API surface before adding wrappers** — e.g. `MRL.Quality.of(int)` already exists (delegates to
  `of(res,res)`); never reintroduce a manual `mapResolution`.
- **Version comes ONLY from the own-jar manifest** (`IOTool.jarVersion()` pins the class' own resource URL, never
  a flat-classpath `META-INF/MANIFEST.MF` lookup). Dev classes-dir runs legitimately show `3.0.0-unknown`.
  (The ffmpeg zips' internal `version.cfg` is unrelated and stays.)
- **Third-party licenses only for redistributed bytes**: shaded (`include`) deps, shipped natives, and
  runtime-downloaded binaries. NOT `library`-scope host-provided deps (gson/log4j/lwjgl/joml) and NOT first-party
  code (modloaders, binaries, waterconfig).
- **Web platform logging** (`api.platform.web`, uniform across all handlers): `Marker IT = getMarker(SimpleName)`
  + `import static WaterMedia.LOGGER`; INFO = resolution summary with counts (single-link scrapers use DEBUG);
  WARN = missing metadata + ignored/unrecognizable URLs; DEBUG = raw parsed values. Every explicit failure throws
  `PlatformException(XxxPlatform.class, msg)` (extends `IOException`), mature gating throws
  `MatureContentException`. `PlatformException` auto-prepends the class name — never repeat the brand in the
  message. Message = where + what + why (id/uri + HTTP status + cause hint).

---

## 2. Change map

Consumed changes. No context needed — only the mapping, so an old name is recognized and not restored.

### API & engines
| Was                                                                                      | Now                                                                                  |
|------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `WaterMediaAPI` (module base)                                                            | `WaterMediaModule`, lifecycle `protected`                                            |
| `currentAPI()` · `totalWorkSteps()` · `completedWorkSteps()`                             | the 3 metric triplets + `failures()`                                                 |
| `FFMediaPlayer.load/loaded/loadError/vulkanDecodeAvailable`                              | `MediaAPI.startFFmpeg/ffmpegLoaded/ffmpegError/vulkanDecode`                         |
| `new *Engine.Builder(...)` · `buildDefault()`                                            | `MediaAPI` engine factories                                                          |
| `setVideoFormat` · `setAudioFormat`                                                      | `format(...)`                                                                        |
| `supportsFormat`                                                                         | `supports(pf)`                                                                       |
| `supportsFrameTextures` · `uploadFrameTextures`                                          | `preload()` · `preload(frames, stride)`                                              |
| `useFrameTexture`                                                                        | `frame(i)`                                                                           |
| `requiredBufferAlignment`                                                                | `alignment()`                                                                        |
| `releaseBuffer`                                                                          | `release(ByteBuffer)`                                                                |
| `bitsPerComponent`                                                                       | `bits()`                                                                             |
| `activeFrameTexture`                                                                     | `activeFrame()`                                                                      |
| `supportsCompressedTextures(String)` · `uploadCompressedFrames(bufs, codec, blockBytes)` | deleted — BC\* is a `PixelFormat` on the standard `supports`/`format`/`preload` path |
| `FakeGFXEngine` (test double)                                                            | `HeadlessGFXEngine` (shipped, sealed permit)                                         |
| `VKContext.instance()` · `device()`                                                      | `vkInstance()` · `vkDevice()`                                                        |
| GLEngine `convert` flag + per-format uniform fields                                      | `conv != CONV_NONE` + one shared uniform scheme                                      |
| GLEngine's 9 `GlStateManager` callbacks + `BindConsumer`/`TexParamConsumer`              | deleted — `Env` envelope + `Hub` batching + orphan sweep                             |

### Players & sync
| Was                                                                                                             | Now                                                                     |
|-----------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| packed `targetSize` long                                                                                        | protected `scaleWidth`/`scaleHeight`/`lod`/`sourceWidth`/`sourceHeight` |
| `MediaPlayer.scaledWidth()/scaledHeight()`                                                                      | removed — call `MathUtil.scaled(...)` directly                          |
| FF BGRA-only sws fallback                                                                                       | one unified sws path                                                    |
| FF 2 ms just-in-time audio gate                                                                                 | eager upload + audible-position clock                                   |
| `pollVideoFrame` · `pollAudioFrame`                                                                             | inlined into `lifecycle` · `drainAudio`                                 |
| Tx Mode-2 5-frame threshold                                                                                     | VRAM budget (`media.txFrameTexturesBudgetMB`)                           |
| AV1 via default `avcodec_find_decoder`                                                                          | `softwareVideoDecoder` → libdav1d > libaom-av1                          |
| sync `Consumer<ByteBuffer>` ctor arg · `bridge != null` checks                                                  | `Bridge` interface · `Role`                                             |
| `MediaSynchronizer` · `SyncClock`/ghost player · `applySnapshot` · `*Impl` hooks · snapshot queue · speed nudge | built and rejected — see §10                                            |

### Repo, build & binaries
| Was                                                                                                                  | Now                                                                                  |
|----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `libs/tools` submodule                                                                                               | merged in-tree at `org/watermedia/tools` (coordinate gone)                           |
| `libs/binaries` submodule                                                                                            | `./binaries` submodule + source union                                                |
| `NetTool`                                                                                                            | `NetRequest` (+ `NetRequest#download(Path)`)                                         |
| `builtJars/`                                                                                                         | `build/libs`                                                                         |
| Shadow 8.3.x                                                                                                         | Shadow 9.x (8.3.x breaks relocation on Gradle 9.3)                                   |
| build-stamped `watermedia/version.cfg`                                                                               | manifest `Implementation-Version` only                                               |
| ffmpeg LGPL classifier                                                                                               | `<classifier>-gpl`, CLI exes dropped                                                 |
| from-source ffmpeg CI as the shipping path                                                                           | bytedeco natives mirrored + repacked locally                                         |
| `YtDlpClient` · `YtDlpExtractor` · `BotGuardClient` · `BotGuardException` · `ytdlp/manifest.json` · `NativeBinaries` | deleted — logic inline in `YtDlpPlatform`/`YouTubePlatform`, latest-release tracking |

### Standalone app
| Was                                                                                               | Now                                                    |
|---------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `AppChrome` · `Colors` · `ui.Dimension` · legacy `Screen`/`ViewScreen`/`LegacyHost`/`element.Root` | deleted — retained tree, drawing only through `Canvas` |
| global `ConfigStatus` + KeybindsBar CONFIG chip                                                   | per-spec `SaveMachine[2]` pip on each SpecTab          |
| separate engine + `playerTarget` selectors                                                        | `RenderMode {OPENGL, VULKAN, VULKAN_AWT, VULKAN_JFX}`  |
| standalone JavaFX `Sage`                                                                          | `JFXPanel` hybrid + `prism.order=sw`                   |
| `javafx-controls`                                                                                 | `javafx-swing`                                         |
| one engine shared across popup players                                                            | fresh engine per source (`PopupVideo.newEngine()`)     |
| app TOML `audio` section                                                                          | `engines` (old value not migrated, accepted)           |

### Rejected — never reintroduce
Blaze3DEngine or any Mojang/Minecraft API coupling · an immortal GL texture-name pool (textures must really be
deleted) · a test-only `GFXEngine` subclass · a codec registry / `BlockCompressor` in `CodecsAPI` · JSVG/Batik or
any `java.awt` in the codec path · audioReady-gated automatic AL↔JS fallback · helper classes for yt-dlp/botguard ·
deleting the PNG/GIF chunk write side as dead code.

---

## 3. Boot, modules & metrics

- Module lifecycle (`load`/`start`/`release`) is `protected` so only `WaterMedia` drives it. **Module instances are
  never exposed** — metrics are name/number statics, so no dev can anchor to an instance for bootstrap control.
- Registry order: Binaries → Config → Codecs → Platform → Media → Network, with `load()` interleaved right before
  each `start()` (no global pre-pass), so config-dependent step counts read registered config.
- 3 metrics: `step/steps/stepName` (module), `taskStep/taskSteps/taskName` (element), `work/workTotal/workName`
  (bytes; total 0 = inactive), plus `failures()` → `Failure(api, step)` for safe (non-fatal) failures.
- FFmpeg bootstrap is centralized in `MediaAPI.startFFmpeg()` — **never move it back into players**. Binaries
  extraction stays in the binaries module and publishes byte progress through the work fields.
- `build.gradle` puts `binaries/src/main/resources` on the TEST runtime classpath so `MediaBootstrap`-gated FFmpeg
  tests extract+load like production instead of skipping.

---

## 4. Repo layout & build

- **`binaries` is a git submodule at `./binaries`** (live remote). `rootProject.name` and the included-build dir
  are both `binaries`; `build.dependsOn gradle.includedBuild("binaries")`. CI checks out submodules recursively.
- **Codependency (source union):** watermedia compiles `binaries/src/main/java` in its own main sourceSet but
  excludes `org/watermedia/binaries/**` from its jar/sourcesJar; the binaries jar ships those classes + xz
  (shaded, relocated to `org.watermedia.libs.xz`) + the native ffmpeg zips. binaries `includeBuild`s the root.
- **Provider wiring (fragile):** watermedia is the sole provider —
  `configurations.compileOnlyApi.extendsFrom(configurations.library)`; binaries' `library` config sets
  `Usage.JAVA_API` so it resolves the apiElements variant. Without that attribute binaries picks the runtime
  variant and fails to compile.
- Versions/names/publish metadata live in `gradle.properties`, plugin versions in `settings.gradle`.
- **Standalone app data layout:** persistent → process CWD (`run/` in dev via the committed
  `.idea/runConfigurations/WaterMedia_App.xml`): `config/`, `logs/`, `watermedia/engine.cfg`,
  `watermedia/uiscale.cfg`, `watermedia/files/` (NetworkServer). Ephemeral → `java.io.tmpdir/watermedia`
  (dep `libs/`, media/network `cache/` shared with the mod — don't move it, yt-dlp cache).
  `AppBootstrap.ENGINE_FILE` and `RenderSystem.ENGINE_PREF_FILE` must stay the same CWD-relative path (the
  launcher's child JVM inherits the CWD). Mod bootstraps untouched.

---

## 5. Native binaries provisioning

### FFmpeg (`binaries`)
- Shipped zips mirror bytedeco's prebuilt GPL natives: download `org.bytedeco:ffmpeg:8.0.1-1.5.13:<classifier>-gpl`,
  extract `org/bytedeco/ffmpeg/<classifier>-gpl/*`, drop the `ffmpeg`/`ffprobe` CLI executables, set `version.cfg`
  = `8.0.1-gpl` (no newline), repack flat with Java `Deflater.BEST_COMPRESSION` (no zip/7z on this box; Java L9
  beats `Compress-Archive` by ~1 MB/zip).
- 5 zips named after `IOTool.platformClassifier()`: `ffmpeg-{windows,linux,macos}.zip` (x64, no suffix) and
  `ffmpeg-{linux,macos}-arm64.zip`. **No windows-arm64** — bytedeco publishes no such classifier.
- `VersionTool.compareTo` ignores the `extra` qualifier, so `FFmpegBinaries.start` also re-extracts when
  `compareTo == 0 && !Objects.equals(extra)`. `FFmpegBinaries` is the only consumer of `version.cfg` +
  `VersionTool.atLeast` — don't touch shared `VersionTool`.
- Why the mirror and not from-source: bytedeco static-links codecs, our builds need rpath which Windows lacks
  (`jniavutil.dll: can't find dependent libraries`), and `./configure && make` never produces the JavaCPP jni glue.
  Any from-source job must curl the glue from Maven Central with `BYTEDECO_VERSION` equal to the `build.gradle`
  coordinate and sharing SONAME majors (FFmpeg 8.0: avcodec-62, avformat-62, avutil-60, swscale-9, swresample-6,
  avfilter-11, avdevice-62), and must pin SVT-AV1 to **v3.1.2** (later versions dropped
  `enable_adaptive_quantization`, which FFmpeg 8.0.1 still sets).

### yt-dlp / BotGuard
- **Provisioning lives in `binaries`** (`YtDlpBinary`, `BotGuardBinary`), integration in watermedia. Both track the
  LATEST release dynamically: yt-dlp via the GitHub releases API (asset by exact name, verified against
  `SHA2-256SUMS`), botguard via Codeberg/Forgejo (asset by SUFFIX, since the `-v<ver>` part moves). One binary + a
  `version` marker per `binaryDir(id)`; download only when `tag_name != cached`; unreachable API + cached binary →
  use it with a warning. botguard deletes `bg_snapshot.bin` on version change (snapshot format is binary-tied).
- Downloads go through `NetRequest`; file plumbing stays in `IOTool`
  (`os/arch/sha256/verifySha256/makeExecutable/move`); temp → verify → atomic move. `xz` is bundled unrelocated for
  botguard's `.tar.xz`. Harness: `gradle binariesProbe`.
- watermedia side: `YtDlpPlatform` holds the whole engine inline (`info()`, `extract()`, shared
  `protected static runProcess()`); `YouTubePlatform extends YtDlpPlatform` adds inline BotGuard `mintToken()`,
  InnerTube `fetchVisitorData()` and the po_token retry. `runProcess` drains stdout AND stderr on threads before
  `waitFor` (a blocking read wedges the single-threaded search executor); `mintToken` is serialized on a lock
  (shared `--snapshot-file`).

---

## 6. Media API engines

- **Construction:** every engine comes from a `MediaAPI` factory — `glEngine(thread, ex)`, `vkEngine(ctx)`,
  `jfxEngine(onFrame)`, `awtEngine(onFrame)`, `headlessEngine(preload)`, `alEngine([buffers])`, `jsEngine([bufferMs])`.
  Client-side enforcement lives in the **sealed base ctors** (`GFXEngine()`/`SFXEngine()` →
  `WaterMedia.checkIsClientSideOrThrow`), so even a direct `new` is gated; `HeadlessGFXEngine` is the only unchecked
  engine (package-private `GFXEngine(boolean)`). Engine unit tests need `MediaBootstrap.client()`.
- **Sealing closes the ctor injection gate** (the player ctors take engine *instances* — that is the real attack
  surface a downstream dev abused): `GFXEngine` permits `GLEngine, VKEngine, HeadlessGFXEngine, SWEngine`;
  `SFXEngine` permits `ALEngine, JSEngine`; `XCodecException` permits `UnsupportedFormatException`; `YtDlpPlatform`
  permits `YouTubePlatform`; `MediaPlayer` was already sealed. **Any new engine must join the `permits` clause.**
  Finalized leaves: 6 `ImageReader`s, 4 `NetpbmDecoder`s, `ImageMetadata`, `NetRequest`, `NetworkServer`, the 4
  `WaterMediaAPI` facades, all `IPlatform` impls, `APSReader`. **Left open on purpose:** `IPlatform` (custom
  platforms via `PlatformAPI.register`), `VKContext` (modder bridge), `PlatformException`/`MatureContentException`.
- **BCn is a `PixelFormat`**: `planes()`, `blockBytes()`, `compressed()` + `BC1(8)/BC2(16)/BC3(16)/BC5(16)/BC7(16)`.
  New pixel layouts put their intrinsic data in the enum, never in side-channel parameters.
  `TxMediaPlayer.tryCodecTextures` maps the DDS codec id via `PixelFormat.valueOf(bc.version())` while keeping its
  own `pixelFormat` as BGRA (player-side view only).
- **`HeadlessGFXEngine`** is the sanctioned no-GPU engine (server/CI + introspection: `uploadCount()`,
  `lastUpload()`, `lastFormat()`, `activeFrame()`).
- **Software engines:** `SWEngine` (sealed, permits `JFXEngine, AWTEngine`) is the CPU sink — accepts only
  BGRA/RGBA (so the decoder converts), copies into a reusable direct BGRA buffer, sentinel `texture()`.
  `JFXEngine` wraps it in a JavaFX `PixelBuffer`+`WritableImage` (zero-copy, `updateBuffer` via `Platform.runLater`);
  `AWTEngine` converts BGRA→ARGB into a `BufferedImage`. JavaFX is **compileOnly, openjfx 21 LTS** (newer =
  `UnsupportedClassVersionError` on the Java 21 runtime) and never bundled.
- **GLEngine shader scheme:** the 5 fragment shaders share one uniform set
  (`plane0..plane3`/`bitScale`/`uvSwap`/`outputWidth`); undeclared locations are -1 and `glUniform*` ignores them,
  so one compile/bind path (`programs[]`/`uniforms[][]` by CONV_\* kind) serves all.

---

## 7. Vulkan

### VKEngine (library)
- Zero-copy = **host-pointer import** (`VK_EXT_external_memory_host`) of FFmpeg's decoded host buffer as a
  `VkBuffer` + `vkCmdCopyBufferToImage`, cached by pointer, falling back to host-visible staging.
  `GFXEngine.alignment()` (default 0, GL-safe) makes `FFMediaPlayer.planePool` allocate page-aligned buffers so
  import engages; it engages only for `position()==0` buffers with `capacity % minAlign == 0`.
- YUV→RGBA via ONE **compute** pipeline (7 modes, BT.709 ported from GLEngine); BGRA/RGBA passthrough by direct
  copy. Triple-buffered slots + fences; `texture()` returns a `VkImageView` already in `SHADER_READ_ONLY_OPTIMAL`.
  SPIR-V compiled at runtime via **lwjgl-shaderc**.
- **Sampler-YCbCr path (VK 1.1 core)** for 8-bit NV12/YUV420P/422P/444P with even extents: planes copy into ONE
  multiplanar `VkImage`, a `VkSamplerYcbcrConversion` immutable sampler (BT.709 narrow, MIDPOINT-else-COSITED,
  LINEAR only with both filter bits) converts in HW, and a trivial compute (`textureLod` only — `texelFetch` is
  illegal through ycbcr samplers) writes the managed RGBA image so `texture()` stays an RGBA view (**never expose
  YUV views** — it would leak per-format immutable-sampler pipelines into consumers). Gated by
  `VKContext.ycbcrSampler()` + per-format properties. Stays on compute: NV21, YUVA, >8-bit, YUYV, GRAY, any gate
  failure. Ycbcr objects are format-specific: destroyed in `format(...)` after drain and in deferred
  `destroyResources`. Instance apiVersion = `max(1.1, min(loader, VK14))`.
- **Bridge:** `VKContext` is implemented by the *consumer*, which owns `VkInstance`/`VkDevice`/queue and lends
  them. Both sides submit to the SAME queue serialized by `VKContext.queueLock()`; a same-queue image barrier
  (dst=FRAGMENT_SHADER) replaces cross-thread semaphores. The engine never destroys context-owned objects.
- **Teardown ORDER (JVM-crash class of bug):** imported memory must be destroyed BEFORE the host buffers are freed
  or `vkFreeMemory` hits a dangling pointer → `EXCEPTION_ACCESS_VIOLATION`. So `release()` calls
  `destroyImportCache()` **synchronously** after `waitAllIdle` and defers only the output image/view via
  `ctx.retire(...)`. The invalidation primitive is `GFXEngine.release(ByteBuffer)` (default no-op; VKEngine drops
  that pointer's import, producer-thread only) — `FFMediaPlayer` calls it before EVERY `memAlignedFree`
  (`ensurePlane` growth and `freePlanePool`). **Never free an aligned plane buffer without `release(buffer)` first.**
- **Invariants:** (1) `format(...)` defers image/view destruction via `ctx.retire` exactly like `release()`;
  engine-private plane/staging dies immediately after `waitAllIdle`. (2) `texture()` reads AND writes
  `lastSeq/lastView` inside `queueLock`. (3) Slot-reuse barriers on the OUT image use `srcStage=FRAGMENT_SHADER`
  (WAR), not TOP_OF_PIPE. (4) Packed-YUV plane width is `(w+1)/2`. (5) `deleteTexture`'s `vkDeviceWaitIdle` runs
  under `queueLock`. (6) **VKEngine creation must stay cheap** (the MRL selector spins up many thumbnail engines):
  the conversion shader compiles lazily on first convert upload, passthrough-only engines never compile, SPIR-V is
  cached process-wide (`static volatile byte[] SPIRV`), no per-engine dummy image.
- `MediaPlayer.release()` releases `gfx` as well as `sfx` (releasing only `sfx` leaked every engine = real VRAM leak).

### App renderer (`bootstrap/app/render/vulkan/VulkanRenderBackend`)
- Implements `RenderBackend` + `VKContext` (swapchain, 2D pipelines per topology, per-frame descriptor pools +
  vertex buffer). **All engine branching lives in the render layer, never in the app** (no `ctx.vulkan` flag):
  `RenderSystem` owns `chooseEngine()`, `applyWindowHints()`, `attach(window)` (false → GL fallback), `present()`,
  `mediaEngineSupplier(thread, exec)`, `engineName()/vulkanActive()/vulkanAvailable()`.
- **Every player must take its `Supplier<GFXEngine>` from `RenderSystem.mediaEngineSupplier(...)`** — a screen
  calling `MediaAPI.glEngine(...)` directly crashes under Vulkan. Drawing must use
  `RenderSystem.bindMediaTexture(player.texture())` (64-bit) — an `(int)` cast truncates a `VkImageView` → white.
- Deferred destruction: retired destructors run after `MAX_FRAMES_IN_FLIGHT+1` frames (monotonic `frameCounter` +
  `ConcurrentLinkedQueue`, drained in `beginFrame`, fully on `cleanup` after `vkDeviceWaitIdle`).
- **`recreateSwapchain()` MUST hold `queueLock`** (its `vkDeviceWaitIdle` needs exclusive queue access vs
  decode-thread uploads; otherwise window mode switches corrupt device/swapchain state). The draw viewport uses the
  **swapchain extent**, not the window-size `viewport()`.
- No TRIANGLE_FAN pipeline (MoltenVK/portability rejects it) — `draw()` expands fans to a triangle list;
  `VK_KHR_portability_subset`/`_enumeration` enabled when advertised. `attach` cleans up a partially built backend
  on failure (surface must die before `glfwDestroyWindow`).
- Player release runs **off the render thread** (`AppContext.releasePlayer` chains each release thread behind the
  previous; `awaitPlayerRelease()` pumps the GL executor and joins before device teardown) because
  `FFMediaPlayer.release()` blocks ~250 ms on FFmpeg teardown. `TxMediaPlayer.release()` awaits `prepareActive == 0`
  (monitor-guarded int) — `Future.cancel(true)` alone races `gfx.release()`.
- LWJGL `Configuration.STACK_SIZE` is raised to 1 MB in `WaterMediaApp`'s static block (64 KB overflows when
  enumerating device extensions).
- `FFMediaPlayer.cleanup()` calls `avcodec_flush_buffers` before `avcodec_free_context` on the video decoder
  (a frame-threaded SW fallback interrupted mid-decode trips `av_assert0(fctx->async_lock)`). The hw→sw
  "GPU transfer too slow" fallback is counterproductive for 8K and can be provoked by resize stalls.

### Strategy & phasing
- **Agnosticism is non-negotiable**: watermedia must never depend on Minecraft/Mojang APIs — Mojang treats modders
  as non-load-bearing and would force chasing every release. MC integration is the modder's job: a mixin implements
  `VKContext` directly on Mojang's `VulkanDevice` and the consumer casts. Caveats vs 26.2 sources: MC's device lacks
  `VK_EXT_external_memory_host` AND `samplerYcbcrConversion` (→ staging + compute), and MC submits lock-free at
  `VulkanQueue.Submission#close`, so a second mixin must wrap it in `synchronized (device)`.
- **Priority: mature VKEngine; GL is the deprecated-tier secondary engine.**
- **Phase 2 (FFmpeg-Vulkan GPU→GPU) is BLOCKED by bindings:** `org.bytedeco:ffmpeg:8.0.1-1.5.13` exposes only
  generic `AVHWDeviceContext`/`AVHWFramesContext`, not `AVVkFrame`/`AVVulkanDeviceContext`. Vulkan hwdecode would
  only yield a GPU→RAM download, SLOWER than software decode + host-import — must NOT be preferred. Implemented:
  a one-time load-time availability probe.
- **Future plan — drop staging entirely** via `VK_EXT_host_image_copy` (core in VK 1.4; LWJGL 3.3.6 ships
  `EXTHostImageCopy`/`VK14`): `vkCopyMemoryToImage` reads any host pointer → no staging buffer, no command buffer,
  no submission for passthrough uploads, no alignment requirement, no import cache, and it **removes the
  import-vs-free teardown hazard entirely**. Gate per device (features2) and per format (`HOST_IMAGE_TRANSFER_BIT`
  via `vkGetPhysicalDeviceFormatProperties2`), add `HOST_TRANSFER_BIT` usage, check
  `VkHostImageCopyDevicePerformanceQuery.optimalDeviceAccess`. Fallback chain becomes
  hostImageCopy → host-import+copy → staging.
- **Known gaps / verify on hardware:** `supports(RGB/GBRA) = false` — `FFMediaPlayer` reroutes via sws but
  `TxMediaPlayer` has no upstream conversion, so an RGB/GBRA *image* renders blank on VK. Y-orientation uses a
  negative-height viewport with a deliberately non-flipped scissor (coupled one-line fixes if wrong on real HW).
  No Vulkan GPU in the build env — don't add a device-requiring VKEngine test to CI.

---

## 8. OpenGL: proxy-less GLEngine

- Self-contained through three pillars: (1) **`Env`** exact capture/restore envelope (the convert variant also
  saves draw toggles + samplers and forces a clean draw state for the FBO pass); (2) per-render-thread **`Hub`**
  batching every engine's drain into one task per frame inside one envelope, so glGet cost is per-frame not
  per-player (the 100-video concern); (3) **deferred provably-safe deletion of exposed texture names** —
  `orphanTexture` frees storage immediately (zero-size respec) and parks the name in `ORPHANS` (WeakHashMap keyed
  by GLCapabilities identity), and `sweepOrphans` runs at the tail of every `Env.restore()`: since the envelope
  guarantees host cache == actual GL state, "no texture unit binds the id" PROVES no host cache references it, so
  `glDeleteTextures` is then safe. Plane textures are never exposed → deleted raw immediately. Plus a volatile
  `released` terminal flag.
- Host-agnostic on MC 1.20.1: exact-restore preserves the `cached == actual` invariant of any skip-if-equal cache
  (vanilla GlStateManager, Sodium/Iris). Cost ~2–8 `glGet*` per drain; NVIDIA threaded-optimization sync risk.
- **MC 26.x:** `GlStateManager` 26.2 is public but internal to `com.mojang.blaze3d.opengl`; caches confirmed alive
  and grown (TEXTURES[12]+activeTexture, BLEND[8] indexed, COLOR_MASK[8] bitmask, NEW separate read/write FBO
  caching). `GlDevice`/`GlCommandEncoder`/`GlRenderPass` are package-private → fetch/restore is the only viable GL
  path. `GlTexture.glId()` is public. `VulkanDevice` is public but `GpuDevice.backend` is private (AT/mixin needed)
  and MC's VkDevice enables no `VK_EXT_external_memory_host` → VKEngine zero-copy import can never engage there.
  A hypothetical Blaze3D engine would need `writeToTexture` (no stride param → packed RGBA only; no BGRA format)
  and a fragment pass for YUV (no public compute dispatch), plus a new accessor since `texture()` returns `long`.
- **Repo gotcha:** `GlStateManager26_2.java` at repo root is byte-identical to `GlStateManager1_20_1.java` — it is
  NOT the real 26.2 class.

---

## 9. Players

### FFMediaPlayer — audio clock
- Audio is fed to the SFX engine **eagerly**; the engine buffer pool is the backpressure and `AUDIO_MAX_LEAD`
  (0.5 s) is only a bogus-PTS safety cap, NOT a pacing gate. The MasterClock is updated with the **audible**
  position: `framePts + duration − sfx.pendingMs()` (AL_SOFT_source_latency compensated).
- Why it must stay that way: a just-in-time gate keeps OpenAL at ~1 queued buffer → chronic underruns on game
  hitches, and OpenAL replays its queue from the start on `alSourcePlay` after a stop (the "OGG slowed/repeated"
  reports).
- Preserve: eager upload + engine backpressure, audible-position updates, full reclaim of processed AL buffers
  before re-queueing, `sfx.flush()` on serial change. ALEngine bookkeeping is lifecycle-thread-only; `release()`
  joins the lifecycle thread before freeing engines. Public getters (`duration()/liveSource()/canPlay()`) must
  never touch native structs.

### JSEngine (javax.sound.sampled)
- A native, dependency-free `SFXEngine`, **first-class user-selectable alternative to ALEngine, not an automatic
  fallback**. `enum AudioEngine {OPENAL, JAVASOUND}` carries the supplier; `AppContext.audioEngine` holds the
  selection.
- Capability tables are **static and conservative on purpose** (U8+S16, mono/stereo): `FFMediaPlayer.initAudio()`
  has no fallback when `format` returns false — it just loses audio. Don't widen to FLT/S32/multichannel;
  `formatFor()` still maps them for direct callers.
- **`speed(float)` is a deliberate no-op** (no portable Java Sound rate control, and honoring SAMPLE_RATE would
  break the `pendingMs()` frame math). Capability contract: `SFXEngine#speed()` → AL true / JS false;
  `MediaPlayer#canSpeed()` = `!liveSource() && (sfx == null || sfx.speed())`; `speed(float)` refuses when false
  (re-requesting the current speed is a success no-op). The app's speed dropdown is `enabled(p.canSpeed())`.
- `upload()` is non-blocking via `available()` (returns false = backpressure) — never blocking `write()` on the
  clock-driving thread. `pendingMs()` = `framesWritten − getLongFramePosition()`, and `flush()` MUST rebase
  `framesWritten`. `line/gainControl/gain/started` are volatile; `source()` returns 0.

### TxMediaPlayer + GLEngine render architecture
- Mode 2 (per-frame textures) is gated by a **VRAM budget** (`media.txFrameTexturesBudgetMB`, default 32 MB, hard
  cap 256 frames) — never by frame count.
- Mode 2 has **no lifecycle thread**: a passive clock resolves the frame inside `texture()` from wall time
  (`texTimeline` + `frameAt` binary search); pause/seek/step/speed rebase `clockBase`/`wallBase` under the `clock`
  lock; ENDED resolves lazily in `status()`/`time()`.
- GLEngine off-thread uploads are **latest-wins coalesced** (one volatile `pending` + at most one queued drain);
  engines must consume/drop a buffer before the 2nd subsequent upload returns; the player keeps `IN_FLIGHT_KEEP = 2`.
- **Persistent-mapped PBO ring** (`ARB_buffer_storage`): producer memcpys under `ringLock`, render thread only does
  texSub + fence, slots retire strictly in order; legacy double-buffered PBO is the GL < 4.4 fallback; ring-full
  drops the frame but still queues a drain so fences retire.
- Hot-path `glGetError` is gated behind `-Dwatermedia.glchecks` (forces driver sync with NVIDIA Threaded Optimization).
- Mode 3 loops/seeks rewind via `ImageReader#reset()` (GIF/PNG/WEBP) instead of re-reading the disk cache; decode
  CPU is bounded by a static `DECODE_PERMITS` semaphore.
- Preserve single-producer assumptions (one lifecycle thread per player), drain ordering (`drainQueued=false`
  before reading `pending`), in-order ring retirement.

### LOD / maxSize upload scaling
- `MediaPlayer` holds PROTECTED volatile `scaleWidth`, `scaleHeight` (0 = no cap), `lod`
  (`LodLevel MAX/CLOSE/NEAR/FAR/FAR_AWAY` = 100/75/50/25/10%) and `sourceWidth`/`sourceHeight` (written by the
  subclass when it learns decoded dims) — the extender owns them. Math lives in
  `MathUtil.scaled(native, max, percent)` = `min(native, max)` (cap, never upscale) then `× percent`, even-clamped
  `>= 2`. **Per-axis, no aspect preservation.** Quality changes re-cap automatically. LOD applies hot, before the
  GPU upload.
- FFMediaPlayer: one sws path — active when `mapping == null` or target != source; keeps the native pixel format
  when `sws_isSupportedOutput` allows (fewer bytes than BGRA), else BGRA; `SWS_AREA` for downscale,
  `SWS_FAST_BILINEAR` for pure conversion; if the scaler can't init but the format uploads natively, degrade to
  unscaled native (don't drop frames).
- TxMediaPlayer: `applyTarget()` writes `outWidth/outHeight` (prepare/lifecycle thread only); `PrefetchedFrame`
  carries its own dims so frames queued before a hot LOD change drain at their old size; YUYV is not scalable;
  Modes 1–2 apply the target at prepare only.
- **Area scaling is centralized in `DataTool`** (`frameBytes(cs,w,h)` / `scalable(cs)` / `scaleArea(src,sw,sh,dst,dw,dh,cs)`
  + private `scalePlane`) — the SAME code path Tx used inline and the `ReaderProxy` downscale share; `scalable`
  excludes YUYV/YUYV2 **and BCn**. Tx must NOT route its scaling through a `ReaderProxy` (it keeps the source
  buffer and scales on upload); both just call the `DataTool` utility. Don't re-duplicate the plane math.
- **NEVER call `gfx.format(...)` without an immediately following upload** — it resets engine state and would blank
  the displayed frame (e.g. while paused).
- Tests: `TxScalingTest` (no natives) + `FFMediaPlayerTest` (gated by `MediaBootstrap.ffmpegAvailable()`).
  `MediaPlayer` is sealed → assert through concrete players. Note `DataTool.endsWith` is NOT null-safe.

### AV1 decoder selection
- The native FFmpeg `av1` decoder is a **hwaccel-only stub in software** (0 frames / send-errors), and
  `avcodec_find_decoder(AV1)` returns it on arm64 while x64 returns libaom — so **never rely on the default decoder
  for AV1 software decode**. `softwareVideoDecoder` prefers `libdav1d` then `libaom-av1` by name on the SOFTWARE
  path only (the HW path keeps the native decoder as hwaccel host).
- Decode loops **drain at clean EOF** (send a null packet before exiting; `PacketQueue.eofReached()` distinguishes
  EOF from abort) — a bare `break` + `avcodec_flush_buffers` discards buffered frames.
- A video stream that drained with zero rendered AND zero skipped frames transitions to **ERROR, not ENDED**.
- `initVideo` logs the actual decoder name (the descriptor long_name doesn't reveal which decoder ran).

---

## 10. Synchronized playback

- Lives **inside `MediaPlayer`** (`api.media.players.sync`). Whole dev integration = a **`Bridge`** (one method
  `send(ByteBuffer)`) in the ctor + `player.sync(ByteBuffer|Packet)`. No polling loop, no correction math on the
  dev side.
- **`Role` (`SOLO`/`AUTHORITY`/`FOLLOWER`)** — the axis is who owns the truth, not client vs server, so
  `ServerMediaPlayer` can be either side while FF/Tx can only follow.
- A follower keeps ONLY the latest `Sync` + its arrival nanos (`authority()`/`authorityTime()` ages it): no queue
  (a snapshot supersedes earlier ones; replaying old ones seeks backwards) and no wrapper class. Correction is ONE
  threshold: `tolerance(ms)` (1 s default) then `seekQuick`.
- Wire: sealed `Packet` — `Sync` 29B / `Config` 11B / `Watch`,`Unwatch` 10B / `Report` 20B / `Control` 19B; decode
  is the trust boundary and consumes ONLY its own bytes. Capabilities: `LOCKSTEP`, `CONTROLS`, `VOLUME`.
  One shared 50 ms ticker drives authority broadcast + follower correction.
- **Why the rejected designs stay rejected:** correcting needs two positions (the aged snapshot and the player,
  which may still be LOADING) so they can't collapse into one — but the target is just a record + a timestamp, so a
  ghost/`SyncClock` buys nothing and costs a second instance and tick per follower. The speed nudge reads to users
  as the video randomly running slow, and a client can only fall behind (the authority is a bare clock), so smooth
  correction solved a case that doesn't occur. Control methods are concrete in the base and return "is this call
  mine to apply" (impls write `if (!super.pause(p)) return false;`), so `*Impl` hooks are ceremony. `Bridge` takes
  no generics: the dev's ResourceLocation identifies the SESSION, not the watcher.
- **Invariants:** `arm()` stays the last statement of every concrete ctor; local teardowns (`release()`, restart)
  call private `abort()`/`teardown()`, NEVER `stop()` (on a follower that stops the media for the whole audience);
  never call the dev's bridge while holding a player lock (take the lock AFTER `super`); the authority ignores
  duration/live from WAITING/LOADING reports (an unopened client reports live=true and would latch the session
  live, killing correction); `Packet.of` must not reject trailing bytes; don't bump revision on the target clock;
  scaling/LOD stay client-local.
- **Timeline-held clock (2026-07-30, fixes the "6-hour clock over an 18s video" + end-of-media restart storm from
  the waterframes field test):** `ServerMediaPlayer.computeTime` returns `accumulatedMs` while `duration <= 0 &&
  !live` — a clock without a timeline cannot wrap nor END, so it must NOT accumulate wall time (WF starts the
  authority at chunk load, hours before anyone latches the duration; snapshots then broadcast those hours and the
  late latch snapped everyone to `bigT % d` or insta-ENDED). `syncDuration`/`syncLive` rebase `segmentStartNanos`
  when the timeline becomes known on a PLAYING un-gated clock — the ONE guard in `computeTime` also keeps
  `gate(true)`/`pause(true)`/`speed()` freeze paths sane, don't re-scatter it. In `follow()`: an ENDED player's
  restart gates on **its own** `duration()` (fallback session d) via `authorityTime() < max(end - tol, tol)` —
  gating on the session duration replayed a finished media forever whenever the two diverged; requires `end > 0`
  so timeline-less sessions (held clock broadcasts time=0 PLAYING, aged P flaps 0–5s per heartbeat) never flap
  restarts. Session STOP on a local ENDED player is a no-op (its pipeline is gone; `stop()` re-fired every tick).
  A SOLO clock resumed after ENDED/STOPPED re-registers the ticker in `pause(false)` or it never ENDs again.
  WF-side residue: session key = BlockPos, so a URL swap can race a stale follower's Report into the NEW session
  and latch the OLD media's duration — divergence the ENDED gate now survives, but WF should key sessions per media.
- **Sync review fixes (2026-07-30):** `ServerMediaPlayer.release()` locks ONLY the local teardown and calls
  `super.release()` unlocked (a follower's Unwatch rides the dev's bridge — lock-after-super applies to release
  too); `Watcher.lastSeenNanos` stamps at construction (the TTL sweep races registration and reads 0 as expired);
  `revision` is an `AtomicInteger` (approved exception, §1); a headless follower's `tick50` adopts the session's
  `live` flag alongside the duration (else the timeline-held guard froze a live mirror's own clock at 0 forever).
  A live session is END-LESS even when a mixed-variant client latched a duration (e.g. an HLS sliding window):
  `update()` never wraps/ENDs it, `time()`/`authorityTime()`/`drift()` never fold against the latched duration,
  and `canSeek()` = `!liveSource()` (matches FFMediaPlayer). The latched duration stays inert on purpose —
  `syncDuration` still accepts it, so a live→VOD misdetection never loses the timeline info.

---

## 11. Codecs

- **Hardening baseline:** `api/codecs` is pinned against DoS by a 65-file hostile corpus in
  `src/test/resources/pentest` (generated by `test/support/MaliciousImages`, asserted by `test/codecs/Pentesting`).
  Contract: hostile input surfaces as `XCodecException` or decodes harmlessly — nothing else.
  - `ImageReader.MAX_DECODED_BYTES = 512 MiB` is **empirically calibrated** (real fixtures need 261 MiB and
    206 MiB; a 256 MiB cap regressed the GIF suite). Don't lower it without re-running that suite.
  - The test JVM needs headroom ABOVE the budget (`test_max_heap`/`test_max_direct_memory` in `gradle.properties`)
    or a bomb OOMs the JVM before the cap can fire.
  - `MAX_PIXELS = 1 << 26` is shared across PNG/JPEG/GIF/NETPBM/WEBP/DDS — keep aligned.
    `JPEGReader.MAX_FRAME_BYTES` is deliberately absolute, not derived.
  - Audited and **not reachable** (pinned by fixtures, don't re-investigate): code execution, local file read,
    SSRF, MC session-token exfiltration. SVG's XXE posture is genuinely closed (external entities disabled,
    `XMLResolver` returns an empty stream — deliberately better than `ACCESS_EXTERNAL_DTD=""` which would reject
    common Inkscape/W3C doctypes; no `href` is ever dereferenced). `SVGParser` sets StAX entity limits explicitly
    because JDK defaults vary 25–500× across Java 17/21/25.
- **SVG decoder** (`api.codecs.readers.svg`) is a from-scratch, **100% AWT-free** rasterizer — no `java.awt.*`, not
  even `java.awt.geom`. Mirrors JSVG's architecture (parse → node tree → `RenderState` → `Output` seam) with a
  raw-buffer backend: `RasterOutput` → `SVGRasterizer` writes ARGB into an `int[]`, `SVGReader` dumps BGRA via a
  LITTLE_ENDIAN int view. Why: the codec path stays AWT-free for GPU upload, and Graphics2D risks the GLFW+AWT
  main-thread conflict on macOS. Keep the own geometry (`Affine`, `Path`+`PathParser`, coverage-AA scanline fill,
  the round-stroke trick = per-segment quads ∪ discs at vertices). Fill AA = 4× vertical supersampling + analytic
  horizontal coverage. Linear gradients only; filters/text/`<use>`/radial/masks out of scope. Detection leaves the
  buffer position at start (SVG has no header). Cap: `WaterMediaConfig.decoders.svgMaxSize` (512 px, uniform
  downscale, never upscale).
- **`ImageReader` proxy chain** (`api.codecs.proxy`): `addProxy(ReaderProxy)` registers per-frame post-processors run
  by `readAll()` in **insertion order** (ordered `List`, never a `Set`). `ReaderProxy.compute(Frame)→Frame` maps
  `Frame(buffer,width,height,PixelFormat)` to a transformed one so proxies chain and geometry flows to the produced
  `ImageData` (last proxy's dims). **Readers reuse one internal `directOut` buffer**, so `readAll` MUST detach every
  frame — the only elision is: feed the reused buffer straight into the chain, and detach only when the whole chain
  is a no-op (`f.buffer() == decoded`); never skip the copy on `isDirect()` (that was a real aliasing bug — all
  animated frames collapsed to the last). Anti-bomb budget now sums the RETAINED (post-proxy) size. First proxy:
  `DownscaleProxy` (area-average, absolute / per-frame scale / source×factor). See §9 for the shared `DataTool` scaler.
- **PNG/GIF chunk write side is NOT dead code.** `read(ByteBuffer)`, `toBytes()`, `toChunk()`, `CHUNK.create`,
  `CHUNK.write`, `crc32` have no callers today but are kept for the planned **APNG writers**. Only the decode-path
  hardening (`convert`/`CHUNK.read` overflow guards) is load-bearing today.
- **NetworkCache codec tier (dormant until native BC/JNI lands):** `Mode.DISK` (raw bodies) vs `Mode.CODEC`
  (frames recompressed to BC7/BC3/BC1 in a DDS container), resolved once at `NetworkCache.start()` from
  `media.txCodecCache` + `CodecsAPI.available("BC")`. Rules: **container ≠ codec** (DDS = `common/dds/DDSHeader`,
  BC = `common/bc/BCCodec`, mirroring RIFF↔WebP); BC is just another codec with a reader (yields COMPRESSED blocks
  for direct GPU upload) + writer; **no registry** — `CodecsAPI` deliberately has no register API (external devs
  must not inject codecs), `available(String)` is general (png/jpeg/gif/webp/netpbm always true, bc probed);
  rigid ctor validation; best-available BC7 > BC3 > BC1. TxMediaPlayer wiring is guarded by `codecActive` so
  Modes 1–3 are byte-identical when off.

---

## 12. Web platforms

- **MedalPlatform** resolves medal.tv clips via the PUBLIC `https://medal.tv/api/content/<id>` — no auth, no
  cookies, no browser UA (a bad id returns HTTP 400, not 404). The root `medal` file's request is the user-FEED
  endpoint and a red herring.
  - URL claim: host `medal.tv`/`www.medal.tv`; id = segment after a `clips`/`clip` segment (locale prefixes are
    scanned past) OR `?contentId=<id>`; non-clip URLs → null.
  - The `contentUrl<height>p` mp4 rungs are **fake dupes** (identical bytes, differing only by a
    `&t=<h>p&c=…&missing` suffix) — don't emit them as qualities. The only real ladder is the HLS master
    `contentUrlHls`, expanded via `MPEGTool.qualities()` like KickPlatform. Fallbacks: progressive `contentUrl`,
    then `socialMediaVideo` (0×0).
  - Signed CDN URLs live ~hours → honor `urlsExpireAt` as `PlatformData.expires`. Gate on `dmcaTakedown` +
    `requireLogin`; filter URLs containing `video/privacy-protected-guest`. No mature gate (medal's `risk` int is
    undocumented — don't guess, matches yt-dlp).
- See §1 for logging/exception conventions and §5 for yt-dlp/YouTube.

---

## 13. Standalone app (`org.watermedia.bootstrap.app`)

- **Fully retained-mode Element tree.** Naming is user-locked: `app/element/` with `Element<T>`/`Group`/`Parent`
  (=LinearLayout, `column()`/`row()`)/`ParentFrame`/`ParentScroll`/`Text`/`Image`/`Icon`; **`MAX_PARENT` = weight 1
  on the main axis (not greedy)**; `ListView`/`Button`/`Spinner`/`Badge`/`KeyChip`/`Dialog`/`Canvas`. Screen base:
  `screen/Screen extends Group` with `keybinds()`, `continuous()`, `under()`, `releaseMedia()`.
- **Elements draw ONLY via `Canvas`** (thin facade over RenderSystem) — never reintroduce imperative drawing.
  `RootScreen` hosts Background / TitleBar / centerSlot / KeybindsBar / global-dialog overlay / CrtOverlay.
- **UI scale is logical px**: tree/ortho/mouse in logical units (`ctx.logicalWidth()/logicalHeight()`), mouse
  divided once in callbacks (raw stays physical for TitleBar), scissor/lineWidth scaled in `RenderEngine`, glyph
  atlases rasterized at physical px with logical quads. **NEVER size UI from `ctx.windowWidth/Height`.**
- **Engine hotswap GL↔VK** applies live via `WaterMediaApp.requestEngineSwap` (volatile latch consumed at the TOP
  of the main loop, never mid-frame): release media + await → `TextRenderer.reset()` + `Assets.dispose()` →
  `RenderSystem.cleanup()` → recreate window+callbacks → `attach` (VK→GL fallback reverts the pref) → restore
  placement/scale → `Assets.load(ctx)`. `WaterMedia.start` never re-runs. **Elements must read texture ids from
  `ctx.assets`/player per draw, never capture them at build time.**
- Input invariants: `Dialog` is fully modal (swallows key/char/scroll and captures unclaimed presses); click
  suppression needs REAL drag movement > 3px; `Group.dispatchHover` self-consumes only when interactive. Live state
  binds in `Element.onUpdate()` (pre-measure, every frame); setters invalidate only on change. Main-loop pacing is
  `syncBeforeFrame → pollEvents → handleFrameInput → render` — don't reorder (it fixes seekbar drag lag).
- CRT is NOT global — exclusive to media previews and the player video area. Animations are self-sustaining
  (advance by `currentTimeMillis` in `onUpdate` + `invalidate()` while t < 1): Switch knob 140 ms, Dropdown ATTACH
  unfold 160 ms, scroll momentum τ = 70 ms, Dialog fade 180 ms via `Canvas.pushAlpha/popAlpha`. Switch and
  SegmentedControl only animate after their first draw (`drawn` flag) so rebuilt Settings rows snap.
- Settings reflects WaterConfig's real group model (spec = root group; nested groups as an indented DFS tree;
  amber active accent). Shell settings live in `app/AppConfig` (`@Spec("watermedia-app", TOML)`); `engine`/`uiScale`
  stay runtime settings — `engine.cfg`/`uiscale.cfg` remain the early-boot source of truth, do NOT move them into
  the TOML. WaterConfig facts (repo `A:\dev\java\waterconfig`): nested group = static final field typed
  `@Spec(disableStatic=true)`; `init()`+`register(Class)` load async; dirty-worker saves ~5 s; **`@FieldEvent` is
  not implemented** (no change callbacks).
- **Native Win32 integration (`app/WinFrame`, Windows-only):** subclasses the GLFW window's WndProc and adds
  `WS_CAPTION|THICKFRAME|MIN/MAXBOX|SYSMENU`. `WM_NCCALCSIZE` returns 0 for both wParam forms; when `IsZoomed` the
  proposed rect is CLAMPED to the monitor workarea (the "GLFW pins maximized to workarea" theory was DISPROVEN at
  runtime). `WM_NCHITTEST`: ~8px resize bands (16px corners, only when !IsZoomed), HTCAPTION for the titlebar minus
  `round(114 * uiScale)` physical px. Install order matters: subclass FIRST, then SWP_FRAMECHANGED.
  `TitleBar.onPress` returns false on Windows (OS drag/snap/sys-menu); manual drag + top-snap remain for non-Windows.
  **Never call `glfwSetWindowAttrib(DECORATED)`** — GLFW must keep believing the window is undecorated.
- **Popup players (software engines showcase).** Shared Swing shell `SwingPlayerWindow` (composition: injected
  video surface + per-tick renderer) with `AWTPlayerWindow`/`JFXPlayerWindow` as thin factories; `PopupSkin` gives
  Swing controls the app's blocky/CRT look via UI delegates. Shortcuts SPACE/←→/↑↓/M/L/N/B on the root pane; one
  Swing Timer (33 ms) drives transport and rendering; `PopupPlayers` keeps a singleton across toolkits.
  - **JavaFX in-process needs both fixes together:** a standalone JavaFX `Stage` never repaints inside the
    GLFW-hosted app (hardware Prism/D3D vs the GLFW GPU context), so the video area is a `JFXPanel` hybrid AND
    `System.setProperty("prism.order", "sw")` runs in a `static{}` block before the toolkit inits. Still images
    need `JFXEngine.repaint()` pumped ~10 fps (the software pulse only repaints on a fresh `updateBuffer`), and
    ended-auto-close is guarded by `duration > 0`.
  - **Engines are PLAYER-EXCLUSIVE:** `MediaPlayer.release()` releases `gfx`, so a shared engine across sequential
    players goes blank on source switch.
  - **Images were black on software engines** because `TxMediaPlayer.openSource()` decoded in the reader's native
    format (YUV420P) and never consulted the engine; it now decodes to BGRA when `!gfx.supports(YUV420P)`,
    mirroring FFMediaPlayer's sws fallback. GPU engines keep the native path.
  - `AppBootstrap` downloads openjfx 21 (base+graphics+swing, platform-classified — each jar holds classes AND
    self-extracting natives) and **supervises** the app JVM: exit code `RELAUNCH_EXIT` (42) re-provisions and
    respawns without re-prompting. Cached JavaFX jars are always added to the child classpath. If JFX was chosen
    but never provisioned, `navigateAction` falls back to the in-app player.
  - Per-engine lifecycle logs (`VKEngine initialized/released`, `Format set`) are DEBUG — at INFO the N thumbnail
    engines looked like a leak but `release()` is idempotent.

---

## 14. Licensing (WMT-Shield draft)

`WMT-SHIELD-LICENSE-DRAFT.md` (repo root) drafts the WaterMediaTeam Shield License for the issue-177 relicensing,
gated on v3 leaving beta. J-RAP v1 numbered-clause format, Polyform-style **separate grants** kept on purpose
(conditions vs covenants: acts outside a scoped grant are infringement, not mere breach — *Jacobsen v. Katzer* vs
*MDY v. Blizzard*), platform model github/curseforge/modrinth/mcmod.cn, terms consolidated in one `# Definitions`
list, generic Licensor (reusable across the user's projects), closing Entire Agreement paragraph (one contract,
cross-conditioned licenses that terminate together — the "two licenses = two lawsuits" worry was refuted: one
infringement action + acumulación de pretensiones).

Locked decisions: three-way environment split **supported / unsupported (forks may port, not compete) / restricted**
(explicit refusal, e.g. TLauncher — extends to forks, never circumventable); dependant embedding is **apps only**
(mods must depend on the official distribution); the 50/50 reward applies only to distributions of the software or
forks, not to dependant products; "exclusive" in J-RAP §1 meant personal/non-transferable → drafted as
non-exclusive + non-transferable; co-ownership of modifications replaced by a **non-exclusive license-back** tied
to consideration, covering only *distributed* changes, for the maximum term permitted by law (LFDA Art. 34 nullifies
global future-work transfers; Arts. 30/33 require onerous, temporal, written transfers); amendment terms are
**prospective** — revised terms bind continued *distribution* after 32 days and never retroactively revoke use
(CCF Art. 1797 + SCJN doctrine on arbitrio unilateral; LFPC Art. 90 on adhesion contracts).

**Open question:** whether dependant apps may redistribute embedded copies "through the channel where the dependant
product is distributed" as an explicit exception to the platform limit.

---

## 15. Open threads

- `module-info.java` — the only way to seal cross-package bases (`WaterMediaAPI`, `ImageReader`, `ImageWriter`,
  `NetpbmDecoder`) and hide internal impl packages; ecosystem decision, not taken.
- Vulkan: host-image-copy migration (§7), RGB/GBRA images on `TxMediaPlayer`, Y-flip/scissor verification on real
  hardware.
- binaries: windows-arm64 JavaCPP glue (parked, needs ARM hardware); linux/macos transitive codec deps in the
  from-source zips; macOS x64 runner mislabeling.
- Codec cache: native BC (JNI) is unimplemented, so the CODEC tier is dormant.
- Licensing: the dependant-app redistribution channel exception.
