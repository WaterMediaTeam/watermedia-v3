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
- ⚙️ Added: `org.watermedia.api.util.NetRequest` — builder-style HTTP/FTP/file:// client (`create(URI|String)`, `Builder.method/accept/contentType/referer/userAgent/header/addHeader/body/connectTimeout/readTimeout/maxRedirects/headers/send`; `uri()`, `statusCode()`, `contentType()`, `contentLength()`, `header()`, `requestHeaders()`, `responseHeaders()`, `getInputStream()`, `getInputStream(Function)`, `readAllAsString()`, `json()`, `json(Class)`, `UserAgent` enum, `installExtraMimeTypes()`)
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
