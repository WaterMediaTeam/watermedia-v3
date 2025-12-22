# WATERMeDIA 3.0.0-alpha.3
## MRL arrives!
MRL its a smart URI wrapper instance, it caches the URI and triss to fetch the source asynchronimously.
At the moment you create the instance, the MRL fetch the URL to find what sources are available (for galleries) and its available qualities (480p or 1440p)

Usage its simple as
``MRL.get(URI)``

if the MRL doesn't exists or its expired, it creates a new one.

To create a MediaPlayer you must do
``createPlayer(int sourceIndex, Thread renderThread, final Executor renderThreadEx, GLEngine glEngine, final ALEngine alEngine, boolean video, final boolean audio)``

the MRL will choose the optimized mediaplayer engine for the selected media type.

But before you must check if the MRL was not busy or has an error (no valid)

``MRL#busy()`` and `MRL#error()`

MediaPlayers now requires MRL.Source instead or MRL, Sources aren't opened on the same
MediaPlayer instance because some edgecases (imgur) sources can be VIDEO and IMAGE, so, creating
a MediaPlayer for specific sources solves this edge cases, qualities on the other hand are ALWAYS the
same media type, so you don't need to worry about

## VLC WAS REMOVED
VLC has no purpose since FFMPEG implementation has become MUCH stable and way better simple than VLC, so in order
to reduce the Binaries file size and simplify the logic, libVLC and videolan-natives its now removed.

videolan-natives repository its now archived and will not receive any futher updates.

# WATERMeDIA 3.0.0-alpha.1
## Engines update
MediaPlayers now requires a new special type of argument, ``GLEngine`` and `ALEngine`
GLEngine was made to delegate OpenGL logic to any exiting GLManager running on the GL context (like minecraft has),
it prevents bugs and edge cases where the GLManager expects some configurations but WATERMeDIA changes it silently by
calling natives.

Please look at the class builders for more information, both are self descriptive.

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

If you don't want or mind to figure out which player to use,
you can use the ``MediaAPI.createPlayer(URI, Thread, Executor, boolean, boolean)`` method 

Example:
```java
public void create() {
    var uri = Uri.create("https://files.catbox.moe/uxypnp.mp4");
    var mediaPlayer = MediaAPI.createPlayer(uri, Thread.currentThread(), Minecraft.getInstance(), true, true);
    mediaPlayer.start();    
}
```

## RENDERING
To render the texture, you need to run your own vertex logic,
blit or fill, you can get the texture id using ``player.texture()``.

For modern versions of Minecraft (1.20.1 and 1.21.1) you should make a ``TextureWrapper``
```java
package me.example.modder;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.NotNull;

public class TextureWrapper extends AbstractTexture {
    public TextureWrapper(int id) {
        this.id = id;
    }

    @Override public int getId() {
        return this.id;
    }

    @Override public void load(@NotNull ResourceManager manager) { /* NO OP */ }
    @Override public void releaseId() { /* NO OP */ }
    @Override public void close() { /* NO OP */}
}
```
and register it to the texture manager
```java
var texture = new TextureWrapper(player.texture());
Minecraft.getInstance().getTextureManager().register("modid:myvideo", texture);
```

## CUSTOM GLSTATE
When your environment (minecraft 1.20.1+) is using a custom GLState,
it may be required to override watermedia's internal glstate (none) and set it before rendering the video texture.
You can do this by using the ``RenderAPI.setCustomGlManager(org.watermedia.api.render.support.GLManager)`` method, which will be called before rendering the video texture.

# PURPOSE OF THIS ALPHA
Purpose its found any potential bug/edge case on the players implementation and fix it, receive feedback of the
current `MediaPlayer.class` methods and if some should be added, removed or renamed, specially for the `FFMediaPlayer.class`.

Some other features remains unimplemented yet. But will be available in next alphas
