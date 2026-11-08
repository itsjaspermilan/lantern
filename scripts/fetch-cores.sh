#!/bin/bash
# Downloads libretro emulator cores for 64-bit Android into native/cores/, where build.sh picks them up.
#
#   scripts/fetch-cores.sh mgba gambatte fceumm     # the cores you want
#   scripts/fetch-cores.sh --list                   # what Lantern knows how to use
#
# Cores are NOT part of Lantern and have their own licences. Some are non-commercial only. A build that contains
# them is for your own device: do not share it. Read native/CORES.md first. Cores come from libretro's nightly build
# server (unsigned); check what you download.
set -euo pipefail
cd "$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"

BASE="https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"
list() {
    cat <<'LIST'
mgba             Game Boy Advance                              MPL-2.0
gambatte         Game Boy, Game Boy Color                      GPL-2.0
fceumm           NES                                           GPL-2.0
snes9x           Super Nintendo                                non-commercial only
genesis_plus_gx  Mega Drive, Master System, Game Gear          non-commercial only
mupen64plus_next_gles3  Nintendo 64                            GPL-2.0
LIST
}

if [ "${1:-}" = "--list" ] || [ $# -eq 0 ]; then
    list
    exit 0
fi

echo "You are about to download: $*"
echo "These cores have their own licences (see --list and native/CORES.md). Keep builds that contain them to yourself."
read -r -p "Continue? [y/N] " answer
[ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "Cancelled."; exit 1; }

mkdir -p native/cores
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for core in "$@"; do
    list | awk '{print $1}' | grep -qx "$core" || { echo "Unknown core '$core' (see --list)" >&2; exit 1; }
    echo "Downloading $core ..."
    curl -fSL "$BASE/${core}_libretro_android.so.zip" -o "$tmp/$core.zip"
    unzip -o -q "$tmp/$core.zip" -d native/cores
    echo "  $(shasum -a 256 "$tmp/$core.zip" | cut -c1-16)...  $(ls -l "native/cores/${core}_libretro_android.so" | awk '{print $5}') bytes"
done
echo "Done. Run ./build.sh install to package them."
