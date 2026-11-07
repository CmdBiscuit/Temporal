# Temporal

Upscaling, anti-aliasing and frame generation for Minecraft.

Temporal renders the world at a lower resolution and scales it back up, with the HUD kept at full resolution:

- **Upscalers:** DLSS, FSR 3, NIS, FSR 1
- **Anti-aliasing:** DLAA, FSR Native AA, TAA
- **Frame generation:** DLSS Frame Generation (2x-6x), FSR 3 Frame Generation (2x), with NVIDIA Reflex
- **Also:** dynamic resolution, an FPS counter that counts generated frames, hotkeys (F7 / F8)

## Versions

Each folder is a complete project with its own README and build instructions:

| Folder | Minecraft | Loader | Build with |
|---|---|---|---|
| [`neoforge-1.21.1`](neoforge-1.21.1) | 1.21.1 | NeoForge | Java 21 |
| [`fabric-1.21.1`](fabric-1.21.1) | 1.21.1 | Fabric (needs Fabric API) | Java 25 |
| [`forge-1.20.1`](forge-1.20.1) | 1.20.1 | Forge | Java 21 |

## Building

Open a terminal in the folder of the version you want and run `gradlew build`. The jar ends up in that folder's `build/libs`.

DLSS and FSR 3 need NVIDIA's and AMD's SDKs in that folder's `third_party/`; its README lists the downloads. Without them the mod still builds, but DLSS, FSR 3 and frame generation won't be available.

## License

MIT, see [LICENSE](LICENSE). The NVIDIA and AMD components keep their own licenses; LICENSE lists them.

NVIDIA, DLSS and Reflex are trademarks of NVIDIA Corporation. AMD, FidelityFX and FSR are trademarks of Advanced Micro Devices, Inc. Temporal is not affiliated with NVIDIA or AMD.
