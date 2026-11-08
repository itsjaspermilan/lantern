package app.lantern;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * A tile, drawn to fit whatever size the shelf gives it (small when idle, large when focused). It shows
 * one game or one system, and is reused for another when it scrolls out of sight. Without a real cover
 * the art is generated from the game's colours.
 */
final class Cover extends View {
    private static final int AMBER = 0xFFFFB454;

    private Game g;
    private final float s;
    private int c1, c2, lastHue, motif;
    private final RectF artBox = new RectF();
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path shape = new Path();
    private final RectF rect = new RectF();
    private float weight;
    /** The tile's background gradient: built once per size and colour pair, not on every draw. */
    private LinearGradient bg;
    private float bgW, bgH;
    private int bgC1, bgC2;
    private android.graphics.Bitmap shot;
    private float shotA;
    /** Height of the strip under the tile that holds the title, in pixels (0: no title). */
    private float labelPx;

    Cover(Context c, float s) {
        super(c);
        this.s = s;
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setColor(Color.WHITE);
        setFocusable(View.NOT_FOCUSABLE); // never the system's focus: it would draw a pale highlight rectangle
        setDefaultFocusHighlightEnabled(false);
        setAlpha(.6f); // an idle tile is dimmed; setWeight skips work when the weight is unchanged
    }

    Game game() {
        return g;
    }

    /** Points this tile at another game or system. */
    void setGame(Game game) {
        g = game;
        motif = (game.id.hashCode() & 0x7fffffff) % 6;
        setColours();
        invalidate();
    }

    /** The tile's two colours come from the game's hue, which changes once its cover has been read. */
    private void setColours() {
        lastHue = g.hue;
        c1 = Color.HSVToColor(new float[]{g.hue, .55f, .62f});
        c2 = Color.HSVToColor(new float[]{(g.hue + 35) % 360, .60f, .26f});
    }

    private LinearGradient bgShader(float w, float h) {
        if (bg == null || w != bgW || h != bgH || c1 != bgC1 || c2 != bgC2) {
            bg = new LinearGradient(0, 0, w * .45f, h, c1, c2, Shader.TileMode.CLAMP);
            bgW = w;
            bgH = h;
            bgC1 = c1;
            bgC2 = c2;
        }
        return bg;
    }

    /** Makes room for the title under the picture. The picture is drawn in what is left above it. */
    void setLabel(float px) {
        if (px == labelPx) return;
        labelPx = px;
        invalidate();
    }

    /** Recent's big tile shows a screenshot of where the game was left, faded in by a (0 to 1). */
    void setShot(android.graphics.Bitmap b, float a) {
        if (b == shot && Math.abs(a - shotA) < .01f) return;
        shot = b;
        shotA = b == null ? 0f : a;
        invalidate();
    }

    /** Draws a picture over the whole w x h area, cropping the centre to the same shape. */
    private void fillCrop(Canvas c, Bitmap b, float w, float h) {
        int sw = b.getWidth(), sh = b.getHeight();
        float sa = (float) sw / sh, ta = w / h;
        android.graphics.Rect src;
        if (sa > ta) {
            int cw = Math.round(sh * ta);
            src = new android.graphics.Rect((sw - cw) / 2, 0, (sw + cw) / 2, sh);
        } else {
            int ch = Math.round(sw / ta);
            src = new android.graphics.Rect(0, (sh - ch) / 2, sw, (sh + ch) / 2);
        }
        rect.set(0, 0, w, h);
        c.drawBitmap(b, src, rect, artPaint);
    }

