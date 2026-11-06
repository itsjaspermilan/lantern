package app.lantern;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;

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

    private static final int TARGET_HEIGHT = 700; // pixels; the largest a focused tile can be is about this
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Set<String> LOADING = new HashSet<>();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    /** Tiny blurred copies of the covers (a few KB each): the soft backdrop behind a cover fitted into a tile. */
    private static final LruCache<String, Bitmap> BACKDROPS = new LruCache<>(256);
    private static Listener listener;

    private static String key(Game g) {
        return g.art.getPath() + "@" + g.art.lastModified();
    }

    /** The blurred backdrop for a game's cover if it has been made (it is made together with the cover), else null. */
    static Bitmap backdrop(Game g) {
        return g.art == null ? null : BACKDROPS.get(key(g));
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
            if (!LOADING.add(key)) return null;
        }
        POOL.execute(() -> {
            Bitmap decoded = decode(g);
            if (decoded != null) CACHE.put(key, decoded);
            synchronized (LOADING) {
                LOADING.remove(key);
            }
            MAIN.post(() -> {
                if (decoded != null && listener != null) listener.onDecoded(g);
                redraw.invalidate();
            });
        });
        return null;
    }

    private static Bitmap decode(Game g) {
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
            BACKDROPS.put(key(g), blurred(b));
        }
        return b;
    }

    /**
     * A very small, soft copy of the picture, cropped to the shape of a tile. Drawn stretched over the whole
     * tile, bilinear filtering turns the few pixels into a smooth blur of the cover's own colours.
     */
    private static Bitmap blurred(Bitmap b) {
        float aspect = 0.72f; // a tile is about 400 x 555
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
