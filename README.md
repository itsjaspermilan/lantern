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

### On the real Retroid Pocket 6

Read over adb on 2026-11-05 (read-only, then the debug APK installed with the owner's OK; Lantern was not made the
home screen and was given no file access): Retroid Pocket 6, QCS8550, Android 13 (SDK 33), arm64, panel 1080 x 1920
used in landscape, density 369, 60 or 120 Hz (default 120), a 55 px navigation bar, no camera cut-out found in the
display dump, 92 GB free, Retroid's own `com.retroidpocket.gamelauncher` installed. The controller is "Retroid
Pocket Controller" (a gamepad with two sticks, digital and analogue triggers, a d-pad that reports as both hat and
buttons, Mode, Home, Back, App-select and F24 keys).

Button positions, from the raw events while the owner pressed them in a set order (bottom, right, left, top face
button, L1, R1, L2, R2, Select, Start, Home, then others): bottom = `BTN_EAST` (Android B), right = `BTN_SOUTH`
(Android A), left = `BTN_WEST` (Android Y), top = `BTN_NORTH` (Android X). So the Pocket 6 uses Nintendo-style
labels, the same as libretro's RetroPad, and `PlayerActivity` now maps its buttons straight across (A to A, B to B,
X to X, Y to Y), while other pads keep the Xbox-style swap. This rests on the owner having pressed in the order
given; it has not yet been checked by playing a game. Not pressed in that session: Mode, Back, App-select, F24,
the d-pad and the sticks, so the menu button is still undecided (Back, Mode or Select + Start all open the menu).

Seen on the device for the first time: Lantern runs and looks as on the emulator, with the real battery and
clock. It showed one bug the emulator could not (the "needs file access" line overlapped the status line), fixed by
dropping that line and letting the Start hint say "Allow file access to find your games".

The owner's collection was then copied to `/sdcard/Lantern/roms/` (17 system folders, 8,217 files, 12 GB; the
Mac's `.git` folder and `.DS_Store` files were left out). File counts matched for every folder and five files,
including the largest GBA game, were byte-identical by MD5; the rest were not compared. File access was granted by
the owner on the device. Measured on the Pocket 6 with 6,841 games (after adding `.unf` to NES and `.sms` to Game
Gear, which recovered 52 files): the library loads in 294 ms in the background, Lantern is on screen in 89 to
100 ms, and scrolling 300 tiles deep in All games gave 211 frames with a median 5 ms, 99th percentile 8 ms
(the 120 Hz budget is 8.3 ms) and 0.5% janky, with 66 views and 88 MB. Non-game files in those folders (`.xml`,
`.ini`, `.srm`, `.bat`) are ignored. A `Dispatch` Switch package in `/sdcard/Games` was left untouched: Lantern has
no Switch core. Systems with no core yet (N64, Atari 2600/7800/Lynx, Famicom Disk, Neo Geo Pocket, PC Engine,
32X, SG-1000) were copied but only N64 is known to Lantern; the rest do not appear until they are added.

