# Emulator cores in use

The core binaries live in `native/cores/` (git-ignored, never committed). This file records what they are, where
they came from and what licence applies, so the folder can be rebuilt and the terms are not forgotten.

All were downloaded on 2026-11-05 from libretro's nightly Android build server,
`https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/<core>_libretro_android.so.zip`, with the owner's
approval for each file. They are nightly builds with no signature: the only integrity checks made were the size
and a SHA-256 of the zip, and that the unzipped file is a 64-bit ARM ELF that exports the libretro functions and
needs only libc, libm and libdl. None of them was scanned or reviewed beyond that.

| Core | Plays | Version shown by the core | Zip size (bytes) | SHA-256 of zip (start) | Licence (read from the repository) |
|---|---|---|---|---|---|
| mGBA | Game Boy Advance | 0.11-212-7a12d6d | 419,371 | 3edd6395280e8bfd | MPL-2.0 (from packager metadata, LICENSE file not re-read) |
| Gambatte | Game Boy, Game Boy Color | v0.5.0-netlink 77f2567 | 434,140 | 4e3572408245cb9f | GPL-2.0 (`gambatte-libretro/COPYING`) |
| FCEUmm | NES | (SVN) e0f3086 | 452,046 | af71a4a924381d7d | GPL-2.0 (`libretro-fceumm/Copying`) |
| Snes9x | Super Nintendo | 1.63 fae2fea | 890,362 | 0c9f532b595ff220 | Snes9x licence: custom, **non-commercial only** (`snes9x/LICENSE`) |
| Genesis Plus GX | Mega Drive, Master System, Game Gear | v1.7.4 f27655b | 1,133,700 | dde6f5b31b227d68 | Custom BSD-style, **non-commercial only**, source must accompany a modified binary; bundled parts have their own terms (`Genesis-Plus-GX/LICENSE.txt`) |

## What this means

- On the owner's own device, none of this is a problem.
- **Do not sell or give away an APK that contains Snes9x or Genesis Plus GX** as part of anything commercial.
- Sharing an APK with GPL cores (Gambatte, FCEUmm) means offering their source and the GPL text with it. Whether
  Lantern itself then has to be GPL depends on how the cores are combined with it (they are separate shared
  libraries loaded at run time, which is the usual argument for keeping them apart, but that is a legal question
  this file does not settle).
- The libretro API header (`native/third_party/libretro/libretro.h`) is MIT and separate from all of the above.
- Before any sharing: get proper legal advice, ship the licence texts, and consider downloading cores at first run
  instead of bundling them.

## Systems and folders

`Cores.java` maps a folder under `Lantern/roms/` to a core: gba to mGBA; gb and gbc to Gambatte; nes to FCEUmm;
snes to Snes9x; md, mastersystem and gamegear to Genesis Plus GX. Other folders (n64, psx, psp, ps2, dc, saturn,
nds) have no core yet. BIOS files are the person's own; none is bundled or needed for the systems above.
