package app.lantern;

import android.view.Surface;

/** The native libretro host (native/player/player.c). Only one game runs at a time. */
final class Player {
    private Player() {}

    static {
        System.loadLibrary("lanternplayer");
    }

    /** Loads the core and the game on a new thread and starts running it. Returns false if it could not begin. */
    static native boolean nativeStart(String corePath, String romPath, String systemDir, String saveDir, Surface surface);

    /** Stops the game and waits for the emulation thread to finish. */
    static native void nativeStop();

    /** A RetroPad button (0 to 15, as in libretro.h) went down or up. */
    static native void nativeButton(int id, boolean down);

    /** 1 asks for a save state, 2 for loading it, on the next frame boundary. */
    static native void nativeStateRequest(int what);

    /** One line about what the player is doing: speed, sound, errors. */
    static native String nativeStatus();

    /** Width over height the picture should be shown at. */
    static native float nativeAspect();
}
