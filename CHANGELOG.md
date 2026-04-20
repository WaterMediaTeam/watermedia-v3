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
  - This decoder is made in Java pure and does not require native libraries.
- ✨ New: Change ``FFMediaPlayer#seek(long)`` with accurate frame seeking, this may be slower on some formats.
  - Keyframe seeking (quick seek) its moved into ``FFMediaPlayer#seekQuick(long)``
- 🐛 Fixed: odd behaviors on FFMediaPlayer on pausing

# 📦 UPDATE 3.0.0-beta.6
- ⚙️ Workaround: APNG and GIF decoders test fail
- 🛠️ Change: DecodersAPI service, use instead static method #register()
- 🐛 Fixed: AppBootstrap wasn't working when opening the JAR
- See [PORTING-PRIME.md](https://github.com/WaterMediaTeam/watermedia-v3/blob/main/PORTING-PRIME.md) for details.
