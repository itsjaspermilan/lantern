package app.lantern;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds the games (ROM folders and installed apps) and remembers favourites and play history. */
final class Library {
    private Library() {}

    private static final class Sys {
        final String folder;
        final String name;
        final List<String> exts;

        Sys(String folder, String name, String... exts) {
            this.folder = folder;
            this.name = name;
            this.exts = Arrays.asList(exts);
        }
    }

    /** Folder name under Lantern/roms, short name, file types. */
    private static final Sys[] SYSTEMS = {
        new Sys("nes", "NES", "nes"),
        new Sys("snes", "SNES", "sfc", "smc"),
        new Sys("gb", "Game Boy", "gb", "gbc"),
        new Sys("gba", "GBA", "gba"),
        new Sys("md", "Mega Drive", "md", "gen", "smd"),
        new Sys("n64", "N64", "z64", "n64", "v64"),
        new Sys("nds", "DS", "nds"),
        new Sys("psx", "PlayStation", "cue", "chd", "pbp"),
        new Sys("psp", "PSP", "iso", "cso"),
        new Sys("saturn", "Saturn", "cue", "chd"),
        new Sys("dc", "Dreamcast", "cdi", "gdi", "chd"),
        new Sys("ps2", "PS2", "iso", "chd"),
    };

    static File romRoot() {
        return new File(Environment.getExternalStorageDirectory(), "Lantern/roms");
    }

    /** Reading the shared storage folder needs "all files access" on Android 11 and later. */
    static boolean hasFileAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    static List<Game> load(Context c) {
        SharedPreferences sp = prefs(c);
        Set<String> fav = new HashSet<>(sp.getStringSet("fav", Collections.<String>emptySet()));
        List<Game> out = new ArrayList<>();
        out.addAll(scanRoms());
        out.addAll(listApps(c));
        for (Game g : out) {
            g.fav = fav.contains(g.id);
            g.lastPlayed = sp.getLong("last:" + g.id, 0);
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    static void toggleFav(Context c, Game g) {
        g.fav = !g.fav;
        SharedPreferences sp = prefs(c);
        Set<String> fav = new HashSet<>(sp.getStringSet("fav", Collections.<String>emptySet()));
        if (g.fav) fav.add(g.id); else fav.remove(g.id);
        sp.edit().putStringSet("fav", fav).apply();
    }

    static void markPlayed(Context c, Game g) {
        g.lastPlayed = System.currentTimeMillis();
        prefs(c).edit().putLong("last:" + g.id, g.lastPlayed).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lantern", Context.MODE_PRIVATE);
    }

    private static List<Game> scanRoms() {
        List<Game> out = new ArrayList<>();
        if (!hasFileAccess()) return out;
        for (Sys s : SYSTEMS) {
            File[] files = new File(romRoot(), s.folder).listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                int dot = n.lastIndexOf('.');
                if (!f.isFile() || n.startsWith(".") || dot < 0) continue;
                if (!s.exts.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) continue;
                Game g = new Game();
                g.id = "rom:" + s.folder + "/" + n;
                g.title = cleanTitle(n);
                g.system = s.name;
                g.file = f;
                g.hue = hue(g.title);
                out.add(g);
            }
        }
        return out;
    }

    private static List<Game> listApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<Game> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            Game g = new Game();
            g.id = "app:" + pkg;
            g.title = String.valueOf(ri.loadLabel(pm));
            g.system = "Android";
            g.icon = ri.loadIcon(pm);
            g.hue = hue(pkg);
            g.launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.add(g);
        }
        return out;
    }

    /** "Moonlit Courier (USA) [!].gba" becomes "Moonlit Courier". */
    static String cleanTitle(String fileName) {
        String n = fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", "").replace('_', ' ').trim();
        return n.isEmpty() ? fileName : n;
    }

    private static int hue(String s) {
        return (s.hashCode() & 0x7fffffff) % 360;
    }
}
