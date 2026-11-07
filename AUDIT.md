# Code quality and performance audit (November 2026)

How it was done: compiler lint (`javac -Xlint:all`), a static scan for unused code, Android's frame statistics
(`dumpsys gfxinfo`) and memory (`dumpsys meminfo`) over a fixed key sequence on the Retroid Pocket 6, repeated rounds
to look for leaks, and a StrictMode run to find disk work on the main thread. The key sequences are in the
conversation that produced this file; the numbers below are from that device, not from a desktop.

## Measured

| Check | Result |
|---|---|
| Lint | No errors. Only deprecation warnings for window-inset and system-UI APIs that Android 13 still needs (minSdk 26). |
| Frames over a 90 s key sequence (1,200+ frames) | 0.6% janky by Android's current measure, no missed vsync, no slow UI thread, GPU time 2 ms. Unchanged by the fixes below: the screen was already fast. |
| Memory over four repeated rounds, trimmed after each | Native heap 56, 58, 59, 59 MB; Java heap flat. It levels off, so no leak. |
| Main-thread disk reads (StrictMode) in the same sequence | 209 before, 20 after. The 20 left are one-offs (first look at a game). |

## Fixed

- **A disk call for every tile on every frame.** The art cache worked out its key with the picture's modified time each
  time a tile drew. It is now worked out once per picture and kept on the game.
- **Disk reads on every focus change** (resume point, play time, whether a core exists). Remembered until the launcher
  returns to the front (the player is another process, so that is when they can change); cores are looked up once.
- **A scan of every ROM on the main thread** for the cover-art fetcher (one file check per game, after every resume).
  It now runs on the fetcher's own thread.
- **A save-folder `mkdirs()` just to look for a file**, on the main thread. Looking no longer creates folders.
- **The resume screenshot was read on the main thread while laying out.** It is read in the background and drawn when
  it arrives.
- **Drawing work per tile per frame:** the background gradient was rebuilt on each draw, and painted even under a
  real cover. It is now built once per size and colour, and skipped when a cover covers it. The grid's clip rectangle
  is no longer re-created on each scroll frame.
- **Animators that outlive their view.** The big tile's breathing animation and the tab-name marquee repeat for ever;
  they are now stopped when the view or activity goes away (`onDetachedFromWindow`, `onDestroy`).
- **Memory:** covers are decoded for at most 520 pixels tall (the biggest they are drawn is about 440), not 700.
- **Threading:** `Game.art` is volatile (the fetcher sets it from its own thread); the screenshot and core caches are
  concurrent collections; the player's play-time write reads the file afresh, since it runs in another process.
- **Dead code removed:** an unused aspect-ratio reader and a helper nobody called.
- **Tests:** `./test.sh` runs 21 checks of the plain logic (title cleaning, file names, system names and colours,
  cores, thumbnail names) on the computer's JVM. A deliberately wrong expectation was used to check that it fails.

## Split out of `HomeActivity` (second pass)

Each piece keeps its own state and talks to the launcher through a small `Host` interface, so what it needs is written
down in one place. Behaviour was checked on the Pocket 6 after each move (screens, keys, animations) and the frame
statistics were re-measured after the last one (same as before: 0.6% janky).

| Class | What it owns | Lines |
|---|---|---|
| `SearchPanel` | the on-screen keyboard, what is typed, the live results, their keys | 280 |
| `MenuPanel` | the Select menu: rows, switches, keys | 240 |
| `TabBar` | logo, tabs, the sliding highlight, the breadcrumb and its marquee, battery, clock | 360 |
| `DetailsPage` | a game's page: the cover's flight to and from its tile, the text column, the actions, their keys | 480 |

Small fixes that came with it: the battery percentage was only read on resume and not shown until the screen was
rebuilt (it now updates on resume); the system Back key now closes the details page, the search and the menu (it was
swallowed or ignored: only the B button worked); and an unused "file details" mode of the page was removed.

A note on the frame numbers: the same build measured 5 ms and 18 ms median frame time on different runs on the Pocket
6 (the device's display or power state changes between runs), so the median is not a reliable comparison here. The share
of janky frames stayed under 1% every time. Compare like with like (same state, several runs) before trusting a change.

## Recommended next (not done: larger than a fix)

1. **Keep `HomeActivity.java` shrinking.** It was about 2,200 lines and is now about 1,340. What is left is the shelf and
   screen wiring: building the screen, the refresh of what is shown, the key handling, the info block beside Recent's
   big tile, the launch animation and the hints. The info block and the launch animation are the next natural pieces.
2. **Cover decoding.** Decoding a cover per visible tile is fine today; a grid of several hundred large pictures on a
   weaker device would want the decode size chosen per tile size, not one size for all.
3. **A build that can lint.** The Gradle-free build has no Android Lint, no CI and no instrumented tests. Adding Lint
   (it can run on the compiled classes) would catch missing permissions, unused resources and API-level mistakes.
4. **Deprecated APIs** (window insets, system-UI flags) will need replacing when `targetSdk` is raised.
5. **`Library.load` runs on every resume** and re-reads the library. It is on a background thread and fast with
   hundreds of games; with tens of thousands it should be incremental.
