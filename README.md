<p align="center">
  <img src="docs/images/lantern-logo.svg" alt="Lantern logo" width="160">
</p>

<h1 align="center">Lantern</h1>

<p align="center">
  A console-style home screen for Android handhelds, with a built-in player for retro games.<br>
  Gamepad first. Open source (MIT). Made for the Retroid Pocket 6.
</p>

<p align="center">
  <img src="docs/images/recent-resume.jpg" alt="The Recent screen: a big tile showing where you left off, and a row of recent games" width="880">
</p>

> The games, covers and screenshots in this README are made up for the project (original artwork, invented titles).
> Lantern ships with **no games, no BIOS files and no emulator cores**: see [Set it up](#set-it-up) and
> [Legal](#legal).

## What it is

Lantern replaces the stock launcher on a handheld. Every game is a tile, whether it is a retro ROM, an Android game or
an app. Press A and you are playing. There are no menus to dig through, and everything works from the gamepad.

- **Recent**: one big tile for the game you played last, showing a screenshot of where you left off, and a row of
  your other recent games. Press A to jump straight back in, Down for the game's page, Y to remove a game from the list.
- **Systems** and **All games**: uniform tiles in a grid, with the focused game's name under it.
- **Search**: an on-screen keyboard with live results.
- **A built-in player** for libretro cores: save states, a resume point every time you leave, cheats, button remapping
  per game or per console, fast-forward, integer scaling and scanlines.
- **Cover art** from your own pictures, or fetched from the public libretro thumbnail server if you turn that on
  (it is off by default). Games without art get a generated tile.
- Smooth, direction-aware motion: the screen moves the way you press.

<p align="center">
  <img src="docs/images/all-games.jpg" alt="All games grid" width="430">
  <img src="docs/images/systems.jpg" alt="Systems grid" width="430">
</p>
<p align="center">
  <img src="docs/images/details.jpg" alt="A game's page" width="430">
  <img src="docs/images/search.jpg" alt="Search with the on-screen keyboard" width="430">
</p>
<p align="center">
  <img src="docs/images/recent-cover.jpg" alt="Recent, with wide cover art" width="430">
  <img src="docs/images/menu.jpg" alt="The Select menu" width="430">
</p>

## Status

Early (version 0.0.1). It is built and tested on a **Retroid Pocket 6** (Android 13, 1920x1080, landscape). It should
run on other 64-bit ARM Android devices (Android 8 or newer) but has not been tried on them, and the layout assumes a
16:9 landscape screen. It is a sideloaded debug build: there is no release APK yet, no Play Store listing and no
auto-update. Emulation covers software-rendered cores only (Game Boy, GBA, NES, SNES, Mega Drive and friends); there are
no N64, PlayStation, DS or PSP cores yet. See [TODO.md](TODO.md).

## Set it up

You need a computer to build Lantern, and the handheld to run it. Setup has five parts: build, install, give it access
to your games, add the emulators you want (in the app), and (optionally) make it your home screen.

### 1. Build it

Requirements on your computer (macOS or Linux; the build is a shell script, not Gradle):

| Needed | Notes |
|---|---|
| **JDK 17** | `java`, `javac` and `keytool` on your `PATH` (or set `JAVA_HOME`). |
| **Android SDK** | Set `ANDROID_HOME` (default `~/Library/Android/sdk`). Install **platform `android-36`**, the latest **build-tools** and **platform-tools** (for `adb`). |
| **Android NDK** | Any recent one (tested with r29). Set `ANDROID_NDK_HOME`, or it uses the newest in `$ANDROID_HOME/ndk`. It builds the native player. |
| `zip`, `unzip`, `curl` | Usually already installed. |

Install the SDK pieces with Android Studio's SDK Manager, or with the command line tools:

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools" "ndk;29.0.14206865"   # versions as tested; newer ones work too
```

Then build:

```sh
git clone https://github.com/itsjaspermilan/lantern.git
cd lantern
./build.sh              # produces build/lantern-debug.apk
```

The build signs the APK with a throwaway debug key that it creates in `build/` (never committed). Run the tests with
`./test.sh` (no device needed).

### 2. Install it on the handheld

1. On the handheld, enable **Developer options** and **USB debugging**, and connect it to your computer.
2. Check it shows up with `adb devices`, then install:

```sh
./build.sh install      # builds, then runs: adb install -r build/lantern-debug.apk
```

(Or copy `build/lantern-debug.apk` to the device and open it; you will need to allow installs from that app.)

### 3. Give it access to your games

Lantern reads game folders in shared storage, which Android treats as "all files access". The first time you open it with
no games, press **Select** for the menu; if access is missing it shows an **Allow file access** row that opens the right
settings page (or go to *Settings > Apps > Lantern > Permissions > All files access*).

Then put your games here, one folder per system. Lantern does not create the `roms` folders: make them yourself.

```
/sdcard/Lantern/roms/<system>/<your games>
```

| Folder | System | File types | Core Lantern uses |
|---|---|---|---|
| `nes` | NES / Famicom | `.nes` `.unf` `.unif` | FCEUmm |
| `snes` | Super Nintendo | `.sfc` `.smc` | Snes9x |
| `gb` | Game Boy | `.gb` `.gbc` | Gambatte |
| `gbc` | Game Boy Color | `.gbc` `.gb` | Gambatte |
| `gba` | Game Boy Advance | `.gba` | mGBA |
| `md` | Mega Drive / Genesis | `.md` `.gen` `.smd` | Genesis Plus GX |
| `mastersystem` | Master System | `.sms` | Genesis Plus GX |
| `gamegear` | Game Gear | `.gg` `.sms` | Genesis Plus GX |
| `n64`, `nds`, `psx`, `psp`, `saturn`, `dc`, `ps2` | listed, but **no core yet** | various | none |

Copy the folders over USB, with `adb push roms /sdcard/Lantern/` (from a local folder named `roms`), or with any file manager. Installed Android games
and apps appear on their own (apps are hidden from the shelves until you switch them on in the menu).

Other folders under `/sdcard/Lantern/` (all optional):

| Folder | What goes in it |
|---|---|
| `bios/` | BIOS files a core needs. Yours to supply: Lantern includes none, and the cores listed above need none. |
| `saves/<system>/` | Created by Lantern: in-game saves, save states and the resume point and its screenshot. |
| `art/<system>/<game>.png` (or `.jpg`) | Your own cover for a game, named like its ROM without the extension. |
| `art/androidgames/<package>.png` | Cover for an Android game, named after its package (or its title). |
| `cheats/<system>/<game>.cht` | Cheats in RetroArch's `.cht` format, named like the ROM. |

### 4. Add emulators

Lantern plays games with **libretro cores**: separate emulator libraries. None is included, because cores have their
own licences (some are GPL, some are non-commercial only). You add the ones you want **in the app**:

- Press **Select**, choose **Emulators**, pick one and press **A**. It shows what each plays and its licence, then
  downloads it (about 0.4 to 1.1 MB each) from libretro's public build server, `buildbot.libretro.com`, into Lantern's
  own storage. Press A twice on an installed one to remove it.
- Or just press **Play** on a game: if its system has no emulator yet, the list opens on the one it needs.

The handheld needs an internet connection for this step only.

**If you build Lantern yourself** you can instead package cores into the APK: put `<name>_libretro_android.so` files
(64-bit ARM) in `native/cores/`, or let `scripts/fetch-cores.sh --list` / `scripts/fetch-cores.sh mgba gambatte`
download them there (it asks first), then `./build.sh install`. A packaged core wins over a downloaded one. Read
[native/CORES.md](native/CORES.md) first, and **keep an APK that contains cores to yourself**: the licences of some
cores do not allow passing it on.

### 5. Make it your home screen (optional)

Press the Home button. Android asks which home app to use; choose Lantern and *Always*. Or:

```sh
adb shell cmd package set-home-activity app.lantern/.HomeActivity
```

Or *Settings > Apps > Default apps > Home app*. To go back to the stock launcher, choose it the same way.

### Cover art

Press **Select** and turn **Cover art** on. Lantern then looks up box art for games that have none, from the public
[libretro thumbnails](https://github.com/libretro-thumbnails) server, once, and keeps what it finds in its own
storage. Apart from downloading the emulators you choose, this is the only thing Lantern uses the network for, and it is off
until you turn it on. Names are matched to the
ROM's file name (the usual "No-Intro" style, e.g. `Game Name (USA).gba`). To use a different mirror, put its address on
the first line of `/sdcard/Lantern/art-source.txt`.

## Using it

| Button | What it does |
|---|---|
| **D-pad** | Move. On Recent: Left and Right choose a game, Down opens its page, A plays at once. |
| **A** | Play / open. |
| **B** | Back (out of a page, a system or a search). |
| **X** | Search. |
| **Y** | On Recent: take the big game off the list (its saves are kept). |
| **L1 / R1** | Previous / next tab. |
| **L2 / R2** | Jump to the previous / next letter in a grid. |
| **Select** | The menu: Android apps, emulators, switches, clock. |

Inside a game, the **Home/Mode** button, **Back**, or **Select + Start** open the game's menu: resume, save and load
states (several slots), cheats, controls (remap any button, for this game or for the whole console), options (scaling,
aspect, scanlines), and quit. Holding **R2** fast-forwards. Leaving a game any way (Home, the menu, the screen turning off) saves
a resume point, so **Resume** puts you back exactly where you were.

## Project layout

| Path | What is there |
|---|---|
| `app/src/main/java/app/lantern/` | The launcher and player UI, in plain Java (no Gradle, no libraries). |
| `native/player/player.c` | The native player host: loads a core, runs it on its own thread, video, audio, input, saves. |
| `native/testcore/` | A tiny libretro core that needs no game, used to test the player. |
| `native/third_party/libretro/` | The libretro API header (MIT). |
| `tests/`, `test.sh` | Checks of the plain logic, run on the computer. |
| `scripts/fetch-cores.sh` | Optional helper that downloads emulator cores to package into your own build. |
| `docs/` | `development-notes.md` (a log of how it was built and measured), images. |
| `AUDIT.md` | A code quality and performance audit, with measurements. |
| `licenses/`, `THIRD_PARTY_NOTICES.md` | Licences of everything that is not Lantern's own. |

## Troubleshooting

- **No games show up.** Check *All files access* is allowed, the folder names match the table (lower case), and the files
  have the right extensions.
- **"Press A to add the emulator for ..."** The system's emulator is not added yet: see step 4.
- **An emulator download fails.** Check the handheld is online; libretro's build server is sometimes slow or down, so
  try again later. Systems marked "No emulator ... yet" (N64, PlayStation and others) have none in Lantern at all.
- **A game does not start.** Some games need a BIOS file in `/sdcard/Lantern/bios/`; the cores above do not. Check
  the file is not damaged.
- **No cover art.** Turn Cover art on in the menu, make sure the file names are in the usual "Game Name (Region)" form, or
  add your own picture to `art/<system>/`.
- **The build fails on `android-36` or the NDK.** Check `ANDROID_HOME` and `ANDROID_NDK_HOME` point where you think.

## Contributing

Issues and pull requests are welcome. Please run `./test.sh` and check your change on a device if you can: much of the
interface is motion and layout, which tests do not cover. Keep new dependencies and assets compatible with the MIT licence
and record them in `THIRD_PARTY_NOTICES.md`.

## Legal

Lantern's own code is released under the **MIT licence** (see [LICENSE](LICENSE)). The third-party material in the
repository (the libretro header, console icons from libretro's retroarch-assets under CC BY 4.0, Kenney's button
prompts under CC0, and Google's Material "android" icon under Apache-2.0) is listed with its licence in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

- **Games and BIOS files are yours to supply.** Lantern includes none. Only use games you own or have the right to use.
- **Emulator cores are not included** and have their own licences (GPL, MPL, and some non-commercial only). The app
  downloads them, when you ask, straight from libretro to your device. See [native/CORES.md](native/CORES.md). An APK
  that contains cores should not be redistributed.
- **Cover art** fetched from the thumbnail server belongs to its publishers and artists. Lantern only displays it on your
  device.
- Console and platform names are trademarks of their owners. Lantern is independent and not affiliated with or endorsed
  by any of them.

This is not legal advice.
