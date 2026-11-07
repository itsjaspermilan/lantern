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
            case "gb":
            case "gbc":
                return "gambatte";
            case "snes":
                return "snes9x";
            case "nes":
                return "fceumm";
            case "md":
            case "mastersystem":
            case "gamegear":
                return "genesis_plus_gx";
            default:
                return null;
        }
    }

    /** The cores bundled with the app do not change while it runs, so each is looked for once. */
    private static final java.util.Map<String, File> FOUND = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> ABSENT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static File file(Context c, String systemFolder) {
        String lib = libFor(systemFolder);
        if (lib == null) return null;
        File known = FOUND.get(lib);
        if (known != null) return known;
        if (ABSENT.contains(lib)) return null;
        File f = new File(c.getApplicationInfo().nativeLibraryDir, "lib" + lib + ".so");
        if (f.exists()) {
            FOUND.put(lib, f);
            return f;
        }
        ABSENT.add(lib);
        return null;
    }

    static boolean has(Context c, String systemFolder) {
        return file(c, systemFolder) != null;
    }
}
