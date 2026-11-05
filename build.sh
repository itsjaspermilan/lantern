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
"$BT/aapt2" link -o "$OUT/base.apk" --manifest app/src/main/AndroidManifest.xml \
    -I "$JAR" --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 0.0.1
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)
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
