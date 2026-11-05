# Lantern

A console-style home screen for the Retroid Pocket 6 (landscape, 1920x1080, gamepad first).
Each game is its own tile: emulated ROMs, Android games and Nova games alike. Replaces the stock launcher.

Plan, mockup and decisions: the "Lantern Launcher" artifact (https://claude.ai/artifact/DCNUKbUBFy1EsoYeDYZzCY).

## Status: M0 done, M1 started

The home screen follows the mockup (1920x1080 layout numbers, scaled to the window): three shelves
(Recent, Favourites, All games) on L1/R1, one row of covers where the focused cover grows, a glow behind
it in the game's colours, the title block, and the button hints.

- Games come from ROM folders (`/sdcard/Lantern/roms/<system>/`, needs "all files access") and from installed Android apps.
- D-pad browses, A opens an Android app, Y favourites, Start opens the file-access setting if needed.
- Favourites and play history are kept in the app's preferences.
- Covers are generated from the game's colours. Real art fetching is not built.
- A opens the game's page: Play, add or remove favourite, Details (file or package facts), and for apps
  Android's App settings. L1/R1 move to the previous or next game, Y favourites, B goes back.

### Player spike (M2)

`native/player/player.c` is a libretro host: it loads a core (`dlopen`), runs it on its own thread, copies each
frame to a surface, plays the audio through AAudio (the audio clock sets the speed), and feeds it the buttons.
It runs in a separate Android process (`:player`), so a crashing core leaves the home screen alone.
`native/testcore/testcore.c` is a small core of our own (a moving square, a 440/880 Hz tone, 16 button lights,
save states) so the player can be checked without ROMs or downloads. An empty `Something.test` file in
`Lantern/roms/test/` plays it.

Checked on the emulator: 320x240 at 59.9 to 60.1 fps, audio stream running (a handful of start-up underruns),
D-pad and face buttons reach the core (Android A arrives as RetroPad B, by position), save state writes 16 bytes
and loading restores the picture, and `am crash` on the player process leaves Lantern running.
`native/third_party/libretro/libretro.h` is the official MIT header, copied from GameStudio (see its NOTICE.md).

### First real core: mGBA

Put libretro cores in `native/cores/` (git-ignored, never committed). `build.sh` packages every
`<name>_libretro_android.so` there as `lib<name>.so`, and `Cores.java` maps a system folder to a core name.
Right now only `mgba_libretro_android.so` is present, from libretro's nightly Android build server
(`buildbot.libretro.com/nightly/android/latest/arm64-v8a/`, 419,371 byte zip, sha256 starting 3edd6395,
mGBA 0.11-212-7a12d6d, MPL-2.0). It is a nightly build with no signature. I checked it is a 64-bit ARM ELF that
exports the libretro functions and needs only libc, libm and libdl.

Checked on the emulator with one commercial GBA game from the owner's own collection (copied to the emulator
only): it boots, the title screen runs at 59.8 to 60.3 fps with sound, a single Start tap moves from "Press
Start" to the game's intro, a 462,912-byte save state is written, and `Advance Wars (USA).srm` (131,072 bytes) is
created. The host writes battery saves every 5 seconds when they change and on exit, through a temp file and a
rename. File names follow RetroArch's (`<game>.srm`, `<game>.state`); that is untested with real RetroArch files.
Button presses are latched so a tap shorter than one frame still reaches the game.
Not verified: that a saved game survives a restart (the `.srm` exists, a second run was not compared), long play
sessions, and anything about speed or heat on the Retroid.

### In-game menu (M3)

Opens with the Guide button (`BUTTON_MODE`), Select and Start together, or Back. Which physical button the
Retroid Pocket 6 reports for its menu or home key is not known yet; the choice is in
`PlayerActivity.dispatchKeyEvent`. The menu is MinUI-style: Continue, Save, Load (left and right pick one of nine
slots, with that slot's screenshot and age), Options, Quit. A confirms, B goes back (and Continue closes it).
Options are Scaling (Aspect, Integer, Fill), Screen effect (Off, Scanlines) and Fast-forward speed (2x, 3x, 4x,
Max), kept per system. Hold R2 to fast-forward; sound is muted while it runs, and R2 is not passed to the game.
Cheats, Controls and the core's own settings are not built, so they are not in the menu.

Leaving a game by Quit, Home or anything that takes the screen saves a resume point (`<game>.state.auto` plus a
screenshot). The game page shows Resume (highlighted) above Play from the start, and the shelf says
"Resume available". Slots are `<game>.state1` to `.state9`, each with `<file>.png`; the resume point is slot 0.

Checked on the emulator with Advance Wars: the menu opens over the frozen game with a "Current game" preview;
Save to slot 1 writes a 462,912-byte state and a PNG and the slot then previews it; Load restored an earlier
scene and closed the menu; Quit and Home both wrote a fresh resume point; Resume continued the game;
Integer scaling drew the picture exactly 5x (1200 px wide) and scanlines drew one line per source row, and both
choices were still set after relaunching; holding R2 changed the host speed to 2 and back (the log shows it).
Not verified: fast-forward speed beyond that log line (the held key was only about 0.4 s), Max speed, 9 slots,
audio after pause and resume (nobody listened), and the menu with a real controller.

Known quirk, unexplained: with keys sent by `adb input`, the first key press after the first menu open of a launch
arrives without its "down" half, so one press is lost. It did not depend on the delay, on which key it was, on the
keyboard app (tried `FLAG_ALT_FOCUSABLE_IM`) or on window focus changes, and it does not happen again until the
game is relaunched. It may be an emulator or `adb` effect; it has not been tested with a real controller.

Limits: software-rendered cores only (hardware rendering is refused), no core options, one controller, the player
draws through the system compositor (no shaders), and only GBA has a real core. The rest need downloads and a
licence check each. Sound was measured by underrun count only; the emulator says nothing about latency.

Not done yet: art fetching, the Systems shelf, cheats, button remapping, a BIOS check, disc swapping, and the
shelf creates one view per game, so a library of hundreds needs recycling.
Not verified: anything on the real device. Only checked on the `retroid-sim` emulator, which keeps system bars
on screen (the layout fits what the window leaves). The D-pad is handled as key events only; stick or hat
motion events are not handled yet.

## Build

No Gradle. `build.sh` runs javac, d8, aapt2, zipalign and apksigner from the Android SDK, and builds the
native code with the NDK's clang (arm64 only).

    ./build.sh            # build/lantern-debug.apk
    ./build.sh install    # and install on the connected device or emulator

If your shell uses asdf shims for Java, run it through a shell that sets `JAVA_HOME`
(the script puts `$JAVA_HOME/bin` first on PATH). For example Nova's `scripts/env.sh ./build.sh install`.

Make it the home screen on an emulator:

    adb shell cmd package set-home-activity app.lantern/.HomeActivity

## Later

Moving to Gradle, Kotlin and Compose needs downloads (Gradle, Kotlin, AndroidX). Do that when the UI outgrows plain Views.
The built-in player's native host is a separate decision (see the plan).
