#!/bin/bash
# Compiles the launcher's plain-logic classes and runs tests/app/lantern/LogicTest.java on this computer's JVM.
# Needs a JDK and the Android SDK (only its android.jar, for the types the classes mention).
set -euo pipefail
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
JAR="$SDK/platforms/android-36/android.jar"
[ -f "$JAR" ] || { echo "test: missing $JAR" >&2; exit 1; }
[ -z "${JAVA_HOME:-}" ] || export PATH="$JAVA_HOME/bin:$PATH"
OUT="$ROOT/build/test-classes"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -source 11 -target 11 -Xlint:-options -classpath "$JAR" -d "$OUT" \
    app/src/main/java/app/lantern/Library.java app/src/main/java/app/lantern/Game.java \
    app/src/main/java/app/lantern/Cores.java app/src/main/java/app/lantern/ArtFetcher.java \
    app/src/main/java/app/lantern/ArtCache.java \
    tests/app/lantern/LogicTest.java
java -cp "$OUT:$JAR" app.lantern.LogicTest
