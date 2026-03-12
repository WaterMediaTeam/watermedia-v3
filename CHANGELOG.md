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
