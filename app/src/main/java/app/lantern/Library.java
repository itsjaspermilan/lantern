package app.lantern;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds the games (ROM folders and installed apps) and remembers favourites and play history. */
final class Library {
    private Library() {}

    private static final class Sys {
        final String folder;
        final String name;
        final String title;
        final List<String> exts;

        Sys(String folder, String name, String title, String... exts) {
            this.folder = folder;
            this.name = name;
            this.title = title;
            this.exts = Arrays.asList(exts);
        }
    }

    /** Folder name under Lantern/roms, short name (the chip on a tile), full name (the Systems shelf), file types. */
    private static final Sys[] SYSTEMS = {
        new Sys("nes", "NES", "Nintendo Entertainment System", "nes", "unf", "unif"),
        new Sys("snes", "SNES", "Super Nintendo", "sfc", "smc"),
        new Sys("gb", "Game Boy", "Game Boy", "gb", "gbc"),
        new Sys("gbc", "Game Boy Color", "Game Boy Color", "gbc", "gb"),
        new Sys("mastersystem", "Master System", "Sega Master System", "sms"),
        // Game Gear collections often hold a few Master System games (.sms); the same core plays them.
        new Sys("gamegear", "Game Gear", "Sega Game Gear", "gg", "sms"),
        new Sys("gba", "GBA", "Game Boy Advance", "gba"),
        new Sys("md", "Mega Drive", "Mega Drive and Genesis", "md", "gen", "smd"),
        new Sys("n64", "N64", "Nintendo 64", "z64", "n64", "v64"),
        new Sys("nds", "DS", "Nintendo DS", "nds"),
        new Sys("psx", "PlayStation", "PlayStation", "cue", "chd", "pbp"),
        new Sys("psp", "PSP", "PlayStation Portable", "iso", "cso"),
        new Sys("saturn", "Saturn", "Sega Saturn", "cue", "chd"),
        new Sys("dc", "Dreamcast", "Sega Dreamcast", "cdi", "gdi", "chd"),
        new Sys("ps2", "PS2", "PlayStation 2", "iso", "chd"),
        // Development only: an empty "something.test" file plays Lantern's own test core.
        new Sys("test", "Test core", "Test core", "test"),
    };

    /** Ids used for the installed Android apps and Android games on the Systems shelf. */
    static final String APPS = "android";
    static final String GAMES = "androidgames";

    /** "game" or "app", for counts: Android apps are not games. */
    static String unit(String sysId, int n) {
        String w = APPS.equals(sysId) ? "app" : "game";
        return n == 1 ? w : w + "s";
    }

    static boolean isAndroid(String sysId) {
        return APPS.equals(sysId) || GAMES.equals(sysId);
    }

    /** The full name of a system, for its tile on the Systems shelf. */
    static String systemTitle(String sysId) {
        if (GAMES.equals(sysId)) return "Android Games";
        if (APPS.equals(sysId)) return "Android Apps";
        for (Sys s : SYSTEMS) if (s.folder.equals(sysId)) return s.title;
        return sysId;
    }

    /** The short name of a system, as shown on a game's chip. */
    static String systemName(String sysId) {
        if (GAMES.equals(sysId)) return "Android Game";
        if (APPS.equals(sysId)) return "Android App";
        for (Sys s : SYSTEMS) if (s.folder.equals(sysId)) return s.name;
        return sysId;
    }

    static File romRoot() {
        return new File(Environment.getExternalStorageDirectory(), "Lantern/roms");
    }

