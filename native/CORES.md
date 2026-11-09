# Emulator cores

**Lantern does not include any emulator core.** There are two ways a core gets onto a device:

1. **Downloaded in the app** (the normal way, and the only way for a release APK): *Select > Emulators*, or pressing
   Play on a game whose system has no core yet. `CoreInstaller.java` downloads the core the person chose from
   libretro's build server (address below) into the app's own storage (`files/cores/`), checks that it is a zip
   holding a 64-bit ARM shared library of a sane size, and the player loads it from there. Each person downloads
   their own copy from libretro, so Lantern never passes a core on. The list shows each core's licence first.
2. **Packaged at build time**: someone who builds Lantern can put cores in `native/cores/` (git-ignored, never
   committed), and `build.sh` packages them. A packaged core wins over a downloaded one.

This file records which cores Lantern has been used
with, where they come from and what licence applies, so the terms are not forgotten.

The cores Lantern's author uses were downloaded on 2026-11-05 (Mupen64Plus-Next on 2026-11-08) from libretro's nightly Android build server,
`https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/<core>_libretro_android.so.zip`. They are nightly builds with no signature: the only integrity checks made were the size
and a SHA-256 of the zip, and that the unzipped file is a 64-bit ARM ELF that exports the libretro functions and
needs only libc, libm and libdl. None of them was scanned or reviewed beyond that.

| Core | Plays | Version shown by the core | Zip size (bytes) | SHA-256 of zip (start) | Licence (read from the repository) |
|---|---|---|---|---|---|
| mGBA | Game Boy Advance | 0.11-212-7a12d6d | 419,371 | 3edd6395280e8bfd | MPL-2.0 (from packager metadata, LICENSE file not re-read) |
| Gambatte | Game Boy, Game Boy Color | v0.5.0-netlink 77f2567 | 434,140 | 4e3572408245cb9f | GPL-2.0 (`gambatte-libretro/COPYING`) |
| FCEUmm | NES | (SVN) e0f3086 | 452,046 | af71a4a924381d7d | GPL-2.0 (`libretro-fceumm/Copying`) |
| Snes9x | Super Nintendo | 1.63 fae2fea | 890,362 | 0c9f532b595ff220 | Snes9x licence: custom, **non-commercial only** (`snes9x/LICENSE`) |
| Mupen64Plus-Next (`mupen64plus_next_gles3`) | Nintendo 64 | 3.0-Vulkan 15bada8 | 2,201,377 | b39f1f708e174540 | GPL-2.0 (from packager metadata, LICENSE file not re-read); needs OpenGL ES 3 |
| melonDS (`melonds`) | Nintendo DS | 0.9.3 | 690,401 | c44d46ef21c64791 | GPL-3.0 (from packager metadata, LICENSE file not re-read); runs without BIOS files (FreeBIOS) |
| Dolphin (`dolphin`) | Nintendo GameCube | 2609.0.169+32c61f9d0e | 6,421,348 | 0c67e48c6008608a | GPL-2.0-or-later (from packager metadata, LICENSE file not re-read); needs OpenGL ES 3 |
| ParaLLEl PS2 (`pcsx2`) | PlayStation 2 | shown as "ParaLLEl PS2" | 2,894,475 | d276acef74e0a1f1 | PCSX2 is GPL-3.0; **not confirmed for this build** (no licence file was found in the libretro repository by the usual names). **Needs the person's own PS2 BIOS** in `Lantern/bios/pcsx2/bios/`. Draws with OpenGL ES; 60 fps in the one game tried |
| Genesis Plus GX | Mega Drive, Master System, Game Gear | v1.7.4 f27655b | 1,133,700 | dde6f5b31b227d68 | Custom BSD-style, **non-commercial only**, source must accompany a modified binary; bundled parts have their own terms (`Genesis-Plus-GX/LICENSE.txt`) |

## What this means

- On your own device, for your own use, none of this is a problem.
- A release APK must be built with an empty `native/cores/`, so that it contains no core; people then download the
  ones they want in the app.
- **Do not redistribute an APK that contains Snes9x or Genesis Plus GX** (their licences are non-commercial only). Lantern's repository and any release built from it contain no cores.
- Sharing an APK with GPL cores (Gambatte, FCEUmm) means offering their source and the GPL text with it. Whether
  Lantern itself then has to be GPL depends on how the cores are combined with it (they are separate shared
  libraries loaded at run time, which is the usual argument for keeping them apart, but that is a legal question
  this file does not settle).
- The libretro API header (`native/third_party/libretro/libretro.h`) is MIT and separate from all of the above.
- Before sharing a build that contains any core: check its licence, ship the licence texts and offer the source of GPL
  cores, or leave the cores out and let people add their own.

## Systems and folders

`Cores.java` lists the downloadable cores (`CATALOG`, with the licence shown in the app) and maps a folder under `Lantern/roms/` to a core: gba to mGBA; gb and gbc to Gambatte; nes to FCEUmm;
snes to Snes9x; md, mastersystem and gamegear to Genesis Plus GX. n64 to Mupen64Plus-Next; nds to melonDS; gc to Dolphin (GameCube only: .iso, .gcm, .ciso, .gcz, .rvz). Other folders (psx, psp, ps2, dc, saturn,
xbox) have no core yet (ps2 uses ParaLLEl PS2 (`pcsx2`). libretro's `play` (Play!) core was tried first: it needs no BIOS but ran at 25 to 45 frames a second, and its licence could not be confirmed, so it is not used); libretro has no Xbox core at all. BIOS files are the person's own; none is bundled or needed for the systems above.
