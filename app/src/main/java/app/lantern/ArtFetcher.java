package app.lantern;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds cover art for ROMs. Order: a picture the person put in Lantern/art/&lt;system&gt;/, then the cache, then
 * (only when switched on) the libretro thumbnail server, which is what RetroArch uses. Matching is by file
 * name, so it works best with No-Intro style names such as "Advance Wars (USA).gba".
 *
 * One request at a time, a few seconds apart in practice, and it stops after three network failures in a
 * row. A game the server does not have is remembered for a week so it is not asked again on every start.
 */
final class ArtFetcher {
    interface Listener {
        /** Called on the main thread when a game has new art. */
        void onArt(Game g);
    }

    private static final String DEFAULT_BASE = "https://thumbnails.libretro.com/";
    private static final long MISS_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean running;
    private volatile boolean cancelled;

    ArtFetcher(Context c) {
        ctx = c.getApplicationContext();
    }

    // ---- where things are ----

    /** The libretro thumbnail folder name for a system, or null when there is none. */
    static String systemName(Game g) {
        return systemName(g.sysId, g.file);
    }

    static String systemName(String sysId, java.io.File file) {
        switch (sysId == null ? "" : sysId) {
            case "nes": return "Nintendo - Nintendo Entertainment System";
            case "snes": return "Nintendo - Super Nintendo Entertainment System";
            case "gb": return file.getName().toLowerCase().endsWith(".gbc") ? "Nintendo - Game Boy Color" : "Nintendo - Game Boy";
            case "gba": return "Nintendo - Game Boy Advance";
            case "gbc": return "Nintendo - Game Boy Color";
            case "mastersystem": return "Sega - Master System - Mark III";
            case "gamegear": return "Sega - Game Gear";
            case "md": return "Sega - Mega Drive - Genesis";
            case "n64": return "Nintendo - Nintendo 64";
            case "nds": return "Nintendo - Nintendo DS";
            case "gc": return "Nintendo - GameCube";
            case "psx": return "Sony - PlayStation";
            case "psp": return "Sony - PlayStation Portable";
            case "saturn": return "Sega - Saturn";
            case "dc": return "Sega - Dreamcast";
            case "ps2": return "Sony - PlayStation 2";
            default: return null;
        }
    }

    /** Fetched pictures live in the app's private folder. */
    static File cacheFile(Context c, Game g) {
        return new File(c.getFilesDir(), "art/" + g.sysId + "/" + Library.baseName(g.file) + ".png");
    }

    private static File missFile(Context c, Game g) {
        return new File(c.getFilesDir(), "art/" + g.sysId + "/" + Library.baseName(g.file) + ".miss");
    }

    /**
     * Where requests go. Normally the libretro server. A first line in Lantern/art-source.txt replaces it,
     * for a mirror or for testing against a local server.
     */
    static String baseUrl() {
        File f = new File(Environment.getExternalStorageDirectory(), "Lantern/art-source.txt");
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line != null && line.trim().startsWith("http")) {
                    String u = line.trim();
                    return u.endsWith("/") ? u : u + "/";
                }
            } catch (java.io.IOException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_BASE;
    }

    /** libretro-thumbnails replaces these characters in names with an underscore. */
    static String thumbName(String name) {
        return name.replaceAll("[&*/:`<>?\\\\|]", "_");
    }

    static String urlFor(String base, Game g) {
        return base + Uri.encode(systemName(g)) + "/Named_Boxarts/" + Uri.encode(thumbName(Library.baseName(g.file))) + ".png";
    }

    // ---- fetching ----

    void cancel() {
        cancelled = true;
    }

    /** Fetches art for every ROM that has none and was not recently missing. Does nothing if already running. */
    synchronized void fetch(List<Game> games, final Listener l) {
        if (running) return;
        running = true;
        cancelled = false;
        final List<Game> snapshot = new ArrayList<>(games);
        worker.execute(() -> {
            // Working out what still needs a picture looks at a file per game: that is for this thread, not the main one.
            final List<Game> todo = new ArrayList<>();
            for (Game g : snapshot) {
                if (cancelled) break;
                if (!g.isRom() || g.art != null || systemName(g) == null) continue;
                File miss = missFile(ctx, g);
                if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_MS) continue;
                todo.add(g);
            }
            String base = todo.isEmpty() ? null : baseUrl();
            int failures = 0;
            for (final Game g : todo) {
                if (cancelled || failures >= 3 || base == null) break;
                switch (fetchOne(base, g)) {
                    case 1:
                        failures = 0;
                        main.post(() -> l.onArt(g));
                        break;
                    case 0:
                        failures = 0;
                        break;
                    default:
                        failures++;
                }
            }
            synchronized (ArtFetcher.this) {
                running = false;
            }
        });
    }

    /** 1 got a picture, 0 the server does not have it, -1 a network or format problem (try again later). */
    private int fetchOne(String base, Game g) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlFor(base, g)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Lantern/0.1 (personal launcher)");
            int code = c.getResponseCode();
            if (code == 404 || code == 403) {
                File miss = missFile(ctx, g);
                miss.getParentFile().mkdirs();
                new FileOutputStream(miss).close();
                miss.setLastModified(System.currentTimeMillis());
                return 0;
            }
            if (code != 200) return -1;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            try (InputStream in = c.getInputStream()) {
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_BYTES) return -1; // far bigger than any cover; refuse it
                }
            }
            byte[] data = buf.toByteArray();
            if (!isPng(data)) return -1;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return -1;

            File out = cacheFile(ctx, g), tmp = new File(out.getPath() + ".tmp");
            out.getParentFile().mkdirs();
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                f.write(data);
            }
            if (!tmp.renameTo(out)) { tmp.delete(); return -1; }
            g.art = out;
            return 1;
        } catch (java.io.IOException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }
}
