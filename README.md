# Lantern

A console-style home screen for the Retroid Pocket 6 (landscape, 1920x1080, gamepad first).
Each game is its own tile: emulated ROMs, Android games and Nova games alike. Replaces the stock launcher.

Plan, mockup and decisions: the "Lantern Launcher" artifact (https://claude.ai/artifact/DCNUKbUBFy1EsoYeDYZzCY).

## Status: M0 done, M1 started

The home screen follows the mockup (1920x1080 layout numbers, scaled to the window): three shelves
(Recent, Favourites, All games) on L1/R1, one row of covers where the focused cover grows, a glow behind
it in the game's colours, the title block, and the button hints.

- Games come from ROM folders (`/sdcard/Lantern/roms/<system>/`, needs "all files access") and from installed Android apps.
- D-pad browses, A opens a game's page, Y favourites, Start turns cover art fetching on or off (or opens the
  file-access setting first if that is missing).
- Favourites and play history are kept in the app's preferences.
- Covers: your own picture, a fetched one, or a generated one (see Cover art below).
- A opens the game's page: Play, add or remove favourite, Details (file or package facts), and for apps
  Android's App settings. L1/R1 move to the previous or next game, Y favourites, B goes back.

### Screen fit

The layout is the mockup's 1920 x 1080 scaled to the window, from the very top. The window draws behind a camera
cut-out on every edge (`values-v30/styles.xml`, `windowLayoutInDisplayCutoutMode=always`) and the glow fills the
whole screen. Only the bottom is kept clear, for a navigation bar. The emulator wears a "Pixel 5" profile that adds
a camera hole and a tall status bar; the Retroid has neither. An earlier version shrank the layout to fit the safe
area and came out about 18% too small. The emulator still draws a black 60 px navigation strip at the bottom that
the app cannot make transparent or hide; not checked on a real device.

### Cover art

Order of preference for a ROM: a picture you put in `Lantern/art/<system>/<game>.png` (or `.jpg`), a cover already
fetched, then (only if switched on with Start) a fetch from libretro's thumbnail server,
`https://thumbnails.libretro.com/<System>/Named_Boxarts/<Game>.png`, by file name, so No-Intro style names work
best. It is off by default and is the only use of the network. One request at a time, it stops after three
failures in a row, refuses anything over 4 MB or that is not a PNG, and remembers a "not found" for a week.
Covers are fitted inside the tile, never cropped, decoded off the main thread and kept in a 24 MB cache, and the
glow and tile colour take their hue from the cover. Android apps still use their own icon (banners are not read).
A first line in `Lantern/art-source.txt` replaces the server address, for a mirror or for testing; plain HTTP is
allowed only for `localhost` (`res/xml/network_security_config.xml`).

Checked on the emulator against a local test server with two made-up pictures, not real covers: the fetch built the
right URLs (system folder names, `(USA)` kept, spaces encoded), got a 200 for the game it had, 404s for six others,
did not ask for the game that had your own picture, made no repeat requests after a relaunch, and the glow followed
the cover's colours.

Real server, one request made by hand with curl (not by the app), with the owner's OK: the address built for
"Advance Wars (USA).gba" on GBA, `.../Nintendo - Game Boy Advance/Named_Boxarts/Advance Wars (USA).png`, returned
200, `image/png`, 430,864 bytes, a 512 x 510 PNG. So the GBA folder name and the file-name rule are confirmed for
that one game, and the picture looks right in the tile and on the game page. NOT checked: the app's own HTTPS fetch
against the real server (TLS, redirects, timing), the other 11 system folder names (from memory), or any game
other than that one. Games it does not know stay generated; there is no hash lookup yet, so a renamed ROM will
miss.

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
