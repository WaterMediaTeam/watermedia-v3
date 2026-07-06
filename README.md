[![CurseForge downloads](https://cf.way2muchnoise.eu/watermedia.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia)
[![CurseForge](https://img.shields.io/curseforge/v/869524?style=for-the-badge&label=curseforge&labelColor=%232d2d2d&color=%23e04e14&link=https%3A%2F%2Fwww.curseforge.com%2Fminecraft%2Fmc-mods%2Fwatermedia%2Ffiles)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![Minecraft versions supported](https://cf.way2muchnoise.eu/versions/Supports_watermedia_all.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![JitPack](https://img.shields.io/jitpack/version/com.github.SrRapero720/watermedia?style=for-the-badge&label=JITPACK&color=34495e&link=https%3A%2F%2Fjitpack.io%2F%23SrRapero720%2Fwatermedia)](https://jitpack.io/#SrRapero720/watermedia)
[![Build status](https://img.shields.io/github/actions/workflow/status/WaterMediaTeam/watermedia/gradle.yml?style=for-the-badge
)](https://github.com/WaterMediaTeam/watermedia/actions/workflows/gradle.yml)

![Discord](https://img.shields.io/discord/486853064284831744?style=for-the-badge&logo=discord&logoColor=white&label=DISCORD&color=7289DA)

## 🦆 WATERMeDIA: Multimedia API 
WATERMeDIA is a multimedia engine, provides a richful API to store, load, decode and renderice multimedia 
in 3D environments like VULKAN and OPENGL. Compatible and focused mainly to support Minecraft version that 
uses Java 17 and upper. Superseding the old rusty FancyVideo-API mod, using FFMPEG and house-made decoders.

FFMPEG binaries comes in a external library jar called [WATERMeDIA: Binaries](), with that JAR you won't need
to compile or install FFMPEG or any other native application, plug and play as you deserve.

# 🧩 Projects using WATERMeDIA
- 🖼️ [WATERFrAMES](https://www.curseforge.com/minecraft/mc-mods/waterframes) - By SrRapero720
- 📺 [WATERViSION](https://www.curseforge.com/minecraft/mc-mods/watervision) - By SrRapero720
- 🧱 [FancyMenu](https://www.curseforge.com/minecraft/mc-mods/fancymenu) - By Keksuccino
- 📽️ [Holographic Renderers](https://www.curseforge.com/minecraft/mc-mods/holographic-renderers) - By Mysticpasta1
- 🤵 [BBS CML EDITION](https://www.curseforge.com/minecraft/mc-mods/bbs-cml-edition) - By ElGatoPro300
- 🖼️ [LittlePictureFrames](https://www.curseforge.com/minecraft/mc-mods/littleframes) - By CreativeMD
- ⏪ [WaterFramesBackported](https://github.com/Toshayo/WaterFrames) - By Toshayo
- 💻 [Conditional Videos](https://www.curseforge.com/minecraft/mc-mods/conditionalvideos) - By MateoF024
- ⏯️ [SVVideo](https://www.curseforge.com/minecraft/mc-mods/svvideo) - By Santiivlog

# 💰 Donations
> [!NOTE]
> The amount you want to donate to us, donate half to FFMPEG developers, 
> without them this project wouldn't be possible. 🫶<br>
> https://www.ffmpeg.org/donations.html
> 
[![Support me on Patreon](https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white)](https://patreon.com/SrRapero720)
[![Support me via Paypal](https://img.shields.io/badge/PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/SrRapero720)
[![Support me on Ko-Fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/srrapero720)
[![Support me via Revolut](https://img.shields.io/badge/REVOLUT-DONATE%20DIRECTLY-191C1F?style=for-the-badge&logo=revolut&logoColor=black&labelColor=ffffff)](https://revolut.me/srrap720)

# 💻 BASIC USAGE
## CREATE AN MRL AND A PLAYER
```java
MRL mrl = MediaAPI.getMrl(URI.create("https://imgur.com/gallery/snow-ducks-YcDd9x"));

// IN A TICK-LOOP METHOD
if (mrl.status == MRL.Status.LOADED) {
    MediaPlayer player = MediaAPI.createPlayer(mrl,
            () -> new GLEngine.Builder(renderThread, renderExecutor).build(),
            () -> new ALEngine.Builder().build());
}
```

Explanation of these 2 classes is simple
``MRL`` (Multimedia Resource Location): it connects to the URL, finds any multimedia source and quality variations and stores it with metadata
``MediaPlayer``: Opens an MRL source, lets you control the playback, uploads the texture and the sound to your engines.

Failing MRLs means there's no multimedia that watermedia can open (broken links or you're trying to open a docx)

## 🚙 ENGINES
``GLEngine``: OpenGL video connector. Self-contained — it captures and restores the host's GL
state around every upload, so state managers with sensitive caches (like Minecraft's
GlStateManager or Sodium) are never desynced. No GL wiring is required.

``VKEngine``: Vulkan video connector. It never creates or owns a Vulkan device — your app lends
it one through a ``VKContext`` implementation, and every frame comes back as a ready-to-sample
``VkImageView``. See the Vulkan section below.

``ALEngine``: OpenAL sound connector.

**NOTE:** sending null gfx and sfx engine suppliers disables video and audio output

## 🌋 VULKAN (VKEngine)
Unlike OpenGL there is no global context to detect, so you lend the engine the device you render
with by implementing ``VKContext``. The engine only creates its own resources over it and never
destroys anything you returned:

```java
public final class MyVKContext implements VKContext {
    public VkInstance vkInstance() { return this.instance; }
    public VkPhysicalDevice physicalDevice() { return this.physicalDevice; }
    public VkDevice vkDevice() { return this.device; }
    public VkQueue queue() { return this.graphicsQueue; }
    public int queueFamily() { return this.graphicsFamily; }
    public Object queueLock() { return this.queueLock; }
    public VkPhysicalDeviceMemoryProperties memoryProperties() { return this.memProps; }
    public boolean hostImportSupported() { return this.hasExtMemoryHost; }
    public long minImportedHostPointerAlignment() { return this.hostPointerAlign; }
    public boolean ycbcrSampler() { return this.hasYcbcrFeature; }
    public void retire(Runnable destroy) { this.endOfFrameQueue.add(destroy); }
}
```

```java
MediaPlayer player = MediaAPI.createPlayer(mrl,
        () -> new VKEngine.Builder(myContext).build(),
        () -> null);

// EACH FRAME: texture() IS A VkImageView HANDLE (RGBA, SHADER_READ_ONLY) — 0 MEANS NO FRAME YET
long imageView = player.texture();
```

The contract in short:
- **``queueLock()``** — Vulkan requires queue submission to be externally synchronized. The engine
  holds this monitor around every ``vkQueueSubmit``; if you share the queue, hold the same lock
  around your own submits and presents.
- **``retire(Runnable)``** — the engine never destroys an image view your in-flight frames might
  still sample. Run the callback once those frames completed (end-of-frame destruction queue),
  and flush any pending callbacks before tearing down the device.
- **Everything you return must outlive every engine built over the context.**

Optional device features the engine uses when you enable them at device creation:
- ``VK_EXT_external_memory_host`` — zero-copy uploads: decoder buffers are imported straight into
  a ``VkBuffer`` (no CPU copy). Report it via ``hostImportSupported()`` + the physical device's
  ``minImportedHostPointerAlignment``. Without it the engine falls back to staging buffers.
- ``samplerYcbcrConversion`` (Vulkan 1.1) — YUV→RGB conversion in sampler hardware for NV12 and
  planar YUV; otherwise a compute pass does the exact same BT.709 math.

### Minecraft 26.x (Vulkan backend)
WaterMedia stays engine-agnostic and never touches Mojang's Blaze3D internals — bridging is the
mod's job. The recommended pattern is a **mixin that implements ``VKContext`` directly on Mojang's
``VulkanDevice``**, so the device itself IS the context and the builder only needs a cast.
``VKContext``'s method names avoid overloading that class's members by return type alone (the
merged bytecode would be legal — the JVM resolves by full descriptor — but duplicate names muddy
stack traces and crash reports), and MC's own ``vkDevice()`` accessor already satisfies the
interface verbatim:

```java
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin implements VKContext {
    @Shadow public abstract VulkanInstance instance();
    @Shadow public abstract VulkanQueue graphicsQueue();
    @Shadow public abstract VkDevice vkDevice(); // ALREADY SATISFIES VKContext#vkDevice()

    @Unique private VkPhysicalDeviceMemoryProperties wm$memProps;

    @Override public VkInstance vkInstance() { return this.instance().vkInstance(); }
    @Override public VkPhysicalDevice physicalDevice() { return this.vkDevice().getPhysicalDevice(); }
    @Override public VkQueue queue() { return this.graphicsQueue().vkQueue(); }
    @Override public int queueFamily() { return this.graphicsQueue().queueFamilyIndex(); }
    @Override public Object queueLock() { return this; } // THE DEVICE ITSELF SERVES AS THE MONITOR

    @Override public VkPhysicalDeviceMemoryProperties memoryProperties() {
        if (this.wm$memProps == null) { // MC DOES NOT EXPOSE THEM — QUERY ONCE
            this.wm$memProps = VkPhysicalDeviceMemoryProperties.calloc();
            VK10.vkGetPhysicalDeviceMemoryProperties(this.physicalDevice(), this.wm$memProps);
        }
        return this.wm$memProps;
    }

    @Override public boolean hostImportSupported() { return false; }  // MC DOES NOT ENABLE THE EXTENSION
    @Override public long minImportedHostPointerAlignment() { return 0; }
    @Override public boolean ycbcrSampler() { return false; }         // NOT ENABLED EITHER
    @Override public void retire(Runnable destroy) { RenderSystem.queueFencedTask(destroy); }
}
```

Unwrap the backend once (``GpuDevice#backend`` is private — one accessor mixin or AT/AW line) and
cast it in the builder:

```java
GpuDeviceBackend backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).wm$backend();
MediaPlayer player = MediaAPI.createPlayer(mrl,
        () -> new VKEngine.Builder((VKContext) backend).build(),
        () -> null);
```

Two caveats verified against the 26.2 sources:
- MC submits to the graphics queue **without holding any lock** (the single submit site is
  ``VulkanQueue.Submission#close``). Since the engine submits from its producer thread, add one
  more tiny mixin wrapping that method in ``synchronized (device)`` — the same monitor
  ``queueLock()`` returns above — or the two submit paths can race.
- MC's device is created without ``VK_EXT_external_memory_host`` and without
  ``samplerYcbcrConversion``, hence the hardcoded ``false`` above: the engine transparently uses
  its staging-buffer path and compute-shader YUV conversion.

# 🔌 AVAILABLE APIs
- CodecsAPI: Picture, ~~Audio~~ and ~~Video~~ decoding
- MediaAPI: Multimedia management and display
- NetworkAPI: Host and Remote storage access for private media
- PlatformAPI: Web platform support for media loading ~~and media searching~~

# ⚖️ License
WATERMeDIA is under Polyform Strict License v1.0.0<br>
Commercial usage is forbidden, you need to contact us in order to use WATERMeDIA for commercial purposes

WATERCoNFIG dependency is shaded under All-Rights-Reserved<br>
This is temporally until the dependency gets moved into a external (non-shadeable) library

JavaCPP bindigs for FFMPEG are shaded under Apache 2.0

Full, verbatim license texts for shaded third-party dependencies are bundled under
`src/main/resources/META-INF/licenses/` (shipped in the jar as `META-INF/licenses/`). 