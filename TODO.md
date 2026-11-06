# Lantern TODO

Roadmap, not features: check the source before relying on any line here.

## Cores still missing (needs the owner's approval for each download)

Systems with a ROM folder but no core: N64, PlayStation, Nintendo DS, PSP, Saturn, Dreamcast, PS2.
Likely libretro cores, in a sensible order (performance notes are expectations, not measurements on the Pocket 6):

1. **N64: Mupen64Plus-Next.** 19 N64 ROMs are already on the device. Per-game settings may be needed.
2. **PlayStation: SwanStation or PCSX ReARMed.** Needs a PS1 BIOS from the owner, `.cue`/`.chd` handling and disc swapping.
3. **Nintendo DS: melonDS or DeSmuME.** Two screens: needs a layout choice and touch for the bottom screen.
4. **PSP: PPSSPP.** The heaviest of the set.
5. **Dreamcast: Flycast.** Needs a BIOS.
6. **Saturn: Beetle Saturn or Yabause.** Heavier, less reliable. Last.
7. **PS2:** no libretro core worth trusting on Android. Realistic route is launching a standalone emulator as an Android app.

Cheap extras with no folder yet: Sega CD (Genesis Plus GX, already present), 32X (PicoDrive), PC Engine (Beetle PCE),
arcade and Neo Geo (FBNeo), Atari 2600/7800 (Stella, ProSystem), Lynx, WonderSwan, Neo Geo Pocket, Virtual Boy.

Before adding any core: read its licence, record it in `native/CORES.md`, check size and hashes.

## Player

- Audio underruns (the HUD showed 1,500+ on SNES): tune the buffer, then listen on the device.
- Optional "trim black edges" for games that send black rows at the top (seen in Aladdin on SNES).
- Decide the in-game menu button (Back, Mode and Select + Start all open it now).
- Integer scaling versus soft scaling as the default.
- Cheats, button remapping, BIOS check, disc swapping.

## Home

- Make Lantern the default home app (Settings, Default apps, Home app, or `adb shell cmd package set-home-activity app.lantern/.HomeActivity`).
- Test that the gesture swipe still reveals the system bar with the full-screen window.
- Search results: rank titles that start with the text first.
- "Move to Games" on an Android app's page, for games that do not declare a category.
- Hint for L2/R2 letter jumping in the bottom row, if there is room.
- System art: icons for Android apps and games are still the drawn shapes (`SystemArt.java`); the console icons are libretro's. Optionally let the owner's pictures in `Lantern/art/_systems/<id>.png` win.
