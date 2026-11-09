package app.lantern;

import android.content.Context;

import java.io.File;

/**
 * Which libretro core plays which system, and where it is. A core is either packaged in the APK (a build made with
 * cores in native/cores/) or downloaded by the person into the app's own storage (see {@link CoreInstaller}); Lantern
 * itself ships none.
 */
final class Cores {
    private Cores() {}

    /** A core Lantern can download, what it plays and its licence (shown before anything is downloaded). */
    static final class Info {
        final String lib, name, plays, licence;
        final boolean nonCommercial;

        Info(String lib, String name, String plays, String licence, boolean nonCommercial) {
            this.lib = lib;
            this.name = name;
            this.plays = plays;
            this.licence = licence;
            this.nonCommercial = nonCommercial;
        }
    }

    /** The cores Lantern knows how to use. Their files come from libretro's build server, never from Lantern. */
    static final Info[] CATALOG = {
        new Info("mgba", "mGBA", "Game Boy Advance", "MPL-2.0", false),
        new Info("gambatte", "Gambatte", "Game Boy, Game Boy Color", "GPL-2.0", false),
        new Info("fceumm", "FCEUmm", "NES", "GPL-2.0", false),
        new Info("snes9x", "Snes9x", "Super Nintendo", "non-commercial", true),
        new Info("genesis_plus_gx", "Genesis Plus GX", "Mega Drive, Master System, Game Gear",
                "non-commercial", true),
        new Info("mupen64plus_next_gles3", "Mupen64Plus-Next", "Nintendo 64", "GPL-2.0", false),
        new Info("melonds", "melonDS", "Nintendo DS", "GPL-3.0", false),
        new Info("dolphin", "Dolphin", "Nintendo GameCube", "GPL-2.0", false),
        new Info("pcsx2", "ParaLLEl PS2 (PCSX2)", "PlayStation 2 (needs your own BIOS file)", "GPL-3.0 (PCSX2's; not confirmed for this build)", false),
    };

    /** Library name without "lib" and ".so", or null when Lantern has no core for the system. */
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
            case "n64":
                return "mupen64plus_next_gles3";
            case "nds":
                return "melonds";
            case "gc":
                return "dolphin";
            case "ps2":
                return "pcsx2";
            case "md":
            case "mastersystem":
            case "gamegear":
                return "genesis_plus_gx";
            default:
                return null;
        }
    }

    /** The downloadable core for a system, or null if there is none. */
    static Info infoFor(String systemFolder) {
        String lib = libFor(systemFolder);
        if (lib == null) return null;
        for (Info i : CATALOG) if (i.lib.equals(lib)) return i;
        return null;
    }

    /** Where a downloaded core lives: the app's own storage, which only Lantern can read. */
    static File downloadedFile(Context c, String lib) {
        return new File(new File(c.getFilesDir(), "cores"), "lib" + lib + ".so");
    }

    static File packagedFile(Context c, String lib) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "lib" + lib + ".so");
    }

    // Cores rarely change while the app runs, so each is looked for once; forget() after a download or removal.
    private static final java.util.Map<String, File> FOUND = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> ABSENT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void forget() {
        FOUND.clear();
        ABSENT.clear();
    }

    /** The core library to load for a system: the one in the APK if there is one, else a downloaded one, else null. */
    static File file(Context c, String systemFolder) {
        String lib = libFor(systemFolder);
        return lib == null ? null : fileForLib(c, lib);
    }

    static File fileForLib(Context c, String lib) {
        File known = FOUND.get(lib);
        if (known != null) return known;
        if (ABSENT.contains(lib)) return null;
        for (File f : new File[]{packagedFile(c, lib), downloadedFile(c, lib)}) {
            if (f.isFile()) {
                FOUND.put(lib, f);
                return f;
            }
        }
        ABSENT.add(lib);
        return null;
    }

    static boolean packaged(Context c, String lib) {
        return packagedFile(c, lib).isFile();
    }

    static boolean has(Context c, String systemFolder) {
        return file(c, systemFolder) != null;
    }

    /** True when a system has no core yet but one can be downloaded. */
    static boolean canAdd(Context c, String systemFolder) {
        return !has(c, systemFolder) && infoFor(systemFolder) != null;
    }
}
