package app.lantern;

import android.content.Context;

import java.io.File;

/** Which libretro core plays which system. Cores are shared libraries bundled in the app. */
final class Cores {
    private Cores() {}

    /** Library name without "lib" and ".so", or null when no core is bundled for the system. */
    static String libFor(String systemFolder) {
        switch (systemFolder) {
            case "test":
                return "testcore";
            case "gba":
                return "mgba";
            default:
                return null;
        }
    }

    static File file(Context c, String systemFolder) {
        String lib = libFor(systemFolder);
        if (lib == null) return null;
        File f = new File(c.getApplicationInfo().nativeLibraryDir, "lib" + lib + ".so");
        return f.exists() ? f : null;
    }

    static boolean has(Context c, String systemFolder) {
        return file(c, systemFolder) != null;
    }
}
