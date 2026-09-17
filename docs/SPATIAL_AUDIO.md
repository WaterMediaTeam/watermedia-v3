# Spatial audio and Sound Physics Remastered

English | [Español (México)](SPATIAL_AUDIO.es-mx.md)

Create an OpenAL engine with `MediaAPI.alEngine(true)` before constructing the player. Spatial mode is fixed for the engine's lifetime: its channel table advertises mono, so FFmpeg's existing resampler downmixes stereo and surround sources before upload. The ordinary `alEngine()` factory uses normal channel negotiation without forcing mono; layouts unsupported by the device may still be converted to the closest available channel count. Java Sound returns `false` from spatial updates and does not expose a native source.

The engine starts with dry, listener-relative mono audio. Supply a `SpatialAudio` state to place it in the world. Passing `null` restores dry, listener-relative playback while retaining mono decoding. Volume, mute, speed, pause and synchronization remain independent of spatial settings.

```java
import org.watermedia.api.media.engines.SFXEngine.SpatialAudio;

final ALEngine audio = MediaAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Run engine creation, spatial updates and release on the host's sound executor, with the original OpenAL context current. The host owns the device, context and listener; they must outlive every player using them. `FFMediaPlayer` uses private playback threads and currently requires the source's context to be the process-current context on those threads, as with ordinary `ALEngine` playback. A host that exposes its audio context only through thread-local binding is not supported by the player pipeline; binding that context on the caller's sound thread alone is insufficient. Native engine operations reject a different or absent context; they never switch or recreate the host's context. Release players before a device reload, then create new engines in the replacement context. `FFMediaPlayer.release()` interrupts and joins its playback pipeline before deleting the audio source; it must run before context destruction, not as a callback from that pipeline.

`SpatialAudio` validates finite positions representable by OpenAL floats, `0 < referenceDistance < maxDistance`, and finite nonnegative rolloff. The host's distance model determines how these parameters attenuate audio. The API does not change the global distance model, listener or source velocity. In particular, `maxDistance` is not a universal silence radius under every OpenAL model. Passing zero rolloff disables distance attenuation. Spatial state belongs to the local listener and is not included in synchronization packets.

## Sound Physics Remastered adapter

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
final MediaPlayer player = MediaAPI.createPlayer(mrl, () -> null, () -> MediaAPI.alEngine(true));
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

## Verification

`SpatialAudioTest` exercises invalid state rejection, Java Sound refusal, real OpenAL source coordinates and attenuation, callback origin restoration, effect clearing, exception handling, mismatched contexts and late calls after release. It also generates a stereo WAV and drives it through `FFMediaPlayer`, verifying mono PCM in actual OpenAL buffers. The device tests require an available OpenAL output device; the decode case additionally requires FFmpeg natives.

An in-game check is still needed for audibility of occlusion and reflections, host sound-executor serialization, listener movement, category settings and device reloads. The reference sources were reviewed during integration, but this checkout has no runnable Minecraft plus Sound Physics integration environment.