    /** Where the person puts BIOS files. Lantern checks them and never supplies them. */
    static File biosDir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Lantern/bios");
        d.mkdirs();
        return d;
    }

    /** In-game saves and save states for one system. */
    static File saveDir(String sysId) {
        File d = new File(Environment.getExternalStorageDirectory(), "Lantern/saves/" + sysId);
        d.mkdirs();
        return d;
    }

    /**
     * Cover art for a ROM: a picture in Lantern/art/&lt;system&gt;/&lt;game&gt;.png (or .jpg) wins, then one already
     * fetched. Null if there is none. An app has art only if it carries a `boxart.png` in its assets (Nova games do); otherwise
     * it uses its own icon.
     */
    static File findArt(Context c, Game g, Map<String, Map<String, String>> own, Map<String, Map<String, String>> cached) {
        if (!g.isRom()) return appBoxArt(c, g);
        String base = baseName(g.file);
        Map<String, String> mine = own.get(g.sysId);
        if (mine == null) {
            mine = names(new File(Environment.getExternalStorageDirectory(), "Lantern/art/" + g.sysId));
            own.put(g.sysId, mine);
        }
        for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
            String actual = mine.get((base + ext).toLowerCase(Locale.ROOT));
            if (actual != null) return new File(new File(Environment.getExternalStorageDirectory(), "Lantern/art/" + g.sysId), actual);
        }
        Map<String, String> fetched = cached.get(g.sysId);
        if (fetched == null) {
            fetched = names(ArtFetcher.cacheFile(c, g).getParentFile());
            cached.put(g.sysId, fetched);
        }
        String actual = fetched.get((base + ".png").toLowerCase(Locale.ROOT));
        return actual == null ? null : new File(ArtFetcher.cacheFile(c, g).getParentFile(), actual);
    }

    /**
     * The cover an app ships as `assets/boxart.png`, copied once into Lantern's own files (again after the app is updated) so that
     * it can be read like any other cover. Null if the app has none.
     */
    private static File appBoxArt(Context c, Game g) {
        try {
            PackageManager pm = c.getPackageManager();
            File dir = new File(c.getFilesDir(), "art/apps");
            File out = new File(dir, g.pkg + ".png");
            long updated = pm.getPackageInfo(g.pkg, 0).lastUpdateTime;
            if (out.exists() && out.lastModified() >= updated) return out;
            try (InputStream in = pm.getResourcesForApplication(g.pkg).getAssets().open("boxart.png")) {
                dir.mkdirs();
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) fo.write(buf, 0, n);
                }
            }
            return out;
        } catch (Exception e) {
            return null; // no such asset (most apps), or the package went away
        }
    }

    /** The file names in a folder, keyed by lower case, from one listing instead of one check per game. */
    private static Map<String, String> names(File dir) {
        Map<String, String> m = new HashMap<>();
        String[] list = dir.list();
        if (list != null) for (String n : list) m.put(n.toLowerCase(Locale.ROOT), n);
        return m;
    }

    /** "Advance Wars (USA).gba" becomes "Advance Wars (USA)": the name RetroArch uses for save files. */
    static String baseName(File rom) {
        String n = rom.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** A game's cheats, in RetroArch's .cht format: Lantern/cheats/&lt;system&gt;/&lt;game&gt;.cht. May not exist. */
    static File cheatFile(String sysId, File rom) {
        return new File(Environment.getExternalStorageDirectory(), "Lantern/cheats/" + sysId + "/" + baseName(rom) + ".cht");
    }

    /**
     * A save state file. suffix ".auto" is the resume point, "3" is slot 3. Same names as the native
     * player writes: "<game>.state.auto", "<game>.state3". A screenshot sits next to it as "<file>.png".
     */
    static File stateFile(String sysId, File rom, String suffix) {
        return new File(saveDir(sysId), baseName(rom) + ".state" + suffix);
    }

    /** When the resume point was saved (milliseconds), or 0 if there is none. */
    static long resumeTime(Game g) {
        if (!g.isRom()) return 0;
        File f = stateFile(g.sysId, g.file, ".auto");
        return f.exists() ? f.lastModified() : 0;
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
        Map<String, Map<String, String>> ownArt = new HashMap<>(), fetchedArt = new HashMap<>();
        for (Game g : out) {
            g.art = findArt(c, g, ownArt, fetchedArt);
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
            File dir = new File(romRoot(), s.folder);
            String[] list = dir.list(); // one listing; no per-file check, which is slow for hundreds of games
            if (list == null) continue;
            for (String n : list) {
                int dot = n.lastIndexOf('.');
                if (n.startsWith(".") || dot < 0) continue;
                if (!s.exts.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT))) continue;
                Game g = new Game();
                g.id = "rom:" + s.folder + "/" + n;
                g.title = cleanTitle(n);
                g.system = s.name;
                g.file = new File(dir, n);
                g.sysId = s.folder;
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
            g.pkg = pkg;
            g.title = String.valueOf(ri.loadLabel(pm));
            boolean game = isGame(ri.activityInfo.applicationInfo);
            g.sysId = game ? GAMES : APPS;
            g.system = systemName(g.sysId);
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

    /** Android's own label: the app declares itself a game (category, or the older flag). */
    @SuppressWarnings("deprecation")
    private static boolean isGame(android.content.pm.ApplicationInfo ai) {
        return ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                || (ai.flags & android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0;
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
