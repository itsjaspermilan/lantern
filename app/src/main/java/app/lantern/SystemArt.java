package app.lantern;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The picture on a system's tile: a simple line drawing of a typical controller or handheld for that system,
 * drawn with paths so it needs no files and stays sharp at any size. Original shapes, not copies of any logo.
 */
final class SystemArt {
    private SystemArt() {}

    private static final int FILL = 0x2EFFFFFF, LINE = 0xB3FFFFFF, DETAIL = 0xCCFFFFFF, SCREEN = 0x55FFFFFF;
    private static final RectF R = new RectF();

    private static final java.util.Map<String, Bitmap> ICONS = new java.util.HashMap<>();
    private static final Paint ICON_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final RectF DST = new RectF();

    /**
     * The system's icon from assets/system-icons (libretro's retroarch-assets, CC BY 4.0, see licenses/), or null
     * for systems that have none (Android apps and games), which fall back to the drawn shapes below.
     */
    private static Bitmap icon(Context ctx, String sysId) {
        synchronized (ICONS) {
            if (ICONS.containsKey(sysId)) return ICONS.get(sysId);
            Bitmap b = null;
            try (java.io.InputStream in = ctx.getAssets().open("system-icons/" + sysId + ".png")) {
                b = BitmapFactory.decodeStream(in);
            } catch (java.io.IOException e) {
                // no icon for this system
            }
            ICONS.put(sysId, b);
            return b;
        }
    }

