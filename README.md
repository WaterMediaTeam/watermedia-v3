[![CurseForge downloads](https://cf.way2muchnoise.eu/watermedia.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia)
[![CurseForge](https://img.shields.io/curseforge/v/869524?style=for-the-badge&label=curseforge&labelColor=%232d2d2d&color=%23e04e14&link=https%3A%2F%2Fwww.curseforge.com%2Fminecraft%2Fmc-mods%2Fwatermedia%2Ffiles)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![Minecraft versions supported](https://cf.way2muchnoise.eu/versions/Supports_watermedia_all.svg?badge_style=for_the_badge)](https://www.curseforge.com/minecraft/mc-mods/watermedia/files)
[![JitPack](https://img.shields.io/jitpack/version/com.github.SrRapero720/watermedia?style=for-the-badge&label=JITPACK&color=34495e&link=https%3A%2F%2Fjitpack.io%2F%23SrRapero720%2Fwatermedia)](https://jitpack.io/#SrRapero720/watermedia)
[![Build status](https://img.shields.io/github/actions/workflow/status/SrRapero720/watermedia/build-test.yml?branch=main&style=for-the-badge)](https://github.com/SrRapero720/watermedia/actions/workflows/build-test.yml)

![Discord](https://img.shields.io/discord/486853064284831744?style=for-the-badge&logo=discord&logoColor=white&label=DISCORD&color=7289DA)

## 🦆 WATERMeDIA: Multimedia API 
WATERMeDIA is a multimedia engine, provides a richful API to store, load, decode and renderice multimedia 
in 3D environments like VULKAN and OPENGL. Compatible and focused mainly to support Minecraft version that 
uses Java 17 and upper. Superseding the old rusty FancyVideo-API mod, using FFMPEG and house-made decoders.

FFMPEG binaries comes in a companion library jar called **WATERMeDIA: Binaries**, with that JAR you won't need
to compile or install FFMPEG or any other native application, plug and play as you deserve. Its sources now live
in this repository under [`binaries/`](https://github.com/SrRapero720/watermedia/tree/main/binaries) — see [📦 Binaries](#-binaries).

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
MRL mrl = MediaAPI.mrl(URI.create("https://imgur.com/gallery/snow-ducks-YcDd9x"));

// IN A TICK-LOOP METHOD
if (mrl.status() == MRL.Status.LOADED) {
    MediaPlayer player = MediaAPI.player(mrl,
            () -> MediaAPI.glEngine(renderThread, renderExecutor),
            () -> MediaAPI.alEngine());
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
MediaPlayer player = MediaAPI.player(mrl,
        () -> MediaAPI.vkEngine(myContext),
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
- **Vulkan Video decoding** — override ``deviceInfo()`` with the feature chain, extension lists and
  queue counts you created the device with, and the player decodes H.264, H.265 and AV1 on the GPU
  with nothing passing through RAM. It needs a 1.3 instance and device with ``timelineSemaphore`` and
  ``synchronization2``, ``VK_KHR_video_queue``, ``VK_KHR_video_decode_queue`` and the
  ``VK_KHR_video_decode_*`` codec extensions. Only 4:2:0 streams decode this way (their NV12 and P010
  pools are what the engine samples), and without ``deviceInfo()`` the engine keeps the host-import
  path above. FFmpeg decodes in one of two places:
  - **Your device**, when you also create queues FFmpeg can own: a compute-capable and a
    transfer-capable queue other than ``queue()`` and a video decode queue, with ``queue()`` the last
    queue created in its family. The engine samples the decoded images in place. FFmpeg submits to
    those queues from its own threads, so never call ``vkDeviceWaitIdle`` while players exist: wait on
    your own queue.
  - **A twin**, when you create no extra queues but enable ``VK_KHR_external_memory_win32`` and
    ``VK_KHR_external_semaphore_win32`` on Windows, or ``VK_KHR_external_memory_fd`` and
    ``VK_KHR_external_semaphore_fd`` elsewhere. The engine creates a second device on the same GPU with
    your feature chain and extensions, FFmpeg decodes there and the engine imports every decoded image
    into your device; your queues stay yours. If an image cannot be imported (natives built without
    FFmpeg's export fix, for instance), its frames come down to RAM instead and playback goes on.

  Either way, release every player before you destroy the device or its instance: FFmpeg keeps using
  them (and the twin lives on your instance) until its last decoder is gone.

```java
public VKContext.DeviceInfo deviceInfo() {
    return new VKContext.DeviceInfo(this.features2, this.instanceExtensions, this.deviceExtensions, this.queueCounts);
}
```

### Minecraft 26.x (Vulkan backend)
WaterMedia stays engine-agnostic and never touches Mojang's Blaze3D internals — bridging is the
mod's job. The recommended pattern is a **mixin that implements ``VKContext`` directly on Mojang's
``VulkanDevice``**, so the device itself IS the context and ``MediaAPI.vkEngine`` only needs a cast.
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
cast it in the factory call:

```java
GpuDeviceBackend backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).wm$backend();
MediaPlayer player = MediaAPI.player(mrl,
        () -> MediaAPI.vkEngine((VKContext) backend),
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

# 📦 Binaries
FFmpeg natives and a few extra shared libraries ship in a companion jar, **WATERMeDIA: Binaries**.
With that jar you won't need to compile or install FFMPEG or any other native application.
Its sources live in this repository under [`binaries/`](https://github.com/SrRapero720/watermedia/tree/main/binaries).
Minecraft clients require Binaries; dedicated servers can omit it. Applications embedding the Java API
can run without it, keeping image support but skipping video decoding and executable-backed platform
resolvers. When installed, binaries load before the other modules; a failed binaries load ends the
startup.
CurseForge and Modrinth list Binaries as required because their dependency relationships do not support
client/server conditions, so installers may also include it in server installations.

## Supported platforms
| Platform | Architecture | Status |
|----------|--------------|:------:|
| Windows  | x86_64       |   ✅    |
| Windows  | aarch64      |   ⛔    |
| Linux    | x86_64       |   ✅    |
| Linux    | aarch64      |   ✅    |
| macOS    | x86_64       |   ✅    |
| macOS    | aarch64      |   ✅    |
| Android  | aarch64      |   ⛔    |
| Android  | x86_64       |   ⛔    |

## Binaries build
FFmpeg binaries are rebuilt from the pinned JavaCPP preset on five native GitHub runners. The complete
pipeline compiles the static dependencies, parses the FFmpeg headers, regenerates JNI and verifies each
classifier before the five archives are repacked together.

# ⚖️ License
WATERMeDIA is under the [Defensive Public Source (Non-Commercial) License v1.0.1](LICENSE.md)<br>
Copyright SrRapero720 (https://github.com/SrRapero720)<br>
Commercial usage is forbidden, you need to contact us in order to use WATERMeDIA for commercial purposes

WATERCoNFIG is required at runtime and is licensed All-Rights-Reserved.

JavaCPP bindings for FFMPEG are shaded under Apache 2.0

Full, verbatim license texts for shaded third-party dependencies are bundled under
`src/main/resources/META-INF/licenses/` (shipped in the jar as `META-INF/licenses/`).

The **binaries** module's own code is under the same DPS (NC) License v1.0.1. Its jar also ships
third-party native binaries and libraries, whose license texts and notices are bundled under
`binaries/src/main/resources/META-INF/licenses/` and shipped as `META-INF/licenses/`:

- **LGPL-3.0-or-later** — FFmpeg 9.0.2, built without `--enable-gpl`, x264 or x265
- **LGPL-2.1-or-later** — ALSA (Linux ARM64 native)
- **GPL-3.0-or-later WITH GCC-exception-3.1** — libatomic on macOS and eligible GCC runtime portions
- **Apache-2.0** — JavaCPP JNI glue (native)
- **MIT / X11** — libva, libva-drm and libdrm (Linux x64 native); rustypipe-botguard (downloaded binary)
- **BSD-3-Clause** — Raspberry Pi userland (Linux ARM64 native)
- **MIT / BSD-3-Clause** — winpthreads (Windows native)
- **0BSD** — XZ for Java (shaded)
- **Unlicense** — yt-dlp (downloaded binary)

Other statically linked codecs retain their permissive, LGPL, MPL or Apache licenses. Each component's
terms apply independently; review the shipped notices and corresponding-source requirements before
redistributing a modified native set. The native package remains replaceable at runtime.
