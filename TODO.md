# TODO

Features planned for future 3.x.x releases. Each entry names the gap as it stands today and what closing it involves.

## Future 3.x.x features

- **Compressed-texture (BCn) uploads in the GL and Vulkan engines.** `PixelFormat` already models BC1/BC3/BC7 (plus BC2/BC5 without a producer) and `NetworkCache` keeps a dormant codec tier that would hand DDS-wrapped blocks to the players, but both engines decline the compressed formats today: GL rejects them in `supports` and `preload` demands a direct upload, VK rejects them and its `default` branch leaves the format unusable, and GL `format(BCn, ...)` silently builds an RGBA plane. The feature uploads the blocks as they come (`glCompressedTexSubImage2D` / `VK_FORMAT_BC*`) so the GPU samples them directly, then lights the codec tier up.
- **Vulkan frame-texture fast path for animated pictures.** `GLEngine` implements `preload`/`frame`, keeping every frame of an animated image as its own texture under `media.watertx.texturesBudget`, while `VKEngine` does not, so on Vulkan `TxMediaPlayer` falls back to decoding and uploading each frame on every repetition. Parity needs one image per frame (or one array image) plus the VRAM gate on the VK side.