    private void drawShot(Canvas c, float w, float h) {
        int sw = shot.getWidth(), sh = shot.getHeight();
        float sa = (float) sw / sh, ta = w / h;
        android.graphics.Rect src;
        if (sa > ta) {
            int cw = Math.round(sh * ta);
            src = new android.graphics.Rect((sw - cw) / 2, 0, (sw + cw) / 2, sh);
        } else {
            int ch = Math.round(sw / ta);
            src = new android.graphics.Rect(0, (sh - ch) / 2, sw, (sh + ch) / 2);
        }
        rect.set(0, 0, w, h);
        artPaint.setAlpha((int) (255 * Math.min(1f, shotA)));
        c.drawBitmap(shot, src, rect, artPaint);
        artPaint.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, h * .5f, 0, h, 0x00000000, Color.argb((int) (0x99 * shotA), 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float cx = w / 2f, cy = h / 2f, rr = Math.min(w, h) * .12f; // a play button: one press and you are back in
        p.setColor(Color.argb((int) (230 * shotA), 255, 255, 255));
        c.drawCircle(cx, cy, rr, p);
        p.setColor(Color.argb((int) (255 * shotA), 0x17, 0x13, 0x1F));
        shape.reset();
        shape.moveTo(cx - rr * .28f, cy - rr * .42f);
        shape.lineTo(cx + rr * .48f, cy);
        shape.lineTo(cx - rr * .28f, cy + rr * .42f);
        shape.close();
        c.drawPath(shape, p);
    }

    /** 0 when idle, 1 when focused. */
    void setWeight(float w) {
        if (w == weight) return;
        weight = w;
        setAlpha(.6f + .4f * w);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight() - labelPx;
        if (g == null || w <= 0 || h <= 0) return;
        // The corners and the ring keep their size on screen when the view is scaled (the details cover flying to or
        // from a tile), so they match the tile at both ends of the trip.
        float vs = Math.max(.05f, getScaleX());
        float r = 18 * s / vs;
        if (g.hue != lastHue) setColours();
        clip.reset();
        clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);

        // A tile with a real cover paints its own background below, so the generated one is only for the others.
        Bitmap art = g.systemTile ? null : ArtCache.get(g, this);
        p.setStyle(Paint.Style.FILL);
        if (art == null) {
            p.setColor(Color.WHITE); // the paint is shared: an old translucent colour would fade the gradient
            p.setShader(bgShader(w, h));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        if (g.systemTile) {
            drawSystem(c, w, h);
            c.restore();
            ring(c, w, h, r);
            drawLabel(c, w, h);
            return;
        }

        c.save();
        c.scale(w / 300f, h / 400f);
        if (weight < .5f && art == null) drawMotif(c); // the selected tile has no pale pattern over it: its ring is enough
        c.restore();

        if (g.icon == null && g.art == null) drawMonogram(c, w, h);
        if (g.icon != null && g.art == null) {
            int sz = Math.round(w * .42f);
            int x = Math.round((w - sz) / 2f), y = Math.round(h * .20f);
            g.icon.setBounds(x, y, x + sz, y + sz);
            g.icon.draw(c);
        }

        // A real cover replaces the generated art and the title. It is fitted inside the tile, never cropped;
        // the rest of the tile is a soft blur of the cover itself, and the cover floats on a shadow.
        if (art != null && (float) art.getWidth() / art.getHeight() >= 1.35f) {
            // Landscape art (a banner, or what Nova Studio makes) fills the whole tile, cropped a little to fit.
            fillCrop(c, art, w, h);
        } else if (art != null) {
            Bitmap back = ArtCache.backdrop(g);
            if (back != null) {
                rect.set(0, 0, w, h);
                c.drawBitmap(back, null, rect, artPaint);
                c.drawColor(0x66000000); // dim it so the sharp cover stands out
            } else {
                p.setColor(Color.WHITE);
                p.setShader(bgShader(w, h));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
            }
            float pad = (8 - 4 * weight) * s / vs; // the box art uses nearly the whole height of the tile; the focused one rises a little
            float k = Math.min((w - 2 * pad) / art.getWidth(), (h - 2 * pad) / art.getHeight());
            float dw = art.getWidth() * k, dh = art.getHeight() * k;
            artBox.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            p.setStyle(Paint.Style.FILL);
            for (int i = 4; i >= 1; i--) { // a soft shadow from a few widening layers
                float grow = i * 4 * s * (1 + weight * .6f) / vs; // like the ring: the same size on screen however the view is scaled
                rect.set(artBox.left - grow, artBox.top - grow + 6 * s / vs, artBox.right + grow, artBox.bottom + grow + 6 * s / vs);
                p.setColor(((int) (0x1C * (.7f + .5f * weight)) << 24));
                c.drawRoundRect(rect, 8 * s / vs + grow, 8 * s / vs + grow, p);
            }
            c.drawBitmap(art, null, artBox, artPaint);
        }

        if (shot != null && shotA > .01f) drawShot(c, w, h);
        if (g.resume && shotA < .5f) { // a small amber play button: this game has a resume point
            float rr = 15 * s / vs, cx = 28 * s / vs, cy = 28 * s / vs;
            p.setStyle(Paint.Style.FILL);
            p.setColor(AMBER);
            c.drawCircle(cx, cy, rr, p);
            p.setColor(0xFF17131F);
            shape.reset();
            shape.moveTo(cx - rr * .28f, cy - rr * .5f);
            shape.lineTo(cx + rr * .52f, cy);
            shape.lineTo(cx - rr * .28f, cy + rr * .5f);
            shape.close();
            c.drawPath(shape, p);
        }
        c.restore();
        ring(c, w, h, r);
        drawLabel(c, w, h);
    }

    /** A game with no cover gets its initials, large and pale, in its system's colours. */
    private void drawMonogram(Canvas c, float w, float h) {
        String[] words = g.title.trim().split("[\\s:\\-]+");
        StringBuilder b = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty() || !Character.isLetterOrDigit(word.charAt(0))) continue;
            b.append(Character.toUpperCase(word.charAt(0)));
            if (b.length() == 2) break;
        }
        if (b.length() == 0) return;
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(Math.min(h * .34f, w * .5f));
        tp.setColor(0x66FFFFFF);
        String m = b.toString();
        float tw = tp.measureText(m);
        c.drawText(m, (w - tw) / 2f, h * .5f + tp.getTextSize() * .35f, tp);
        tp.setColor(Color.WHITE);
    }

    /** The title under the picture, on one line. */
    private void drawLabel(Canvas c, float w, float artH) {
        if (labelPx <= 0 || g == null) return;
        tp.setTextSize(Math.min(labelPx * .62f, 26 * s));
        tp.setColor(Color.WHITE);
        CharSequence t = TextUtils.ellipsize(g.title, tp, Math.max(1f, w - 8 * s), TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), 4 * s, artH + labelPx * .72f, tp);
    }

    /** A system's tile: its picture on the colour. The name and the count are shown below the shelf, not repeated here. */
    private void drawSystem(Canvas c, float w, float h) {
        float cx = w / 2f, cy = h / 2f;
        p.setStyle(Paint.Style.FILL);
        if ("all".equals(g.sysId)) { // the "All games" tile: Kenney's grid icon
            Bitmap grid = UiIcons.get(getContext(), "menu_grid");
            if (grid != null) {
                float half = Math.min(w, h) * .27f;
                rect.set(cx - half, cy - half, cx + half, cy + half);
                p.setAlpha(235);
                c.drawBitmap(grid, null, rect, artPaint);
                p.setAlpha(255);
            }
            return;
        }
        SystemArt.draw(getContext(), c, p, g.sysId, cx, cy, Math.min(w, h) * .62f);
    }

    /** The white ring around the focused tile. */
    private void ring(Canvas c, float w, float h, float r) {
        if (weight <= .02f) return;
        p.setStyle(Paint.Style.STROKE);
        float k = Math.max(.05f, getScaleX());
        p.setStrokeWidth(6 * s / k);
        p.setColor(Color.argb((int) (255 * weight), 255, 255, 255));
        c.drawRoundRect(3 * s / k, 3 * s / k, w - 3 * s / k, h - 3 * s / k, r, r, p);
    }

    /** Six simple shapes, one per game, drawn on a 300 x 400 sheet. */
    private void drawMotif(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        int light = 0x29FFFFFF, dark = 0x24000000;
        switch (motif) {
            case 0: // rings
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(7);
                p.setColor(0x38FFFFFF);
                c.drawCircle(150, 150, 46, p);
                c.drawCircle(150, 150, 92, p);
                c.drawCircle(150, 150, 138, p);
                break;
            case 1: // stripes
                p.setColor(light);
                poly(c, -20, 300, 140, -20, 190, -20, 30, 300);
                poly(c, 80, 340, 240, 20, 290, 20, 130, 340);
                poly(c, 170, 380, 330, 60, 380, 60, 220, 380);
                break;
            case 2: // grid
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                p.setColor(0x1FFFFFFF);
                for (int i = 1; i < 8; i++) {
                    c.drawLine(i * 40, 0, i * 40, 400, p);
                    c.drawLine(0, i * 50, 300, i * 50, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(light);
                c.drawRect(70, 80, 180, 190, p);
                break;
            case 3: // sun
                p.setColor(light);
                c.drawCircle(150, 160, 82, p);
                p.setColor(dark);
                c.drawRect(0, 230, 300, 400, p);
                break;
            case 4: // triangles
                p.setColor(light);
                poly(c, 150, 40, 270, 250, 30, 250);
                p.setColor(dark);
                poly(c, 150, 110, 220, 240, 80, 240);
                break;
            default: // waves
                p.setColor(light);
                wave(c, 180);
                p.setColor(dark);
                wave(c, 250);
                break;
        }
    }

    private void poly(Canvas c, float... pts) {
        shape.reset();
        shape.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) shape.lineTo(pts[i], pts[i + 1]);
        shape.close();
        c.drawPath(shape, p);
    }

    private void wave(Canvas c, float y) {
        shape.reset();
        shape.moveTo(0, y);
        shape.quadTo(75, y - 50, 150, y);
        shape.quadTo(225, y + 50, 300, y);
        shape.lineTo(300, 400);
        shape.lineTo(0, 400);
        shape.close();
        c.drawPath(shape, p);
    }
}