    /** Draws the art for a system, centred at (cx, cy), about `size` pixels across. */
    static void draw(Context ctx, Canvas c, Paint p, String sysId, float cx, float cy, float size) {
        if (Library.GAMES.equals(sysId)) { // Google's Material "android" icon
            int id = ctx.getResources().getIdentifier("ic_android", "drawable", ctx.getPackageName());
            android.graphics.drawable.Drawable d = id == 0 ? null : ctx.getResources().getDrawable(id, null).mutate();
            if (d != null) {
                float half = size * .62f;
                d.setTint(0xEBFFFFFF);
                d.setBounds(Math.round(cx - half), Math.round(cy - half), Math.round(cx + half), Math.round(cy + half));
                d.draw(c);
                return;
            }
        }
        Bitmap icon = icon(ctx, sysId);
        if (icon != null) {
            float half = size * .62f; // the pictures have a margin of their own
            DST.set(cx - half, cy - half, cx + half, cy + half);
            ICON_PAINT.setAlpha(235);
            c.drawBitmap(icon, null, DST, ICON_PAINT);
            return;
        }
        c.save();
        c.translate(cx, cy);
        float k = size / 100f;
        c.scale(k, k);
        p.setShader(null);
        switch (sysId) {
            case "nes": nes(c, p); break;
            case "snes": snes(c, p); break;
            case "md": megaDrive(c, p, false); break;
            case "saturn": megaDrive(c, p, true); break;
            case "mastersystem": masterSystem(c, p); break;
            case "n64": n64(c, p); break;
            case "psx":
            case "ps2":
            case "dc": playStation(c, p); break;
            case "gb":
            case "gbc": gameBoy(c, p); break;
            case "gba": landscape(c, p, 48, 24, 14, 24, 17, 7, 5f); break;
            case "gamegear": landscape(c, p, 48, 26, 10, 30, 18, 6, 5f); break;
            case "psp": landscape(c, p, 50, 22, 16, 28, 16, 6, 3.5f); break;
            case "nds": clamshell(c, p); break;
            case Library.GAMES: nes(c, p); break;
            case Library.APPS: phone(c, p); break;
            default: nes(c, p);
        }
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    // ---- parts ----

    private static void body(Canvas c, Paint p, float l, float t, float r, float b, float rad) {
        R.set(l, t, r, b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(FILL);
        c.drawRoundRect(R, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.6f);
        p.setColor(LINE);
        c.drawRoundRect(R, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    private static void block(Canvas c, Paint p, float l, float t, float r, float b, float rad, int color) {
        R.set(l, t, r, b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(R, rad, rad, p);
    }

    private static void dpad(Canvas c, Paint p, float x, float y, float s) {
        float a = s * .72f;
        block(c, p, x - s, y - a / 2, x + s, y + a / 2, 1.5f, DETAIL);
        block(c, p, x - a / 2, y - s, x + a / 2, y + s, 1.5f, DETAIL);
    }

    private static void dot(Canvas c, Paint p, float x, float y, float r) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(DETAIL);
        c.drawCircle(x, y, r, p);
    }

    private static void pill(Canvas c, Paint p, float x, float y, float w) {
        block(c, p, x - w / 2, y - 1.6f, x + w / 2, y + 1.6f, 1.6f, DETAIL);
    }

    // ---- controllers ----

    private static void nes(Canvas c, Paint p) {
        body(c, p, -46, -17, 46, 17, 5);
        dpad(c, p, -28, 0, 8);
        pill(c, p, -4, 6, 9);
        pill(c, p, 10, 6, 9);
        dot(c, p, 27, 3, 5.5f);
        dot(c, p, 40, 3, 5.5f);
    }

    private static void snes(Canvas c, Paint p) {
        block(c, p, -42, -27, -22, -19, 3, LINE); // shoulder buttons
        block(c, p, 22, -27, 42, -19, 3, LINE);
        body(c, p, -46, -21, 46, 21, 15);
        dpad(c, p, -26, 0, 9);
        pill(c, p, -6, 7, 8);
        pill(c, p, 6, 7, 8);
        float bx = 27, by = 0, o = 8.5f;
        dot(c, p, bx, by - o, 4.6f);
        dot(c, p, bx, by + o, 4.6f);
        dot(c, p, bx - o, by, 4.6f);
        dot(c, p, bx + o, by, 4.6f);
    }

    private static void megaDrive(Canvas c, Paint p, boolean six) {
        body(c, p, -48, -17, 48, 17, 17);
        dpad(c, p, -29, 0, 8.5f);
        if (six) {
            for (int i = 0; i < 3; i++) {
                dot(c, p, 8 + i * 12, -5, 3.8f);
                dot(c, p, 8 + i * 12, 6, 3.8f);
            }
        } else {
            dot(c, p, 8, 4, 5);
            dot(c, p, 23, 0, 5);
            dot(c, p, 38, -4, 5);
        }
        pill(c, p, -2, 11, 8);
    }

    private static void masterSystem(Canvas c, Paint p) {
        body(c, p, -34, -22, 34, 22, 6);
        dpad(c, p, -17, 0, 9);
        dot(c, p, 12, 5, 6);
        dot(c, p, 25, 5, 6);
        pill(c, p, 0, -14, 14);
    }

    private static void n64(Canvas c, Paint p) {
        body(c, p, -46, -22, 46, -8, 6);
        body(c, p, -46, -10, -28, 34, 9); // left grip
        body(c, p, 28, -10, 46, 34, 9);   // right grip
        body(c, p, -8, -10, 8, 40, 7);    // middle grip
        dpad(c, p, -37, -17, 5);
        dot(c, p, 0, -3, 4.5f);
        dot(c, p, 37, -16, 3.4f);
        dot(c, p, 30, -13, 3.4f);
    }

    private static void playStation(Canvas c, Paint p) {
        body(c, p, -46, -22, -22, 34, 11); // left grip
        body(c, p, 22, -22, 46, 34, 11);   // right grip
        body(c, p, -40, -23, 40, 14, 14);
        dpad(c, p, -26, -5, 8);
        float bx = 26, by = -5, o = 8.5f;
        dot(c, p, bx, by - o, 4f);
        dot(c, p, bx, by + o, 4f);
        dot(c, p, bx - o, by, 4f);
        dot(c, p, bx + o, by, 4f);
        pill(c, p, -4, 3, 6);
        pill(c, p, 6, 3, 6);
    }

    // ---- handhelds ----

    private static void gameBoy(Canvas c, Paint p) {
        body(c, p, -27, -46, 27, 46, 9);
        block(c, p, -20, -38, 20, -4, 3, SCREEN);
        dpad(c, p, -13, 17, 7);
        dot(c, p, 12, 14, 5);
        dot(c, p, 4, 22, 5);
        pill(c, p, -9, 37, 9);
        pill(c, p, 5, 37, 9);
    }

    /** A wide handheld: body half-width/height, screen half-width/height, d-pad size, button radius. */
    private static void landscape(Canvas c, Paint p, float hw, float hh, float rad, float sw, float sh, float dp, float br) {
        body(c, p, -hw, -hh, hw, hh, rad);
        block(c, p, -sw, -sh, sw, sh, 3, SCREEN);
        float lx = -(hw + sw) / 2f, rx = (hw + sw) / 2f;
        dpad(c, p, lx, 0, dp);
        dot(c, p, rx + 1, -br * .9f, br);
        dot(c, p, rx - br * 1.4f, br * .9f, br);
    }

    private static void clamshell(Canvas c, Paint p) {
        body(c, p, -30, -48, 30, -4, 7);
        block(c, p, -23, -42, 23, -10, 2, SCREEN);
        body(c, p, -30, 0, 30, 44, 7);
        block(c, p, -17, 5, 17, 33, 2, SCREEN);
        dpad(c, p, -25, 38, 3.2f);
        dot(c, p, 25, 38, 2.6f);
    }

    private static void phone(Canvas c, Paint p) {
        body(c, p, -25, -45, 25, 45, 10);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float x = -15 + col * 15, y = -26 + row * 15;
                block(c, p, x - 5.5f, y - 5.5f, x + 5.5f, y + 5.5f, 3, DETAIL);
            }
        }
        pill(c, p, 0, 36, 14);
    }
}
