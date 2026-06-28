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

// In a tick-loop method
if (mrl.status == MRL.Status.LOADED) {
    MediaPlayer player = MediaAPI.createPlayer(mrl, 0, new GLEngine.Builder().build(), new AlEngine.Builder().build());
}
```

Explanation of these 2 classes is simple
``MRL`` (Multimedia Resource Location): it connects to the URL, finds any multimedia source and quality variations and stores it with metadata
``MediaPlayer``: Opens an MRL source, lets you control the playback, uploads the texture and the sound to your engines.

Failing MRLs means there's no multimedia that watermedia can open (broken links or you're trying to open a docx)

## 🚙 ENGINES
``GLEngine``: Is a proxy class required in OpenGL applications with a GlStateManager with a very 
sensitive texture and shader management, without it can break the GLStateManager.

``ALEngine``: OpenAL sound connector.

**NOTE:** sending null gl and al engine suppliers disables video and audio output

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