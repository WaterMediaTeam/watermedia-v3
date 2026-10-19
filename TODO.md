# TODO

Features planned for future 3.x.x releases. Each entry names the gap as it stands today and what closing it involves.

## Future 3.x.x features

- **Compressed-texture (BCn) uploads in the GL and Vulkan engines.** `PixelFormat` models BC1/BC2/BC3/BC5/BC7 and `BcReader` hands out the blocks of a BC1/BC3/BC7 DDS texture array with their `PixelFormat`, but `GFXEngine.supports` declines every compressed format, so no built-in engine accepts them and no player opens DDS sources (`CodecsAPI.reader` does not dispatch them). The feature uploads the blocks as they come (`glCompressedTexSubImage2D` / `VK_FORMAT_BC*`) so the GPU samples them directly, then routes DDS sources through `BcReader` in the image player.
- **HDR tone mapping from the stream's metadata and on software engines.** The GL and VK engines map PQ and HLG to SDR with a fixed 1000-nit peak and a highlight knee, ignoring the mastering-display and content-light side data (`AVMasteringDisplayMetadata`, `AVContentLightMetadata`) that state the real peak, and the software engines (AWT, JavaFX) receive BGRA from `sws_scale`, which applies the stream's matrix and range but cannot tone map, so HDR looks dim and flat there. Closing it carries the peak in `Colorimetry` and gives the BGRA path a tone-mapping step (libavfilter `tonemap` or a CPU curve).
- **Vulkan frame-texture fast path for animated pictures.** `GLEngine` implements `preload`/`frame`, keeping every frame of an animated image as its own texture under `media.watertx.texturesBudget`, while `VKEngine` does not, so on Vulkan `TxMediaPlayer` falls back to decoding and uploading each frame on every repetition. Parity needs one image per frame (or one array image) plus the VRAM gate on the VK side.
