#!/bin/bash
# Gradle-free debug build: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Needs a JDK on PATH and the Android SDK (ANDROID_HOME, default ~/Library/Android/sdk).
#   ./build.sh            build build/lantern-debug.apk
#   ./build.sh install    build, install on the connected device or emulator
set -euo pipefail
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR="$SDK/platforms/android-36/android.jar"
[ -f "$JAR" ] || { echo "build: missing $JAR" >&2; exit 1; }
OUT="$ROOT/build"
rm -rf "$OUT/classes" "$OUT/dex" "$OUT/base.apk" "$OUT/aligned.apk"
mkdir -p "$OUT/classes" "$OUT/dex"

# Prefer JAVA_HOME's tools: the asdf shims on PATH need a version file in this folder.
[ -z "${JAVA_HOME:-}" ] || export PATH="$JAVA_HOME/bin:$PATH"
javac -source 11 -target 11 -Xlint:-options -classpath "$JAR" -d "$OUT/classes" \
    $(find app/src/main/java -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest app/src/main/AndroidManifest.xml "$OUT/res.zip" \
    -A app/src/main/assets -I "$JAR" --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 0.0.1
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)

# Native code: the player host and the test core, built with the NDK for 64-bit ARM.
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
CC=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang | head -1)
LIBDIR="$OUT/lib/arm64-v8a"
rm -rf "$OUT/lib" && mkdir -p "$LIBDIR"
CFLAGS="-shared -fPIC -O2 -Wall -Wextra -Inative/third_party/libretro"
"$CC" $CFLAGS -o "$LIBDIR/liblanternplayer.so" native/player/player.c -llog -landroid -laaudio -ldl -lm
"$CC" $CFLAGS -o "$LIBDIR/libtestcore.so" native/testcore/testcore.c -lm
# Real cores the person has downloaded into native/cores/ (git-ignored, never committed):
# mgba_libretro_android.so is packaged as libmgba.so, because Android only unpacks lib*.so files.
for core in native/cores/*_libretro_android.so; do
    [ -f "$core" ] || continue
    name=$(basename "$core" _libretro_android.so)
    cp "$core" "$LIBDIR/lib$name.so"
done
# Stored, not compressed, so zipalign can page-align them.
(cd "$OUT" && zip -q -0 -r base.apk lib)
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

# A throwaway debug key, generated here and never committed (build/ is ignored).
KEYSTORE="$OUT/debug.keystore"
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Lantern Debug" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/lantern-debug.apk" "$OUT/aligned.apk"
echo "built $OUT/lantern-debug.apk"

if [ "${1:-}" = install ]; then
    "$SDK/platform-tools/adb" install -r "$OUT/lantern-debug.apk"
fi
