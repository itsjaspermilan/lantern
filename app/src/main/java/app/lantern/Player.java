package app.lantern;

import android.view.Surface;

/** The native libretro host (native/player/player.c). Only one game runs at a time. */
final class Player {
    private Player() {}

    static {
        System.loadLibrary("lanternplayer");
    }

    /**
     * Loads the core and the game on a new thread and starts running it. startSlot is a save state to
     * load right away: -1 for none, 0 for the resume point, 1 to 9 for a slot. False if it could not begin.
     */
    static native boolean nativeStart(String corePath, String romPath, String systemDir, String saveDir,
                                      Surface surface, int startSlot);

    /** Stops the game and waits for the emulation thread to finish. */
    static native void nativeStop();

    /** A RetroPad button (0 to 15, as in libretro.h) went down or up. */
    static native void nativeButton(int id, boolean down);

    /** Freezes the game and its sound, or lets it go on. */
    static native void nativePause(boolean paused);

    /** 1 is normal speed, 2 to 4 faster, 0 as fast as it will go. Sound is off while not at 1. */
    static native void nativeSpeed(int speed);

    /** Saves a state: slot 0 is the resume point, 1 to 9 the person's slots. Returns bytes written, or 0. */
    static native int nativeSaveState(int slot);

    static native boolean nativeLoadState(int slot);

    /** Width in the high 16 bits, height in the low 16, or 0 before the first frame. */
    static native int nativeFrameSize();

    /** Copies the latest frame into the array as ARGB pixels (width x height ints). */
    static native boolean nativeSnapshot(int[] out);

    /** One line about what the player is doing: speed, sound, errors. */
    static native String nativeStatus();

    /** Width over height the picture should be shown at. */
    static native float nativeAspect();
}
