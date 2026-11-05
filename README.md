# Lantern

A console-style home screen for the Retroid Pocket 6 (landscape, 1920x1080, gamepad first).
Each game is its own tile: emulated ROMs, Android games and Nova games alike. Replaces the stock launcher.

Plan, mockup and decisions: the "Lantern Launcher" artifact (https://claude.ai/artifact/DCNUKbUBFy1EsoYeDYZzCY).

## Status: M0

One screen. Lists installed apps as a row of tiles. D-pad moves focus, A opens the app, Home returns here.
The background takes a tint from the focused tile.

Not done yet: ROM scanning, art, game pages, the built-in libretro player and its menu.
Not verified: anything on the real device. Only checked on the `retroid-sim` Android 13 emulator.

## Build

No Gradle. `build.sh` runs javac, d8, aapt2, zipalign and apksigner from the Android SDK.

    ./build.sh            # build/lantern-debug.apk
    ./build.sh install    # and install on the connected device or emulator

If your shell uses asdf shims for Java, run it through a shell that sets `JAVA_HOME`
(the script puts `$JAVA_HOME/bin` first on PATH). For example Nova's `scripts/env.sh ./build.sh install`.

Make it the home screen on an emulator:

    adb shell cmd package set-home-activity app.lantern/.HomeActivity

## Later

Moving to Gradle, Kotlin and Compose needs downloads (Gradle, Kotlin, AndroidX). Do that when the UI outgrows plain Views.
The built-in player's native host is a separate decision (see the plan).