Reported by the owner after playing a GBA game on the Pocket 6: buttons right, picture clipped. Cause: the game
picture was sized for the full 1080 px height while the 55 px navigation bar is drawn over the bottom of the screen,
so the last rows were lost (it had also been visible on the emulator's screenshots and not chased). Fix: the player
first fitted the picture above the bar (1538 x 1025 px); the owner then asked for true full screen, see below. The picture is scaled smoothly by
Android, so it looks soft; Options, Scaling, Integer gives sharp pixels at whole multiples (not judged on the device).

### Shelf speed and the Systems shelf

`Shelf` keeps views only for the tiles on or near the screen (about 10) and reuses them as the row slides, so the
cost no longer grows with the library. The library is read on a background thread (one directory listing per
system and per art folder, not a file check per game) and the screen fills in when it is ready.

Measured on the emulator with 1,095 games (about 1,000 empty fake files, since removed), before and after:
cold start 588 to 599 ms became 126 to 158 ms; views alive 1,146 became 66 to 69; scrolling 30 tiles had a median
frame of 18 ms and 3.8% janky frames, now 17 ms and 1.9%; scrolling 400 tiles deep had 0.4% janky frames; memory
(PSS) 140 MB became 59 to 110 MB. The library load itself takes about 110 to 125 ms for those games. These are
emulator numbers, on a laptop's CPU, not the Retroid's.

The Systems shelf (third tab) has one tile per system that has games, with its game count; A opens that system's
games, the tab shows the system's name, and B goes back to the same tile. Two bugs found and fixed on the way:
going back landed on the first tile, and fresh tiles were drawn at full brightness instead of dimmed.

### Screen fit

The layout is the mockup's 1920 x 1080 scaled to the window, from the very top. The window draws behind a camera
cut-out on every edge (`values-v30/styles.xml`, `windowLayoutInDisplayCutoutMode=always`) and the glow fills the
whole screen. Nothing is reserved at the bottom: the Pocket 6's 55 px strip was black inside Lantern's own buffer, because
Android clips an app's drawing to the system-bar area unless the window asks otherwise. `HomeActivity.fullScreenWindow`
(`FLAG_LAYOUT_NO_LIMITS`, transparent bars, contrast enforcement off) lets Lantern draw there, for the home screen, game
pages, the player (picture 1080 px tall) and the menu. Checked by screenshot on the Pocket 6. The bar stays hidden;
the gesture swipe to reveal it is not yet tested. The emulator wears a "Pixel 5" profile that adds
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
glow and tile colour take their hue from the cover. Android apps use their own icon, unless there is a picture in `Lantern/art/androidgames/` (or `android/` for other apps) named after the package (`com.nova.games.pine_ridge_rally.png`) or the title (`Pine Ridge Rally.png`). Nothing is fetched for apps.
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

### Cores

Put libretro cores in `native/cores/` (git-ignored, never committed). `build.sh` packages every
`<name>_libretro_android.so` there as `lib<name>.so`, and `Cores.java` maps a system folder to a core name.
Present now: mGBA (GBA), Gambatte (Game Boy, Game Boy Color), FCEUmm (NES), Snes9x (SNES) and Genesis Plus GX
(Mega Drive, Master System, Game Gear), all from libretro's nightly Android build server, each downloaded with the
owner's approval. Versions, sizes, checksums and licences are in `native/CORES.md`. Short version: Gambatte and
FCEUmm are GPL-2.0; **Snes9x and Genesis Plus GX are non-commercial only**, so an APK containing them must not be
sold or bundled commercially. Nightly builds are unsigned; each file was checked for size, checksum, 64-bit ARM
and the libretro entry points only.

Test games, one per system, copied from the owner's own collection to the emulator only (never to this repository):
a GBA game, `Tesserae (USA).gb`, `Klustar (USA).gbc`, `Urban Champion (World).nes`, `Space Invaders - The Original
Game (USA).sfc` and `Teddy Boy (USA, Europe).sms`. No Mega Drive game was tested: the collection has no Mega Drive
folder, so Genesis Plus GX was exercised only through its Master System support.

Checked on the emulator, each launched from the shelf with its own core (the status line names the core): Gambatte
ran Tesserae (grey Game Boy palette) and Klustar (colour) at 59.7 fps; FCEUmm ran Urban Champion's attract mode at
60.9 fps; Genesis Plus GX ran Teddy Boy at 60.0 fps; Snes9x ran Space Invaders (`Checksum OK`, the Taito logo) at
60 fps. Snes9x saved an 823,407-byte state with a screenshot and loaded it back ("state loaded"). Not checked:
save states, resume, the menu and fast-forward on the other four new cores; the Mega Drive, Game Gear and
"headered" ROM variants; sound by ear; anything on the Retroid.

Two things that looked like core bugs and were not: a black SNES screen was an empty placeholder file I had
created earlier (the core logged `Corrupt ... CRC32: 00000000`), and 30 to 40 fps with many underruns on the first
Gambatte runs happened within minutes of installing the app. It did not recur once the emulator was idle, which
fits Android compiling the new app in the background, but that was not proven.

Sound: every core shows a steady trickle of underruns on the emulator (roughly one every one to two seconds). The
player has no dynamic rate control and keeps about three frames queued. Whether it is audible is unknown; raising
the queue is the easy knob and costs a frame or two of delay per step.

Diagnostics: F3 shows a status line (core, size, pixel format, fps, ms inside the core, ms waiting in the video
call, pictures per run, share of the picture that is not black, sound state, underruns). The "core ms" figure
includes the video wait, which is the display pacing the game to 60 fps; the core's own work is much smaller. A
game that cannot start (for example an empty file) now says so on screen and Back leaves it.

The first core, mGBA (0.11-212-7a12d6d, MPL-2.0):

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
Controls and the core's own settings are not built, so they are not in the menu.

Leaving a game by Quit, Home or anything that takes the screen saves a resume point (`<game>.state.auto` plus a
screenshot). The game page shows Resume (highlighted) above Play from the start, and the shelf says
"Resume available". Slots are `<game>.state1` to `.state9`, each with `<file>.png`; the resume point is slot 0.
Sleep is different from Home: when the screen turns off mid-game the resume point is saved and the game is put away, but the
player stays open, so waking carries on from that point (even if Android ended the process meanwhile). If the in-game menu was
open it is open again. Tested on the Pocket 6 with the power key (sleep, wake: same screen, game running); Home still ends the game.

### Controls

In-game menu, Controls: every RetroPad button is listed with the key that presses it. Press A on a row, then the
key you want; if that key was doing something else the two swap. The first row decides where changes are kept:
"This game" or "All <console> games" (starting from the console's controls when first switched to a game). Reset puts
the usual buttons back for whichever is in use. Stored in the player's own preferences (`ctl:console:<system>`,
`ctl:game:<system>/<game>`, `ctl:use:...`), as `button:key` pairs, so only changes are kept and defaults can improve.
Defaults are by position (the Retroid's labels differ from an Xbox pad's). Back and Home/Mode always open the menu and
cannot be bound. Physical R2 stays hold-to-fast-forward unless it is given to a game button. Sticks are not mapped yet.
Tested on the Pocket 6 with injected keys (rebind, swap, persistence across a relaunch, reset); not yet with a long real play session.

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
draws through the system compositor (no shaders), and N64, PlayStation, PSP, Saturn, Dreamcast and DS have no core.
Sound was measured by underrun count only; the emulator says nothing about latency.

### Cheats

A game's cheats live in `Lantern/cheats/<system>/<game>.cht`, in RetroArch's format (`cheat0_desc`, `cheat0_code`,
`cheat0_enable`), named like its save. A game with that file gets a Cheats row in the in-game menu, listing each
cheat with On or Off; A or left and right change it, the change reaches the game at once and is written back to the
file's enable lines. The codes go through libretro's cheat calls, so their format is whatever the core takes.
The host resets and re-sends the whole list on each change, because mGBA ignores libretro's enabled flag.
Cheats that patch code or need a master code may only take effect from a fresh start, not after Resume.

Checked on a real Pocket 6 with mGBA, using test codes that write known values to RAM in American Idol: enabled codes
appeared in a save state and a disabled one did not; turning one on in the menu reached the running game; on the next
fresh start the changed file applied and the code turned off was gone. Pokemon Emerald resumed from a NextUI state
with its 2 cheats on and ran.
Not verified: what those Emerald or FireRed cheats do in play, FireRed on the device, and cheats on any other core
(all five export the calls; nothing else was tried). A cheat turned off mid-game leaves what it wrote in RAM.

Not done yet: button remapping, a BIOS check, disc swapping, more cores.
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


## System icons

The Systems shelf shows an icon for each console, from libretro's retroarch-assets (CC BY 4.0). Attribution, source and
licence text are in `licenses/system-icons.md`. Android apps and games use shapes drawn in code (`SystemArt.java`).

### Home layouts

Recent shows the selected game very large with a Resume/Play button; pressing A starts it straight away (from its
resume point when it has one) and Up opens its game page (play from the start, details). Only the selected game is
highlighted. Left and Right change the selection: the next game glides into the big spot and the six after it show in a
small row. Up to twelve recent games are kept there, then a last "All games" tile that opens the All games tab. Small arrows under the big game show which ways you can go (the left one hides on the first game, the right one on the last). Titles are printed under each tile, not on it. The button hints at the bottom are right-aligned. Favourites, Systems, All games, a system's games and search results are
a vertical grid (seven game tiles or six system tiles across) that scrolls a row at a time, with a peek of the next row;
the focused game's name, system and status are one line under the grid. Tiles no longer carry the system chip (it is in
that line). Touch: drag a grid up and down. Checked on the Pocket 6 with injected keys; the touch drag is not tried yet.

