## 1.0.0

### New
- NIS (NVIDIA Image Scaling) upscaler. Works on any GPU except on macOS.
- Dynamic Resolution: pick a target FPS and the render resolution drops (down to 50%) when your GPU can't keep up, then comes back. Works with every upscaler.
- FPS counter that counts generated frames (Frame Generation page).
- Hotkeys: F7 toggles upscaling, F8 toggles frame generation. Rebindable in Controls.
- The Sharpness slider now works with DLSS.
- New icon.

### Improved
- FSR 3: less smearing on particles, rain, snow, water and glass.
- On a fresh install Temporal picks DLSS on RTX cards and FSR 3 on other cards.
- On Linux and macOS, DLSS, FSR 3 and frame generation are greyed out instead of failing.
- Shorter frame generation description.
- License is now MIT.

### Fixed
- First-person hand disappearing with upscaling off when Sable / Create Aeronautics is installed.
- Immersive Portals: a portal's view covered the whole screen and DLSS failed.
- Frame generation not starting on AMD Radeon cards.
- The V-Sync frame cap now uses the refresh rate of the monitor the game is on.
- Setting descriptions going off the screen at large GUI scales.
