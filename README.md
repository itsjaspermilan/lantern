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
`Lantern/roms/test/` plays it. F5 and F7 save and load one state (dev keys), Back leaves.

Checked on the emulator: 320x240 at 59.9 to 60.1 fps, audio stream running (a handful of start-up underruns),
D-pad and face buttons reach the core (Android A arrives as RetroPad B, by position), save state writes 16 bytes
and loading restores the picture, and `am crash` on the player process leaves Lantern running.
`native/third_party/libretro/libretro.h` is the official MIT header, copied from GameStudio (see its NOTICE.md).

Spike limits: software-rendered cores only (hardware rendering is refused), no core options, one controller,
the system scales the picture (no integer scaling, shaders or exact aspect), leaving the game ends it with no
resume point, and there is no in-game menu yet. No real core (GBA, SNES and so on) is bundled. Getting those
means downloading prebuilt cores or building them from source, and checking each one's licence.
Sound was measured by underrun count only. Nobody has listened to it, and the emulator says nothing about
latency on the Retroid.

Not done yet: art fetching, the Systems shelf, the in-game menu, resume points, SRAM saves, real cores.
The game page has no Resume, Save states or Game settings rows because the menu they belong to is not built. The shelf creates one view per game, so a library of hundreds needs recycling.
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
