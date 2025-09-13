# WATERMEDIA 2.1.x to 3.0.0-alpha.0
Everything was removed and rebuilded from scratch.

## Removed
- ImageAPI
- PlayerAPI
- NetworkAPI
- CacheAPI

## MediaAPI
Replaces ImageAPI, PlayerAPI, NetworkAPI and CacheAPI. This new API deals with pictures as a
"video" with no audio, or more like an animated texture, so you don't have to handle renderables as a different type of media.
Also, centralizes all media player implementations under the ``MediaPlayer.class``, working as a OpenGL/OpenAL
manager, avoiding class redundancy by using player capabilities (for audio and video) and focus the actual class
in the engine implementation.

MediaPlayer constructor explained
``MediaPlayer player = new MediaPlayer(URI, Thread, Executor, boolean, boolean)``
1. URI: the URI of the media, you can make one using ``URI.create()``
2. Thread: the render thread, usually ``Thread.currentThread()`` if you're on the render thread
3. Executor: the executor for the provided thread, so the player can perform method calls on render thread for OpenGL, In minecraft the executor is the Minecraft instance, so using ``Minecraft.getInstance()`` its enough
4. boolean (1): enables Video Capabilities
5. boolean (2): enables Audio Capabilities

Current available implementations are
- ``FFMediaPlayer``
- ``PicturePlayer`` (this one skips boolean (2) argument)
- ``VLMediaPlayer``

Next alphas will have a API method inside ``MediaAPI.class`` to provide you the right player for the provided
MRL (Media resource location, aka the URL or the File Path), but for now you have to call the right media player
for the right media type.

Example:
```java
public void create() {
    var uri = Uri.create("https://files.catbox.moe/uxypnp.mp4");
    var mediaPlayer = new FFMediaPlayer(uri, Thread.currentThread(), Minecraft.getInstance(), true, true);
    mediaPlayer.start();    
}
```

# PURPOSE OF THIS ALPHA
Purpose its found any potential bug/edge case on the players implementation and fix it, receive feedback of the
current `MediaPlayer.class` methods and if some should be added, removed or renamed, specially for the `FFMediaPlayer.class`.

Some other features remains unimplemented yet. But will be available in next alphas
