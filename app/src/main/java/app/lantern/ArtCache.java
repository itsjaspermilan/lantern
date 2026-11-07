package app.lantern;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;

import java.io.File;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decoded cover pictures, kept in memory while they are needed. A picture is decoded off the main thread
 * the first time a tile wants it, scaled down so a tile never holds more pixels than it can show, and the
 * game's colour (hue) is taken from it for the glow and the tile background.
 */
final class ArtCache {
    private ArtCache() {}

    /** Called on the main thread after a game's picture has been decoded. */
    interface Listener {
        void onDecoded(Game g);
    }

    private static final int TARGET_HEIGHT = 520; // pixels: a little more than the tallest a cover is ever drawn (Recent's big tile)
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Set<String> LOADING = new HashSet<>();
    private static final java.util.HashMap<String, java.util.List<View>> WAITERS = new java.util.HashMap<>();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    /** Tiny blurred copies of the covers (a few KB each): the soft backdrop behind a cover fitted into a tile. */
    private static final LruCache<String, Bitmap> BACKDROPS = new LruCache<>(256);
    private static Listener listener;

    /**
     * The cache key for a game's cover. It includes the file's modified time (so a replaced picture is not served from
     * the cache), which costs a disk call, so it is worked out once per file and kept on the game, not on every draw.
     */
    private static String key(Game g) {
        File f = g.art;
        if (f == null) return null;
        if (g.artKeyFile != f || g.artKey == null) {
            g.artKey = f.getPath() + "@" + f.lastModified();
            g.artKeyFile = f;
        }
        return g.artKey;
    }

    /** The blurred backdrop for a game's cover if it has been made (it is made together with the cover), else null. */
    static Bitmap backdrop(Game g) {
        return g.art == null ? null : BACKDROPS.get(key(g));
    }

    private static final LruCache<String, Bitmap> SHOTS = new LruCache<>(6);
    private static final java.util.Map<String, java.io.File> SHOT_FILES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Screenshots being read, and ones known not to exist (until the next clearShots). */
    private static final Set<String> SHOT_BUSY = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> SHOT_NONE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The screenshot saved with a game's resume point, or null if it is not ready (or there is none). It is read off the
     * main thread; when it arrives, ready runs on the main thread so the caller can draw it.
     */
    static Bitmap shot(final Game g, final Runnable ready) {
        if (g == null || !g.isRom() || !g.resume) return null;
        Bitmap b = SHOTS.get(g.id);
        if (b != null) return b;
        if (SHOT_NONE.contains(g.id) || !SHOT_BUSY.add(g.id)) return null;
        POOL.execute(() -> {
            java.io.File f = SHOT_FILES.get(g.id);
            if (f == null) {
                f = new java.io.File(Library.stateFile(g.sysId, g.file, ".auto").getPath() + ".png");
                SHOT_FILES.put(g.id, f);
            }
            Bitmap d = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (d != null) SHOTS.put(g.id, d); else SHOT_NONE.add(g.id);
            SHOT_BUSY.remove(g.id);
            if (d != null) MAIN.post(ready);
        });
        return null;
    }

    /** Forget the screenshots: a game may have saved a new resume point. */
    static void clearShots() {
        SHOTS.evictAll();
        SHOT_NONE.clear();
    }

    static void setListener(Listener l) {
        listener = l;
    }

    /** The picture if it is ready; otherwise null, and it starts loading and redraws the view when done. */
    static Bitmap get(final Game g, final View redraw) {
        if (g.art == null) return null;
        final String key = key(g);
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        synchronized (LOADING) {
            if (!LOADING.add(key)) { // already loading for another tile: this one is redrawn too when it lands
                java.util.List<View> w = WAITERS.get(key);
                if (w == null) { w = new java.util.ArrayList<>(); WAITERS.put(key, w); }
                if (!w.contains(redraw)) w.add(redraw);
                return null;
            }
        }
        POOL.execute(() -> {
            Bitmap decoded = decode(g, key);
            if (decoded != null) CACHE.put(key, decoded);
            final java.util.List<View> waiting;
            synchronized (LOADING) {
                LOADING.remove(key);
                waiting = WAITERS.remove(key);
            }
            MAIN.post(() -> {
                if (decoded != null && listener != null) listener.onDecoded(g);
                redraw.invalidate();
                if (waiting != null) for (View v : waiting) v.invalidate();
            });
        });
        return null;
    }

    private static Bitmap decode(Game g, String key) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(g.art.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outHeight / (sample * 2) >= TARGET_HEIGHT && o.outWidth / (sample * 2) >= TARGET_HEIGHT / 2) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(g.art.getPath(), o);
        if (b != null) {
            g.hue = dominantHue(b, g.hue);
            BACKDROPS.put(key, blurred(b));
        }
        return b;
    }

    /**
     * A very small, soft copy of the picture, cropped to the shape of a tile. Drawn stretched over the whole
     * tile, bilinear filtering turns the few pixels into a smooth blur of the cover's own colours.
     */
    private static Bitmap blurred(Bitmap b) {
        float aspect = 1.65f; // a tile is a landscape rectangle, about 330 x 200
        int cw = b.getWidth(), ch = Math.round(cw / aspect);
        if (ch > b.getHeight()) { ch = b.getHeight(); cw = Math.round(ch * aspect); }
        Bitmap cur = Bitmap.createBitmap(b, (b.getWidth() - cw) / 2, (b.getHeight() - ch) / 2, cw, ch);
        // Halve repeatedly: one big step would skip pixels and shimmer instead of blurring.
        while (cur.getHeight() > 18) {
            Bitmap next = Bitmap.createScaledBitmap(cur, Math.max(2, cur.getWidth() / 2), Math.max(2, cur.getHeight() / 2), true);
            if (cur != b) cur.recycle();
            cur = next;
        }
        return cur;
    }

    /** The most colourful hue in the picture, weighted by saturation and brightness, or fallback for grey art. */
    private static int dominantHue(Bitmap b, int fallback) {
        Bitmap small = Bitmap.createScaledBitmap(b, 24, 24, true);
        double x = 0, y = 0, weight = 0;
        float[] hsv = new float[3];
        for (int py = 0; py < 24; py++) {
            for (int px = 0; px < 24; px++) {
                Color.colorToHSV(small.getPixel(px, py), hsv);
                double w = hsv[1] * hsv[2] * hsv[1]; // vivid pixels count most
                double a = Math.toRadians(hsv[0]);
                x += Math.cos(a) * w;
                y += Math.sin(a) * w;
                weight += w;
            }
        }
        if (small != b) small.recycle();
        if (weight < 0.5) return fallback; // nearly grey: keep the old colour
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (int) ((deg + 360) % 360);
    }
}
