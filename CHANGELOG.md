# BETA 3.0.0-beta.7
- ✨ New: Added support for webp decoding (with animated webp support)
  - This decoder is made in Java pure and does not require native libraries.
- ✨ New: Change ``FFMediaPlayer#seek(long)`` with accurate frame seeking, this may be slower on some formats.
  - Keyframe seeking (quick seek) its moved into ``FFMediaPlayer#seekQuick(long)``
- 🐛 Fixed: odd behaviors on FFMediaPlayer on pausing

# BETA 3.0.0-beta.6
- ⚙️ Workaround: APNG and GIF decoders test fail
- 🛠️ Change: DecodersAPI service, use instead static method #register()
- 🐛 Fixed: AppBootstrap wasn't working when opening the JAR
- See [PORTING-PRIME.md](https://github.com/WaterMediaTeam/watermedia-v3/blob/main/PORTING-PRIME.md) for details.
