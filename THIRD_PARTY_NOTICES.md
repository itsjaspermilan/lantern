# Third-party material

Lantern's own code is under the MIT licence (see `LICENSE`). This file lists everything in the repository that is not
Lantern's own, and what is deliberately **not** in it.

## In this repository

| Material | Where | Licence | Notes |
|---|---|---|---|
| libretro API header (`libretro.h`) | `native/third_party/libretro/` | MIT, (c) 2010-2024 The RetroArch team | Unmodified. Its licence text is at the top of the file and applies to that header only. |
| Console icons (16 PNG) | `app/src/main/assets/system-icons/` | CC BY 4.0 | From libretro's [retroarch-assets](https://github.com/libretro/retroarch-assets) (`xmb/monochrome/png`). Credit: the RetroArch / libretro contributors. Renamed, otherwise unchanged; Lantern tints them at run time. Licence text: `licenses/retroarch-assets-COPYING.txt`; details: `licenses/system-icons.md`. |
| Button prompts, arrows, grid icon | `app/src/main/assets/ui/` | CC0 1.0 | From [Kenney](https://kenney.nl/assets) (Input Prompts, Game Icons). Credit is welcome, not required. Their licence files: `licenses/kenney-*`. |
| "android" icon | `app/src/main/res/drawable/ic_android.xml` | Apache-2.0 | Google's [Material icons](https://github.com/google/material-design-icons), written as an Android vector drawable. Licence text: `licenses/material-icons-LICENSE.txt`. |
| README screenshots and their made-up cover art | `docs/images/` | MIT (Lantern's own) | The games shown are invented, and their covers were drawn for this project. |
| The Lantern logo and launcher icon | `docs/images/`, `app/src/main/res/drawable/ic_launcher_*`, `LanternLogo.java` | MIT (Lantern's own) | Drawn for this project. |

No fonts are bundled; the interface uses the device's own system font.

## Not in this repository, and why

- **Emulator cores.** Lantern plays games by loading libretro cores (shared libraries) that the person adds themselves
  (see `native/CORES.md`). Cores have their own licences: some are GPL-2.0 or MPL-2.0, and some (Snes9x, Genesis Plus
  GX) are **non-commercial only**. They are not committed, and a build that bundles them must not be redistributed
  without checking each licence. The git-ignored folder `native/cores/` is where a person's own copies go.
- **Games, BIOS files, save files.** Never included. The person supplies their own.
- **Cheats.** Not included. *Get cheats* in the game menu downloads one game's `.cht` file from the libretro database
  (github.com/libretro/libretro-database, CC BY-SA 4.0) when the person asks. The file is kept as it came, in their
  own `Lantern/cheats/` folder.
- **Cover art.** Not included. Lantern can fetch box art from the public libretro thumbnail server when the person
  turns that on (off by default). That art belongs to its publishers and artists; Lantern only displays it on the
  person's own device and does not store or redistribute it. The screenshots in this repository use made-up games with
  original artwork.
- **Android SDK and NDK.** Used to build; not redistributed.

## Names and trademarks

Console and platform names (Game Boy, NES, Super Nintendo, Mega Drive, PlayStation and so on) are used only to say
which systems a game folder is for. They are trademarks of their owners. Lantern is an independent project and is not
affiliated with or endorsed by any of them.
