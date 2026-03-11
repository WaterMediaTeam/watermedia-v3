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
- ServerMediaPlayer: Not just a placeholder, it is a utility class to run a server-side time orchestator, **STILL NOT IMPLEMENTED**

### ARGUMENTS
- sourceIndex (int) - the source index to play
- renderThread (Thread) - the thread instance of the render thread (available on ``Minecraft.thread``)
- renderThreadEx (Executor) - the executor that runs task on render thread (``Minecraft.getInstance()``)
- glEngine (GLEngine) - the instance you MUST need to create using GLEngine.Builder. this is needed because Minecraft hardly depends on GlStateManager, so you need to proxy all watermedia GL calls into GlStateManager equivalents.
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