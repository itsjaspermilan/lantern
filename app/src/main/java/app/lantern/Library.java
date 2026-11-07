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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds the games (ROM folders and installed apps) and remembers play history. */
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
     * fetched. Null if there is none. An app (or game) can have art too: a picture in Lantern/art/<androidgames or android>/ named
     * after its package (com.example.game.png) or its title (Pine Ridge Rally.png); otherwise it uses its own icon.
     */
    static File findArt(Context c, Game g, Map<String, Map<String, String>> own, Map<String, Map<String, String>> cached) {
        String base = g.isRom() ? baseName(g.file) : g.pkg;
        Map<String, String> mine = own.get(g.sysId);
        if (mine == null) {
            mine = names(new File(Environment.getExternalStorageDirectory(), "Lantern/art/" + g.sysId));
            own.put(g.sysId, mine);
        }
        for (String name : g.isRom() ? new String[]{base} : new String[]{g.pkg, g.title}) {
            for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
                String actual = mine.get((name + ext).toLowerCase(Locale.ROOT));
                if (actual != null) return new File(new File(Environment.getExternalStorageDirectory(), "Lantern/art/" + g.sysId), actual);
            }
        }
        if (!g.isRom()) return null;
        Map<String, String> fetched = cached.get(g.sysId);
        if (fetched == null) {
            fetched = names(ArtFetcher.cacheFile(c, g).getParentFile());
            cached.put(g.sysId, fetched);
        }
        String actual = fetched.get((base + ".png").toLowerCase(Locale.ROOT));
        return actual == null ? null : new File(ArtFetcher.cacheFile(c, g).getParentFile(), actual);
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
        // Not saveDir(): that creates the folder, and this is called just to look for a file (on the main thread, often).
        return new File(new File(Environment.getExternalStorageDirectory(), "Lantern/saves/" + sysId), baseName(rom) + ".state" + suffix);
    }

    // What is read from files when a tile is focused is remembered until the app comes back to the front (the player is
    // another process: it is what changes these), so moving across tiles does not touch the disk on the main thread.
    private static final Map<String, Long> RESUME = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> PLAYED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Forget the remembered file facts: call when the launcher returns to the front. */
    static void forgetFileFacts() {
        RESUME.clear();
        PLAYED.clear();
    }

    /** When the resume point was saved (milliseconds), or 0 if there is none. */
    static long resumeTime(Game g) {
        if (!g.isRom()) return 0;
        Long known = RESUME.get(g.id);
        if (known != null) return known;
        File f = stateFile(g.sysId, g.file, ".auto");
        long t = f.exists() ? f.lastModified() : 0;
        RESUME.put(g.id, t);
        return t;
    }

    /** Reading the shared storage folder needs "all files access" on Android 11 and later. */
    static boolean hasFileAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    static List<Game> load(Context c) {
        SharedPreferences sp = prefs(c);
        List<Game> out = new ArrayList<>();
        out.addAll(scanRoms());
        out.addAll(listApps(c));
        Map<String, Map<String, String>> ownArt = new HashMap<>(), fetchedArt = new HashMap<>();
        Map<String, Set<String>> saves = new HashMap<>();
        for (Game g : out) {
            g.art = findArt(c, g, ownArt, fetchedArt);
            g.lastPlayed = sp.getLong("last:" + g.id, 0);
            if (g.isRom()) {
                Set<String> ss = saves.get(g.sysId);
                if (ss == null) {
                    ss = new HashSet<>();
                    String[] l = new File(Environment.getExternalStorageDirectory(), "Lantern/saves/" + g.sysId).list();
                    if (l != null) ss.addAll(Arrays.asList(l));
                    saves.put(g.sysId, ss);
                }
                g.resume = ss.contains(baseName(g.file) + ".state.auto");
            }
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    private static File timeFile(Context c, String id) {
        File d = new File(c.getFilesDir(), "time");
        d.mkdirs();
        return new File(d, id.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** Total time spent in a game, in milliseconds. Kept in a small file because the player is another process. */
    static long playTime(Context c, String id) {
        Long known = PLAYED.get(id);
        if (known != null) return known;
        long t = readPlayTime(c, id);
        PLAYED.put(id, t);
        return t;
    }

    private static long readPlayTime(Context c, String id) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(timeFile(c, id)))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static void addPlayTime(Context c, String id, long ms) {
        if (ms < 3000) return;
        try (java.io.FileWriter w = new java.io.FileWriter(timeFile(c, id))) {
            w.write(String.valueOf(readPlayTime(c, id) + ms)); // read afresh: this runs in the player process, whose memory is not Home's
        } catch (Exception e) {
            // not worth failing a game over
        }
    }

    /** Takes a game off the Recent list. Its saves and play time are kept; playing it again brings it back. */
    static void clearPlayed(Context c, Game g) {
        g.lastPlayed = 0;
        prefs(c).edit().remove("last:" + g.id).apply();
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
                g.hue = systemHue(s.folder, g.title);
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

    /** Each system has its own colour family; a game varies a little within it, so a shelf of games without art still looks organised. */
    private static int systemHue(String folder, String title) {
        int base = baseHue(folder);
        if (base < 0) return hue(title);
        return (base + (title.hashCode() & 0x7fffffff) % 31 - 15 + 360) % 360;
    }

    /** A system's own colour (a hue, 0-359), or -1 for Android apps and anything without one. */
    static int baseHue(String folder) {
        int base;
        switch (folder) {
            case "nes": base = 0; break;
            case "snes": base = 265; break;
            case "gb": base = 95; break;
            case "gbc": base = 320; break;
            case "mastersystem": base = 205; break;
            case "gamegear": base = 175; break;
            case "gba": base = 245; break;
            case "md": base = 225; break;
            case "n64": base = 140; break;
            case "nds": base = 195; break;
            case "psx": base = 30; break;
            case "psp": base = 285; break;
            case "saturn": base = 345; break;
            case "dc": base = 18; break;
            case "ps2": base = 215; break;
            default: return -1;
        }
        return base;
    }

    private static int hue(String s) {
        return (s.hashCode() & 0x7fffffff) % 360;
    }
}
