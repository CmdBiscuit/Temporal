# Temporal

Upscaling, anti-aliasing and frame generation for Minecraft 1.20.1 (Forge).

Temporal renders the world at a lower resolution and scales it back up, with the HUD kept at full resolution:

- **Upscalers:** DLSS, FSR 3, NIS, FSR 1
- **Anti-aliasing:** DLAA, FSR Native AA, TAA
- **Frame generation:** DLSS Frame Generation (2x-6x), FSR 3 Frame Generation (2x), with NVIDIA Reflex
- **Also:** dynamic resolution, an FPS counter that counts generated frames, hotkeys (F7 / F8)

Download it from [Modrinth](https://modrinth.com/).

## Building

You need a Java 21 JDK to run Gradle; it downloads the Java 17 that Minecraft 1.20.1 is built with.

DLSS and FSR 3 come from NVIDIA's and AMD's SDKs, which aren't in this repository. Download them and unpack them into `third_party/`:

| Folder | Download |
|---|---|
| `third_party/streamline` | [NVIDIA Streamline SDK 2.14.16](https://github.com/NVIDIA-RTX/Streamline/releases) (the folder with `bin/x64` and `license.txt`) |
| `third_party/fidelityfx` | [AMD FidelityFX SDK 1.1.4](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases) (the folder with `PrebuiltSignedDLL` and `LICENSE.txt`) |

Without them the mod still builds, but DLSS, FSR 3 and frame generation won't be available.

Then build:

```
gradlew build
```

The jar ends up in `build/libs`. On Windows, `Build Mod.cmd` does the same and finds a Java 21 JDK for you; `Run Client.cmd` starts the game with the mod.

## Project layout

- `src/main/java/com/temporal/client` - upscaling, TAA, settings screen
- `src/main/java/com/temporal/client/bridge` - the Vulkan side: DLSS (Streamline), FSR 3 (FidelityFX), frame generation, presentation
- `src/main/java/com/temporal/client/nis` - NVIDIA Image Scaling
- `src/main/resources/assets/temporal/shaders` - GLSL shaders
- `tools` - scripts that build the GUI sprites (`Make-GuiArt.ps1`) and the logo (`Make-Logo.ps1`)
- `art` - the logo at full size

## License

MIT, see [LICENSE](LICENSE). The NVIDIA and AMD components keep their own licenses; LICENSE lists them.

NVIDIA, DLSS and Reflex are trademarks of NVIDIA Corporation. AMD, FidelityFX and FSR are trademarks of Advanced Micro Devices, Inc. Temporal is not affiliated with NVIDIA or AMD.
