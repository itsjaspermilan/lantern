# Lantern: guidance for working on this repository

Lantern is a gamepad-first Android home screen with a built-in libretro player, made for the Retroid Pocket 6
(Android 13, 1920x1080 landscape). Read `README.md` for what it does and how it is set up, `TODO.md` for what is open,
and `AUDIT.md` for the last code-quality and performance pass. `docs/development-notes.md` is a history log, not a
reference: check the code before trusting a line there. If `CLAUDE.local.md` exists, it holds this machine's device
and tool details: read it too.

## How it is built

- Plain Java (no Gradle, no libraries) plus C for the native player. `./build.sh` runs javac, d8, aapt2, the NDK's
  clang, zipalign and apksigner directly; `./build.sh install` also runs `adb install -r`. `./test.sh` runs the
  plain-logic checks on the computer's JVM (no device).
- Needs JDK 17, the Android SDK with platform `android-36` (`ANDROID_HOME`), build-tools, platform-tools and an NDK
  (`ANDROID_NDK_HOME`, or the newest in `$ANDROID_HOME/ndk`).
- Always read the build output: a failed compile can leave an older APK in `build/`, and installing that hides the
  failure.
- Emulator cores are never in the repository. People put their own in the git-ignored `native/cores/`
  (`scripts/fetch-cores.sh` downloads them, after asking); `build.sh` packages whatever is there.

## Code map

| File | What it is |
|---|---|
| `HomeActivity.java` | The home screen: builds the screen, refreshes what is shown, key handling, the info block, launch animation, hints. Still the biggest file; split further when touching it. |
| `Shelf.java` | The tiles: grid layouts (vertical, row-snapped, recycled views) and Recent's big tile with the row under it. |
| `Cover.java` | One tile: cover art fitted on a blur of itself, generated art, the ring, the resume badge. |
| `TabBar.java`, `SearchPanel.java`, `MenuPanel.java`, `DetailsPage.java` | Pieces split out of `HomeActivity`; each talks to it through a small `Host` interface. |
| `Library.java` | Finds games (ROM folders, installed apps), play history, play time, file facts (cached until resume). |
| `ArtCache.java`, `ArtFetcher.java` | Decoding covers off the main thread; optional fetching from the libretro thumbnail server. |
| `PlayerActivity.java`, `PlayerMenu.java`, `Controls.java`, `native/player/player.c` | The player (its own process, `:player`), its menu, button remapping, and the native host that loads a core. |
| `Glow.java`, `LanternLogo.java`, `SystemArt.java`, `UiIcons.java` | Background, logo, system icons, button prompts. |

Layout numbers are in a 1920x1080 "mockup" space and scaled with `u()` / `px()`. Keep that convention.

## How changes are checked

There are few automated tests of the screens, so check on a device:

1. `./build.sh install` and read the output.
2. Drive it with `adb shell input keyevent <code>`: D-pad 19 up, 20 down, 21 left, 22 right; 96 A, 97 B, 99 X, 100 Y,
   102 L1, 103 R1, 108 Start, 109 Select, 4 Back, 3 Home. `adb shell input text "abc"` types into the search.
3. `adb exec-out screencap -p > shot.png` and look at it. Screenshots are slow, so a capture "mid-animation" is usually
   near the end of it; say what was and was not seen.
4. Start from a known state: `adb shell am force-stop app.lantern` then
   `adb shell am start -n app.lantern/.HomeActivity`. The app resumes where it was otherwise.
5. Check `adb logcat` for `FATAL` / `AndroidRuntime` after a run.
6. Frame statistics: `adb shell dumpsys gfxinfo app.lantern reset`, run a key sequence, then `dumpsys gfxinfo`. The median
   frame time varies between runs on the same build (device state), so compare several runs, and trust the janky share.

The APK is not debuggable (`run-as` fails), so the app's preferences cannot be edited from adb. Do not change the
person's library on the device to test something; if a test needs other content (screenshots, for example), set the
real folder aside with a rename, restore it afterwards, and check it is back.

## Rules

- The person plays with a gamepad only: no touch-only features, and Nintendo-style button prompts (A right, B bottom).
- Plain words in the interface and in documentation; few choices, good defaults.
- Motion follows the button pressed (Down moves the screen down, R1 brings the next tab from the right) and nothing may
  show twice or flicker during a transition.
- Licences: Lantern is MIT. Any new third-party code, icon, font or asset must have a compatible licence and be listed
  in `THIRD_PARTY_NOTICES.md` (licence text in `licenses/`). Never commit cores, games, BIOS files, real cover art or
  screenshots that show them; README screenshots use made-up games with original art.
- Downloads and installs (cores, SDK pieces, tools) need the person's approval first.
- Commit each step when asked to, with the GitHub noreply email already set in this repository's git config, and the
  attribution trailer the session specifies. Pushing to `origin` (github.com/itsjaspermilan/lantern) needs a clear yes.
- Report honestly: say what was checked on the device, what was only built, and what was not seen.
