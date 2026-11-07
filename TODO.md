# Lantern TODO

Roadmap, not features: check the source before relying on any line here. Items marked **(unverified)** were built
but never confirmed on a device.

## Release

- Build the release APK with an empty `native/cores/` (people add emulators in the app: *Select > Emulators*).
  Consider making `build.sh release` refuse to package cores.
- Emulator downloads: the libretro nightlies change under the same name and are not signed. Options: pin known
  hashes per core, or show the core's version after download. Also "update" for an installed core, and a check of a
  failed download path on the device (only success, removal and Play-opens-the-list were tried).
- Release signing with a key the author keeps (never committed), so updates install over earlier versions; then GitHub
  Releases with the APK. Only a debug build exists now.
- GitHub: turn on Issues, add topics (android, launcher, retro-gaming, libretro).

## Cores still missing (each download needs the author's approval)

Systems with a ROM folder but no core: N64, PlayStation, Nintendo DS, PSP, Saturn, Dreamcast, PS2.
Likely libretro cores, in a sensible order (performance notes are expectations, not measurements on the Pocket 6):

1. **N64: Mupen64Plus-Next.** Per-game settings may be needed.
2. **PlayStation: SwanStation or PCSX ReARMed.** Needs a PS1 BIOS from the person, `.cue`/`.chd` handling and disc swapping.
3. **Nintendo DS: melonDS or DeSmuME.** Two screens: needs a layout choice and touch for the bottom screen.
4. **PSP: PPSSPP.** The heaviest of the set.
5. **Dreamcast: Flycast.** Needs a BIOS.
6. **Saturn: Beetle Saturn or Yabause.** Heavier, less reliable. Last.
7. **PS2:** no libretro core worth trusting on Android. Realistic route is launching a standalone emulator as an Android app.

Cheap extras with no folder yet: Sega CD (Genesis Plus GX, already supported), 32X (PicoDrive), PC Engine (Beetle
PCE), arcade and Neo Geo (FBNeo), Atari 2600/7800 (Stella, ProSystem), Lynx, WonderSwan, Neo Geo Pocket, Virtual Boy.

Before adding any core: read its licence, record it in `native/CORES.md` and `scripts/fetch-cores.sh`, check size
and hashes. Cores are never committed.

## Player

- Audio underruns (the HUD showed 1,500+ on SNES): tune the buffer, then listen on the device.
- Optional "trim black edges" for games that send black rows at the top (seen in Aladdin on SNES).
- Decide the in-game menu button (Back, Mode and Select + Start all open it now).
- Integer scaling versus soft scaling as the default.
- BIOS check, disc swapping, analogue sticks (only the D-pad is mapped).
- Remapping with a real controller in a long session **(unverified: tested with injected keys only)**.
- Reopening the menu after the device sleeps, and resuming after the player process was killed **(unverified)**.

## Home

- Search results: rank titles that start with the text first.
- "Move to Games" on an Android app's page, for games that do not declare a category.
- Hint for L2/R2 letter jumping in the bottom row, if there is room.
- On the details page opened from a grid, the "Choose" hint shows the D-pad with Up; Up there no longer goes back.
  Decide whether it should show Down only, or hide when there is a single action.
- Undo for Y (remove from Recent), or a confirmation.
- Ideas for Recent, offered but not built: a slow drift of the blurred backdrop (check the battery cost), a thin
  played-time bar on tiles, a "last played 3 days ago" line, a divider before the "All games" tile, Today / Earlier
  labels.
- System art: icons for Android apps and games are still the drawn shapes (`SystemArt.java`); optionally let the
  person's pictures in `Lantern/art/_systems/<id>.png` win.
- Test that the gesture swipe still reveals the system bar with the full-screen window.
- Bug report, not reproduced: once, backing out of a Recent game's details landed on All games. B, Up, Back, double B
  and B then Down all returned to Recent in testing. Watch for it; note what was pressed before.
- **(unverified)** Touch: dragging a grid up and down; the fix for a random pale rectangle over the focused tile.
- Other screens: the layout assumes 16:9 landscape and has only been seen on the Pocket 6.

## Code (see `AUDIT.md`)

- Keep splitting `HomeActivity.java` (about 1,340 lines): the info block beside Recent's big tile and the launch
  animation are the next natural pieces.
- Replace the deprecated window-inset and system-UI calls before raising `targetSdk`.
- Make the library reload on resume incremental (it rescans everything; fine with thousands of games, not with tens of
  thousands).
- Choose the cover decode size per tile size, not one size for all.
- Update `native/third_party/libretro/libretro.h` to the current upstream header.
- Android Lint and CI: the Gradle-free build has neither.

## Other projects

- Nova Studio could write a game's landscape banner straight to `Lantern/art/androidgames/<package>.png`, which
  Lantern already shows full-bleed.
